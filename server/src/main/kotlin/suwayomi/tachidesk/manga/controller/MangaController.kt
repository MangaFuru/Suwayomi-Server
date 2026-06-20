package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpCode
import io.javalin.http.Context
import io.javalin.http.NotFoundResponse
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.kodein.di.DI
import org.kodein.di.conf.global
import org.kodein.di.instance
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.MangaList
import suwayomi.tachidesk.manga.impl.Page
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReadyByIndex
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.dataclass.PageImageResourceDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.Page as SourcePage
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.util.chapter.ChapterSanitizer.sanitize
import okhttp3.Cookie
import okhttp3.HttpUrl
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import uy.kohesive.injekt.injectLazy
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.formParam
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

object MangaController {
    private val json by DI.global.instance<Json>()
    private val goMangaChapterCache = ConcurrentHashMap<Int, Map<Int, GoMangaChapterDataClass>>()

    // In-memory LRU cover cache keyed by DB mangaId. The slim iOS embedded server
    // CANNOT disk-cache covers: getImageResponse's write to tempThumbnailCacheRoot
    // 500s on the sandboxed iOS filesystem (v0.5.24 regression). So spare the catalog
    // grid a per-cell CDN re-fetch with a bounded in-memory cache instead. Covers are
    // small (~30-50 KB); 256 entries ~= 8-13 MB, evicted least-recently-used.
    private val goMangaCoverCache: MutableMap<Int, Pair<ByteArray, String>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<Int, Pair<ByteArray, String>>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Int, Pair<ByteArray, String>>,
                ): Boolean = size > 256
            },
        )

    data class GoMangaMangaDataClass(
        val id: Int,
        val sourceId: String,
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val artist: String?,
        val author: String?,
        val description: String?,
        val genre: List<String>,
        val status: String,
        val inLibrary: Boolean,
        val realUrl: String?,
        val lastFetchedAt: Long?,
        val chaptersLastFetchedAt: Long?,
        val chapterCount: Long?,
    )

    data class GoMangaChapterDataClass(
        val id: Int,
        val url: String,
        val name: String,
        val uploadDate: Long,
        val chapterNumber: Float,
        val scanlator: String?,
        val mangaId: Int,
        val read: Boolean,
        val bookmarked: Boolean,
        val lastPageRead: Int,
        val lastReadAt: Long,
        val index: Int,
        val fetchedAt: Long,
        val realUrl: String?,
        val downloaded: Boolean,
        val pageCount: Int,
        val chapterCount: Int?,
    )

    private fun slimMode(): Boolean = System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true"

    private fun String?.toGoMangaGenreList(): List<String> =
        this
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private suspend fun getGoMangaManga(
        mangaId: Int,
        onlineFetch: Boolean,
    ): GoMangaMangaDataClass {
        val initialized =
            transaction {
                MangaTable.select { MangaTable.id eq mangaId }
                    .first()[MangaTable.initialized]
            }
        if (onlineFetch || !initialized) {
            Manga.getManga(mangaId, onlineFetch)
        }
        return transaction {
            val row = MangaTable.select { MangaTable.id eq mangaId }.first()
            val chapterCount = ChapterTable.select { ChapterTable.manga eq mangaId }.count()
            GoMangaMangaDataClass(
                id = mangaId,
                sourceId = row[MangaTable.sourceReference].toString(),
                url = row[MangaTable.url],
                title = row[MangaTable.title],
                thumbnailUrl = MangaList.proxyThumbnailUrl(mangaId),
                artist = row[MangaTable.artist],
                author = row[MangaTable.author],
                description = row[MangaTable.description],
                genre = row[MangaTable.genre].toGoMangaGenreList(),
                status = MangaStatus.valueOf(row[MangaTable.status]).name,
                inLibrary = row[MangaTable.inLibrary],
                realUrl = row[MangaTable.realUrl],
                lastFetchedAt = row[MangaTable.lastFetchedAt],
                chaptersLastFetchedAt = row[MangaTable.chaptersLastFetchedAt],
                chapterCount = chapterCount,
            )
        }
    }

    private fun ResultRow.toGoMangaChapter(chapterCount: Int?): GoMangaChapterDataClass =
        GoMangaChapterDataClass(
            id = this[ChapterTable.id].value,
            url = this[ChapterTable.url],
            name = this[ChapterTable.name],
            uploadDate = this[ChapterTable.date_upload],
            chapterNumber = this[ChapterTable.chapter_number],
            scanlator = this[ChapterTable.scanlator],
            mangaId = this[ChapterTable.manga].value,
            read = this[ChapterTable.isRead],
            bookmarked = this[ChapterTable.isBookmarked],
            lastPageRead = this[ChapterTable.lastPageRead],
            lastReadAt = this[ChapterTable.lastReadAt],
            index = this[ChapterTable.sourceOrder],
            fetchedAt = this[ChapterTable.fetchedAt],
            realUrl = this[ChapterTable.realUrl],
            downloaded = this[ChapterTable.isDownloaded],
            pageCount = this[ChapterTable.pageCount],
            chapterCount = chapterCount,
        )

    private fun ChapterDataClass.toGoMangaChapter(): GoMangaChapterDataClass =
        GoMangaChapterDataClass(
            id = id,
            url = url,
            name = name,
            uploadDate = uploadDate,
            chapterNumber = chapterNumber,
            scanlator = scanlator,
            mangaId = mangaId,
            read = read,
            bookmarked = bookmarked,
            lastPageRead = lastPageRead,
            lastReadAt = lastReadAt,
            index = index,
            fetchedAt = fetchedAt,
            realUrl = realUrl,
            downloaded = downloaded,
            pageCount = pageCount,
            chapterCount = chapterCount,
        )

    private fun storeGoMangaChapterList(
        mangaId: Int,
        chapters: List<GoMangaChapterDataClass>,
    ): List<GoMangaChapterDataClass> {
        goMangaChapterCache[mangaId] = chapters.associateBy { it.index }
        return chapters
    }

    private fun storeGoMangaChapter(
        mangaId: Int,
        chapter: GoMangaChapterDataClass,
    ): GoMangaChapterDataClass {
        val cached = goMangaChapterCache[mangaId].orEmpty().toMutableMap()
        val existing = cached[chapter.index]
        cached[chapter.index] =
            if (existing != null && existing.pageCount > 0 && chapter.pageCount <= 0) {
                existing
            } else {
                chapter
            }
        goMangaChapterCache[mangaId] = cached
        return cached.getValue(chapter.index)
    }

    private suspend fun getGoMangaChapterList(
        mangaId: Int,
        onlineFetch: Boolean,
    ): List<GoMangaChapterDataClass> {
        if (onlineFetch) {
            return storeGoMangaChapterList(
                mangaId,
                Chapter.getChapterList(mangaId, onlineFetch = true)
                    .map { it.toGoMangaChapter() },
            )
        }

        val cached =
            transaction {
                val rows =
                    ChapterTable.select { ChapterTable.manga eq mangaId }
                        .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                        .toList()
                val chapterCount = rows.size
                rows.map { it.toGoMangaChapter(chapterCount) }
            }
        if (cached.isNotEmpty()) {
            return storeGoMangaChapterList(mangaId, cached)
        }
        return storeGoMangaChapterList(
            mangaId,
            Chapter.getChapterList(mangaId, onlineFetch = false)
                .map { it.toGoMangaChapter() },
        )
    }

    private fun getCachedGoMangaChapter(
        mangaId: Int,
        chapterIndex: Int,
    ): GoMangaChapterDataClass? {
        goMangaChapterCache[mangaId]?.get(chapterIndex)?.let { return it }

        val cached =
            transaction {
                ChapterTable.select {
                    (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex)
                }
                    .firstOrNull()
                    ?.toGoMangaChapter(chapterCount = null)
            }
        return cached?.let { storeGoMangaChapter(mangaId, it) }
    }

    private suspend fun getOnlineGoMangaChapter(
        mangaId: Int,
        chapterIndex: Int,
    ): GoMangaChapterDataClass =
        storeGoMangaChapter(
            mangaId,
            getChapterDownloadReadyByIndex(chapterIndex, mangaId).toGoMangaChapter(),
        )

    private fun imageBytes(
        ctx: Context,
        image: Pair<InputStream, String>,
    ): InputStream {
        ctx.header("content-type", image.second)
        val httpCacheSeconds = 1.days.inWholeSeconds
        ctx.header("cache-control", "max-age=$httpCacheSeconds")
        // GoMangaStreamedImageProxy (embedded-jvm-runtime-tuning 3.1): hand the
        // source InputStream to Javalin, which copies it to the response in a
        // bounded (~4-8KB) buffer and closes it — instead of `readBytes()`
        // materializing the WHOLE image in JVM heap per proxied request, the
        // dominant transient-heap source on the pure-interpreter device VM under
        // a cover/page load burst. The stream is valid at this point (the prior
        // `readBytes()` consumed it here), so Javalin's slightly-later streamed
        // read is safe; validated by the demo image tests (no truncation / no
        // "Stream closed"). Do NOT wrap in `.use {}` — Javalin owns closing it
        // after the response is written.
        return image.first
    }

    val retrieve =
        handler(
            pathParam<Int>("mangaId"),
            queryParam("onlineFetch", false),
            documentWith = {
                withOperation {
                    summary("Get manga info")
                    description("Get a manga from the database using a specific id.")
                }
            },
            behaviorOf = { ctx, mangaId, onlineFetch ->
                if (slimMode()) {
                    ctx.future(future { getGoMangaManga(mangaId, onlineFetch) })
                } else {
                    ctx.future(
                        future {
                            Manga.getManga(mangaId, onlineFetch)
                        },
                    )
                }
            },
            withResults = {
                json<MangaDataClass>(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** get manga info with all data filled in */
    val retrieveFull =
        handler(
            pathParam<Int>("mangaId"),
            queryParam("onlineFetch", false),
            documentWith = {
                withOperation {
                    summary("Get manga info with all data filled in")
                    description("Get a manga from the database using a specific id.")
                }
            },
            behaviorOf = { ctx, mangaId, onlineFetch ->
                ctx.future(
                    future {
                        Manga.getMangaFull(mangaId, onlineFetch)
                    },
                )
            },
            withResults = {
                json<MangaDataClass>(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** manga thumbnail */
    // GoManga slim cover proxy: fetch the DB manga's cover via the source's OWN
    // OkHttp client (per-source auth: baozi AppBypass referer/CDN rewrite, manhuaren
    // gsn signing), materialize the bytes in-memory, and serve subsequent grid cells
    // from a bounded LRU cache. source.getImage returns 200 where the stock
    // fetchHttpSourceMangaThumbnail's FORCE_NETWORK + refresh path 500s for baozi. We
    // must NOT disk-cache via getImageResponse — its write to tempThumbnailCacheRoot
    // 500s on the sandboxed iOS FS (v0.5.24 regression) — so the LRU map IS the cache
    // (a warm hit is a heap read, not a CDN round-trip). AntiWatermarkInterceptor — the
    // only libawt risk — is disabled on iOS by the host app's pref seeding.
    private suspend fun getGoMangaThumbnail(mangaId: Int): Pair<InputStream, String> {
        goMangaCoverCache[mangaId]?.let { (bytes, contentType) ->
            return java.io.ByteArrayInputStream(bytes) to contentType
        }
        val (sourceId, coverUrl) =
            transaction {
                val row = MangaTable.select { MangaTable.id eq mangaId }.first()
                row[MangaTable.sourceReference] to row[MangaTable.thumbnail_url]
            }
        val cover =
            coverUrl?.takeIf { it.isNotBlank() }
                ?: throw NotFoundResponse("manga $mangaId has no thumbnail url")
        val source =
            getCatalogueSourceOrStub(sourceId) as? HttpSource
                ?: throw NotFoundResponse("source $sourceId is not an HttpSource")
        // Materialize bytes WHILE the OkHttp Response is open: Javalin streams the
        // result AFTER the future resolves, by when a lazy byteStream() is already
        // closed (cf. getGoMangaPageImageByUrl's "closed" IOException). Per-request
        // heap stays bounded by the 32/8 OkHttp dispatcher cap.
        val response = source.getImage(SourcePage(index = 0, url = "", imageUrl = cover))
        val contentType = response.header("content-type") ?: "image/jpeg"
        val bytes = response.use { it.body?.bytes() } ?: throw NotFoundResponse("empty cover body")
        goMangaCoverCache[mangaId] = bytes to contentType
        return java.io.ByteArrayInputStream(bytes) to contentType
    }

    val thumbnail =
        handler(
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Get a manga thumbnail")
                    description("Get a manga thumbnail from the source or the cache.")
                }
            },
            behaviorOf = { ctx, mangaId ->
                ctx.future(
                    future {
                        // Slim (embedded iOS): source-client fetch + disk cache.
                        if (slimMode()) getGoMangaThumbnail(mangaId) else Manga.getMangaThumbnail(mangaId)
                    }
                        .thenApply {
                            imageBytes(ctx, it)
                        },
                )
            },
            withResults = {
                image(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** adds the manga to library */
    val addToLibrary =
        handler(
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Add manga to library")
                    description("Use a manga id to add the manga to your library.\nWill do nothing if manga is already in your library.")
                }
            },
            behaviorOf = { ctx, mangaId ->
                ctx.future(
                    future { Library.addMangaToLibrary(mangaId) },
                )
            },
            withResults = {
                httpCode(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** removes the manga from the library */
    val removeFromLibrary =
        handler(
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Remove manga to library")
                    description("Use a manga id to remove the manga to your library.\nWill do nothing if manga not in your library.")
                }
            },
            behaviorOf = { ctx, mangaId ->
                ctx.future(
                    future { Library.removeMangaFromLibrary(mangaId) },
                )
            },
            withResults = {
                httpCode(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** list manga's categories */
    val categoryList =
        handler(
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Get a manga's categories")
                    description("Get the list of categories for this manga")
                }
            },
            behaviorOf = { ctx, mangaId ->
                ctx.json(CategoryManga.getMangaCategories(mangaId))
            },
            withResults = {
                json<Array<CategoryDataClass>>(HttpCode.OK)
            },
        )

    /** adds the manga to category */
    val addToCategory =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("categoryId"),
            documentWith = {
                withOperation {
                    summary("Add manga to category")
                    description("Add a manga to a category using their ids.")
                }
            },
            behaviorOf = { ctx, mangaId, categoryId ->
                CategoryManga.addMangaToCategory(mangaId, categoryId)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
            },
        )

    /** removes the manga from the category */
    val removeFromCategory =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("categoryId"),
            documentWith = {
                withOperation {
                    summary("Remove manga from category")
                    description("Remove a manga from a category using their ids.")
                }
            },
            behaviorOf = { ctx, mangaId, categoryId ->
                CategoryManga.removeMangaFromCategory(mangaId, categoryId)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
            },
        )

    /** used to modify a manga's meta parameters */
    val meta =
        handler(
            pathParam<Int>("mangaId"),
            formParam<String>("key"),
            formParam<String>("value"),
            documentWith = {
                withOperation {
                    summary("Add data to manga")
                    description("A simple Key-Value storage in the manga object, you can set values for whatever you want inside it.")
                }
            },
            behaviorOf = { ctx, mangaId, key, value ->
                Manga.modifyMangaMeta(mangaId, key, value)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** get chapter list when showing a manga */
    val chapterList =
        handler(
            pathParam<Int>("mangaId"),
            queryParam("onlineFetch", false),
            documentWith = {
                withOperation {
                    summary("Get manga chapter list")
                    description(
                        "Get the manga chapter list from the database or online. " +
                            "If there is no chapters in the database it fetches the chapters online. " +
                            "Use onlineFetch to update chapter list.",
                    )
                }
            },
            behaviorOf = { ctx, mangaId, onlineFetch ->
                if (slimMode()) {
                    ctx.future(future { getGoMangaChapterList(mangaId, onlineFetch) })
                } else {
                    ctx.future(future { Chapter.getChapterList(mangaId, onlineFetch) })
                }
            },
            withResults = {
                json<Array<ChapterDataClass>>(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** batch edit chapters of single manga */
    val chapterBatch =
        handler(
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Chapters update multiple")
                    description("Update multiple chapters of single manga. For batch marking as read, or bookmarking")
                }
                body<Chapter.MangaChapterBatchEditInput>()
            },
            behaviorOf = { ctx, mangaId ->
                val input = json.decodeFromString<Chapter.MangaChapterBatchEditInput>(ctx.body())
                Chapter.modifyChapters(input, mangaId)
            },
            withResults = {
                httpCode(HttpCode.OK)
            },
        )

    /** batch edit chapters from multiple manga */
    val anyChapterBatch =
        handler(
            documentWith = {
                withOperation {
                    summary("Chapters update multiple")
                    description("Update multiple chapters on any manga. For batch marking as read, or bookmarking")
                }
                body<Chapter.ChapterBatchEditInput>()
            },
            behaviorOf = { ctx ->
                val input = json.decodeFromString<Chapter.ChapterBatchEditInput>(ctx.body())
                Chapter.modifyChapters(
                    Chapter.MangaChapterBatchEditInput(
                        input.chapterIds,
                        null,
                        input.change,
                    ),
                )
            },
            withResults = {
                httpCode(HttpCode.OK)
            },
        )

    /** used to display a chapter, get a chapter in order to show its pages */
    val chapterRetrieve =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            queryParam("onlineFetch", false),
            documentWith = {
                withOperation {
                    summary("Get a chapter")
                    description("Get the chapter from the manga id and chapter index. It will also retrieve the pages for this chapter.")
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex, onlineFetch ->
                if (slimMode()) {
                    ctx.future(
                        future {
                            if (onlineFetch) {
                                getOnlineGoMangaChapter(mangaId, chapterIndex)
                            } else {
                                val cached = getCachedGoMangaChapter(mangaId, chapterIndex)
                                cached ?: throw NotFoundResponse("cached chapter not found")
                            }
                        },
                    )
                } else {
                    ctx.future(future { getChapterDownloadReadyByIndex(chapterIndex, mangaId) })
                }
            },
            withResults = {
                json<ChapterDataClass>(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** used to modify a chapter's parameters */
    val chapterModify =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            formParam<Boolean?>("read"),
            formParam<Boolean?>("bookmarked"),
            formParam<Boolean?>("markPrevRead"),
            formParam<Int?>("lastPageRead"),
            documentWith = {
                withOperation {
                    summary("Modify a chapter")
                    description("Update user info for a given chapter, such as read status, bookmarked, and more.")
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex, read, bookmarked, markPrevRead, lastPageRead ->
                Chapter.modifyChapter(mangaId, chapterIndex, read, bookmarked, markPrevRead, lastPageRead)

                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
            },
        )

    /** delete a downloaded chapter */
    val chapterDelete =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            documentWith = {
                withOperation {
                    summary("Delete a chapter download")
                    description("Delete the downloaded chapter and its files.")
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex ->
                Chapter.deleteChapter(mangaId, chapterIndex)

                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** used to modify a chapter's meta parameters */
    val chapterMeta =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            formParam<String>("key"),
            formParam<String>("value"),
            documentWith = {
                withOperation {
                    summary("Add data to chapter")
                    description("A simple Key-Value storage in the chapter object, you can set values for whatever you want inside it.")
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex, key, value ->
                Chapter.modifyChapterMeta(mangaId, chapterIndex, key, value)

                ctx.status(200)
            },
            withResults = {
                httpCode(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** get direct-first page image resources for a chapter */
    val pageResourceList =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            documentWith = {
                withOperation {
                    summary("Get chapter page image resources")
                    description(
                        "Get direct-first page image URLs, request headers, cache keys, and fallback proxy URLs for host-side image loading.",
                    )
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex ->
                ctx.future(future { Page.getPageImageResources(mangaId, chapterIndex) })
            },
            withResults = {
                json<List<PageImageResourceDataClass>>(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    /** get page at index "index" */
    val pageRetrieve =
        handler(
            pathParam<Int>("mangaId"),
            pathParam<Int>("chapterIndex"),
            pathParam<Int>("index"),
            documentWith = {
                withOperation {
                    summary("Get a chapter page")
                    description(
                        "Get a chapter page for a given index. Cache use can be disabled so it only retrieves it directly from the source.",
                    )
                }
            },
            behaviorOf = { ctx, mangaId, chapterIndex, index ->
                ctx.future(
                    future { Page.getPageImage(mangaId, chapterIndex, index) }
                        .thenApply {
                            imageBytes(ctx, it)
                        },
                )
            },
            withResults = {
                image(HttpCode.OK)
                httpCode(HttpCode.NOT_FOUND)
            },
        )

    // ──────────────────────────────────────────────────────────────────────
    // GoManga no-DB source-URL addressing (source-token-addressing)
    //
    // Address detail / chapter-list / page-list by the REAL source URL via the
    // extension SPI, performing NO MangaTable/ChapterTable/PageTable read or
    // write — removing the H2 round-trip + write churn (MangaList.processEntries
    // write-N-rows, Chapter delete+batchInsert) that degrades the
    // pure-interpreter device JVM over a browsing session. The legacy DB-id
    // routes above stay registered (additive). Source handles come from
    // GetCatalogueSource (jar-cached by id; needs no manga DB row).
    // ──────────────────────────────────────────────────────────────────────

    /** Tachiyomi pages for a (sourceId, chapterURL), cached so the URL-keyed
     *  proxy fallback does not re-run getPageList per page. */
    private val goMangaUrlPageCache = ConcurrentHashMap<String, List<SourcePage>>()

    private fun urlPageCacheKey(
        sourceId: Long,
        chapterUrl: String,
    ): String = "$sourceId:$chapterUrl"

    private fun urlPageProxyPath(
        sourceId: Long,
        chapterUrl: String,
        index: Int,
    ): String =
        "/api/v1/source/$sourceId/manga/chapter/page-image?chapterUrl=" +
            java.net.URLEncoder.encode(chapterUrl, "UTF-8") + "&index=$index"

    private fun okhttp3.Headers.goMangaHeaderMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }

    private suspend fun goMangaTrueImageUrl(
        page: SourcePage,
        source: HttpSource,
    ): String {
        if (page.imageUrl == null) page.imageUrl = source.getImageUrl(page)
        return page.imageUrl!!
    }

    private suspend fun getGoMangaMangaByUrl(
        sourceId: Long,
        mangaUrl: String,
    ): GoMangaMangaDataClass {
        val source = getCatalogueSourceOrStub(sourceId)
        val sManga = SManga.create().apply { url = mangaUrl; title = mangaUrl }
        val details = source.getMangaDetails(sManga)
        return GoMangaMangaDataClass(
            id = 0,
            sourceId = sourceId.toString(),
            url = mangaUrl,
            title = details.title,
            // Source-direct thumbnail URL (NOT proxyThumbnailUrl(mangaId): there
            // is no DB id here, and the app loads covers app-direct anyway).
            thumbnailUrl = details.thumbnail_url,
            artist = details.artist,
            author = details.author,
            description = details.description,
            genre = details.genre.toGoMangaGenreList(),
            status = MangaStatus.valueOf(details.status).name,
            inLibrary = false,
            // realUrl = the source's own browsable page (mirror-aware), mirroring the
            // DB path (Manga.kt:153 → getMangaUrl). baozi (fork) overrides getMangaUrl
            // to honor its v1.key.api_option mirror (e.g. tw.baozimh.com); happymh
            // yields a real /manga page. The old baseUrl+url IGNORED those overrides,
            // so "open website" + CF WebView verification used www.baozimh.com no
            // matter which mirror the user picked.
            realUrl = (source as? HttpSource)?.getMangaUrl(sManga),
            lastFetchedAt = null,
            chaptersLastFetchedAt = null,
            chapterCount = null,
        )
    }

    private suspend fun getGoMangaChaptersByUrl(
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
    ): List<GoMangaChapterDataClass> {
        val source = getCatalogueSourceOrStub(sourceId)
        val sManga =
            SManga.create().apply {
                url = mangaUrl
                title = mangaTitle
            }
        val chapters = source.getChapterList(sManga)
        // Enrich identically to the DB path (Chapter.fetchChapterList) so chapter
        // numbers / names match the legacy route — but write NO ChapterTable rows.
        chapters.forEach { chapter ->
            (source as? HttpSource)?.prepareNewChapter(chapter, sManga)
            chapter.chapter_number =
                ChapterRecognition
                    .parseChapterNumber(mangaTitle, chapter.name, chapter.chapter_number.toDouble())
                    .toFloat()
            chapter.name = chapter.name.sanitize(mangaTitle)
            chapter.scanlator = chapter.scanlator?.ifBlank { null }?.trim()
        }
        val count = chapters.size
        // index mirrors ChapterTable.sourceOrder: sources return newest-first, so
        // the newest chapter gets the highest index (the app sorts by index desc).
        return chapters.mapIndexed { position, chapter ->
            GoMangaChapterDataClass(
                id = 0,
                url = chapter.url,
                name = chapter.name,
                uploadDate = chapter.date_upload,
                chapterNumber = chapter.chapter_number,
                scanlator = chapter.scanlator,
                mangaId = 0,
                read = false,
                bookmarked = false,
                lastPageRead = 0,
                lastReadAt = 0,
                index = count - position,
                fetchedAt = 0,
                realUrl = null,
                downloaded = false,
                pageCount = 0,
                chapterCount = count,
            )
        }
    }

    private suspend fun getGoMangaPagesByUrl(
        sourceId: Long,
        chapterUrl: String,
    ): List<PageImageResourceDataClass> {
        val source = getCatalogueSourceOrStub(sourceId)
        val pages = source.getPageList(SChapter.create().apply { url = chapterUrl })
        goMangaUrlPageCache[urlPageCacheKey(sourceId, chapterUrl)] = pages
        if (source !is HttpSource) {
            return pages.map { page ->
                PageImageResourceDataClass(
                    index = page.index,
                    imageUrl = null,
                    cacheKey = "${urlPageCacheKey(sourceId, chapterUrl)}:${page.index}:${page.url}",
                    fallbackImageUrl = urlPageProxyPath(sourceId, chapterUrl, page.index),
                    requiresProxy = true,
                )
            }
        }
        val headers = source.headers.goMangaHeaderMap()
        return pages.map { page ->
            val imageUrl =
                try {
                    goMangaTrueImageUrl(page, source)
                } catch (_: Exception) {
                    null
                }
            PageImageResourceDataClass(
                index = page.index,
                imageUrl = imageUrl,
                headers = headers,
                cacheKey = "${urlPageCacheKey(sourceId, chapterUrl)}:${page.index}:${imageUrl ?: page.url}",
                fallbackImageUrl = urlPageProxyPath(sourceId, chapterUrl, page.index),
                requiresProxy = imageUrl == null,
            )
        }
    }

    private suspend fun getGoMangaPageImageByUrl(
        sourceId: Long,
        chapterUrl: String,
        index: Int,
    ): Pair<InputStream, String> {
        val source = getCatalogueSourceOrStub(sourceId)
        val key = urlPageCacheKey(sourceId, chapterUrl)
        val pages =
            goMangaUrlPageCache[key]
                ?: source.getPageList(SChapter.create().apply { url = chapterUrl })
                    .also { goMangaUrlPageCache[key] = it }
        val page =
            pages.firstOrNull { it.index == index }
                ?: pages.getOrNull(index)
                ?: throw NotFoundResponse("page $index not found for $chapterUrl")
        // LocalSource (and any non-HTTP source) serves page bytes from disk, not
        // OkHttp. Materialize eagerly (like the HttpSource path below) so the bytes
        // are still valid when Javalin streams the future's result — a lazy
        // FileInputStream is already closed by then (IOException: Stream Closed).
        // Archive chapters expose entries via LocalSource.pageCache (keyed by the
        // no-DB chapterUrl); directory chapters carry an absolute file path in
        // page.imageUrl.
        if (source !is HttpSource) {
            val cached = eu.kanade.tachiyomi.source.local.LocalSource.pageCache[chapterUrl]?.getOrNull(index)
            val bytes: ByteArray =
                if (cached != null) {
                    cached().use { it.readBytes() }
                } else {
                    val filePath = page.imageUrl
                        ?: throw NotFoundResponse("page $index has no local source for $chapterUrl")
                    java.io.File(filePath).inputStream().use { it.readBytes() }
                }
            val contentType =
                suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
                    .findImageType { java.io.ByteArrayInputStream(bytes) }?.mime ?: "image/jpeg"
            return java.io.ByteArrayInputStream(bytes) to contentType
        }
        if (page.imageUrl == null) page.imageUrl = source.getImageUrl(page)
        val response = source.getImage(page)
        val contentType = response.header("content-type") ?: "image/jpeg"
        // Materialize the bytes WHILE the OkHttp Response is open. The slim route
        // returns this via `ctx.future{…}.thenApply{imageBytes}` and Javalin streams
        // the result AFTER the future resolves — by then `response` is out of scope
        // and OkHttp has closed its body, so returning the lazy `byteStream()` threw
        // `java.io.IOException: closed` (the manhuaren reader 500). Reading into a
        // ByteArrayInputStream here keeps it valid; per-request heap stays bounded by
        // the 32/8 OkHttp dispatcher cap (embedded-jvm-runtime-tuning 1.1).
        val bytes = response.use { it.body?.bytes() } ?: throw NotFoundResponse("empty image body")
        return java.io.ByteArrayInputStream(bytes) to contentType
    }

    // Plain Javalin handlers (NOT the `handler(...)` util): that util wraps every
    // route in Javalin's OpenAPI `documented(...)` handler, whose request-time
    // lambda throws NullPointerException for these routes in the slim embedded
    // server — which surfaces as a 404. The no-DB routes therefore register as
    // plain handlers; the JSON response is still produced by `ctx.future`
    // rendering the future's object result, exactly as the util's behaviorOf does
    // (cf. the slim `retrieve` handler).

    /** detail by real source URL (no DB) */
    val mangaDetailByUrl =
        io.javalin.http.Handler { ctx ->
            val url = ctx.queryParam("url").orEmpty()
            if (url.isBlank()) {
                ctx.status(400)
            } else {
                ctx.future(future { getGoMangaMangaByUrl(ctx.pathParam("sourceId").toLong(), url) })
            }
        }

    /** chapter list by real source URL (no DB) */
    val chapterListByUrl =
        io.javalin.http.Handler { ctx ->
            val url = ctx.queryParam("url").orEmpty()
            if (url.isBlank()) {
                ctx.status(400)
            } else {
                ctx.future(
                    future {
                        getGoMangaChaptersByUrl(
                            ctx.pathParam("sourceId").toLong(),
                            url,
                            ctx.queryParam("title").orEmpty(),
                        )
                    },
                )
            }
        }

    /** page resource list by real chapter URL (no DB) */
    val pageResourceListByUrl =
        io.javalin.http.Handler { ctx ->
            val url = ctx.queryParam("url").orEmpty()
            if (url.isBlank()) {
                ctx.status(400)
            } else {
                ctx.future(future { getGoMangaPagesByUrl(ctx.pathParam("sourceId").toLong(), url) })
            }
        }

    /** URL-keyed page-image proxy (last-resort fallback for the no-DB path) */
    val pageImageByUrl =
        io.javalin.http.Handler { ctx ->
            val chapterUrl = ctx.queryParam("chapterUrl").orEmpty()
            if (chapterUrl.isBlank()) {
                ctx.status(400)
            } else {
                val index = ctx.queryParam("index")?.toIntOrNull() ?: 0
                ctx.future(
                    future { getGoMangaPageImageByUrl(ctx.pathParam("sourceId").toLong(), chapterUrl, index) }
                        .thenApply { imageBytes(ctx, it) },
                )
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // host-owned-cookie-routing 4.1: proactive server cookie seed
    //
    // The host pushes a verified host's FULL cookie set (not cf_clearance only)
    // into the server's OkHttp jar PROACTIVELY (on boot / after verification),
    // so the first source-parsing request to a Cloudflare host already carries
    // the clearance instead of taking a 403 -> FlareSolverr /v1 round-trip. The
    // reactive /v1 pull (CloudflareInterceptor) stays as the fallback. Plain
    // data classes (Javalin's mapper) — no @Serializable, so the class-swap
    // recompile needs no serialization plugin.
    // ──────────────────────────────────────────────────────────────────────

    private val networkHelper: NetworkHelper by injectLazy()

    data class GoMangaSeedCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String = "/",
    )

    data class GoMangaCookieSeedRequest(
        val host: String,
        val cookies: List<GoMangaSeedCookie>,
        val userAgent: String = "",
    )

    private fun seedHostCookies(req: GoMangaCookieSeedRequest) {
        val host = req.host.removePrefix(".")
        if (host.isBlank() || req.cookies.isEmpty()) return
        val httpUrl = HttpUrl.Builder().scheme("https").host(host).build()
        val cookies =
            req.cookies.map { c ->
                Cookie
                    .Builder()
                    .name(c.name)
                    .value(c.value)
                    .domain(c.domain.removePrefix("."))
                    .path(c.path.ifBlank { "/" })
                    .build()
            }
        networkHelper.cookieStore.addAll(httpUrl, cookies)
        // GoMangaStableDefaultUA: the clearance UA deliberately does NOT touch
        // the server-wide default UA. Per-host clearance/UA pairing lives in the
        // host-side FlareSolverr shim, and the CloudflareInterceptor forces that
        // UA onto each CF-walled request. Adopting it globally degraded
        // UA-sensitive source APIs (happymh page-list under a mobile Safari UA).
        // req.userAgent stays on the wire format for compatibility.
    }

    /** Proactively seed the server OkHttp jar with a verified host's cookies. */
    val cfCookieSeed =
        io.javalin.http.Handler { ctx ->
            val req =
                try {
                    ctx.bodyAsClass(GoMangaCookieSeedRequest::class.java)
                } catch (_: Exception) {
                    ctx.status(400)
                    return@Handler
                }
            seedHostCookies(req)
            ctx.status(200)
        }

    // GoMangaServerUserAgent: plain data class (Javalin Jackson, no @Serializable
    // → no serialization-plugin recompile trap).
    data class GoMangaDefaultUaRequest(
        val userAgent: String = "",
    )

    /**
     * Set the server's default HTTP-client User-Agent for source requests.
     * The host calls this ONCE at boot with a STABLE ecosystem UA (the
     * Tachiyomi/Mihon-family default the extensions are tested against), NOT
     * the device WebKit UA — UA-sensitive source APIs degrade under a mobile
     * Safari UA (happymh page-list). Cloudflare clearance/UA pairing is
     * per-request via the CloudflareInterceptor + FlareSolverr shim instead.
     * Idempotent via NetworkHelper's guard.
     */
    val gomangaDefaultUa =
        io.javalin.http.Handler { ctx ->
            val req =
                try {
                    ctx.bodyAsClass(GoMangaDefaultUaRequest::class.java)
                } catch (_: Exception) {
                    ctx.status(400)
                    return@Handler
                }
            if (req.userAgent.isNotBlank()) {
                networkHelper.setDefaultUserAgent(req.userAgent)
            }
            ctx.status(200)
        }
}

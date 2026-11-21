package suwayomi.tachidesk.manga

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.apibuilder.ApiBuilder.delete
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.head
import io.javalin.apibuilder.ApiBuilder.patch
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.apibuilder.ApiBuilder.put
import io.javalin.apibuilder.ApiBuilder.ws
import suwayomi.tachidesk.manga.controller.BackupController
import suwayomi.tachidesk.manga.controller.BuiltInKomgaController
import suwayomi.tachidesk.manga.controller.CategoryController
import suwayomi.tachidesk.manga.controller.DownloadController
import suwayomi.tachidesk.manga.controller.ExtensionController
import suwayomi.tachidesk.manga.controller.MangaController
import suwayomi.tachidesk.manga.controller.QuickJsFixtureController
import suwayomi.tachidesk.manga.controller.SourceController
import suwayomi.tachidesk.manga.controller.TrackController
import suwayomi.tachidesk.manga.controller.UpdateController

object MangaAPI {
    fun defineEndpoints() {
        if (System.getProperty("suwayomi.tachidesk.gomanga.slimMode", "false") == "true") {
            defineGoMangaEndpoints()
            return
        }

        path("extension") {
            get("list", ExtensionController.list)

            get("install/{pkgName}", ExtensionController.install)
            post("install", ExtensionController.installFile)
            get("update/{pkgName}", ExtensionController.update)
            get("uninstall/{pkgName}", ExtensionController.uninstall)

            get("icon/{pkgName}", ExtensionController.icon)
        }

        path("source") {
            get("list", SourceController.list)
            get("{sourceId}", SourceController.retrieve)

            get("{sourceId}/popular/{pageNum}", SourceController.popular)
            get("{sourceId}/latest/{pageNum}", SourceController.latest)

            get("{sourceId}/preferences", SourceController.getPreferences)
            post("{sourceId}/preferences", SourceController.setPreference)
            get("{sourceId}/preferences/persistence", SourceController.preferencePersistenceCapabilities)
            post("{sourceId}/preferences/capture", SourceController.registerPreferenceCapture)
            post("{sourceId}/preferences/ack", SourceController.acknowledgePreferenceCapture)

            get("{sourceId}/filters", SourceController.getFilters)
            post("{sourceId}/filters", SourceController.setFilters)

            get("{sourceId}/search", SourceController.searchSingle)
            post("{sourceId}/quick-search", SourceController.quickSearchSingle)
//            get("all/search", SourceController.searchGlobal) // TODO
        }

        path("manga") {
            get("{mangaId}", MangaController.retrieve)
            get("{mangaId}/full", MangaController.retrieveFull)
            get("{mangaId}/thumbnail", MangaController.thumbnail)

            get("{mangaId}/category", MangaController.categoryList)
            get("{mangaId}/category/{categoryId}", MangaController.addToCategory)
            delete("{mangaId}/category/{categoryId}", MangaController.removeFromCategory)

            get("{mangaId}/library", MangaController.addToLibrary)
            delete("{mangaId}/library", MangaController.removeFromLibrary)

            patch("{mangaId}/meta", MangaController.meta)

            get("{mangaId}/chapters", MangaController.chapterList)
            post("{mangaId}/chapter/batch", MangaController.chapterBatch)
            get("{mangaId}/chapter/{chapterIndex}", MangaController.chapterRetrieve)
            patch("{mangaId}/chapter/{chapterIndex}", MangaController.chapterModify)
            put("{mangaId}/chapter/{chapterIndex}", MangaController.chapterModify)
            delete("{mangaId}/chapter/{chapterIndex}", MangaController.chapterDelete)

            patch("{mangaId}/chapter/{chapterIndex}/meta", MangaController.chapterMeta)

            get("{mangaId}/chapter/{chapterIndex}/pages", MangaController.pageResourceList)
            get("{mangaId}/chapter/{chapterIndex}/page/{index}", MangaController.pageRetrieve)
        }

        path("chapter") {
            post("batch", MangaController.anyChapterBatch)
            get("{chapterId}/download", MangaController.downloadChapter)
            head("{chapterId}/download", MangaController.downloadChapter)
        }

        path("category") {
            get("", CategoryController.categoryList)
            post("", CategoryController.categoryCreate)

            // The order here is important {categoryId} needs to be applied last
            // or throws a NumberFormatException
            patch("reorder", CategoryController.categoryReorder)

            get("{categoryId}", CategoryController.categoryMangas)
            patch("{categoryId}", CategoryController.categoryModify)
            delete("{categoryId}", CategoryController.categoryDelete)

            patch("{categoryId}/meta", CategoryController.meta)
        }

        path("backup") {
            post("import", BackupController.protobufImport)
            post("import/file", BackupController.protobufImportFile)

            post("validate", BackupController.protobufValidate)
            post("validate/file", BackupController.protobufValidateFile)

            get("export", BackupController.protobufExport)
            get("export/file", BackupController.protobufExportFile)
        }

        path("downloads") {
            ws("", DownloadController::downloadsWS)

            get("start", DownloadController.start)
            get("stop", DownloadController.stop)
            get("clear", DownloadController.clear)
        }

        path("download") {
            get("{mangaId}/chapter/{chapterIndex}", DownloadController.queueChapter)
            delete("{mangaId}/chapter/{chapterIndex}", DownloadController.unqueueChapter)
            patch("{mangaId}/chapter/{chapterIndex}/reorder/{to}", DownloadController.reorderChapter)
            post("batch", DownloadController.queueChapters)
            delete("batch", DownloadController.unqueueChapters)
        }

        path("update") {
            get("recentChapters/{pageNum}", UpdateController.recentChapters)
            post("fetch", UpdateController.categoryUpdate)
            post("reset", UpdateController.reset)
            get("summary", UpdateController.updateSummary)
            ws("", UpdateController::categoryUpdateWS)
        }

        path("track") {
            get("list", TrackController.list)
            post("login", TrackController.login)
            post("logout", TrackController.logout)
            post("search", TrackController.search)
            post("bind", TrackController.bind)
            post("update", TrackController.update)
            get("{trackerId}/thumbnail", TrackController.thumbnail)
        }
    }

    private fun defineGoMangaEndpoints() {
        path("extension") {
            get("list", ExtensionController.list)
            post("install", ExtensionController.installFile)
            get("uninstall/{pkgName}", ExtensionController.uninstall)
            get("icon/{apkName}", ExtensionController.icon)
        }

        path("source") {
            get("list", SourceController.list)
            get("{sourceId}", SourceController.retrieve)
            get("{sourceId}/popular/{pageNum}", SourceController.popular)
            get("{sourceId}/latest/{pageNum}", SourceController.latest)
            get("{sourceId}/preferences", SourceController.getPreferences)
            post("{sourceId}/preferences", SourceController.setPreference)
            get("{sourceId}/preferences/persistence", SourceController.preferencePersistenceCapabilities)
            post("{sourceId}/preferences/capture", SourceController.registerPreferenceCapture)
            post("{sourceId}/preferences/ack", SourceController.acknowledgePreferenceCapture)
            get("{sourceId}/filters", SourceController.getFilters)
            post("{sourceId}/filters", SourceController.setFilters)
            get("{sourceId}/search", SourceController.searchSingle)
            post("{sourceId}/quick-search", SourceController.quickSearchSingle)
            // GoManga no-DB source-URL addressing (source-token-addressing):
            // detail / chapter-list / page-list by the REAL source URL, no H2.
            get("{sourceId}/manga/details", MangaController.mangaDetailByUrl)
            get("{sourceId}/manga/chapters", MangaController.chapterListByUrl)
            get("{sourceId}/manga/chapter/pages", MangaController.pageResourceListByUrl)
            get("{sourceId}/manga/chapter/page-image", MangaController.pageImageByUrl)
        }

        path("manga") {
            get("{mangaId}", MangaController.retrieve)
            get("{mangaId}/full", MangaController.retrieveFull)
            get("{mangaId}/thumbnail", MangaController.thumbnail)
            get("{mangaId}/chapters", MangaController.chapterList)
            get("{mangaId}/chapter/{chapterIndex}", MangaController.chapterRetrieve)
            get("{mangaId}/chapter/{chapterIndex}/pages", MangaController.pageResourceList)
            get("{mangaId}/chapter/{chapterIndex}/page/{index}", MangaController.pageRetrieve)
        }

        path("gomanga") {
            post("builtin-komga/{sourceId}/activate", BuiltInKomgaController.activate)
            post("builtin-komga/{sourceId}/deactivate", BuiltInKomgaController.deactivate)
            // host-owned-cookie-routing 4.1: proactive host->server cookie seed.
            post("cf-cookies", MangaController.cfCookieSeed)
            // GoMangaStableDefaultUA: set the server's stable ecosystem source
            // UA at boot (clearance/UA pairing is per-request, never global).
            post("default-ua", MangaController.gomangaDefaultUa)
            // GoMangaInsecureHttpGate: mirror the app-side InsecureSourcePolicy
            // ("允許不安全的來源") onto the server's OkHttp egress. Pushed at boot
            // and on toggle flip; fail-closed until pushed.
            post("allow-insecure-http", MangaController.gomangaAllowInsecureHttp)
            // Test-only operation table. The native host sets this property only
            // when an app-hosted iOS test explicitly exports its fixture flag.
            if (System.getProperty("suwayomi.tachidesk.gomanga.quickJsFixture", "false") == "true") {
                get("quickjs-fixture/operation-table", QuickJsFixtureController.operationTable)
            }
        }
    }
}

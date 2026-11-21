package suwayomi.tachidesk.manga.impl.builtin.komga

/*
 * Copyright (C) Contributors to the GoManga project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.server.database.DBManager
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div

data class BuiltInKomgaLifecycleState(
    val sourceId: String,
    val active: Boolean,
    val activeSourceIds: List<String>,
)

class BuiltInKomgaInvalidSourceId(
    val sourceId: String,
) : IllegalArgumentException("source id is not a built-in Komga slot")

class BuiltInKomgaLifecycleUnavailable(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class BuiltInKomgaExtensionMutationRejected :
    IllegalArgumentException("the built-in Komga provider is not extension-management content")

/**
 * Persistent activation boundary for the three bundled Komga slots.
 *
 * The provider classes are always present. A slot is active only when its
 * [SourceTable] row points at the hidden sentinel extension row.
 */
object BuiltInKomgaLifecycle {
    const val packageId = "eu.kanade.tachiyomi.extension.all.komga"
    const val sentinelClassName =
        "suwayomi.tachidesk.manga.impl.builtin.komga.BuiltInKomgaProviderFactory"

    val allowedSourceIds: List<Long> =
        BuiltInKomgaProviderFactory.slots.map(BuiltInKomgaProviderFactory.Slot::sourceId)

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()

    private data class InitializationPlan(
        val sourceIdsToUnregister: Set<Long>,
        val activeSourceIds: List<Long>,
        val legacyApkNames: Set<String>,
    )

    @Synchronized
    fun initialize() {
        isInitialized = false
        try {
            if (
                System.getProperty(
                    "suwayomi.tachidesk.gomanga.failBuiltInKomgaLifecycleInit",
                    "false",
                ) == "true"
            ) {
                throw BuiltInKomgaLifecycleUnavailable(
                    "built-in Komga factory failed to initialize",
                )
            }

            BuiltInKomgaProviderFactory.validate()
            val plan = DBManager.embeddedSourceConnection()?.use(::canonicalInitializationPlan)
                ?: transaction { initializePersistence() }

            plan.sourceIdsToUnregister.forEach(GetSource::unregisterSource)
            removeLegacyProviderJars(plan.legacyApkNames)
            ensureBundledIcon()
            plan.activeSourceIds.forEach { sourceId ->
                GetSource.registerSource(sourceId to BuiltInKomgaProviderFactory.create(sourceId))
            }
            isInitialized = true
        } catch (error: BuiltInKomgaLifecycleUnavailable) {
            throw error
        } catch (error: Throwable) {
            throw BuiltInKomgaLifecycleUnavailable(
                "built-in Komga factory failed to initialize",
                error,
            )
        }
    }

    @Synchronized
    fun activate(sourceId: Long): BuiltInKomgaLifecycleState {
        requireInitialized()
        val slot =
            BuiltInKomgaProviderFactory.slot(sourceId)
                ?: throw BuiltInKomgaInvalidSourceId(sourceId.toString())
        val source = BuiltInKomgaProviderFactory.create(sourceId)

        transaction {
            val sentinelId = ensureSentinel()
            val existing = SourceTable.selectAll().where { SourceTable.id eq sourceId }.firstOrNull()
            if (existing == null) {
                insertSource(slot, sentinelId.value)
            } else if (existing[SourceTable.extension] != sentinelId) {
                SourceTable.deleteWhere { SourceTable.id eq sourceId }
                insertSource(slot, sentinelId.value)
            } else {
                SourceTable.update({ SourceTable.id eq sourceId }) {
                    it[name] = slot.defaultName
                    it[lang] = "all"
                    it[contentWarning] = 0
                }
            }
        }

        GetSource.registerSource(sourceId to source)
        return state(sourceId, active = true)
    }

    fun activate(sourceId: String): BuiltInKomgaLifecycleState {
        val parsed =
            sourceId
                .takeIf { raw -> BuiltInKomgaProviderFactory.slots.any { it.sourceId.toString() == raw } }
                ?.toLong()
                ?: throw BuiltInKomgaInvalidSourceId(sourceId)
        return activate(parsed)
    }

    @Synchronized
    fun deactivate(sourceId: Long): BuiltInKomgaLifecycleState {
        requireInitialized()
        if (sourceId !in allowedSourceIds) {
            throw BuiltInKomgaInvalidSourceId(sourceId.toString())
        }

        val cachedSource = GetSource.evictCachedSource(sourceId)
        try {
            transaction {
                val sentinelIds =
                    ExtensionTable
                        .selectAll()
                        .where {
                            (ExtensionTable.pkgName eq packageId) and
                                (ExtensionTable.classFQName eq sentinelClassName)
                        }.map { it[ExtensionTable.id] }
                if (sentinelIds.isNotEmpty()) {
                    SourceTable.deleteWhere {
                        (SourceTable.id eq sourceId) and (SourceTable.extension inList sentinelIds)
                    }
                }
            }
        } catch (error: Throwable) {
            if (cachedSource != null) {
                GetSource.registerSource(sourceId to cachedSource)
            }
            throw error
        }

        Source.preferenceScreenMap.remove(sourceId)
        BuiltInKomgaProviderFactory
            .create(sourceId)
            .getSourcePreferences()
            .edit()
            .clear()
            .commit()
        return state(sourceId, active = false)
    }

    fun deactivate(sourceId: String): BuiltInKomgaLifecycleState {
        val parsed =
            sourceId
                .takeIf { raw -> BuiltInKomgaProviderFactory.slots.any { it.sourceId.toString() == raw } }
                ?.toLong()
                ?: throw BuiltInKomgaInvalidSourceId(sourceId)
        return deactivate(parsed)
    }

    fun activeSourceIds(): List<Long> {
        requireInitialized()
        return transaction {
            val sentinelIds =
                ExtensionTable
                    .selectAll()
                    .where {
                        (ExtensionTable.pkgName eq packageId) and
                            (ExtensionTable.classFQName eq sentinelClassName)
                    }.map { it[ExtensionTable.id] }
            if (sentinelIds.isEmpty()) {
                emptyList()
            } else {
                val active =
                    SourceTable
                        .selectAll()
                        .where {
                            (SourceTable.id inList allowedSourceIds) and
                                (SourceTable.extension inList sentinelIds)
                        }.map { it[SourceTable.id].value }
                        .toSet()
                allowedSourceIds.filter(active::contains)
            }
        }
    }

    fun isBuiltInPackage(pkgName: String): Boolean = pkgName == packageId

    fun rejectExtensionMutation(pkgName: String) {
        if (isBuiltInPackage(pkgName)) {
            throw BuiltInKomgaExtensionMutationRejected()
        }
    }

    fun isSentinel(row: ResultRow): Boolean =
        row[ExtensionTable.pkgName] == packageId &&
            row[ExtensionTable.classFQName] == sentinelClassName

    private fun canonicalInitializationPlan(db: java.sql.Connection): InitializationPlan? {
        val rowsSeen = mutableSetOf<Long>()
        var sentinelId: Int? = null
        db.prepareStatement("""
            SELECT E.ID, E.CLASS_NAME, E.APK_NAME, S.ID
            FROM EXTENSION E LEFT JOIN SOURCE S ON S.EXTENSION = E.ID
            WHERE E.PKG_NAME = ?
        """).use { sql ->
            sql.setString(1, packageId)
            sql.executeQuery().use { rows ->
                while (rows.next()) {
                    val id = rows.getInt(1)
                    if (sentinelId != null && id != sentinelId) return null
                    if (rows.getString(2) != sentinelClassName || rows.getString(3) != null) return null
                    sentinelId = id
                    val source = rows.getLong(4)
                    if (!rows.wasNull()) {
                        if (source !in allowedSourceIds) return null
                        rowsSeen.add(source)
                    }
                }
            }
        }
        if (sentinelId == null) return null
        return InitializationPlan(rowsSeen, allowedSourceIds.filter { it in rowsSeen }, emptySet())
    }

    private fun initializePersistence(): InitializationPlan {
        val extensionRows =
            ExtensionTable
                .selectAll()
                .where { ExtensionTable.pkgName eq packageId }
                .toList()
        val extensionIds = extensionRows.map { it[ExtensionTable.id] }
        val sourceRows =
            if (extensionIds.isEmpty()) {
                emptyList()
            } else {
                SourceTable
                    .selectAll()
                    .where { SourceTable.extension inList extensionIds }
                    .toList()
            }
        val sourceIdsToUnregister = sourceRows.map { it[SourceTable.id].value }.toSet()
        val hasLegacyPayload = extensionRows.any { !isSentinel(it) || it[ExtensionTable.apkName] != null }
        val persistedActiveIds =
            if (hasLegacyPayload) {
                emptyList()
            } else {
                val active = sourceRows.map { it[SourceTable.id].value }.toSet()
                allowedSourceIds.filter(active::contains)
            }
        val legacyApkNames =
            extensionRows
                .mapNotNull { it[ExtensionTable.apkName] }
                .toSet()

        // Replacing a canonical sentinel cascades to data referencing its stable ID.
        val alreadyCanonical = extensionRows.size == 1 && !hasLegacyPayload &&
            sourceIdsToUnregister.all { it in allowedSourceIds }
        if (!alreadyCanonical) {
            if (extensionIds.isNotEmpty()) {
                SourceTable.deleteWhere { SourceTable.extension inList extensionIds }
                ExtensionTable.deleteWhere { ExtensionTable.id inList extensionIds }
            }
            val sentinelId = ensureSentinel()
            persistedActiveIds.forEach { sourceId ->
                insertSource(
                    BuiltInKomgaProviderFactory.slot(sourceId)!!,
                    sentinelId.value,
                )
            }
        }

        return InitializationPlan(
            sourceIdsToUnregister = sourceIdsToUnregister,
            activeSourceIds = persistedActiveIds,
            legacyApkNames = legacyApkNames,
        )
    }

    private fun ensureSentinel() =
        ExtensionTable
            .selectAll()
            .where {
                (ExtensionTable.pkgName eq packageId) and
                    (ExtensionTable.classFQName eq sentinelClassName)
            }.firstOrNull()
            ?.get(ExtensionTable.id)
            ?.also { id ->
                ExtensionTable.update({ ExtensionTable.id eq id }) {
                    it[apkName] = null
                    it[storeIndexUrl] = null
                    it[iconUrl] = ""
                    it[name] = "Komga built-in provider"
                    it[pkgName] = packageId
                    it[apkUrl] = null
                    it[jarUrl] = null
                    it[extensionLib] = "1.4"
                    it[versionName] = "1.4.68"
                    it[versionCode] = 68
                    it[lang] = "all"
                    it[contentWarning] = 0
                    it[isInstalled] = true
                    it[hasUpdate] = false
                    it[isObsolete] = false
                    it[classFQName] = sentinelClassName
                }
            }
            ?: ExtensionTable.insertAndGetId {
                it[apkName] = null
                it[storeIndexUrl] = null
                it[iconUrl] = ""
                it[name] = "Komga built-in provider"
                it[pkgName] = packageId
                it[apkUrl] = null
                it[jarUrl] = null
                it[extensionLib] = "1.4"
                it[versionName] = "1.4.68"
                it[versionCode] = 68
                it[lang] = "all"
                it[contentWarning] = 0
                it[isInstalled] = true
                it[hasUpdate] = false
                it[isObsolete] = false
                it[classFQName] = sentinelClassName
            }

    private fun insertSource(
        slot: BuiltInKomgaProviderFactory.Slot,
        extensionId: Int,
    ) {
        SourceTable.insert {
            it[id] = slot.sourceId
            it[name] = slot.defaultName
            it[lang] = "all"
            it[extension] = extensionId
            it[contentWarning] = 0
        }
    }

    private fun state(
        sourceId: Long,
        active: Boolean,
    ) = BuiltInKomgaLifecycleState(
        sourceId = sourceId.toString(),
        active = active,
        activeSourceIds = activeSourceIds().map(Long::toString),
    )

    private fun requireInitialized() {
        if (!isInitialized) {
            throw BuiltInKomgaLifecycleUnavailable(
                "built-in Komga factory failed to initialize",
            )
        }
    }

    private fun removeLegacyProviderJars(apkNames: Set<String>) {
        apkNames.forEach { apkName ->
            val jarName = apkName.substringBeforeLast(".apk") + ".jar"
            val jarPath = Path(applicationDirs.extensionsRoot) / jarName
            PackageTools.jarLoaderMap.remove(jarPath.absolutePathString())?.close()
            jarPath.deleteIfExists()
        }
    }

    private fun ensureBundledIcon() {
        val iconDirectory = File(applicationDirs.extensionsRoot, "icon")
        iconDirectory.mkdirs()
        val iconFile = File(iconDirectory, "$packageId.png")
        val input =
            BuiltInKomgaLifecycle::class.java.getResourceAsStream("/icon/komga.png")
                ?: throw BuiltInKomgaLifecycleUnavailable(
                    "built-in Komga icon failed to initialize",
                )
        input.use { source ->
            iconFile.outputStream().use { output -> source.copyTo(output) }
        }
        logger.debug { "Cached bundled Komga provider icon" }
    }
}

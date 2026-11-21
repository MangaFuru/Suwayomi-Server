package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.json.JsonMapper
import io.javalin.json.fromJsonString
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.Source.preferenceScreenMap
import suwayomi.tachidesk.manga.impl.extension.Extension.proxyExtensionIconUrl
import suwayomi.tachidesk.manga.impl.util.source.GetSource.getSourceOrNull
import suwayomi.tachidesk.manga.impl.util.source.GetSource.getSourceOrStub
import suwayomi.tachidesk.manga.impl.util.source.GetSource.unregisterSource
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.SourceDataClass
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import uy.kohesive.injekt.injectLazy
import xyz.nulldev.androidcompat.androidimpl.CustomContext
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.androidcompat.io.sharedprefs.SourcePreferencePersistenceCapability

object Source {
    private val logger = KotlinLogging.logger {}

    suspend fun getSourceList(): List<SourceDataClass> {
        return suspendTransaction {
            SourceTable.selectAll().mapNotNull {
                val catalogueSource = getSourceOrNull(it[SourceTable.id].value) ?: return@mapNotNull null
                val sourceExtension = ExtensionTable.selectAll().where { ExtensionTable.id eq it[SourceTable.extension] }.first()

                SourceDataClass(
                    id = it[SourceTable.id].value.toString(),
                    name = it[SourceTable.name],
                    lang = it[SourceTable.lang],
                    iconUrl = proxyExtensionIconUrl(sourceExtension[ExtensionTable.pkgName]),
                    supportsLatest = catalogueSource.supportsLatest,
                    isConfigurable = catalogueSource is ConfigurableSource,
                    isNsfw = it[SourceTable.contentWarning] >= ContentWarning.MIXED.ordinal,
                    displayName = catalogueSource.toString(),
                    baseUrl = runCatching { (catalogueSource as? HttpSource)?.baseUrl }.getOrNull(),
                )
            }
        }
    }

    suspend fun getSource(sourceId: Long): SourceDataClass? { // all the data extracted fresh form the source instance
        return suspendTransaction {
            val source = SourceTable.selectAll().where { SourceTable.id eq sourceId }.firstOrNull() ?: return@suspendTransaction null
            val catalogueSource = getSourceOrNull(sourceId) ?: return@suspendTransaction null
            val extension = ExtensionTable.selectAll().where { ExtensionTable.id eq source[SourceTable.extension] }.first()

            SourceDataClass(
                id = sourceId.toString(),
                name = source[SourceTable.name],
                lang = source[SourceTable.lang],
                iconUrl = proxyExtensionIconUrl(extension[ExtensionTable.pkgName]),
                supportsLatest = catalogueSource.supportsLatest,
                isConfigurable = catalogueSource is ConfigurableSource,
                isNsfw = source[SourceTable.contentWarning] >= ContentWarning.MIXED.ordinal,
                displayName = catalogueSource.toString(),
                baseUrl = runCatching { (catalogueSource as? HttpSource)?.baseUrl }.getOrNull(),
            )
        }
    }

    private val context: CustomContext by injectLazy()

    /**
     * (2021-11) Clients should support these types for extensions to work properly
     * - EditTextPreference
     * - SwitchPreferenceCompat
     * - ListPreference
     * - CheckBoxPreference
     * - MultiSelectListPreference
     */
    data class PreferenceObject(
        val type: String,
        val props: Any,
    )

    var preferenceScreenMap: MutableMap<Long, PreferenceScreen> = mutableMapOf()

    /**
     *  Gets a source's PreferenceScreen, puts the result into [preferenceScreenMap]
     */
    suspend fun getSourcePreferences(sourceId: Long): List<PreferenceObject> =
        getSourcePreferencesRaw(sourceId).map {
            PreferenceObject(it::class.java.simpleName, it)
        }

    suspend fun getSourcePreferencesRaw(sourceId: Long): List<Preference> {
        val source = getSourceOrStub(sourceId)

        if (source is ConfigurableSource) {
            val sourceShardPreferences = source.sourcePreferences()

            val screen = PreferenceScreen(context)
            screen.sharedPreferences = sourceShardPreferences

            source.setupPreferenceScreen(screen)

            preferenceScreenMap[sourceId] = screen

            return screen.preferences
        }
        return emptyList()
    }

    data class SourcePreferenceChange(
        val position: Int,
        val value: String,
    )

    /** GOMANGA_SOURCE_PREFERENCE_TRANSACTION_V1 */
    data class SourcePreferenceUpdateResult(
        val status: String,
        val code: String? = null,
        val warning: String? = null,
        val source: SourceDataClass? = null,
    )

    data class SourcePreferencePersistenceCapabilities(
        val supportsMemoryOnlySourcePreferences: Boolean,
        val requiresCanonicalCommit: Boolean,
        val hasLegacySourcePreferences: Boolean,
    )

    data class SourcePreferenceCaptureToken(val token: String)

    private suspend fun sourcePreferenceStore(sourceId: Long): JavaSharedPreferences? {
        val screen = preferenceScreenMap[sourceId] ?: runCatching {
            getSourcePreferencesRaw(sourceId)
            preferenceScreenMap[sourceId]
        }.getOrNull()
        return screen?.sharedPreferences as? JavaSharedPreferences
    }

    suspend fun sourcePreferencePersistenceCapabilities(sourceId: Long): SourcePreferencePersistenceCapabilities {
        // Non-configurable sources have no screen/store and remain usable.
        val memoryOnly = SourcePreferencePersistenceCapability.memoryOnly("source_$sourceId")
        return SourcePreferencePersistenceCapabilities(memoryOnly, true, sourcePreferenceStore(sourceId)?.hasLegacySourcePreferences() == true)
    }

    suspend fun registerSourcePreferenceCapture(sourceId: Long, token: String): Boolean =
        sourcePreferenceStore(sourceId)?.registerCanonicalCapture(token) == true

    suspend fun acknowledgeSourcePreferenceCapture(sourceId: Long, token: String): Boolean =
        sourcePreferenceStore(sourceId)?.acknowledgeCanonicalCapture(token, committed = true) == true

    private val jsonMapper: JsonMapper by injectLazy()

    suspend fun setSourcePreference(
        sourceId: Long,
        position: Int,
        value: String,
        getValue: (Preference) -> Any = { pref ->
            when (pref.defaultValueType) {
                "String" -> value
                "Boolean" -> value.toBoolean()
                "Set<String>" -> jsonMapper.fromJsonString<List<String>>(value).toSet()
                else -> throw RuntimeException("Unsupported type conversion")
            }
        },
    ): SourcePreferenceUpdateResult {
        val screen =
            preferenceScreenMap[sourceId]
                ?: return SourcePreferenceUpdateResult(
                    status = "invalid",
                    code = "preference_screen_unavailable",
                )
        val pref =
            screen.preferences.getOrNull(position)
                ?: return SourcePreferenceUpdateResult(
                    status = "invalid",
                    code = "invalid_preference_position",
                )

        if (!pref.isEnabled) {
            return SourcePreferenceUpdateResult(
                status = "invalid",
                code = "preference_disabled",
            )
        }

        val newValue =
            runCatching { getValue(pref) }
                .getOrElse {
                    return SourcePreferenceUpdateResult(
                        status = "invalid",
                        code = "invalid_preference_value",
                    )
                }

        if (!pref.callChangeListener(newValue)) {
            return SourcePreferenceUpdateResult(
                status = "rejected",
                code = "preference_change_rejected",
            )
        }

        pref.saveNewValue(newValue)
        unregisterSource(sourceId)

        val rebuiltSource = getSource(sourceId)
        return SourcePreferenceUpdateResult(
            status = "accepted",
            warning = if (rebuiltSource == null) "source_rebuild_failed" else null,
            source = rebuiltSource,
        )
    }

    fun getSourcesMetaMaps(ids: List<Long>): Map<Long, Map<String, String>> =
        transaction {
            SourceMetaTable
                .selectAll()
                .where { SourceMetaTable.ref inList ids }
                .groupBy { it[SourceMetaTable.ref] }
                .mapValues { it.value.associate { it[SourceMetaTable.key] to it[SourceMetaTable.value] } }
                .withDefault { emptyMap() }
        }

    fun modifyMeta(
        sourceId: Long,
        key: String,
        value: String,
    ) {
        modifySourceMetas(mapOf(sourceId to mapOf(key to value)))
    }

    fun modifySourceMetas(metaBySourceIds: Map<Long, Map<String, String>>) {
        transaction {
            val sourceIds = metaBySourceIds.keys
            val metaKeys = metaBySourceIds.flatMap { it.value.keys }

            val dbMetaBySourceId =
                SourceMetaTable
                    .selectAll()
                    .where { (SourceMetaTable.ref inList sourceIds) and (SourceMetaTable.key inList metaKeys) }
                    .groupBy { it[SourceMetaTable.ref] }

            val existingMetaByMetaId =
                sourceIds.flatMap { sourceId ->
                    val metaByKey = dbMetaBySourceId[sourceId].orEmpty().associateBy { it[SourceMetaTable.key] }
                    val existingMetas = metaBySourceIds[sourceId].orEmpty().filter { (key) -> key in metaByKey.keys }

                    existingMetas.map { entry ->
                        val metaId = metaByKey[entry.key]!![SourceMetaTable.id].value

                        metaId to entry
                    }
                }

            val newMetaBySourceId =
                sourceIds.flatMap { sourceId ->
                    val metaByKey = dbMetaBySourceId[sourceId].orEmpty().associateBy { it[SourceMetaTable.key] }

                    metaBySourceIds[sourceId]
                        .orEmpty()
                        .filter { entry -> entry.key !in metaByKey.keys }
                        .map { entry -> sourceId to entry }
                }

            if (existingMetaByMetaId.isNotEmpty()) {
                BatchUpdateStatement(SourceMetaTable)
                    .apply {
                        existingMetaByMetaId.forEach { (metaId, entry) ->
                            addBatch(EntityID(metaId, SourceMetaTable))
                            this[SourceMetaTable.value] = entry.value
                        }
                    }.toExecutable()
                    .execute(this@transaction)
            }

            if (newMetaBySourceId.isNotEmpty()) {
                SourceMetaTable.batchInsert(newMetaBySourceId) { (sourceId, entry) ->
                    this[SourceMetaTable.ref] = sourceId
                    this[SourceMetaTable.key] = entry.key
                    this[SourceMetaTable.value] = entry.value
                }
            }
        }
    }
}

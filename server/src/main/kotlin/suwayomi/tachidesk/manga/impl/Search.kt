package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.javalin.json.JsonMapper
import io.javalin.json.fromJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import suwayomi.tachidesk.manga.impl.MangaList.processEntries
import suwayomi.tachidesk.manga.impl.util.source.GetSource.getSourceOrStub
import suwayomi.tachidesk.manga.model.dataclass.PagedMangaListDataClass
import uy.kohesive.injekt.injectLazy

object Search {
    suspend fun sourceSearch(
        sourceId: Long,
        searchTerm: String,
        pageNum: Int,
    ): PagedMangaListDataClass {
        val source = getSourceOrStub(sourceId)
        val searchManga = source.getSearchManga(pageNum, searchTerm, getFilterListOf(source))
        return searchManga.processEntries(sourceId)
    }

    suspend fun sourceFilter(
        sourceId: Long,
        pageNum: Int,
        filter: FilterData,
    ): PagedMangaListDataClass {
        val source = getSourceOrStub(sourceId)
        val filterList = source.getFilterList()
        if (filter.expectedFilterDefinition != null) {
            if (FilterDefinitionToken.forList(filterList) != filter.expectedFilterDefinition) throw FilterDefinitionChanged()
            if (filter.filter != null) updateFilterListValidated(filterList, filter.filter)
        } else if (filter.filter != null) {
            updateFilterList(filterList, filter.filter)
        }
        val searchManga = source.getSearchManga(pageNum, filter.searchTerm ?: "", filterList)
        return searchManga.processEntries(sourceId)
    }

    private val filterListCache = mutableMapOf<Long, FilterList>()

    private fun getFilterListOf(
        source: Source,
        reset: Boolean = false,
    ): FilterList {
        if (reset || !filterListCache.containsKey(source.id)) {
            filterListCache[source.id] = source.getFilterList()
        }
        return filterListCache[source.id]!!
    }

    suspend fun getFilterList(
        sourceId: Long,
        reset: Boolean,
    ): List<FilterObject> = filterObjects(getFilterListOf(getSourceOrStub(sourceId), reset))

    data class FilterSnapshot(val items: List<FilterObject>, val definitionToken: String)

    suspend fun getFilterSnapshot(sourceId: Long, reset: Boolean): FilterSnapshot {
        val filters = getFilterListOf(getSourceOrStub(sourceId), reset)
        return FilterSnapshot(filterObjects(filters), FilterDefinitionToken.forList(filters))
    }

    private fun filterObjects(filters: FilterList): List<FilterObject> {
        return filters.list.map {
            FilterObject(
                when (it) {
                    is Filter.Header -> "Header"
                    is Filter.Separator -> "Separator"
                    is Filter.Select<*> -> "Select"
                    is Filter.Text -> "Text"
                    is Filter.CheckBox -> "CheckBox"
                    is Filter.TriState -> "TriState"
                    is Filter.Group<*> -> "Group"
                    is Filter.Sort -> "Sort"
                    else -> throw RuntimeException("sealed class Cannot have more Subtypes!")
                },
                when (it) {
                    is Filter.Group<*> -> {
                        SerializableGroup(
                            it.name,
                            it.state.map { item ->
                                when (item) {
                                    is Filter.CheckBox -> FilterObject("CheckBox", item)
                                    is Filter.TriState -> FilterObject("TriState", item)
                                    is Filter.Text -> FilterObject("Text", item)
                                    is Filter.Select<*> -> FilterObject("Select", item)
                                    else -> throw RuntimeException("Illegal Group item type!")
                                }
                            },
                        )
                    }

                    else -> {
                        it
                    }
                },
            )
        }
    }

    private fun Filter.Select<*>.getValuesType(): String = values::class.java.componentType!!.simpleName

    class SerializableGroup(
        name: String,
        state: List<FilterObject>,
    ) : Filter<List<FilterObject>>(name, state)

    data class FilterObject(
        val type: String,
        val filter: Filter<*>,
    )

    suspend fun setFilter(
        sourceId: Long,
        changes: List<FilterChange>,
    ) {
        val source = getSourceOrStub(sourceId)
        val filterList = getFilterListOf(source, false)
        updateFilterList(filterList, changes)
    }

    private fun updateFilterList(
        filterList: FilterList,
        changes: List<FilterChange>,
    ): FilterList {
        changes.forEach { change ->
            when (val filter = filterList[change.position]) {
                is Filter.Header -> {
                    // NOOP
                }

                is Filter.Separator -> {
                    // NOOP
                }

                is Filter.Select<*> -> {
                    filter.state = change.state.toInt()
                }

                is Filter.Text -> {
                    filter.state = change.state
                }

                is Filter.CheckBox -> {
                    filter.state = change.state.toBooleanStrict()
                }

                is Filter.TriState -> {
                    filter.state = change.state.toInt()
                }

                is Filter.Group<*> -> {
                    val groupChange = jsonMapper.fromJsonString<FilterChange>(change.state)

                    when (val groupFilter = filter.state[groupChange.position]) {
                        is Filter.CheckBox -> groupFilter.state = groupChange.state.toBooleanStrict()
                        is Filter.TriState -> groupFilter.state = groupChange.state.toInt()
                        is Filter.Text -> groupFilter.state = groupChange.state
                        is Filter.Select<*> -> groupFilter.state = groupChange.state.toInt()
                    }
                }

                is Filter.Sort -> {
                    filter.state = jsonMapper.fromJsonString(change.state, Filter.Sort.Selection::class.java)
                }
            }
        }
        return filterList
    }

    private fun updateFilterListValidated(filterList: FilterList, changes: List<FilterChange>) {
        try {
            changes.forEach { change ->
                val target = filterList.getOrNull(change.position) ?: throw IllegalArgumentException()
                when (target) {
                    is Filter.Select<*> -> require(change.state.toInt() in target.values.indices)
                    is Filter.CheckBox -> change.state.toBooleanStrict()
                    is Filter.TriState -> require(change.state.toInt() in 0..2)
                    is Filter.Group<*> -> {
                        val objectValue = Json.parseToJsonElement(change.state) as? JsonObject ?: throw IllegalArgumentException()
                        val position = objectValue["position"] as? JsonPrimitive ?: throw IllegalArgumentException()
                        val state = objectValue["state"] as? JsonPrimitive ?: throw IllegalArgumentException()
                        require(!position.isString && state.isString)
                        val nested = FilterChange(position.intOrNull ?: throw IllegalArgumentException(), state.content)
                        val child = target.state.getOrNull(nested.position) as? Filter<*> ?: throw IllegalArgumentException()
                        when (child) { is Filter.Select<*> -> require(nested.state.toInt() in child.values.indices); is Filter.CheckBox -> nested.state.toBooleanStrict(); is Filter.TriState -> require(nested.state.toInt() in 0..2); is Filter.Text -> Unit; else -> throw IllegalArgumentException() }
                    }
                    is Filter.Sort -> {
                        val objectValue = Json.parseToJsonElement(change.state) as? JsonObject ?: throw IllegalArgumentException()
                        val index = objectValue["index"] as? JsonPrimitive ?: throw IllegalArgumentException()
                        val ascending = objectValue["ascending"] as? JsonPrimitive ?: throw IllegalArgumentException()
                        require(!index.isString && !ascending.isString && ascending.booleanOrNull != null)
                        require((index.intOrNull ?: throw IllegalArgumentException()) in target.values.indices)
                    }
                    is Filter.Header, is Filter.Separator -> throw IllegalArgumentException()
                    is Filter.Text -> Unit
                    else -> throw IllegalArgumentException()
                }
            }
        } catch (_: Exception) { throw FilterDefinitionChanged() }
        updateFilterList(filterList, changes)
    }

    suspend fun buildFilterList(
        sourceId: Long,
        changes: List<FilterChange>,
    ): FilterList {
        val source = getSourceOrStub(sourceId)
        val filterList = source.getFilterList()
        return updateFilterList(filterList, changes)
    }

    private val jsonMapper: JsonMapper by injectLazy()

    @Serializable
    data class FilterChange(
        val position: Int,
        val state: String,
    )

    @Serializable
    data class FilterData(
        val searchTerm: String?,
        val filter: List<FilterChange>?,
        val expectedFilterDefinition: String? = null,
    )

    class FilterDefinitionChanged : RuntimeException("FILTER_DEFINITION_CHANGED")

    @Suppress("UNUSED_PARAMETER")
    fun sourceGlobalSearch(searchTerm: String) {
        // TODO
    }
}

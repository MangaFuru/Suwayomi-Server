package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import io.javalin.json.JsonMapper
import io.javalin.json.JavalinJackson3
import kotlinx.coroutines.runBlocking
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Uses the production Search path, stopping at the provider boundary so no
 * database or external network is needed to prove dispatch and list identity. */
object FilterDefinitionContract {
    private class Choice(values: Array<String>) : Filter.Select<String>("Genre", values)
    private class Toggle : Filter.CheckBox("Enabled", true)
    private class Tri : Filter.TriState("Audience", 0)
    private class Order : Filter.Sort("Order", arrayOf("Name", "Date"), Selection(0, true))
    private class Group : Filter.Group<Filter<*>>("Group", listOf(Toggle(), Tri()))
    private class ReachedProvider : RuntimeException()
    private class Probe : StubSource(982734L) {
        var reads = 0
        var searches = 0
        var values = arrayOf("All", "Action", "Comedy")
        lateinit var last: FilterList
        override fun getFilterList(): FilterList {
            reads += 1
            return FilterList(Filter.Header("Hint"), Filter.Separator(), Choice(values), Toggle(), Tri(), Order(), Group()).also { last = it }
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            check(filters === last) { "Search must use the exact validated list" }
            searches += 1
            throw ReachedProvider()
        }
    }

    fun canonicalDefinition() {
        // Frozen v1 UTF-8 canonical bytes: v1|0:6#Header1#A;
        check(FilterDefinitionToken.forList(FilterList(Filter.Header("A"))) == "v1:a8061237356d02f9699e438e7122095db895dfe47eff214ab909b6be9ea3b683")
        val source = Probe()
        val a = source.getFilterList()
        val token = FilterDefinitionToken.forList(a)
        (a[2] as Filter.Select<*>).state = 2
        (a[3] as Filter.CheckBox).state = false
        check(FilterDefinitionToken.forList(a) == token) { "Mutable states changed definition" }
        source.values = arrayOf("All", "Comedy", "Action")
        check(FilterDefinitionToken.forList(source.getFilterList()) != token) { "Reordering must invalidate" }
        check(FilterDefinitionToken.forList(FilterList(Choice(emptyArray()))) != FilterDefinitionToken.forList(FilterList(Choice(arrayOf("")))))
        check(FilterDefinitionToken.forList(FilterList(Choice(arrayOf("a,b", "c")))) != FilterDefinitionToken.forList(FilterList(Choice(arrayOf("a", "b,c")))))
        check(FilterDefinitionToken.forList(FilterList(Filter.Header("A"), Choice(arrayOf("x")))) != FilterDefinitionToken.forList(FilterList(Choice(arrayOf("x")), Filter.Header("A"))))
        val group = Group()
        val grouped = FilterDefinitionToken.forList(FilterList(group))
        (group.state[0] as Filter.CheckBox).state = false
        check(FilterDefinitionToken.forList(FilterList(group)) == grouped)
        val reordered = object : Filter.Group<Filter<*>>("Group", group.state.reversed()) {}
        check(FilterDefinitionToken.forList(FilterList(reordered)) != grouped)
    }

    private fun withProbe(body: suspend (Probe) -> Unit) = runBlocking {
        val source = Probe()
        GetSource.registerSource(source.id to source)
        try { body(source) } finally { GetSource.unregisterSource(source.id) }
    }

    fun snapshotAndDispatch() = withProbe { source ->
        val snapshot = Search.getFilterSnapshot(source.id, true)
        check(source.reads == 1 && snapshot.items.size == 7)
        check(snapshot.definitionToken == FilterDefinitionToken.forList(source.last))
        val changes = listOf(
            Search.FilterChange(2, "2"), Search.FilterChange(3, "false"), Search.FilterChange(4, "2"),
            Search.FilterChange(5, "{\"index\":1,\"ascending\":false}"),
            Search.FilterChange(6, "{\"position\":0,\"state\":\"false\"}"),
            Search.FilterChange(6, "{\"position\":1,\"state\":\"2\"}"),
        )
        try {
            Search.sourceFilter(source.id, 1, Search.FilterData("", changes, snapshot.definitionToken))
            error("Provider probe must run")
        } catch (_: ReachedProvider) { }
        check(source.reads == 2 && source.searches == 1) { "Extra getFilterList or search" }
        check((source.last[2] as Filter.Select<*>).state == 2)
        check(!(source.last[3] as Filter.CheckBox).state)
        check((source.last[4] as Filter.TriState).state == 2)
        check((source.last[5] as Filter.Sort).state == Filter.Sort.Selection(1, false))
        val children = (source.last[6] as Filter.Group<*>).state
        check(!(children[0] as Filter.CheckBox).state && (children[1] as Filter.TriState).state == 2)
    }

    fun mismatchNeverDispatches() = withProbe { source ->
        val snapshot = Search.getFilterSnapshot(source.id, true)
        source.values = arrayOf("All", "Comedy", "Action")
        try {
            Search.sourceFilter(source.id, 1, Search.FilterData("", listOf(Search.FilterChange(2, "1")), snapshot.definitionToken))
            error("Reordered definition was accepted")
        } catch (_: Search.FilterDefinitionChanged) { }
        check(source.reads == 2 && source.searches == 0)
    }

    fun invalidStatesNeverDispatch() = withProbe { source ->
        val token = Search.getFilterSnapshot(source.id, true).definitionToken
        val invalid = listOf(
            Search.FilterChange(-1, "0"), Search.FilterChange(99, "0"), Search.FilterChange(0, "0"),
            Search.FilterChange(1, "0"), Search.FilterChange(2, "3"), Search.FilterChange(2, "-1"),
            Search.FilterChange(2, "x"), Search.FilterChange(3, "yes"), Search.FilterChange(4, "3"),
            Search.FilterChange(5, "{\"index\":2,\"ascending\":true}"),
            Search.FilterChange(5, "{\"index\":1}"),
            Search.FilterChange(5, "{\"index\":1,\"ascending\":\"false\"}"),
            Search.FilterChange(5, "{\"index\":\"1\",\"ascending\":false}"),
            Search.FilterChange(6, "{\"position\":2,\"state\":\"false\"}"),
            Search.FilterChange(6, "{\"position\":0,\"state\":\"yes\"}"),
            Search.FilterChange(6, "{\"position\":\"0\",\"state\":\"false\"}"),
            Search.FilterChange(6, "{\"position\":0,\"state\":false}"),
        )
        for (change in invalid) {
            try {
                Search.sourceFilter(source.id, 1, Search.FilterData("", listOf(change), token))
                error("Invalid filter state accepted: $change")
            } catch (_: Search.FilterDefinitionChanged) { }
        }
        check(source.reads == invalid.size + 1 && source.searches == 0)
    }

    fun legacyDispatchIsPreserved() = withProbe { source ->
        try {
            // The old protocol ignores Header mutations and has no token.
            Search.sourceFilter(source.id, 1, Search.FilterData("", listOf(Search.FilterChange(0, "ignored"))))
            error("Legacy provider probe must run")
        } catch (_: ReachedProvider) { }
        check(source.reads == 1 && source.searches == 1)
    }

    @JvmStatic fun main(args: Array<String>) {
        startKoin { modules(module { single<JsonMapper> { JavalinJackson3() } }) }
        try {
            canonicalDefinition()
            snapshotAndDispatch()
            mismatchNeverDispatches()
            invalidStatesNeverDispatch()
            legacyDispatchIsPreserved()
        } finally { stopKoin() }
        println("sourceFilterDefinitionBehavior=PASS cases=5 invalidStates=17")
    }
}

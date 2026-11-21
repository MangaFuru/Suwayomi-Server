package suwayomi.tachidesk.manga.impl

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.security.MessageDigest

object FilterDefinitionToken {
    fun forList(list: FilterList): String {
        val canonical = buildString { append("v1|"); list.list.forEachIndexed { i, f -> appendFilter(this, i, f) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }
    private fun appendFilter(out: StringBuilder, position: Int, filter: Filter<*>) {
        out.append(position).append(':'); appendString(out, typeOf(filter)); appendString(out, filter.name)
        when (filter) {
            is Filter.Select<*> -> { out.append('S').append(filter.displayValues.size).append(':'); filter.displayValues.forEach { appendString(out, it) } }
            is Filter.Sort -> { out.append('O').append(filter.values.size).append(':'); filter.values.forEach { appendString(out, it) } }
            is Filter.Group<*> -> { out.append('G').append(filter.state.size).append(':'); filter.state.forEachIndexed { i, c -> require(c is Filter<*>); appendFilter(out, i, c) } }
        }
        out.append(';')
    }
    private fun appendString(out: StringBuilder, value: String) { out.append(value.length).append('#').append(value) }
    private fun typeOf(filter: Filter<*>): String = when (filter) {
        is Filter.Header -> "Header"; is Filter.Separator -> "Separator"; is Filter.Select<*> -> "Select"; is Filter.Text -> "Text"; is Filter.CheckBox -> "CheckBox"; is Filter.TriState -> "TriState"; is Filter.Group<*> -> "Group"; is Filter.Sort -> "Sort"; else -> filter.javaClass.name
    }
}

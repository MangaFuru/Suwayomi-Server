/*
 * Adapted from keiyoushi/extensions-source at
 * 9f2565708438fbbcbe86d5c0f120571b0de0bbc7.
 * Licensed under the Apache License, Version 2.0.
 */
package eu.kanade.tachiyomi.extension.all.komga

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

val formatterDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
val formatterDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

fun parseDate(date: String): Long = try {
    formatterDate.parse(date)!!.time
} catch (_: ParseException) {
    0L
}

fun parseDateTime(date: String) = try {
    formatterDateTime.parse(date)!!.time
} catch (_: ParseException) {
    0L
}

fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    restartRequired: Boolean = false,
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

        setOnPreferenceChangeListener { _, newValue ->
            try {
                val text = newValue as String
                val result = text.isBlank() || validate?.invoke(text) ?: true
                result
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }.also(::addPreference)
}

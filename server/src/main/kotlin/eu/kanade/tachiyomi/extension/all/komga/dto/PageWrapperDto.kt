/*
 * Adapted from keiyoushi/extensions-source at
 * 9f2565708438fbbcbe86d5c0f120571b0de0bbc7.
 * Licensed under the Apache License, Version 2.0.
 */
package eu.kanade.tachiyomi.extension.all.komga.dto

import kotlinx.serialization.Serializable

@Serializable
class PageWrapperDto<T>(
    val content: List<T>,
    val empty: Boolean,
    val first: Boolean,
    val last: Boolean,
    val number: Long,
    val numberOfElements: Long,
    val size: Long,
    val totalElements: Long,
    val totalPages: Long,
)

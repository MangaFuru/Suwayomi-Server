package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

data class PageDataClass(
    val index: Int,
    var imageUrl: String,
)

data class PageImageResourceDataClass(
    val index: Int,
    val imageUrl: String?,
    val headers: Map<String, String> = emptyMap(),
    val cacheKey: String,
    val fallbackImageUrl: String,
    val requiresProxy: Boolean = false,
)

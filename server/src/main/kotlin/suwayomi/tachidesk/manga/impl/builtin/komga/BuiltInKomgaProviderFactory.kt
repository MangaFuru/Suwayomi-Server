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

import eu.kanade.tachiyomi.extension.all.komga.BUILT_IN_KOMGA_INSTANCE_IDS
import eu.kanade.tachiyomi.extension.all.komga.Komga

/**
 * Server-owned factory for the bundled provider.
 *
 * Payload availability is intentionally separate from source activation:
 * callers must request one allowlisted slot through [BuiltInKomgaLifecycle].
 */
object BuiltInKomgaProviderFactory {
    data class Slot(
        val sourceId: Long,
        val defaultName: String,
    )

    val slots: List<Slot> =
        listOf(
            Slot(4508733312114627536L, "Komga"),
            Slot(8074481155021144106L, "Komga (2)"),
            Slot(5132811728275817394L, "Komga (3)"),
        )

    init {
        check(slots.map(Slot::sourceId) == BUILT_IN_KOMGA_INSTANCE_IDS)
        check(slots.map(Slot::sourceId).distinct().size == slots.size)
    }

    fun slot(sourceId: Long): Slot? = slots.firstOrNull { it.sourceId == sourceId }

    fun create(sourceId: Long): Komga {
        if (slot(sourceId) == null) {
            throw BuiltInKomgaInvalidSourceId(sourceId.toString())
        }
        return Komga(lang = "all", id = sourceId)
    }

    fun validate() {
        check(slots.size == 3)
        check(slots.map(Slot::defaultName) == listOf("Komga", "Komga (2)", "Komga (3)"))
    }
}

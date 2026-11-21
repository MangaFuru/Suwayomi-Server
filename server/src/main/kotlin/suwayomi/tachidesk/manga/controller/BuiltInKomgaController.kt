package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the GoManga project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.builtin.komga.BuiltInKomgaInvalidSourceId
import suwayomi.tachidesk.manga.impl.builtin.komga.BuiltInKomgaLifecycle
import suwayomi.tachidesk.manga.impl.builtin.komga.BuiltInKomgaLifecycleUnavailable

data class BuiltInKomgaLifecycleErrorResponse(
    val code: String,
    val message: String,
    val sourceId: String? = null,
)

object BuiltInKomgaController {
    val activate = Handler { context -> mutate(context, BuiltInKomgaLifecycle::activate) }
    val deactivate = Handler { context -> mutate(context, BuiltInKomgaLifecycle::deactivate) }

    private fun mutate(
        context: Context,
        operation: (String) -> Any,
    ) {
        val sourceId = context.pathParam("sourceId")
        try {
            context.json(operation(sourceId))
        } catch (error: BuiltInKomgaInvalidSourceId) {
            context
                .status(HttpStatus.BAD_REQUEST)
                .json(
                    BuiltInKomgaLifecycleErrorResponse(
                        code = "invalid_source_id",
                        sourceId = error.sourceId,
                        message = error.message ?: "source id is not a built-in Komga slot",
                    ),
                )
        } catch (error: BuiltInKomgaLifecycleUnavailable) {
            context
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .json(
                    BuiltInKomgaLifecycleErrorResponse(
                        code = "lifecycle_unavailable",
                        message = error.message ?: "built-in Komga factory failed to initialize",
                    ),
                )
        }
    }
}

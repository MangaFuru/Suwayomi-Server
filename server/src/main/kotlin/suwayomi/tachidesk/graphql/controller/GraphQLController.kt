/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.controller

import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.websocket.WsConfig
import suwayomi.tachidesk.graphql.server.TachideskGraphQLServer
import suwayomi.tachidesk.graphql.server.TemporaryFileStorage
import suwayomi.tachidesk.server.JavalinSetup.future
import kotlin.io.path.inputStream

object GraphQLController {
    // GoManga slim-mode: build the GraphQL schema lazily, on the first GraphQL
    // request, instead of eagerly during Javalin route registration. The schema
    // build is a heavy graphql-kotlin reflection pass (~4s on the iOS
    // pure-interpreter OpenJ9 JVM) and is NOT required by the REST readiness
    // endpoint (/api/v1/settings/about), so deferring it lets the embedded server
    // answer REST ~4s sooner on cold start. The first GraphQL query pays the
    // one-time build cost (the host can hide it by issuing a warm-up query off the
    // critical path). SYNCHRONIZED lazy => safe under concurrent first requests.
    private val server by lazy { TachideskGraphQLServer.create() }

    /** execute graphql query */
    fun execute(ctx: Context) {
        ctx.future(
            future {
                server.execute(ctx)
            },
        )
    }

    fun playground(ctx: Context) {
        ctx.contentType(ContentType.TEXT_HTML)
        ctx.result(javaClass.getResourceAsStream("/graphql-playground.html")!!)
    }

    fun retrieveFile(ctx: Context) {
        val filename = ctx.pathParam("file")
        val file = TemporaryFileStorage.retrieveFile(filename)
        ctx.contentType("application/octet-stream")
        ctx.header("Content-Disposition", """attachment; filename="$filename"""")
        ctx.result(file.inputStream())
    }

    fun webSocket(ws: WsConfig) {
        ws.onMessage { ctx ->
            server.handleSubscriptionMessage(ctx)
        }
        ws.onClose { ctx ->
            server.handleSubscriptionDisconnect(ctx)
        }
    }
}

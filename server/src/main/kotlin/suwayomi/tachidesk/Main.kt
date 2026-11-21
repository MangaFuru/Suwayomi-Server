package suwayomi.tachidesk

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.applicationSetup
import suwayomi.tachidesk.server.database.EmbeddedDatabaseAdmission
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread

fun main() {
    // Trigger graphql schema generation as early as possible, takes multiple seconds, delaying the javalin startup otherwise
    if (!java.lang.Boolean.getBoolean("suwayomi.tachidesk.gomanga.slimMode") ||
        java.lang.Boolean.getBoolean("suwayomi.tachidesk.gomanga.graphqlEnabled")) {
        GraphQLSchemaProvider.init()
    }
    if (java.lang.Boolean.getBoolean("suwayomi.tachidesk.gomanga.slimMode")) {
        // Prepare routes and Jetty while the existing database is being validated.
        // Only start the listener after both the data and source registry are ready.
        val root = System.getProperty("suwayomi.tachidesk.config.server.rootDir")
        val setupReady = CompletableFuture<Unit>()
        val database = root?.let { configuredRoot ->
            FutureTask {
                EmbeddedDatabaseAdmission.prepareDuringSetup(ApplicationDirs(configuredRoot)) { setupReady.get() }
            }.also { task -> thread(name = "gomanga-read-database", isDaemon = true) { task.run() } }
        }
        val http = FutureTask { JavalinSetup.prepareHttp() }
        try {
            applicationSetup(
                prepareDatabase = { dirs ->
                    if (database == null) {
                        EmbeddedDatabaseAdmission.prepare(dirs)
                    } else {
                        check(dirs.dataRoot == root) { "Embedded data root changed during startup" }
                        setupReady.complete(Unit)
                        database.awaitResult()
                    }
                },
                onModulesReady = {
                    thread(name = "gomanga-prepare-http", isDaemon = true) { http.run() }
                },
            )
            JavalinSetup.startPrepared(http.awaitResult())
        } finally {
            setupReady.cancel(false)
            http.cancel(false)
        }
    } else {
        applicationSetup()
        JavalinSetup.javalinSetup()
    }
}


private fun <T> FutureTask<T>.awaitResult(): T =
    try {
        get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

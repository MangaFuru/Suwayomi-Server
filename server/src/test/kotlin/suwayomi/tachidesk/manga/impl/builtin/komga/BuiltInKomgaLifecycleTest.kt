package suwayomi.tachidesk.manga.impl.builtin.komga

/*
 * Copyright (C) Contributors to the GoManga project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.test.ApplicationTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltInKomgaLifecycleTest : ApplicationTest() {
    private val first = 4508733312114627536L
    private val second = 8074481155021144106L
    private val third = 5132811728275817394L
    private val forcedInitializationFailureProperty =
        "suwayomi.tachidesk.gomanga.failBuiltInKomgaLifecycleInit"

    @BeforeEach
    fun resetBeforeTest() {
        System.clearProperty(forcedInitializationFailureProperty)
        BuiltInKomgaLifecycle.initialize()
        BuiltInKomgaLifecycle.allowedSourceIds.forEach(BuiltInKomgaLifecycle::deactivate)
    }

    @AfterEach
    fun resetAfterTest() {
        System.clearProperty(forcedInitializationFailureProperty)
        if (!BuiltInKomgaLifecycle.isInitialized) {
            BuiltInKomgaLifecycle.initialize()
        }
        BuiltInKomgaLifecycle.allowedSourceIds.forEach(BuiltInKomgaLifecycle::deactivate)
    }

    @Test
    fun `provider is ready but zero Komga sources are active by default`() {
        assertTrue(BuiltInKomgaLifecycle.isInitialized)
        assertEquals(emptyList(), BuiltInKomgaLifecycle.activeSourceIds())
        assertNull(GetSource.getSourceOrNull(first))
        assertNull(GetSource.getSourceOrNull(second))
        assertNull(GetSource.getSourceOrNull(third))
    }

    @Test
    fun `allowed slots activate and deactivate independently and idempotently`() {
        val firstActivation = BuiltInKomgaLifecycle.activate(first)
        val repeatedActivation = BuiltInKomgaLifecycle.activate(first)
        BuiltInKomgaLifecycle.activate(second)

        assertEquals(firstActivation, repeatedActivation)
        assertEquals(listOf(first, second), BuiltInKomgaLifecycle.activeSourceIds())
        assertNotNull(GetSource.getSourceOrNull(first))
        assertNotNull(GetSource.getSourceOrNull(second))
        assertTrue(Source.getSourcePreferences(first).isNotEmpty())

        val deactivation = BuiltInKomgaLifecycle.deactivate(second)
        val repeatedDeactivation = BuiltInKomgaLifecycle.deactivate(second)

        assertEquals(deactivation, repeatedDeactivation)
        assertEquals(listOf(first), BuiltInKomgaLifecycle.activeSourceIds())
        assertNotNull(GetSource.getSourceOrNull(first))
        assertNull(GetSource.getSourceOrNull(second))
    }

    @Test
    fun `invalid id leaves the exact active set unchanged`() {
        BuiltInKomgaLifecycle.activate(first)

        assertFailsWith<BuiltInKomgaInvalidSourceId> {
            BuiltInKomgaLifecycle.activate(42L)
        }

        assertEquals(listOf(first), BuiltInKomgaLifecycle.activeSourceIds())
    }

    @Test
    fun `restart restores zero active Komga slots`() {
        assertRestartRestores()
    }

    @Test
    fun `restart restores exactly one active Komga slot`() {
        assertRestartRestores(second)
    }

    @Test
    fun `restart restores exactly two active Komga slots`() {
        assertRestartRestores(first, third)
    }

    @Test
    fun `restart restores exactly three active Komga slots`() {
        assertRestartRestores(first, second, third)
    }

    @Test
    fun `forced lifecycle initialization failure is surfaced and recoverable`() {
        BuiltInKomgaLifecycle.activate(first)
        GetSource.unregisterAllSources()

        System.setProperty(forcedInitializationFailureProperty, "true")
        val failure =
            assertFailsWith<BuiltInKomgaLifecycleUnavailable> {
                BuiltInKomgaLifecycle.initialize()
            }

        assertEquals("built-in Komga factory failed to initialize", failure.message)
        assertFalse(BuiltInKomgaLifecycle.isInitialized)
        assertFailsWith<BuiltInKomgaLifecycleUnavailable> {
            BuiltInKomgaLifecycle.activeSourceIds()
        }

        System.clearProperty(forcedInitializationFailureProperty)
        BuiltInKomgaLifecycle.initialize()

        assertEquals(listOf(first), BuiltInKomgaLifecycle.activeSourceIds())
        assertNotNull(GetSource.getSourceOrNull(first))
    }

    @Test
    fun `built in package is hidden from extension inventory and uninstall`() {
        BuiltInKomgaLifecycle.activate(first)

        assertFalse(
            ExtensionsList
                .extensionTableAsDataClass()
                .any { it.pkgName == BuiltInKomgaLifecycle.packageId },
        )
        assertFailsWith<BuiltInKomgaExtensionMutationRejected> {
            Extension.uninstallExtension(BuiltInKomgaLifecycle.packageId)
        }
        assertEquals(listOf(first), BuiltInKomgaLifecycle.activeSourceIds())
    }

    private fun assertRestartRestores(vararg expectedSourceIds: Long) {
        expectedSourceIds.forEach(BuiltInKomgaLifecycle::activate)
        GetSource.unregisterAllSources()

        BuiltInKomgaLifecycle.initialize()

        assertEquals(expectedSourceIds.toList(), BuiltInKomgaLifecycle.activeSourceIds())
        BuiltInKomgaLifecycle.allowedSourceIds.forEach { sourceId ->
            if (sourceId in expectedSourceIds) {
                assertNotNull(GetSource.getSourceOrNull(sourceId))
            } else {
                assertNull(GetSource.getSourceOrNull(sourceId))
            }
        }
    }
}

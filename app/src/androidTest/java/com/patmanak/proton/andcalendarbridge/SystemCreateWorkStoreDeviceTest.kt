// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWork
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite/preferences fixtures; no Provider, account, network or event content. */
@RunWith(AndroidJUnit4::class)
class SystemCreateWorkStoreDeviceTest {
    private val scope = CalendarScope(AccountId("system-create-fixture"), CalendarId("fixture-calendar"), 0)

    private fun fixture(block: suspend (Context, String, SystemCreateWorkStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val base = ApplicationProvider.getApplicationContext<Context>()
        val fixtureId = UUID.randomUUID().toString()
        val name = "system-create-fixture-$fixtureId.db"
        val preferenceName = "system-create-preferences-fixture-$fixtureId"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(if (name == "system-create-modes") preferenceName else name, mode)
        }
        val store = SystemCreateWorkStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); base.deleteDatabase(name); base.deleteSharedPreferences(preferenceName) }
    }

    @Test fun freshSchemaStoresOnlyIdentityAndReservationsSurviveReopen() = fixture { context, name, store ->
        val first = store.reserve(scope, 7)
        assertEquals(SystemCreateWorkStatus.PENDING, first.status)
        assertEquals(0, first.timestamp.nano)
        assertEquals(first, store.reserve(scope, 7))
        assertEquals(1, store.count(scope))
        store.readableDatabase.rawQuery("PRAGMA table_info(system_creates)", null).use { cursor ->
            val columns = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            assertEquals(setOf("account", "calendar", "generation", "native_row", "local_id",
                "capture_id", "attempt", "timestamp", "status"), columns)
        }
        assertEquals(1, store.readableDatabase.version)
        store.close()
        SystemCreateWorkStore(context, name).use { reopened ->
            assertEquals(first, reopened.read(scope, 7))
            assertEquals(first, reopened.reserve(scope, 7))
            assertEquals(listOf(first), reopened.page(scope))
        }
        assertFalse(first.toString().contains(first.captureId.value))
        assertFalse(first.toString().contains(scope.account.value))
        assertEquals("SystemCreateWorkStore(REDACTED)", store.toString())
    }

    @Test fun uncertainAndBlockedWorkKeepsAllIdentitiesAcrossStatusAndRestart() = fixture { context, name, store ->
        val statuses = listOf(SystemCreateWorkStatus.CAPTURED, SystemCreateWorkStatus.WAITING,
            SystemCreateWorkStatus.UNSUPPORTED, SystemCreateWorkStatus.UNKNOWN)
        val jobs = statuses.mapIndexed { index, status ->
            val original = store.reserve(scope, index + 1L)
            assertTrue(store.status(original, status))
            // Caller status does not form part of the immutable identity comparison.
            assertTrue(store.status(original.copy(status = SystemCreateWorkStatus.COMPLETE), status))
            val expected = original.copy(status = status)
            assertEquals(expected, store.reserve(scope, index + 1L))
            expected
        }
        store.close()
        SystemCreateWorkStore(context, name).use { reopened ->
            assertEquals(jobs, reopened.page(scope))
            jobs.forEach { assertEquals(it, reopened.reserve(scope, it.row)) }
            assertEquals(4, reopened.count(scope))
        }
    }

    @Test fun statusRequiresEveryImmutableIdentityAndCannotCreateForeignWork() = fixture { _, _, store ->
        val original = store.reserve(scope, 5)
        val changed = listOf(
            original.copy(scope = scope.copy(account = AccountId("other-fixture-account"))),
            original.copy(scope = scope.copy(calendar = CalendarId("other-fixture-calendar"))),
            original.copy(scope = scope.copy(generation = 1)),
            original.copy(row = 6),
            original.copy(localId = original.localId.copy(value = UUID.randomUUID().toString())),
            original.copy(captureId = MutationId(UUID.randomUUID().toString())),
            original.copy(attempt = MutationId(UUID.randomUUID().toString())),
            original.copy(timestamp = original.timestamp.plusSeconds(1)),
        )
        changed.forEach { assertFalse(store.status(it, SystemCreateWorkStatus.COMPLETE)) }
        assertEquals(original, store.read(scope, 5))
        assertEquals(1, store.count(scope))
    }

    @Test fun pageIsBoundedOrderedAndIsolatedAndExcludesCompleteRows() = fixture { _, _, store ->
        listOf(9L, 3L, 7L, 1L).forEach { store.reserve(scope, it) }
        val complete = store.read(scope, 3)!!
        assertTrue(store.status(complete, SystemCreateWorkStatus.COMPLETE))
        store.reserve(scope.copy(generation = 1), 2)
        store.reserve(scope.copy(calendar = CalendarId("other-fixture-calendar")), 2)
        assertEquals(listOf(1L, 7L), store.page(scope, limit = 2).map { it.row })
        assertEquals(listOf(9L), store.page(scope, afterRow = 7).map { it.row })
        assertTrue(store.page(scope, afterRow = 9).isEmpty())
        assertEquals(3, store.count(scope))
        assertEquals(SystemCreateWorkStatus.COMPLETE, store.read(scope, 3)!!.status)
        for (limit in listOf(0, -1, 101)) rejectsArgument { store.page(scope, limit = limit) }
        rejectsArgument { store.page(scope, afterRow = -1) }
        rejectsArgument { store.reserve(scope, 0) }
        rejectsArgument { store.read(scope, -1) }
        rejectsArgument { complete.copy(timestamp = Instant.ofEpochSecond(1, 1)) }
    }

    @Test fun completedRowReuseReplacesIdentityAndOldWorkCannotChangeTheNewJob() = fixture { context, name, store ->
        val old = store.reserve(scope, 8)
        assertTrue(store.status(old, SystemCreateWorkStatus.COMPLETE))
        assertEquals(0, store.count(scope))
        assertTrue(store.page(scope).isEmpty())
        assertEquals(old.copy(status = SystemCreateWorkStatus.COMPLETE), store.read(scope, 8))
        val next = store.reserve(scope, 8)
        assertNotEquals(old.localId, next.localId)
        assertNotEquals(old.captureId, next.captureId)
        assertNotEquals(old.attempt, next.attempt)
        assertEquals(SystemCreateWorkStatus.PENDING, next.status)
        assertFalse(store.status(old, SystemCreateWorkStatus.UNKNOWN))
        store.close()
        SystemCreateWorkStore(context, name).use { reopened -> assertEquals(next, reopened.reserve(scope, 8)) }
    }

    @Test fun twoHelpersReserveOneIdentityIncludingCompletedRowReuse() = fixture { context, name, store ->
        SystemCreateWorkStore(context, name).use { other ->
            suspend fun reservations(): List<SystemCreateWork> = coroutineScope {
                (0 until 16).map { index -> async { (if (index % 2 == 0) store else other).reserve(scope, 12) } }.awaitAll()
            }
            val first = reservations()
            assertEquals(1, first.distinct().size)
            assertTrue(store.status(first.first(), SystemCreateWorkStatus.COMPLETE))
            val next = reservations()
            assertEquals(1, next.distinct().size)
            assertNotEquals(first.first().attempt, next.first().attempt)
            assertEquals(1, store.count(scope))
        }
    }

    @Test fun modesDefaultDisabledPersistAndUseFramedScopeHashes() = fixture { context, name, store ->
        val alternatives = listOf(scope.copy(account = AccountId("other-fixture-account")),
            scope.copy(calendar = CalendarId("other-fixture-calendar")), scope.copy(generation = 1))
        assertFalse(store.modeEnabled(scope))
        store.setMode(scope, true)
        assertTrue(store.modeEnabled(scope))
        alternatives.forEach { assertFalse(store.modeEnabled(it)) }
        val framedOne = CalendarScope(AccountId("fixture:a"), CalendarId("b"), 0)
        val framedTwo = CalendarScope(AccountId("fixture"), CalendarId("a:b"), 0)
        store.setMode(framedOne, true)
        assertFalse(store.modeEnabled(framedTwo))
        val keys = context.getSharedPreferences("system-create-modes", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.all { it.matches(Regex("v1-[0-9a-f]{64}")) })
        store.close()
        SystemCreateWorkStore(context, name).use { reopened ->
            assertTrue(reopened.modeEnabled(scope))
            reopened.setMode(scope, false)
            assertFalse(reopened.modeEnabled(scope))
            assertTrue(reopened.modeEnabled(framedOne))
        }
    }

    private suspend fun rejectsArgument(block: suspend () -> Any?) {
        try { block(); fail("Expected argument rejection") }
        catch (_: IllegalArgumentException) { }
    }
}

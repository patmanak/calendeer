// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

/** Isolated SQL/preferences and coordinator ports: no service/session/Provider mutation. */
class EventColorSchedulingDeviceTest {
    @Test fun preferenceIsCommittedBeforeSchedulingAndSurvivesAFailedTrigger() = fixture { context, _ ->
        assertEquals(EventColorMode.EVENT, EventColorPreferences.read(context))
        var triggered = 0
        val preferences = EventColorPreferences(context) {
            assertEquals(EventColorMode.CALENDAR, EventColorPreferences.read(context)); triggered++
        }
        assertTrue(preferences.setMode(EventColorMode.CALENDAR)); assertEquals(1, triggered)
        assertEquals(EventColorMode.CALENDAR, EventColorPreferences(context) {}.mode.value)
        assertFalse(EventColorPreferences(context) { error("Synthetic scheduler failure") }.setMode(EventColorMode.EVENT))
        assertEquals(EventColorMode.EVENT, EventColorPreferences.read(context))
        assertEquals(NetworkType.NOT_REQUIRED, EventColorWorker.request().workSpec.constraints.requiredNetworkType)
    }

    @Test fun pausedMirrorsArePagedOfflineWithoutChangingConsentOrCrossingAccounts() = fixture { context, store ->
        val account = AccountId("color-account")
        val other = AccountId("other-color-account")
        store.remember(account, listOf(CalendarSummary(CalendarId("fixture"), "Synthetic color calendar")))
        store.remember(other, listOf(CalendarSummary(CalendarId("fixture"), "Other synthetic calendar")))
        val before = store.selections(account)
        val calls = mutableListOf<Long>()
        val projection = object : CalendarProjection by AndroidCalendarProjection(context) {
            override suspend fun reprojectEventColors(scope: CalendarScope, afterRow: Long, limit: Int): ColorProjectionPage {
                assertEquals(account, scope.account); assertEquals(100, limit); assertTrue(admitOperation())
                calls += afterRow
                return if (afterRow == 0L) ColorProjectionPage(100, true) else ColorProjectionPage(101, false)
            }
        }
        val owner = owner(store, projection, { true }, { 1L }, { _, id -> id == account })
        assertTrue(owner.reprojectColors()); assertEquals(listOf(0L, 100L), calls)
        assertEquals(before, store.selections(account)); assertFalse(projection.admitOperation())
    }

    @Test fun revocationBetweenPagesStopsTheOldLeaseAndAReopenedPassRestartsAtZero() = fixture { context, store ->
        val account = AccountId("color-revocation-account")
        store.remember(account, listOf(CalendarSummary(CalendarId("fixture"), "Synthetic revocation calendar")))
        var generation = 1L
        var revoked = false
        val calls = mutableListOf<Long>()
        val projection = object : CalendarProjection by AndroidCalendarProjection(context) {
            override suspend fun reprojectEventColors(scope: CalendarScope, afterRow: Long, limit: Int): ColorProjectionPage {
                calls += afterRow
                if (!revoked) { revoked = true; generation++; return ColorProjectionPage(100, true) }
                return ColorProjectionPage(101, false)
            }
        }
        val owner = owner(store, projection, { true }, { generation }, { _, _ -> true })
        assertFalse(owner.reprojectColors()); assertEquals(listOf(0L), calls); assertFalse(projection.admitOperation())
        assertTrue(owner.reprojectColors()); assertEquals(listOf(0L, 0L), calls)
    }

    @Test fun permissionAndAuthenticationLossNeverGrantNativeAdmission() = fixture { context, store ->
        val projection = AndroidCalendarProjection(context)
        assertFalse(owner(store, projection, { false }, { 1L }, { _, _ -> true }).reprojectColors())
        val noPermission = ImportCoordinatorNativeCalendars(store, projection, Mutex(), { 1L }, { 1L },
            { true }, { false }, { false }, { _, _ -> true }, {}, {})
        assertFalse(noPermission.reprojectColors())
    }

    private fun owner(store: ImportStore, projection: CalendarProjection, authenticated: () -> Boolean,
        generation: () -> Long, current: suspend (Long, AccountId) -> Boolean) =
        ImportCoordinatorNativeCalendars(store, projection, Mutex(), generation, { 1L }, authenticated,
            { true }, { false }, current, {}, {})

    private fun fixture(block: suspend (Context, ImportStore) -> Unit) = runBlocking {
        val real = ApplicationProvider.getApplicationContext<Context>()
        val token = UUID.randomUUID().toString()
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(real) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "color-scheduling-$token-$name"; names += scoped
                return real.getSharedPreferences(scoped, mode)
            }
        }
        val database = "color-scheduling-$token.db"
        val store = ImportStore(context, database)
        try { block(context, store) }
        finally { store.close(); real.deleteDatabase(database); names.forEach { real.deleteSharedPreferences(it) } }
    }
}

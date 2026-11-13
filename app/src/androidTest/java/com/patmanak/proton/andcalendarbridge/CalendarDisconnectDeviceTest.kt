// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract.Calendars
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Disposable native accounts and SQL only; no real session or service calls. */
class CalendarDisconnectDeviceTest {
    @Test fun confirmedDisconnectRemovesOnlyKnownCalendarsAndRetainsUnknownHistory() = fixture {
        coordinator.disconnectCalendars()
        assertFalse(coordinator.state.value.authenticated)
        assertEquals(0, rows(first)); assertEquals(0, rows(second)); assertEquals(1, rows(unrelated))
        ImportStore(context, database).use { assertEquals(intent, it.localEdit(first, base.eventId)) }
        assertEquals(unknown, journal.personalJournal.read(first, prepared.id) { true })
        coordinator.disconnectCalendars() // Repeated cleanup after an interrupted sign-out is harmless.
        assertEquals(1, rows(unrelated))
        // Existing import logic notices a recreated calendar and requeues its projection.
        assertTrue(AndroidCalendarProjection(context).ensureCalendarState(first, CalendarSummary(first.calendar, "Synthetic reconnect")).created)
        assertEquals(unknown, journal.personalJournal.read(first, prepared.id) { true })
    }

    @Test fun missingPermissionFailsVisiblyAndRetryRemovesTheSameMirrors() = fixture {
        permission = false
        try { coordinator.disconnectCalendars(); fail("Removal must not report success without permission") }
        catch (_: IllegalStateException) { }
        assertFalse(coordinator.state.value.authenticated)
        assertEquals(1, rows(first)); assertEquals(1, rows(second))
        permission = true
        coordinator.disconnectCalendars()
        assertEquals(0, rows(first)); assertEquals(0, rows(second)); assertEquals(1, rows(unrelated))
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val database = "disconnect-$token.db"
        private val journalDatabase = "disconnect-journal-$token.db"
        val first = CalendarScope(AccountId("disconnect-$token"), CalendarId("one"), 0)
        val second = first.copy(calendar = CalendarId("two"))
        val unrelated = first.copy(account = AccountId("disconnect-unrelated-$token"))
        val native = AndroidCalendarProjection(context)
        val rowIds = mutableMapOf<CalendarScope, Long>()
        val store = ImportStore(context, database)
        val journal = TitleUpdateAttemptStore(context, journalDatabase)
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val format = AdapterFormat("synthetic-disconnect", 2)
        val base = RemoteChange.Upsert(EventId("fixture"), Revision("before"), EventContent("Synthetic disconnect",
            EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneOffset.UTC),
            details = EventDetails()))
        val intent = LocalEditIntent(MutationId("local"), first, format, base, base.content.copy(title = "Unsent fixture"))
        val prepared = PersonalUpdateAttemptRecord(MutationId("attempt"), PersonalEditPreparation(intent,
            Instant.parse("2030-05-01T00:00:00Z"), format, "synthetic-capsule"), format, "synthetic-wire")
        val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        var permission = true
        private val port = object : CalendarProjection by native { override fun hasPermissions() = permission }
        val coordinator = ImportCoordinator(context, store, object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = first.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No network")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No network")
        }, port, jobs, object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() = Unit
            override fun continueWork(network: Boolean) = Unit
        })
        suspend fun seed() {
            store.remember(first.account, listOf(CalendarSummary(first.calendar, "Synthetic disconnect one"),
                CalendarSummary(second.calendar, "Synthetic disconnect two")))
            store.setEnabled(first, true); store.setEnabled(second, true)
            store.transaction(first) { putRemote(base); putProjection(ProjectionIntent(first, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(first, base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            assertTrue(journal.personalJournal.remember(prepared) { true })
            assertTrue(journal.personalJournal.claim(prepared) { true })
            for (target in listOf(first, second, unrelated)) rowIds[target] = native.ensureCalendarState(target, CalendarSummary(target.calendar, "Synthetic disconnect")).id
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (!coordinator.state.value.selectionsLoaded) delay(10) }
        }
        fun rows(target: CalendarScope): Int {
            val account = native.accountFor(target.account)
            return context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
                "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._ID}=?",
                arrayOf(account.name, account.type, rowIds.getValue(target).toString()), null)!!.use { it.count }
        }
        suspend fun close() {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            val clean = AndroidCalendarProjection(context)
            for (target in listOf(first, second, unrelated)) clean.removeOwnedCalendar(target)
            for (account in listOf(first.account, unrelated.account)) AccountManager.get(context).removeAccountExplicitly(clean.accountFor(account))
            store.close(); journal.close(); context.deleteDatabase(database); context.deleteDatabase(journalDatabase)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

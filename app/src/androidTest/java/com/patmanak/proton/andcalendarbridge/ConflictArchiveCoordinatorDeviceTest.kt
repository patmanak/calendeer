// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQL; controlled session/permission ports. No Provider contents or service requests. */
@RunWith(AndroidJUnit4::class)
class ConflictArchiveCoordinatorDeviceTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "archive-inspection-$token.db"
        val target = CalendarScope(AccountId("inspection-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-import", 1)
        val id = MutationId("synthetic-owner")
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        val intent = LocalEditIntent(id, target, format, base, base.content.copy(title = "Captured fixture"))
        val latest = base.copy(revision = Revision("changed"), content = base.content.copy(title = "Remote fixture"))
        val store = ImportStore(context, name)
        lateinit var ref: ConflictArchiveRef
        var permission = true
        var account: AccountId? = target.account
        var onAccount: suspend () -> Unit = {}
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("fixture", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider access")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider access")
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { onAccount(); return account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service reads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        val schedules = AtomicInteger()
        private val networkSchedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() { schedules.incrementAndGet() }
            override fun requestNetwork() { schedules.incrementAndGet(); networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = error("No inspection scheduling")
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Inspection fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertTrue(store.captureLocalEdit(intent) == intent)
            store.transaction(target) { putRemote(latest) }
            ref = store.conflictArchives(ConflictArchiveQuery(target)).entries.single()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
        }
        fun atFinalAccountCheck(action: suspend () -> Unit) {
            var checks = 0
            onAccount = { if (++checks == 2) action() }
        }
        // Content is never emitted. Comparing every SQL table catches accidental journal/projection changes.
        fun sqlSnapshot(): List<List<String?>> {
            val db = store.writableDatabase
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            return tables.flatMap { table -> db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(listOf(table) + (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            } }
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun Fixture.unchangedRead(block: suspend Fixture.() -> Unit) {
        val sql = sqlSnapshot(); val requests = schedules.get()
        block()
        assertTrue("Inspection changed SQL history", sql == sqlSnapshot())
        assertEquals(requests, schedules.get())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun allThreeReadersPreserveEverySqlTableAndScheduleNothing() = fixture { unchangedRead {
        val reader = coordinator.conflictArchiveReader
        val page = (reader.list(ConflictArchiveQuery(target)) as ConflictArchiveRead.Available).value
        assertEquals(ref, page.entries.single())
        val detail = (reader.detail(ref) as ConflictArchiveRead.Available).value
        assertEquals(base, detail.base); assertEquals(intent.content, detail.local)
        val observations = (reader.observations(ConflictObservationQuery(ref)) as ConflictArchiveRead.Available).value
        assertTrue(observations.entries.any { it.change == latest })
        assertEquals("ConflictArchiveRead.Available(REDACTED)", reader.detail(ref).toString())
    } }
    @Test fun pausedAndUnavailableCalendarsRemainReadable() = fixture {
        store.setEnabled(target, false); store.remember(target.account, emptyList())
        unchangedRead { assertTrue(coordinator.conflictArchiveReader.detail(ref) is ConflictArchiveRead.Available) }
    }
    @Test fun permissionOrAccountLossPreventsAllReaders() = fixture { unchangedRead {
        permission = false
        val reader = coordinator.conflictArchiveReader
        assertEquals(ConflictArchiveRead.Inactive, reader.list(ConflictArchiveQuery(target)))
        assertEquals(ConflictArchiveRead.Inactive, reader.detail(ref))
        assertEquals(ConflictArchiveRead.Inactive, reader.observations(ConflictObservationQuery(ref)))
        permission = true; account = AccountId("foreign")
        assertEquals(ConflictArchiveRead.Inactive, reader.detail(ref))
    } }
    @Test fun exactScopeAndDossierIdentityAreRequired() = fixture { unchangedRead {
        val reader = coordinator.conflictArchiveReader
        assertEquals(ConflictArchiveRead.Unqualified, reader.detail(ref.copy(id = ref.id + 1)))
        assertEquals(ConflictArchiveRead.Unqualified, reader.detail(ref.copy(mutationId = MutationId("other"))))
        assertEquals(ConflictArchiveRead.Unqualified, reader.detail(ref.copy(scope = target.copy(generation = 1))))
        assertEquals(ConflictArchiveRead.Unqualified, reader.list(ConflictArchiveQuery(target.copy(calendar = CalendarId("other")))))
    } }
    @Test fun lossAfterSqlReadingNeverReturnsContents() = fixture {
        atFinalAccountCheck { permission = false }
        unchangedRead { assertEquals(ConflictArchiveRead.Inactive, coordinator.conflictArchiveReader.detail(ref)) }
    }
    @Test fun missingDossierStillPerformsFinalSessionCheck() = fixture {
        atFinalAccountCheck { account = AccountId("foreign") }
        unchangedRead { assertEquals(ConflictArchiveRead.Inactive, coordinator.conflictArchiveReader.detail(ref.copy(id = ref.id + 1))) }
    }
    @Test fun reconnectInvalidatesPresentationEpochAndSnapshot() = fixture {
        val epoch = coordinator.conflictArchiveEpoch.value
        atFinalAccountCheck {
            onAccount = { awaitCancellation() }
            coordinator.authenticationChanged(false); coordinator.authenticationChanged(true)
        }
        val sql = sqlSnapshot()
        assertEquals(ConflictArchiveRead.Inactive, coordinator.conflictArchiveReader.detail(ref))
        assertTrue(coordinator.conflictArchiveEpoch.value > epoch)
        assertEquals(sql, sqlSnapshot())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun cancellationAfterMissingDossierPropagatesAndClosesLeases() = fixture {
        atFinalAccountCheck { throw CancellationException("Synthetic cancellation") }
        var cancelled = false
        try { coordinator.conflictArchiveReader.detail(ref.copy(id = ref.id + 1)) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun sessionPortsCannotSwallowCancellationWithNullOrAnOrdinaryFailure() = fixture {
        for (atFinal in listOf(false, true)) for (throwFailure in listOf(false, true)) {
            var checks = 0
            onAccount = {
                if (++checks == if (atFinal) 2 else 1) {
                    currentCoroutineContext().cancel()
                    if (throwFailure) throw java.io.IOException("Synthetic failure")
                    account = null
                }
            }
            val normalReturns = AtomicInteger()
            val observation = CoroutineScope(currentCoroutineContext()).async {
                coordinator.conflictArchiveReader.detail(ref.copy(id = ref.id + 1))
                normalReturns.incrementAndGet()
            }
            var cancelled = false
            try { observation.await() } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(0, normalReturns.get())
            assertEquals(if (atFinal) 2 else 1, checks)
            assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
            account = target.account
        }
    }
}

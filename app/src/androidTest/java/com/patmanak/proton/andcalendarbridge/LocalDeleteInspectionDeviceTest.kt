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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQL; controlled session/permission ports. No Provider contents or service requests. */
@RunWith(AndroidJUnit4::class)
class LocalDeleteInspectionDeviceTest {
    private class Fixture(editedBeforeDeletion: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "delete-inspection-$token.db"
        val target = CalendarScope(AccountId("delete-inspection-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-import", 1)
        val id = MutationId("synthetic-owner")
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        val intent = LocalDeleteIntent(id, target, format, base, if (editedBeforeDeletion) base.content.copy(title = "Captured fixture") else base.content)
        val latest = base.copy(revision = Revision("changed"), content = base.content.copy(title = "Remote fixture"))
        val store = ImportStore(context, name)
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
            assertTrue(store.captureLocalDelete(intent) == intent)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
        }
        suspend fun inspect(event: EventId = base.eventId, mutation: MutationId = id, scope: CalendarScope = target) =
            coordinator.inspectLocalDelete(scope, event, mutation)
        suspend fun review(): LocalDeleteReview = (inspect() as LocalDeleteInspection.Available).review
        suspend fun cache(change: RemoteChange = latest) {
            store.admitTransaction = { true }; store.transaction(target) { putRemote(change); putProjection(ProjectionIntent(target, change)) }
        }
        suspend fun checkpoint(value: AdapterFormat?) {
            store.admitTransaction = { true }; store.transaction(target) { checkpoint = value?.let { PageCheckpoint(target, it) } }
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
    private fun fixture(editedBeforeDeletion: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(editedBeforeDeletion)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun Fixture.unchangedRead(block: suspend Fixture.() -> Unit) {
        val sql = sqlSnapshot(); val requests = schedules.get()
        block()
        assertTrue("Inspection changed SQL history", sql == sqlSnapshot())
        assertEquals(requests, schedules.get())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }

    @Test fun exactDeleteOwnerRetainsBothCapturedVersionsWithoutChangingAnySqlTable() = fixture { unchangedRead {
        val value = review()
        assertTrue(value.owner == intent); assertTrue(value.imported == base); assertTrue(value.projectedBase == base)
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation)
        assertTrue(value.capturedBaseStillProjected); assertFalse(value.recordedConflict)
        assertEquals("LocalDeleteInspection.Available(REDACTED)", inspect().toString())
    } }
    @Test fun deletionWithUnchangedCapturedContentIsStillAnIntention() = fixture(false) { unchangedRead {
        val value = review()
        assertTrue(value.owner.content == base.content)
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation); assertFalse(value.recordedConflict)
    } }
    @Test fun newerImportAndReturningBaseNeverResetTheRecordedConflict() = fixture {
        cache()
        unchangedRead {
            val value = review()
            assertEquals(LocalEditImportRelation.CHANGED, value.importRelation); assertTrue(value.recordedConflict)
            assertTrue(value.owner.base == base); assertTrue(value.owner.content == intent.content)
        }
        cache(base)
        unchangedRead { assertEquals(LocalEditImportRelation.UNCHANGED, review().importRelation); assertTrue(review().recordedConflict) }
    }
    @Test fun remoteTombstoneAndCacheAbsenceNeverAcknowledgeTheLocalDeletion() = fixture {
        cache(RemoteChange.Deleted(base.eventId, Revision("gone")))
        unchangedRead {
            val value = review()
            assertEquals(LocalEditImportRelation.DELETED, value.importRelation); assertTrue(value.recordedConflict)
            assertTrue(value.owner == intent.copy(state = LocalEditState.CONFLICT))
            assertTrue(value.projectedBase == base)
        }
        store.writableDatabase.execSQL("DELETE FROM records")
        unchangedRead { assertEquals(LocalEditImportRelation.UNOBSERVED, review().importRelation); assertTrue(review().recordedConflict) }
    }
    @Test fun deferredEvidencePreventsComparingTheRetainedOldImport() = fixture {
        store.admitTransaction = { true }
        store.transaction(target) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId, base.revision,
            format, DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-evidence")) }
        unchangedRead { assertEquals(LocalEditImportRelation.DEFERRED, review().importRelation); assertTrue(review().imported == base) }
    }
    @Test fun unknownAndIncompatibleFormatsTakePrecedenceOverTombstones() = fixture {
        cache(RemoteChange.Deleted(base.eventId, Revision("gone")))
        checkpoint(null); unchangedRead { assertEquals(LocalEditImportRelation.FORMAT_UNKNOWN, review().importRelation) }
        checkpoint(format.copy(version = 2)); unchangedRead { assertEquals(LocalEditImportRelation.FORMAT_MISMATCH, review().importRelation) }
    }
    @Test fun pausedAndUnavailableKnownCalendarsRetainHistoricalInspection() = fixture {
        store.setEnabled(target, false); store.remember(target.account, emptyList())
        unchangedRead { assertTrue(review().owner == intent) }
    }
    @Test fun wrongMutationEventCalendarAndGenerationDoNotReturnPrivateHistory() = fixture { unchangedRead {
        assertEquals(LocalDeleteInspection.Unqualified, inspect(mutation = MutationId("other")))
        assertEquals(LocalDeleteInspection.Unqualified, inspect(event = EventId("other")))
        assertEquals(LocalDeleteInspection.Unqualified, inspect(scope = target.copy(calendar = CalendarId("other"))))
        assertEquals(LocalDeleteInspection.Unqualified, inspect(scope = target.copy(generation = 1)))
    } }
    @Test fun permissionAndAccountLossBeforeReadingPreventInspection() = fixture { unchangedRead {
        permission = false; assertEquals(LocalDeleteInspection.Inactive, inspect())
        permission = true; account = AccountId("foreign"); assertEquals(LocalDeleteInspection.Inactive, inspect())
        account = null; assertEquals(LocalDeleteInspection.Inactive, inspect())
    } }
    @Test fun permissionAndAccountLossAfterSqlReadingPreventReturningTheSnapshot() = fixture {
        atFinalAccountCheck { permission = false }; unchangedRead { assertEquals(LocalDeleteInspection.Inactive, inspect()) }
        permission = true; atFinalAccountCheck { account = AccountId("foreign") }
        unchangedRead { assertEquals(LocalDeleteInspection.Inactive, inspect()) }
    }
    @Test fun reconnectAndPolicyChangeInvalidateFrozenHistoryAndCloseItsLeases() = fixture {
        atFinalAccountCheck {
            // Keep the next startup owner's lease separate from the inspected owner's closure.
            onAccount = { awaitCancellation() }
            coordinator.authenticationChanged(false); coordinator.authenticationChanged(true)
        }
        val sql = sqlSnapshot()
        assertEquals(LocalDeleteInspection.Inactive, inspect()); assertTrue(sql == sqlSnapshot())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun calendarPolicyChangeAfterReadingPreventsReturningTheSnapshot() = fixture {
        val selected = store.selections(target.account).single()
        atFinalAccountCheck { onAccount = { awaitCancellation() }; coordinator.setEnabled(selected, false) }
        unchangedRead { assertEquals(LocalDeleteInspection.Inactive, inspect()) }
    }
    @Test fun cancellationAtInitialAndFinalSessionChecksPropagates() = fixture {
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalAccountCheck { throw CancellationException("Controlled cancellation") }
            else onAccount = { throw CancellationException("Controlled cancellation") }
            unchangedRead { try { inspect(); fail("Cancellation swallowed") } catch (_: CancellationException) { } }
        }
    }
    @Test fun sessionReadFailuresAtBothChecksAreSanitizedAndCloseLeases() = fixture {
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalAccountCheck { error("Synthetic failure") } else onAccount = { error("Synthetic failure") }
            unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
        }
    }
    @Test fun malformedOwnerIdentitiesRevisionsAndNonIntegerVersionsAreUnavailable() = fixture {
        store.writableDatabase.execSQL("UPDATE local_edits SET local=replace(local, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
        store.writableDatabase.execSQL("UPDATE local_edits SET local=replace(local, 'foreign', 'synthetic-event')")
        store.writableDatabase.execSQL("UPDATE local_edits SET local=replace(local, 'base', 'different-revision')")
        unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
        store.writableDatabase.execSQL("UPDATE local_edits SET local=replace(local, 'different-revision', 'base')")
        for (invalid in listOf("1.5", "2147483648", "0")) {
            store.writableDatabase.execSQL("UPDATE local_edits SET version=$invalid")
            unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
        }
    }
    @Test fun foreignImportedAndProjectedIdentitiesAreUnavailableWithoutMutation() = fixture {
        store.writableDatabase.execSQL("UPDATE records SET payload=replace(payload, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
        store.writableDatabase.execSQL("UPDATE records SET payload=replace(payload, 'foreign', 'synthetic-event')")
        store.writableDatabase.execSQL("UPDATE projection_bases SET payload=replace(payload, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalDeleteInspection.Unavailable, inspect()) }
    }
    @Test fun incompatiblePreparationIsRefusedWithoutDecodingItAndUpdateKindIsUnqualified() = fixture {
        store.writableDatabase.execSQL("INSERT INTO local_edit_preparations (scope,mutation,event,adapter,version,base,local,state,timestamp,prep_adapter,prep_version,payload) " +
            "SELECT scope,mutation,event,adapter,version,base,local,state,'invalid','synthetic-preparation',1,'unreadable-protected' FROM local_edits")
        unchangedRead {
            assertEquals(LocalDeleteInspection.Unavailable, inspect())
            assertEquals(LocalDeleteInspection.Unqualified, inspect(mutation = MutationId("foreign")))
        }
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='UPDATE'")
        unchangedRead { assertEquals(LocalDeleteInspection.Unqualified, inspect()) }
    }
    @Test fun reopenedSqlHistoryKeepsTheOriginalDeletionAndConflict() = fixture {
        cache()
        val sql = sqlSnapshot()
        ImportStore(context, name).use { reopened ->
            val value = reopened.inspectLocalDelete(target, base.eventId, id)!!
            assertTrue(value.owner == intent.copy(state = LocalEditState.CONFLICT))
            assertEquals(LocalEditImportRelation.CHANGED, value.importRelation)
        }
        assertTrue(sql == sqlSnapshot())
    }
}

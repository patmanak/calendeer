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
class LocalEditInspectionDeviceTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "edit-inspection-$token.db"
        val target = CalendarScope(AccountId("inspection-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-import", 1)
        val id = MutationId("synthetic-owner")
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        val intent = LocalEditIntent(id, target, format, base, base.content.copy(title = "Captured fixture"))
        val latest = base.copy(revision = Revision("changed"), content = base.content.copy(title = "Remote fixture"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2030-10-01T10:00:00Z"),
            AdapterFormat("controlled-title", 1), "synthetic-protected")
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
            assertTrue(store.captureLocalEdit(intent) == intent)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
        }
        suspend fun inspect(event: EventId = base.eventId, mutation: MutationId = id, scope: CalendarScope = target) =
            coordinator.inspectLocalEdit(scope, event, mutation)
        suspend fun review(): LocalEditReview = (inspect() as LocalEditInspection.Available).review
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

    @Test fun exactOwnerAndBaseAreInspectedWithoutChangingAnySqlTable() = fixture { unchangedRead {
        val value = review()
        assertTrue(value.owner == intent); assertTrue(value.imported == base); assertTrue(value.projectedBase == base)
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation)
        assertTrue(value.titleOnly); assertTrue(value.capturedBaseStillProjected); assertFalse(value.recordedConflict)
        assertNull(value.retainedTitle)
    } }
    @Test fun newerImportAndReturningBaseKeepTheRecordedConflictAndPreparation() = fixture {
        store.admitTransaction = { true }; assertTrue(store.retainLocalEditPreparation(preparation)); cache()
        unchangedRead {
            val changed = review()
            assertEquals(LocalEditImportRelation.CHANGED, changed.importRelation); assertTrue(changed.recordedConflict)
            assertTrue(changed.owner.base == base); assertTrue(changed.owner.content == intent.content)
            assertEquals(RetainedLocalTitleInfo(preparation.format, preparation.timestamp), changed.retainedTitle)
            assertEquals("LocalEditInspection.Available(REDACTED)", inspect().toString())
        }
        cache(base)
        unchangedRead { assertEquals(LocalEditImportRelation.UNCHANGED, review().importRelation); assertTrue(review().recordedConflict) }
        ImportStore(context, name).use { reopened -> assertTrue(reopened.localEditPreparation(target, base.eventId) == preparation) }
    }
    @Test fun explicitTombstoneDiffersFromAMissingImportedRecord() = fixture {
        cache(RemoteChange.Deleted(base.eventId, Revision("delete")))
        unchangedRead { assertEquals(LocalEditImportRelation.DELETED, review().importRelation); assertTrue(review().recordedConflict) }
        store.writableDatabase.execSQL("DELETE FROM records")
        unchangedRead { assertEquals(LocalEditImportRelation.UNOBSERVED, review().importRelation); assertTrue(review().recordedConflict) }
    }
    @Test fun deferredEvidenceOverridesTheRetainedOldRecord() = fixture {
        store.admitTransaction = { true }
        store.transaction(target) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId, base.revision,
            format, DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-evidence")) }
        unchangedRead { assertEquals(LocalEditImportRelation.DEFERRED, review().importRelation); assertTrue(review().imported == base) }
    }
    @Test fun unknownAndIncompatibleObservationFormatsNeverQualifyAComparison() = fixture {
        checkpoint(null); unchangedRead { assertEquals(LocalEditImportRelation.FORMAT_UNKNOWN, review().importRelation) }
        checkpoint(format.copy(version = 2)); unchangedRead { assertEquals(LocalEditImportRelation.FORMAT_MISMATCH, review().importRelation) }
    }
    @Test fun pausedAndUnavailableKnownCalendarsRetainHistoricalInspection() = fixture {
        store.setEnabled(target, false)
        store.remember(target.account, emptyList())
        unchangedRead { assertEquals(LocalEditImportRelation.UNCHANGED, review().importRelation) }
    }
    @Test fun wrongMutationEventCalendarAndGenerationDoNotReturnPrivateHistory() = fixture { unchangedRead {
        assertEquals(LocalEditInspection.Unqualified, inspect(mutation = MutationId("other")))
        assertEquals(LocalEditInspection.Unqualified, inspect(event = EventId("other")))
        assertEquals(LocalEditInspection.Unqualified, inspect(scope = target.copy(calendar = CalendarId("other"))))
        assertEquals(LocalEditInspection.Unqualified, inspect(scope = target.copy(generation = 1)))
    } }
    @Test fun permissionAndAccountLossBeforeReadingPreventInspection() = fixture { unchangedRead {
        permission = false; assertEquals(LocalEditInspection.Inactive, inspect())
        permission = true; account = AccountId("foreign"); assertEquals(LocalEditInspection.Inactive, inspect())
        account = null; assertEquals(LocalEditInspection.Inactive, inspect())
    } }
    @Test fun permissionOrAccountLossAfterSqlReadingPreventsReturningTheSnapshot() = fixture {
        atFinalAccountCheck { permission = false }
        unchangedRead { assertEquals(LocalEditInspection.Inactive, inspect()) }
        permission = true; atFinalAccountCheck { account = AccountId("foreign") }
        unchangedRead { assertEquals(LocalEditInspection.Inactive, inspect()) }
    }
    @Test fun sessionReconnectAfterSqlReadingInvalidatesTheFrozenGeneration() = fixture {
        atFinalAccountCheck {
            // Hold the new startup owner at its session read so its lease cannot replace the inspected owner's lease.
            onAccount = { awaitCancellation() }
            coordinator.authenticationChanged(false); coordinator.authenticationChanged(true)
        }
        val sql = sqlSnapshot()
        assertEquals(LocalEditInspection.Inactive, inspect()); assertTrue(sql == sqlSnapshot())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun policyChangeAfterSqlReadingInvalidatesTheFrozenObservation() = fixture {
        val selection = store.selections(target.account).single()
        atFinalAccountCheck {
            onAccount = { awaitCancellation() }
            coordinator.setEnabled(selection, false)
        }
        assertEquals(LocalEditInspection.Inactive, inspect())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
    }
    @Test fun cancellationAtInitialAndFinalSessionChecksPropagatesAndClosesLeases() = fixture {
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalAccountCheck { throw CancellationException("Controlled cancellation") }
            else onAccount = { throw CancellationException("Controlled cancellation") }
            unchangedRead {
                try { inspect(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
            }
        }
    }
    @Test fun malformedOwnerAndPreparationFailWithoutReturningPayloadOrChangingRows() = fixture {
        store.admitTransaction = { true }
        store.retainLocalEditPreparation(preparation)
        store.writableDatabase.execSQL("UPDATE local_edit_preparations SET timestamp='invalid'")
        unchangedRead { assertEquals(LocalEditInspection.Unavailable, inspect()) }
        store.writableDatabase.execSQL("UPDATE local_edit_preparations SET timestamp=?", arrayOf(preparation.timestamp.toString()))
        store.writableDatabase.execSQL("UPDATE local_edits SET base=replace(base, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalEditInspection.Unavailable, inspect()) }
    }
    @Test fun deleteIntentIsUnqualifiedInsteadOfTreatedAsACorruptUpdate() = fixture {
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='DELETE'")
        unchangedRead { assertEquals(LocalEditInspection.Unqualified, inspect()) }
    }
    @Test fun sessionReadFailuresAtInitialAndFinalChecksAreUnavailableAndCloseLeases() = fixture {
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalAccountCheck { error("Synthetic session failure") }
            else onAccount = { error("Synthetic session failure") }
            unchangedRead { assertEquals(LocalEditInspection.Unavailable, inspect()) }
        }
    }
    @Test fun mismatchedImportedOrProjectedIdentityIsUnavailableWithoutMutation() = fixture {
        store.writableDatabase.execSQL("UPDATE records SET payload=replace(payload, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalEditInspection.Unavailable, inspect()) }
        store.writableDatabase.execSQL("UPDATE records SET payload=replace(payload, 'foreign', 'synthetic-event')")
        store.writableDatabase.execSQL("UPDATE projection_bases SET payload=replace(payload, 'synthetic-event', 'foreign')")
        unchangedRead { assertEquals(LocalEditInspection.Unavailable, inspect()) }
    }
    @Test fun reopenedSqlHistoryRetainsTheExactInspectionWithoutATransition() = fixture {
        cache()
        val sql = sqlSnapshot()
        ImportStore(context, name).use { reopened ->
            val value = reopened.inspectLocalEdit(target, base.eventId, id)!!
            assertTrue(value.owner == intent.copy(state = LocalEditState.CONFLICT))
            assertEquals(LocalEditImportRelation.CHANGED, value.importRelation)
        }
        assertTrue(sql == sqlSnapshot())
    }
}

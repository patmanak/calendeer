package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic storage fixtures; no session, network or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class EventDetailsStoreDeviceTest {
    private val scope = CalendarScope(AccountId("details-fixture"), CalendarId("calendar"), 0)
    private val other = scope.copy(account = AccountId("other-details-fixture"))
    private val format = AdapterFormat("details-fixture", 1)
    private val mapping = AdapterFormat("details-mapping", 1)
    private val initial = PageCheckpoint(scope, format)
    private val interval = CoverageInterval(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), ZoneId.of("Europe/Paris"))
    private val metadata = PageCoverage(listOf(interval), 0, true, true)
    private val receipt = ReceivedCalendarPage(PageRequest(initial, 100), "sealed-fixture", metadata)
    private val ledger get() = CoverageTracker.applied(
        CoverageTracker.received(CoverageLedger(), receipt.request, metadata, 100), metadata, true, 200)
    private val details = EventDetails(listOf(EventReminder(15), EventReminder(-5)),
        EventPerson("organizer@example.invalid", "Organizer fixture"), listOf(
            EventAttendee(EventPerson("guest@example.invalid", "Guest fixture"), AttendanceRole.OPTIONAL, AttendanceStatus.TENTATIVE),
            EventAttendee(EventPerson("room@example.invalid"), AttendanceRole.RESOURCE, AttendanceStatus.ACCEPTED)))
    private fun event(value: EventDetails? = details, revision: String = "v1") = RemoteChange.Upsert(
        EventId("fixture"), Revision(revision), EventContent("Details fixture",
            EventTime.AllDay(interval.start, interval.start.plusDays(1)), details = value))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "event-details-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun <T> ImportStore.delta(target: CalendarScope = scope, action: DeltaSyncTransaction.() -> T): T =
        transaction(target) { action(this as DeltaSyncTransaction) }
    private suspend fun ImportStore.queue(value: RemoteChange) = transaction(scope) {
        putRemote(value); putProjection(ProjectionIntent(scope, value))
    }
    private fun profileCount(store: ImportStore) = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM mapping_profiles", null).use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun detailsSurviveRestartAndStaleAcknowledgementCannotConsumeChangedOrClearedDetails() = fixture { store, name ->
        store.queue(event())
        val old = store.pending(scope).single()
        // A mapper revision may retain the server revision; content must still participate in CAS.
        val updated = event(details.copy(reminders = listOf(EventReminder(30))))
        store.queue(updated); store.acknowledge(old)
        assertEquals(updated, store.pending(scope).single().change)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            val intent = reopened.pending(scope).single()
            assertEquals(updated, intent.change)
            reopened.queue(event(EventDetails())); reopened.acknowledge(intent)
            val cleared = reopened.pending(scope).single()
            assertEquals(EventDetails(), (cleared.change as RemoteChange.Upsert).content.details)
            reopened.acknowledge(cleared)
            assertTrue(reopened.pending(scope).isEmpty())
            assertTrue(reopened.pending(other).isEmpty())
        }
    }

    @Test fun absentProfileInvalidatesOnlyCoverageConfidenceAndRetainsEveryRecoveryObligation() = fixture { store, name ->
        val tombstone = RemoteChange.Deleted(EventId("deleted-fixture"), Revision("gone"))
        val deferred = DeferredCalendarEvent(EventId("unsupported-fixture"), Revision("v1"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-deferred")
        val cursor = OpaqueCursor(AdapterFormat("delta-fixture", 1), "before")
        val deltaReceipt = ReceivedChangePage(scope, cursor, "sealed-delta")
        val job = CalendarChangeJob(CalendarChangeNotice("event-job", CalendarNoticeKind.EVENT_CHANGED, EventId("changed-fixture")))
        store.delta {
            coverage = ledger; checkpoint = initial.copy(traversalComplete = true); received = receipt
            defer(deferred); deltaCursor = cursor; deltaReceived = deltaReceipt; deltaMore = true; putDeltaJob(job)
            putRemote(tombstone); putProjection(ProjectionIntent(scope, tombstone))
        }
        store.delta(other) { coverage = ledger }
        store.ensureMappingProfile(scope, mapping)
        val invalidated = ledger.copy(observed = null, active = ledger.active!!.copy(
            windows = ledger.active!!.windows.map { it.copy(prefixKnown = false) }))
        assertEquals(invalidated, store.delta { coverage })
        assertEquals(ledger, store.delta(other) { coverage })
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(1, profileCount(reopened))
            assertEquals(invalidated, reopened.delta { coverage })
            assertEquals(receipt, reopened.delta { received })
            assertEquals(initial.copy(traversalComplete = true), reopened.delta { checkpoint })
            assertEquals(deferred, reopened.delta { deferred(deferred.eventId) })
            assertEquals(cursor, reopened.delta { deltaCursor }); assertEquals(deltaReceipt, reopened.delta { deltaReceived })
            assertTrue(reopened.delta { deltaMore }); assertEquals(listOf(job), reopened.delta { deltaJobs() })
            assertEquals(tombstone, reopened.pending(scope).single().change)
            reopened.ensureMappingProfile(scope, mapping)
            assertEquals(invalidated, reopened.delta { coverage })
        }
    }

    @Test fun identicalProfilePreservesExactLedgerAndChangedProfileInvalidatesItAgain() = fixture { store, _ ->
        store.ensureMappingProfile(scope, mapping)
        store.delta { coverage = ledger }
        val before = store.readableDatabase.rawQuery("SELECT payload FROM coverage_state", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        store.ensureMappingProfile(scope, mapping)
        assertEquals(ledger, store.delta { coverage })
        assertEquals(before, store.readableDatabase.rawQuery("SELECT payload FROM coverage_state", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        store.ensureMappingProfile(scope, mapping.copy(version = 2))
        val invalidated = store.delta { coverage }
        assertNull(invalidated.observed)
        assertTrue(invalidated.active!!.windows.all { !it.prefixKnown })
        assertEquals(ledger.epoch, invalidated.epoch)
        assertEquals(ledger.active!!.windows.single().appliedPages, invalidated.active!!.windows.single().appliedPages)
        assertEquals(1, profileCount(store))
    }

    @Test fun lostLeaseRollsBackBothProfileAndConfidenceAndUnknownScopeCannotCreateProfile() = fixture { store, _ ->
        store.delta { coverage = ledger }
        var calls = 0
        store.admitTransaction = { ++calls == 1 }
        try { store.ensureMappingProfile(scope, mapping); fail("Expected lease rollback") }
        catch (_: IllegalStateException) { }
        finally { store.admitTransaction = { true } }
        assertEquals(0, profileCount(store)); assertEquals(ledger, store.delta { coverage })
        try { store.ensureMappingProfile(scope.copy(generation = 1), mapping); fail("Expected unknown scope rejection") }
        catch (_: IllegalStateException) { }
        assertEquals(0, profileCount(store))
        store.ensureMappingProfile(scope, mapping)
        store.delta { coverage = ledger }
        calls = 0; store.admitTransaction = { ++calls == 1 }
        try { store.ensureMappingProfile(scope, mapping.copy(version = 2)); fail("Expected upgrade rollback") }
        catch (_: IllegalStateException) { }
        finally { store.admitTransaction = { true } }
        store.ensureMappingProfile(scope, mapping)
        assertEquals(ledger, store.delta { coverage })
    }

    @Test fun schemaSixMigrationPreservesExactLegacyCacheReceiptPendingAndSeriesAssociation() = fixture { store, name ->
        val legacy = """{"id":"legacy","revision":"v1","deleted":false,"title":"Legacy fixture","description":"","location":"","allDay":true,"start":"2026-10-01","end":"2026-10-02"}"""
        val key = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        val master = event(null).copy(eventId = EventId("master"), content = event(null).content.copy(
            recurrence = EventRecurrence("legacy-series", rule = "FREQ=DAILY;COUNT=3")))
        store.queue(master); store.delta { received = receipt; coverage = ledger }
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE local_edit_preparations")
            db.execSQL("DROP TABLE captured_create_handoffs")
            db.execSQL("DROP TABLE captured_create_acknowledgements")
            db.execSQL("DROP TABLE captured_create_bases")
            db.execSQL("DROP TABLE IF EXISTS mapping_profiles")
            db.execSQL("DROP TABLE IF EXISTS projection_receipts")
            db.execSQL("DROP TABLE IF EXISTS projection_bases")
            db.execSQL("DROP TABLE IF EXISTS local_edits")
            db.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,1)", arrayOf(key, "legacy", legacy))
            db.version = 6
        }
        ImportStore(context, name).use { reopened ->
            assertEquals(15, reopened.readableDatabase.version); assertEquals(0, profileCount(reopened))
            assertEquals(receipt, reopened.delta { received }); assertEquals(ledger, reopened.delta { coverage })
            assertEquals("legacy-series", reopened.deletedSeriesUid(scope, master.eventId))
            assertEquals(legacy, reopened.readableDatabase.rawQuery("SELECT payload FROM records WHERE event='legacy'", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            val intent = reopened.pending(scope).single { it.change.eventId.value == "legacy" }
            assertNull((intent.change as RemoteChange.Upsert).content.details)
            reopened.acknowledge(intent)
            assertEquals(listOf(master), reopened.pending(scope).map { it.change })
        }
    }

    @Test fun contextJobsBeforeEventLimitRetainArrivalOrderWithinEachGroup() = fixture { store, name ->
        val context = CalendarChangeJob(CalendarChangeNotice("context", CalendarNoticeKind.CONTEXT_CHANGED))
        val content = CalendarChangeJob(CalendarChangeNotice("content", CalendarNoticeKind.CONTENT_CHANGED))
        store.delta {
            (1..25).forEach { putDeltaJob(CalendarChangeJob(CalendarChangeNotice("job-$it", CalendarNoticeKind.EVENT_CHANGED, EventId("event-$it")))) }
            putDeltaJob(context); putDeltaJob(content)
        }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(listOf(context, content), reopened.delta { deltaJobs(2) })
            reopened.delta { assertTrue(removeDeltaJob(context)); assertTrue(removeDeltaJob(content)) }
            assertEquals(listOf("job-1", "job-2"), reopened.delta { deltaJobs(2).map { it.notice.id } })
        }
    }

    @Test fun unfinishedOldMappingKeepsRescanObligationUntilFullScanAdmissionAndRollsBackLostLease() = fixture { store, name ->
        val unfinished = initial.copy(cursor = OpaqueCursor(format, "old-continuation"))
        store.delta { checkpoint = unfinished; coverage = ledger }
        store.ensureMappingProfile(scope, mapping)
        assertTrue(store.mappingRescanPending(scope))
        store.beginRefresh(scope)
        assertEquals(unfinished, store.delta { checkpoint })
        assertTrue(store.mappingRescanPending(scope))
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            reopened.ensureMappingProfile(scope, mapping)
            assertTrue(reopened.mappingRescanPending(scope))
            val complete = initial.copy(traversalComplete = true)
            reopened.delta { checkpoint = complete; received = receipt }
            reopened.beginRefresh(scope)
            assertEquals(complete, reopened.delta { checkpoint })
            assertTrue(reopened.mappingRescanPending(scope))
            reopened.delta { received = null }
            val before = reopened.delta { coverage }
            var calls = 0
            reopened.admitTransaction = { ++calls == 1 }
            try { reopened.beginRefresh(scope); fail("Expected rescan admission rollback") }
            catch (_: IllegalStateException) { }
            finally { reopened.admitTransaction = { true } }
            assertTrue(reopened.mappingRescanPending(scope))
            assertEquals(complete, reopened.delta { checkpoint }); assertEquals(before, reopened.delta { coverage })
            reopened.beginRefresh(scope)
            assertFalse(reopened.mappingRescanPending(scope))
            assertNull(reopened.delta { checkpoint })
            assertEquals(before.restart(), reopened.delta { coverage })
            reopened.ensureMappingProfile(scope, mapping)
            assertFalse(reopened.mappingRescanPending(scope))
            reopened.ensureMappingProfile(scope, mapping.copy(version = 2))
            assertTrue(reopened.mappingRescanPending(scope))
            reopened.beginRefresh(scope)
            assertFalse(reopened.mappingRescanPending(scope))
        }
    }

    @Test fun deltaAndPlannedRefreshCannotConsumeRequiredFullRescan() = fixture { store, _ ->
        assertFalse(store.mappingRescanPending(scope))
        store.ensureMappingProfile(scope, mapping)
        store.delta {
            checkpoint = initial.copy(traversalComplete = true); coverage = ledger
            deltaCursor = OpaqueCursor(AdapterFormat("delta-fixture", 1), "established")
        }
        store.beginDeltaRefresh(scope, ledger)
        assertTrue(store.mappingRescanPending(scope))
        val planned = initial.copy(cursor = OpaqueCursor(format, "extension"))
        store.beginRefresh(scope, planned, ledger)
        assertEquals(planned, store.delta { checkpoint })
        assertTrue(store.mappingRescanPending(scope))
        assertFalse(store.mappingRescanPending(other))
    }
}

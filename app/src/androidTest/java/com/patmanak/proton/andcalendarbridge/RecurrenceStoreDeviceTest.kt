package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic databases; no account session or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class RecurrenceStoreDeviceTest {
    private val scope = CalendarScope(AccountId("recurrence-fixture-account"), CalendarId("fixture-calendar"), 0)
    private val other = scope.copy(account = AccountId("other-fixture-account"))
    private val day = LocalDate.of(2026, 10, 1)
    private val uid = "fixture-series"
    private fun event(id: String, recurrence: EventRecurrence? = null, revision: String = "v1") = RemoteChange.Upsert(
        EventId(id), Revision(revision), EventContent("Synthetic series fixture", EventTime.AllDay(day, day.plusDays(1)), recurrence = recurrence))
    private fun master(id: String = "master", uid: String = this.uid, revision: String = "v1") =
        event(id, EventRecurrence(uid, rule = "FREQ=DAILY;COUNT=5"), revision)
    private fun child(id: String = "child", uid: String = this.uid) =
        event(id, EventRecurrence(uid, original = RecurrencePoint.AllDay(day.plusDays(1))))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recurrence-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange, target: CalendarScope = scope) = transaction(target) {
        putRemote(change); putProjection(ProjectionIntent(target, change))
    }
    private suspend fun ImportStore.drain() { pending(scope).forEach { acknowledge(it) } }

    @Test fun childBeforeParentSurvivesReopenThenMasterPrecedesItsExceptions() = fixture { store, name ->
        store.queue(child())
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(1, store.blockedSeriesCount(scope))
        assertEquals(1, store.selections(scope.account).single().pendingProjectionCount)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(child(), reopened.transaction(scope) { remote(child().eventId) })
            assertEquals(1, reopened.blockedSeriesCount(scope))
            reopened.queue(master())
            assertEquals(listOf(master(), child()), reopened.pending(scope).map { it.change })
            assertEquals(0, reopened.blockedSeriesCount(scope))
            assertEquals(0, reopened.blockedSeriesCount(other))
            reopened.drain()
            reopened.queue(master(revision = "v2"))
            assertEquals(listOf(master(revision = "v2"), child()), reopened.pending(scope).map { it.change })
        }
    }

    @Test fun manyUnknownChildrenDoNotHideEligibleRecordsAfterTheLimit() = fixture { store, _ ->
        (1..125).forEach { store.queue(child("unknown-$it", "unknown-series-$it")) }
        val simple = event("eligible-last")
        store.queue(simple)
        assertEquals(listOf(simple), store.pending(scope, limit = 1).map { it.change })
        assertEquals(125, store.blockedSeriesCount(scope))
    }

    @Test fun exceptionOriginalTypeWaitsForLaterParentUpdateRegardlessOfItsDisplayType() = fixture { store, name ->
        store.queue(master()); store.drain()
        val instant = Instant.parse("2026-10-02T08:00:00Z")
        // The exception is displayed all day but belongs to an original timed slot.
        val incoming = event("changed-child", EventRecurrence(uid, original = RecurrencePoint.Timed(instant)))
        store.queue(incoming)
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(1, store.blockedSeriesCount(scope))
        assertEquals(1, store.selections(scope.account).single().pendingProjectionCount)
        store.requeueAll(scope)
        assertEquals(listOf(master()), store.pending(scope).map { it.change })
        store.drain(); store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertTrue(reopened.pending(scope).isEmpty())
            assertEquals(1, reopened.blockedSeriesCount(scope))
            val updated = master(revision = "timed-v2").copy(content = EventContent("Timed master fixture",
                EventTime.Timed(instant.minusSeconds(86400), instant.minusSeconds(86400).plusSeconds(3600), ZoneId.of("Europe/Paris")),
                recurrence = EventRecurrence(uid, rule = "FREQ=DAILY;COUNT=5")))
            reopened.queue(updated)
            assertEquals(listOf(updated, incoming), reopened.pending(scope).map { it.change })
            assertEquals(0, reopened.blockedSeriesCount(scope))
            reopened.drain()
            assertEquals(0, reopened.selections(scope.account).single().pendingProjectionCount)
        }
    }

    @Test fun deferredCachedMasterKeepsChildrenBlockedUntilSupportedReplacementClearsTheIssue() = fixture { store, name ->
        store.queue(master()); store.queue(child()); store.drain()
        store.transaction(scope) {
            (this as StagedSyncTransaction).defer(DeferredCalendarEvent(master().eventId, Revision("unusable-v2"),
                AdapterFormat("fixture", 1), DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture"))
            removeProjection(master().eventId)
        }
        store.requeueAll(scope)
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(1, store.blockedSeriesCount(scope))
        assertEquals(1, store.selections(scope.account).single().pendingProjectionCount)
        assertEquals(child(), store.transaction(scope) { remote(child().eventId) })
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertTrue(reopened.pending(scope).isEmpty())
            assertEquals(1, reopened.blockedSeriesCount(scope))
            val replacement = master(revision = "supported-v3")
            reopened.transaction(scope) {
                // Match engine ordering: the new remote value is applied before clearing its issue.
                putRemote(replacement); putProjection(ProjectionIntent(scope, replacement))
                (this as StagedSyncTransaction).clearDeferred(replacement.eventId)
            }
            assertEquals(0, reopened.blockedSeriesCount(scope))
            assertEquals(listOf(replacement, child()), reopened.pending(scope).map { it.change })
        }
    }

    @Test fun masterDeletionSuppressesCachedAndLaterChildrenWithoutInventingRemoteTombstones() = fixture { store, name ->
        store.queue(master()); store.queue(child()); store.drain()
        val deleted = RemoteChange.Deleted(master().eventId, Revision("deleted-v2"))
        store.queue(deleted)
        assertEquals(listOf(deleted), store.pending(scope).map { it.change })
        assertEquals(uid, store.deletedSeriesUid(scope, deleted.eventId))
        assertEquals(child(), store.transaction(scope) { remote(child().eventId) })
        store.drain(); store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            reopened.queue(child("late-child"))
            assertTrue(reopened.pending(scope).isEmpty())
            assertEquals(0, reopened.blockedSeriesCount(scope))
            assertEquals(0, reopened.selections(scope.account).single().pendingProjectionCount)
            reopened.requeueAll(scope)
            assertEquals(listOf(deleted), reopened.pending(scope).map { it.change })
            reopened.queue(master(revision = "restored-v3"))
            assertEquals(setOf("master", "child", "late-child"), reopened.pending(scope).map { it.change.eventId.value }.toSet())
        }
    }

    @Test fun childDeletionIsIndependentAndMasterConversionKeepsStaleChildProtection() = fixture { store, _ ->
        store.queue(master()); store.queue(child()); store.queue(child("sibling")); store.drain()
        val deleted = RemoteChange.Deleted(child().eventId, Revision("deleted-v2"))
        store.queue(deleted)
        assertEquals(listOf(deleted), store.pending(scope).map { it.change })
        assertNull(store.deletedSeriesUid(scope, deleted.eventId))
        store.drain()
        store.queue(master(revision = "v2"))
        assertEquals(setOf("master", "sibling"), store.pending(scope).map { it.change.eventId.value }.toSet())
        store.drain()
        val simple = event("master", revision = "simple-v3")
        store.queue(simple)
        assertEquals(listOf(simple), store.pending(scope).map { it.change })
        assertEquals(uid, store.deletedSeriesUid(scope, simple.eventId))
        store.queue(child("late-child"))
        assertEquals(1, store.selections(scope.account).single().pendingProjectionCount)
        store.queue(event("late-child", revision = "simple-v2"))
        assertEquals(setOf("master", "late-child"), store.pending(scope).map { it.change.eventId.value }.toSet())
    }

    @Test fun identityAndDuplicateMasterFailuresRollBackRecordsAssociationsAndCheckpoint() = fixture { store, _ ->
        store.queue(master())
        val checkpoint = PageCheckpoint(scope, AdapterFormat("fixture", 1), traversalComplete = true)
        for (bad in listOf(master(uid = "changed-uid"), child("master"), master(id = "duplicate"))) {
            try {
                store.transaction(scope) { this.checkpoint = checkpoint; putRemote(bad); putProjection(ProjectionIntent(scope, bad)) }
                fail("Expected ambiguous identity rejection")
            } catch (_: IllegalArgumentException) { }
            assertNull(store.transaction(scope) { this.checkpoint })
            assertEquals(master(), store.transaction(scope) { remote(master().eventId) })
            assertNull(store.transaction(scope) { remote(EventId("duplicate")) })
            assertNull(store.deletedSeriesUid(scope, EventId("duplicate")))
        }
        try {
            store.transaction(scope) { putRemote(child()); error("Interrupted association") }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertNull(store.transaction(scope) { remote(child().eventId) })
        assertEquals(0, store.blockedSeriesCount(scope))
        store.queue(master(), other)
        assertEquals(listOf(master()), store.pending(other).map { it.change })
    }

    @Test fun recurrenceCodecRetainsTypedDatesCancellationAndCompareAndSwap() = fixture { store, name ->
        val instant = Instant.parse("2026-10-01T08:00:00Z")
        val timed = EventTime.Timed(instant, instant.plusSeconds(3600), ZoneId.of("Europe/Paris"))
        val parent = master().copy(content = EventContent("Typed fixture", timed, recurrence = EventRecurrence(uid,
            rule = "FREQ=WEEKLY;COUNT=4", additionalDates = listOf(RecurrencePoint.Timed(instant.plusSeconds(86400))),
            excludedDates = listOf(RecurrencePoint.Timed(instant.plusSeconds(7 * 86400))))))
        val cancelled = event("cancelled").copy(content = EventContent("Cancelled fixture", timed,
            recurrence = EventRecurrence(uid, original = RecurrencePoint.Timed(instant), cancelled = true)))
        store.queue(parent); store.queue(cancelled)
        val oldIntent = store.pending(scope).first()
        val newer = parent.copy(revision = Revision("v2"))
        store.queue(newer); store.acknowledge(oldIntent)
        assertEquals(newer, store.pending(scope).first().change)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(listOf(newer, cancelled), reopened.pending(scope).map { it.change })
            reopened.drain()
            assertTrue(reopened.pending(scope).isEmpty())
        }
    }

    @Test fun schemaFiveMigrationPreservesExactLegacyPayloadAndAcknowledgement() = fixture { store, name ->
        val legacy = "{\"id\":\"legacy\",\"revision\":\"v1\",\"deleted\":false,\"title\":\"Legacy fixture\",\"description\":\"\",\"location\":\"\",\"allDay\":true,\"start\":\"2026-10-01\",\"end\":\"2026-10-02\"}"
        val key = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE series_members")
            db.execSQL("DROP TABLE mapping_profiles")
            db.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,1)", arrayOf(key, "legacy", legacy))
            db.version = 5
        }
        ImportStore(context, name).use { reopened ->
            assertEquals(7, reopened.readableDatabase.version)
            assertEquals(legacy, reopened.readableDatabase.rawQuery("SELECT payload FROM records WHERE event='legacy'", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
            val intent = reopened.pending(scope).single()
            assertNull((intent.change as RemoteChange.Upsert).content.recurrence)
            reopened.acknowledge(intent)
            assertTrue(reopened.pending(scope).isEmpty())
            reopened.queue(child())
            assertEquals(1, reopened.blockedSeriesCount(scope))
        }
    }
}

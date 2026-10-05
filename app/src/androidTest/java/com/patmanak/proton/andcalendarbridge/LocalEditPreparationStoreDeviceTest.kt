// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated app-private SQLite fixtures; no account, network or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class LocalEditPreparationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("preparation-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("observation-fixture", 1)
    private val preparationFormat = AdapterFormat("opaque-preparation-fixture", 7)
    private val base = RemoteChange.Upsert(EventId("event"), Revision("base-one"), EventContent("Original",
        EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4))))
    private fun edit(change: RemoteChange.Upsert = base, mutation: String = "edit") =
        LocalEditIntent(MutationId(mutation), scope, format, change, change.content.copy(title = "Local title"))
    private fun preparation(intent: LocalEditIntent = edit(), payload: String = "sealed-fixture") =
        LocalEditPreparation(intent, Instant.parse("2026-10-03T12:00:00Z"), preparationFormat, payload)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val name = "local-preparation-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.setEnabled(scope, true)
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private suspend fun ImportStore.capture(intent: LocalEditIntent = edit()) {
        queue(intent.base); assertEquals(1, acknowledge(ProjectionIntent(scope, intent.base)))
        assertEquals(intent, captureLocalEdit(intent))
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected preparation refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }
    private fun raw(name: String, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rows(store: ImportStore, table: String): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
        .use { check(it.moveToFirst()); it.getInt(0) }

    @Test fun frozenPreparationSurvivesReopeningWithoutChangingOwnerOrRemote() = fixture { store, name ->
        store.capture(); assertTrue(store.retainLocalEditPreparation(preparation()))
        assertEquals(preparation(), store.localEditPreparation(scope, base.eventId))
        assertEquals(edit(), store.localEdit(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(base, store.transaction(scope) { remote(base.eventId) }); assertTrue(store.pending(scope).isEmpty())
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(preparation(), reopened.localEditPreparation(scope, base.eventId))
            assertFalse(reopened.retainLocalEditPreparation(preparation()))
        }
    }
    @Test fun exactReplayIsIdempotentButAlteredPreparationIsNeverOverwritten() = fixture { store, _ ->
        store.capture(); assertTrue(store.retainLocalEditPreparation(preparation()))
        assertFalse(store.retainLocalEditPreparation(preparation()))
        reject { store.retainLocalEditPreparation(preparation(payload = "other")) }
        reject { store.retainLocalEditPreparation(preparation().copy(timestamp = preparation().timestamp.plusSeconds(1))) }
        reject { store.retainLocalEditPreparation(preparation().copy(format = preparationFormat.copy(version = 8))) }
        assertEquals(preparation(), store.localEditPreparation(scope, base.eventId))
    }
    @Test fun requiresExactPausedCapturedOwnerAndDoesNotCreateOne() = fixture { store, _ ->
        reject { store.retainLocalEditPreparation(preparation()) }; assertNull(store.localEdit(scope, base.eventId))
        store.capture()
        reject { store.retainLocalEditPreparation(preparation(edit().copy(id = MutationId("foreign")))) }
        reject { store.retainLocalEditPreparation(preparation(edit().copy(format = format.copy(version = 2)))) }
        reject { store.retainLocalEditPreparation(preparation(edit().copy(content = base.content.copy(title = "Other")))) }
        reject { store.retainLocalEditPreparation(preparation(edit(base.copy(revision = Revision("other"))))) }
        assertNull(store.localEditPreparation(scope, base.eventId))
    }
    @Test fun unknownOrForeignScopeAndEventCannotReuseOwner() = fixture { store, _ ->
        store.capture()
        for (foreign in listOf(scope.copy(account = AccountId("other")), scope.copy(calendar = CalendarId("other")), scope.copy(generation = 1))) {
            reject { store.retainLocalEditPreparation(preparation(edit().copy(scope = foreign))) }
            reject { store.localEditPreparation(foreign, base.eventId) }
        }
        reject { store.retainLocalEditPreparation(preparation(edit(base.copy(eventId = EventId("foreign"))))) }
        assertNull(store.localEditPreparation(scope, EventId("foreign")))
    }
    @Test fun conflictBetweenPreparationAndCommitRefusesNewEntry() = fixture { store, _ ->
        store.capture(); val prepared = preparation()
        store.queue(base.copy(revision = Revision("advanced")))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        reject { store.retainLocalEditPreparation(prepared) }; assertNull(store.localEditPreparation(scope, base.eventId))
    }
    @Test fun frozenPreparationRemainsInspectionOnlyAfterConflictAndScopePause() = fixture { store, name ->
        store.capture(); store.retainLocalEditPreparation(preparation())
        store.queue(base.copy(revision = Revision("advanced"))); store.setEnabled(scope, false)
        assertEquals(preparation(), store.localEditPreparation(scope, base.eventId))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertFalse(store.retainLocalEditPreparation(preparation()))
        store.remember(scope.account, emptyList())
        assertEquals(preparation(), store.localEditPreparation(scope, base.eventId))
        assertFalse(store.retainLocalEditPreparation(preparation()))
        store.close(); ImportStore(context, name).use { assertEquals(preparation(), it.localEditPreparation(scope, base.eventId)) }
    }
    @Test fun disabledOrUnavailableScopeCannotAdmitNewPreparation() = fixture { store, _ ->
        store.capture(); store.setEnabled(scope, false)
        reject { store.retainLocalEditPreparation(preparation()) }
        store.setEnabled(scope, true); store.remember(scope.account, emptyList())
        reject { store.retainLocalEditPreparation(preparation()) }
    }
    @Test fun leaseLossBeforeOrAfterInsertRollsBackAndBlocksInspection() = fixture { store, _ ->
        store.capture(); var calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { store.retainLocalEditPreparation(preparation()) }
        store.admitTransaction = { true }; assertNull(store.localEditPreparation(scope, base.eventId))
        store.admitTransaction = { false }; reject { store.retainLocalEditPreparation(preparation()) }
        reject { store.localEditPreparation(scope, base.eventId) }
        store.admitTransaction = { true }; store.retainLocalEditPreparation(preparation())
        calls = 0; store.admitTransaction = { ++calls == 1 }; reject { store.localEditPreparation(scope, base.eventId) }
    }
    @Test fun cancellationBeforeCommitRollsBackNewPreparation() = fixture { store, _ ->
        store.capture(); val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.retainLocalEditPreparation(preparation()) }; fail("Expected cancellation") }
        catch (_: CancellationException) { } finally { store.admitTransaction = { true } }
        assertNull(store.localEditPreparation(scope, base.eventId)); assertEquals(edit(), store.localEdit(scope, base.eventId))
    }
    @Test fun sqlFaultRollsBackWithoutChangingOwnerOrProjection() = fixture { store, _ ->
        store.capture()
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_preparation BEFORE INSERT ON local_edit_preparations BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { store.retainLocalEditPreparation(preparation()) }
        assertNull(store.localEditPreparation(scope, base.eventId)); assertEquals(edit(), store.localEdit(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun largeSealedPayloadExceedsDefaultCursorWindowButReopensExactly() = fixture { store, name ->
        store.capture(); val large = preparation(payload = "s".repeat(3 * 1024 * 1024))
        assertTrue(store.retainLocalEditPreparation(large)); assertEquals(large, store.localEditPreparation(scope, base.eventId))
        store.close(); ImportStore(context, name).use { assertEquals(large, it.localEditPreparation(scope, base.eventId)) }
    }
    @Test fun twoHelpersRacingDifferentPreparationsHaveExactlyOneWinner() = fixture { store, name ->
        store.capture(); ImportStore(context, name).use { other ->
            val values = listOf(preparation(), preparation(payload = "second"))
            val results = coroutineScope { values.mapIndexed { index, value -> async(Dispatchers.IO) {
                try { (if (index == 0) store else other).retainLocalEditPreparation(value) } catch (_: IllegalArgumentException) { false }
            } }.awaitAll() }
            assertEquals(1, results.count { it }); val winner = values[results.indexOf(true)]
            assertEquals(winner, store.localEditPreparation(scope, base.eventId)); assertEquals(winner, other.localEditPreparation(scope, base.eventId))
        }
    }
    @Test fun orphanedOrAlteredOwnersRefuseInspectionAndReplay() = fixture { store, name ->
        store.capture(); store.retainLocalEditPreparation(preparation())
        raw(name) { it.execSQL("UPDATE local_edits SET mutation='altered'") }
        reject { store.localEditPreparation(scope, base.eventId) }; reject { store.retainLocalEditPreparation(preparation()) }
        raw(name) { it.execSQL("DELETE FROM local_edits") }
        reject { store.localEditPreparation(scope, base.eventId) }; assertEquals(1, rows(store, "local_edit_preparations"))
    }
    @Test fun corruptFrozenLocalIdentityAndMetadataAreNotReinterpreted() = fixture { store, name ->
        store.capture(); store.retainLocalEditPreparation(preparation())
        raw(name) { it.execSQL("UPDATE local_edit_preparations SET timestamp='malformed'") }
        reject { store.localEditPreparation(scope, base.eventId) }
    }
    @Test fun currentOwnerLocalEventOrRevisionMismatchCannotAuthenticatePreparation() = fixture { store, name ->
        store.capture(); store.retainLocalEditPreparation(preparation())
        val original = store.readableDatabase.rawQuery("SELECT local FROM local_edits", null).use {
            check(it.moveToFirst()); it.getString(0)
        }
        for (field in listOf("id", "revision")) {
            raw(name) { it.execSQL("UPDATE local_edits SET local=?", arrayOf(JSONObject(original).put(field, "foreign").toString())) }
            reject { store.localEditPreparation(scope, base.eventId) }
            reject { store.retainLocalEditPreparation(preparation()) }
        }
        raw(name) { it.execSQL("UPDATE local_edits SET local=?", arrayOf(original)) }
        assertEquals(preparation(), store.localEditPreparation(scope, base.eventId))
    }
    @Test fun mutableEmptyDetailsAreFrozenBeforeLeaseCallbackCanChangeCallerInput() = fixture { store, _ ->
        val mutable = mutableListOf<EventReminder>()
        val detailedBase = base.copy(content = base.content.copy(details = EventDetails(reminders = mutable)))
        val intent = edit(detailedBase); store.capture(intent)
        val prepared = preparation(intent)
        val expected = prepared.copy(intent = LocalEditPreparation.freezeIntent(intent))
        var calls = 0
        store.admitTransaction = { if (++calls == 1) mutable.add(EventReminder(10)); true }
        assertTrue(store.retainLocalEditPreparation(prepared)); store.admitTransaction = { true }
        assertEquals(expected, store.localEditPreparation(scope, base.eventId))
        assertEquals(1, mutable.size)
    }
    @Test fun unknownPreparationFormatIsRetainedAsOpaqueEvidence() = fixture { store, _ ->
        store.capture(); val unknown = preparation().copy(format = AdapterFormat("future-adapter", 999))
        assertTrue(store.retainLocalEditPreparation(unknown)); assertEquals(unknown, store.localEditPreparation(scope, base.eventId))
        assertFalse(store.retainLocalEditPreparation(unknown))
    }
    @Test fun migrationFromThirteenPreservesOwnerBaselineAndReceivedCheckpoint() = fixture { store, name ->
        store.capture(); val receipt = ReceivedCalendarPage(PageRequest(PageCheckpoint(scope, format), 100), "sealed-inbox")
        store.transaction(scope) { (this as StagedSyncTransaction).received = receipt }
        store.close()
        raw(name) {
            it.execSQL("DROP TABLE local_edit_preparations")
            it.execSQL("DROP TABLE title_update_acknowledgement_history")
            it.execSQL("DROP TABLE title_update_acknowledgements")
            it.version = 13
        }
        ImportStore(context, name).use { upgraded ->
            assertEquals(17, upgraded.readableDatabase.version)
            assertEquals(edit(), upgraded.localEdit(scope, base.eventId)); assertEquals(base, upgraded.projectedBaseline(scope, base.eventId))
            assertEquals(receipt, upgraded.transaction(scope) { (this as StagedSyncTransaction).received })
            assertNull(upgraded.localEditPreparation(scope, base.eventId)); assertTrue(upgraded.retainLocalEditPreparation(preparation()))
            assertEquals(0, rows(upgraded, "captured_create_handoffs"))
        }
    }
    @Test fun preparationsShareStagingCapacityAndOverflowRollsBack() = fixture { store, _ ->
        val large = "s".repeat(4 * 1024 * 1024)
        val first = edit(); store.capture(first); assertTrue(store.retainLocalEditPreparation(preparation(first, large)))
        for (index in 1..6) {
            val next = edit(base.copy(eventId = EventId("event-$index")), "edit-$index")
            store.capture(next); assertTrue(store.retainLocalEditPreparation(preparation(next, large)))
        }
        val last = edit(base.copy(eventId = EventId("overflow")), "overflow")
        store.capture(last)
        try { store.retainLocalEditPreparation(preparation(last, large)); fail("Expected shared staging capacity refusal") }
        catch (_: ImportCapacityException) { }
        assertNull(store.localEditPreparation(scope, last.base.eventId)); assertEquals(last, store.localEdit(scope, last.base.eventId))
        assertEquals(7, rows(store, "local_edit_preparations"))
    }
}

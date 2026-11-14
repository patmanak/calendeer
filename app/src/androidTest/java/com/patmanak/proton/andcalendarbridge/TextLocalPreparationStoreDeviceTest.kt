// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTextPreparationAdmission
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTitlePreparationAdmission
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated SQLite fixtures only. No Calendar Provider, account session or network. */
class TextLocalPreparationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("text-preparation-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-text-import", 1)
    private val base = RemoteChange.Upsert(EventId("event"), Revision("original"), EventContent("Original",
        EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 2)), "Old\r\ndescription", "Old place", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("local-edit"), scope, format, base,
        base.content.copy(title = "New\r\ntitle", description = "New\r\ndescription", location = "New\r\nplace"))
    private fun preparation(intent: LocalEditIntent = this.intent, timestamp: Instant = Instant.parse("2030-03-01T12:00:00Z"),
        format: AdapterFormat = AdapterFormat("synthetic-text-preparation", 1), payload: String = "opaque-text-preparation") =
        LocalTextEditPreparation(intent, timestamp, format, payload)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "text-preparation-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic preparation fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun assertPreparation(expected: LocalTextEditPreparation, actual: LocalTextEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }
    private suspend fun capture(store: ImportStore, value: LocalEditIntent = intent) { assertEquals(value, store.captureLocalEdit(value)) }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected closed storage refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: android.database.sqlite.SQLiteException) { }
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> = db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
        buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else "${row.getType(it)}:${row.getString(it)}" }) }
    }

    @Test fun exactRawPreparationPersistsAndReopensWithoutChangingOwnerOrBase() = fixture { store, name ->
        capture(store); val value = preparation()
        assertEquals(LocalTextPreparationAdmission.ELIGIBLE, store.assessLocalTextPreparation(intent))
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(value))
        assertPreparation(value, store.localTextEditPreparation(scope, base.eventId))
        assertEquals(intent, store.localEdit(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertPreparation(value, reopened.localTextEditPreparation(scope, base.eventId))
            assertEquals(LocalTextPreparationAdmission.HISTORICAL, reopened.retainCurrentLocalTextPreparation(value))
        }
    }
    @Test fun firstCapsuleCannotBeReplacedByDifferentTimestampFormatOrPayload() = fixture { store, _ ->
        capture(store); val first = preparation()
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(first))
        for (other in listOf(preparation(timestamp = first.timestamp.plusSeconds(1)), preparation(format = first.format.copy(version = 2)),
            preparation(payload = "different-capsule"))) {
            assertEquals(LocalTextPreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTextPreparation(other))
            assertPreparation(first, store.localTextEditPreparation(scope, base.eventId))
        }
    }
    @Test fun fullRawOwnerIdentityAndCheckpointAreRequired() = fixture { store, _ ->
        assertEquals(LocalTextPreparationAdmission.UNQUALIFIED, store.assessLocalTextPreparation(intent)); capture(store)
        for (other in listOf(intent.copy(id = MutationId("other")), intent.copy(format = format.copy(version = 2)),
            intent.copy(content = intent.content.copy(description = "New\ndescription")))) {
            assertEquals(LocalTextPreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTextPreparation(preparation(other)))
        }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        assertEquals(LocalTextPreparationAdmission.UNQUALIFIED, store.assessLocalTextPreparation(intent))
        assertNull(store.localTextEditPreparation(scope, base.eventId))
    }
    @Test fun disabledUnavailableForeignAndChangedRemoteCannotAdmit() = fixture { store, _ ->
        capture(store); store.setEnabled(scope, false)
        assertEquals(LocalTextPreparationAdmission.INACTIVE, store.retainCurrentLocalTextPreparation(preparation()))
        store.setEnabled(scope, true)
        for (other in listOf(scope.copy(generation = 1), scope.copy(account = AccountId("other")), scope.copy(calendar = CalendarId("other"))))
            reject { store.retainCurrentLocalTextPreparation(preparation(intent.copy(scope = other))) }
        store.transaction(scope) { putRemote(base.copy(revision = Revision("newer"))) }
        assertEquals(LocalTextPreparationAdmission.CONFLICT, store.assessLocalTextPreparation(intent))
        assertNull(store.localTextEditPreparation(scope, base.eventId))
        store.remember(scope.account, emptyList())
        assertEquals(LocalTextPreparationAdmission.INACTIVE, store.assessLocalTextPreparation(intent))
    }
    @Test fun conflictIsMonotoneButHistoricalCapsuleRemainsReadableAfterPause() = fixture { store, name ->
        capture(store); val value = preparation()
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(value))
        assertEquals(LocalTextPreparationAdmission.CONFLICT, store.conflictLocalTextPreparation(intent))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        store.setEnabled(scope, false); store.remember(scope.account, emptyList()); store.close()
        ImportStore(context, name).use { reopened -> assertPreparation(value, reopened.localTextEditPreparation(scope, base.eventId)) }
    }
    @Test fun titleAndTextPreparationsCannotOwnSameEventInEitherOrder() {
        for (textFirst in listOf(false, true)) fixture { store, _ ->
            // A title-only synthetic intent is admitted by both preparation domains; routing remains a coordinator policy.
            val titleIntent = intent.copy(content = base.content.copy(title = "Only title")); capture(store, titleIntent)
            val title = LocalEditPreparation(titleIntent, preparation().timestamp, AdapterFormat("synthetic-title", 1), "title-capsule")
            val text = preparation(titleIntent)
            if (textFirst) {
                assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(text))
                assertEquals(LocalTitlePreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTitlePreparation(title))
                reject { store.retainLocalEditPreparation(title) }; assertNull(store.localEditPreparation(scope, base.eventId))
            } else {
                assertEquals(LocalTitlePreparationAdmission.STORED, store.retainCurrentLocalTitlePreparation(title))
                assertEquals(LocalTextPreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTextPreparation(text))
                assertNull(store.localTextEditPreparation(scope, base.eventId))
            }
        }
    }
    @Test fun independentHelpersRetainOnlyOneFirstCapsule() = fixture { store, name ->
        capture(store)
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope { listOf(store, other).map { helper -> async(Dispatchers.IO) {
                gate.await(); helper.retainCurrentLocalTextPreparation(preparation())
            } }.also { gate.complete(Unit) }.awaitAll() }
            assertEquals(1, outcomes.count { it == LocalTextPreparationAdmission.STORED })
            assertEquals(1, outcomes.count { it == LocalTextPreparationAdmission.HISTORICAL })
            assertPreparation(preparation(), store.localTextEditPreparation(scope, base.eventId))
        }
    }
    @Test fun leaseRevocationAndCancelledCommitRollBackPreparation() = fixture { store, _ ->
        capture(store); var calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { store.retainCurrentLocalTextPreparation(preparation()) }
        store.admitTransaction = { true }; assertNull(store.localTextEditPreparation(scope, base.eventId))
        for (ordinaryFailure in listOf(false, true)) supervisorScope {
            var cancelled = 0; var other = 0; var returned = 0
            val child = async {
                val job = currentCoroutineContext().job; calls = 0
                store.admitTransaction = {
                    if (++calls == 2) {
                        job.cancel()
                        if (ordinaryFailure) throw java.io.IOException("Synthetic cancelled commit")
                    }
                    true
                }
                try { store.retainCurrentLocalTextPreparation(preparation()); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { other++ }
            }
            try { child.await() } catch (_: CancellationException) { }
            store.admitTransaction = { true }
            assertEquals(1, cancelled); assertEquals(0, other); assertEquals(0, returned)
        }
        assertNull(store.localTextEditPreparation(scope, base.eventId)); assertEquals(intent, store.localEdit(scope, base.eventId))
    }
    @Test fun largeOpaquePayloadAndEmptyDetailsSnapshotsSurviveReopen() = fixture { store, name ->
        val reminders = mutableListOf<EventReminder>()
        val withLists = intent.copy(base = base.copy(content = base.content.copy(details = EventDetails(reminders = reminders))),
            content = intent.content.copy(details = EventDetails(reminders = reminders)))
        capture(store, withLists); val value = preparation(withLists, payload = "x".repeat(2 * 1024 * 1024 + 17))
        var calls = 0; store.admitTransaction = { if (++calls == 1) reminders.add(EventReminder(10)); true }
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(value))
        store.admitTransaction = { true }; store.close()
        ImportStore(context, name).use { reopened -> assertPreparation(value, reopened.localTextEditPreparation(scope, base.eventId)) }
        assertEquals(1, reminders.size)
    }
    @Test fun malformedRetainedMetadataAndOwnerNeverBecomeNewPreparation() = fixture { store, _ ->
        capture(store); assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(preparation()))
        val original = rows(store.readableDatabase, "local_text_edit_preparations")
        store.writableDatabase.execSQL("UPDATE local_text_edit_preparations SET timestamp='not-a-timestamp'")
        val damaged = rows(store.readableDatabase, "local_text_edit_preparations")
        reject { store.localTextEditPreparation(scope, base.eventId) }
        reject { store.retainCurrentLocalTextPreparation(preparation()) }
        assertEquals(damaged, rows(store.readableDatabase, "local_text_edit_preparations")); assertNotEquals(original, damaged)
    }
    @Test fun migrationsFromEighteenAndNineteenPreserveHistoricalBytesAndAddAllTextTables() {
        for (version in listOf(18, 19)) fixture { store, name ->
            capture(store)
            val tables = listOf("calendars", "records", "projection_bases", "local_edits", "conflict_archives", "conflict_observations")
            val before = tables.associateWith { rows(store.readableDatabase, it) }
            for (table in listOf("personal_edit_preparations", "personal_update_acknowledgements", "personal_update_acknowledgement_history", "delete_acknowledgements", "local_time_edit_preparations", "time_update_acknowledgements", "time_update_acknowledgement_history", "local_text_edit_preparations", "text_update_acknowledgements", "text_update_acknowledgement_history"))
                store.writableDatabase.execSQL("DROP TABLE $table")
            store.writableDatabase.version = version; store.close()
            ImportStore(context, name).use { reopened ->
                assertEquals(26, reopened.readableDatabase.version)
                assertEquals(before, tables.associateWith { rows(reopened.readableDatabase, it) })
                for (table in listOf("local_text_edit_preparations", "text_update_acknowledgements", "text_update_acknowledgement_history"))
                    assertTrue(rows(reopened.readableDatabase, table).isEmpty())
                assertEquals(LocalTextPreparationAdmission.STORED, reopened.retainCurrentLocalTextPreparation(preparation()))
            }
        }
    }
    @Test fun failedAdditiveMigrationRollsBackVersionAndExistingBytes() = fixture { store, name ->
        capture(store); val before = rows(store.readableDatabase, "local_edits")
        store.writableDatabase.execSQL("DROP TABLE personal_edit_preparations")
        store.writableDatabase.execSQL("DROP TABLE personal_update_acknowledgements")
        store.writableDatabase.execSQL("DROP TABLE personal_update_acknowledgement_history")
        store.writableDatabase.execSQL("DROP TABLE delete_acknowledgements")
        store.writableDatabase.execSQL("DROP TABLE local_time_edit_preparations")
        store.writableDatabase.execSQL("DROP TABLE time_update_acknowledgements")
        store.writableDatabase.execSQL("DROP TABLE time_update_acknowledgement_history")
        store.writableDatabase.execSQL("DROP TABLE local_text_edit_preparations")
        store.writableDatabase.execSQL("DROP TABLE text_update_acknowledgements")
        store.writableDatabase.version = 19; store.close()
        ImportStore(context, name).use { reopened -> reject { reopened.writableDatabase } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(19, raw.version); assertEquals(before, rows(raw, "local_edits"))
            assertFalse(raw.rawQuery("SELECT 1 FROM sqlite_master WHERE name='local_text_edit_preparations'", null).use { it.moveToFirst() })
        }
    }
}

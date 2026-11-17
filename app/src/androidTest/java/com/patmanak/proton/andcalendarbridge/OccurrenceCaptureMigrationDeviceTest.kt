// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Independent historical schema-3 DDL; production upgrade is the operation under test. */
class OccurrenceCaptureMigrationDeviceTest {
    @Test fun schemaThreePreservesRawIntentPreparationAndSubmissionWithoutInventingOccurrenceContext() = fixture { context, name, raw ->
        LocalCreateStore(context, name).use { store ->
            assertEquals(4, store.readableDatabase.version)
            assertEquals(intent, store.readIntent(scope, reservation.localId))
            assertEquals(preparation, store.readPreparation(scope, reservation.localId))
            assertEquals(submission, store.readSubmissionReservation(scope, reservation.localId))
            assertEquals("historical-sealed-request", store.readSubmission(scope, reservation.localId)!!.protectedPayload)
            assertNull(store.readOccurrenceContext(scope, reservation.localId))
            store.readableDatabase.rawQuery("SELECT content,occurrence_capture,occurrence_slot FROM local_creates", null).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            store.readableDatabase.rawQuery("SELECT captured_content FROM local_create_submissions", null).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0))
            }
        }
    }

    @Test fun interruptedProductionUpgradeRollsBackBothColumnsAndTheIndexBeforeReopening() = fixture { context, name, raw ->
        object : SQLiteOpenHelper(context, name, null, 4) {
            override fun onCreate(db: SQLiteDatabase) = error("Historical fixture required")
            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
                LocalCreateStore(context, name).use { it.onUpgrade(db, old, new) }
                error("Synthetic interruption after production migration")
            }
        }.use { helper -> assertThrows(IllegalStateException::class.java) { helper.writableDatabase } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(3, db.version)
            db.rawQuery("PRAGMA table_info(local_creates)", null).use { cursor ->
                while (cursor.moveToNext()) assertFalse(cursor.getString(1).startsWith("occurrence_"))
            }
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='local_occurrence_slot'", null).use { assertEquals(0, it.count) }
            db.rawQuery("SELECT content FROM local_creates", null).use { assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0)) }
        }
        LocalCreateStore(context, name).use { store ->
            assertEquals(4, store.readableDatabase.version); assertEquals(intent, store.readIntent(scope, reservation.localId))
            assertNull(store.readOccurrenceContext(scope, reservation.localId))
        }
    }

    private val scope = CalendarScope(AccountId("migration-fixture"), CalendarId("calendar"), 0)
    private val reservation = LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"),
        scope, AdapterFormat("fixture", 1), Instant.parse("2030-04-01T12:00:00Z"))
    private val intent = LocalCreateIntent(reservation, EventContent("Historical fixture",
        EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2)), details = EventDetails()))
    private val preparation = LocalCreatePreparation(intent, AdapterFormat("historical", 1), "historical-sealed-preparation")
    private val submission = LocalCreateSubmissionReservation(intent, MutationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), AdapterFormat("historical-submission", 2))

    private fun fixture(block: suspend (Context, String, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "occurrence-migration-${UUID.randomUUID()}.db"
        val raw = JSONObject().put("schema", 1).put("knownEmptyDetails", true).put("reservation", JSONObject().apply {
            put("id", reservation.id.value); put("localId", reservation.localId.value)
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
            put("adapter", reservation.format.id); put("version", reservation.format.version)
            put("timestamp", reservation.timestamp.toString()); put("markerProtocol", 1)
        }).put("title", "Historical fixture").put("description", "").put("location", "")
            .put("allDay", true).put("start", "2030-04-01").put("end", "2030-04-02").toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("""CREATE TABLE local_creates(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),
                    local_event TEXT NOT NULL,mutation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),
                    created_at TEXT NOT NULL,marker_protocol INTEGER NOT NULL CHECK(marker_protocol=1),content TEXT,
                    prep_adapter TEXT,prep_version INTEGER,prep_payload TEXT,
                    PRIMARY KEY(account,calendar,generation,local_event),UNIQUE(account,calendar,generation,mutation),
                    CHECK((prep_adapter IS NULL AND prep_version IS NULL AND prep_payload IS NULL) OR
                    (prep_adapter IS NOT NULL AND prep_version IS NOT NULL AND prep_payload IS NOT NULL AND prep_version>0)))""".trimIndent())
                db.execSQL("""CREATE TABLE local_create_submissions(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),
                    local_event TEXT NOT NULL,attempt TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),
                    captured_content TEXT NOT NULL,payload TEXT CHECK(payload IS NULL OR length(trim(payload))>0),
                    PRIMARY KEY(account,calendar,generation,local_event),UNIQUE(account,calendar,generation,attempt),
                    FOREIGN KEY(account,calendar,generation,local_event) REFERENCES local_creates(account,calendar,generation,local_event))""".trimIndent())
                db.insertOrThrow("local_creates", null, ContentValues().apply {
                    put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                    put("local_event", reservation.localId.value); put("mutation", reservation.id.value)
                    put("adapter", reservation.format.id); put("version", reservation.format.version); put("created_at", reservation.timestamp.toString())
                    put("marker_protocol", 1); put("content", raw); put("prep_adapter", preparation.format.id)
                    put("prep_version", preparation.format.version); put("prep_payload", preparation.protectedPayload)
                })
                db.insertOrThrow("local_create_submissions", null, ContentValues().apply {
                    put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                    put("local_event", reservation.localId.value); put("attempt", submission.attempt.value)
                    put("adapter", submission.format.id); put("version", submission.format.version)
                    put("captured_content", raw); put("payload", "historical-sealed-request")
                })
                db.version = 3
            }
            block(context, name, raw)
        } finally { context.deleteDatabase(name) }
    }
}

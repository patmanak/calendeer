// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSchema
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated historical SQL and synthetic series only; no account, Provider or service access. */
@RunWith(AndroidJUnit4::class)
class CapturedSeriesSchemaDeviceTest {
    private val scope = CalendarScope(AccountId("series-schema-fixture"), CalendarId("calendar"), 0)
    private val content = EventContent("Synthetic series", EventTime.AllDay(LocalDate.parse("2030-10-02"),
        LocalDate.parse("2030-10-03")), details = EventDetails())
    private fun record(index: Int = 1) = CreateAttemptRecord(MutationId(UUID(0, index * 10L).toString()), scope,
        AdapterFormat("synthetic-submission", if (index == 1) 2 else 4), "synthetic-opaque-payload-$index",
        CreateAttemptState.OUTCOME_UNKNOWN, "synthetic-opaque-evidence-$index")
    private fun identity(index: Int = 1): CapturedCreateDispatchIdentity = CapturedCreateDispatchIdentity.of(record(index),
        LocalCreateIntent(LocalCreateReservation(MutationId("capture-$index"), LocalEventId(UUID(0, index.toLong()).toString()),
            scope, AdapterFormat("synthetic-import", 1), Instant.parse("2030-01-01T00:00:00Z")), content,
            if (index == 1) null else CreateSeriesRule(5)))
    private fun receipt(index: Int = 1) = CapturedCreateAttachmentReceipt(CapturedCreateBinding(identity(index),
        EventId("event-$index"), Revision("verified-$index")), UUID(0, 100L + index).toString())
    private val tables = listOf("captured_create_links", "captured_create_bindings", "captured_create_attachments")

    // Frozen captured constraints from schema 7, with minimal parent/read-only generic tables.
    private val identityColumns = """
        account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation>=0),
        mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0),
        payload_fingerprint TEXT NOT NULL, local_id TEXT NOT NULL, capture_id TEXT NOT NULL,
        capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version>0),
        timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
        fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version=1)
    """.trimIndent()
    private fun legacyCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE create_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,
            mutation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,payload TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),evidence TEXT,
            PRIMARY KEY(account,calendar,generation,mutation))""")
        db.execSQL("CREATE TABLE create_bindings(account TEXT,calendar TEXT,generation INTEGER,mutation TEXT,event TEXT)")
        db.execSQL("""CREATE TABLE captured_create_links($identityColumns,
            PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,generation,local_id),
            UNIQUE(account,calendar,generation,capture_id),FOREIGN KEY(account,calendar,generation,mutation)
            REFERENCES create_attempts(account,calendar,generation,mutation))""")
        db.execSQL("""CREATE TABLE captured_create_bindings($identityColumns,event TEXT NOT NULL,verified_revision TEXT NOT NULL,
            PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,generation,event),
            FOREIGN KEY(account,calendar,generation,mutation) REFERENCES captured_create_links(account,calendar,generation,mutation))""")
        db.execSQL("""CREATE TABLE captured_create_attachments($identityColumns,event TEXT NOT NULL,verified_revision TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE,protocol INTEGER NOT NULL CHECK(protocol=1),
            PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,generation,event),
            FOREIGN KEY(account,calendar,generation,mutation) REFERENCES captured_create_bindings(account,calendar,generation,mutation))""")
        seedCreate(db, 1)
    }
    private fun linkValues(index: Int) = ContentValues().apply {
        val link = identity(index)
        put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        put("mutation", link.attempt.id.value); put("adapter", link.attempt.format.id); put("version", link.attempt.format.version)
        put("payload_fingerprint", link.attempt.payloadFingerprint); put("local_id", link.localId.value)
        put("capture_id", link.captureId.value); put("capture_adapter", link.captureFormat.id); put("capture_version", link.captureFormat.version)
        put("timestamp", link.timestamp.epochSecond); put("content_fingerprint", link.contentFingerprint); put("fingerprint_version", link.fingerprintVersion)
    }
    private fun seedCreate(db: SQLiteDatabase, index: Int) {
        val record = record(index)
        db.insertOrThrow("create_attempts", null, ContentValues().apply {
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
            put("mutation", record.id.value); put("adapter", record.format.id); put("version", record.format.version)
            put("payload", record.protectedPayload); put("state", record.state.name); put("evidence", record.protectedEvidence)
        })
        val row = linkValues(index)
        db.insertOrThrow(tables[0], null, row)
        row.put("event", receipt(index).binding.eventId.value); row.put("verified_revision", receipt(index).binding.verifiedRevision.value)
        db.insertOrThrow(tables[1], null, row)
        row.put("nonce", receipt(index).nonce); row.put("protocol", 1)
        db.insertOrThrow(tables[2], null, row)
    }
    private fun legacyImport(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captured_create_bases(scope TEXT NOT NULL,event TEXT NOT NULL,
            account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),
            attempt TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),
            payload_fingerprint TEXT NOT NULL,local_id TEXT NOT NULL,capture_id TEXT NOT NULL,capture_adapter TEXT NOT NULL,
            capture_version INTEGER NOT NULL CHECK(capture_version>0),timestamp INTEGER NOT NULL,content_fingerprint TEXT NOT NULL,
            fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version=1),verified_revision TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE,protocol INTEGER NOT NULL CHECK(protocol=1),payload TEXT NOT NULL,
            PRIMARY KEY(scope,event),UNIQUE(scope,attempt),UNIQUE(scope,local_id),UNIQUE(scope,capture_id))""")
        db.execSQL("""CREATE TABLE captured_create_acknowledgements(scope TEXT NOT NULL,event TEXT NOT NULL,nonce TEXT NOT NULL UNIQUE,
            protocol INTEGER NOT NULL CHECK(protocol=1),fingerprint TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('UNKNOWN','PROVEN')),
            PRIMARY KEY(scope,event))""")
        db.execSQL("""CREATE TABLE captured_create_handoffs(scope TEXT NOT NULL,event TEXT NOT NULL,nonce TEXT NOT NULL UNIQUE,
            protocol INTEGER NOT NULL CHECK(protocol=1),ack_nonce TEXT NOT NULL,ack_fingerprint TEXT NOT NULL,fingerprint TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('PENDING','RELEASED')),waiting INTEGER NOT NULL DEFAULT 0 CHECK(waiting IN (0,1)),
            last_projection TEXT,PRIMARY KEY(scope,event))""")
        val row = linkValues(1).apply {
            put("scope", "synthetic-scope"); put("event", "event-1"); put("attempt", getAsString("mutation")); remove("mutation")
            put("verified_revision", "verified-1"); put("nonce", "synthetic-base-nonce"); put("protocol", 1)
            put("payload", "synthetic-baseline-payload")
        }
        db.insertOrThrow("captured_create_bases", null, row)
        for (state in listOf("UNKNOWN", "PROVEN")) db.execSQL("INSERT INTO captured_create_acknowledgements VALUES(?,?,?,1,?,?)",
            arrayOf("synthetic-scope", "event-$state", "ack-$state", "synthetic-ack-fingerprint", state))
        for (state in listOf("PENDING", "RELEASED")) db.execSQL("INSERT INTO captured_create_handoffs VALUES(?,?,?,1,?,?,?, ?,1,?)",
            arrayOf("synthetic-scope", "event-$state", "handoff-$state", "synthetic-ack-nonce", "synthetic-ack-fingerprint",
                "synthetic-handoff-fingerprint", state, "synthetic-last-projection"))
    }
    private fun snapshot(db: SQLiteDatabase, names: List<String>): Map<String, List<List<String?>>> = names.associateWith { table ->
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { rows -> buildList {
            while (rows.moveToNext()) add((0 until rows.columnCount).map { if (rows.isNull(it)) null else "${rows.getType(it)}:${rows.getString(it)}" })
        } }
    }
    private fun fixture(importing: Boolean = false, block: (Context, String, SQLiteDatabase) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "series-schema-fixture-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                if (importing) legacyImport(db) else legacyCreate(db)
                db.version = if (importing) 24 else 7
                block(context, name, db)
            }
        } finally { context.deleteDatabase(name) }
    }
    private fun upgrade(context: Context, name: String, importing: Boolean, failAfter: Boolean = false) =
        object : SQLiteOpenHelper(context, name, null, if (importing) 25 else 8) {
            override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SQLiteDatabase) = error("Historical fixture required")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (importing) ImportSchema.upgrade(db, oldVersion, newVersion) { }
                else CreateAttemptStore(context, name).use { it.onUpgrade(db, oldVersion, newVersion) }
                if (failAfter) error("Synthetic interrupted upgrade")
            }
        }
    @Test fun schemaSevenPreservesUnknownEvidenceAndAllIdentityRowsThenAcceptsVersionTwo() = fixture { context, name, raw ->
        val names = listOf("create_attempts") + tables
        val before = snapshot(raw, names)
        assertThrows(SQLiteException::class.java) { raw.execSQL("UPDATE captured_create_links SET fingerprint_version=2") }
        upgrade(context, name, false).use { helper ->
            val db = helper.writableDatabase
            assertEquals(8, db.version); assertEquals(before, snapshot(db, names))
            seedCreate(db, 2)
            for (table in tables) assertThrows(SQLiteException::class.java) { db.execSQL("UPDATE $table SET fingerprint_version=3") }
            assertThrows(SQLiteException::class.java) { db.execSQL("DELETE FROM captured_create_links") }
            assertThrows(SQLiteException::class.java) { db.insertOrThrow(tables[0], null, linkValues(2)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
        upgrade(context, name, false).use { assertEquals(2, snapshot(it.readableDatabase, tables)[tables[0]]!!.size) }
        CreateAttemptStore(context, name).use { store -> runBlocking {
            assertEquals(record(2), store.read(scope, record(2).id))
            assertEquals(identity(2), store.capturedIdentity(scope, record(2).id))
            assertEquals(receipt(2).binding, store.capturedBinding(identity(2)))
            assertEquals(receipt(2), store.capturedAttachment(receipt(2).binding))
        } }
    }
    @Test fun schemaTwentyFourKeepsBaselineAckAndHandoffBytesAndConstraints() = fixture(true) { context, name, raw ->
        val names = listOf("captured_create_bases", "captured_create_acknowledgements", "captured_create_handoffs")
        val before = snapshot(raw, names)
        assertThrows(SQLiteException::class.java) { raw.execSQL("UPDATE captured_create_bases SET fingerprint_version=2") }
        upgrade(context, name, true).use { helper ->
            val db = helper.writableDatabase
            assertEquals(25, db.version); assertEquals(before, snapshot(db, names))
            db.execSQL("UPDATE captured_create_bases SET fingerprint_version=2")
            assertThrows(SQLiteException::class.java) { db.execSQL("UPDATE captured_create_bases SET fingerprint_version=3") }
            assertThrows(SQLiteException::class.java) { db.execSQL("INSERT INTO captured_create_bases SELECT * FROM captured_create_bases") }
        }
    }
    @Test fun interruptedUpgradesRollbackRowsConstraintsAndSchemaVersionInBothDatabases() {
        for (importing in listOf(false, true)) fixture(importing) { context, name, raw ->
            val names = if (importing) listOf("captured_create_bases", "captured_create_acknowledgements", "captured_create_handoffs")
                else listOf("create_attempts") + tables
            val before = snapshot(raw, names)
            upgrade(context, name, importing, true).use { helper ->
                assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
            }
            assertEquals(if (importing) 24 else 7, raw.version); assertEquals(before, snapshot(raw, names))
            val table = if (importing) names[0] else tables[0]
            assertThrows(SQLiteException::class.java) { raw.execSQL("UPDATE $table SET fingerprint_version=2") }
            upgrade(context, name, importing).use { assertEquals(if (importing) 25 else 8, it.writableDatabase.version) }
        }
    }
    @Test fun versionTwoBaselineRoundTripsThroughTheExistingImportStore() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "series-baseline-fixture-${UUID.randomUUID()}.db"
        val receipt = receipt(2)
        val change = RemoteChange.Upsert(receipt.binding.eventId, receipt.binding.verifiedRevision,
            content.copy(recurrence = EventRecurrence("synthetic@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
        val baseline = CapturedCreateBaseline(receipt, change)
        try {
            ImportStore(context, name).use { store ->
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
                store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, identity(2).captureFormat) }
                assertTrue(store.retainCapturedBaseline(baseline, AppliedCapturedCreateAttachment(receipt)))
            }
            ImportStore(context, name).use { assertEquals(baseline, it.capturedBaseline(scope, change.eventId)) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun seriesAckReservationNeverRearmsOnReopenAndKeepsTheExactVerifiedBaseline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "series-ack-fixture-${UUID.randomUUID()}.db"
        val attachment = receipt(2)
        val baseline = CapturedCreateBaseline(attachment, RemoteChange.Upsert(attachment.binding.eventId, attachment.binding.verifiedRevision,
            content.copy(recurrence = EventRecurrence("synthetic@calendeer.invalid", "FREQ=DAILY;COUNT=5"))))
        val proposed = CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 999).toString())
        try {
            ImportStore(context, name).use { store ->
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
                store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, identity(2).captureFormat) }
                assertTrue(store.retainCapturedBaseline(baseline, AppliedCapturedCreateAttachment(attachment)))
                val first = store.reserveCapturedAcknowledgement(proposed)
                assertTrue(first.fresh); assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, first.record.state)
            }
            ImportStore(context, name).use { store ->
                val reopened = store.reserveCapturedAcknowledgement(proposed.copy(nonce = UUID(0, 1000).toString()))
                assertFalse(reopened.fresh); assertEquals(proposed, reopened.record.receipt)
                assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, reopened.record.state)
                val foreign = baseline.copy(change = baseline.change.copy(content = baseline.change.content.copy(
                    recurrence = baseline.change.content.recurrence!!.copy(uid = "foreign@calendeer.invalid"))))
                try { store.reserveCapturedAcknowledgement(proposed.copy(baseline = foreign)); fail("Foreign UID baseline adopted") }
                catch (_: IllegalStateException) { }
                val proven = store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposed))
                assertEquals(CapturedCreateAcknowledgementState.PROVEN, proven.state)
            }
            ImportStore(context, name).use { store ->
                val replay = store.reserveCapturedAcknowledgement(proposed.copy(nonce = UUID(0, 1001).toString()))
                assertFalse(replay.fresh); assertEquals(proposed, replay.record.receipt)
                assertEquals(CapturedCreateAcknowledgementState.PROVEN, replay.record.state)
                assertEquals(baseline, store.capturedBaseline(scope, baseline.change.eventId))
            }
        } finally { context.deleteDatabase(name) }
    }
}

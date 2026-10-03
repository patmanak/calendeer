// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures; no Provider, account, network, or encrypted payload proof. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateBaselineStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-base-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 1)
    private val content = EventContent("Synthetic captured baseline",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails())
    private fun baseline(index: Int = 1, target: CalendarScope = scope, importFormat: AdapterFormat = format): CapturedCreateBaseline {
        val intent = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture-$index"),
            LocalEventId(UUID(0, index.toLong() * 2).toString()), target, importFormat,
            Instant.parse("2026-10-01T00:00:00Z")), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(intent,
            MutationId(UUID(0, index.toLong() * 2 + 1).toString()), AdapterFormat("synthetic-submission", 2)),
            "synthetic-opaque-payload-$index")
        val change = RemoteChange.Upsert(EventId("event-$index"), Revision("first-revision"), content)
        val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission), change.eventId, change.revision)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, UUID(0, 100L + index).toString()), change)
    }
    private val first get() = baseline()
    private fun witness(value: CapturedCreateBaseline = first) = AppliedCapturedCreateAttachment(value.receipt)
    private fun scopeKey(value: CalendarScope = scope) = JSONArray().put(value.account.value).put(value.calendar.value)
        .put(value.generation).toString()
    private fun checkpoint(importFormat: AdapterFormat = format) = JSONObject().put("adapter", importFormat.id)
        .put("version", importFormat.version).put("cursor", JSONObject.NULL).put("complete", false).toString()
    private fun encoded(change: RemoteChange) = JSONObject().apply {
        put("id", change.eventId.value); put("revision", change.revision.value); put("deleted", change is RemoteChange.Deleted)
        if (change is RemoteChange.Upsert) {
            put("title", change.content.title); put("description", change.content.description); put("location", change.content.location)
            put("details", JSONObject().put("schema", 1).put("reminders", JSONArray()).put("attendees", JSONArray()))
            val time = change.content.time as EventTime.AllDay
            put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString())
        }
    }.toString()
    private fun fixture(initialize: Boolean = true, block: suspend (Context, String, ImportStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-base-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            if (initialize) {
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
                store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
            }
            block(context, name, store)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected inactive/corrupt/colliding baseline refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private fun rawPending(store: ImportStore, event: EventId = first.change.eventId): Int? =
        store.writableDatabase.rawQuery("SELECT pending FROM records WHERE scope=? AND event=?", arrayOf(scopeKey(), event.value)).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }

    @Test fun firstRetentionIsAtomicDurableAndConsumesOnlyTheExactQueuedSnapshot() = fixture { context, name, store ->
        store.queue(first.change)
        assertTrue(store.retainCapturedBaseline(first, witness()))
        assertEquals(first.change, store.projectedBaseline(scope, first.change.eventId))
        assertEquals(first, store.capturedBaseline(scope, first.change.eventId)); assertEquals(0, rawPending(store))
        assertTrue(store.retainCapturedBaseline(first, witness()))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first, reopened.capturedBaseline(scope, first.change.eventId))
            assertTrue(reopened.retainCapturedBaseline(first, witness()))
            assertEquals(first.change, reopened.transaction(scope) { remote(first.change.eventId) })
            assertEquals(first.change, reopened.projectedBaseline(scope, first.change.eventId))
        }
    }

    @Test fun absentRemoteRecordsRemainAbsentAfterSeedingAndReplay() = fixture { _, _, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        assertNull(store.transaction(scope) { remote(first.change.eventId) }); assertNull(rawPending(store))
        assertTrue(store.retainCapturedBaseline(first, witness()))
        assertNull(store.transaction(scope) { remote(first.change.eventId) }); assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun differentRevisionContentOrDeletionRemainPendingIncludingReusedOpaqueRevision() = fixture { _, _, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        for (change in listOf(first.change.copy(revision = Revision("different-revision")),
            first.change.copy(content = content.copy(title = "Different remote title")),
            first.change.copy(content = content.copy(details = null)),
            RemoteChange.Deleted(first.change.eventId, first.change.revision))) {
            store.queue(change)
            assertTrue(store.retainCapturedBaseline(first, witness()))
            assertEquals(change, store.transaction(scope) { remote(first.change.eventId) })
            assertEquals(1, rawPending(store)); assertEquals(change, store.pending(scope).single().change)
            assertEquals(first.change, store.projectedBaseline(scope, first.change.eventId))
        }
    }

    @Test fun deferredEvidenceAllowsSeedingButNeverConsumesTheExactIncomingRecord() = fixture { _, _, store ->
        store.queue(first.change)
        val deferred = DeferredCalendarEvent(first.change.eventId, first.change.revision, format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-opaque-deferred")
        store.transaction(scope) { (this as DeltaSyncTransaction).defer(deferred) }
        assertTrue(store.retainCapturedBaseline(first, witness())); assertEquals(1, rawPending(store))
        assertEquals(deferred, store.deferred(scope).single())
        assertTrue(store.retainCapturedBaseline(first, witness())); assertEquals(1, rawPending(store))
        assertEquals(deferred, store.deferred(scope).single())
    }

    @Test fun anyPreexistingGenericBaseIsRefusedIncludingIdenticalDeletedMalformedAndForeignIdentity() = fixture { context, name, store ->
        store.queue(first.change); assertEquals(1, store.acknowledge(ProjectionIntent(scope, first.change)))
        val originalPayload = encoded(first.change)
        for (payload in listOf(originalPayload, "not-json", encoded(RemoteChange.Deleted(first.change.eventId, first.change.revision)),
            encoded(first.change.copy(eventId = EventId("foreign-event"))))) {
            raw(context, name) { it.execSQL("UPDATE projection_bases SET payload=?", arrayOf(payload)) }
            assertFalse(store.retainCapturedBaseline(first, witness()))
            assertNull(store.capturedBaseline(scope, first.change.eventId))
            store.writableDatabase.rawQuery("SELECT payload FROM projection_bases", null).use {
                assertTrue(it.moveToFirst()); assertEquals(payload, it.getString(0))
            }
        }
    }

    @Test fun rawLocalMutationAndOutstandingProjectionReceiptRefuseFirstRetention() = fixture { context, name, store ->
        store.queue(first.change)
        raw(context, name) { it.execSQL("INSERT INTO local_edits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
            scopeKey(), "synthetic-local", first.change.eventId.value, format.id, format.version,
            encoded(first.change), encoded(first.change), "PAUSED", "DELETE")) }
        assertFalse(store.retainCapturedBaseline(first, witness())); assertNull(store.capturedBaseline(scope, first.change.eventId))
        assertEquals(1, rawPending(store))
        raw(context, name) { it.execSQL("DELETE FROM local_edits") }
        val receipt = store.prepareProjection(ProjectionIntent(scope, first.change), format)
        assertFalse(store.retainCapturedBaseline(first, witness()))
        raw(context, name) { it.execSQL("UPDATE projection_receipts SET payload='malformed-payload'") }
        assertFalse(store.retainCapturedBaseline(first, witness())); assertEquals(1, rawPending(store))
        raw(context, name) { it.execSQL("UPDATE projection_receipts SET payload=?", arrayOf(encoded(receipt.change))) }
        assertEquals(receipt, store.projectionReceipt(scope, first.change.eventId))
    }

    @Test fun replayPreservesLaterLocalMutationAndUnresolvedProjectionReceiptWithoutQueueConsumption() = fixture { context, name, store ->
        store.queue(first.change); assertTrue(store.retainCapturedBaseline(first, witness()))
        val local = LocalEditIntent(MutationId("later-local-edit"), scope, format, first.change,
            content.copy(title = "Later Android edit"))
        store.captureLocalEdit(local)
        raw(context, name) { it.execSQL("UPDATE records SET pending=1") }
        assertTrue(store.retainCapturedBaseline(first, witness()))
        assertEquals(local, store.localEdit(scope, first.change.eventId)); assertEquals(1, rawPending(store))
        raw(context, name) { it.execSQL("DELETE FROM local_edits") }
        val receipt = store.prepareProjection(ProjectionIntent(scope, first.change), format)
        assertTrue(store.retainCapturedBaseline(first, witness()))
        assertEquals(receipt, store.projectionReceipt(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun differentWitnessAndProvenanceCannotAdoptOrReplaceOwnedBaseline() = fixture { _, _, store ->
        val foreignReceipt = first.receipt.copy(nonce = UUID(0, 999).toString())
        assertFalse(store.retainCapturedBaseline(first, AppliedCapturedCreateAttachment(foreignReceipt)))
        assertNull(store.capturedBaseline(scope, first.change.eventId))
        assertTrue(store.retainCapturedBaseline(first, witness()))
        val foreign = first.copy(receipt = foreignReceipt)
        assertFalse(store.retainCapturedBaseline(foreign, witness(foreign)))
        assertEquals(first, store.capturedBaseline(scope, first.change.eventId))
    }

    @Test fun orphanMalformedOrAdvancedBaseNeverAllowsReplayToRepairOrRewind() = fixture { context, name, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        for (payload in listOf("invalid-json", encoded(RemoteChange.Deleted(first.change.eventId, first.change.revision)),
            encoded(first.change.copy(content = content.copy(location = "Advanced projection"))))) {
            raw(context, name) { it.execSQL("UPDATE projection_bases SET payload=?", arrayOf(payload)) }
            assertNull(store.capturedBaseline(scope, first.change.eventId))
            assertFalse(store.retainCapturedBaseline(first, witness()))
            store.writableDatabase.rawQuery("SELECT payload FROM projection_bases", null).use {
                assertTrue(it.moveToFirst()); assertEquals(payload, it.getString(0))
            }
        }
        raw(context, name) { it.execSQL("DELETE FROM projection_bases") }
        assertNull(store.capturedBaseline(scope, first.change.eventId)); assertFalse(store.retainCapturedBaseline(first, witness()))
    }

    @Test fun corruptProvenanceFailsClosedWithoutReplacingTheRawRow() = fixture { context, name, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        val link = first.receipt.binding.identity
        for (corruption in listOf("account='foreign-account'", "capture_adapter='foreign-adapter'",
            "content_fingerprint='${"c".repeat(64)}'", "local_id='invalid-uuid'", "nonce='invalid-nonce'", "protocol=2",
            "payload='malformed-json'", "payload='${encoded(RemoteChange.Deleted(first.change.eventId, first.change.revision))}'")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captured_create_bases SET $corruption") }
            assertNull(store.capturedBaseline(scope, first.change.eventId)); assertFalse(store.retainCapturedBaseline(first, witness()))
            raw(context, name) { db -> db.execSQL("UPDATE captured_create_bases SET account=?, capture_adapter=?, content_fingerprint=?, " +
                "local_id=?, nonce=?, protocol=1, payload=?", arrayOf(scope.account.value, format.id, link.contentFingerprint,
                link.localId.value, first.receipt.nonce, encoded(first.change))) }
            assertEquals(first, store.capturedBaseline(scope, first.change.eventId))
        }
    }

    @Test fun structurallyValidNonceOrPayloadFingerprintDriftCannotAuthorizeReplayOrRepair() = fixture { context, name, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        val originalFingerprint = first.receipt.binding.identity.attempt.payloadFingerprint
        for (corruption in listOf("nonce='00000000-0000-0000-0000-000000000999'",
            "payload_fingerprint='${"d".repeat(64)}'")) {
            raw(context, name) { it.execSQL("UPDATE captured_create_bases SET $corruption") }
            val historical = checkNotNull(store.capturedBaseline(scope, first.change.eventId))
            assertNotEquals(first, historical)
            assertFalse(store.retainCapturedBaseline(first, witness()))
            assertEquals(historical, store.capturedBaseline(scope, first.change.eventId))
            raw(context, name) { it.execSQL("UPDATE captured_create_bases SET nonce=?, payload_fingerprint=?",
                arrayOf(first.receipt.nonce, originalFingerprint)) }
        }
        assertEquals(first, store.capturedBaseline(scope, first.change.eventId))
    }

    @Test fun checkpointMustExistAndMatchTheActiveExactScopeFormat() = fixture { context, name, store ->
        store.transaction(scope) { checkpoint = null }
        assertFalse(store.retainCapturedBaseline(first, witness()))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        assertFalse(store.retainCapturedBaseline(first, witness()))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        store.setEnabled(scope, false); assertFalse(store.retainCapturedBaseline(first, witness()))
        store.setEnabled(scope, true)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        raw(context, name) { it.execSQL("UPDATE calendars SET available=0") }
        assertFalse(store.retainCapturedBaseline(first, witness()))
        raw(context, name) { it.execSQL("UPDATE calendars SET available=1") }
        assertTrue(store.retainCapturedBaseline(first, witness()))
        reject { store.capturedBaseline(scope.copy(generation = 1), first.change.eventId) }
    }

    @Test fun scopedIdentityCollisionRollsBackWithoutCreatingAnotherBaseOrConsumingItsRecord() = fixture { _, _, store ->
        assertTrue(store.retainCapturedBaseline(first, witness()))
        val second = baseline(2)
        val identity = second.receipt.binding.identity.copy(localId = first.receipt.binding.identity.localId)
        val collision = second.copy(receipt = second.receipt.copy(binding = second.receipt.binding.copy(identity = identity)))
        store.queue(second.change)
        assertFalse(store.retainCapturedBaseline(collision, witness(collision)))
        assertNull(store.capturedBaseline(scope, second.change.eventId))
        assertNull(store.projectedBaseline(scope, second.change.eventId)); assertEquals(1, rawPending(store, second.change.eventId))
        assertEquals(first, store.capturedBaseline(scope, first.change.eventId))
    }

    @Test fun insertAndPendingUpdateFailuresRollBackAllProvenanceAndProjectionWrites() = fixture { context, name, store ->
        store.queue(first.change)
        for ((table, timing) in listOf("projection_bases" to "INSERT", "records" to "UPDATE")) {
            raw(context, name) { db -> db.execSQL("CREATE TRIGGER reject_baseline BEFORE $timing ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic baseline rejection'); END") }
            reject { store.retainCapturedBaseline(first, witness()) }
            assertNull(store.capturedBaseline(scope, first.change.eventId)); assertNull(store.projectedBaseline(scope, first.change.eventId))
            assertEquals(1, rawPending(store))
            raw(context, name) { it.execSQL("DROP TRIGGER reject_baseline") }
        }
        assertTrue(store.retainCapturedBaseline(first, witness()))
    }

    @Test fun leaseRevocationAndCancellationCannotCommitASeedOrConsumePending() = fixture { context, name, store ->
        store.queue(first.change)
        var checks = 0
        store.admitTransaction = { ++checks == 1 }
        reject { store.retainCapturedBaseline(first, witness()) }
        store.admitTransaction = { true }
        assertNull(store.capturedBaseline(scope, first.change.eventId)); assertEquals(1, rawPending(store))
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var calls = 0
                store.admitTransaction = { if (++calls == 2) caller.cancel(); true }
                store.retainCapturedBaseline(first, witness())
            }
            try { job.await(); fail("Cancelled seed must not commit") } catch (_: CancellationException) { }
        }
        store.close()
        ImportStore(context, name).use { reopened ->
            assertNull(reopened.capturedBaseline(scope, first.change.eventId)); assertEquals(1, rawPending(reopened))
            assertTrue(reopened.retainCapturedBaseline(first, witness()))
        }
    }

    @Test fun concurrentHelpersRetainExactlyOneOwnedBaseAndReplaysDoNotResetIt() = fixture { context, name, store ->
        store.queue(first.change)
        ImportStore(context, name).use { other ->
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val one = async(Dispatchers.IO) { start.await(); store.retainCapturedBaseline(first, witness()) }
                val two = async(Dispatchers.IO) { start.await(); other.retainCapturedBaseline(first, witness()) }
                start.complete(Unit); assertTrue(one.await()); assertTrue(two.await())
            }
            assertEquals(first, other.capturedBaseline(scope, first.change.eventId)); assertEquals(0, rawPending(other))
            store.writableDatabase.rawQuery("SELECT count(*) FROM captured_create_bases", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun independentlyConstructedVersionTenMigrationPreservesEveryExistingWorkTableWithoutProvenance() =
        fixture(initialize = false) { context, name, store ->
            val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
            val tables = listOf("calendars", "records", "inbox", "deferred_events", "delta_state", "delta_jobs",
                "delta_reconciliation", "coverage_state", "series_members", "mapping_profiles", "projection_bases", "local_edits", "projection_receipts")
            fun rows(db: SQLiteDatabase, table: String): List<List<String?>> = db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
                buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
            }
            val before = mutableMapOf<String, List<List<String?>>>()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)")
                old.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE inbox (scope TEXT PRIMARY KEY, request_checkpoint TEXT NOT NULL, request_limit INTEGER NOT NULL, payload TEXT NOT NULL, coverage TEXT)")
                old.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL, event TEXT NOT NULL, revision TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, retry_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE delta_state (scope TEXT PRIMARY KEY, cursor_adapter TEXT, cursor_version INTEGER, cursor_value TEXT, received_adapter TEXT, received_version INTEGER, received_after TEXT, received_payload TEXT, more INTEGER NOT NULL DEFAULT 0)")
                old.execSQL("CREATE TABLE delta_jobs (scope TEXT NOT NULL, notice_id TEXT NOT NULL, event TEXT, notice TEXT NOT NULL, received TEXT, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,notice_id))")
                old.execSQL("CREATE TABLE delta_reconciliation (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, after_cursor TEXT NOT NULL, payload TEXT NOT NULL)")
                old.execSQL("CREATE TABLE coverage_state (scope TEXT PRIMARY KEY, payload TEXT NOT NULL)")
                old.execSQL("CREATE TABLE series_members (scope TEXT NOT NULL, event TEXT NOT NULL, uid TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE mapping_profiles (scope TEXT PRIMARY KEY, adapter TEXT NOT NULL, version INTEGER NOT NULL, rescan_pending INTEGER NOT NULL DEFAULT 1)")
                old.execSQL("CREATE TABLE projection_bases (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE local_edits (scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'UPDATE', PRIMARY KEY(scope,mutation), UNIQUE(scope,event))")
                old.execSQL("CREATE TABLE projection_receipts (scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, previous_base TEXT, protocol INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("INSERT INTO calendars VALUES (?, ?, ?, ?, ?, NULL, 1, ?, 'BASELINE', NULL, 0, 1)", arrayOf<Any>(scopeKey(), scope.account.value, scope.calendar.value, scope.generation, "Synthetic migration", checkpoint()))
                old.execSQL("INSERT INTO records VALUES (?, ?, ?, 1)", arrayOf(scopeKey(), first.change.eventId.value, encoded(first.change)))
                old.execSQL("INSERT INTO inbox VALUES (?, ?, 4, ?, NULL)", arrayOf(scopeKey(), checkpoint(), "synthetic-sealed-page"))
                old.execSQL("INSERT INTO deferred_events VALUES (?, 'deferred-event', 'deferred-revision', ?, 1, 'UNSUPPORTED_CONTENT', 'synthetic-sealed-event', 1)", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO delta_state VALUES (?, ?, 1, 'cursor', ?, 1, 'cursor', 'synthetic-received', 1)", arrayOf(scopeKey(), format.id, format.id))
                old.execSQL("INSERT INTO delta_jobs VALUES (?, 'notice', 'job-event', 'synthetic-notice', 'synthetic-event', 1)", arrayOf(scopeKey()))
                old.execSQL("INSERT INTO delta_reconciliation VALUES (1, ?, ?, 1, 'cursor', 'synthetic-history')", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO coverage_state VALUES (?, ?)", arrayOf(scopeKey(),
                    JSONObject().put("schema", 2).put("epoch", 0).put("active", JSONObject.NULL)
                        .put("previous", JSONObject.NULL).put("observed", JSONObject.NULL).toString()))
                old.execSQL("INSERT INTO series_members VALUES (?, 'series-event', 'synthetic-series', 'master')", arrayOf(scopeKey()))
                old.execSQL("INSERT INTO mapping_profiles VALUES (?, ?, 1, 1)", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO projection_bases VALUES (?, 'generic-event', ?)", arrayOf(scopeKey(), encoded(first.change.copy(eventId = EventId("generic-event")))))
                old.execSQL("INSERT INTO local_edits VALUES (?, 'local-mutation', 'generic-event', ?, 1, ?, ?, 'CONFLICT', 'DELETE')", arrayOf(scopeKey(), format.id,
                    encoded(first.change.copy(eventId = EventId("generic-event"))), encoded(first.change.copy(eventId = EventId("generic-event")))))
                old.execSQL("INSERT INTO projection_receipts VALUES (?, 'receipt-event', ?, ?, ?, 1, NULL, 1)", arrayOf(scopeKey(), UUID(0, 500).toString(),
                    encoded(first.change.copy(eventId = EventId("receipt-event"))), format.id))
                old.version = 10
                for (table in tables) before[table] = rows(old, table)
            }
            assertEquals(12, store.writableDatabase.version)
            for (table in tables) assertEquals("Migration must preserve $table", before[table], rows(store.writableDatabase, table))
            store.writableDatabase.rawQuery("SELECT count(*) FROM captured_create_bases", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertNull(store.capturedBaseline(scope, first.change.eventId))
        }
}

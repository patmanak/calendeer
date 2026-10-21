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
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite fixtures only. No Provider, network, account or production journal access. */
@RunWith(AndroidJUnit4::class)
class ConflictArchiveStoreDeviceTest {
    private val scope = CalendarScope(AccountId("conflict-fixture-account"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("conflict-fixture", 1)
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("base"), EventContent("Base fixture",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails()))
    private val changed = base.copy(revision = Revision("remote-one"), content = base.content.copy(title = "Remote one"))
    private fun intent(event: RemoteChange.Upsert = base, target: CalendarScope = scope) = LocalEditIntent(
        MutationId("mutation-${event.eventId.value}"), target, format, event, event.content.copy(title = "Local capture"))
    private fun key(target: CalendarScope = scope) = JSONArray().put(target.account.value).put(target.calendar.value).put(target.generation).toString()
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "conflict-archive-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.setEnabled(scope, true)
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.establish(value: RemoteChange.Upsert = base, target: CalendarScope = scope) {
        transaction(target) { checkpoint = PageCheckpoint(target, format); putRemote(value); putProjection(ProjectionIntent(target, value)) }
        assertEquals(1, acknowledge(ProjectionIntent(target, value)))
    }
    private suspend fun ImportStore.receive(change: RemoteChange) = transaction(scope) { putRemote(change) }
    private suspend fun ImportStore.ref() = conflictArchives(ConflictArchiveQuery(scope)).entries.single()
    private suspend fun ImportStore.observations(archive: ConflictArchiveRef? = null) = conflictObservations(ConflictObservationQuery(archive ?: ref())).entries
    private suspend fun refused(action: suspend () -> Unit) {
        try { action(); fail("Invalid archive operation accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private fun count(db: SQLiteDatabase, table: String) = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }

    @Test fun completeVariantsAndReturnToBaseSurviveReopeningWithoutOverwritingCapture() = fixture { store, name ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed)
        val second = changed.copy(content = changed.content.copy(location = "Different content with same revision"))
        store.receive(second); store.receive(second)
        store.receive(RemoteChange.Deleted(base.eventId, Revision("deleted")))
        store.receive(base)
        val ref = store.ref()
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            val detail = checkNotNull(reopened.conflictArchive(ref))
            assertEquals(base, detail.base); assertEquals(intent().content, detail.local); assertEquals(format, detail.format)
            val variants = reopened.observations(ref)
            assertEquals(5, variants.size)
            assertEquals(listOf(base, changed, second, RemoteChange.Deleted(base.eventId, Revision("deleted")), base), variants.map { it.change })
            assertEquals(ConflictObservationOrigin.CACHED, variants.first().origin); assertNull(variants.first().observedAt)
            assertTrue(variants.drop(1).all { it.origin == ConflictObservationOrigin.RECEIVED && it.observedAt != null })
        }
    }
    @Test fun deferredEvidenceIsKeptAfterClearButNeverExposedAsVerifiedContent() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent())
        val first = DeferredCalendarEvent(base.eventId, Revision("opaque"), format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-one")
        val second = first.copy(protectedPayload = "sealed-two")
        store.transaction(scope) {
            val staged = this as StagedSyncTransaction
            staged.defer(first); staged.defer(second); staged.clearDeferred(base.eventId); putRemote(base)
        }
        val variants = store.observations()
        assertEquals(listOf(ConflictObservationKind.VERIFIED, ConflictObservationKind.DEFERRED, ConflictObservationKind.DEFERRED, ConflictObservationKind.VERIFIED), variants.map { it.kind })
        assertTrue(variants.filter { it.kind == ConflictObservationKind.DEFERRED }.all { it.change == null && it.deferredReason == first.reason })
        val retained = store.readableDatabase.rawQuery("SELECT payload FROM conflict_observations WHERE kind='DEFERRED' ORDER BY id", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        assertEquals(listOf("sealed-one", "sealed-two"), retained)
    }
    @Test fun captureAfterRemoteChangeArchivesOnlyAvailableCacheWithoutInventingHistory() = fixture { store, _ ->
        store.establish(); store.receive(changed)
        assertEquals(LocalEditState.CONFLICT, store.captureLocalEdit(intent()).state)
        val variants = store.observations(); assertEquals(1, variants.size)
        assertEquals(changed, variants.single().change); assertNull(variants.single().observedAt)
        assertEquals(ConflictObservationOrigin.CACHED, variants.single().origin)
    }
    @Test fun localDeleteAndAbsentCacheAreDistinctFromRemoteTombstone() = fixture { store, _ ->
        store.establish()
        store.writableDatabase.delete("records", "scope=? AND event=?", arrayOf(key(), base.eventId.value))
        store.captureLocalDelete(LocalDeleteIntent(MutationId("delete-intent"), scope, format, base, base.content))
        val ref = store.ref(); assertEquals(MutationKind.DELETE, ref.kind)
        assertEquals(base.content, store.conflictArchive(ref)?.local)
        assertEquals(ConflictObservationKind.UNOBSERVED, store.observations(ref).single().kind)
        store.receive(RemoteChange.Deleted(base.eventId, Revision("remote-delete")))
        assertEquals(2, store.observations(ref).size)
        assertTrue(store.observations(ref).last().change is RemoteChange.Deleted)
    }
    @Test fun metadataAndObservationsUseLookaheadAndExactScopeAndReferenceBinding() = fixture { store, _ ->
        repeat(3) { index ->
            val event = base.copy(eventId = EventId("event-$index")); store.establish(event); store.captureLocalEdit(intent(event))
            store.receive(event.copy(revision = Revision("changed-$index")))
        }
        val first = store.conflictArchives(ConflictArchiveQuery(scope, 2)); assertEquals(2, first.entries.size); assertNotNull(first.next)
        val last = store.conflictArchives(ConflictArchiveQuery(scope, 2, first.next)); assertEquals(1, last.entries.size); assertNull(last.next)
        val ref = first.entries.first()
        val page = store.conflictObservations(ConflictObservationQuery(ref, 1)); assertNotNull(page.next)
        val tail = store.conflictObservations(ConflictObservationQuery(ref, 1, page.next)); assertEquals(1, tail.entries.size); assertNull(tail.next)
        assertNull(store.conflictArchive(ref.copy(mutationId = MutationId("different"))))
        refused { store.conflictArchives(ConflictArchiveQuery(scope.copy(generation = 1))) }
        val other = scope.copy(account = AccountId("other-fixture"))
        store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other")))
        assertTrue(store.conflictArchives(ConflictArchiveQuery(other)).entries.isEmpty())
    }
    @Test fun archiveSurvivesOwnerCleanupAndDisableButExplicitScopeRemovalCascades() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed); val ref = store.ref()
        // Represents the owner cleanup already used by an acknowledgement; archive has no owner FK.
        store.writableDatabase.delete("local_edits", "scope=?", arrayOf(key()))
        store.setEnabled(scope, false); store.remember(scope.account, emptyList())
        assertEquals(base, store.conflictArchive(ref)?.base); assertEquals(2, store.observations(ref).size)
        store.writableDatabase.delete("calendars", "scope=?", arrayOf(key()))
        assertEquals(0, count(store.readableDatabase, "conflict_archives")); assertEquals(0, count(store.readableDatabase, "conflict_observations"))
    }
    @Test fun migrationFrom17PreservesLegacyBytesAndOnlyBackfillsPresentConflictCache() = fixture { store, name ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed)
        val sql = "SELECT base,local,state FROM local_edits WHERE scope=?"
        fun bytes(db: SQLiteDatabase) = db.rawQuery(sql, arrayOf(key())).use { row -> check(row.moveToFirst()); List(3) { row.getString(it) } }
        val original = bytes(store.readableDatabase)
        store.writableDatabase.apply {
            // Remove empty later tables to reconstruct the historical fixture.
            execSQL("DROP TABLE delete_acknowledgements")
            execSQL("DROP TABLE local_time_edit_preparations")
            execSQL("DROP TABLE time_update_acknowledgements")
            execSQL("DROP TABLE time_update_acknowledgement_history")
            execSQL("DROP TABLE local_text_edit_preparations")
            execSQL("DROP TABLE text_update_acknowledgements")
            execSQL("DROP TABLE text_update_acknowledgement_history")
            execSQL("DROP TRIGGER conflict_archive_scope_delete"); execSQL("DROP TABLE conflict_observations"); execSQL("DROP TABLE conflict_archives"); version = 17
        }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { migrated ->
            assertEquals(22, migrated.readableDatabase.version); assertEquals(original, bytes(migrated.readableDatabase))
            val observation = migrated.observations().single()
            assertEquals(changed, observation.change); assertEquals(ConflictObservationOrigin.CACHED, observation.origin); assertNull(observation.observedAt)
            assertEquals(base, migrated.conflictArchive(migrated.ref())?.base)
        }
    }
    @Test fun revocationRollsBackIncomingConflictArchiveAndCheckpointTogether() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent())
        var checks = 0; var inserted = false
        store.admitTransaction = {
            checks++
            if (checks == 2) { inserted = count(store.writableDatabase, "conflict_archives") == 1; false } else true
        }
        refused { store.transaction(scope) { putRemote(changed); checkpoint = PageCheckpoint(scope, format, traversalComplete = true) } }
        store.admitTransaction = { true }
        assertTrue(inserted); assertTrue(store.conflictArchives(ConflictArchiveQuery(scope)).entries.isEmpty())
        assertEquals(LocalEditState.PAUSED, store.localEdit(scope, base.eventId)?.state)
        store.transaction(scope) { assertEquals(base, remote(base.eventId)); assertFalse(checkNotNull(checkpoint).traversalComplete) }
    }
    @Test fun exhaustedBudgetRollsBackNewVariantButDoesNotPreventReadingOldProof() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed); val ref = store.ref()
        store.writableDatabase.execSQL("INSERT INTO delta_reconciliation(scope,adapter,version,after_cursor,payload) VALUES(?,?,1,'fixture',zeroblob(?))",
            arrayOf<Any?>(key(), format.id, ImportStore.STAGING_BUDGET_BYTES))
        try { store.receive(changed.copy(revision = Revision("too-large"))); fail("Budget was ignored") } catch (_: ImportCapacityException) { }
        assertEquals(base, store.conflictArchive(ref)?.base); assertEquals(2, store.observations(ref).size)
        assertEquals(1, store.conflictArchives(ConflictArchiveQuery(scope)).entries.size)
        store.readableDatabase.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?", arrayOf(key(), base.eventId.value)).use {
            assertTrue(it.moveToFirst()); assertFalse(it.getString(0).contains("too-large"))
        }
    }
    @Test fun oversizedOrMalformedArchivePayloadIsRejectedBeforeDecodingAndNeverLooksAbsent() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed); val ref = store.ref()
        store.writableDatabase.execSQL("UPDATE conflict_archives SET base=zeroblob(4194305) WHERE id=?", arrayOf(ref.id))
        refused { store.conflictArchive(ref) }
        assertEquals(1, store.conflictArchives(ConflictArchiveQuery(scope)).entries.size)
        store.writableDatabase.execSQL("UPDATE conflict_archives SET event=CAST(X'80' AS TEXT) WHERE id=?", arrayOf(ref.id))
        refused { store.conflictArchives(ConflictArchiveQuery(scope)) }
    }
    @Test fun cancellationAfterSqlMutationRollsBackAndNeverReturnsAnArchive() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent())
        supervisorScope {
            val task = async(start = CoroutineStart.LAZY) {
                val job = currentCoroutineContext().job
                var calls = 0
                store.admitTransaction = { if (++calls == 2) job.cancel(); true }
                store.receive(changed)
            }
            try { task.await(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        }
        store.admitTransaction = { true }
        assertTrue(store.conflictArchives(ConflictArchiveQuery(scope)).entries.isEmpty())
        assertEquals(LocalEditState.PAUSED, store.localEdit(scope, base.eventId)?.state)
    }
    @Test fun corruptedNumericStorageIsBoundedBeforeCursorWindowAndNeverCoerced() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed); val ref = store.ref()
        store.writableDatabase.execSQL("UPDATE conflict_archives SET version=zeroblob(20000000) WHERE id=?", arrayOf(ref.id))
        refused { store.conflictArchive(ref) }
        store.writableDatabase.execSQL("UPDATE conflict_archives SET version=1 WHERE id=?", arrayOf(ref.id))
        store.writableDatabase.execSQL("UPDATE conflict_observations SET version=CAST(zeroblob(20000000) AS TEXT) WHERE archive_id=?", arrayOf(ref.id))
        refused { store.conflictObservations(ConflictObservationQuery(ref)) }
        store.writableDatabase.execSQL("UPDATE conflict_observations SET version=NULL WHERE archive_id=?", arrayOf(ref.id))
        refused { store.conflictObservations(ConflictObservationQuery(ref)) }
        assertEquals(base, store.conflictArchive(ref)?.base)
    }
    @Test fun boundedMalformedCacheIsArchivedWithoutInventingARevisionAndSurvivesReplacement() = fixture { store, name ->
        store.establish(); store.captureLocalEdit(intent())
        val raw = "synthetic malformed cache — not an event"
        store.writableDatabase.execSQL("UPDATE records SET payload=? WHERE scope=? AND event=?", arrayOf(raw, key(), base.eventId.value))
        store.receive(changed)
        val ref = store.ref()
        val first = store.observations(ref).first()
        assertEquals(ConflictObservationKind.UNREADABLE, first.kind); assertEquals(ConflictObservationOrigin.CACHED, first.origin)
        assertNull(first.change); assertNull(first.deferredReason); assertNull(first.observedAt)
        assertEquals(changed, store.observations(ref).last().change)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(ConflictObservationKind.UNREADABLE, reopened.observations(ref).first().kind)
            reopened.readableDatabase.rawQuery("SELECT payload,revision,reason FROM conflict_observations WHERE archive_id=? AND kind='UNREADABLE'", arrayOf(ref.id.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals(raw, it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
        }
    }
    @Test fun schema18UpgradePreservesEveryEvidenceByteCursorAndAutoincrementBound() = fixture { store, name ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed)
        val ref = store.ref(); val first = store.conflictObservations(ConflictObservationQuery(ref, 1))
        val before = evidenceRows(store.readableDatabase)
        val owner = store.localEdit(scope, base.eventId)
        makeVersion18(store.writableDatabase)
        store.writableDatabase.execSQL("UPDATE sqlite_sequence SET seq=90 WHERE name='conflict_observations'")
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { migrated ->
            assertEquals(22, migrated.readableDatabase.version); assertEquals(before, evidenceRows(migrated.readableDatabase))
            assertEquals(owner, migrated.localEdit(scope, base.eventId)); assertEquals(ref, migrated.ref())
            val tail = migrated.conflictObservations(ConflictObservationQuery(ref, 1, first.next))
            assertEquals(changed, tail.entries.single().change); assertNull(tail.next)
            migrated.receive(changed); assertEquals(before, evidenceRows(migrated.readableDatabase))
            migrated.receive(base)
            assertEquals(91L, migrated.observations(ref).last().id)
            migrated.readableDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('conflict_observation_archive','conflict_archive_scope_delete')", null).use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
    }
    @Test fun schema18ConstraintFailureRollsBackWholeMigrationWithoutDroppingOriginalEvidence() = fixture { store, name ->
        store.establish(); store.captureLocalEdit(intent()); store.receive(changed)
        makeVersion18(store.writableDatabase)
        store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        store.writableDatabase.execSQL("UPDATE conflict_observations SET kind='CORRUPTED' WHERE id=1")
        store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=OFF")
        val before = evidenceRows(store.readableDatabase)
        store.close()
        try {
            ImportStore(ApplicationProvider.getApplicationContext(), name).use { it.readableDatabase }
            fail("Invalid legacy observation admitted")
        } catch (_: SQLiteException) { }
        val context = ApplicationProvider.getApplicationContext<Context>()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(18, raw.version); assertEquals(before, evidenceRows(raw))
            raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='conflict_observations_v18'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            raw.execSQL("UPDATE conflict_observations SET kind='VERIFIED' WHERE id=1")
        }
        ImportStore(context, name).use { assertEquals(22, it.readableDatabase.version) }
    }
    private fun evidenceRows(db: SQLiteDatabase): List<List<String?>> = db.rawQuery("SELECT * FROM conflict_observations ORDER BY id", null).use { row ->
        buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
    }
    /** Construct the exact version-18 observation constraint; retain all other version-18 tables. */
    private fun makeVersion18(db: SQLiteDatabase) {
        // Remove empty later tables to reconstruct the historical fixture.
        db.execSQL("DROP TABLE delete_acknowledgements")
        db.execSQL("DROP TABLE local_time_edit_preparations")
        db.execSQL("DROP TABLE time_update_acknowledgements")
        db.execSQL("DROP TABLE time_update_acknowledgement_history")
        db.execSQL("DROP TABLE local_text_edit_preparations")
        db.execSQL("DROP TABLE text_update_acknowledgements")
        db.execSQL("DROP TABLE text_update_acknowledgement_history")
        db.execSQL("DROP TRIGGER conflict_archive_scope_delete")
        db.execSQL("DROP INDEX conflict_observation_archive")
        db.execSQL("ALTER TABLE conflict_observations RENAME TO fixture_new_observations")
        db.execSQL("""CREATE TABLE conflict_observations (
            id INTEGER PRIMARY KEY AUTOINCREMENT, archive_id INTEGER NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('VERIFIED','DEFERRED','UNOBSERVED')),
            origin TEXT NOT NULL CHECK(origin IN ('CACHED','RECEIVED')), observed_at TEXT,
            adapter TEXT, version INTEGER, revision TEXT, reason TEXT, payload TEXT
        )""".trimIndent())
        db.execSQL("INSERT INTO conflict_observations SELECT * FROM fixture_new_observations")
        db.execSQL("DROP TABLE fixture_new_observations")
        db.execSQL("CREATE INDEX conflict_observation_archive ON conflict_observations(archive_id,id)")
        db.execSQL("""CREATE TRIGGER conflict_archive_scope_delete AFTER DELETE ON calendars BEGIN
            DELETE FROM conflict_observations WHERE archive_id IN (SELECT id FROM conflict_archives WHERE scope=OLD.scope);
            DELETE FROM conflict_archives WHERE scope=OLD.scope;
        END""".trimIndent())
        db.version = 18
    }
}

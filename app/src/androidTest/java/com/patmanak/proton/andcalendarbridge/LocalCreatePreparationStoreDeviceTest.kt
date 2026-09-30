// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated journal fixtures. Synthetic opaque strings are not evidence of encryption or dispatch. */
@RunWith(AndroidJUnit4::class)
class LocalCreatePreparationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("preparation-fixture"), CalendarId("calendar"), 0)
    private val reservation = LocalCreateReservation(MutationId("fixture-create"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), scope, AdapterFormat("local-capture-fixture", 1),
        Instant.parse("2026-10-01T12:00:00Z"))
    private val content = EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
        description = "Synthetic notes", location = "Synthetic location", details = EventDetails())
    private val intent = LocalCreateIntent(reservation, content)
    private val preparation = LocalCreatePreparation(intent, AdapterFormat("sealed-preparation-fixture", 1), "synthetic-opaque-first-payload")
    private fun fixture(block: suspend (Context, String, LocalCreateStore) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "local-preparation-${UUID.randomUUID()}.db"
        val store = LocalCreateStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected foreign, immutable or inactive preparation refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }
    private suspend fun LocalCreateStore.capture() { assertTrue(reserve(reservation)); assertEquals(intent, retain(intent)) }
    private fun contentPayload(store: LocalCreateStore) = store.readableDatabase.rawQuery("SELECT content FROM local_creates", null).use {
        assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getString(0)
    }
    private fun assertNoPreparationColumns(store: LocalCreateStore) = store.readableDatabase.rawQuery(
        "SELECT prep_adapter,prep_version,prep_payload FROM local_creates", null).use {
        assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
    }

    @Test fun missingOrOnlyReservedWorkCannotRetainPreparationImplicitly() = fixture { _, _, store ->
        assertNull(store.readPreparation(scope, reservation.localId)); refused { store.retainPreparation(preparation) }
        assertNull(store.readReservation(scope, reservation.localId))
        store.reserve(reservation)
        assertNull(store.readPreparation(scope, reservation.localId)); refused { store.retainPreparation(preparation) }
        assertNull(store.readIntent(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        assertNoPreparationColumns(store)
        store.retain(intent); assertEquals(preparation, store.retainPreparation(preparation))
    }
    @Test fun firstPreparationIsImmutableAndDoesNotChangeCaptureContentOrReservation() = fixture { _, _, store ->
        store.capture(); val original = contentPayload(store)
        assertEquals(preparation, store.retainPreparation(preparation)); assertEquals(preparation, store.retainPreparation(preparation))
        val randomizedRetry = preparation.copy(protectedPayload = "synthetic-randomized-${UUID.randomUUID()}")
        refused { store.retainPreparation(randomizedRetry) }
        refused { store.retainPreparation(preparation.copy(format = preparation.format.copy(version = 2))) }
        refused { store.retainPreparation(preparation.copy(format = AdapterFormat("other-profile", 1))) }
        assertEquals(preparation, store.readPreparation(scope, reservation.localId)); assertEquals(original, contentPayload(store))
        assertEquals(intent, store.readIntent(scope, reservation.localId)); assertEquals(reservation, store.readReservation(scope, reservation.localId))
        assertFalse(store.reserve(reservation)); assertEquals(intent, store.retain(intent))
    }
    @Test fun preparationReopenRetainsFirstSealedPayloadAndNeverReopensStampPermission() = fixture { context, name, store ->
        store.capture(); store.retainPreparation(preparation); store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(3, reopened.readableDatabase.version)
            assertEquals(preparation, reopened.readPreparation(scope, reservation.localId)); assertFalse(reopened.reserve(reservation))
            assertEquals(preparation, reopened.retainPreparation(preparation)); assertEquals(intent, reopened.readIntent(scope, reservation.localId))
        }
    }
    @Test fun foreignIdentityOrDifferentCapturedContentCannotAcquirePreparation() = fixture { _, _, store ->
        store.capture()
        val foreign = listOf(intent.copy(content = content.copy(title = "Different snapshot")),
            intent.copy(reservation = reservation.copy(id = MutationId("other"))),
            intent.copy(reservation = reservation.copy(localId = LocalEventId(UUID.randomUUID().toString()))),
            intent.copy(reservation = reservation.copy(timestamp = reservation.timestamp.plusSeconds(1))),
            intent.copy(reservation = reservation.copy(format = reservation.format.copy(version = 2))),
            intent.copy(reservation = reservation.copy(scope = scope.copy(account = AccountId("foreign")))),
            intent.copy(reservation = reservation.copy(scope = scope.copy(calendar = CalendarId("foreign")))),
            intent.copy(reservation = reservation.copy(scope = scope.copy(generation = 1))))
        for (candidate in foreign) refused { store.retainPreparation(preparation.copy(intent = candidate)) }
        assertNull(store.readPreparation(scope, reservation.localId)); assertEquals(intent, store.readIntent(scope, reservation.localId))
        assertEquals(preparation, store.retainPreparation(preparation))
    }
    @Test fun sameOpaquePreparationFromTwoHelpersCommitsOnceAndBothReplaysAreIdempotent() = fixture { context, name, store ->
        store.capture()
        store.writableDatabase.execSQL("CREATE TABLE fixture_updates (value INTEGER NOT NULL)")
        store.writableDatabase.execSQL("CREATE TRIGGER count_prep AFTER UPDATE OF prep_payload ON local_creates BEGIN INSERT INTO fixture_updates VALUES(1); END")
        LocalCreateStore(context, name).use { other ->
            assertEquals(intent, other.readIntent(scope, reservation.localId))
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); store.retainPreparation(preparation) }
                val second = async(Dispatchers.IO) { start.await(); other.retainPreparation(preparation) }
                start.complete(Unit)
                assertEquals(listOf(preparation, preparation), listOf(first.await(), second.await()))
            }
            assertEquals(preparation, other.readPreparation(scope, reservation.localId))
        }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM fixture_updates", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
    }
    @Test fun competingRandomizedPreparationsRetainExactlyOneFirstPayload() = fixture { context, name, store ->
        store.capture()
        val another = preparation.copy(protectedPayload = "synthetic-other-${UUID.randomUUID()}")
        LocalCreateStore(context, name).use { other ->
            assertEquals(intent, other.readIntent(scope, reservation.localId))
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); runCatching { store.retainPreparation(preparation) } }
                val second = async(Dispatchers.IO) { start.await(); runCatching { other.retainPreparation(another) } }
                start.complete(Unit)
                val results = listOf(first.await(), second.await())
                assertEquals(1, results.count { it.isSuccess })
                val retained = results.single { it.isSuccess }.getOrThrow()
                assertEquals(retained, store.readPreparation(scope, reservation.localId)); assertEquals(retained, other.readPreparation(scope, reservation.localId))
            }
        }
        assertEquals(intent, store.readIntent(scope, reservation.localId)); assertFalse(store.reserve(reservation))
    }
    @Test fun futureOpaqueAdapterProfileIsPreservedWithoutReinterpretationOrReplacement() = fixture { _, _, store ->
        store.capture()
        val future = preparation.copy(format = AdapterFormat("future-sealed-fixture", 73))
        assertEquals(future, store.retainPreparation(future)); assertEquals(future, store.readPreparation(scope, reservation.localId))
        refused { store.retainPreparation(preparation) }
        assertEquals(future, store.readPreparation(scope, reservation.localId)); assertFalse(store.reserve(reservation))
    }
    @Test fun revokedLeaseRollsBackPreparationAndReadGatesRefuseAfterScopeChange() = fixture { _, _, store ->
        store.capture(); val original = contentPayload(store)
        var calls = 0; store.admitCapture = { ++calls == 1 }
        refused { store.retainPreparation(preparation) }
        store.admitCapture = { true }; assertNull(store.readPreparation(scope, reservation.localId)); assertNoPreparationColumns(store)
        assertEquals(original, contentPayload(store)); assertFalse(store.reserve(reservation))
        store.retainPreparation(preparation)
        store.admitCapture = { false }; refused { store.readPreparation(scope, reservation.localId) }; refused { store.retainPreparation(preparation) }
        calls = 0; store.admitCapture = { ++calls == 1 }; refused { store.readPreparation(scope, reservation.localId) }
        store.admitCapture = { true }; assertEquals(preparation, store.readPreparation(scope, reservation.localId))
    }
    @Test fun cancellationBeforeCommitRollsBackAllPreparationFieldsOnly() = fixture { _, _, store ->
        store.capture(); val original = contentPayload(store)
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitCapture = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.retainPreparation(preparation) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitCapture = { true } }
        assertNull(store.readPreparation(scope, reservation.localId)); assertNoPreparationColumns(store)
        assertEquals(original, contentPayload(store)); assertEquals(intent, store.readIntent(scope, reservation.localId))
        assertFalse(store.reserve(reservation)); assertEquals(preparation, store.retainPreparation(preparation))
    }
    @Test fun failedPreparationWritePreservesFirstCapturedIntentAndAllowsOnlyExplicitRetentionRetry() = fixture { _, _, store ->
        store.capture()
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_prep BEFORE UPDATE OF prep_payload ON local_creates BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.retainPreparation(preparation) }
        assertNoPreparationColumns(store); assertEquals(intent, store.readIntent(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        store.writableDatabase.execSQL("DROP TRIGGER refuse_prep")
        assertEquals(preparation, store.retainPreparation(preparation))
    }
    @Test fun freshSchemaRejectsPartialPreparationAndNonpositiveVersion() = fixture { _, _, store ->
        store.capture()
        for (bits in 1..6) {
            val partial = ContentValues().apply {
                if (bits and 1 != 0) put("prep_adapter", preparation.format.id) else putNull("prep_adapter")
                if (bits and 2 != 0) put("prep_version", preparation.format.version) else putNull("prep_version")
                if (bits and 4 != 0) put("prep_payload", preparation.protectedPayload) else putNull("prep_payload")
            }
            refused { store.writableDatabase.update("local_creates", partial, null, null) }
            assertNoPreparationColumns(store)
        }
        refused { store.writableDatabase.update("local_creates", ContentValues().apply {
            put("prep_adapter", preparation.format.id); put("prep_version", 0); put("prep_payload", preparation.protectedPayload)
        }, null, null) }
        assertNoPreparationColumns(store)
    }
    @Test fun partialCorruptionIsRefusedByAllReadersWithoutReservationFallback() = fixture { _, _, store ->
        store.capture()
        store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        try {
            store.writableDatabase.execSQL("UPDATE local_creates SET prep_adapter='synthetic-partial'")
            refused { store.readReservation(scope, reservation.localId) }; refused { store.readIntent(scope, reservation.localId) }
            refused { store.readPreparation(scope, reservation.localId) }; refused { store.reserve(reservation) }
            refused { store.retain(intent) }; refused { store.retainPreparation(preparation) }
        } finally {
            store.writableDatabase.execSQL("UPDATE local_creates SET prep_adapter=NULL,prep_version=NULL,prep_payload=NULL")
            store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=OFF")
        }
        assertFalse(store.reserve(reservation)); assertEquals(intent, store.readIntent(scope, reservation.localId))
    }
    @Test fun corruptedOrUncapturedContentCannotGainOrReturnPreparation() = fixture { _, _, store ->
        store.capture()
        val original = contentPayload(store)
        val corrupted = JSONObject(original).apply {
            getJSONObject("reservation").put("localId", UUID.randomUUID().toString())
        }.toString()
        store.writableDatabase.update("local_creates", ContentValues().apply { put("content", corrupted) }, null, null)
        refused { store.retainPreparation(preparation) }
        assertNoPreparationColumns(store)
        store.writableDatabase.update("local_creates", ContentValues().apply {
            put("content", original); put("prep_adapter", preparation.format.id); put("prep_version", preparation.format.version); put("prep_payload", "   ")
        }, null, null)
        refused { store.readPreparation(scope, reservation.localId) }; refused { store.readIntent(scope, reservation.localId) }
        store.writableDatabase.update("local_creates", ContentValues().apply { put("prep_payload", preparation.protectedPayload); putNull("content") }, null, null)
        refused { store.readPreparation(scope, reservation.localId) }; refused { store.readReservation(scope, reservation.localId) }
    }

    @Test fun independentlyConstructedSchemaOneMigrationPreservesReservationsAndRawCaptureWithoutCreatingPreparation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "local-prep-migration-${UUID.randomUUID()}.db"
        val uncaptured = reservation.copy(id = MutationId("uncaptured"), localId = LocalEventId("11111111-2222-3333-4444-555555555555"))
        val capturedPayload = JSONObject().apply {
            put("schema", 1); put("knownEmptyDetails", true)
            put("reservation", JSONObject().apply {
                put("id", reservation.id.value); put("localId", reservation.localId.value); put("account", scope.account.value)
                put("calendar", scope.calendar.value); put("generation", scope.generation); put("adapter", reservation.format.id)
                put("version", reservation.format.version); put("timestamp", reservation.timestamp.toString()); put("markerProtocol", 1)
            })
            put("title", content.title); put("description", content.description); put("location", content.location)
            put("allDay", true); put("start", "2026-10-01"); put("end", "2026-10-02")
        }.toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("CREATE TABLE local_creates(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),local_event TEXT NOT NULL,mutation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,marker_protocol INTEGER NOT NULL CHECK(marker_protocol=1),content TEXT,PRIMARY KEY(account,calendar,generation,local_event),UNIQUE(account,calendar,generation,mutation))")
                for (record in listOf(reservation, uncaptured)) db.insertOrThrow("local_creates", null, ContentValues().apply {
                    put("account", record.scope.account.value); put("calendar", record.scope.calendar.value); put("generation", record.scope.generation)
                    put("local_event", record.localId.value); put("mutation", record.id.value); put("adapter", record.format.id); put("version", record.format.version)
                    put("created_at", record.timestamp.toString()); put("marker_protocol", record.markerProtocol)
                    if (record == reservation) put("content", capturedPayload) else putNull("content")
                })
                db.version = 1
            }
            LocalCreateStore(context, name).use { store ->
                assertEquals(3, store.readableDatabase.version)
                assertEquals(reservation, store.readReservation(scope, reservation.localId)); assertEquals(uncaptured, store.readReservation(scope, uncaptured.localId))
                assertEquals(intent, store.readIntent(scope, reservation.localId)); assertNull(store.readIntent(scope, uncaptured.localId))
                assertFalse(store.reserve(reservation)); assertFalse(store.reserve(uncaptured))
                assertNull(store.readPreparation(scope, reservation.localId)); assertNull(store.readPreparation(scope, uncaptured.localId))
                store.readableDatabase.rawQuery("SELECT content FROM local_creates WHERE local_event=?", arrayOf(reservation.localId.value)).use {
                    assertTrue(it.moveToFirst()); assertEquals(capturedPayload, it.getString(0))
                }
                refused { store.writableDatabase.execSQL("UPDATE local_creates SET prep_adapter='synthetic-partial'") }
                refused { store.writableDatabase.update("local_creates", ContentValues().apply {
                    put("prep_adapter", preparation.format.id); put("prep_version", 0); put("prep_payload", preparation.protectedPayload)
                }, null, null) }
                assertEquals(preparation, store.retainPreparation(preparation)); assertEquals(preparation, store.readPreparation(scope, reservation.localId))
                refused { store.retainPreparation(preparation.copy(intent = LocalCreateIntent(uncaptured, content))) }
            }
        } finally { context.deleteDatabase(name) }
    }
}

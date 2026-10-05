// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** SQL qualification with synthetic witnesses only; no Provider success or service writes. */
@RunWith(AndroidJUnit4::class)
class ProjectionRepresentationStoreDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CalendarScope(AccountId("representation-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("representation-fixture", 1)
    private val original = RemoteChange.Upsert(EventId("fixture-event"), Revision("original"), EventContent("Original",
        EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 27))))
    private val finite = original.copy(revision = Revision("finite"), content = EventContent("Finite fixture",
        EventTime.Timed(Instant.parse("2030-10-26T00:30:00Z"), Instant.parse("2030-10-26T02:30:00Z"), ZoneId.of("Europe/Paris")),
        recurrence = EventRecurrence("fixture-series", "FREQ=DAILY;COUNT=3")))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val name = "projection-representation-${UUID.randomUUID()}.db"; val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture"))); store.setEnabled(scope, true)
            store.queue(original); store.acknowledge(ProjectionIntent(scope, original))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) { putRemote(change); putProjection(ProjectionIntent(scope, change)) }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected representation refusal") }
        catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    private fun metadata(store: ImportStore, table: String): String? = store.readableDatabase.rawQuery(
        "SELECT representation FROM $table WHERE event=?", arrayOf(original.eventId.value)).use { check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
    private fun mutate(store: ImportStore, table: String, value: String?) {
        assertEquals(1, store.writableDatabase.update(table, ContentValues().apply { put("representation", value) }, "event=?", arrayOf(original.eventId.value)))
    }

    @Test fun savedFiniteReferenceSurvivesReopenAndAcknowledgementKeepsNewerPendingCanonicalContent() = fixture { store, name ->
        val calculated = ProjectedEventReference.finiteDaily(finite)
        val dates = calculated.dates.toMutableList().apply { this[1] = this[1].plusSeconds(3600) }
        val reference = ProjectedEventReference.restore(finite, ProjectionRepresentation.FINITE_DAILY_V1, dates)
        store.queue(finite); val receipt = store.prepareProjection(ProjectionIntent(scope, finite), format, reference)
        assertEquals(original, receipt.previousBase); assertNull(receipt.previousRepresentation)
        val payload = metadata(store, "projection_receipts")!!
        val newer = finite.copy(revision = Revision("newer"), content = finite.content.copy(title = "Newer fixture"))
        store.queue(newer); store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(receipt, reopened.projectionReceipt(scope, finite.eventId))
            assertEquals(payload, metadata(reopened, "projection_receipts"))
            assertTrue(reopened.restoreProjection(AppliedProjectionWitness(receipt)))
            assertEquals(finite, reopened.projectedBaseline(scope, finite.eventId))
            assertEquals(reference, reopened.projectedReference(scope, finite.eventId))
            assertEquals(newer, reopened.pending(scope).single().change)
            assertNull(reopened.projectionReceipt(scope, finite.eventId)); assertEquals(payload, metadata(reopened, "projection_bases"))
        }
        ImportStore(context, name).use { reopened -> assertEquals(reference, reopened.projectedReference(scope, finite.eventId)) }
    }

    @Test fun completePreviousRepresentationAndExactPreparedMetadataControlRestoration() = fixture { store, _ ->
        val first = ProjectedEventReference.finiteDaily(finite)
        store.queue(finite); val initial = store.prepareProjection(ProjectionIntent(scope, finite), format, first)
        assertTrue(store.restoreProjection(AppliedProjectionWitness(initial)))
        store.queue(finite)
        val direct = ProjectedEventReference.direct(finite)
        val receipt = store.prepareProjection(ProjectionIntent(scope, finite), format, direct)
        assertEquals(first, receipt.previousRepresentation)
        val raw = metadata(store, "projection_bases")
        mutate(store, "projection_bases", null)
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(receipt, store.projectionReceipt(scope, finite.eventId)); assertEquals(finite, store.pending(scope).single().change)
        mutate(store, "projection_bases", raw)
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt.copy(representation = first))))
        assertTrue(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(direct, store.projectedReference(scope, finite.eventId))
    }

    @Test fun finiteToLegacyDirectRestorationClearsTheOldMetadataWithoutChangingCanonicalPayload() = fixture { store, _ ->
        store.queue(finite); val represented = store.prepareProjection(ProjectionIntent(scope, finite), format, ProjectedEventReference.finiteDaily(finite))
        assertTrue(store.restoreProjection(AppliedProjectionWitness(represented)))
        store.queue(finite); val direct = store.prepareProjection(ProjectionIntent(scope, finite), format)
        assertNotNull(direct.previousRepresentation); assertNull(direct.representation)
        assertTrue(store.restoreProjection(AppliedProjectionWitness(direct)))
        assertNull(metadata(store, "projection_bases")); assertEquals(ProjectedEventReference.direct(finite), store.projectedReference(scope, finite.eventId))
    }

    @Test fun unknownAndMalformedMetadataBlockReadsPreparationAndUntrackedAcknowledgementWithoutErasure() = fixture { store, _ ->
        store.queue(finite); val receipt = store.prepareProjection(ProjectionIntent(scope, finite), format, ProjectedEventReference.finiteDaily(finite))
        val valid = metadata(store, "projection_receipts")!!
        val candidates = listOf("{", valid.replace("finite-daily-dates", "future-profile"), valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""), JSONObject(valid).put("extra", 1).toString(),
            valid.replace("2030-10-27T00:30:00Z", "2030-10-27T00:30:00.001Z"), " ".repeat(4097))
        for (bad in candidates) {
            mutate(store, "projection_receipts", bad)
            refused { store.projectionReceipt(scope, finite.eventId) }
            refused { store.restoreProjection(AppliedProjectionWitness(receipt)) }
            refused { store.prepareProjection(ProjectionIntent(scope, finite), format) }
            refused { store.acknowledge(ProjectionIntent(scope, finite)) }
            assertEquals(bad, metadata(store, "projection_receipts")); assertEquals(original, store.projectedBaseline(scope, finite.eventId))
            assertEquals(finite, store.pending(scope).single().change)
        }
        mutate(store, "projection_receipts", valid)
        refused { store.acknowledge(ProjectionIntent(scope, finite)) }
        mutate(store, "projection_bases", "{\"id\":\"future\",\"version\":1,\"dates\":[]}")
        refused { store.projectedBaseline(scope, finite.eventId) }; refused { store.projectedReference(scope, finite.eventId) }
        refused { store.restoreProjection(AppliedProjectionWitness(receipt)) }
        assertNotNull(metadata(store, "projection_bases")); assertEquals(valid, metadata(store, "projection_receipts"))
    }

    @Test fun schemaFourteenMigrationPreservesLegacyPayloadsNoncesAndDirectRecovery() = fixture { store, name ->
        val next = original.copy(revision = Revision("next")); store.queue(next)
        val receipt = store.prepareProjection(ProjectionIntent(scope, next), format)
        val basePayload = store.readableDatabase.rawQuery("SELECT payload FROM projection_bases", null).use { it.moveToFirst(); it.getString(0) }
        val receiptPayload = store.readableDatabase.rawQuery("SELECT payload FROM projection_receipts", null).use { it.moveToFirst(); it.getString(0) }
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE projection_bases RENAME TO old_bases")
            db.execSQL("CREATE TABLE projection_bases(scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,event))")
            db.execSQL("INSERT INTO projection_bases SELECT scope,event,payload FROM old_bases"); db.execSQL("DROP TABLE old_bases")
            db.execSQL("ALTER TABLE projection_receipts RENAME TO old_receipts")
            db.execSQL("CREATE TABLE projection_receipts(scope TEXT NOT NULL,event TEXT NOT NULL,nonce TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,previous_base TEXT,protocol INTEGER NOT NULL CHECK(protocol=1),PRIMARY KEY(scope,event))")
            db.execSQL("INSERT INTO projection_receipts SELECT scope,event,nonce,payload,adapter,version,previous_base,protocol FROM old_receipts")
            db.execSQL("DROP TABLE old_receipts")
            db.execSQL("DROP TABLE title_update_acknowledgement_history")
            db.execSQL("DROP TABLE title_update_acknowledgements")
            db.version = 14
        }
        ImportStore(context, name).use { reopened ->
            assertEquals(17, reopened.readableDatabase.version); assertEquals(receipt, reopened.projectionReceipt(scope, next.eventId))
            assertEquals(basePayload, reopened.readableDatabase.rawQuery("SELECT payload FROM projection_bases", null).use { it.moveToFirst(); it.getString(0) })
            assertEquals(receiptPayload, reopened.readableDatabase.rawQuery("SELECT payload FROM projection_receipts", null).use { it.moveToFirst(); it.getString(0) })
            assertTrue(reopened.restoreProjection(AppliedProjectionWitness(receipt)))
            assertEquals(ProjectedEventReference.direct(next), reopened.projectedReference(scope, next.eventId))
        }
    }

    @Test fun nativeMapperRefusesCurrentOrPreviousFiniteMetadataBeforeProviderAccess() = fixture { store, _ ->
        val reference = ProjectedEventReference.finiteDaily(finite)
        store.queue(finite); val receipt = store.prepareProjection(ProjectionIntent(scope, finite), format, reference)
        val native = AndroidCalendarProjection(context)
        val previousOnly = receipt.copy(representation = null, previousBase = finite, previousRepresentation = reference)
        for (candidate in listOf(receipt, previousOnly)) for (inspect in listOf(false, true)) {
            try {
                if (inspect) native.inspectProjectionReceipt(candidate) else native.applyTracked(candidate)
                fail("Finite mapper integration is not enabled")
            } catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
        }
        assertEquals(receipt, store.projectionReceipt(scope, finite.eventId)); assertEquals(original, store.projectedBaseline(scope, finite.eventId))
    }
}

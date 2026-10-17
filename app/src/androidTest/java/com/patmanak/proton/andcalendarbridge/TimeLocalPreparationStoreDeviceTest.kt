// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTimePreparationAdmission
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, isolated SQLite fixtures. No Provider, account, service or transport. */
class TimeLocalPreparationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("time-preparation-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-time-import", 1)
    private val originalTime = EventTime.Timed(Instant.parse("2030-03-30T10:00:00Z"),
        Instant.parse("2030-03-30T11:00:00Z"), ZoneId.of("Europe/Paris"))
    private val replacementTime = originalTime.copy(start = Instant.parse("2030-03-31T10:00:00Z"),
        end = Instant.parse("2030-03-31T12:00:00Z"))
    private val base = RemoteChange.Upsert(EventId("event"), Revision("original"),
        EventContent("Unchanged\r\ntitle", originalTime, "Unchanged\r\ndescription", "Unchanged\r\nplace", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("local-time-edit"), scope, format, base,
        base.content.copy(time = replacementTime))
    private fun preparation(value: LocalEditIntent = intent, timestamp: Instant = Instant.parse("2030-03-01T12:00:00Z"),
        format: AdapterFormat = AdapterFormat("synthetic-time-preparation", 1), payload: String = "opaque-time-preparation") =
        LocalTimeEditPreparation(value, timestamp, format, payload)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(original: RemoteChange.Upsert = base, block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "time-preparation-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic time preparation")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(original); putProjection(ProjectionIntent(scope, original)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, original)))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun assertPreparation(expected: LocalTimeEditPreparation, actual: LocalTimeEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected closed time preparation refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun exactTimedScopeOriginalReplacementAndRawTextSurviveReopen() = fixture { store, name ->
        assertEquals(intent, store.captureLocalEdit(intent))
        assertEquals(LocalTimePreparationAdmission.ELIGIBLE, store.assessLocalTimePreparation(intent))
        val value = preparation()
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(value))
        assertPreparation(value, store.localTimeEditPreparation(scope, base.eventId))
        assertEquals(intent, store.localEdit(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertPreparation(value, reopened.localTimeEditPreparation(scope, base.eventId))
            assertEquals(LocalTimePreparationAdmission.HISTORICAL, reopened.retainCurrentLocalTimePreparation(value))
            assertEquals(replacementTime, reopened.localTimeEditPreparation(scope, base.eventId)!!.intent.content.time)
        }
    }

    @Test fun allDayExclusiveDatesRemainExactAcrossYearBoundary() {
        val original = base.copy(content = base.content.copy(time = EventTime.AllDay(LocalDate.of(2030, 12, 30), LocalDate.of(2031, 1, 2))))
        fixture(original) { store, name ->
            val local = intent.copy(base = original, content = original.content.copy(
                time = EventTime.AllDay(LocalDate.of(2031, 1, 1), LocalDate.of(2031, 1, 5))))
            assertEquals(local, store.captureLocalEdit(local)); val value = preparation(local)
            assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(value))
            store.close(); ImportStore(context, name).use { assertPreparation(value, it.localTimeEditPreparation(scope, base.eventId)) }
        }
    }

    @Test fun firstCapsuleCannotChangeAndFullOwnerAndCheckpointMustMatch() = fixture { store, _ ->
        assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.assessLocalTimePreparation(intent))
        assertEquals(intent, store.captureLocalEdit(intent)); val first = preparation()
        for (other in listOf(intent.copy(id = MutationId("other")), intent.copy(format = format.copy(version = 2)),
            intent.copy(content = intent.content.copy(time = replacementTime.copy(end = replacementTime.end.plusSeconds(1)))),
            intent.copy(base = base.copy(revision = Revision("different-base"))))) {
            assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTimePreparation(preparation(other)))
        }
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(first))
        for (other in listOf(preparation(timestamp = first.timestamp.plusSeconds(1)),
            preparation(format = first.format.copy(version = 2)), preparation(payload = "different-capsule"))) {
            assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTimePreparation(other))
            assertPreparation(first, store.localTimeEditPreparation(scope, base.eventId))
        }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.assessLocalTimePreparation(intent))
        assertPreparation(first, store.localTimeEditPreparation(scope, base.eventId))
    }

    @Test fun scopeAdmissionConflictAndPausePreserveHistoricalCapsule() = fixture { store, name ->
        assertEquals(intent, store.captureLocalEdit(intent)); val value = preparation()
        for (other in listOf(scope.copy(generation = 1), scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign"))))
            reject { store.retainCurrentLocalTimePreparation(preparation(intent.copy(scope = other))) }
        store.setEnabled(scope, false)
        assertEquals(LocalTimePreparationAdmission.INACTIVE, store.retainCurrentLocalTimePreparation(value))
        store.setEnabled(scope, true)
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(value))
        assertEquals(LocalTimePreparationAdmission.CONFLICT, store.conflictLocalTimePreparation(intent))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertEquals(LocalTimePreparationAdmission.CONFLICT, store.assessLocalTimePreparation(intent))
        store.setEnabled(scope, false); store.remember(scope.account, emptyList()); store.close()
        ImportStore(context, name).use { assertPreparation(value, it.localTimeEditPreparation(scope, base.eventId)) }
    }

    @Test fun unsupportedCombinedKindZoneAndUnknownFieldsStayHeldWithoutPreparation() {
        val originals = listOf(base, base.copy(content = base.content.copy(details = null)),
            base.copy(content = base.content.copy(colorRgb = 0x123456)))
        for (original in originals) {
            val candidates = if (original != base) listOf(original.content.copy(time = replacementTime)) else listOf(
                intent.content.copy(title = "Combined title"), intent.content.copy(description = "Combined description"),
                intent.content.copy(location = "Combined location"),
                intent.content.copy(time = replacementTime.copy(zone = ZoneId.of("UTC"))),
                intent.content.copy(time = EventTime.AllDay(LocalDate.of(2030, 3, 31), LocalDate.of(2030, 4, 1))))
            for (content in candidates) fixture(original) { store, _ ->
                val observed = intent.copy(base = original, content = content)
                assertEquals(observed, store.captureLocalEdit(observed))
                assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.assessLocalTimePreparation(observed))
                assertNull(store.localTimeEditPreparation(scope, base.eventId))
                assertNotNull(store.localEdit(scope, base.eventId))
                assertEquals(original, store.projectedBaseline(scope, base.eventId))
            }
        }
        val recurring = base.copy(content = base.content.copy(recurrence = EventRecurrence("synthetic-series", rule = "FREQ=DAILY;COUNT=2")))
        fixture(recurring) { store, _ ->
            // Recurrence is refused by LocalEditIntent itself, before storage can admit a partial edit.
            reject { intent.copy(base = recurring, content = recurring.content.copy(time = replacementTime)) }
            assertNull(store.localEdit(scope, base.eventId)); assertNull(store.localTimeEditPreparation(scope, base.eventId))
            assertEquals(recurring, store.projectedBaseline(scope, base.eventId))
            assertEquals(recurring, store.transaction(scope) { remote(base.eventId) })
        }
    }

    @Test fun staleRemoteAndForeignPreparationFamiliesCannotBeAdopted() {
        for (family in listOf("local_edit_preparations", "local_text_edit_preparations")) fixture { store, _ ->
            assertEquals(intent, store.captureLocalEdit(intent))
            assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(preparation()))
            // Simulate an incompatible legacy/interrupted capsule; presence must close admission before decoding it.
            store.writableDatabase.execSQL("INSERT INTO $family SELECT * FROM local_time_edit_preparations")
            store.writableDatabase.execSQL("DELETE FROM local_time_edit_preparations")
            assertEquals(LocalTimePreparationAdmission.UNQUALIFIED, store.retainCurrentLocalTimePreparation(preparation()))
            assertNull(store.localTimeEditPreparation(scope, base.eventId))
            store.writableDatabase.execSQL("DELETE FROM $family")
            store.transaction(scope) { putRemote(base.copy(revision = Revision("new-remote"))) }
            assertEquals(LocalTimePreparationAdmission.CONFLICT, store.assessLocalTimePreparation(intent))
            assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        }
    }

    @Test fun competingHelpersPersistOnlyOneFirstCapsule() = fixture { store, name ->
        assertEquals(intent, store.captureLocalEdit(intent))
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope { listOf(store, other).map { helper -> async(Dispatchers.IO) {
                gate.await(); helper.retainCurrentLocalTimePreparation(preparation())
            } }.also { gate.complete(Unit) }.awaitAll() }
            assertEquals(1, outcomes.count { it == LocalTimePreparationAdmission.STORED })
            assertEquals(1, outcomes.count { it == LocalTimePreparationAdmission.HISTORICAL })
            assertPreparation(preparation(), store.localTimeEditPreparation(scope, base.eventId))
        }
    }

    @Test fun leaseRevocationAndCancellationWithOrdinaryFailureRollBack() = fixture { store, _ ->
        assertEquals(intent, store.captureLocalEdit(intent)); var calls = 0
        store.admitTransaction = { ++calls == 1 }
        reject { store.retainCurrentLocalTimePreparation(preparation()) }
        store.admitTransaction = { true }; assertNull(store.localTimeEditPreparation(scope, base.eventId))
        for (ordinaryFailure in listOf(false, true)) supervisorScope {
            var cancelled = 0; var other = 0; var returned = 0
            val child = async {
                val job = currentCoroutineContext().job; calls = 0
                store.admitTransaction = {
                    if (++calls == 2) { job.cancel(); if (ordinaryFailure) throw java.io.IOException("Synthetic cancelled commit") }
                    true
                }
                try { store.retainCurrentLocalTimePreparation(preparation()); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { other++ }
            }
            try { child.await() } catch (_: CancellationException) { }
            store.admitTransaction = { true }
            assertEquals(1, cancelled); assertEquals(0, other); assertEquals(0, returned)
        }
        assertNull(store.localTimeEditPreparation(scope, base.eventId)); assertEquals(intent, store.localEdit(scope, base.eventId))
    }

    @Test fun utf8PayloadBoundaryAndMutableEmptyDetailsAreFrozenBeforeCallbacks() = fixture { store, name ->
        val reminders = mutableListOf<EventReminder>()
        val local = intent.copy(base = base.copy(content = base.content.copy(details = EventDetails(reminders = reminders))),
            content = intent.content.copy(details = EventDetails(reminders = reminders)))
        assertEquals(local, store.captureLocalEdit(local))
        val value = preparation(local, payload = "é".repeat(LocalTimeEditPreparation.MAX_PAYLOAD_BYTES / 2))
        reject { preparation(payload = value.protectedPayload + "é") }
        var calls = 0
        store.admitTransaction = { if (++calls == 1) reminders.add(EventReminder(10)); true }
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(value))
        store.admitTransaction = { true }; store.close()
        ImportStore(context, name).use { assertPreparation(value, it.localTimeEditPreparation(scope, base.eventId)) }
        assertEquals(1, reminders.size)
    }

    @Test fun corruptCapsuleMetadataCannotBeReplacedOrReinterpretedAsAbsence() = fixture { store, _ ->
        assertEquals(intent, store.captureLocalEdit(intent))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(preparation()))
        store.writableDatabase.execSQL("UPDATE local_time_edit_preparations SET timestamp='invalid-timestamp'")
        reject { store.localTimeEditPreparation(scope, base.eventId) }
        reject { store.retainCurrentLocalTimePreparation(preparation()) }
        store.readableDatabase.rawQuery("SELECT timestamp,payload FROM local_time_edit_preparations", null).use {
            assertTrue(it.moveToFirst()); assertEquals("invalid-timestamp", it.getString(0)); assertEquals(preparation().protectedPayload, it.getString(1))
        }
        assertEquals(intent, store.localEdit(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Existing capture journal + real Provider, inactive; no account session or service. */
class OccurrenceCaptureDeviceTest {
    @Test fun capturedProfileRoundTripsWithoutDowngradeOrChangingLegacyBytes() = fixture {
        val intent = intent()
        val payload = LocalCreateContentCodec.encodeContent(intent)
        assertEquals(4, org.json.JSONObject(payload).getInt("schema"))
        assertEquals(intent, LocalCreateContentCodec.decodeIntent(payload, reservation))
        assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeContent(payload, reservation) }
        val legacy = LocalCreateIntent(reservation, intent.content)
        val old = LocalCreateContentCodec.encodeContent(legacy)
        assertEquals(legacy, LocalCreateContentCodec.decodeIntent(old, reservation))
        assertEquals(old, LocalCreateContentCodec.encodeContent(LocalCreateContentCodec.decodeIntent(old, reservation)))
    }

    @Test fun nativeStampAndDurableCaptureSurviveReopeningWithoutChangingTheMaster() = fixture {
        val before = native.rows()
        val frozen = intent()
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val retained = checkNotNull(retained())
        assertEquals(frozen, retained); assertEquals(retained, context())
        assertNotNull(native.rows().getValue(native.child)[android.provider.CalendarContract.Events.SYNC_DATA5])
        assertEquals(before.getValue(native.parent), native.rows().getValue(native.parent))
        assertEquals("1", native.rows().getValue(native.child)[android.provider.CalendarContract.Events.DIRTY])
        journal.close(); journal = LocalCreateStore(context, name)
        assertEquals(retained, retained())
        assertEquals(LocalEditCaptureResult.RETAINED, capture(Long.MAX_VALUE, timestamp.plusSeconds(60)))
        assertEquals(retained, retained())
    }

    @Test fun aConsumedReservationWithoutAStampNeverUsesTheOldRowHintAgain() = fixture {
        val frozen = intent()
        assertTrue(journal.reserveOccurrence(frozen))
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertNull(retained()); assertEquals(frozen, context())
        assertNull(native.rows().getValue(native.child)[android.provider.CalendarContract.Events.SYNC_DATA5])
    }

    @Test fun dateOnlyOriginalSurvivesDurableTimedChildCaptureAndReopening() = fixture(allDay = true) {
        val frozen = intent()
        assertTrue(frozen.occurrence!!.original is RecurrencePoint.AllDay)
        assertTrue(frozen.content.time is EventTime.Timed)
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        journal.close(); journal = LocalCreateStore(context, name)
        assertEquals(frozen, retained()); assertEquals(frozen, context())
        assertEquals(LocalEditCaptureResult.RETAINED, capture(Long.MAX_VALUE))
        assertEquals("1", native.rows().getValue(native.child)[Events.DIRTY])
    }

    @Test fun slotAndPendingContextCannotBeReplacedByAnotherIdentityOrVariant() = fixture {
        val frozen = intent()
        assertTrue(journal.reserveOccurrence(frozen)); assertFalse(journal.reserveOccurrence(frozen))
        val source = frozen.occurrence!!
        val flat = frozen.content.copy(title = "Different phone edit")
        for (variant in listOf(
            frozen.copy(occurrence = OccurrenceEditCandidate(source.master.copy(revision = Revision("new-revision")), source.original, frozen.content)),
            frozen.copy(occurrence = OccurrenceEditCandidate(source.master, RecurrencePoint.Timed(Instant.parse("2030-04-04T09:00:00Z")), frozen.content)),
            frozen.copy(content = flat, occurrence = OccurrenceEditCandidate(source.master, source.original, flat)))) {
            assertThrows(IllegalStateException::class.java) { runBlocking { journal.reserveOccurrence(variant) } }
        }
        val duplicate = frozen.copy(reservation = reservation.copy(localId = LocalEventId(UUID.randomUUID().toString()), id = MutationId("other-capture")))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { runBlocking { journal.reserveOccurrence(duplicate) } }
        assertNull(journal.readReservation(scope, duplicate.reservation.localId))
        assertEquals(frozen, context()); assertNull(retained())
    }

    @Test fun lossAfterStampRecoversOnlyTheFrozenContextAndNeverAdoptsANewerEdit() {
        for (newer in listOf(false, true)) fixture {
            val frozen = intent()
            var writes = 0
            projection = object : CalendarProjection by native.projection {
                override suspend fun captureAndStampOccurrence(intent: LocalCreateIntent, childRow: Long): OwnedOccurrenceEditSnapshot? {
                    val result = native.projection.captureAndStampOccurrence(intent, childRow)
                    assertNotNull(result); writes++
                    throw java.io.IOException("Synthetic lost stamp response")
                }
            }
            assertEquals(LocalEditCaptureResult.UNAVAILABLE, capture())
            assertEquals(1, writes); assertNull(retained()); assertEquals(frozen, context())
            if (newer) native.change(native.child, Events.TITLE, "Newer phone occurrence")
            journal.close(); journal = LocalCreateStore(context, name)
            assertEquals(if (newer) LocalEditCaptureResult.UNQUALIFIED else LocalEditCaptureResult.RETAINED, capture(Long.MAX_VALUE))
            assertEquals(1, writes); assertEquals(if (newer) null else frozen, retained())
            assertEquals("1", native.rows().getValue(native.child)[Events.DIRTY])
            if (newer) assertEquals("Newer phone occurrence", native.rows().getValue(native.child)[Events.TITLE])
        }
    }

    @Test fun revocationAfterStampLeavesContextForInspectionWithoutRetainingContent() = fixture {
        val frozen = intent()
        projection = object : CalendarProjection by native.projection {
            override suspend fun captureAndStampOccurrence(intent: LocalCreateIntent, childRow: Long): OwnedOccurrenceEditSnapshot? {
                val result = native.projection.captureAndStampOccurrence(intent, childRow)
                assertNotNull(result); allowed = false
                return result
            }
        }
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        allowed = true
        assertNull(retained()); assertEquals(frozen, context())
        assertEquals(LocalEditCaptureResult.RETAINED, capture(Long.MAX_VALUE))
        assertEquals(frozen, retained())
    }

    @Test fun callerCancellationDuringAQueryCannotReachTheStampBatch() = fixture {
        val frozen = intent(); assertTrue(journal.reserveOccurrence(frozen))
        var batchReached = false; var queries = 0; var injected = false
        lateinit var job: Job
        val actual = context.contentResolver
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No direct insert")
            override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = error("No direct update")
            override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = error("No direct delete")
            override fun query(uri: Uri, c: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
                val result = actual.query(uri, c, s, a, o)
                if (uri.pathSegments.firstOrNull() == "events" && ++queries == 2) { injected = true; job.cancel() }
                return result
            }
            override fun applyBatch(ops: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                batchReached = true; return actual.applyBatch(CalendarContract.AUTHORITY, ops)
            }
        }
        val resolver = ContentResolver.wrap(provider)
        val guarded = AndroidCalendarProjection(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        })
        coroutineScope {
            job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { guarded.captureAndStampOccurrence(frozen, native.child) }
            job.start(); job.join()
        }
        assertTrue(injected); assertTrue(job.isCancelled); assertFalse(batchReached)
        assertNull(native.rows().getValue(native.child)[Events.SYNC_DATA5])
        assertEquals(frozen, context()); assertNull(retained())
    }

    @Test fun oldPreparationSubmissionAndFingerprintCannotDropTheOccurrenceContext() = fixture {
        val frozen = intent()
        assertThrows(IllegalArgumentException::class.java) { LocalCreatePreparation(frozen, AdapterFormat("legacy", 3), "sealed") }
        assertThrows(IllegalArgumentException::class.java) { LocalCreateSubmissionReservation(frozen, MutationId(UUID.randomUUID().toString()), AdapterFormat("legacy", 4)) }
        val work = CreateAttemptRecord(MutationId("legacy-work"), scope, AdapterFormat("legacy", 4), "sealed")
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateDispatchIdentity.of(work, frozen) }
        val payload = org.json.JSONObject(LocalCreateContentCodec.encodeContent(frozen))
        for (variant in listOf(org.json.JSONObject(payload.toString()).put("profile", 2),
            org.json.JSONObject(payload.toString()).put("unknown", true), org.json.JSONObject(payload.toString()).also {
                it.getJSONObject("master").put("unknown", "retained elsewhere")
            })) assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeIntent(variant.toString(), reservation) }
    }

    private class Fixture(val context: Context, allDay: Boolean) {
        val native = OccurrenceEditProviderDeviceTest.Fixture(context, allDay)
        val scope get() = native.scope
        private val token = UUID.randomUUID().toString()
        val name = "occurrence-capture-$token.db"
        private val importName = "occurrence-import-$token.db"
        var journal = LocalCreateStore(context, name)
        var projection: CalendarProjection = native.projection
        var allowed = true
        val store = ImportStore(context, importName)
        private val format = AdapterFormat("occurrence-fixture", 1)
        val timestamp = Instant.parse("2030-04-01T12:00:00Z")
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("capture-$token")
        val reservation get() = LocalCreateReservation(id, localId, scope, format, timestamp)
        private fun staging() = LocalCreateStaging(scope, store, projection, journal, { format }, { allowed }, { allowed }, { _, _ -> error("No outgoing lease") })
        suspend fun seed() {
            native.seed()
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic occurrence capture"))); store.setEnabled(scope, true)
            store.transaction(scope) { putRemote(native.base); putProjection(ProjectionIntent(scope, native.base)); checkpoint = PageCheckpoint(scope, format, traversalComplete = true) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, native.base)))
        }
        suspend fun intent(): LocalCreateIntent {
            native.projection.admitOperation = { true }
            val candidate = checkNotNull(native.projection.captureOwnedOccurrenceEdit(scope, native.base, native.child)).candidate
            return LocalCreateIntent(reservation, candidate.content.copy(recurrence = null), occurrence = candidate)
        }
        suspend fun capture(row: Long = native.child, time: Instant = timestamp): LocalEditCaptureResult {
            val result = staging().captureOccurrence(native.base, row, localId, id, time)
            assertFalse(native.projection.admitOperation()); assertFalse(journal.admitCapture(scope))
            return result
        }
        suspend fun retained(): LocalCreateIntent? { journal.admitCapture = { it == scope }; return journal.readIntent(scope, localId) }
        suspend fun context(): LocalCreateIntent? { journal.admitCapture = { it == scope }; return journal.readOccurrenceContext(scope, localId) }
        suspend fun close() {
            native.close(); journal.close(); store.close(); context.deleteDatabase(name); context.deleteDatabase(importName)
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

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

    @Test fun occurrenceSubmissionReopensWithoutRearmingAndClaimsExactlyOnce() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val intent = checkNotNull(retained())
        val port = SubmissionPort()
        val coordinator = coordinator()
        val attempt = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalCreateSubmissionResult.RETAINED, coordinator.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        journal.admitCapture = { true }
        val reopened = checkNotNull(journal.readSubmission(scope, localId))
        assertTrue(reopened.reservation.isOccurrence); assertEquals(intent, reopened.reservation.intent)
        // A new owner cannot borrow the staging right from the first owner.
        assertSame(CreateSubmissionObservation.Unknown, coordinator().submitLocalCreate(scope, localId, id, attempt, port))
        assertNull(attempts.read(scope, attempt)); assertEquals(0, port.sends)
        val owner = coordinator()
        val resumed = owner.resumePreparedLocalCreate(scope, localId, id, attempt, port)
        assertEquals(LocalCreateSubmissionResult.RETAINED, resumed)
        assertEquals(1, port.prepares); assertEquals(0, port.sends)
        // Use the first owner with its existing fresh lease and the same database handle.
        assertSame(CreateSubmissionObservation.Unknown, coordinator.submitLocalCreate(scope, localId, id, attempt, port))
        assertEquals(1, port.sends)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(scope, attempt)!!.state)
        assertEquals(3, attempts.capturedIdentity(scope, attempt)!!.fingerprintVersion)
        coordinator.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(1, port.sends); assertEquals(1, port.prepares)
        journal.close(); journal = LocalCreateStore(context, name)
        coordinator().submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(1, port.sends)
        assertEquals("1", native.rows().getValue(native.child)[Events.DIRTY])
    }

    @Test fun finalClaimRefusesNewerChildOrExtraFamilyMemberAndKeepsCapturedIntent() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val captured = checkNotNull(retained())
        val port = SubmissionPort(); val owner = coordinator(); val attempt = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        port.beforeClaim = { native.change(native.child, Events.TITLE, "Newer native edit") }
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends); assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
        assertEquals(captured, retained())
        native.change(native.child, Events.TITLE, captured.content.title)
        native.insertChild()
        port.beforeClaim = {}
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends); assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
    }

    @Test fun finalClaimRechecksRemoteMasterCacheAndEditableConsentWithoutAnEpochChange() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val port = SubmissionPort(); val owner = coordinator(); val attempt = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        port.beforeClaim = { store.transaction(scope) { putRemote(native.base.copy(revision = Revision("newer"))) } }
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends); assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
        store.admitTransaction = { true }; store.transaction(scope) { putRemote(native.base) }
        port.beforeClaim = { editable = false }
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends); assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
    }

    @Test fun readOnlyOccurrenceKeepsItsOneShotPreparationRight() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val owner = coordinator()
        val port = SubmissionPort()
        val attempt = MutationId(UUID.randomUUID().toString())
        editable = false
        val outcome = owner.stageLocalCreateSubmission(scope, localId, id, attempt, port)
        journal.admitCapture = { it == scope }
        assertNull(journal.readSubmissionReservation(scope, localId))
        assertEquals(0, port.prepares)
        assertEquals(LocalCreateSubmissionResult.INACTIVE, outcome)
        editable = true
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        assertEquals(1, port.prepares)
    }

    @Test fun interruptedOccurrenceSubmissionReservationNeverRegeneratesAfterReopen() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val port = SubmissionPort(); val owner = coordinator(); val attempt = MutationId(UUID.randomUUID().toString())
        port.onPrepare = { throw java.io.IOException("Synthetic preparation interruption") }
        assertEquals(LocalCreateSubmissionResult.UNAVAILABLE, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        journal.close(); journal = LocalCreateStore(context, name)
        port.onPrepare = {}
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, coordinator().stageLocalCreateSubmission(scope, localId, id, attempt, port))
        assertEquals(1, port.prepares); assertEquals(0, port.sends)
        journal.admitCapture = { true }
        assertTrue(journal.readSubmissionReservation(scope, localId)!!.isOccurrence)
        assertNull(journal.readSubmission(scope, localId))
    }

    @Test fun consentRevokedDuringTheLastNativeInspectionCannotReachTheClaim() = fixture {
        var revoke = false
        projection = object : CalendarProjection by native.projection {
            override suspend fun inspectStampedOccurrence(intent: LocalCreateIntent): OwnedOccurrenceEditSnapshot? {
                val observed = native.projection.inspectStampedOccurrence(intent)
                if (revoke) editable = false
                return observed
            }
        }
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val port = SubmissionPort(); val owner = coordinator(); val attempt = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        port.beforeClaim = { revoke = true }
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends)
        assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
    }

    @Test fun missingOriginalCaptureContextCannotBeAdoptedAtTheFinalClaim() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val port = SubmissionPort(); val owner = coordinator(); val attempt = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, attempt, port))
        port.beforeClaim = { journal.writableDatabase.execSQL("UPDATE local_creates SET occurrence_capture=NULL,occurrence_slot=NULL") }
        owner.submitLocalCreate(scope, localId, id, attempt, port)
        assertEquals(0, port.sends)
        assertEquals(CreateAttemptState.PREPARED, attempts.read(scope, attempt)!!.state)
    }

    internal open class SubmissionPort : CalendarCreateSubmissionPort {
        override val occurrenceSubmissionFormat = AdapterFormat("synthetic-occurrence-submission", 5)
        var prepares = 0; var sends = 0
        var onPrepare: suspend () -> Unit = {}; var beforeClaim: suspend () -> Unit = {}
        private var frozen: CalendarCreateDraft? = null
        override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
            prepares++; frozen = draft.frozen(); onPrepare()
            return PreparedCalendarCreate(draft.scope, occurrenceSubmissionFormat, "synthetic-retained-occurrence")
        }
        override suspend fun readCapturedExpectation(record: CreateAttemptRecord, expected: CalendarCreateDraft): EventContent? =
            expected.occurrence?.occurrence?.content?.takeIf { expected == frozen && record.id == expected.correlation &&
                record.format == occurrenceSubmissionFormat && record.protectedPayload == "synthetic-retained-occurrence" }
        override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No generic sends")
        override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = CreateSubmissionObservation.Unknown
        override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal): CreateSubmissionObservation {
            beforeClaim()
            if (journal.claim(record)) sends++
            return CreateSubmissionObservation.Unknown
        }
        override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation =
            CreateSubmissionObservation.Unknown
    }

    internal class Fixture(val context: Context, allDay: Boolean) {
        val native = OccurrenceEditProviderDeviceTest.Fixture(context, allDay)
        val scope get() = native.scope
        private val token = UUID.randomUUID().toString()
        val name = "occurrence-capture-$token.db"
        private val importName = "occurrence-import-$token.db"
        var journal = LocalCreateStore(context, name)
        var projection: CalendarProjection = native.projection
        var allowed = true
        val store = ImportStore(context, importName)
        val attempts = CreateAttemptStore(context, "occurrence-dispatch-$token.db")
        var editable = true
        private val format = AdapterFormat("occurrence-fixture", 1)
        val timestamp = Instant.parse("2030-04-01T12:00:00Z")
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("capture-$token")
        val reservation get() = LocalCreateReservation(id, localId, scope, format, timestamp)
        internal val reader: ReadOnlyCalendarRemote = object : ReadOnlyCalendarRemote {
            override val format get() = this@Fixture.format
            override suspend fun currentAccount() = scope.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No import network")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        fun coordinator(port: CalendarCreateSubmissionPort? = null) = ImportCoordinatorCapturedCreates(store,
            if (port == null) reader else object : ReadOnlyCalendarRemote by reader, CalendarCreateSubmissionPort by port {},
            projection, kotlinx.coroutines.sync.Mutex(), object : ImportScheduler {
            override fun cancel() {}; override fun requestLocal() {}; override fun requestNetwork() {}; override fun continueWork(network: Boolean) {}
        }, attempts, journal, { 0 }, { 0 }, { allowed }, { editable }, { allowed }, { _, _ -> allowed }, {}, { _, _, _ -> check(allowed) })
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
            native.close(); journal.close(); attempts.close(); store.close(); context.deleteDatabase(name); context.deleteDatabase(importName)
            context.deleteDatabase("occurrence-dispatch-$token.db")
        }
    }
    internal fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

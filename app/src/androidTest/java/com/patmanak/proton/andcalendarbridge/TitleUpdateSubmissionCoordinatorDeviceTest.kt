// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQL and controlled transport/native observations. This slice neither acknowledges
 * Android nor persists verified results; synthetic Verified is not Proton compatibility evidence.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateSubmissionCoordinatorDeviceTest {
    private class Journal(private val source: TitleUpdateAttemptStore) : TitleUpdateAttemptJournal {
        var claims = 0
        var afterRead: suspend (TitleUpdateAttemptRecord?) -> TitleUpdateAttemptRecord? = { it }
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No preparation retention")
        override suspend fun remember(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No preparation retention")
        override suspend fun read(scope: CalendarScope, id: MutationId) = afterRead(source.read(scope, id))
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No legacy claim")
        override suspend fun claim(expected: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            claims++; return source.claim(expected, admit)
        }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) =
            source.retainEvidence(expectedUnknown, protectedEvidence)
    }
    private open class UnsupportedPort(override val titleUpdateFormat: AdapterFormat?) : CalendarTitleUpdateSubmissionPort {
        var legacySubmissions = 0
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No preparation")
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? = error("No preparation")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
            legacySubmissions++; error("No unrestricted submission")
        }
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
            legacySubmissions++; error("No fallback to three-argument submission")
        }
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
        override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = error("No inspection")
    }
    private class Fixture(allDay: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val importName = "title-submit-owner-$token.db"
        val journalName = "title-submit-attempt-$token.db"
        val target = CalendarScope(AccountId("title-submit-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("fixture-import", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), EventContent(
            "Synthetic original", if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 27), LocalDate.of(2030, 10, 28)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            "Synthetic description", "Synthetic location", details = EventDetails()))
        val intent = LocalEditIntent(MutationId("fixture-local-intent"), target, format, base, base.content.copy(title = "Synthetic prepared title"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-07T12:00:00Z"), AdapterFormat("fixture-preparation", 1), "synthetic-capsule")
        val record = TitleUpdateAttemptRecord(MutationId("fixture-attempt"), preparation, AdapterFormat("fixture-wire", 1), "synthetic-wire")
        val unknown get() = record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val responseEvidence = "synthetic-response-evidence"
        val evidencedUnknown get() = unknown.copy(protectedEvidence = responseEvidence)
        val expectedEvent = base.copy(revision = Revision("synthetic-updated-revision"), content = intent.content)
        var reportedEvent = expectedEvent
        var reportedAttempt = TitleUpdateAttemptIdentity.of(unknown)
        val store = ImportStore(context, importName)
        val journal = TitleUpdateAttemptStore(context, journalName)
        val guarded = Journal(journal)
        var permission = true
        var mode = true
        var account: AccountId? = target.account
        var nativeContent = intent.content
        var nativeAbsent = false
        var captures = 0
        var nativeWrites = 0
        var submissions = 0
        var simulatedSends = 0
        var inRuntime = false
        var forbiddenAccountReentries = 0
        var forbidOrdinaryCapture = false
        var duringAccount: suspend () -> Unit = {}
        var duringCapture: suspend () -> Unit = {}
        var atStage: suspend (String) -> Unit = {}
        var afterSourceDenial: (suspend () -> Unit)? = null
        var swallowWithUnknown = false
        var skipClaim = false
        var omitEvidence = false
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("fixture-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar { nativeWrites++; error("No native write") }
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) { nativeWrites++; error("No native write") }
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                nativeWrites++; error("Submission cannot acknowledge Android")
            }
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                check(!forbidOrdinaryCapture) { "CREATE provenance cannot fall back to ordinary capture" }
                check(admitOperation()); assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                captures++; duringCapture(); return if (nativeAbsent) null else OwnedLocalEditSnapshot(1, 1, nativeContent)
            }
        }
        val port = object : UnsupportedPort(record.format) {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean,
                qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                submissions++; inRuntime = true
                try {
                    assertEquals(this@Fixture.record, record)
                    assertTrue(admit(target)); assertFalse(admit(target.copy(generation = target.generation + 1)))
                    suspend fun qualify(): Boolean {
                        val result = qualifySource(target)
                        if (!result) afterSourceDenial?.let { restore ->
                            restore(); assertFalse(admit(target)); assertFalse(qualifySource(target))
                        }
                        return result && admit(target)
                    }
                    atStage("entered")
                    if (swallowWithUnknown) return TitleUpdateSubmissionObservation.Unknown
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    atStage("beforeClaim")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    if (!skipClaim && !journal.claim(record, admit)) return TitleUpdateSubmissionObservation.Unknown
                    atStage("afterClaim")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    simulatedSends++
                    if (!omitEvidence) journal.retainEvidence(unknown, responseEvidence)
                    atStage("afterSend")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    return TitleUpdateSubmissionObservation.Verified(reportedAttempt, reportedEvent)
                } finally { inRuntime = false }
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? {
                if (inRuntime) { forbiddenAccountReentries++; error("No authentication facade re-entry during runtime") }
                duringAccount(); return account
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        private val scheduled = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, systemCreatesEnabled = { mode })
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic submission fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertEquals(intent, store.captureLocalEdit(intent)); assertTrue(store.retainLocalEditPreparation(preparation))
            val reservation = TitleUpdatePreparationReservation.of(record)
            assertEquals(reservation, journal.reservePreparation(reservation) { true }); journal.remember(record)
            journal.admitDispatch = { error("No global dispatch authority") }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (scheduled.get() == 0) delay(10) }
        }
        suspend fun submit(value: TitleUpdateAttemptRecord = record, allowNetwork: Boolean = false,
            targetPort: CalendarTitleUpdateSubmissionPort = port) = coordinator.submitLocalTitleAttempt(value, guarded, targetPort, allowNetwork)
        suspend fun owner(): LocalEditIntent? { store.admitTransaction = { true }; return store.localEdit(target, base.eventId) }
        suspend fun assertNoAcknowledgementOrResult() {
            assertEquals(0, nativeWrites); assertEquals(0, forbiddenAccountReentries)
            assertNull(journal.readVerified(target, record.id)); assertNull(journal.readCompletion(target, record.id))
        }
        suspend fun close() {
            duringAccount = {}; coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            journal.close(); store.close(); context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(allDay)
        try { fixture.seed(); fixture.block(); fixture.assertNoAcknowledgementOrResult() } finally { fixture.close() }
    }

    @Test fun timedAndAllDaySubmissionReturnsVerifiedWithDurableUnknownAndRetainedOwner() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            val result = submit(allowNetwork = true) as? TitleUpdateSubmissionObservation.Verified
            assertNotNull(result); assertEquals(expectedEvent, result!!.event)
            assertEquals(TitleUpdateAttemptIdentity.of(unknown), result.attempt)
            assertEquals(1, submissions); assertEquals(1, guarded.claims); assertEquals(1, simulatedSends)
            assertEquals(evidencedUnknown, journal.read(target, record.id)); assertEquals(intent, owner())
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            TitleUpdateAttemptStore(context, journalName).use { reopened -> assertEquals(evidencedUnknown, reopened.read(target, record.id)) }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true)); assertEquals(1, submissions)
        }
    }

    @Test fun offlineDefaultNeverCallsTheSubmissionPort() = fixture {
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit())
        assertEquals(0, submissions); assertEquals(0, guarded.claims); assertEquals(record, journal.read(target, record.id))
    }

    @Test fun missingWireUnknownOrEvidenceBearingInputCannotEnterRuntime() {
        fixture {
            journal.writableDatabase.execSQL("DELETE FROM title_update_attempts")
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true)); assertEquals(0, submissions)
        }
        fixture {
            assertTrue(journal.claim(record) { true })
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true)); assertEquals(0, submissions)
            assertEquals(unknown, journal.read(target, record.id))
        }
        fixture {
            for (value in listOf(record.copy(protectedEvidence = "invented-evidence"), unknown))
                assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(value, true))
            assertEquals(0, submissions); assertEquals(0, guarded.claims)
        }
    }

    @Test fun substitutedWireCapsuleOwnerOrFormatCannotReachThePort() = fixture {
        for (value in listOf(record.copy(id = MutationId("substituted-id")), record.copy(protectedPayload = "substituted-wire"),
            record.copy(format = record.format.copy(version = 2)), record.copy(preparation = preparation.copy(protectedPayload = "substituted-capsule")),
            record.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("substituted-owner"))))))
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(value, true))
        assertEquals(0, submissions); assertEquals(0, guarded.claims); assertEquals(record, journal.read(target, record.id))
    }

    @Test fun missingOwnerCapsuleOrConflictedOwnerCannotReachRuntime() {
        for (change in listOf("DELETE FROM local_edits", "DELETE FROM local_edit_preparations", "UPDATE local_edits SET state='CONFLICT'")) fixture {
            store.writableDatabase.execSQL(change)
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(0, submissions); assertEquals(0, guarded.claims); assertEquals(record, journal.read(target, record.id))
        }
    }

    @Test fun changedOrMissingNativeContentIsHeldBeforeRuntime() {
        for (absent in listOf(false, true)) fixture {
            nativeAbsent = absent; if (!absent) nativeContent = intent.content.copy(title = "Synthetic later native edit")
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(0, submissions); assertEquals(0, guarded.claims); assertEquals(intent, owner())
        }
    }

    @Test fun nativeAndSqlChangesDuringRuntimePreventClaim() {
        for (sql in listOf(false, true)) fixture {
            atStage = { if (it == "beforeClaim") {
                if (sql) store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'")
                else nativeContent = intent.content.copy(title = "Synthetic later native edit")
            } }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(1, submissions); assertEquals(0, guarded.claims); assertEquals(0, simulatedSends)
            assertEquals(record, journal.read(target, record.id))
        }
    }

    @Test fun changedOwnerAfterClaimStaysUnknownWithoutSendingOrAcknowledging() = fixture {
        atStage = { if (it == "afterClaim") store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'") }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
        assertEquals(1, guarded.claims); assertEquals(0, simulatedSends); assertEquals(unknown, journal.read(target, record.id))
        assertEquals(LocalEditState.CONFLICT, owner()!!.state)
    }

    @Test fun deniedSourceClosesInvocationEvenWhenNativeContentIsRestored() = fixture {
        atStage = { if (it == "beforeClaim") nativeContent = intent.content.copy(title = "Synthetic intervening edit") }
        afterSourceDenial = { nativeContent = intent.content }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
        assertEquals(1, submissions); assertEquals(0, guarded.claims); assertEquals(0, simulatedSends)
        assertEquals(record, journal.read(target, record.id))
    }

    @Test fun permissionPolicyAndSelectionRevocationBeforeClaimCannotResumeTheInvocation() {
        for (revocation in 0..2) fixture {
            atStage = { if (it == "beforeClaim") when (revocation) {
                0 -> { permission = false; coordinator.permissionsChanged(); permission = true; coordinator.permissionsChanged() }
                1 -> { coordinator.systemCreatePolicyChanged(target, false); coordinator.systemCreatePolicyChanged(target, true) }
                else -> store.setEnabled(target, false)
            } }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(1, submissions); assertEquals(0, guarded.claims); assertEquals(0, simulatedSends)
            assertEquals(record, journal.read(target, record.id))
        }
    }

    @Test fun revocationAfterSimulatedSendHidesObservationAndPreservesUnknown() = fixture {
        atStage = { if (it == "afterSend") { permission = false; coordinator.permissionsChanged() } }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
        assertEquals(1, guarded.claims); assertEquals(1, simulatedSends); assertEquals(evidencedUnknown, journal.read(target, record.id))
        assertEquals(intent, owner())
    }

    @Test fun cancellationSwallowedAsNullOrUnknownStillPropagatesBeforeNormalReturn() {
        for (stage in listOf("account", "capture", "runtime")) fixture {
            val normalReturns = AtomicInteger()
            suspend fun cancelCall() { currentCoroutineContext().cancel(CancellationException("Synthetic submission cancellation")) }
            when (stage) {
                "account" -> duringAccount = { cancelCall(); account = null }
                "capture" -> duringCapture = { cancelCall(); nativeAbsent = true }
                else -> { swallowWithUnknown = true; atStage = { if (it == "entered") cancelCall() } }
            }
            supervisorScope {
                val request = async { submit(allowNetwork = true); normalReturns.incrementAndGet() }
                try { request.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(0, normalReturns.get()); assertEquals(0, guarded.claims); assertEquals(0, simulatedSends)
            assertEquals(record, journal.read(target, record.id))
            duringAccount = {}; account = target.account
        }
    }

    @Test fun unsupportedFourArgumentPortNeverFallsBackToLegacySubmission() = fixture {
        val unsupported = UnsupportedPort(record.format)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true, targetPort = unsupported))
        assertEquals(0, unsupported.legacySubmissions)
        assertEquals(0, guarded.claims); assertEquals(record, journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun verifiedResponseWithDifferentContentOrAttemptIsHeldWithoutAcknowledgement() {
        for (differentAttempt in listOf(false, true)) fixture {
            if (differentAttempt) reportedAttempt = reportedAttempt.copy(id = MutationId("other-reported-attempt"))
            else reportedEvent = expectedEvent.copy(content = expectedEvent.content.copy(title = "Different observed title"))
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(1, guarded.claims); assertEquals(1, simulatedSends)
            assertEquals(evidencedUnknown, journal.read(target, record.id)); assertEquals(intent, owner())
        }
    }

    @Test fun verifiedWithoutCommittedClaimOrResponseEvidenceCannotPassTheDurableBarrier() {
        for (claimed in listOf(false, true)) fixture {
            skipClaim = !claimed; omitEvidence = true
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertEquals(1, submissions); assertEquals(1, simulatedSends)
            assertEquals(if (claimed) 1 else 0, guarded.claims)
            assertEquals(if (claimed) unknown else record, journal.read(target, record.id))
            assertEquals(intent, owner())
        }
    }

    @Test fun finalDurableReadCannotSubstituteWireOrHideALaterNativeEdit() {
        for (substitutedWire in listOf(false, true)) fixture {
            var finalReads = 0
            guarded.afterRead = { durable ->
                if (submissions > 0 && !inRuntime) {
                    finalReads++
                    if (substitutedWire) durable?.copy(protectedPayload = "substituted-final-wire")
                    else { nativeContent = intent.content.copy(title = "Synthetic edit during final journal read"); durable }
                } else durable
            }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
            assertTrue(finalReads > 0); assertEquals(1, guarded.claims); assertEquals(1, simulatedSends)
            assertEquals(evidencedUnknown, journal.read(target, record.id)); assertEquals(intent, owner())
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        }
    }

    @Test fun corruptCreateHandoffProvenanceCannotFallBackToOrdinaryOwnership() = fixture {
        store.writableDatabase.execSQL("""INSERT INTO captured_create_handoffs
            (scope,event,nonce,protocol,ack_nonce,ack_fingerprint,fingerprint,state,waiting)
            SELECT scope,event,'cc044f8e-24f8-4b88-9d7d-7a9f7d0a2367',1,
                'e14185dc-6c6b-4bb4-95c9-416f4c318ebc','invalid','invalid','RELEASED',1 FROM local_edits""".trimIndent())
        forbidOrdinaryCapture = true
        assertEquals(TitleUpdateSubmissionObservation.Unknown, submit(allowNetwork = true))
        assertEquals(0, captures); assertEquals(0, submissions); assertEquals(0, guarded.claims)
        assertEquals(record, journal.read(target, record.id))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protected fragments qualify guarded control flow, not native crypto or service acceptance. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedTitleSubmissionTest {
    private val stages = listOf("user", "addresses", "metadata", "context-bootstrap", "local-addresses")
    private fun fixture() = TitleSubmissionFixture().also { it.contextStages = stages; it.protectedOpens = 0 }
    private class NamedJournal(private val f: TitleSubmissionFixture) : TitleUpdateAttemptJournal {
        var reads = 0
        var returnNullFromRead = false
        var claims = 0
        var afterRead: suspend () -> Unit = {}
        var beforeClaim: suspend () -> Unit = {}
        var afterClaim: suspend () -> Unit = {}
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No retention during submission")
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? {
            reads++
            val result = f.journal.read(scope, id)
            afterRead()
            return if (returnNullFromRead) null else result
        }
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = throw AssertionError("No unguarded claim fallback")
        override suspend fun claim(expected: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            claims++
            beforeClaim()
            if (!admit(expected.scope)) return false
            assertFalse(admit(expected.scope.copy(generation = expected.scope.generation + 1)))
            val result = f.journal.claim(expected)
            afterClaim()
            return result
        }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) =
            f.journal.retainEvidence(expectedUnknown, protectedEvidence)
    }

    @Test fun admittedSubmissionClaimsOnceRetainsEvidenceThenVerifiesExactContent() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        val result = adapter(f).submit(f.record, journal) { true } as TitleUpdateSubmissionObservation.Verified
        assertEquals(TitleUpdateAttemptIdentity.of(f.record), result.attempt)
        assertEquals(f.verified, result.event)
        assertEquals(1, journal.claims); assertEquals(1, f.sends); assertEquals(1, f.prepares)
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertNotNull(f.journal.stored.protectedEvidence)
        assertTrue(f.steps.indexOf("claim") < f.steps.indexOf("send"))
        assertTrue(f.steps.indexOf("evidence") < f.steps.indexOf("get"))
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { true })
        assertEquals(1, f.sends); assertEquals(1, journal.claims)
        assertEquals(1, f.steps.count { it == "get" })
    }

    @Test fun deniedAdmissionDoesNotReadJournalSessionOrProtectedData() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { false })
        assertEquals(0, journal.reads); assertEquals(0, f.sessionReads); assertEquals(0, f.protectedOpens)
        assertTrue(f.steps.isEmpty())
    }

    @Test fun unknownCallerOrStoredUnknownNeverInspectsOrPreparesAgain() = runTest {
        for (storedUnknown in listOf(false, true)) {
            val f = fixture(); val journal = NamedJournal(f)
            val unknown = f.record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            if (storedUnknown) f.journal.stored = unknown
            assertEquals(TitleUpdateSubmissionObservation.Unknown,
                adapter(f).submit(if (storedUnknown) f.record else unknown, journal) { true })
            assertEquals(0, f.prepares); assertEquals(0, f.sends); assertEquals(0, journal.claims)
            assertFalse("get" in f.steps); assertFalse("verify" in f.steps)
        }
    }

    @Test fun evidenceWrongFormatOrSubstitutedHistoricalBindingCannotClaim() = runTest {
        val seed = fixture()
        val invalid = listOf(seed.record.copy(protectedEvidence = "invented-evidence"),
            seed.record.copy(format = seed.record.format.copy(version = 2)),
            seed.record.copy(id = MutationId("foreign-attempt")),
            seed.record.copy(preparation = seed.preparation.copy(timestamp = seed.timestamp.plusSeconds(1))))
        for (record in invalid) {
            val f = fixture(); val journal = NamedJournal(f)
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(record, journal) { true })
            assertEquals(0, f.prepares); assertEquals(0, f.sends); assertEquals(0, journal.claims)
            assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun revokedJournalReadCannotProceedToOpeningOrPreparation() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var admitted = true
        journal.afterRead = { admitted = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
        assertEquals(0, f.protectedOpens); assertEquals(0, f.prepares); assertEquals(0, journal.claims)
    }

    @Test fun revocationDuringSessionLookupCannotOpenProtectedData() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var admitted = true
        f.afterSession = { admitted = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
        assertEquals(0, f.protectedOpens); assertEquals(0, f.prepares); assertEquals(0, journal.claims)
    }

    @Test fun revocationAtFreshPreparationAndEveryContextStageStopsBeforeClaim() = runTest {
        for (boundary in listOf("prepare", "context") + stages) {
            val f = fixture(); val journal = NamedJournal(f); var admitted = true
            f.after = { if (it == boundary) admitted = false }
            assertEquals(boundary, TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
            assertEquals(boundary, f.steps.last())
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun sessionReplacementAtFreshPreparationAndEveryContextStageCannotClaim() = runTest {
        for (boundary in listOf("prepare", "context") + stages) {
            val f = fixture(); val journal = NamedJournal(f)
            f.after = { if (it == boundary) f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement")) }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { true })
            assertEquals(boundary, f.steps.last())
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun changedFreshBaseOrContextNeverClaims() = runTest {
        for (variant in 0..1) {
            val f = fixture(); val journal = NamedJournal(f)
            if (variant == 0) f.fresh = f.preparationFor(ProtonTimestampedSharedTitle(
                JsonObject(f.row + ("ModifyTime" to JsonPrimitive(9))), f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true))
            else f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("different")))
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { true })
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun unsupportedGuardedJournalNeverFallsBackToLegacyClaim() = runTest {
        val f = fixture()
        val legacy = object : TitleUpdateAttemptJournal {
            override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No remember")
            override suspend fun read(scope: CalendarScope, id: MutationId) = f.journal.read(scope, id)
            override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = throw AssertionError("No legacy claim")
            override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit = error("No evidence")
        }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, legacy) { true })
        assertEquals(f.record, f.journal.stored); assertEquals(0, f.sends)
    }

    @Test fun revokeBeforeClaimLeavesPreparedButRevokeAfterClaimHoldsUnknownWithoutSend() = runTest {
        for (after in listOf(false, true)) {
            val f = fixture(); val journal = NamedJournal(f); var admitted = true
            if (after) journal.afterClaim = { admitted = false } else journal.beforeClaim = { admitted = false }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
            assertEquals(if (after) TitleUpdateAttemptState.OUTCOME_UNKNOWN else TitleUpdateAttemptState.PREPARED, f.journal.stored.state)
            assertEquals(0, f.sends); assertNull(f.journal.stored.protectedEvidence); assertFalse("get" in f.steps)
        }
    }

    @Test fun replacedSessionAfterClaimHoldsUnknownWithoutTransport() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        journal.afterClaim = { f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement")) }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { true })
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertEquals(0, f.sends); assertFalse("get" in f.steps)
    }

    @Test fun revocationOrSessionReplacementAfterSendRetainsBodyAndSuppressesInspection() = runTest {
        for (replaceSession in listOf(false, true)) {
            val f = fixture(); val journal = NamedJournal(f); var admitted = true
            f.after = { if (it == "send") {
                if (replaceSession) f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement"))
                else admitted = false
            } }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
            assertEquals(1, f.sends); assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
            val evidence = Json.parseToJsonElement(f.protector.open(checkNotNull(f.journal.stored.protectedEvidence))) as JsonObject
            assertEquals("{\"Code\":1001}", evidence.string("body"))
            assertEquals(JsonPrimitive(true), evidence["complete"])
            assertFalse("get" in f.steps); assertFalse("verify" in f.steps)
        }
    }

    @Test fun inspectionRevocationAfterSendCannotReturnVerifiedOrContinueItsStages() = runTest {
        for (boundary in listOf("get", "bootstrap", "verify")) {
            val f = fixture(); val journal = NamedJournal(f); var admitted = true
            f.after = { if (it == boundary) admitted = false }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { admitted })
            assertEquals(boundary, f.steps.last()); assertNotNull(f.journal.stored.protectedEvidence)
            assertEquals(1, f.sends); assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        }
    }

    @Test fun cancellationDuringTransportPropagatesAndUnknownCannotBeSubmittedAgain() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        f.sendFailure = CancellationException("Synthetic cancellation")
        try { adapter(f).submit(f.record, journal) { true }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal) { true })
        assertEquals(1, f.sends); assertEquals(1, journal.claims); assertFalse("get" in f.steps)
    }

    @Test fun cancelledReadReturningNormallyCannotProduceUnknownNormally() = runTest {
        for (returnsNull in listOf(false, true)) {
            val f = fixture(); val journal = NamedJournal(f); var returned = false
            journal.returnNullFromRead = returnsNull
            journal.afterRead = { currentCoroutineContext().cancel(CancellationException("Synthetic cancellation")) }
            supervisorScope {
                val task = async { adapter(f).submit(f.record, journal) { true }; returned = true }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertFalse(returned); assertEquals(0, f.prepares); assertEquals(0, f.sends)
        }
    }

    @Test fun revocationWhileJournalReadSuspendsStopsBeforeProtectedOpening() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var admitted = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        journal.afterRead = { entered.complete(Unit); release.await() }
        val work = async { adapter(f).submit(f.record, journal) { admitted } }
        runCurrent(); assertTrue("Guarded journal read was not invoked", entered.isCompleted)
        admitted = false; release.complete(Unit)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, work.await())
        assertEquals(0, f.protectedOpens); assertEquals(0, f.prepares); assertEquals(0, journal.claims)
    }

    @Test fun unsupportedGuardedFreshPreparationDoesNotFallBackToLegacyPreparation() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        assertEquals(TitleUpdateSubmissionObservation.Unknown,
            adapter(f, supportsGuardedFresh = false).submit(f.record, journal) { true })
        assertEquals(0, f.prepares); assertEquals(0, journal.claims); assertEquals(0, f.sends)
        assertEquals(f.record, f.journal.stored)
    }

    private fun adapter(f: TitleSubmissionFixture, supportsGuardedFresh: Boolean = true): ProtonTitleUpdateSubmissionAdapter {
        val unsupported = CalendarLocalEditPreparationPort { _, _ -> throw AssertionError("No legacy fresh preparation") }
        val guarded = object : CalendarLocalEditPreparationPort {
            override val localTitleFormat = f.preparation.format
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: java.time.Instant): LocalEditPreparation =
                throw AssertionError("No legacy fresh preparation")
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: java.time.Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation? {
                assertEquals(f.intent, intent); assertEquals(f.timestamp, timestamp)
                if (!admit(f.scope)) return null
                assertFalse(admit(f.scope.copy(generation = f.scope.generation + 1)))
                f.prepares++; f.step("prepare")
                return if (admit(f.scope)) f.fresh else null
            }
        }
        return ProtonTitleUpdateSubmissionAdapter(if (supportsGuardedFresh) guarded else unsupported,
            f.protector, { f.sessionReads++; val value = f.current; f.afterSession(); value }, { _, _, checkpoint ->
                f.step("context"); checkpoint()
                f.contextStages.forEach { checkpoint(); f.step(it); checkpoint() }
                f.freshContext
            }, object : TitleUpdateSubmissionRemote {
                override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
                    assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
                    f.sends++; f.step("send"); f.sendFailure?.let { throw it }
                    return "{\"Code\":1001}"
                }
                override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                    assertEquals(f.intent.base.eventId.value, id); f.step("get"); return f.readRow
                }
            }, object : EventImportRemote {
                override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage =
                    error("No search")
                override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                    f.step("bootstrap"); return f.bootstrap
                }
            }, CreateEventVerifier { _, _, _, _, uid, policy ->
                assertEquals("synthetic-uid", uid); assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy)
                f.step("verify"); f.verified
            })
    }}
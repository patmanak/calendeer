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
class ProtonOwnerQualifiedTitleSubmissionTest {
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

    @Test fun admittedAsyncSourceAllowsOneClaimAndVerifiedReadback() = runTest {
        val f = fixture(); val journal = NamedJournal(f); val scopes = mutableListOf<CalendarScope>()
        val result = adapter(f).submit(f.record, journal, { true }) {
            yield(); scopes += it; it == f.scope
        } as TitleUpdateSubmissionObservation.Verified
        assertEquals(TitleUpdateAttemptIdentity.of(f.record), result.attempt); assertEquals(f.verified, result.event)
        assertTrue(scopes.isNotEmpty()); assertTrue(scopes.all { it == f.scope })
        assertEquals(1, f.sends); assertEquals(1, journal.claims)
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertNotNull(f.journal.stored.protectedEvidence)
    }

    @Test fun deniedInitialSourceStopsBeforeSessionProtectedDataAndNetwork() = runTest {
        val f = fixture(); val journal = NamedJournal(f)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal, { true }) { false })
        assertEquals(0, f.sessionReads); assertEquals(0, f.protectedOpens)
        assertEquals(0, f.prepares); assertEquals(0, f.sends); assertEquals(0, journal.claims)
    }

    @Test fun sourceChangeDuringJournalReadCannotOpenRetainedPayload() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var current = true
        journal.afterRead = { current = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal, { true }) { current })
        assertEquals(0, f.protectedOpens); assertEquals(0, f.prepares); assertEquals(0, journal.claims)
    }

    @Test fun sourceChangeAtFreshPreparationOrContextStageStopsBeforeClaim() = runTest {
        for (boundary in listOf("prepare", "context") + stages) {
            val f = fixture(); val journal = NamedJournal(f); var current = true
            f.after = { if (it == boundary) current = false }
            assertEquals(boundary, TitleUpdateSubmissionObservation.Unknown,
                adapter(f).submit(f.record, journal, { true }) { current })
            assertEquals(boundary, f.steps.last())
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun sourceChangeAfterCommittedClaimLeavesUnknownWithoutSending() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var current = true
        journal.afterClaim = { current = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal, { true }) { current })
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertEquals(0, f.sends); assertNull(f.journal.stored.protectedEvidence); assertFalse("get" in f.steps)
    }

    @Test fun sourceIsCheckedAfterSuspendedPostClaimSessionLookupBeforePut() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var current = true; var held = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val adapter = adapter(f, sessions = {
            if (!held && f.journal.stored.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) {
                held = true; entered.complete(Unit); release.await()
            }
            f.current
        })
        val task = async { adapter.submit(f.record, journal, { true }) { current } }
        runCurrent(); assertTrue("Post-claim session checkpoint was not reached", entered.isCompleted)
        current = false; release.complete(Unit)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, task.await())
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertEquals(0, f.sends); assertFalse("get" in f.steps)
    }

    @Test fun sourceChangeAfterSendKeepsFirstEvidenceWithoutInspection() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var current = true
        f.after = { if (it == "send") current = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal, { true }) { current })
        assertEquals(1, f.sends); assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        val retained = checkNotNull(f.journal.stored.protectedEvidence)
        val body = Json.parseToJsonElement(f.protector.open(retained)) as JsonObject
        assertEquals("{\"Code\":1001}", body.string("body")); assertEquals(JsonPrimitive(true), body["complete"])
        assertFalse("get" in f.steps); assertFalse("verify" in f.steps)
        current = true
        assertEquals(TitleUpdateSubmissionObservation.Unknown, adapter(f).submit(f.record, journal, { true }) { current })
        assertEquals(retained, f.journal.stored.protectedEvidence); assertEquals(1, f.sends)
    }

    @Test fun sourceFalseReturningAfterCancellationDoesNotBecomeUnknownNormally() = runTest {
        for (throwsAfterCancel in listOf(false, true)) {
            val f = fixture(); val journal = NamedJournal(f); var returned = false
            supervisorScope {
                val task = async {
                    adapter(f).submit(f.record, journal, { true }) {
                        currentCoroutineContext().cancel(CancellationException("Synthetic cancellation"))
                        if (throwsAfterCancel) throw IllegalStateException("Synthetic callback failure")
                        false
                    }
                    returned = true
                }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertFalse(returned); assertEquals(0, f.protectedOpens); assertEquals(0, f.sends); assertEquals(0, journal.claims)
        }
    }

    @Test fun suspendedInitialSourceCannotResumeAfterAccessRevocation() = runTest {
        val f = fixture(); val journal = NamedJournal(f); var access = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val task = async { adapter(f).submit(f.record, journal, { access }) {
            entered.complete(Unit); release.await(); true
        } }
        runCurrent(); assertTrue("Source callback was not reached", entered.isCompleted)
        access = false; release.complete(Unit)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, task.await())
        assertEquals(0, f.protectedOpens); assertEquals(0, f.sessionReads); assertEquals(0, f.sends)
    }
    private fun adapter(f: TitleSubmissionFixture, supportsGuardedFresh: Boolean = true, sessions: suspend () -> CatalogSession? = { f.sessionReads++; val value = f.current; f.afterSession(); value }): ProtonTitleUpdateSubmissionAdapter {
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
            f.protector, { sessions() }, { _, _, checkpoint ->
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
    }
}

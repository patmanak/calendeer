// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedPersonalUpdateFacadeTest {
    @Test fun preparationUsesDedicatedRuntimeAndChecksCompleteReturnedBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            if (wrong) runtime.prepared = record.copy(id = MutationId("different"))
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.prepareSubmission(record.id, record.preparation) { it == record.scope }
            if (wrong) assertNull(actual) else { assertEquals(record, actual); assertNotSame(record, actual) }
            assertEquals(listOf("prepare"), runtime.calls)
            assertFalse(requireNotNull(runtime.lease)(record.scope))
        }
    }

    @Test fun unauthenticatedAndDeniedOperationsNeverConstructOrEnterRuntime() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed(); var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareSubmission(record.id, record.preparation) { true })
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, unopened.submit(record, f.journal, { true }, { true }, { true }))
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, unopened.inspect(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
        assertEquals(0, builds)
        val runtime = Runtime(record); val authenticated = facade(runtime, StandardTestDispatcher(testScheduler)); authenticated.restore()
        assertNull(authenticated.prepareSubmission(record.id, record.preparation) { false })
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, authenticated.submit(record, f.journal, { false }, { true }, { true }))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun absentOrWrongCapabilityRefusesWithoutRuntimeFallback() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        for (format in listOf(null, TITLE_UPDATE_SUBMISSION_FORMAT)) {
            val runtime = Runtime(record).apply { personalUpdateFormat = format }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            assertNull(facade.prepareSubmission(record.id, record.preparation) { true })
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { true }, { true }, { true }))
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.inspect(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
            assertTrue(runtime.calls.isEmpty())
        }
        val absent = facade(null, StandardTestDispatcher(testScheduler)); absent.restore()
        assertNull(absent.prepareSubmission(record.id, record.preparation) { true })
    }

    @Test fun submissionKeepsEvidenceLeaseIndependentAndAllCallbacksExpireOnReturn() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed(); var allowed = true
        val runtime = Runtime(record)
        runtime.during = { phase -> if (phase == "submit") {
            val lease = requireNotNull(runtime.lease); val evidence = requireNotNull(runtime.evidenceLease)
            assertTrue(lease(record.scope)); assertTrue(evidence(record.scope))
            assertFalse(evidence(record.scope.copy(generation = record.scope.generation + 1)))
            allowed = false
            assertFalse(lease(record.scope)); assertTrue(evidence(record.scope))
            allowed = true; assertFalse(lease(record.scope))
        } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { allowed }, { true }, { true }))
        assertEquals(listOf("submit"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope)); assertFalse(requireNotNull(runtime.evidenceLease)(record.scope))
        assertFalse(requireNotNull(runtime.source)(record.scope))
    }

    @Test fun verifiedResultCannotChangeRequestOrFullPreparationBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            val returned = record.copy(id = if (wrong) MutationId("other") else record.id, state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            runtime.observation = PersonalUpdateSubmissionObservation.Verified(returned, requireNotNull(f.decoded))
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.submit(record, f.journal, { true }, { true }, { true })
            if (wrong) assertEquals(PersonalUpdateSubmissionObservation.Unknown, actual)
            else assertEquals(returned, (actual as PersonalUpdateSubmissionObservation.Verified).record)
            assertEquals(listOf("submit"), runtime.calls)
        }
    }

    @Test fun unknownInspectionRoutesOnlyToInspectionAndCannotSubmitOrPrepare() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val runtime = Runtime(record).apply { observation = PersonalUpdateSubmissionObservation.Verified(record, requireNotNull(f.decoded)) }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        val actual = facade.inspect(record, f.journal) { true }
        assertTrue(actual is PersonalUpdateSubmissionObservation.Verified); assertEquals(listOf("inspect"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope))
    }

    @Test fun logoutWaitsForSubmissionAndThenPreventsAllTextCalls() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record); val auth = Auth()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        runtime.during = { if (it == "submit") { entered.complete(Unit); release.await() } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler), auth); facade.restore()
        val work = async { facade.submit(record, f.journal, { true }, { true }, { true }) }
        runCurrent(); assertTrue(entered.isCompleted)
        val logout = async { facade.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
        release.complete(Unit); work.await(); logout.await(); assertEquals(1, auth.cancellations)
        assertNull(facade.prepareSubmission(record.id, record.preparation) { true })
        assertEquals(listOf("submit"), runtime.calls)
    }

    @Test fun runtimeSwallowingCancellationCannotReturnNullOrUnknownNormally() = runTest {
        for (phase in listOf("prepare", "submit", "inspect")) for (throws in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            var reached = false; var cancelled = 0; var ordinary = 0; var returned = 0
            runtime.during = { reached = true; currentCoroutineContext().cancel(); if (throws) throw IOException("Synthetic cancellation") }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            supervisorScope {
                val child = async {
                    try {
                        when (phase) {
                            "prepare" -> facade.prepareSubmission(record.id, record.preparation) { true }
                            "submit" -> facade.submit(record, f.journal, { true }, { true }, { true })
                            else -> facade.inspect(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true }
                        }
                        returned++
                    } catch (_: CancellationException) { cancelled++ }
                      catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertTrue("Runtime reached: $phase/$throws", reached)
            assertEquals("Cancellation: $phase/$throws", 1, cancelled)
            assertEquals("Ordinary failure: $phase/$throws", 0, ordinary)
            assertEquals("Normal return: $phase/$throws", 0, returned)
        }
    }

    @Test fun seriesPreparationPreservesProfileAndChecksExactReturnedBinding() = runTest {
        for (variant in 0..2) {
            val f = PersonalSubmissionFixture(); val (record, _) = seriesRecord(f)
            val runtime = Runtime(record).apply { seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT }
            runtime.prepared = when (variant) {
                1 -> record.copy(id = MutationId("different-series-wire"))
                2 -> record.copy(preparation = PersonalEditPreparation.forSeries(record.preparation.intent,
                    record.preparation.timestamp.plusSeconds(1), record.preparation.format, record.preparation.protectedPayload))
                else -> record
            }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.prepareSubmission(record.id, record.preparation) { it == record.scope }
            if (variant == 0) {
                assertEquals(record, actual); assertNotSame(record, actual)
                assertTrue(requireNotNull(actual).preparation.isSeries)
            } else assertNull(actual)
            assertEquals(listOf("prepare"), runtime.calls)
            assertFalse(requireNotNull(runtime.lease)(record.scope))
        }
    }

    @Test fun seriesSubmissionReturnsOnlyTheExactUnknownWireAndPreparationBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val (record, decoded) = seriesRecord(f)
            val returned = record.copy(id = if (wrong) MutationId("foreign-series-wire") else record.id,
                state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            val runtime = Runtime(record).apply {
                seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT
                observation = PersonalUpdateSubmissionObservation.Verified(returned, decoded)
            }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.submit(record, f.journal, { true }, { true }, { true })
            if (wrong) assertEquals(PersonalUpdateSubmissionObservation.Unknown, actual)
            else {
                assertTrue(actual is PersonalUpdateSubmissionObservation.Verified)
                val verified = actual as PersonalUpdateSubmissionObservation.Verified
                assertEquals(returned, verified.record); assertTrue(verified.record.preparation.isSeries)
                assertEquals(decoded.content, verified.event.content)
            }
            assertEquals(listOf("submit"), runtime.calls)
            assertFalse(requireNotNull(runtime.lease)(record.scope))
            assertFalse(requireNotNull(runtime.evidenceLease)(record.scope))
        }
    }

    @Test fun deniedOrWrongSeriesCapabilityNeverFallsBackToPersonalRuntime() = runTest {
        val f = PersonalSubmissionFixture(); val (record, _) = seriesRecord(f)
        for (format in listOf(null, PERSONAL_UPDATE_REQUEST_FORMAT, TITLE_UPDATE_SUBMISSION_FORMAT)) {
            val runtime = Runtime(record).apply { seriesUpdateFormat = format }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            assertNull(facade.prepareSubmission(record.id, record.preparation) { true })
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { true }, { true }, { true }))
            assertTrue(runtime.calls.isEmpty())
        }
        val runtime = Runtime(record).apply { seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        assertNull(facade.prepareSubmission(record.id, record.preparation) { false })
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { false }, { true }, { true }))
        assertTrue(runtime.calls.isEmpty())
        var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No runtime construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareSubmission(record.id, record.preparation) { true })
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, unopened.submit(record, f.journal, { true }, { true }, { true }))
        assertEquals(0, builds)
    }

    @Test fun seriesUnknownInspectionSurvivesDisabledSendCapabilityAndNeverSubmitsOrPrepares() = runTest {
        val f = PersonalSubmissionFixture(); val (prepared, decoded) = seriesRecord(f)
        val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val runtime = Runtime(unknown).apply {
            seriesUpdateFormat = null
            observation = PersonalUpdateSubmissionObservation.Verified(unknown, decoded)
        }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        val actual = facade.inspect(unknown, f.journal) { it == unknown.scope }
        assertTrue(actual is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(unknown, (actual as PersonalUpdateSubmissionObservation.Verified).record)
        assertEquals(listOf("inspect"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(unknown.scope))
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(unknown, f.journal, { true }, { true }, { true }))
        assertEquals(listOf("inspect"), runtime.calls)
    }

    @Test fun revokedSeriesLeaseRejectsVerifiedResultAndKeepsEvidenceIndependentUntilReturn() = runTest {
        val f = PersonalSubmissionFixture(); val (record, decoded) = seriesRecord(f); var allowed = true
        val runtime = Runtime(record).apply {
            seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT
            observation = PersonalUpdateSubmissionObservation.Verified(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), decoded)
        }
        runtime.during = { phase -> if (phase == "submit") {
            val lease = requireNotNull(runtime.lease); val evidence = requireNotNull(runtime.evidenceLease)
            assertTrue(lease(record.scope)); assertTrue(evidence(record.scope))
            allowed = false
            assertFalse(lease(record.scope)); assertTrue(evidence(record.scope))
            allowed = true; assertFalse(lease(record.scope))
        } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { allowed }, { true }, { true }))
        assertEquals(listOf("submit"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope)); assertFalse(requireNotNull(runtime.evidenceLease)(record.scope))
        assertFalse(requireNotNull(runtime.source)(record.scope))
    }

    @Test fun cancellationAcrossSeriesPreparationSubmissionAndInspectionCannotReturnNormally() = runTest {
        for (phase in listOf("prepare", "submit", "inspect")) {
            val f = PersonalSubmissionFixture(); val (record, _) = seriesRecord(f)
            val runtime = Runtime(record).apply { seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT }
            var reached = false; var cancelled = false; var returned = false
            runtime.during = { reached = true; currentCoroutineContext().cancel() }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            supervisorScope {
                val child = async {
                    try {
                        when (phase) {
                            "prepare" -> facade.prepareSubmission(record.id, record.preparation) { true }
                            "submit" -> facade.submit(record, f.journal, { true }, { true }, { true })
                            else -> facade.inspect(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true }
                        }
                        returned = true
                    } catch (_: CancellationException) { cancelled = true }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertTrue("Series runtime reached", reached)
            assertTrue("Series cancellation propagated", cancelled)
            assertFalse("Cancelled series operation cannot return", returned)
            assertEquals(listOf(phase), runtime.calls)
        }
    }

    private suspend fun seriesRecord(f: PersonalSubmissionFixture): Pair<PersonalUpdateAttemptRecord, RemoteChange.Upsert> {
        val row = JsonObject(f.originalRow + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=5")))
        val base = f.intent.base.copy(revision = row.revision(f.bootstrap),
            content = f.intent.base.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=5")))
        val after = f.intent.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=8"))
        val intent = f.intent.copy(base = base, content = after, seriesProfile = SeriesEditProfile.of(base.content, after))
        val prepared = ProtonPersonalPreparationCodec(f.protector).sealSeries(intent, "synthetic-uid", f.timestamp,
            ProtonTimestampedSharedPersonal(row, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true))
        val request = ProtonPersonalUpdateRequestCodec(f.protector).seal(MutationId("synthetic-series-wire"), prepared, f.context)
        val record = PersonalUpdateAttemptRecord(request.id, request.preparation, request.format, request.protectedPayload)
        val actual = JsonObject(f.readRow + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=8")))
        return record to base.copy(revision = actual.revision(f.bootstrap), content = after)
    }

    private class Runtime(private val record: PersonalUpdateAttemptRecord) : CalendarPersonalUpdatePort {
        override var personalUpdateFormat: AdapterFormat? = PERSONAL_UPDATE_REQUEST_FORMAT
        override var seriesUpdateFormat: AdapterFormat? = null
        val calls = mutableListOf<String>()
        var during: suspend (String) -> Unit = {}
        var prepared: PersonalUpdateAttemptRecord? = record
        var observation: PersonalUpdateSubmissionObservation = PersonalUpdateSubmissionObservation.Unknown
        var lease: ((CalendarScope) -> Boolean)? = null
        var evidenceLease: ((CalendarScope) -> Boolean)? = null
        var source: (suspend (CalendarScope) -> Boolean)? = null
        override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
            admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
            assertEquals(record.id, id); assertEquals(record.preparation.intent, preparation.intent)
            assertEquals(record.preparation.isSeries, preparation.isSeries)
            assertNotSame(record.preparation, preparation); lease = admit; calls += "prepare"; during("prepare"); return prepared
        }
        override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
            assertEquals(this.record, record); assertNotSame(this.record, record)
            lease = admit; source = qualifySource; evidenceLease = admitEvidence; calls += "submit"; during("submit"); return observation
        }
        override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
            lease = admit; calls += "inspect"; during("inspect"); return observation
        }
    }
    private class Auth : AuthenticationFlowPort {
        var cancellations = 0
        override suspend fun restore() = AuthenticationStep.Authenticated
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
        override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
        override suspend fun cancel() { cancellations++ }
    }
    private fun facade(runtime: CalendarPersonalUpdatePort?, dispatcher: CoroutineDispatcher, auth: Auth = Auth()) =
        SerializedAuthenticationPort({ ProtonAuthenticationGraph(auth, NoEvents, personalUpdates = runtime) {
            CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList())
        } }, {}, dispatcher)
    private object NoEvents : CalendarDeltaRemote {
        override val format = EVENT_FORMAT
        override val deltaFormat = CALENDAR_DELTA_FORMAT
        override suspend fun currentAccount(): AccountId? = error("No auth re-entry")
        override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        override suspend fun fetch(request: PageRequest): RemotePage = error("No fetch")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("No receive")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("No decode")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = error("No retry")
        override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = error("No feed")
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage = error("No feed")
        override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage = error("No feed")
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent = error("No GET")
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = error("No decode")
        override suspend fun refreshContext(scope: CalendarScope) = error("No context")
    }
}

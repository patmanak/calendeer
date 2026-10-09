// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protected capsules exercise preparation control flow, never crypto or service writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedTitlePreparationTest {
    private val stages = listOf("user", "addresses", "metadata", "context-bootstrap", "local-addresses")
    private fun fixture() = TitleSubmissionFixture().also { it.contextStages = stages; it.protectedOpens = 0 }
    private fun TitleSubmissionFixture.reservation() = TitleUpdatePreparationReservation.of(record)

    @Test fun admittedPreparationBindsReservedIdentityAndHistoricalCapsuleWithoutDispatch() = runTest {
        val f = fixture(); val scopes = mutableListOf<CalendarScope>()
        val actual = checkNotNull(f.adapter.prepareSubmission(f.reservation()) { scopes += it; it == f.scope })
        assertTrue(f.reservation().matches(actual))
        assertEquals(TitleUpdateAttemptState.PREPARED, actual.state)
        assertNull(actual.protectedEvidence)
        assertEquals(f.context, f.codec.open(actual).context)
        assertEquals(listOf("context") + stages, f.steps)
        assertTrue(scopes.isNotEmpty()); assertTrue(scopes.all { it == f.scope })
        assertEquals(0, f.prepares); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
    }

    @Test fun deniedAdmissionNeverReadsSessionOrOpensHistoricalCapsule() = runTest {
        val f = fixture()
        assertNull(f.adapter.prepareSubmission(f.reservation()) { false })
        assertEquals(0, f.sessionReads); assertEquals(0, f.protectedOpens); assertTrue(f.steps.isEmpty())
    }

    @Test fun revocationWhileSessionLookupIsSuspendedPreventsHistoricalOpening() = runTest {
        val f = fixture(); var admitted = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val adapter = adapter(f, sessions = {
            f.sessionReads++; entered.complete(Unit); release.await(); f.initial
        })
        val work = async { adapter.prepareSubmission(f.reservation()) { admitted } }
        runCurrent()
        assertTrue("Guarded preparation must reach the admitted session lookup", entered.isCompleted)
        admitted = false; release.complete(Unit)
        assertNull(work.await()); assertEquals(0, f.protectedOpens); assertTrue(f.steps.isEmpty())
    }

    @Test fun revocationAtEachContextStageStopsBeforeItsSuccessor() = runTest {
        val all = listOf("context") + stages
        for (boundary in all) {
            val f = fixture(); var admitted = true
            f.after = { if (it == boundary) admitted = false }
            assertNull(boundary, f.adapter.prepareSubmission(f.reservation()) { admitted })
            assertEquals(boundary, all.take(all.indexOf(boundary) + 1), f.steps)
            assertEquals(0, f.prepares); assertEquals(0, f.sends); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun sessionReplacementAtEveryContextStageRemainsTypedAuthenticationFailure() = runTest {
        val all = listOf("context") + stages
        for (boundary in all) {
            val f = fixture()
            f.after = { if (it == boundary) f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement")) }
            reason(LocalEditPreparationFailure.AUTHENTICATION) { f.adapter.prepareSubmission(f.reservation()) { true } }
            assertEquals(boundary, all.take(all.indexOf(boundary) + 1), f.steps)
        }
    }

    @Test fun revocationDuringSealCannotReturnPreparedWire() = runTest {
        val f = fixture(); var admitted = true; var seals = 0
        val protector = object : EventPayloadProtector {
            override fun open(protected: String) = f.protector.open(protected)
            override fun seal(plain: String) = f.protector.seal(plain).also { seals++; admitted = false }
        }
        assertNull(adapter(f, protector).prepareSubmission(f.reservation()) { admitted })
        assertEquals(1, seals); assertEquals(listOf("context") + stages, f.steps)
        assertEquals(f.record, f.journal.stored); assertEquals(0, f.sends)
    }

    @Test fun sessionReplacementDuringSealCannotReturnPreparedWire() = runTest {
        val f = fixture(); var seals = 0
        val protector = object : EventPayloadProtector {
            override fun open(protected: String) = f.protector.open(protected)
            override fun seal(plain: String) = f.protector.seal(plain).also {
                seals++; f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement"))
            }
        }
        reason(LocalEditPreparationFailure.AUTHENTICATION) { adapter(f, protector).prepareSubmission(f.reservation()) { true } }
        assertEquals(1, seals)
    }

    @Test fun cancelledJobAfterSealDoesNotReturnEvenWhenProtectorReturnsNormally() = runTest {
        val f = fixture(); var returned = false; var seals = 0
        lateinit var work: Deferred<Unit>
        val protector = object : EventPayloadProtector {
            override fun open(protected: String) = f.protector.open(protected)
            override fun seal(plain: String) = f.protector.seal(plain).also { seals++; work.cancel() }
        }
        work = async(start = CoroutineStart.LAZY) {
            adapter(f, protector).prepareSubmission(f.reservation()) { true }; returned = true
        }
        work.start()
        try { work.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, seals); assertFalse("The adapter returned after cancellation", returned)
    }

    @Test fun cancellationAndTypedFailuresAreNotTurnedIntoDeniedPreparation() = runTest {
        for (expected in LocalEditPreparationFailure.entries) {
            val f = fixture(); f.after = { if (it == "addresses") throw LocalEditPreparationException(expected) }
            reason(expected) { f.adapter.prepareSubmission(f.reservation()) { true } }
            assertEquals(listOf("context", "user", "addresses"), f.steps)
        }
        val f = fixture(); f.after = { if (it == "addresses") throw CancellationException("synthetic cancellation") }
        try { f.adapter.prepareSubmission(f.reservation()) { true }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(listOf("context", "user", "addresses"), f.steps)
        reason(LocalEditPreparationFailure.STORAGE) {
            fixture().let { it.adapter.prepareSubmission(it.reservation()) { throw LocalEditPreparationException(LocalEditPreparationFailure.STORAGE) } }
        }
    }

    @Test fun legacyPreparationRetainsNetworkAndStorageFailureTaxonomy() = runTest {
        val network = fixture(); network.after = { throw IOException("synthetic network failure") }
        reason(LocalEditPreparationFailure.NETWORK) { network.adapter.prepareSubmission(network.record.id, network.preparation) }
        val storage = fixture()
        val broken = object : EventPayloadProtector {
            override fun open(protected: String): String = throw ProtonTitlePreparationException(ProtonTitlePreparationFailure.STORAGE)
            override fun seal(plain: String): String = error("No seal")
        }
        reason(LocalEditPreparationFailure.STORAGE) { adapter(storage, broken).prepareSubmission(storage.record.id, storage.preparation) }
    }

    @Test fun preparationIsFrozenBeforeFirstSessionSuspension() = runTest {
        val f = fixture(); val reminders = mutableListOf<EventReminder>()
        val intent = f.intent.copy(content = f.intent.content.copy(details = EventDetails(reminders = reminders)))
        val reservation = f.reservation().copy(preparation = f.preparation.copy(intent = intent))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val adapter = adapter(f, sessions = {
            if (++reads == 1) { entered.complete(Unit); release.await() }
            f.initial
        })
        val work = async { adapter.prepareSubmission(reservation) { true } }
        runCurrent(); assertTrue("Session lookup was not reached", entered.isCompleted)
        reminders += EventReminder(10); release.complete(Unit)
        val actual = checkNotNull(work.await())
        assertTrue(f.reservation().matches(actual))
    }

    private fun adapter(f: TitleSubmissionFixture, protector: EventPayloadProtector = f.protector,
        sessions: suspend () -> CatalogSession? = { f.sessionReads++; val value = f.current; f.afterSession(); value }) =
        ProtonTitleUpdateSubmissionAdapter(CalendarLocalEditPreparationPort { _, _ -> error("No new local preparation") },
            protector, { sessions() }, { _, _, checkpoint ->
                f.step("context"); checkpoint()
                stages.forEach { checkpoint(); f.step(it); checkpoint() }
                f.freshContext
            }, object : TitleUpdateSubmissionRemote {
                override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String = error("No dispatch")
                override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject = error("No readback")
            }, object : EventImportRemote {
                override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No search")
                override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject = error("Context owns reads")
            }, CreateEventVerifier { _, _, _, _, _, _ -> error("No readback verification") })

    private suspend fun reason(expected: LocalEditPreparationFailure, block: suspend () -> Any?) {
        try { block(); fail("Expected $expected") }
        catch (failure: LocalEditPreparationException) { assertEquals(expected, failure.reason) }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdatePreparationRecoveryTest {
    private val scope = CalendarScope(AccountId("recovery-account"), CalendarId("recovery-calendar"), 2)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-09T10:00:00Z"), Instant.parse("2026-10-09T11:00:00Z"), ZoneId.of("Europe/Paris")),
        details = EventDetails())
    private val intent = LocalEditIntent(MutationId("recovery-intent"), scope, AdapterFormat("fixture-import", 1),
        RemoteChange.Upsert(EventId("recovery-event"), Revision("original"), content), content.copy(title = "Synthetic changed"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-09T12:00:00Z"),
        AdapterFormat("fixture-preparation", 1), "opaque-capsule")
    private val candidate = TitleUpdatePreparationReservation(MutationId("proposed-id"), preparation, AdapterFormat("fixture-wire", 1))
    private val canonical = candidate.copy(id = MutationId("canonical-id"))
    private val wire = TitleUpdateAttemptRecord(canonical.id, preparation, canonical.format, "first-wire")

    /** Unsupported overloads deliberately inherit the deny-by-default contract. */
    private open class LegacyJournal : TitleUpdatePreparationJournal {
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No unrestricted retention")
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? = error("No unsupported read")
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No dispatch claim")
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit = error("No evidence")
    }
    private class ReserveOnlyJournal(private val reservation: TitleUpdatePreparationReservation) : LegacyJournal() {
        var reads = 0
        override suspend fun reservePreparation(candidate: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean) = reservation.takeIf { admit(it.scope) }
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? { reads++; return null }
    }
    private class Journal(var reservation: TitleUpdatePreparationReservation?) : LegacyJournal() {
        var durable: TitleUpdateAttemptRecord? = null
        val calls = mutableListOf<String>()
        val readTargets = mutableListOf<Pair<CalendarScope, MutationId>>()
        var reserveHook: suspend () -> Unit = {}
        var readHook: suspend (Int) -> Unit = {}
        var rememberHook: suspend (TitleUpdateAttemptRecord) -> Boolean = { record ->
            if (durable == null) { durable = record; true } else durable == record
        }
        var reads = 0
        override suspend fun reservePreparation(candidate: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdatePreparationReservation? {
            calls += "reserve"; check(admit(candidate.scope)); reserveHook(); return reservation
        }
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? {
            calls += "read"; readTargets += scope to id; reads++; readHook(reads)
            return durable
        }
        override suspend fun remember(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admit(record.scope)); return rememberHook(record)
        }
    }
    private open class LegacyPort(override val titleUpdateFormat: AdapterFormat?) : CalendarTitleUpdateSubmissionPort {
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
            error("No unrestricted preparation")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
            error("No submission")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
        override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
            error("No guarded inspection")
    }
    private class Port(format: AdapterFormat?, var response: TitleUpdateAttemptRecord?) : LegacyPort(format) {
        override var titleUpdateFormat = format
        val requests = mutableListOf<TitleUpdatePreparationReservation>()
        var hook: suspend () -> Unit = {}
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
            check(admit(reservation.scope)); requests += reservation; hook(); return response
        }
    }
    private fun recovery(journal: TitleUpdatePreparationJournal, port: CalendarTitleUpdateSubmissionPort) =
        TitleUpdatePreparationRecovery(journal, port)
    private fun admitted(value: CalendarScope) = value == scope

    @Test fun offlineRecoveryReturnsExistingDurableWireWithoutPreparingAgain() = runTest {
        val journal = Journal(canonical).apply { durable = wire }
        val port = Port(candidate.format, wire).apply { hook = { error("Offline must not prepare") } }
        assertEquals(wire, recovery(journal, port).prepare(candidate, admit = ::admitted))
        assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember"))
        assertTrue(journal.readTargets.isNotEmpty()); assertTrue(journal.readTargets.all { it == (scope to canonical.id) })
        assertEquals(wire, journal.durable)
    }

    @Test fun bareOfflineReservationDoesNotOptIntoNetworkOrInventWire() = runTest {
        val journal = Journal(canonical); val port = Port(candidate.format, wire)
        assertNull(recovery(journal, port).prepare(candidate, admit = ::admitted))
        assertTrue(journal.calls.contains("reserve")); assertTrue(port.requests.isEmpty())
        assertFalse(journal.calls.contains("remember")); assertNull(journal.durable)
    }

    @Test fun onlinePreparationUsesCanonicalIdentityAndReturnsExplicitDurableReadback() = runTest {
        val journal = Journal(canonical); val port = Port(candidate.format, wire)
        assertEquals(wire, recovery(journal, port).prepare(candidate, true, ::admitted))
        assertEquals(listOf(canonical), port.requests); assertEquals(wire, journal.durable)
        assertEquals("read", journal.calls.last()); assertTrue(journal.reads >= 2)
        assertTrue(journal.readTargets.all { it == (scope to canonical.id) })
    }

    @Test fun canonicalReservationMayChangeOnlyAttemptIdNotItsCompleteBinding() = runTest {
        val alternatives = listOf(
            canonical.copy(format = canonical.format.copy(version = 2)),
            canonical.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            canonical.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            canonical.copy(preparation = preparation.copy(format = preparation.format.copy(version = 2))),
            canonical.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-owner")))),
            canonical.copy(preparation = preparation.copy(intent = intent.copy(scope = scope.copy(generation = 3)))),
            canonical.copy(preparation = preparation.copy(intent = intent.copy(base = intent.base.copy(revision = Revision("different-base"))))),
            canonical.copy(preparation = preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different title")))),
        )
        for (other in alternatives) {
            val journal = Journal(other); val port = Port(candidate.format, wire)
            assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
            assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember"))
        }
    }

    @Test fun unknownHistoryNeverPreparesOrReplacesWireEvenWithNetworkOptIn() = runTest {
        for (reserved in listOf(null, canonical)) {
            val unknown = wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "response-evidence")
            val journal = Journal(reserved).apply { durable = unknown }; val port = Port(candidate.format, wire)
            assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
            assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember")); assertEquals(unknown, journal.durable)
        }
    }

    @Test fun unsupportedGuardedPortNeverFallsBackToUnrestrictedPreparation() = runTest {
        val journal = Journal(canonical)
        assertNull(recovery(journal, LegacyPort(candidate.format)).prepare(candidate, true, ::admitted))
        assertFalse(journal.calls.contains("remember")); assertNull(journal.durable)
    }

    @Test fun unsupportedReservationOrGuardedRetentionNeverFallsBackToLegacyMethods() = runTest {
        val port = Port(candidate.format, wire)
        assertNull(recovery(LegacyJournal(), port).prepare(candidate, true, ::admitted)); assertTrue(port.requests.isEmpty())
        val journal = ReserveOnlyJournal(canonical)
        assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
        assertEquals(listOf(canonical), port.requests); assertTrue(journal.reads >= 2)
    }

    @Test fun preparedAdapterResponseRequiresExactReservationAndNoEvidence() = runTest {
        val alternatives = listOf(
            wire.copy(id = candidate.id), wire.copy(format = wire.format.copy(version = 2)),
            wire.copy(preparation = preparation.copy(protectedPayload = "other-capsule")),
            wire.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), wire.copy(protectedEvidence = "unexpected-evidence"),
        )
        for (other in alternatives) {
            val journal = Journal(canonical); val port = Port(candidate.format, other)
            assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
            assertFalse(journal.calls.contains("remember")); assertNull(journal.durable)
        }
    }

    @Test fun losingPreparationRaceReturnsFirstDurableWireRatherThanItsOwnCiphertext() = runTest {
        val winner = wire.copy(protectedPayload = "concurrent-winning-wire")
        val journal = Journal(canonical).apply { rememberHook = { durable = winner; false } }
        val port = Port(candidate.format, wire)
        assertEquals(winner, recovery(journal, port).prepare(candidate, true, ::admitted))
        assertEquals("read", journal.calls.last()); assertEquals(winner, journal.durable)
        assertEquals(listOf(canonical), port.requests)
    }

    @Test fun successfulRetentionRequiresExactWireReadbackRatherThanAnotherPayload() = runTest {
        val different = wire.copy(protectedPayload = "different-payload")
        val journal = Journal(canonical).apply { rememberHook = { durable = different; true } }
        val port = Port(candidate.format, wire)
        assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
        assertEquals("read", journal.calls.last()); assertEquals(different, journal.durable)
        assertEquals(listOf(canonical), port.requests)
    }

    @Test fun missingMismatchedUnknownOrEvidenceBearingReadbackCannotBeReportedAsPrepared() = runTest {
        val alternatives = listOf(null, wire.copy(id = MutationId("different-durable-id")),
            wire.copy(preparation = preparation.copy(protectedPayload = "different-durable-capsule")),
            wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), wire.copy(protectedEvidence = "unexpected-evidence"))
        for (other in alternatives) for (retained in listOf(false, true)) {
            val journal = Journal(canonical).apply { rememberHook = { durable = other; retained } }
            val port = Port(candidate.format, wire)
            assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
            assertEquals(other, journal.durable)
        }
    }

    @Test fun revokedEpochStopsAfterEachAwaitIncludingNullAndFinalReadback() = runTest {
        for (stage in listOf("initial", "reserve", "read", "prepare", "remember", "readback")) {
            var active = stage != "initial"
            val journal = Journal(canonical); val port = Port(candidate.format, wire)
            when (stage) {
                "reserve" -> journal.reserveHook = { active = false; journal.reservation = null }
                "read" -> journal.readHook = { active = false }
                "prepare" -> port.hook = { active = false; port.response = null }
                "remember" -> journal.rememberHook = { journal.durable = it; active = false; true }
                "readback" -> journal.readHook = { if (it > 1) active = false }
            }
            assertNull(recovery(journal, port).prepare(candidate, true) { active && admitted(it) })
            when (stage) {
                "initial" -> { assertTrue(journal.calls.isEmpty()); assertTrue(port.requests.isEmpty()) }
                "reserve", "read" -> { assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember")) }
                "prepare" -> assertFalse(journal.calls.contains("remember"))
                else -> assertEquals(wire, journal.durable)
            }
        }
    }

    @Test fun cancelledDependencyReturningNullCannotBecomeANormalRecoveryResult() = runTest {
        for (stage in listOf("reserve", "read", "prepare", "remember", "readback")) {
            val journal = Journal(canonical); val port = Port(candidate.format, wire)
            val normalReturns = AtomicInteger(); val cancellations = AtomicInteger()
            suspend fun cancelLookup() { currentCoroutineContext().cancel(CancellationException("Synthetic dependency cancellation")) }
            when (stage) {
                "reserve" -> journal.reserveHook = { cancelLookup(); journal.reservation = null }
                "read" -> journal.readHook = { cancelLookup() }
                "prepare" -> port.hook = { cancelLookup(); port.response = null }
                "remember" -> journal.rememberHook = { cancelLookup(); false }
                "readback" -> journal.readHook = { if (it > 1) { cancelLookup(); journal.durable = null } }
            }
            supervisorScope {
                val request = async {
                    try { recovery(journal, port).prepare(candidate, true, ::admitted); normalReturns.incrementAndGet() }
                    catch (cancelled: CancellationException) { cancellations.incrementAndGet(); throw cancelled }
                }
                try { request.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals("The helper must throw before a normal return", 0, normalReturns.get())
            assertEquals(1, cancellations.get())
            if (stage in listOf("reserve", "read")) assertTrue(port.requests.isEmpty())
            if (stage in listOf("reserve", "read", "prepare")) assertFalse(journal.calls.contains("remember"))
        }
    }

    @Test fun persistenceFailurePropagatesEvenWhenWireCommittedBeforeReplyWasLost() = runTest {
        val failure = IllegalStateException("Synthetic lost commit response")
        val journal = Journal(canonical).apply { rememberHook = { durable = it; throw failure } }
        val port = Port(candidate.format, wire)
        try { recovery(journal, port).prepare(candidate, true, ::admitted); fail("Storage failure must propagate") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals("remember", journal.calls.last()); assertEquals(wire, journal.durable)
        val requests = port.requests.size
        assertEquals(wire, recovery(journal, port).prepare(candidate, admit = ::admitted))
        assertEquals(requests, port.requests.size)
    }

    @Test fun unavailableOrChangedAdapterFormatCannotQualifyPreparedWire() = runTest {
        for (format in listOf(null, candidate.format.copy(version = 2))) {
            val journal = Journal(canonical); val port = Port(format, wire)
            assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
            assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember"))
        }
        val journal = Journal(canonical); val port = Port(candidate.format, wire)
        port.hook = { port.titleUpdateFormat = candidate.format.copy(version = 2) }
        assertNull(recovery(journal, port).prepare(candidate, true, ::admitted))
        assertFalse(journal.calls.contains("remember")); assertNull(journal.durable)
    }

    @Test fun offlineUntrustedDurableRecordCannotBypassFullPreparedBinding() = runTest {
        for (other in listOf(wire.copy(id = candidate.id), wire.copy(format = wire.format.copy(version = 2)),
            wire.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("other-owner")))),
            wire.copy(protectedEvidence = "unexpected-evidence"))) {
            val journal = Journal(canonical).apply { durable = other }; val port = Port(candidate.format, wire)
            assertNull(recovery(journal, port).prepare(candidate, admit = ::admitted))
            assertTrue(port.requests.isEmpty()); assertFalse(journal.calls.contains("remember")); assertEquals(other, journal.durable)
        }
    }
}

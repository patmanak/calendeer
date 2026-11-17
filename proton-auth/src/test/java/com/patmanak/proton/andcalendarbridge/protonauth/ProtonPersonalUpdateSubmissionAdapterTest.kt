// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonPersonalUpdateSubmissionAdapterTest {
    @Test fun typedTransientPreclaimPreparationReadKeepsPersonalUpdateUnsent() = runTest {
        val f = PersonalSubmissionFixture(); val prepared = f.seed()
        f.hook = { if (it == "prepare") throw LocalEditPreparationException(LocalEditPreparationFailure.NETWORK) }
        try {
            f.submit(prepared); fail("Expected preclaim read failure")
        } catch (failure: OutgoingReadUnavailableException) { assertNull(failure.cause) }
        assertEquals(prepared, f.journal.stored)
        assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
    }
    @Test fun wrongRequestFormatAndSwappedPreparationOrWireNeverClaim() = runTest {
        for (variant in 0..4) {
            val f = PersonalSubmissionFixture(); val first = f.seed()
            val second = f.record(MutationId("other-request"))
            val next = when (variant) {
                0 -> first.copy(format = TEXT_UPDATE_REQUEST_FORMAT)
                1 -> first.copy(format = PERSONAL_UPDATE_REQUEST_FORMAT.copy(version = 3))
                2 -> first.copy(preparation = f.prepare())
                3 -> first.copy(protectedPayload = second.protectedPayload)
                else -> first.copy(preparation = PersonalEditPreparation(first.preparation.intent,
                    first.preparation.timestamp.plusSeconds(1), first.preparation.format, first.preparation.protectedPayload))
            }
            f.journal.stored = next
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(next))
            assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun sourceRevocationAroundEveryPreSendStageClosesTheOperation() = runTest {
        for (stage in listOf("source", "read", "session", "prepare", "context", "claim", "claimed")) {
            val f = PersonalSubmissionFixture(); val first = f.seed()
            f.hook = { if (it == stage) f.sourceAllowed = false }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(first))
            assertEquals(0, f.count("send")); assertEquals(0, f.count("evidence"))
            if (stage !in listOf("claim", "claimed")) assertEquals(first, f.journal.stored)
            else assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        }
    }

    @Test fun exactTemporalApiMetadataAndCompleteDecodedProofAreRequired() = runTest {
        for (variant in 0..12) {
            val f = PersonalSubmissionFixture(java.time.ZoneOffset.UTC); val first = f.seed()
            val after = f.intent.content.time as EventTime.Timed
            when (variant) {
                0 -> f.readRow = JsonObject(f.readRow + ("StartTime" to JsonPrimitive(after.start.epochSecond + 1)))
                1 -> f.readRow = JsonObject(f.readRow + ("EndTime" to JsonPrimitive(after.end.epochSecond - 1)))
                2 -> f.readRow = JsonObject(f.readRow + ("StartTimezone" to JsonPrimitive("Z")))
                3 -> f.readRow = JsonObject(f.readRow + ("EndTimezone" to JsonPrimitive("Etc/UTC")))
                4 -> f.readRow = JsonObject(f.readRow + ("FullDay" to JsonPrimitive(1)))
                5 -> f.readRow = JsonObject(f.readRow - "StartTimezone")
                6 -> f.decoded = null
                7 -> f.decoded = f.decoded!!.copy(eventId = EventId("other-event"))
                8 -> f.decoded = f.decoded!!.copy(revision = f.intent.base.revision)
                9 -> f.decoded = f.decoded!!.copy(content = f.intent.content.copy(title = "Other"))
                10 -> f.decoded = f.decoded!!.copy(content = f.intent.content.copy(time = after.copy(end = after.end.plusSeconds(1))))
                11 -> f.decoded = f.decoded!!.copy(content = f.intent.content.copy(time = after.copy(zone = java.time.ZoneId.of("UTC"))))
                else -> f.decoded = f.decoded!!.copy(content = f.intent.content.copy(location = "Other"))
            }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(first))
            assertEquals(1, f.count("send")); assertNotNull(f.journal.stored!!.protectedEvidence)
            if (variant <= 5) assertEquals(0, f.count("verify"))
        }
    }

    @Test fun unknownInspectionNeverResendsAndRejectsForeignEvidenceOrChangedContext() = runTest {
        for (variant in 0..3) {
            val f = PersonalSubmissionFixture(); val first = f.seed()
            f.submit(first)
            val unknown = requireNotNull(f.journal.stored)
            f.steps.clear()
            when (variant) {
                0 -> Unit
                1 -> {
                    val payload = requireNotNull(unknown.protectedEvidence)
                    f.values[payload] = f.values.getValue(payload).replace("personal-edit-v2-evidence", "text-update-evidence")
                }
                2 -> f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("other-member")))
                3 -> f.hook = { if (it == "context") f.current = f.initial.copy(sessionId = SessionId("other-session")) }
            }
            val result = f.inspect(unknown)
            if (variant == 0) assertTrue(result is PersonalUpdateSubmissionObservation.Verified)
            else assertEquals(PersonalUpdateSubmissionObservation.Unknown, result)
            assertEquals(0, f.count("send")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("prepare"))
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(unknown))
        }
    }

    @Test fun reentrantSubmissionCanClaimTheFirstWireOnlyOnce() = runTest {
        val f = PersonalSubmissionFixture(); val first = f.seed(); var entered = false
        f.hook = { if (it == "claim" && !entered) { entered = true; f.submit(first) } }
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(first))
        assertTrue(entered); assertEquals(1, f.count("send")); assertEquals(1, f.count("claimed"))
        assertEquals(first.protectedPayload, f.journal.stored!!.protectedPayload)
    }

    @Test fun guardedPreparationSealsTheCompleteTimeRequestAndExpiresContextCheckpoint() = runTest {
        val f = PersonalSubmissionFixture()
        val record = f.adapter.prepareSubmission(MutationId("requested"), f.preparation) { it == f.scope }
        assertNotNull(record)
        assertEquals(MutationId("requested"), record!!.id); assertEquals(f.intent, record.preparation.intent)
        assertEquals(PERSONAL_UPDATE_REQUEST_FORMAT, record.format); assertEquals(PersonalUpdateAttemptState.PREPARED, record.state)
        assertNull(record.protectedEvidence)
        assertEquals(f.context, ProtonPersonalUpdateRequestCodec(f.protector).open(ProtonProtectedPersonalUpdateRequest(
            record.id, record.preparation, record.format, record.protectedPayload)).context)
        var accepted = false
        try { requireNotNull(f.retainedCheckpoint).invoke(); accepted = true } catch (_: Exception) { }
        assertFalse("A retained checkpoint cannot outlive preparation", accepted)
        assertEquals(0, f.count("send"))
    }

    @Test fun deniedPreparationDoesNotReadStorageSessionOrContext() = runTest {
        val f = PersonalSubmissionFixture(); var opens = 0
        f.storageHook = { opens++ }
        assertNull(f.adapter.prepareSubmission(MutationId("requested"), f.preparation) { false })
        assertEquals(0, opens); assertTrue(f.steps.isEmpty())
    }

    @Test fun timedUtcAndNamedZoneSubmissionsClaimOnceBeforeTransportAndPreserveRawResponse() = runTest {
        for (zone in listOf(java.time.ZoneOffset.UTC, java.time.ZoneId.of("Europe/Paris"))) {
            val f = PersonalSubmissionFixture(zone); val record = f.seed()
            val observed = f.submit(record) as? PersonalUpdateSubmissionObservation.Verified
            assertNotNull(observed); assertEquals(f.decoded, observed!!.event)
            assertEquals(f.journal.stored, observed.record)
            assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, observed.record.state)
            assertEquals(1, f.count("claim")); assertEquals(1, f.count("send")); assertEquals(1, f.count("evidence"))
            assertTrue(f.steps.indexOf("claimed") < f.steps.indexOf("send"))
            assertTrue(f.steps.indexOf("evidence") < f.steps.indexOf("get"))
            val evidence = Json.parseToJsonElement(f.protector.open(requireNotNull(f.journal.stored!!.protectedEvidence))) as JsonObject
            assertEquals("personal-edit-v2-evidence", evidence.string("kind")); assertEquals(2L, evidence.number("version"))
            assertEquals(f.response, evidence.string("body"))
            assertEquals(f.intent.content, observed.event.content)
            assertNotEquals(f.intent.base.revision, observed.event.revision)
        }
    }

    @Test fun freshRandomProtectedPreparationIsNotComparedByCiphertext() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        f.fresh = f.prepare()
        assertNotEquals(record.preparation.protectedPayload, f.fresh!!.protectedPayload)
        assertTrue(f.submit(record) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(1, f.count("send"))
    }

    @Test fun sourceOrAdmissionDenialNeverClaimsAndCannotFallBack() = runTest {
        for (sourceDenied in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            if (sourceDenied) f.sourceAllowed = false else f.allowed = false
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(record, f.journal.stored); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun missingOrSubstitutedDurableWorkCannotEnterFreshPreparationOrClaim() = runTest {
        for (variant in 0..3) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            f.journal.stored = when (variant) {
                0 -> null
                1 -> record.copy(protectedPayload = "another-request")
                2 -> record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
                else -> record.copy(id = MutationId("another-id"))
            }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(0, f.count("prepare")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun changedSessionContextOrCompleteOriginalStopsBeforeClaim() = runTest {
        for (variant in 0..2) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            when (variant) {
                0 -> f.hook = { if (it == "prepare") f.current = f.initial.copy(sessionId = SessionId("other-session")) }
                1 -> f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("other-member")))
                2 -> f.fresh = f.prepare(ProtonTimestampedSharedPersonal(
                    JsonObject(f.originalRow + ("ModifyTime" to JsonPrimitive(3))),
                    f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true))
            }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(record, f.journal.stored); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun revocationAfterClaimLeavesUnknownWithoutSendingOrResetting() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "claimed") f.sourceAllowed = false }
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertEquals(0, f.count("send")); assertNull(f.journal.stored!!.protectedEvidence)
        f.hook = {}; f.sourceAllowed = true
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record)); assertEquals(1, f.count("claim"))
    }

    @Test fun modeRevocationAfterPutStillRetainsResponseUnderSeparateBoundedLease() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "send") f.allowed = false }
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
        assertEquals(1, f.count("send")); assertEquals(1, f.count("evidence")); assertEquals(0, f.count("get"))
        assertNotNull(f.journal.stored!!.protectedEvidence)
        assertFalse(requireNotNull(f.journal.evidenceLease)(f.scope))
        assertFalse(requireNotNull(f.retainedPreparationLease)(f.scope))
        assertFalse(requireNotNull(f.journal.readLease)(f.scope))
    }

    @Test fun responseCannotAttachToSubstitutedUnknownAndDeniedEvidenceNeverInventsSuccess() = runTest {
        for (substitution in listOf(false, true)) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            f.hook = { if (it == "send") {
                if (substitution) f.journal.stored = f.journal.stored!!.copy(protectedPayload = "substituted")
                else f.evidenceAllowed = false
            } }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(1, f.count("send")); assertEquals(0, f.count("evidence")); assertEquals(0, f.count("get"))
            assertNull(f.journal.stored!!.protectedEvidence)
        }
    }

    @Test fun lostResponseClaimFailureAndEvidenceFailureNeverRetryTransport() = runTest {
        for (variant in 0..2) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            when (variant) {
                0 -> f.sendFailure = IOException("Synthetic response loss")
                1 -> f.journal.claimFailure = IOException("Synthetic commit failure")
                2 -> f.journal.evidenceFailure = IOException("Synthetic evidence failure")
            }
            f.submit(record)
            if (variant == 1) { assertEquals(record, f.journal.stored); assertEquals(0, f.count("send")) }
            else {
                assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
                f.submit(record); assertEquals(1, f.count("send")); assertEquals(1, f.count("claim"))
            }
        }
    }

    @Test fun cancellationSwallowedByEverySuspendingStageRemainsCancellationInsideChild() = runTest {
        for (stage in listOf("session", "read", "source", "prepare", "context", "claimed", "send", "evidence"))
            for (ordinaryFailure in listOf(false, true)) {
                val f = PersonalSubmissionFixture(); val record = f.seed()
                var reached = false; var cancelled = 0; var ordinary = 0; var returned = 0
                f.hook = { if (it == stage && !reached) {
                    reached = true; currentCoroutineContext().cancel()
                    if (ordinaryFailure) throw IOException("Synthetic cancelled dependency")
                } }
                supervisorScope {
                    val child = async {
                        try { f.submit(record); returned++ }
                        catch (_: CancellationException) { cancelled++ }
                        catch (_: Exception) { ordinary++ }
                    }
                    try { child.await() } catch (_: CancellationException) { }
                }
                assertTrue("Expected stage reached: $stage", reached)
                assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
                if (stage !in listOf("send", "evidence")) assertEquals(0, f.count("send"))
            }
    }
}

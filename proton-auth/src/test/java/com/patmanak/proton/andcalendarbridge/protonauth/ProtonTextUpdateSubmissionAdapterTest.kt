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

class ProtonTextUpdateSubmissionAdapterTest {
    @Test fun guardedPreparationSealsTheCompleteTextRequestAndExpiresContextCheckpoint() = runTest {
        val f = TextSubmissionFixture()
        val record = f.adapter.prepareSubmission(MutationId("requested"), f.preparation) { it == f.scope }
        assertNotNull(record)
        assertEquals(MutationId("requested"), record!!.id); assertEquals(f.intent, record.preparation.intent)
        assertEquals(TEXT_UPDATE_REQUEST_FORMAT, record.format); assertEquals(TextUpdateAttemptState.PREPARED, record.state)
        assertNull(record.protectedEvidence)
        assertEquals(f.context, ProtonTextUpdateRequestCodec(f.protector).open(ProtonProtectedTextUpdateRequest(
            record.id, record.preparation, record.format, record.protectedPayload)).context)
        var accepted = false
        try { requireNotNull(f.retainedCheckpoint).invoke(); accepted = true } catch (_: Exception) { }
        assertFalse("A retained checkpoint cannot outlive preparation", accepted)
        assertEquals(0, f.count("send"))
    }

    @Test fun deniedPreparationDoesNotReadStorageSessionOrContext() = runTest {
        val f = TextSubmissionFixture(); var opens = 0
        f.storageHook = { opens++ }
        assertNull(f.adapter.prepareSubmission(MutationId("requested"), f.preparation) { false })
        assertEquals(0, opens); assertTrue(f.steps.isEmpty())
    }

    @Test fun timedAndAllDayFullTextSubmissionsClaimOnceBeforeTransportAndPreserveRawResponse() = runTest {
        for (timed in listOf(false, true)) {
            val f = TextSubmissionFixture(timed); val record = f.seed()
            val observed = f.submit(record) as? TextUpdateSubmissionObservation.Verified
            assertNotNull(observed); assertEquals(f.decoded, observed!!.event)
            assertEquals(f.journal.stored, observed.record)
            assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, observed.record.state)
            assertEquals(1, f.count("claim")); assertEquals(1, f.count("send")); assertEquals(1, f.count("evidence"))
            assertTrue(f.steps.indexOf("claimed") < f.steps.indexOf("send"))
            assertTrue(f.steps.indexOf("evidence") < f.steps.indexOf("get"))
            val evidence = Json.parseToJsonElement(f.protector.open(requireNotNull(f.journal.stored!!.protectedEvidence))) as JsonObject
            assertEquals("text-update-evidence", evidence.string("kind")); assertEquals(1L, evidence.number("version"))
            assertEquals(f.response, evidence.string("body"))
            assertEquals("Changed\r\nnotes", observed.record.preparation.intent.content.description)
            assertEquals("Changed\nnotes", observed.event.content.description)
        }
    }

    @Test fun freshRandomProtectedPreparationIsNotComparedByCiphertext() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed()
        f.fresh = f.prepare()
        assertNotEquals(record.preparation.protectedPayload, f.fresh!!.protectedPayload)
        assertTrue(f.submit(record) is TextUpdateSubmissionObservation.Verified)
        assertEquals(1, f.count("send"))
    }

    @Test fun sourceOrAdmissionDenialNeverClaimsAndCannotFallBack() = runTest {
        for (sourceDenied in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed()
            if (sourceDenied) f.sourceAllowed = false else f.allowed = false
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(record, f.journal.stored); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun missingOrSubstitutedDurableWorkCannotEnterFreshPreparationOrClaim() = runTest {
        for (variant in 0..3) {
            val f = TextSubmissionFixture(); val record = f.seed()
            f.journal.stored = when (variant) {
                0 -> null
                1 -> record.copy(protectedPayload = "another-request")
                2 -> record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
                else -> record.copy(id = MutationId("another-id"))
            }
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(0, f.count("prepare")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun changedSessionContextOrCompleteOriginalStopsBeforeClaim() = runTest {
        for (variant in 0..2) {
            val f = TextSubmissionFixture(); val record = f.seed()
            when (variant) {
                0 -> f.hook = { if (it == "prepare") f.current = f.initial.copy(sessionId = SessionId("other-session")) }
                1 -> f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("other-member")))
                2 -> f.fresh = f.prepare(ProtonTimestampedSharedText(
                    JsonObject(f.originalRow + ("ModifyTime" to JsonPrimitive(3))),
                    f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true))
            }
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(record, f.journal.stored); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun revocationAfterClaimLeavesUnknownWithoutSendingOrResetting() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "claimed") f.sourceAllowed = false }
        assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertEquals(0, f.count("send")); assertNull(f.journal.stored!!.protectedEvidence)
        f.hook = {}; f.sourceAllowed = true
        assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record)); assertEquals(1, f.count("claim"))
    }

    @Test fun modeRevocationAfterPutStillRetainsResponseUnderSeparateBoundedLease() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "send") f.allowed = false }
        assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
        assertEquals(1, f.count("send")); assertEquals(1, f.count("evidence")); assertEquals(0, f.count("get"))
        assertNotNull(f.journal.stored!!.protectedEvidence)
        assertFalse(requireNotNull(f.journal.evidenceLease)(f.scope))
        assertFalse(requireNotNull(f.retainedPreparationLease)(f.scope))
        assertFalse(requireNotNull(f.journal.readLease)(f.scope))
    }

    @Test fun responseCannotAttachToSubstitutedUnknownAndDeniedEvidenceNeverInventsSuccess() = runTest {
        for (substitution in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed()
            f.hook = { if (it == "send") {
                if (substitution) f.journal.stored = f.journal.stored!!.copy(protectedPayload = "substituted")
                else f.evidenceAllowed = false
            } }
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.submit(record))
            assertEquals(1, f.count("send")); assertEquals(0, f.count("evidence")); assertEquals(0, f.count("get"))
            assertNull(f.journal.stored!!.protectedEvidence)
        }
    }

    @Test fun lostResponseClaimFailureAndEvidenceFailureNeverRetryTransport() = runTest {
        for (variant in 0..2) {
            val f = TextSubmissionFixture(); val record = f.seed()
            when (variant) {
                0 -> f.sendFailure = IOException("Synthetic response loss")
                1 -> f.journal.claimFailure = IOException("Synthetic commit failure")
                2 -> f.journal.evidenceFailure = IOException("Synthetic evidence failure")
            }
            f.submit(record)
            if (variant == 1) { assertEquals(record, f.journal.stored); assertEquals(0, f.count("send")) }
            else {
                assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
                f.submit(record); assertEquals(1, f.count("send")); assertEquals(1, f.count("claim"))
            }
        }
    }

    @Test fun cancellationSwallowedByEverySuspendingStageRemainsCancellationInsideChild() = runTest {
        for (stage in listOf("session", "read", "source", "prepare", "context", "claimed", "send", "evidence"))
            for (ordinaryFailure in listOf(false, true)) {
                val f = TextSubmissionFixture(); val record = f.seed()
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

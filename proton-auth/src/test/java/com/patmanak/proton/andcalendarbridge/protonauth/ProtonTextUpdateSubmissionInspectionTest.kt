// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonTextUpdateSubmissionInspectionTest {
    @Test fun unknownWithoutResponseCanBeVerifiedByExactAuthenticatedReadbackOnly() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        f.journal.stored = record
        val actual = f.inspect(record) as? TextUpdateSubmissionObservation.Verified
        assertNotNull(actual); assertEquals(record, actual!!.record); assertEquals(f.decoded, actual.event)
        assertEquals(1, f.count("get")); assertEquals(1, f.count("verify"))
        assertEquals(0, f.count("claim")); assertEquals(0, f.count("send")); assertEquals(0, f.count("prepare"))
    }

    @Test fun preparedMissingAndSubstitutedHistoryCannotReadRemoteEvent() = runTest {
        for (variant in 0..2) {
            val f = TextSubmissionFixture(); val prepared = f.seed()
            val record = if (variant == 0) prepared else prepared.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            f.journal.stored = if (variant == 1) null else prepared
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.inspect(record))
            assertEquals(0, f.count("get")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
        }
    }

    @Test fun equalTextWithWrongSignatureMetadataRevisionOrFullContentDoesNotVerify() = runTest {
        for (variant in 0..4) {
            val f = TextSubmissionFixture(); val record = f.seed().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            f.journal.stored = record
            when (variant) {
                0 -> f.readRow = JsonObject(f.readRow + ("SharedKeyPacket" to JsonPrimitive("different")))
                1 -> f.readRow = JsonObject(f.readRow + ("SharedEvents" to JsonArray(listOf(f.fragments.clearSharedPart,
                    JsonObject(f.fragments.encryptedSharedPart + ("Signature" to JsonPrimitive("different")))))))
                2 -> f.decoded = f.decoded!!.copy(revision = f.intent.base.revision)
                3 -> f.decoded = f.decoded!!.copy(content = f.decoded!!.content.copy(location = "Different"))
                4 -> f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("different")))
            }
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.inspect(record))
            assertEquals(0, f.count("send")); assertEquals(0, f.count("claim"))
        }
    }

    @Test fun unrelatedOrMalformedProtectedEvidenceCannotBeReinterpretedAsTextEvidence() = runTest {
        val f = TextSubmissionFixture(); val prepared = f.seed()
        for (evidence in listOf("not-json", "{\"kind\":\"title-update-evidence\",\"version\":1}")) {
            val record = prepared.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = f.protector.seal(evidence))
            f.journal.stored = record
            assertEquals(TextUpdateSubmissionObservation.Unknown, f.inspect(record)); assertEquals(0, f.count("get"))
        }
    }

    @Test fun inspectionRevocationAfterGetStopsCryptoAndExpiresCallbacks() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        f.journal.stored = record; f.hook = { if (it == "get") f.allowed = false }
        assertEquals(TextUpdateSubmissionObservation.Unknown, f.inspect(record))
        assertEquals(1, f.count("get")); assertEquals(0, f.count("verify"))
        f.allowed = true; assertFalse(requireNotNull(f.journal.readLease)(f.scope))
    }

    @Test fun inspectionCancellationAfterReturnedValueOrOrdinaryFailureCannotBecomeUnknown() = runTest {
        for (stage in listOf("read", "context", "get", "bootstrap", "verify")) for (throws in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            f.journal.stored = record
            var reached = false; var cancelled = 0; var ordinary = 0; var returned = 0
            f.hook = { if (it == stage && !reached) {
                reached = true; currentCoroutineContext().cancel(); if (throws) throw IOException("Synthetic cancellation")
            } }
            supervisorScope {
                val child = async {
                    try { f.inspect(record); returned++ }
                    catch (_: CancellationException) { cancelled++ }
                    catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertTrue("Expected stage reached: $stage", reached)
            assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
        }
    }
}

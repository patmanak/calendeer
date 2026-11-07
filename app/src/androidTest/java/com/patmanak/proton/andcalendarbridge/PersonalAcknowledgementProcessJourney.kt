// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract.Events
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue

/** Isolated coordinator checkpoint, not a production WorkManager interruption.
 * The host owns process death: READY is an intermediate status, never a test result.
 * No timeout, cancellation injection, self-kill, request reset or resend stands in for death.
 * All identities/content stay in the fixture's private preferences; status is closed evidence.
 */
internal class PersonalAcknowledgementProcessJourney private constructor(
    private val prefs: SharedPreferences, val enabled: Boolean
) {
    suspend fun awaitHostDeath(
        receipt: PersonalUpdateAcknowledgementReceipt, retained: PersonalUpdateAcknowledgementRecord,
        request: PersonalUpdateAttemptRecord, result: PersonalUpdateVerifiedResult,
        native: Map<String, String?>, expectedNative: Map<String, String?>,
        noNativeWriteOrCompletion: Boolean, noTransport: Boolean
    ): Nothing {
        assertTrue("Require a fresh durable pending receipt before the native delegate",
            enabled && !prefs.contains("process_checkpoint_pid") &&
                retained.state == PersonalUpdateAcknowledgementState.PENDING && retained.receipt == receipt &&
                receipt.matches(result) && request.copy(protectedEvidence = null) == result.record &&
                noNativeWriteOrCompletion && noTransport && native == expectedNative &&
                native[Events.DIRTY] == "1" &&
                native[Events.SYNC_DATA3] != "calendeer-projection-receipt-v1:${receipt.projection.nonce}" &&
                requestHash(request) == prefs.getString("attempt_hash", null) &&
                resultHash(result) == prefs.getString("result_hash", null))
        assertTrue("Persist the exact original pending checkpoint synchronously", prefs.edit()
            .putInt("process_checkpoint_pid", Process.myPid())
            .putString("process_checkpoint_nonce", receipt.projection.nonce)
            .putString("process_checkpoint_receipt_hash", receiptHash(receipt))
            .putString("process_checkpoint_request_hash", requestHash(request))
            .putString("process_checkpoint_result_hash", resultHash(result))
            .putString("process_checkpoint_native_hash", nativeHash(native)).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("personal_guarded_before_native_ack_ready", true)
            putBoolean("personal_guarded_pending_receipt_durable", true)
            putBoolean("personal_guarded_checkpoint_no_transport", true)
            putInt("personal_guarded_checkpoint_pid", Process.myPid())
        })
        awaitCancellation()
    }

    fun verifyRestart(retained: PersonalUpdateAcknowledgementRecord, request: PersonalUpdateAttemptRecord,
        result: PersonalUpdateVerifiedResult, native: Map<String, String?>, noCompletion: Boolean) {
        verifyProcess()
        assertTrue("Restart retains the original pending receipt, nonce, full request/result and untouched dirty row",
            retained.state == PersonalUpdateAcknowledgementState.PENDING && noCompletion &&
                retained.receipt.matches(result) &&
                retained.receipt.projection.nonce == prefs.getString("process_checkpoint_nonce", null) &&
                receiptHash(retained.receipt) == prefs.getString("process_checkpoint_receipt_hash", null) &&
                requestHash(request) == prefs.getString("process_checkpoint_request_hash", null) &&
                resultHash(result) == prefs.getString("process_checkpoint_result_hash", null) &&
                nativeHash(native) == prefs.getString("process_checkpoint_native_hash", null) && native[Events.DIRTY] == "1")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("personal_guarded_original_pending_after_process_death", true)
        })
    }

    fun verifyProcess() {
        assertTrue("Recovery requires the host-terminated checkpoint process to differ",
            enabled && prefs.getInt("process_checkpoint_pid", -1) > 0 &&
                prefs.getInt("process_checkpoint_pid", -1) != Process.myPid())
    }

    fun verifyCompleted(retained: PersonalUpdateAcknowledgementRecord, request: PersonalUpdateAttemptRecord,
        result: PersonalUpdateVerifiedResult, noTransport: Boolean) {
        verifyProcess()
        assertTrue("Completion retains the checkpoint's original nonce and full immutable history without transport",
            retained.state == PersonalUpdateAcknowledgementState.RESTORED && retained.receipt.matches(result) &&
                retained.receipt.projection.nonce == prefs.getString("process_checkpoint_nonce", null) &&
                receiptHash(retained.receipt) == prefs.getString("process_checkpoint_receipt_hash", null) &&
                requestHash(request) == prefs.getString("process_checkpoint_request_hash", null) &&
                resultHash(result) == prefs.getString("process_checkpoint_result_hash", null) && noTransport)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("personal_guarded_process_checkpoint_same_nonce_completed", true)
            putBoolean("personal_guarded_checkpoint_no_transport", true)
        })
    }

    companion object {
        fun enroll(prefs: SharedPreferences, args: Bundle, phase: String): PersonalAcknowledgementProcessJourney {
            val scenario = args.getString("personalGuardedScenario").orEmpty()
            assertTrue("Only the explicit closed process-death scenario is supported",
                scenario.isEmpty() || scenario == "BEFORE_NATIVE_ACK")
            if (prefs.contains("token")) {
                assertTrue("Never enable or change the scenario on an existing token",
                    prefs.getString("process_scenario", "") == scenario)
            } else {
                assertTrue("Only a new submit token may freeze this scenario", phase == "submit")
                assertTrue(prefs.edit().putString("process_scenario", scenario).commit())
            }
            assertTrue("Interrupt requires explicit frozen process-death opt-in",
                phase != "interrupt" || scenario == "BEFORE_NATIVE_ACK")
            assertTrue("The one-shot interrupt cannot be replayed or replace completed history",
                phase != "interrupt" || (!prefs.contains("process_checkpoint_pid") && !prefs.getBoolean("ack_completed", false)))
            return PersonalAcknowledgementProcessJourney(prefs, scenario == "BEFORE_NATIVE_ACK")
        }
        private fun requestHash(value: PersonalUpdateAttemptRecord) = hash(PersonalUpdateAttemptStorageCodec.encode(value))
        private fun resultHash(value: PersonalUpdateVerifiedResult) = hash(PersonalUpdateProofStorageCodec.encodeResult(value))
        private fun receiptHash(value: PersonalUpdateAcknowledgementReceipt) =
            hash(PersonalUpdateAcknowledgementCodec.encode(value).toByteArray(Charsets.UTF_8))
        private fun nativeHash(value: Map<String, String?>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            value.toSortedMap().forEach { (key, text) ->
                listOf(key, text).forEach { field ->
                    val bytes = field?.toByteArray(Charsets.UTF_8)
                    digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                    bytes?.let { digest.update(it) }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

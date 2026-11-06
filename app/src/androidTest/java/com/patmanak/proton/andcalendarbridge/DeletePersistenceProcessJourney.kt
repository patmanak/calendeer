// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract.Events
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Real durable boundaries only. READY must be paired with host-observed runner/PID death.
 * No request, protected response or proof capsule is copied to preferences or status output.
 */
internal class DeletePersistenceProcessJourney(
    private val prefs: SharedPreferences, private val phase: String,
    private val source: CalendarScope, private val event: EventId, private val content: EventContent,
    private val store: ImportStore, private val delegate: DeleteRecoveryJournal,
    private val factual: (CalendarScope) -> Boolean, private val allowStore: () -> Unit,
    private val values: () -> Map<String, String?>, private val writes: () -> Int,
    private val inspect: suspend (DeleteAcknowledgementReceipt) -> AppliedDeleteAcknowledgement?,
    private val productionBusy: () -> Boolean, private val quiet: () -> Unit,
) {
    private val proofScenario get() = frozen("scenario") == "PROOF_PROCESS_INTERRUPTION"
    private var beforeAck: Map<String, String?>? = null
    val journal = object : DeleteRecoveryJournal by delegate {
        override suspend fun retainVerified(result: DeleteVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            val retained = delegate.retainVerified(result, admit)
            if (retained && proofScenario && phase == "SEND") callback {
                check(!prefs.getBoolean("submit_completed", false) && writes() == 0)
                val (record, receipt) = pending(result)
                val row = values()
                check(row[Events.DIRTY] == "1" && row[Events.DELETED] == "1")
                check(prefs.getString("persistence_send_native_hash", null) == nativeHash(row))
                stop("proof", record, result, receipt, row)
            }
            return retained
        }
    }

    suspend fun beforeSend() {
        awaitQuiet()
        check(phase == "SEND" && !prefs.contains("persistence_send_native_hash"))
        check(prefs.edit().putString("persistence_send_native_hash", nativeHash(values())).commit())
    }

    private suspend fun pending(result: DeleteVerifiedResult): Pair<DeleteAttemptRecord, DeleteAcknowledgementReceipt> {
        allowStore()
        val record = checkNotNull(delegate.read(source, MutationId(frozen("attempt_id")), factual))
        val ack = checkNotNull(store.deleteAcknowledgement(source, event, record.id))
        check(prefs.getBoolean("delete_requested", false) && !prefs.getBoolean("ack_completed", false))
        check(record.state == DeleteAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
            record.scope == source && record.eventId == event && record.intent.id.value == frozen("intent_id") &&
            record.id == DeleteAttemptIdentity.requestId(record.intent, record.format) &&
            record.intent.content == content && record.intent.base.content == content &&
            record.intent.state == LocalEditState.PAUSED && store.localDelete(source, event) == record.intent &&
            store.projectedBaseline(source, event) == record.intent.base &&
            store.transaction(source) { remote(event) } == record.intent.base)
        check(result.record == record.copy(protectedEvidence = null) &&
            delegate.readVerified(source, record.id, factual) == result &&
            delegate.readCompletion(source, record.id, factual) == null &&
            ack.state == DeleteAcknowledgementState.PENDING && ack.receipt.intent == record.intent &&
            ack.receipt.attempt == DeleteAttemptIdentity.of(record) &&
            ack.receipt.matches(DeleteSubmissionObservation.Verified(result.record)))
        return record to ack.receipt
    }

    suspend fun afterNativeAck(receipt: DeleteAcknowledgementReceipt) {
        if (proofScenario || phase != "ACK") return
        callback {
            check(prefs.getBoolean("submit_completed", false) && writes() == 1)
            val result = checkNotNull(delegate.readVerified(source, receipt.attempt.id, factual))
            val (record, pendingReceipt) = pending(result)
            check(pendingReceipt == receipt && inspect(receipt)?.receipt == receipt)
            val row = values()
            check(row[Events.DIRTY] == "0" && row[Events.DELETED] == "1" &&
                (checkNotNull(beforeAck) - ACK_FIELDS) == (row - ACK_FIELDS) && row[Events.MUTATORS] == null)
            stop("ack", record, result, receipt, row)
        }
    }

    fun proofCheckpointReady() = proofScenario && prefs.getBoolean("proof_stop_ready", false)

    suspend fun beforeRecovery(phase: String, noTraffic: Boolean) {
        check(noTraffic)
        allowStore()
        val record = checkNotNull(delegate.read(source, MutationId(frozen("attempt_id")), factual))
        val result = checkNotNull(delegate.readVerified(source, record.id, factual))
        check(frozen("attempt_hash") == hash(DeleteAttemptStorageCodec.encode(record)) &&
            frozen("result_hash") == hash(DeleteProofStorageCodec.encodeResult(result)))
        if (phase == "REPLAY") {
            check(prefs.getBoolean(if (proofScenario) "proof_recovered" else "ack_recovered", false))
            val completion = checkNotNull(delegate.readCompletion(source, record.id, factual))
            check(frozen("persistence_completion_hash") == hash(DeleteProofStorageCodec.encodeCompletion(completion)))
            return
        }
        if (!proofScenario && phase == "ACK") {
            check(!prefs.contains("ack_stop_ready"))
            pending(result)
            beforeAck = values()
            check(beforeAck!![Events.DIRTY] == "1" && beforeAck!![Events.DELETED] == "1")
            return
        }
        check(phase == if (proofScenario) "ACK" else "ACK_RECOVER")
        val kind = if (proofScenario) "proof" else "ack"
        check(prefs.getBoolean("${kind}_stop_ready", false) && prefs.getInt("${kind}_stop_pid", -1) > 0 &&
            prefs.getInt("${kind}_stop_pid", -1) != Process.myPid())
        val (_, receipt) = pending(result)
        check(receipt.nonce == frozen("${kind}_stop_nonce") && record.format.id == frozen("${kind}_stop_format_id") &&
            record.format.version == prefs.getInt("${kind}_stop_format_version", -1) &&
            frozen("${kind}_stop_native_hash") == nativeHash(values()))
        if (!proofScenario) check(inspect(receipt)?.receipt == receipt &&
            values()[Events.DIRTY] == "0" && values()[Events.DELETED] == "1")
    }

    fun recovered(phase: String, completion: DeleteCompletion) {
        val kind = if (proofScenario) "proof" else "ack"
        check(phase == if (proofScenario) "ACK" else "ACK_RECOVER")
        check(!prefs.contains("${kind}_recovered"))
        check(!prefs.contains("persistence_completion_hash"))
        check(prefs.edit().putBoolean("${kind}_recovered", true)
            .putString("persistence_completion_hash", hash(DeleteProofStorageCodec.encodeCompletion(completion))).commit())
        status("guarded_delete_${kind}_recovered")
    }

    private suspend fun stop(kind: String, record: DeleteAttemptRecord, result: DeleteVerifiedResult,
        receipt: DeleteAcknowledgementReceipt, row: Map<String, String?>): Nothing {
        check(!prefs.contains("${kind}_stop_ready") && !prefs.contains("${kind}_stop_pid"))
        val requestHash = hash(DeleteAttemptStorageCodec.encode(record))
        val resultHash = hash(DeleteProofStorageCodec.encodeResult(result))
        check(!prefs.contains("attempt_hash") || frozen("attempt_hash") == requestHash)
        check(!prefs.contains("result_hash") || frozen("result_hash") == resultHash)
        awaitQuiet()
        check(values() == row && pending(result) == (record to receipt))
        check(prefs.edit().putString("attempt_hash", requestHash).putString("result_hash", resultHash)
            .putString("${kind}_stop_nonce", receipt.nonce).putString("${kind}_stop_format_id", record.format.id)
            .putInt("${kind}_stop_format_version", record.format.version).putInt("${kind}_stop_pid", Process.myPid())
            .putString("${kind}_stop_native_hash", nativeHash(row)).putBoolean("${kind}_stop_ready", true).commit())
        status("guarded_delete_${kind}_stop_ready")
        Process.killProcess(Process.myPid())
        error("Process kill unexpectedly returned")
    }

    private suspend fun awaitQuiet() {
        // Read-only production maintenance also publishes busy. Wait before claiming or
        // killing; never weaken the account, mode or complete binding checks afterward.
        withTimeout(60_000) { while (productionBusy()) delay(50) }
        quiet()
    }

    // JNI/SDK callbacks must fail with an Exception, never escape as AssertionError.
    private suspend fun <T> callback(action: suspend () -> T): T = try { action() }
        catch (failure: AssertionError) { throw IllegalStateException("Persistence checkpoint validation failed", failure) }

    private fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
    private fun status(name: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putBoolean(name, true) })

    companion object {
        fun isScenario(scenario: String) = scenario in setOf("PROOF_PROCESS_INTERRUPTION", "ACK_PROCESS_INTERRUPTION")
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA3, Events.MUTATORS)
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun nativeHash(row: Map<String, String?>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            row.toSortedMap().forEach { (key, value) ->
                listOf(key, if (value == null) "NULL" else "VALUE", value.orEmpty()).forEach {
                    val bytes = it.toByteArray(Charsets.UTF_8)
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

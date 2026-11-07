// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.SharedPreferences
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.DeleteResponseLossObservation
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonDeleteInspectionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import android.os.Process
import java.security.MessageDigest
import org.junit.Assert.assertTrue

/** Actual discarded response is a transport observation, never acceptance or an ACK.
 * Local recovery reads immutable history only. Fresh authenticated absence is separate.
 */
internal class DeleteResponseLossJourney(
    private val prefs: SharedPreferences, private val scope: CalendarScope, private val event: EventId,
    private val store: ImportStore, private val journal: DeleteRecoveryJournal,
    private val factual: (CalendarScope) -> Boolean, private val allowStore: () -> Unit,
    private val values: () -> Map<String, String?>, private val nativeHash: (Map<String, String?>) -> String,
    private val writes: () -> Int, private val historyCount: () -> Int,
) {
    private fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
    private fun hash(record: DeleteAttemptRecord) = MessageDigest.getInstance("SHA-256")
        .digest(DeleteAttemptStorageCodec.encode(record)).joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun retainSend(local: LocalDeleteIntent, observation: DeleteResponseLossObservation?,
        verifiedReturned: Boolean, wakes: Int, dirty: Map<String, String?>) {
        val unknown = checkNotNull(journal.read(scope, MutationId(frozen("attempt_id")), factual))
        allowStore()
        val receipt = checkNotNull(store.deleteAcknowledgement(scope, event, unknown.id))
        assertTrue("Drop only the enrolled actual response without claiming acceptance",
            observation?.measuredLoss == true && prefs.getBoolean("response_loss_admission_closed", false) &&
                !verifiedReturned && wakes == 0 &&
                writes() == 0 && values() == dirty && unknown.scope == scope && unknown.eventId == event &&
                unknown.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                unknown.id == DeleteAttemptIdentity.requestId(unknown.intent, unknown.format) &&
                unknown.intent == local && ProtonDeleteInspectionProbe.localEvidence(unknown) == "NO_RESPONSE" &&
                journal.readVerified(scope, unknown.id, factual) == null &&
                journal.readCompletion(scope, unknown.id, factual) == null &&
                receipt.state == DeleteAcknowledgementState.PENDING && receipt.receipt.intent == local && historyCount() == 1)
        assertTrue(prefs.edit().putBoolean("response_loss_dropped", true).putInt("response_loss_pid", Process.myPid())
            .putString("response_loss_record_hash", hash(unknown)).putString("response_loss_native_hash", nativeHash(dirty))
            .putString("response_loss_nonce", receipt.receipt.nonce).commit())
    }

    suspend fun inspectLocal(retained: DeleteAttemptRecord) {
        allowStore()
        val receipt = checkNotNull(store.deleteAcknowledgement(scope, event, retained.id))
        assertTrue("A lost response remains the same UNKNOWN with no manufactured proof",
            prefs.getBoolean("response_loss_dropped", false) && prefs.getInt("response_loss_pid", -1) > 0 &&
                prefs.getInt("response_loss_pid", -1) != Process.myPid() && retained.scope == scope && retained.eventId == event &&
                frozen("response_loss_record_hash") == hash(retained) &&
                frozen("response_loss_native_hash") == nativeHash(values()) && writes() == 0 && historyCount() == 1 &&
                receipt.state == DeleteAcknowledgementState.PENDING && receipt.receipt.nonce == frozen("response_loss_nonce") &&
                journal.readVerified(scope, retained.id, factual) == null && journal.readCompletion(scope, retained.id, factual) == null &&
                ProtonDeleteInspectionProbe.localEvidence(retained) == "NO_RESPONSE")
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

/** Read-only witnesses surrounding ordinary production worker activation. No journal writes,
 * dispatch decoration, cancellation, manual worker calls or radio restoration are available here.
 */
internal class DeleteWorkerNetworkJourney(
    private val app: Context, private val scope: CalendarScope, private val event: EventId,
    private val content: EventContent, private val prefs: SharedPreferences, private val accountHash: String,
    private val admit: (CalendarScope) -> Boolean, private val row: () -> Map<String, String?>,
    private val rowHash: (Map<String, String?>) -> String, private val history: () -> List<MutationId>,
    private val completed: suspend () -> Boolean, private val status: (String) -> Unit,
) : AutoCloseable {
    private var probe: ProtonDeleteNetworkInterruptionProbe.Handle? = null
    private fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
    private fun hash(record: DeleteAttemptRecord) = MessageDigest.getInstance("SHA-256")
        .digest(DeleteAttemptStorageCodec.encode(record)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun owner(): LocalDeleteIntent = ImportStore(app).use { store ->
        val owner = checkNotNull(store.localDelete(scope, event))
        assertTrue("The network journey retains the original complete offline owner",
            owner.scope == scope && owner.base.eventId == event && owner.state == LocalEditState.PAUSED &&
                owner.content == content && owner.base.content == content && owner.id.value == frozen("offline_owner") &&
                DeleteAttemptIdentity.intentFingerprint(owner) == frozen("offline_owner_hash") &&
                store.projectedBaseline(scope, event) == owner.base && store.transaction(scope) { remote(event) } == owner.base)
        val sourceName = "calendar-delete-worker-v1-${frozen("token")}-source.db"
        ImportStore(app, sourceName).use { source ->
            assertTrue("Retain the complete original captured remote observation",
                source.transaction(scope) { remote(event) } == owner.base)
        }
        owner
    }

    suspend fun beforeActivation(phase: String) {
        assertTrue("Network activation follows offline capture/replay and owned restoration",
            prefs.getBoolean("offline_replayed", false) && prefs.getBoolean("modes_restored", false) &&
                !prefs.getBoolean("worker_verified", false))
        if (phase == "NETWORK_RUN") {
            assertTrue("Never prepare or interrupt a second production request",
                !prefs.contains("network_run_started") && !prefs.contains("network_cut_attempted") &&
                    !prefs.contains("network_run_pid") && history().isEmpty())
            val original = owner()
            ImportStore(app).use { assertTrue("Preview starts without a reserved ACK", it.deleteAcknowledgement(scope, event) == null) }
            assertNative()
            assertWifiOnly()
            assertTrue("Freeze the single preview/run admission before authentic preparation",
                prefs.edit().putBoolean("network_run_started", true).commit())
            val port = ProtonAuthentication.deletions(app)
            val format = checkNotNull(port.deleteFormat)
            val id = DeleteAttemptIdentity.requestId(original, format)
            // Narrow preview exception: authentic preparation is read-only. The protected
            // capsule may differ later; the deterministic raw MemberID/Delete wire binds the probe.
            val preview = checkNotNull(port.prepareSubmission(id, original, admit))
            assertTrue("Preview cannot change owner or allocate history", preview.intent == original && preview.id == id &&
                preview.format == format && preview.state == DeleteAttemptState.PREPARED &&
                preview.protectedEvidence == null && history().isEmpty() && owner() == original)
            ImportStore(app).use { assertTrue(it.deleteAcknowledgement(scope, event) == null) }
            probe = ProtonDeleteNetworkInterruptionProbe.arm(app, preview, accountHash) {
                DeleteNetworkInterruptionJourney.cutWifi(app, prefs)
            }
        } else {
            assertTrue("Recovery requires a different process from the measured request",
                prefs.getBoolean("network_run_verified", false) && prefs.getInt("network_run_pid", -1) > 0 &&
                    prefs.getInt("network_run_pid", -1) != Process.myPid())
            if (phase == "NETWORK_OFFLINE_REPLAY") {
                DeleteNetworkInterruptionJourney.assertOffline(app)
                if (prefs.getBoolean("network_reconnect_held", false)) assertTrue("Held reconnect offline replay uses another process",
                    prefs.getInt("network_reconnect_pid", -1) > 0 && prefs.getInt("network_reconnect_pid", -1) != Process.myPid())
            } else {
                assertOnline()
                assertTrue("Reconnect follows a restarted offline replay in another process",
                    prefs.getBoolean("network_offline_verified", false) && prefs.getInt("network_offline_pid", -1) > 0 &&
                        prefs.getInt("network_offline_pid", -1) != Process.myPid() && !prefs.getBoolean("network_reconnect_held", false))
            }
            unknown(saved = true)
        }
    }

    private fun assertOnline() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        assertTrue("Host must reconnect before the online network phase",
            connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            })
    }

    private fun assertWifiOnly() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val internet = connectivity.allNetworks.mapNotNull(connectivity::getNetworkCapabilities)
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        assertTrue("Only the enrolled Wi-Fi-only transport may reach the single preview and worker request",
            internet.isNotEmpty() && internet.all { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) })
    }

    private fun assertNative(): String {
        val values = row()
        val nativeHash = rowHash(values)
        assertTrue("Retain every native column of the captured dirty tombstone",
            values[Events.DIRTY] == "1" && values[Events.DELETED] == "1" && nativeHash == frozen("offline_row_hash"))
        return nativeHash
    }

    private suspend fun unknown(saved: Boolean): DeleteAttemptRecord {
        val ids = history()
        assertTrue("Retain exactly one production request", ids.size == 1)
        val id = ids.single()
        val original = owner()
        val nativeHash = assertNative()
        return TitleUpdateAttemptStore(app).use { shared ->
            val journal = shared.deleteJournal
            val record = checkNotNull(journal.read(scope, id, admit))
            val evidenceShape = ProtonDeleteInspectionProbe.localEvidence(record)
            assertTrue("Retain production UNKNOWN, its actual evidence shape and deterministic identity",
                record.intent == original && record.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                    record.id == DeleteAttemptIdentity.requestId(original, record.format) &&
                    evidenceShape in setOf("NONE", "NO_RESPONSE") &&
                    (evidenceShape == "NONE") == (record.protectedEvidence == null) &&
                    journal.readVerified(scope, id, admit) == null && journal.readCompletion(scope, id, admit) == null)
            ImportStore(app).use { store ->
                val receipt = checkNotNull(store.deleteAcknowledgement(scope, event, id))
                assertTrue("Only the exact fully bound pending native receipt may remain",
                    receipt.state == DeleteAcknowledgementState.PENDING && receipt.receipt.intent == original &&
                        receipt.receipt.attempt == DeleteAttemptIdentity.of(record))
                if (saved) assertTrue("Never replace first request, capsule, receipt or native witness",
                    frozen("network_attempt") == id.value && frozen("network_record_hash") == hash(record) &&
                        frozen("network_evidence_shape") == evidenceShape && frozen("network_native_hash") == nativeHash &&
                        frozen("network_receipt_nonce") == receipt.receipt.nonce)
            }
            record
        }
    }

    /** True only when the main fixture must validate the ordinary production completion proof. */
    suspend fun afterActivation(phase: String, idle: suspend () -> Unit, assertOwned: () -> Unit): Boolean {
        if (phase == "NETWORK_RUN") {
            withTimeout(240_000) {
                while (checkNotNull(probe).observation().let { !it.measuredFailure && !it.measuredCancellation }) {
                    assertOwned(); delay(100)
                }
            }
            val measurement = checkNotNull(probe).observation()
            val kind = measurementKind(measurement)
            assertTrue("Worker interruption requires the actual completed radio cut",
                prefs.getBoolean("network_loss_observed", false))
            DeleteNetworkInterruptionJourney.assertOffline(app)
            assertTrue("Freeze the first worker transport measurement without replacing a witness",
                !prefs.contains("network_measurement_kind") && prefs.edit().putString("network_measurement_kind", kind)
                    .putInt("network_calls", measurement.calls).putInt("network_body_starts", measurement.bodyStarts).commit())
            idle(); assertOwned()
            val settled = checkNotNull(probe).observation()
            assertTrue("Settled workers retain the exact one-call measured transport interruption",
                measurementKind(settled) == kind && settled == measurement)
            DeleteNetworkInterruptionJourney.assertOffline(app)
            val record = unknown(saved = false)
            val evidenceShape = ProtonDeleteInspectionProbe.localEvidence(record)
            val receipt = ImportStore(app).use { checkNotNull(it.deleteAcknowledgement(scope, event, record.id)) }
            assertTrue("Persist the first real production witness atomically",
                !prefs.contains("network_record_hash") && prefs.edit().putString("network_attempt", record.id.value)
                    .putString("network_record_hash", hash(record)).putString("network_native_hash", assertNative())
                    .putString("network_evidence_shape", evidenceShape).putString("network_settled_measurement_kind", kind)
                    .putInt("network_settled_calls", settled.calls).putInt("network_settled_body_starts", settled.bodyStarts)
                    .putString("network_receipt_nonce", receipt.receipt.nonce).putInt("network_run_pid", Process.myPid())
                    .putBoolean("network_run_verified", true).commit())
            status(if (kind == "SOCKET") "system_delete_network_socket_failure" else "system_delete_network_cancelled_after_loss")
            status("system_delete_network_unknown_retained")
            status(if (evidenceShape == "NO_RESPONSE") "system_delete_network_no_response_retained" else "system_delete_network_missing_response_retained")
        } else if (phase == "NETWORK_OFFLINE_REPLAY") {
            DeleteNetworkInterruptionJourney.assertOffline(app)
            idle(); assertOwned(); unknown(saved = true)
            val edit = prefs.edit().putBoolean("network_offline_verified", true)
            if (!prefs.contains("network_offline_pid")) edit.putInt("network_offline_pid", Process.myPid())
            assertTrue("Preserve first restarted offline witness atomically", edit.commit())
            DeleteNetworkInterruptionJourney.assertOffline(app)
            status("system_delete_network_offline_immutable")
        } else {
            idle(); assertOwned()
            if (completed()) {
                // Completion is validated by the main fixture with the exact native/SQL ACK
                // and permanent barrier. The original request/capsule must remain immutable.
                TitleUpdateAttemptStore(app).use { shared ->
                    val record = checkNotNull(shared.deleteJournal.read(scope, MutationId(frozen("network_attempt")), admit))
                    assertTrue("Completion preserves the one original request and capsule",
                        history() == listOf(record.id) && hash(record) == frozen("network_record_hash"))
                }
                status("system_delete_network_reconnect_completed")
                return true
            }
            val record = unknown(saved = true)
            // Diagnostic only: PRESENT/contextMatched grants no proof and does not establish
            // that the production worker performed an HTTP inspection.
            val diagnostic = checkNotNull(ProtonDeleteInspectionProbe.inspect(app, record, accountHash))
            assertTrue("Authenticated read-only diagnostic confirms the held remote event",
                diagnostic.result == DeleteReadResult.PRESENT && diagnostic.contextMatched)
            assertOwned(); unknown(saved = true)
            assertTrue(prefs.edit().putBoolean("network_reconnect_held", true)
                .putInt("network_reconnect_pid", Process.myPid()).commit())
            status("system_delete_network_reconnect_held"); status("system_delete_network_diagnostic_present")
        }
        return false
    }

    private fun measurementKind(observation: DeleteNetworkInterruptionObservation): String {
        assertTrue("Require one authenticated post-cut socket failure or separate transport cancellation",
            observation.measuredFailure.xor(observation.measuredCancellation))
        return if (observation.measuredFailure) "SOCKET" else "CANCELLED"
    }

    override fun close() { probe?.close(); probe = null }
}

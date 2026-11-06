// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.CalendarContract.Events
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Local witnesses around production scheduling. READY is only a pre-kill checkpoint: the
 * host must separately observe runner termination and process absence before offline restart.
 * No radio changes, journal writes, fake response, cancellation or manual worker call occur here.
 */
internal class DeleteWorkerProcessJourney(
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
        val original = checkNotNull(store.localDelete(scope, event))
        check(original.scope == scope && original.base.eventId == event && original.state == LocalEditState.PAUSED &&
            original.content == content && original.base.content == content && original.id.value == frozen("offline_owner") &&
            DeleteAttemptIdentity.intentFingerprint(original) == frozen("offline_owner_hash") &&
            store.projectedBaseline(scope, event) == original.base && store.transaction(scope) { remote(event) } == original.base)
        ImportStore(app, "calendar-delete-worker-v1-${frozen("token")}-source.db").use { source ->
            check(source.transaction(scope) { remote(event) } == original.base)
        }
        original
    }

    private fun native(): String {
        val values = row()
        return rowHash(values).also {
            check(values[Events.DIRTY] == "1" && values[Events.DELETED] == "1" && it == frozen("offline_row_hash"))
        }
    }

    /** Cached import state and persisted mode reads only; never obtains an authentication facade. */
    private fun consent(active: Boolean) {
        val state = (app.applicationContext as BridgeApplication).imports.state.value
        check(state.authenticated && state.hasPermission && admit(scope))
        SystemCreateWorkStore(app).use { modes ->
            fun flags(target: CalendarScope) = listOf(modes.modeEnabled(target), modes.titlesEnabled(target),
                modes.textsEnabled(target), modes.timesEnabled(target), modes.deletesEnabled(target))
            if (active) {
                val expected = listOf(true, false, false, false, true)
                check(flags(scope) == expected && !prefs.getBoolean("mode_write_pending", false) &&
                    (0..4).map { prefs.getBoolean("owned_$it", false) } == expected)
            }
            check(state.selections.filter { it.enabled && it.scope != scope }.all { flags(it.scope).none { enabled -> enabled } })
        }
    }

    private fun connectivity(online: Boolean) {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val connected = manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        check(connected == online) { "PROCESS_NETWORK_STATE_REQUIRED" }
    }

    private suspend fun unknown(saved: Boolean): DeleteAttemptRecord {
        val ids = history()
        check(ids.size == 1)
        val original = owner()
        val nativeHash = native()
        return TitleUpdateAttemptStore(app).use { shared ->
            val journal = shared.deleteJournal
            val record = checkNotNull(journal.read(scope, ids.single(), admit))
            check(record.intent == original && record.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                record.id == DeleteAttemptIdentity.requestId(original, record.format) && record.protectedEvidence == null &&
                journal.readVerified(scope, record.id, admit) == null && journal.readCompletion(scope, record.id, admit) == null)
            ImportStore(app).use { store ->
                val receipt = checkNotNull(store.deleteAcknowledgement(scope, event, record.id))
                check(receipt.state == DeleteAcknowledgementState.PENDING && receipt.receipt.intent == original &&
                    receipt.receipt.attempt == DeleteAttemptIdentity.of(record))
                if (saved) check(frozen("process_attempt") == record.id.value && frozen("process_record_hash") == hash(record) &&
                    frozen("process_native_hash") == nativeHash && frozen("process_receipt_nonce") == receipt.receipt.nonce)
            }
            record
        }
    }

    suspend fun beforeActivation(phase: String) {
        check(prefs.getBoolean("offline_replayed", false) && !prefs.getBoolean("worker_verified", false))
        consent(active = false)
        if (phase == "PROCESS_RUN") {
            check(prefs.getBoolean("modes_restored", false) && !prefs.contains("process_run_requested") &&
                !prefs.contains("process_stop_ready") && !prefs.contains("process_run_pid") && history().isEmpty())
            connectivity(online = true)
            val original = owner()
            native()
            ImportStore(app).use { check(it.deleteAcknowledgement(scope, event) == null) }
            check(prefs.edit().putBoolean("process_run_requested", true).commit())
            // The only preparation exception: one genuine read-only wire preview. Production
            // preparation/claim/send is subsequently performed independently by normal workers.
            val port = ProtonAuthentication.deletions(app)
            val format = checkNotNull(port.deleteFormat)
            val id = DeleteAttemptIdentity.requestId(original, format)
            val preview = checkNotNull(port.prepareSubmission(id, original, admit))
            check(preview.intent == original && preview.id == id && preview.format == format &&
                preview.state == DeleteAttemptState.PREPARED && preview.protectedEvidence == null &&
                history().isEmpty() && owner() == original)
            ImportStore(app).use { check(it.deleteAcknowledgement(scope, event) == null) }
            probe = ProtonDeleteNetworkInterruptionProbe.arm(app, preview, accountHash) { stopAtBodyStart() }
        } else {
            check(prefs.getBoolean("process_stop_ready", false) && prefs.getInt("process_run_pid", -1) > 0 &&
                prefs.getInt("process_run_pid", -1) != Process.myPid() &&
                prefs.getInt("process_calls", -1) == 1 && prefs.getInt("process_body_starts", -1) == 1 &&
                prefs.getBoolean("process_authenticated", false) &&
                frozen("process_work_name") in setOf("calendar-import-network", "calendar-import-periodic"))
            if (phase == "PROCESS_OFFLINE_REPLAY") {
                connectivity(online = false)
                // The first restart still owns the killed run's enabled modes. Later replays
                // start restored; the parent fixture alone owns all restoration/setter rights.
                if (!prefs.getBoolean("modes_restored", false)) consent(active = true)
            } else {
                check(phase == "PROCESS_RECONNECT" && prefs.getBoolean("modes_restored", false) &&
                    prefs.getBoolean("process_offline_verified", false) && prefs.getInt("process_offline_pid", -1) > 0 &&
                    prefs.getInt("process_offline_pid", -1) != Process.myPid() && !prefs.getBoolean("process_reconnect_held", false))
                connectivity(online = true)
            }
            unknown(saved = true)
        }
    }

    private fun stopAtBodyStart() {
        // An AssertionError escaping OkHttp/JNI can abort the runner without our checkpoint.
        // Convert even assertions in supplied read-only witnesses to an ordinary exception.
        try {
            runBlocking { withTimeout(60_000) {
                check(!prefs.contains("process_stop_ready"))
                val measurement = checkNotNull(probe).observation()
                check(measurement.calls == 1 && measurement.authenticated && measurement.bodyStarts == 1 &&
                    !measurement.hookFailed && !measurement.socketFailure && !measurement.callEnded && !measurement.cancelledAfterLoss)
                consent(active = true)
                val record = unknown(saved = false)
                val receipt = ImportStore(app).use { checkNotNull(it.deleteAcknowledgement(scope, event, record.id)) }
                val manager = WorkManager.getInstance(app)
                val running = listOf("calendar-import-network", "calendar-import-periodic").flatMap { name ->
                    manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                        .filter { it.state == WorkInfo.State.RUNNING }.map { name to it }
                }
                check(running.size == 1)
                val (workName, work) = running.single()
                val pid = Process.myPid()
                check(pid > 0)
                check(prefs.edit().putString("process_attempt", record.id.value).putString("process_record_hash", hash(record))
                    .putString("process_native_hash", native()).putString("process_receipt_nonce", receipt.receipt.nonce)
                    .putString("process_work_id", work.id.toString()).putString("process_work_name", workName)
                    .putInt("process_run_pid", pid).putInt("process_calls", measurement.calls)
                    .putInt("process_body_starts", measurement.bodyStarts).putBoolean("process_authenticated", measurement.authenticated)
                    .putBoolean("process_stop_ready", true).commit())
                status("system_delete_process_stop_ready")
                Process.killProcess(pid)
                error("PROCESS_KILL_RETURNED")
            } }
        } catch (failure: Throwable) {
            throw IllegalStateException("PROCESS_STOP_CHECKPOINT_FAILED", failure)
        }
    }

    /** True only for an existing normal production completion proof. */
    suspend fun afterActivation(phase: String, idle: suspend () -> Unit, assertOwned: () -> Unit): Boolean {
        if (phase == "PROCESS_RUN") {
            withTimeout(240_000) {
                while (true) {
                    assertOwned(); consent(active = true)
                    check(!checkNotNull(probe).observation().hookFailed) { "PROCESS_STOP_HOOK_FAILED" }
                    delay(100)
                }
            }
            error("PROCESS_STOP_NOT_OBSERVED")
        }
        if (phase == "PROCESS_OFFLINE_REPLAY") {
            connectivity(online = false)
            assertOwned(); consent(active = true); unknown(saved = true)
            idle()
            connectivity(online = false)
            assertOwned(); consent(active = true); unknown(saved = true)
            val edit = prefs.edit().putBoolean("process_offline_verified", true)
            if (!prefs.contains("process_offline_pid")) edit.putInt("process_offline_pid", Process.myPid())
            check(edit.commit())
            status("system_delete_process_offline_immutable")
            return false
        }
        check(phase == "PROCESS_RECONNECT")
        idle(); assertOwned(); consent(active = true)
        val record = TitleUpdateAttemptStore(app).use { shared ->
            checkNotNull(shared.deleteJournal.read(scope, MutationId(frozen("process_attempt")), admit))
        }
        check(history() == listOf(record.id) && hash(record) == frozen("process_record_hash"))
        // This authenticated diagnostic is never evidence that a production worker sent a GET.
        val diagnostic = checkNotNull(ProtonDeleteInspectionProbe.inspect(app, record, accountHash))
        check(diagnostic.contextMatched)
        if (diagnostic.result == DeleteReadResult.ABSENT) {
            check(completed()) { "PROCESS_ABSENT_REQUIRES_PRODUCTION_COMPLETION" }
            status("system_delete_process_reconnect_completed")
            return true
        }
        check(diagnostic.result == DeleteReadResult.PRESENT)
        assertOwned(); consent(active = true); unknown(saved = true)
        check(!completed())
        check(prefs.edit().putBoolean("process_reconnect_held", true).putInt("process_reconnect_pid", Process.myPid()).commit())
        status("system_delete_process_reconnect_held")
        status("system_delete_process_diagnostic_present")
        return false
    }

    override fun close() { probe?.close(); probe = null }
}

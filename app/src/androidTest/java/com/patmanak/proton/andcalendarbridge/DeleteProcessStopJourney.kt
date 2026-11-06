// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonDeleteInspectionProbe
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.DeleteAttemptRecord
import java.security.MessageDigest
import org.junit.Assert.assertTrue

/** Isolated opt-in fixture: death after a bound in-memory response, before persistence.
 * READY is not evidence of death. The protected host must observe a dead runner and new PID.
 * Receiving a response is not remote success or authenticated absence and grants no ACK.
 */
internal object DeleteProcessStopJourney {
    fun assertQuiet(app: BridgeApplication, source: CalendarScope, accessible: () -> Boolean) {
        val imports = app.imports.state.value
        val writes = app.systemCreates.state.value
        val selected = imports.selections.map { it.scope }
        assertTrue("Whole-process stop requires current authenticated scope and idle production writes",
            accessible() && imports.authenticated && imports.hasPermission && source in selected && !writes.busy)
        SystemCreateWorkStore(app).use { modes ->
            assertTrue("Every selected production calendar must disable all five write flags",
                selected.all { scope -> !modes.modeEnabled(scope) && !modes.titlesEnabled(scope) &&
                    !modes.textsEnabled(scope) && !modes.timesEnabled(scope) && !modes.deletesEnabled(scope) &&
                    scope !in writes.enabled && scope !in writes.titlesEnabled && scope !in writes.textsEnabled &&
                    scope !in writes.timesEnabled && scope !in writes.deletesEnabled })
        }
    }

    suspend fun stop(app: BridgeApplication, prefs: SharedPreferences, unknown: DeleteAttemptRecord,
        suppliedCapsule: String, frozenInterruption: SharedPreferences.Editor, accessible: () -> Boolean): Nothing {
        assertTrue("Never replace the first process-stop witness", !prefs.contains("process_stop_ready") &&
            !prefs.contains("process_reported_success") && !prefs.contains("process_response_hash"))
        val shape = ProtonDeleteInspectionProbe.localEvidence(unknown.copy(protectedEvidence = suppliedCapsule))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("guarded_delete_process_response_shape", shape)
        })
        // A non-null response can be outside the strict success grammar. It still qualifies
        // this response-persistence checkpoint, never acceptance or acknowledgement. Use an
        // Exception at this callback so failure follows the adapter's normal UNKNOWN path.
        check(shape in RESPONSE_SHAPES) { "A complete bound response is required for process stop" }
        // Cached authentication/scope and local consent only: never reacquire the deletion facade lock.
        assertQuiet(app, unknown.scope, accessible)
        val responseHash = MessageDigest.getInstance("SHA-256").digest(suppliedCapsule.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertTrue("Atomically retain only opaque response hash and closed process-stop witnesses",
            frozenInterruption.putBoolean("process_stop_ready", true).putBoolean("process_response_received", true)
                .putString("process_response_shape", shape).putBoolean("process_reported_success", shape == "REPORTED_SUCCESS")
                .putString("process_response_hash", responseHash).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("guarded_delete_process_stop_ready", true)
            putBoolean("guarded_delete_process_response_received", true)
            putBoolean("guarded_delete_process_reported_success", shape == "REPORTED_SUCCESS")
        })
        Process.killProcess(Process.myPid())
        error("Process kill unexpectedly returned")
    }

    fun assertRestarted(prefs: SharedPreferences) {
        val shape = prefs.getString("process_response_shape", null)
        assertTrue("Require a complete-response checkpoint followed by an actually different process",
            prefs.getBoolean("process_stop_ready", false) && prefs.getBoolean("process_response_received", false) &&
                shape in RESPONSE_SHAPES && prefs.getBoolean("process_reported_success", false) == (shape == "REPORTED_SUCCESS") &&
                prefs.getInt("interruption_pid", -1) > 0 && prefs.getInt("interruption_pid", -1) != Process.myPid() &&
                prefs.getString("process_response_hash", null)?.matches(Regex("[a-f0-9]{64}")) == true)
    }

    private val RESPONSE_SHAPES = setOf("REPORTED_SUCCESS", "REPORTED_FAILURE", "UNCLASSIFIED")
}

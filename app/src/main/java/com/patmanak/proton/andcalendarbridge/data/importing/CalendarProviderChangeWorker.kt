// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.patmanak.proton.andcalendarbridge.BridgeApplication
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** URI is a scheduling hint; all existing scope, capture, request and send admissions remain. */
class CalendarProviderChangeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApplication
        if (!app.calendarSyncSettings.automaticAllowed()) return Result.success()
        val wake = app.providerWake
        try {
            val watch = inputData.getString("watch")
            if (watch != null && !inputData.getBoolean("signal", false)) {
                // Retry rebuilds content constraints and may need a new URI notification.
                // Move work off that constraint before auth/arm/capture can fail.
                wake.enqueueSignal(watch)
                return Result.success()
            }
            // Register the next independent URI job before authentication or any Provider scan.
            val state = if (watch != null) wake.registration.onTrigger(watch) else wake.registration.ensureArmed()
            if (!state.enabled) return Result.success()
            val authenticated = withContext(Dispatchers.Main.immediate) {
                val state = withTimeoutOrNull(60_000) { app.authentication.state.first {
                    !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
                } }
                    ?: return@withContext null
                app.imports.authenticationChanged(state.destination == AuthenticationDestination.AUTHENTICATED)
                state.destination == AuthenticationDestination.AUTHENTICATED
            } ?: return Result.retry()
            val account = com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication.events(app).currentAccount()
                ?: return Result.success()
            val nativeAccount = AndroidCalendarProjection(app).accountFor(account)
            if (!SystemSyncWork.automaticAllowed(nativeAccount)) return Result.success()
            app.imports.refreshProviderWakeState()
            val scopes = app.systemCreates.providerWakeScopes().filter { it.account == account }
            if (!authenticated || scopes.isEmpty()) {
                wake.setEnabled(false)
                return Result.success()
            }
            val network = inputData.getBoolean("network", false)
            if (network) {
                // No incoming sync, no fresh recovery reset. UNKNOWN stays inspect-only;
                // durable CREATE pauses cannot be lifted by this origin.
                val outgoing = app.systemCreates.run(true, fresh = false, providerWake = true, expectedAccount = account,
                    pushAllowed = { SystemSyncWork.automaticAllowed(nativeAccount) })
                if (outgoing == ImportRunResult.COMPLETE) trace("PROVIDER_NETWORK_COMPLETE")
                return finish(outgoing) {
                    wake.enqueueNetwork()
                }
            }
            val continuation = inputData.getString("pending")
            val pending = continuation ?: state.pendingToken ?: return Result.success()
            val reader = AndroidCalendarProjection(applicationContext)
            reader.admitOperation = {
                app.imports.state.value.authenticated && reader.hasPermissions() &&
                    SystemSyncWork.automaticAllowed(nativeAccount) &&
                    app.systemCreates.providerWakeScopes().filter { it.account == account } == scopes
            }
            return finish(captureProviderWake(pending, state.fingerprint, inputData.getString("fingerprint"),
                { reader.providerDirtyFingerprint(scopes) },
                { app.systemCreates.run(false, fresh = false, providerWake = true, expectedAccount = account).also {
                    if (it == ImportRunResult.COMPLETE) trace("PROVIDER_CAPTURE_COMPLETE")
                } },
                wake::enqueueLocal, wake::enqueueNetwork,
                { token, hash -> wake.registration.acknowledge(token, hash) },
                continued = continuation != null,
            )) { error("Capture must persist its own continuation") }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return Result.retry() }
    }

    // Debug-only closed stage codes. Logcat supplies timestamps; no IDs, payloads or exceptions.
    private fun trace(code: String) {
        if (applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.i("CalendeerSync", code)
    }

    private suspend fun finish(result: ImportRunResult, next: suspend () -> Unit): Result = when (result) {
        ImportRunResult.RETRY -> Result.retry()
        ImportRunResult.CONTINUE -> { next(); Result.success() }
        ImportRunResult.COMPLETE -> Result.success()
    }
}

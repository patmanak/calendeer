package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.patmanak.proton.andcalendarbridge.BridgeApplication
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CalendarImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApplication
        if (inputData.getString(SystemSyncWork.ACCOUNT) != null) return systemWork(app)
        app.allowNormalScheduling()
        val network = inputData.getBoolean("network", false)
        val result = executeImportWork(ready = { withContext(Dispatchers.Main.immediate) {
            val controller = app.authentication
            val state = withTimeoutOrNull(60_000) { controller.state.first { !it.isSubmitting } }
            if (state == null) null else {
                app.imports.authenticationChanged(state.destination == AuthenticationDestination.AUTHENTICATED)
                state.destination == AuthenticationDestination.AUTHENTICATED
            }
        } }, run = {
            val fresh = runAttemptCount == 0 && !inputData.getBoolean("resume", false)
            runPass(app, network, fresh, fresh, false, false)
        }, continueWork = { app.imports.enqueueContinuation(network) })
        return when (result) {
            ImportRunResult.RETRY -> Result.retry()
            ImportRunResult.CONTINUE -> error("Continuation must be scheduled before completion")
            ImportRunResult.COMPLETE -> Result.success()
        }
    }

    private suspend fun systemWork(app: BridgeApplication): Result {
        val expected = android.accounts.Account(requireNotNull(inputData.getString(SystemSyncWork.ACCOUNT)),
            com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.ACCOUNT_TYPE)
        val manual = inputData.getBoolean(SystemSyncWork.MANUAL, false)
        var accountId: com.patmanak.proton.andcalendarbridge.sync.AccountId? = null
        suspend fun admit(): SystemSyncOutcome? {
            val state = withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(60_000) { app.authentication.state.first {
                    !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
                } }
            } ?: return SystemSyncOutcome.RETRY
            if (state.destination != AuthenticationDestination.AUTHENTICATED || state.isCleanupRequired ||
                state.isCleanupInProgress) return SystemSyncOutcome.AUTH
            val active = com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication.events(app).currentAccount()
                ?: return SystemSyncOutcome.AUTH
            if (com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection(app).accountFor(active) != expected ||
                !SystemSyncWork.installed(app, expected))
                return SystemSyncOutcome.STALE
            accountId = active
            if (!com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection(app).hasPermissions())
                return SystemSyncOutcome.PERMISSION
            if (!manual && !SystemSyncWork.automaticAllowed(expected)) return SystemSyncOutcome.DISABLED
            app.imports.authenticationChanged(true)
            app.imports.refreshProviderWakeState()
            return null
        }
        val outcome = try {
            executeSystemSync(::admit, { first -> runPass(app, true,
                first && runAttemptCount == 0, false, true, inputData.getBoolean(SystemSyncWork.UPLOAD, false),
                requireNotNull(accountId)) })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { SystemSyncOutcome.RETRY }
        if (outcome == SystemSyncOutcome.SUCCESS) SystemSyncWork.recordSuccess(app, expected)
        return if (outcome == SystemSyncOutcome.RETRY) Result.retry()
            else Result.success(androidx.work.workDataOf(SystemSyncWork.OUTCOME to outcome.name))
    }

    private suspend fun runPass(app: BridgeApplication, network: Boolean, incomingFresh: Boolean,
        outgoingFresh: Boolean, providerWake: Boolean, uploadOnly: Boolean,
        expectedAccount: com.patmanak.proton.andcalendarbridge.sync.AccountId? = null): ImportRunResult {
        val incoming = if (uploadOnly) ImportRunResult.COMPLETE else try {
            app.imports.run(network, fresh = incomingFresh, expectedAccount = expectedAccount)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { ImportRunResult.RETRY }
        val outgoing = app.systemCreates.run(network, fresh = outgoingFresh, providerWake = providerWake,
            expectedAccount = expectedAccount)
        return when {
            outgoing == ImportRunResult.RETRY || incoming == ImportRunResult.RETRY -> ImportRunResult.RETRY
            outgoing == ImportRunResult.CONTINUE || incoming == ImportRunResult.CONTINUE -> ImportRunResult.CONTINUE
            else -> ImportRunResult.COMPLETE
        }
    }
}

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
        val network = inputData.getBoolean("network", false)
        val result = executeImportWork(ready = { withContext(Dispatchers.Main.immediate) {
            val controller = app.authentication
            val state = withTimeoutOrNull(60_000) { controller.state.first { !it.isSubmitting } }
            if (state == null) null else {
                app.imports.authenticationChanged(state.destination == AuthenticationDestination.AUTHENTICATED)
                state.destination == AuthenticationDestination.AUTHENTICATED
            }
        } }, run = {
            app.imports.run(network, fresh = runAttemptCount == 0 && !inputData.getBoolean("resume", false))
        }, continueWork = { app.imports.enqueueContinuation(network) })
        return when (result) {
            ImportRunResult.RETRY -> Result.retry()
            ImportRunResult.CONTINUE -> error("Continuation must be scheduled before completion")
            ImportRunResult.COMPLETE -> Result.success()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import androidx.work.*
import com.patmanak.proton.andcalendarbridge.BridgeApplication
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** Local representation only: no capture, import pass, dispatch or network constraint. */
class EventColorWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BridgeApplication
        val state = withTimeoutOrNull(60_000) { app.authentication.state.first {
            !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
        } } ?: return Result.retry()
        if (state.destination != AuthenticationDestination.AUTHENTICATED || state.isCleanupRequired ||
            state.isCleanupInProgress) return Result.success()
        return try {
            if (app.imports.reprojectEventColors()) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            // Revoked lease needs a fresh pass; a cancelled worker stays cancelled.
            currentCoroutineContext().ensureActive()
            Result.retry()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        internal const val WORK_NAME = "calendar-event-color-display"
        internal fun request() = OneTimeWorkRequestBuilder<EventColorWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        fun enqueue(context: Context, changed: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,
                if (changed) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request())
        }
    }
}

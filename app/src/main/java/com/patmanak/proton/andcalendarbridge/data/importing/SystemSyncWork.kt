// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContentResolver
import android.provider.CalendarContract
import android.os.SystemClock
import androidx.work.*
import com.patmanak.proton.andcalendarbridge.BridgeApplication
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Platform handoff only. The existing worker remains the sole synchronization executor. */
internal object SystemSyncWork {
    const val ACCOUNT = "system_account"
    const val MANUAL = "system_manual"
    const val UPLOAD = "system_upload"
    const val PULL = "system_pull"
    const val OUTCOME = "system_outcome"
    const val AUTOMATIC_TAG = "calendar-system-auto"
    const val AUTHORITY = CalendarContract.AUTHORITY
    const val PREFS = "android-calendar-sync"
    const val PERIODIC_WORK = "calendar-import-periodic"
    const val WAIT_MILLIS = 600_000L

    fun automaticAllowed(account: Account): Boolean = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(account, AUTHORITY)

    suspend fun currentAccount(app: BridgeApplication): Account? =
        ProtonAuthentication.events(app).currentAccount()?.let { AndroidCalendarProjection(app).accountFor(it) }

    fun installed(context: Context, account: Account) = account.type == AndroidCalendarProjection.ACCOUNT_TYPE &&
        AccountManager.get(context).getAccountsByType(account.type).contains(account)

    fun manualName(account: Account) = "calendar-manual-${account.name}"

    fun enqueue(context: Context, account: Account, manual: Boolean, request: OneTimeWorkRequest): Operation =
        WorkManager.getInstance(context).let { manager ->
            if (manual) manager.enqueueUniqueWork(manualName(account), ExistingWorkPolicy.KEEP, request)
            else manager.enqueue(request)
        }

    fun request(account: Account, manual: Boolean, upload: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInputData(workDataOf(ACCOUNT to account.name, MANUAL to manual, UPLOAD to (upload && !manual), "network" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .also { if (!manual) it.addTag(AUTOMATIC_TAG) }.build()

    fun periodicRequest(account: Account, minutes: Int): PeriodicWorkRequest {
        require(minutes in AndroidCalendarSyncSettings.INTERVALS)
        return PeriodicWorkRequestBuilder<CalendarImportWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(ACCOUNT to account.name, PULL to true, "network" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    }

    fun await(context: Context, id: UUID, timeoutMillis: Long = WAIT_MILLIS,
        uniqueName: String? = null): SystemSyncOutcome {
        val manager = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var watched = id
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            var info = manager.getWorkInfoById(watched).get(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            if (info == null && uniqueName != null) {
                // KEEP may reject the proposed ID. Follow the retained live job instead.
                info = manager.getWorkInfosForUniqueWork(uniqueName)
                    .get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    .firstOrNull { !it.state.isFinished } ?: return SystemSyncOutcome.RETRY
                watched = info.id
            }
            if (info != null) {
                if (info.state.isFinished) return if (info.state == WorkInfo.State.SUCCEEDED)
                    info.outputData.getString(OUTCOME)?.let { value ->
                        SystemSyncOutcome.entries.firstOrNull { it.name == value }
                    } ?: SystemSyncOutcome.RETRY
                else SystemSyncOutcome.RETRY
                if (info.runAttemptCount > 0 && info.state == WorkInfo.State.ENQUEUED) return SystemSyncOutcome.RETRY
            }
            Thread.sleep(250)
        }
        // Only the Android wait expires. The independent executor must keep running.
        return SystemSyncOutcome.RETRY
    }

    fun recordSuccess(context: Context, account: Account) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("success:${account.name}", System.currentTimeMillis()).commit())
    }
}

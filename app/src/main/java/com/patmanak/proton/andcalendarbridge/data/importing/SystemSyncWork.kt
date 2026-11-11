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
    const val OUTCOME = "system_outcome"
    const val AUTOMATIC_TAG = "calendar-system-auto"
    const val AUTHORITY = CalendarContract.AUTHORITY
    const val PREFS = "android-calendar-sync"

    fun automaticAllowed(account: Account): Boolean = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(account, AUTHORITY)

    suspend fun currentAccount(app: BridgeApplication): Account? =
        ProtonAuthentication.events(app).currentAccount()?.let { AndroidCalendarProjection(app).accountFor(it) }

    fun installed(context: Context, account: Account) = account.type == AndroidCalendarProjection.ACCOUNT_TYPE &&
        AccountManager.get(context).getAccountsByType(account.type).contains(account)

    fun request(account: Account, manual: Boolean, upload: Boolean): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInputData(workDataOf(ACCOUNT to account.name, MANUAL to manual, UPLOAD to upload, "network" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .also { if (!manual) it.addTag(AUTOMATIC_TAG) }.build()

    fun await(context: Context, id: UUID, timeoutMillis: Long = 120_000): SystemSyncOutcome {
        val manager = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val info = manager.getWorkInfoById(id).get(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
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

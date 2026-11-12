// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.os.SystemClock
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.importing.*

class CalendarSyncAdapterService : Service() {
    private val adapter by lazy { CalendarSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

internal class CalendarSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String,
        provider: ContentProviderClient?, syncResult: SyncResult) {
        val deadline = SystemClock.elapsedRealtime() + SystemSyncWork.WAIT_MILLIS
        var owned = false
        var outcome = SystemSyncOutcome.RETRY
        try {
            owned = authority == SystemSyncWork.AUTHORITY && SystemSyncWork.installed(context, account)
            if (!owned) return
            val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
            if (!manual && !SystemSyncWork.automaticAllowed(account)) return
            val request = SystemSyncWork.request(account, manual,
                extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false))
            outcome = relaySystemSync(
                { WorkManager.getInstance(context).enqueue(request).result.get(
                    (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 10_000), java.util.concurrent.TimeUnit.MILLISECONDS); Unit },
                { SystemSyncWork.await(context, request.id, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) })
        } catch (_: Exception) { /* WorkManager owns retries; never cancel its independent job. */ }
        finally {
            report(outcome, syncResult)
            // DAVx5's workaround for Android issue 320542002 affects the native latch only.
            if (owned && Build.VERSION.SDK_INT >= 34) try {
                ContentResolver.cancelSync(account, authority)
            } catch (_: Exception) { /* Native cleanup must not cancel or misreport the executor. */ }
        }
    }

    internal fun report(@Suppress("UNUSED_PARAMETER") outcome: SystemSyncOutcome, result: SyncResult) = result.clear()
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.app.Service
import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.importing.*
import java.util.UUID

class CalendarSyncAdapterService : Service() {
    private val adapter by lazy { CalendarSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

internal class CalendarSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String,
        provider: ContentProviderClient?, syncResult: SyncResult) {
        if (authority != SystemSyncWork.AUTHORITY || !SystemSyncWork.installed(context, account)) {
            report(SystemSyncOutcome.AUTH, syncResult); return
        }
        val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
        if (!manual && !SystemSyncWork.automaticAllowed(account)) return
        var id: UUID? = null
        try {
            val request = SystemSyncWork.request(account, manual,
                extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false))
            id = request.id
            // Independent jobs: cancelling this callback cannot cancel another callback's work.
            WorkManager.getInstance(context).enqueue(request).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            report(SystemSyncWork.await(context, id), syncResult)
        } catch (_: InterruptedException) {
            id?.let { WorkManager.getInstance(context).cancelWorkById(it) }
            Thread.currentThread().interrupt()
            syncResult.stats.numIoExceptions++
        } catch (_: Exception) {
            id?.let { WorkManager.getInstance(context).cancelWorkById(it) }
            syncResult.stats.numIoExceptions++
        }
    }

    internal fun report(outcome: SystemSyncOutcome, result: SyncResult) {
        when (outcome) {
            SystemSyncOutcome.SUCCESS, SystemSyncOutcome.DISABLED -> Unit
            SystemSyncOutcome.AUTH, SystemSyncOutcome.STALE -> result.stats.numAuthExceptions++
            SystemSyncOutcome.PERMISSION -> result.stats.numParseExceptions++
            SystemSyncOutcome.RETRY -> result.stats.numIoExceptions++
        }
    }
}

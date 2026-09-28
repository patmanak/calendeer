package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Scheduling is a trigger, never storage; injectable so isolated journeys cannot schedule real accounts. */
interface ImportScheduler {
    fun cancel()
    fun requestLocal()
    fun requestNetwork()
    fun continueWork(network: Boolean)
}

class WorkManagerImportScheduler(context: Context) : ImportScheduler {
    private val context = context.applicationContext
    private val manager get() = WorkManager.getInstance(context)
    override fun cancel() { manager.cancelAllWorkByTag(WORK_TAG) }
    override fun requestLocal() {
        manager.enqueueUniqueWork(LOCAL_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CalendarImportWorker>().addTag(WORK_TAG).build())
    }
    override fun requestNetwork() {
        manager.enqueueUniqueWork(NETWORK_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CalendarImportWorker>()
                .setInputData(workDataOf("network" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG).build())
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CalendarImportWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf("network" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK_TAG).build())
    }
    override fun continueWork(network: Boolean) {
        val request = OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInputData(workDataOf("network" to network, "resume" to true)).addTag(WORK_TAG)
        if (network) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        manager.enqueueUniqueWork(if (network) NETWORK_WORK else LOCAL_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
    }
    private companion object {
        const val WORK_TAG = "calendar-import"
        const val LOCAL_WORK = "calendar-import-local"
        const val NETWORK_WORK = "calendar-import-network"
        const val PERIODIC_WORK = "calendar-import-periodic"
    }
}

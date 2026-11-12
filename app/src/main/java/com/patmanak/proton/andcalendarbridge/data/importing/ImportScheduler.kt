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

class WorkManagerImportScheduler(context: Context, private val manual: Boolean = false) : ImportScheduler {
    private val context = context.applicationContext
    private val manager get() = WorkManager.getInstance(context)
    override fun cancel() { manager.cancelAllWorkByTag(WORK_TAG) }
    private val normalAllowed get() =
        (context as? com.patmanak.proton.andcalendarbridge.BridgeApplication)?.let {
            it.normalSchedulingAllowed && (manual || it.calendarSyncSettings.automaticAllowed())
        } != false
    override fun requestLocal() {
        if (!normalAllowed) return
        manager.enqueueUniqueWork(localName(), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CalendarImportWorker>()
                .setInputData(workDataOf(SystemSyncWork.MANUAL to manual)).addTag(WORK_TAG)
                .also { if (!manual) it.addTag(SystemSyncWork.AUTOMATIC_TAG) }.build())
    }
    override fun requestNetwork() {
        if (!normalAllowed) return
        manager.enqueueUniqueWork(networkName(), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CalendarImportWorker>()
                .setInputData(workDataOf("network" to true, SystemSyncWork.MANUAL to manual))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG).also { if (!manual) it.addTag(SystemSyncWork.AUTOMATIC_TAG) }.build())
    }
    override fun continueWork(network: Boolean) {
        if (!normalAllowed) return
        val request = OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInputData(workDataOf("network" to network, "resume" to true, SystemSyncWork.MANUAL to manual)).addTag(WORK_TAG)
            .also { if (!manual) it.addTag(SystemSyncWork.AUTOMATIC_TAG) }
        if (network) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        manager.enqueueUniqueWork(if (network) networkName() else localName(), ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
    }
    private fun localName() = if (manual) "$LOCAL_WORK-manual" else LOCAL_WORK
    private fun networkName() = if (manual) "$NETWORK_WORK-manual" else NETWORK_WORK
    private companion object {
        const val WORK_TAG = "calendar-import"
        const val LOCAL_WORK = "calendar-import-local"
        const val NETWORK_WORK = "calendar-import-network"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import android.provider.CalendarContract
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** One application-owned instance. Preferences contain random scheduling tokens and a hash only. */
internal class AndroidProviderWake(context: Context) {
    private val context = context.applicationContext
    private val manager get() = WorkManager.getInstance(context)
    private val preferences = this.context.getSharedPreferences("calendar-provider-wake-v1", Context.MODE_PRIVATE)
    private val store = object : ProviderWakeStateStore {
        override fun read(): ProviderWakeState {
            val enabled = preferences.getBoolean("enabled", false)
            val watch = preferences.getString("watch", null)
            val pending = preferences.getString("pending", null)
            val fingerprint = preferences.getString("fingerprint", null)
            check(!enabled || watch?.let(::validToken) == true) { "Invalid provider wake state" }
            check(pending == null || (enabled && validToken(pending))) { "Invalid provider wake state" }
            check(fingerprint == null || fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid provider wake state" }
            return ProviderWakeState(enabled, watch, pending, fingerprint)
        }
        override fun write(state: ProviderWakeState) {
            check(preferences.edit().clear().putBoolean("enabled", state.enabled)
                .putString("watch", state.watchToken).putString("pending", state.pendingToken)
                .putString("fingerprint", state.fingerprint).commit()) { "Provider wake state was not persisted" }
        }
    }
    val registration = ProviderWakeRegistration(store, object : ProviderWakeBackend {
        override suspend fun arm(token: String) {
            require(validToken(token))
            if ((this@AndroidProviderWake.context as? com.patmanak.proton.andcalendarbridge.BridgeApplication)
                    ?.calendarSyncSettings?.automaticAllowed() == false) return
            // A new independent name is necessary: KEEP on our own RUNNING name loses the successor.
            manager.enqueueUniqueWork("calendar-provider-watch.$token", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CalendarProviderChangeWorker>()
                    .setInputData(workDataOf("watch" to token))
                    .setConstraints(Constraints.Builder()
                        .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(30, TimeUnit.SECONDS).build())
                    .addTag(WATCH_TAG).addTag(WORK_TAG).build()).awaitPersisted()
        }
        override suspend fun cancelAll() { manager.cancelAllWorkByTag(WATCH_TAG).awaitPersisted() }
    }, { UUID.randomUUID().toString() })

    suspend fun setEnabled(enabled: Boolean) = registration.setEnabled(enabled)

    /** Transfer the consumed URI latch to an unconstrained durable job before business work. */
    suspend fun enqueueSignal(watch: String) {
        require(validToken(watch))
        manager.enqueueUniqueWork("calendar-provider-signal.$watch", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CalendarProviderChangeWorker>()
                .setInputData(workDataOf("watch" to watch, "signal" to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG).build()).awaitPersisted()
    }

    suspend fun enqueueNetwork() = enqueue(network = true)

    suspend fun enqueueLocal(pending: String, fingerprint: String?) = enqueue(false, pending, fingerprint)

    suspend fun enqueue(network: Boolean, pending: String? = null, fingerprint: String? = null) {
        val request = OneTimeWorkRequestBuilder<CalendarProviderChangeWorker>()
            .setInputData(workDataOf("network" to network, "pending" to pending, "fingerprint" to fingerprint))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK_TAG)
        if (network) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // Append a durable successor even while an earlier page is RUNNING. Never replace its work.
        manager.enqueueUniqueWork(if (network) "calendar-provider-network" else "calendar-provider-local",
            ExistingWorkPolicy.APPEND_OR_REPLACE, request.build()).awaitPersisted()
    }

    private fun validToken(value: String): Boolean = try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }

    private companion object {
        const val WORK_TAG = "calendar-import"
        const val WATCH_TAG = "calendar-provider-watch"
    }
}

/** WorkManager's asynchronous enqueue is not a durable handoff until its operation succeeds. */
internal suspend fun Operation.awaitPersisted(): Unit = suspendCancellableCoroutine { continuation ->
    val future = result
    future.addListener({
        try {
            future.get()
            if (continuation.isActive) continuation.resume(Unit)
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }, { command -> command.run() })
}

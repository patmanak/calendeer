package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.workDataOf
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarImportWorker
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.DeltaSyncTransaction
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Host controls device idle; local/network variants schedule the production import worker. */
@RunWith(AndroidJUnit4::class)
class CalendarDozeSchedulingDeviceTest {
    @Test fun importWorkerSurvivesForcedIdle() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("calendarDozeScheduling") == "true")
        val phase = arguments.getString("dozePhase")
        require(phase in setOf("prepare", "verify", "cleanup"))
        val network = arguments.getString("dozeNetwork") == "true"
        val variant = if (network) "network" else "local"
        val workName = "calendar-doze-qualification-$variant"
        val app = ApplicationProvider.getApplicationContext<BridgeApplication>()
        val preferences = app.getSharedPreferences(
            if (network) "doze-network-scheduling-fixture" else "doze-scheduling-fixture", Context.MODE_PRIVATE)
        val manager = WorkManager.getInstance(app)
        if (phase == "cleanup") {
            manager.cancelUniqueWork(workName).result.get()
            assertTrue(preferences.edit().clear().commit())
            return@runBlocking
        }
        val authentication = withTimeout(60_000) { app.authentication.state.first { !it.isSubmitting } }
        assertEquals(AuthenticationDestination.AUTHENTICATED, authentication.destination)
        if (phase == "prepare") {
            assertTrue(preferences.edit().clear().commit())
            var enabledScopes: Set<String> = emptySet()
            if (network) {
                assertTrue("Calendar grants required", AndroidCalendarProjection(app).hasPermissions())
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                assertTrue("Validated network required", connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                withTimeout(180_000) {
                    while (app.imports.state.value.selections.count { it.enabled && it.available } < 2 ||
                        app.imports.state.value.busy ||
                        manager.getWorkInfosForUniqueWork(NETWORK_WORK).get().any { !it.state.isFinished }) delay(500)
                }
                val enabled = app.imports.state.value.selections.filter { it.enabled }
                assertTrue("Network fixture requires completed accessible imports",
                    enabled.all { it.available && it.status == "IMPORTED" })
                enabledScopes = enabled.map { scopeFingerprint(it.scope) }.toSet()
            }
            val builder = OneTimeWorkRequestBuilder<CalendarImportWorker>().setInitialDelay(45, TimeUnit.SECONDS)
            if (network) builder.setInputData(workDataOf("network" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            val request = builder.build()
            manager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).result.get()
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get().state)
            assertTrue(preferences.edit().putString("id", request.id.toString())
                .putStringSet("enabledScopes", enabledScopes).commit())
        } else {
            val id = UUID.fromString(preferences.getString("id", null) ?: error("Missing preparation fixture"))
            val started = System.currentTimeMillis()
            val result = withTimeout(180_000) {
                var info: WorkInfo
                do {
                    info = manager.getWorkInfoById(id).get() ?: error("Scheduled work disappeared")
                    if (!info.state.isFinished) delay(1_000)
                } while (!info.state.isFinished)
                info
            }
            assertEquals("Import worker must finish after idle exit", WorkInfo.State.SUCCEEDED, result.state)
            if (network) {
                val expectedScopes = preferences.getStringSet("enabledScopes", null) ?: error("Missing prepared scopes")
                assertTrue("Network fixture requires at least two retained scopes", expectedScopes.size >= 2)
                withTimeout(180_000) {
                    while (app.imports.state.value.busy ||
                        app.imports.state.value.selections.filter { it.enabled }
                            .map { scopeFingerprint(it.scope) }.toSet() != expectedScopes ||
                        app.imports.state.value.selections.filter { it.enabled }.any { !it.available || it.status != "IMPORTED" } ||
                        manager.getWorkInfosForUniqueWork(NETWORK_WORK).get().any { !it.state.isFinished }) delay(500)
                }
                assertTrue("Session must remain authenticated", app.imports.state.value.authenticated)
                for (selection in app.imports.state.value.selections.filter { it.enabled }) {
                    val coverage = selection.coverage ?: error("Missing selected coverage")
                    assertTrue("Selected coverage must remain complete", coverage.finished)
                    assertEquals(coverage.windows.size, coverage.receivedWindows)
                    assertEquals(coverage.windows.size, coverage.appliedWindows)
                    assertEquals(0, selection.pendingProjectionCount)
                    assertEquals(0, selection.deferredCount)
                    assertEquals(0, selection.unverifiedCount)
                    assertTrue("History must remain complete", !selection.historyGap)
                    assertTrue("Network import must leave a drained durable change feed", app.imports.store.transaction(selection.scope) {
                        val delta = this as DeltaSyncTransaction
                        checkpoint?.traversalComplete == true && delta.received == null && delta.deltaCursor != null &&
                            !delta.deltaMore && delta.deltaReceived == null && delta.deltaJobs(1).isEmpty() &&
                            delta.deltaJobs(1, true).isEmpty()
                    })
                    assertTrue("Mapping migration must be complete", !app.imports.store.mappingRescanPending(selection.scope))
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("${variant}_worker_succeeded", true)
                putInt("run_attempt_count", result.runAttemptCount)
                putLong("observed_completion_wait_ms", System.currentTimeMillis() - started)
                if (network) {
                    putInt("enabled_calendar_count", app.imports.state.value.selections.count { it.enabled })
                    putBoolean("all_calendar_deltas_drained", true)
                }
            })
        }
    }

    /** Fixture-only scope fingerprints, never included in status or assertion messages. */
    private fun scopeFingerprint(scope: CalendarScope): String {
        val encoded = listOf(scope.account.value, scope.calendar.value, scope.generation.toString())
            .joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object { const val NETWORK_WORK = "calendar-import-network" }
}

package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in sanitized diagnostics; app startup may trigger its normal read-only import. */
@RunWith(AndroidJUnit4::class)
class CalendarStatusSnapshotDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun reportsCalendarStatusCountsWithoutIdentities() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarStatusSnapshot") == "true")
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() &&
                !app.imports.state.value.busy
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val networkValidated = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val recoveryRequested = InstrumentationRegistry.getArguments().getString("requestSync") == "true" &&
            app.imports.state.value.selections.any { it.status == "RUNNING" }
        if (recoveryRequested) {
            assertTrue("A validated network is required for this trigger", networkValidated)
            app.imports.requestSync()
            compose.waitUntil(180_000) {
                !app.imports.state.value.busy && app.imports.state.value.selections.none { it.status == "RUNNING" }
            }
        }
        if (InstrumentationRegistry.getArguments().getString("awaitSettled") == "true") {
            compose.waitUntil(180_000) {
                !app.imports.state.value.busy && app.imports.state.value.selections.none { it.status == "RUNNING" }
            }
        }
        val selections = app.imports.state.value.selections
        assertTrue(selections.isNotEmpty())
        val known = setOf("PAUSED", "PENDING", "RUNNING", "IMPORTED", "PARTIAL", "HISTORY_GAP",
            "SERIES_PENDING", "INCOMPLETE", "NETWORK", "ERROR", "AUTH", "LOCAL_KEYS", "CAPACITY",
            "UNSUPPORTED", "PERMISSION", "UNAVAILABLE")
        val histogram = selections.groupingBy { if (it.status in known) it.status else "OTHER" }.eachCount()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("calendar_count", selections.size)
            putInt("enabled_calendar_count", selections.count { it.enabled })
            putInt("pending_projection_count", selections.sumOf { it.pendingProjectionCount })
            putInt("deferred_event_count", selections.sumOf { it.deferredCount })
            putInt("unverified_event_count", selections.sumOf { it.unverifiedCount })
            putInt("history_gap_calendar_count", selections.count { it.historyGap })
            putInt("deferred_network_calendar_count", selections.count { it.enabled && it.nextAttempt > System.currentTimeMillis() })
            putInt("unfinished_coverage_calendar_count", selections.count { it.enabled && it.coverage?.finished == false })
            putInt("completed_calendar_count", selections.count { it.enabled && it.completedAt != null })
            putBoolean("network_validated", networkValidated)
            putBoolean("recovery_trigger_performed", recoveryRequested)
            for ((status, count) in histogram) putInt("status_$status", count)
        })
    }
}

package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.sync.PageCheckpoint
import com.patmanak.proton.andcalendarbridge.sync.PageRequest
import com.patmanak.proton.andcalendarbridge.sync.RemoteImportException
import com.patmanak.proton.andcalendarbridge.sync.DeltaSyncTransaction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only probe. Emits only a closed failure category for each network stage. */
@RunWith(AndroidJUnit4::class)
class CalendarNetworkStageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun reportsWhereAnEnabledCalendarImportStops() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarNetworkStage") == "true")
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() &&
                !app.imports.state.value.busy
        }
        val selections = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertTrue("At least one enabled calendar is required", selections.isNotEmpty())
        val remote = ProtonAuthentication.events(app)
        val results = mutableMapOf<String, Int>()
        fun record(stage: String, category: String) {
            val key = "stage_${stage}_$category"
            results[key] = (results[key] ?: 0) + 1
        }
        runBlocking {
            for (selection in selections) {
                var stage = "context"
                try {
                    withTimeout(120_000) {
                        remote.refreshContext(selection.scope)
                        record(stage, "OK")
                        stage = "receive"
                        val received = remote.receive(PageRequest(PageCheckpoint(selection.scope, remote.format), 20))
                        record(stage, "OK")
                        stage = "decode"
                        remote.decode(received)
                        record(stage, "OK")
                        stage = "delta_latest"
                        val cursor = remote.latestChanges(selection.scope)
                        record(stage, "OK")
                        stage = "delta_receive"
                        val changes = remote.receiveChanges(selection.scope, cursor)
                        record(stage, "OK")
                        stage = "delta_decode"
                        remote.decodeChanges(changes)
                        record(stage, "OK")
                    }
                } catch (failure: RemoteImportException) {
                    record(stage, failure.reason.name)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    record(stage, "TIMEOUT")
                } catch (_: Exception) {
                    record(stage, "OTHER")
                }
            }
        }
        val coordinatorResult = runBlocking {
            try {
                withTimeout(240_000) { app.imports.run(network = true, fresh = false).name }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                "TIMEOUT"
            } catch (_: Exception) {
                "OTHER"
            }
        }
        val statuses = app.imports.state.value.selections.filter { it.enabled }
        val blockedState = runBlocking {
            statuses.filter { it.status == "UNSUPPORTED" }.map { selection ->
                app.imports.store.transaction(selection.scope) {
                    val delta = this as DeltaSyncTransaction
                    listOf(checkpoint?.traversalComplete == true, delta.received != null,
                        delta.deltaCursor != null, delta.deltaReceived != null, delta.deltaMore,
                        delta.deltaJobs(1).isNotEmpty(), delta.deltaJobs(1, true).isNotEmpty())
                }
            }
        }
        runBlocking {
            for (selection in statuses.filter { it.status == "UNSUPPORTED" }) {
                val cursor = app.imports.store.transaction(selection.scope) {
                    (this as DeltaSyncTransaction).deltaCursor
                } ?: continue
                var stage = "stored_delta_receive"
                try {
                    withTimeout(120_000) {
                        val page = remote.receiveChanges(selection.scope, cursor)
                        record(stage, "OK")
                        stage = "stored_delta_decode"
                        remote.decodeChanges(page)
                        record(stage, "OK")
                    }
                } catch (failure: RemoteImportException) {
                    record(stage, failure.reason.name)
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    record(stage, "TIMEOUT")
                } catch (_: Exception) {
                    record(stage, "OTHER")
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("enabled_calendar_count", selections.size)
            putString("coordinator_result", coordinatorResult)
            statuses.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                if (status in setOf("IMPORTED", "PARTIAL", "PENDING", "NETWORK", "UNSUPPORTED", "ERROR",
                        "AUTH", "LOCAL_KEYS", "CAPACITY", "INCOMPLETE", "HISTORY_GAP", "SERIES_PENDING", "RUNNING"))
                    putInt("coordinator_status_$status", count)
            }
            putInt("blocked_baseline_incomplete_count", blockedState.count { !it[0] })
            putInt("blocked_baseline_receipt_count", blockedState.count { it[1] })
            putInt("blocked_delta_cursor_count", blockedState.count { it[2] })
            putInt("blocked_delta_receipt_count", blockedState.count { it[3] })
            putInt("blocked_delta_more_count", blockedState.count { it[4] })
            putInt("blocked_delta_job_count", blockedState.count { it[5] })
            putInt("blocked_delta_blocked_job_count", blockedState.count { it[6] })
            results.forEach { (key, count) -> putInt(key, count) }
        })
    }
}

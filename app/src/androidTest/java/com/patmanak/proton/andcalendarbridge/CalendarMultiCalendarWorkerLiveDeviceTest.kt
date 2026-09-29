package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.DeltaSyncTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in unchanged-fixture refresh through the production scheduler, never a direct import call. */
@RunWith(AndroidJUnit4::class)
class CalendarMultiCalendarWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun networkQueueRefreshesEnabledCalendarsAndPreservesPausedMirror() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarMultiWorker") == "true")
        val app = compose.activity.application as BridgeApplication
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permissions required", projection.hasPermissions())
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty()
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        assertTrue("Validated network required", connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        val manager = WorkManager.getInstance(app)
        suspend fun awaitIdleNetworkQueue() = withTimeout(180_000) {
            while (true) {
                val existing = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get()
                if (!app.imports.state.value.busy && existing.isNotEmpty() && existing.all { it.state.isFinished }) break
                delay(500)
            }
        }
        awaitIdleNetworkQueue()
        val before = app.imports.state.value.selections
        assertTrue("Every enabled fixture calendar must be accessible", before.filter { it.enabled }.all { it.available })
        val enabled = before.filter { it.enabled && it.available }
        val paused = before.filter { !it.enabled }
        assertTrue("This fixture requires at least two enabled calendars", enabled.size >= 2)
        assertTrue("This fixture requires a paused calendar", paused.isNotEmpty())
        assertTrue("Fixture must start from completed imports", enabled.all { it.status == "IMPORTED" })
        val account = enabled.first().scope.account
        assertTrue("Fixture requires one retained account", before.all { it.scope.account == account })
        val providerAccount = projection.accountFor(account)
        val mappings = app.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE)
        fun mapping(calendar: String) = mappings.getLong(providerAccount.name + ":" + calendar, -1)
        val beforePausedMappings = paused.map { mapping(it.scope.calendar.value) }
        val providerBefore = providerSnapshot(app, providerAccount.name, providerAccount.type)
        val previousWork = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get().map { it.id }.toSet()

        // The application owns scheduling and continuation. Do not repair the queue from the test.
        app.imports.requestSync()
        var observedWork: List<WorkInfo> = emptyList()
        var periodicWorkerObserved = false
        withTimeout(300_000) {
            while (true) {
                observedWork = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get().filter { it.id !in previousWork }
                periodicWorkerObserved = periodicWorkerObserved ||
                    manager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().any { it.state == WorkInfo.State.RUNNING }
                val selections = app.imports.state.value.selections.filter { it.enabled && it.available }
                if (observedWork.isNotEmpty() && observedWork.all { it.state == WorkInfo.State.SUCCEEDED } &&
                    !app.imports.state.value.busy && selections.size == enabled.size &&
                    selections.all { it.status == "IMPORTED" }) break
                if (observedWork.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED })
                    error("The production network queue did not succeed")
                delay(500)
            }
        }

        val after = app.imports.state.value.selections
        assertTrue("Session must remain authenticated", app.imports.state.value.authenticated)
        assertTrue("Every enabled fixture calendar must remain accessible", after.filter { it.enabled }.all { it.available })
        assertTrue("Enabled calendar identities must be retained",
            after.filter { it.enabled && it.available }.map { it.scope }.toSet() == enabled.map { it.scope }.toSet())
        assertTrue("Paused calendar state must be retained", after.filter { !it.enabled } == paused)
        assertTrue("Paused Provider mappings must be retained",
            paused.map { mapping(it.scope.calendar.value) } == beforePausedMappings)
        val enabledMappings = after.filter { it.enabled }.map { mapping(it.scope.calendar.value) }
        assertTrue("Enabled calendars require distinct Provider mirrors",
            enabledMappings.all { it > 0 } && enabledMappings.distinct().size == enabled.size)
        var processedPeriods = 0
        for (selection in after.filter { it.enabled && it.available }) {
            val coverage = selection.coverage ?: error("Completed import must retain coverage")
            assertTrue("Every selected calendar must finish coverage", coverage.finished)
            assertEquals(coverage.windows.size, coverage.receivedWindows)
            assertEquals(coverage.windows.size, coverage.appliedWindows)
            processedPeriods += coverage.appliedWindows
            assertEquals(0, selection.pendingProjectionCount)
            assertEquals(0, selection.deferredCount)
            assertEquals(0, selection.unverifiedCount)
            assertTrue("History must remain complete for this fixture", !selection.historyGap)
            assertTrue("The durable baseline and change feed must be drained", app.imports.store.transaction(selection.scope) {
                val delta = this as DeltaSyncTransaction
                checkpoint?.traversalComplete == true && delta.received == null && delta.deltaCursor != null &&
                    !delta.deltaMore && delta.deltaReceived == null && delta.deltaJobs(1).isEmpty() &&
                    delta.deltaJobs(1, true).isEmpty()
            })
            assertTrue("Mapping migration must be complete", !app.imports.store.mappingRescanPending(selection.scope))
            val providerId = mapping(selection.scope.calendar.value)
            app.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars.CALENDAR_ACCESS_LEVEL),
                "${Calendars._ID}=? AND ${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
                arrayOf(providerId.toString(), providerAccount.name, providerAccount.type), null)!!.use {
                assertEquals("Each enabled calendar requires one owned mirror", 1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals(Calendars.CAL_ACCESS_READ, it.getInt(0))
            }
        }
        val providerAfter = providerSnapshot(app, providerAccount.name, providerAccount.type)
        assertTrue("Unchanged refresh must retain Provider event identities and revisions", providerBefore == providerAfter)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("enabled_calendar_count", enabled.size)
            putInt("paused_calendar_count", paused.size)
            putInt("successful_network_work_count", observedWork.size)
            putInt("maximum_run_attempt_count", observedWork.maxOf { it.runAttemptCount })
            putInt("processed_period_count", processedPeriods)
            putInt("projected_event_count", providerAfter.values.sumOf { it.size })
            putBoolean("all_calendar_deltas_drained", true)
            putBoolean("unchanged_provider_identities", true)
            putBoolean("paused_calendar_preserved", true)
            putBoolean("periodic_worker_observed_running", periodicWorkerObserved)
        })
    }

    /** Internal opaque identities only, no names or event contents. Never include this map in failures. */
    private fun providerSnapshot(app: BridgeApplication, accountName: String, accountType: String): Map<Long, List<List<String?>>> {
        val calendarIds = app.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(accountName, accountType), null)!!.use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }
        return calendarIds.associateWith { calendar ->
            app.contentResolver.query(Events.CONTENT_URI,
                arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA2, Events.SYNC_DATA4),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), Events._ID)!!.use {
                buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> it.getString(column) }) }
            }
        }
    }

    private companion object {
        const val NETWORK_WORK = "calendar-import-network"
        const val PERIODIC_WORK = "calendar-import-periodic"
    }
}

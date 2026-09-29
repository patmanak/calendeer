package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarImportWorker
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
import java.util.concurrent.TimeUnit

/** Host controls device idle; this fixture schedules the production local import worker only. */
@RunWith(AndroidJUnit4::class)
class CalendarDozeSchedulingDeviceTest {
    @Test fun localImportWorkerSurvivesForcedIdle() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("calendarDozeScheduling") == "true")
        val phase = arguments.getString("dozePhase")
        require(phase in setOf("prepare", "verify", "cleanup"))
        val app = ApplicationProvider.getApplicationContext<BridgeApplication>()
        val preferences = app.getSharedPreferences("doze-scheduling-fixture", Context.MODE_PRIVATE)
        val manager = WorkManager.getInstance(app)
        if (phase == "cleanup") {
            manager.cancelUniqueWork(WORK_NAME).result.get()
            assertTrue(preferences.edit().clear().commit())
            return@runBlocking
        }
        val authentication = withTimeout(60_000) { app.authentication.state.first { !it.isSubmitting } }
        assertEquals(AuthenticationDestination.AUTHENTICATED, authentication.destination)
        if (phase == "prepare") {
            val request = OneTimeWorkRequestBuilder<CalendarImportWorker>()
                .setInitialDelay(45, TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request).result.get()
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get().state)
            assertTrue(preferences.edit().putString("id", request.id.toString()).commit())
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
            assertEquals("Local import worker must finish after idle exit", WorkInfo.State.SUCCEEDED, result.state)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("local_worker_succeeded", true)
                putInt("run_attempt_count", result.runAttemptCount)
                putLong("observed_completion_wait_ms", System.currentTimeMillis() - started)
            })
        }
    }

    private companion object { const val WORK_NAME = "calendar-doze-qualification-local" }
}

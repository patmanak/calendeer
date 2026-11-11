// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.app.job.JobScheduler
import android.os.Bundle
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only session/policy witness. Does not qualify process-absent editing or a deadline. */
@RunWith(AndroidJUnit4::class)
class ProviderWakeDeviceTest {
    @Test fun resolvedWriteSelectionsHaveAnActualSystemContentTrigger() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerWakeObservation") == "true")
        val app = ApplicationProvider.getApplicationContext<BridgeApplication>()
        val auth = withTimeout(60_000) { app.authentication.state.first {
            !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
        } }
        assertEquals(AuthenticationDestination.AUTHENTICATED, auth.destination)
        app.imports.authenticationChanged(true)
        app.imports.refreshProviderWakeState()
        assertTrue(app.imports.state.value.selectionsLoaded)
        val expected = app.systemCreates.providerWakeScopes().isNotEmpty()
        val scheduler = app.getSystemService(JobScheduler::class.java)
        fun count() = scheduler.allPendingJobs.count { job ->
            job.service.packageName == app.packageName && job.triggerContentUris.orEmpty().any {
                it.uri == CalendarContract.Events.CONTENT_URI
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("provider_wake_expected", expected)
        })
        withTimeout(30_000) {
            while ((count() > 0) != expected) delay(200)
        }
        assertEquals(expected, count() > 0)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("provider_wake_writable", expected)
            putBoolean("provider_wake_registered", count() > 0)
        })
    }
}

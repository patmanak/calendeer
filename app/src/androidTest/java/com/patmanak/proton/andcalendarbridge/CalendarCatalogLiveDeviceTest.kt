package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit read-only live check. Requires an already connected account; never logs its metadata. */
@RunWith(AndroidJUnit4::class)
class CalendarCatalogLiveDeviceTest {
    @Test fun retainedSessionDiscoversCalendarMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarLiveCatalog") == "true")
        val app = ApplicationProvider.getApplicationContext<BridgeApplication>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val authentication = runBlocking {
                withTimeout(60_000) { app.authentication.state.first { state -> !state.isSubmitting } }
            }
            assertEquals("An already authenticated session is required", AuthenticationDestination.AUTHENTICATED,
                authentication.destination)
            val catalog = runBlocking {
                withTimeout(60_000) {
                    app.calendarCatalog.state.first { state ->
                        state.status == CalendarCatalogStatus.READY || state.status == CalendarCatalogStatus.ERROR
                    }
                }
            }
            assertEquals("Catalog failure category: ${catalog.failure}", CalendarCatalogStatus.READY, catalog.status)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putInt("calendar_count", catalog.calendars.size)
                putBoolean("retained_session_authenticated", true)
            })
        }
    }
}

package com.patmanak.proton.andcalendarbridge

import android.app.Application
import android.os.SystemClock
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationController
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationMonotonicClock
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogController
import com.patmanak.proton.andcalendarbridge.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.proton.andcalendarbridge.ui.theme.AndroidThemePreferenceStore
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportCoordinator
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore

class BridgeApplication : Application() {
    private val authenticationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val imports by lazy {
        ImportCoordinator(this, ImportStore(this), ProtonAuthentication.events(this),
            AndroidCalendarProjection(this), authenticationScope, creates = CreateAttemptStore(this))
    }

    // App-owned: activity recreation must neither repeat login nor revoke a retained session.
    val authentication by lazy {
        AuthenticationController(
            ProtonAuthentication.create(this),
            AuthenticationMonotonicClock(SystemClock::elapsedRealtime),
            authenticationScope,
        ).also { controller ->
            authenticationScope.launch {
                controller.state.map { it.destination == AuthenticationDestination.AUTHENTICATED }
                    .distinctUntilChanged().collect { imports.authenticationChanged(it) }
            }
        }
    }
    val calendarCatalog by lazy {
        CalendarCatalogController(
            ProtonAuthentication.catalog(this), authenticationScope, SystemClock::elapsedRealtime,
        ).also { controller ->
            authenticationScope.launch {
                authentication.state
                    .map { it.destination == AuthenticationDestination.AUTHENTICATED }
                    .distinctUntilChanged()
                    .collect(controller::setAuthenticated)
            }
            authenticationScope.launch { controller.state.collect { imports.rememberCatalog(it) } }
        }
    }
    lateinit var themePreferences: AndroidThemePreferenceStore
        private set
    lateinit var languagePreferences: AndroidLanguagePreferenceStore
        private set

    override fun onCreate() {
        super.onCreate()
        themePreferences = AndroidThemePreferenceStore(this)
        languagePreferences = AndroidLanguagePreferenceStore(this)
        authenticationScope.launch {
            try { imports.initializeCreates() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Experimental writes stay closed until initialization succeeds. */ }
        }
    }
}

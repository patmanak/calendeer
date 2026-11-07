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
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateController
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.AndroidTitleRecoveryCursorStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTitleRecoveryController
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemUpdateController
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemTitleOperations
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemTextOperations
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemPersonalOperations
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemTimeOperations
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemDeleteOperations
import com.patmanak.proton.andcalendarbridge.sync.MutationKind

class BridgeApplication : Application() {
    // Per-calendar explicit consent remains required for this restricted experimental profile.
    private val textSynchronizationQualified = true
    // Timed service readback qualified; each calendar still requires separate time consent.
    private val timeSynchronizationQualified = true
    // Ordinary service/editor/worker profile qualified; separate deletion consent stays off by default.
    private val deleteSynchronizationQualified = true
    private val authenticationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val createAttempts by lazy { CreateAttemptStore(this) }
    private val localCreateCaptures by lazy { LocalCreateStore(this) }
    private val nativeCalendar by lazy { AndroidCalendarProjection(this) }
    private val systemCreateWork by lazy { SystemCreateWorkStore(this) }
    // Production history only. Experimental fixtures keep their isolated journals.
    // No title transport or dispatch admission is exposed to this offline traversal.
    private val titleAttempts by lazy { TitleUpdateAttemptStore(this) }
    private val titleRecovery by lazy {
        SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(this),
            { query -> imports.recoverLocalTitles(query, titleAttempts) })
    }
    private val titleUpdates by lazy {
        SystemUpdateController(
            AndroidTitleRecoveryCursorStore(this, "calendar-title-owner-cursors"),
            AndroidTitleRecoveryCursorStore(this, "calendar-title-inspection-cursors"),
            CoordinatorSystemTitleOperations(imports, titleAttempts,
                ProtonAuthentication.localEditPreparations(this), ProtonAuthentication.titleUpdates(this)),
            { target -> titleRecovery.reset(target) })
    }
    private val textOperations: CoordinatorSystemTextOperations by lazy {
        CoordinatorSystemTextOperations(imports, titleAttempts.textJournal,
            ProtonAuthentication.localTextPreparations(this), ProtonAuthentication.textUpdates(this))
    }
    private val textRecovery by lazy {
        SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(this, "calendar-text-recovery-cursors"),
            recover = { com.patmanak.proton.andcalendarbridge.sync.TitleUpdateOfflineRecoveryProgress(inactive = true) },
            guardedRecover = { query, active ->
                textOperations.recoverOffline(query) { requested -> requested == query.scope && active() }
            })
    }
    private val textUpdates by lazy {
        SystemUpdateController(
            AndroidTitleRecoveryCursorStore(this, "calendar-text-owner-cursors"),
            AndroidTitleRecoveryCursorStore(this, "calendar-text-inspection-cursors"),
            textOperations, { target -> textRecovery.reset(target) })
    }
    private val timeOperations: CoordinatorSystemTimeOperations by lazy {
        CoordinatorSystemTimeOperations(imports, titleAttempts.timeJournal,
            ProtonAuthentication.localTimePreparations(this), ProtonAuthentication.timeUpdates(this))
    }
    private val timeRecovery by lazy {
        SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(this, "calendar-time-recovery-cursors"),
            recover = { com.patmanak.proton.andcalendarbridge.sync.TitleUpdateOfflineRecoveryProgress(inactive = true) },
            guardedRecover = { query, active ->
                timeOperations.recoverOffline(query) { requested -> requested == query.scope && active() }
            })
    }
    private val timeUpdates by lazy {
        SystemUpdateController(
            AndroidTitleRecoveryCursorStore(this, "calendar-time-owner-cursors"),
            AndroidTitleRecoveryCursorStore(this, "calendar-time-inspection-cursors"),
            timeOperations, { target -> timeRecovery.reset(target) })
    }
    private val personalOperations: CoordinatorSystemPersonalOperations by lazy {
        CoordinatorSystemPersonalOperations(imports, titleAttempts.personalJournal,
            ProtonAuthentication.personalPreparations(this), ProtonAuthentication.personalUpdates(this))
    }
    private val personalRecovery by lazy {
        SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(this, "calendar-personal-recovery-cursors"),
            recover = { com.patmanak.proton.andcalendarbridge.sync.TitleUpdateOfflineRecoveryProgress(inactive = true) },
            guardedRecover = { query, active ->
                personalOperations.recoverOffline(query) { requested -> requested == query.scope && active() }
            })
    }
    private val personalUpdates by lazy {
        SystemUpdateController(
            AndroidTitleRecoveryCursorStore(this, "calendar-personal-owner-cursors"),
            AndroidTitleRecoveryCursorStore(this, "calendar-personal-inspection-cursors"),
            personalOperations, { target -> personalRecovery.reset(target) })
    }
    private val deleteOperations: CoordinatorSystemDeleteOperations by lazy {
        CoordinatorSystemDeleteOperations(imports, titleAttempts.deleteJournal,
            ProtonAuthentication.deletions(this))
    }
    private val deleteRecovery by lazy {
        SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(this, "calendar-delete-recovery-cursors"),
            recover = { com.patmanak.proton.andcalendarbridge.sync.TitleUpdateOfflineRecoveryProgress(inactive = true) },
            guardedRecover = { query, active ->
                deleteOperations.recoverOffline(query) { requested -> requested == query.scope && active() }
            })
    }
    private val deleteUpdates by lazy {
        SystemUpdateController(
            AndroidTitleRecoveryCursorStore(this, "calendar-delete-owner-cursors"),
            AndroidTitleRecoveryCursorStore(this, "calendar-delete-inspection-cursors"),
            deleteOperations, { target -> deleteRecovery.reset(target) }, mutationKind = MutationKind.DELETE)
    }
    val systemCreates by lazy { SystemCreateController(this, imports, nativeCalendar, systemCreateWork,
        localCreateCaptures, createAttempts, ProtonAuthentication.createSubmissions(this), authenticationScope,
        titleRecovery = titleRecovery, titleUpdates = titleUpdates,
        textRecovery = if (textSynchronizationQualified) textRecovery else null,
        textUpdates = if (textSynchronizationQualified) textUpdates else null,
        timeRecovery = if (timeSynchronizationQualified) timeRecovery else null,
        timeUpdates = if (timeSynchronizationQualified) timeUpdates else null,
        personalRecovery = personalRecovery, personalUpdates = personalUpdates,
        deleteRecovery = if (deleteSynchronizationQualified) deleteRecovery else null,
        deleteUpdates = if (deleteSynchronizationQualified) deleteUpdates else null) }

    val imports: ImportCoordinator by lazy {
        ImportCoordinator(this, ImportStore(this), ProtonAuthentication.events(this),
            nativeCalendar, authenticationScope, creates = createAttempts,
            localCreates = localCreateCaptures, systemCreatesEnabled = systemCreateWork::modeEnabled,
            systemTitlesEnabled = systemCreateWork::titlesEnabled,
            systemTextsEnabled = { target -> textSynchronizationQualified && systemCreateWork.textsEnabled(target) },
            systemTimesEnabled = { target -> timeSynchronizationQualified && systemCreateWork.timesEnabled(target) },
            systemDeletesEnabled = { target -> deleteSynchronizationQualified && systemCreateWork.deletesEnabled(target) })
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
        systemCreates.start()
        authenticationScope.launch {
            try { imports.initializeCreates() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Experimental writes stay closed until initialization succeeds. */ }
        }
    }
}

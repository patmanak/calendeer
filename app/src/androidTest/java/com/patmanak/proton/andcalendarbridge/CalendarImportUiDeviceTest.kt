package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import java.time.ZoneOffset
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarImportPanel
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarImportTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic presentation only. Does not instantiate a store, request real permissions or import. */
@RunWith(AndroidJUnit4::class)
class CalendarImportUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(ImportUiState())
    private val selection = ImportStore.Selection(
        CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0),
        CalendarSummary(CalendarId("fixture-calendar"), "Fixture calendar"), false, "PAUSED", null)
    private var permissionRequests = 0
    private var settingsRequests = 0
    private var syncRequests = 0
    private var enableRequests = 0

    private fun start(initial: ImportUiState) {
        state.value = initial
        compose.setContent {
            MaterialTheme {
                CalendarImportPanel(state.value,
                    onSetEnabled = { selected, enabled ->
                        assertEquals(selection.scope, selected.scope)
                        if (enabled) enableRequests++
                    },
                    onSync = { syncRequests++ }, onRequestPermission = { permissionRequests++ },
                    onOpenSettings = { settingsRequests++ },
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }

    @Test fun signedOutDoesNotShowImportConfigurationOrRequestPermission() {
        start(ImportUiState(selections = listOf(selection), authenticated = false))
        compose.onNodeWithTag(CalendarImportTags.PANEL).assertDoesNotExist()
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, permissionRequests) }
    }

    @Test fun missingPermissionExplainsSharingAndRequiresExplicitAction() {
        start(ImportUiState(selections = listOf(selection), authenticated = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_privacy)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_permission_help)).assertExists()
        compose.runOnIdle { assertEquals(0, permissionRequests) }
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).performScrollTo().performClick()
        compose.onNodeWithTag(CalendarImportTags.SETTINGS).performScrollTo().performClick()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(1, settingsRequests)
            assertEquals(0, syncRequests)
        }
    }

    @Test fun importRequiresSelectionAndPermissionAndBusyStatePreventsDuplicateActions() {
        start(ImportUiState(selections = listOf(selection), authenticated = true, hasPermission = true))
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).assertDoesNotExist()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertIsOff()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, enableRequests)
            state.value = state.value.copy(selections = listOf(selection.copy(enabled = true, status = "PENDING")))
        }
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, syncRequests); state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertIsNotEnabled()
    }

    @Test fun completedImportIsLimitedToSupportedPeriodAndPauseKeepsPreviousCompletionVisible() {
        start(ImportUiState(selections = listOf(selection.copy(enabled = true, status = "IMPORTED", completedAt = 1_700_000_000_000)),
            authenticated = true, hasPermission = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_imported)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_window)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.COMPLETED).assertExists()
        compose.onNodeWithTag(CalendarImportTags.COVERAGE).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(selections = state.value.selections.map { it.copy(enabled = false, status = "PAUSED") }) }
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_paused)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.COMPLETED).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_pause_help)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
    }

    @Test fun partialImportReportsRetainedEventsWithoutClaimingFullSuccess() {
        start(ImportUiState(selections = listOf(selection.copy(enabled = true, status = "PARTIAL", deferredCount = 2, unverifiedCount = 1, historyGap = true)),
            authenticated = true, hasPermission = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_partial)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.HISTORY_GAP).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_deferred_count, 2)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_deferred_categories, 1, 1)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_imported)).assertDoesNotExist()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsEnabled()
    }

    @Test fun missingSeriesReportsPendingDependencyWithoutClaimingSuccess() {
        start(ImportUiState(selections = listOf(selection.copy(enabled = true,
            status = "SERIES_PENDING", pendingProjectionCount = 1)),
            authenticated = true, hasPermission = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_series_pending)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_recurrence_limits)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.PROJECTION_PENDING).assertExists()
        compose.onNodeWithTag(CalendarImportTags.COMPLETED).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_imported)).assertDoesNotExist()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsEnabled()
    }

    @Test fun historyGapWarningSurvivesNetworkFailureAndPauseWithoutClaimingCompleteImport() {
        start(ImportUiState(selections = listOf(selection.copy(enabled = true, status = "HISTORY_GAP", historyGap = true)),
            authenticated = true, hasPermission = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_history_gap)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.HISTORY_GAP).assertExists()
        compose.onNodeWithTag(CalendarImportTags.COMPLETED).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_imported)).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(selections = state.value.selections.map { it.copy(status = "NETWORK") })
        }
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_network)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.HISTORY_GAP).assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(selections = state.value.selections.map { it.copy(enabled = false, status = "PAUSED") })
        }
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_paused)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.HISTORY_GAP).assertExists()
    }

    @Test fun receivedAndProcessedPeriodsRemainDistinctFromDeferredEventsAndProjection() {
        val window = WindowCoverage(CoverageInterval(LocalDate.of(2030, 1, 1),
            LocalDate.of(2030, 2, 1), ZoneOffset.UTC), prefixKnown = true,
            receivedPages = 4, terminalReceived = true, receivedAt = 10)
        val coverage = CoverageRun(AdapterFormat("fixture", 1), listOf(window), lastIndex = 0)
        start(ImportUiState(selections = listOf(selection.copy(enabled = true,
            status = "LOCAL_KEYS", coverage = coverage)), authenticated = true, hasPermission = true))
        compose.onNodeWithText(compose.activity.getString(R.string.import_coverage_progress, 1, 1, 0)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.PROJECTION_PENDING).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(selections = listOf(selection.copy(enabled = true,
                status = "PARTIAL", deferredCount = 1, historyGap = true, pendingProjectionCount = 2,
                coverage = coverage.copy(windows = listOf(window.copy(appliedPages = 4,
                    terminalApplied = true, appliedAt = 20)), finished = true))))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.import_coverage_progress, 1, 1, 1)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_coverage_help)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_projection_pending, 2)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.DEFERRED).assertExists()
        compose.onNodeWithTag(CalendarImportTags.HISTORY_GAP).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.import_status_imported)).assertDoesNotExist()
    }
}

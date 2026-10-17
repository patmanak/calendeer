package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import com.patmanak.proton.andcalendarbridge.ui.calendars.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation fixtures only. No production controller, device calendar or remote account access. */
@RunWith(AndroidJUnit4::class)
class CalendarDashboardUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val summary = CalendarSummary(CalendarId("fixture-calendar"), "Fixture calendar", 0x7448CE)
    private val selection = ImportStore.Selection(CalendarScope(AccountId("fixture-account"), summary.id, 0),
        summary, enabled = true, status = "IMPORTED", completedAt = null)
    private val imports = mutableStateOf(ImportUiState(listOf(selection), hasPermission = true, authenticated = true))
    private val creates = mutableStateOf(SystemCreateUiState())
    private val catalog = mutableStateOf(CalendarCatalogState(CalendarCatalogStatus.READY,
        listOf(summary), canRefresh = true, account = selection.scope.account))
    private var refreshes = 0
    private var syncs = 0
    private var permissions = 0
    private var settings = 0
    private val selectionChanges = mutableListOf<Boolean>()
    private val createChanges = mutableListOf<Boolean>()
    private val titleChanges = mutableListOf<Boolean>()
    private val textChanges = mutableListOf<Boolean>()
    private val timeChanges = mutableListOf<Boolean>()
    private val deleteChanges = mutableListOf<Boolean>()

    private fun start() {
        compose.setContent {
            MaterialTheme {
                CalendarDashboard(catalog.value, imports.value, creates.value,
                    onRefresh = { refreshes++ },
                    onSetEnabled = { selected, enabled -> assertEquals(selection.scope, selected.scope); selectionChanges += enabled },
                    onSync = { syncs++ }, onRequestPermission = { permissions++ }, onOpenSettings = { settings++ },
                    onMode = { selected, enabled -> assertEquals(selection.scope, selected.scope); createChanges += enabled },
                    onTitlesMode = { selected, enabled -> assertEquals(selection.scope, selected.scope); titleChanges += enabled },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    onTextMode = { selected, enabled -> assertEquals(selection.scope, selected.scope); textChanges += enabled },
                    onTimeMode = { selected, enabled -> assertEquals(selection.scope, selected.scope); timeChanges += enabled },
                    onDeleteMode = { selected, enabled -> assertEquals(selection.scope, selected.scope); deleteChanges += enabled })
            }
        }
    }

    @Test fun calendarAppearsOnceAndOpeningOptionsDoesNotEnableAnyMode() {
        start()
        compose.onAllNodesWithText(summary.title).assertCountEquals(1)
        compose.onAllNodesWithTag(CalendarDashboardTags.CARD).assertCountEquals(1)
        compose.onNodeWithTag("system-create-toggle").assertDoesNotExist()
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
        compose.onNodeWithTag("system-create-toggle").assertIsOff()
        compose.onNodeWithTag("system-title-toggle").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
        compose.onNodeWithTag("system-create-toggle").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), createChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), selectionChanges)
            assertEquals(0, permissions)
            assertEquals(0, syncs)
        }
    }

    @Test fun pendingAndConflictingChangesStayVisibleWithOptionsClosedAndAfterPause() {
        imports.value = imports.value.copy(selections = listOf(selection.copy(deferredCount = 2, unverifiedCount = 1,
            historyGap = true, pendingProjectionCount = 3, retainedLocalChangeCount = 2, localConflictCount = 1)))
        creates.value = SystemCreateUiState(pending = mapOf(selection.scope to 4))
        start()
        for (tag in listOf(CalendarImportTags.DEFERRED, CalendarImportTags.HISTORY_GAP,
            CalendarImportTags.PROJECTION_PENDING, CalendarImportTags.LOCAL_CHANGES, "system-create-pending")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_dashboard_ready)).assertDoesNotExist()
        compose.onNodeWithTag("system-create-toggle").assertDoesNotExist()
        compose.runOnIdle { imports.value = imports.value.copy(selections = imports.value.selections.map { it.copy(enabled = false) }) }
        compose.onNodeWithTag(CalendarImportTags.LOCAL_CHANGES).assertExists()
        compose.onNodeWithTag("system-create-pending").assertExists()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
    }

    @Test fun writeOptionsRequireTheSameExplicitIndependentConsent() {
        start()
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
        compose.onNodeWithTag("system-title-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("system-create-toggle").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), createChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            creates.value = creates.value.copy(enabled = setOf(selection.scope))
        }
        compose.onNodeWithTag("system-title-toggle").assertIsOff().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), titleChanges) }
    }

    @Test fun missingPermissionBlocksNewWriteModesButNeverBlocksRevokingConsent() {
        imports.value = imports.value.copy(hasPermission = false,
            selections = listOf(selection.copy(enabled = false, available = false)))
        start()
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
        compose.onNodeWithTag("system-create-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("system-title-toggle").assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertIsNotEnabled()
        compose.runOnIdle { creates.value = creates.value.copy(enabled = setOf(selection.scope), titlesEnabled = setOf(selection.scope)) }
        compose.onNodeWithTag("system-create-toggle").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("system-title-toggle").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), createChanges); assertEquals(listOf(false), titleChanges) }
    }

    @Test fun deletionOptionRequiresSupportAndUsesOnlyItsIndependentConsentCallback() {
        creates.value = creates.value.copy(textSupportAvailable = true, timeSupportAvailable = true)
        start()
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
        compose.onNodeWithTag("system-delete-toggle").assertDoesNotExist()
        compose.runOnIdle { creates.value = creates.value.copy(deleteSupportAvailable = true) }
        compose.onNodeWithTag("system-delete-toggle").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { creates.value = creates.value.copy(enabled = setOf(selection.scope)) }
        compose.onNodeWithTag("system-delete-toggle").assertIsOff().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), deleteChanges)
            assertEquals(emptyList<Boolean>(), createChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), textChanges)
            assertEquals(emptyList<Boolean>(), timeChanges)
            creates.value = creates.value.copy(deletesEnabled = setOf(selection.scope))
        }
        compose.onNodeWithTag("system-title-toggle").assertIsOff()
        compose.onNodeWithTag("system-text-toggle").assertIsOff()
        compose.onNodeWithTag("system-time-toggle").assertIsOff()
        compose.onNodeWithTag("system-delete-toggle").assertIsOn().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, false), deleteChanges)
            assertEquals(emptyList<Boolean>(), createChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), textChanges)
            assertEquals(emptyList<Boolean>(), timeChanges)
            creates.value = creates.value.copy(deleteSupportAvailable = false)
        }
        compose.onNodeWithTag("system-delete-toggle").assertDoesNotExist()
    }

    @Test fun permissionsAndGlobalActionsAreExplicitAndSeparate() {
        imports.value = imports.value.copy(hasPermission = false)
        start()
        compose.runOnIdle { assertEquals(0, permissions); assertEquals(0, refreshes); assertEquals(0, syncs) }
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).performScrollTo().performClick()
        compose.onNodeWithTag(CalendarImportTags.SETTINGS).performScrollTo().performClick()
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, permissions); assertEquals(1, settings); assertEquals(1, refreshes); assertEquals(0, syncs)
            imports.value = imports.value.copy(hasPermission = true)
        }
        compose.onNodeWithTag(CalendarImportTags.SYNC).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, syncs); imports.value = imports.value.copy(busy = true) }
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertIsNotEnabled()
    }

    @Test fun staleCatalogAndOutgoingFailureStayVisibleWithoutLosingTheCalendar() {
        catalog.value = catalog.value.copy(status = CalendarCatalogStatus.ERROR,
            failure = CalendarCatalogFailure.UNREACHABLE, isRetryBlocked = true)
        creates.value = creates.value.copy(failed = true)
        start()
        compose.onNodeWithTag(CalendarCatalogTags.ERROR).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.STALE).assertExists()
        compose.onNodeWithTag(CalendarDashboardTags.OUTGOING_ERROR).assertExists()
        compose.onAllNodesWithText(summary.title).assertCountEquals(1)
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertIsNotEnabled()
    }

    @Test fun attentionExplainsPreservationAndRetriesOnlyAfterExplicitClickWithoutChangingConsent() {
        creates.value = creates.value.copy(failed = true, textSupportAvailable = true)
        start()
        compose.onNodeWithTag(SystemSyncAttentionTags.PRESERVED)
            .assertTextEquals(compose.activity.getString(R.string.system_create_error))
        compose.onNodeWithTag(SystemSyncAttentionTags.DETAILS).assertDoesNotExist()
        compose.onNodeWithTag(SystemSyncAttentionTags.WHY).performScrollTo().performClick()
        compose.onNodeWithTag(SystemSyncAttentionTags.DETAILS).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.sync_attention_explanation)) and
            hasAnyAncestor(hasTestTag(SystemSyncAttentionTags.DETAILS))).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.system_text_limits)) and
            hasAnyAncestor(hasTestTag(SystemSyncAttentionTags.DETAILS))).assertExists()
        compose.runOnIdle {
            assertEquals(0, syncs); assertEquals(0, refreshes); assertEquals(0, permissions); assertEquals(0, settings)
            assertEquals(emptyList<Boolean>(), createChanges); assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), textChanges); assertEquals(emptyList<Boolean>(), selectionChanges)
        }
        compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, syncs); assertEquals(true, creates.value.failed) }
        compose.onNodeWithTag(CalendarDashboardTags.OUTGOING_ERROR).assertExists()
        compose.onNodeWithTag(SystemSyncAttentionTags.WHY).performScrollTo().performClick()
        compose.onNodeWithTag(SystemSyncAttentionTags.DETAILS).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), createChanges); assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), textChanges); assertEquals(emptyList<Boolean>(), selectionChanges)
        }
    }

    @Test fun retryAndMainSyncRequirePermissionAnAvailableSelectionAndBothWorkersIdle() {
        creates.value = creates.value.copy(failed = true)
        start()
        val ready = imports.value
        val blocked = listOf(ready.copy(hasPermission = false), ready.copy(busy = true),
            ready.copy(selections = emptyList()), ready.copy(selections = listOf(selection.copy(enabled = false))),
            ready.copy(selections = listOf(selection.copy(available = false))))
        for (state in blocked) {
            compose.runOnIdle { imports.value = state }
            compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).assertIsNotEnabled()
            compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        }
        compose.runOnIdle { imports.value = ready; creates.value = creates.value.copy(busy = true) }
        compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).assertIsNotEnabled()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_dashboard_working)).assertExists()
        compose.runOnIdle { creates.value = creates.value.copy(busy = false) }
        compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).assertIsEnabled()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, syncs) }
    }

    @Test fun attentionFollowsActualFailureAndAuthenticationWithoutAutomaticRequests() {
        start()
        compose.onNodeWithTag(CalendarDashboardTags.OUTGOING_ERROR).assertDoesNotExist()
        compose.runOnIdle { creates.value = creates.value.copy(failed = true) }
        compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).assertExists()
        compose.runOnIdle { creates.value = creates.value.copy(failed = false) }
        compose.onNodeWithTag(CalendarDashboardTags.OUTGOING_ERROR).assertDoesNotExist()
        compose.runOnIdle { creates.value = creates.value.copy(failed = true); imports.value = imports.value.copy(authenticated = false) }
        compose.onNodeWithTag(CalendarDashboardTags.OUTGOING_ERROR).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, syncs) }
    }

    @Test fun legacyPanelWithoutRetryCallbackOffersExplanationWithoutAnOrphanAction() {
        creates.value = creates.value.copy(failed = true)
        compose.setContent {
            MaterialTheme {
                SystemCreatePanel(imports.value, creates.value, onMode = { _, enabled -> createChanges += enabled },
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
        compose.onNodeWithTag(SystemSyncAttentionTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(SystemSyncAttentionTags.WHY).performScrollTo().performClick()
        compose.onNodeWithTag(SystemSyncAttentionTags.DETAILS).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.system_title_limits)) and
            hasAnyAncestor(hasTestTag(SystemSyncAttentionTags.DETAILS))).assertExists()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), createChanges); assertEquals(0, syncs) }
    }

    @Test fun discoveryWithoutAPersistedScopeDoesNotInventAnActionableSelection() {
        imports.value = imports.value.copy(selections = emptyList())
        start()
        compose.onAllNodesWithText(summary.title).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_dashboard_configuring)).assertExists()
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertDoesNotExist()
        compose.onNodeWithTag(CalendarImportTags.SYNC).assertIsNotEnabled()
        compose.runOnIdle { imports.value = imports.value.copy(selections = listOf(selection)) }
        compose.onAllNodesWithText(summary.title).assertCountEquals(1)
        compose.onNodeWithTag(CalendarImportTags.TOGGLE).assertIsEnabled()
    }

    @Test fun signingOutHidesCalendarMetadataAndRetainedWork() {
        imports.value = imports.value.copy(selections = listOf(selection.copy(retainedLocalChangeCount = 1)))
        start()
        compose.onNodeWithTag(CalendarImportTags.LOCAL_CHANGES).assertExists()
        compose.runOnIdle { imports.value = imports.value.copy(authenticated = false) }
        compose.onNodeWithTag(CalendarDashboardTags.PANEL).assertDoesNotExist()
        compose.onAllNodesWithText(summary.title).assertCountEquals(0)
    }
}

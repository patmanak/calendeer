package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarCatalogPanel
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarCatalogTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation of synthetic metadata only; no account, adapter, storage or network is used. */
@RunWith(AndroidJUnit4::class)
class CalendarCatalogUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(CalendarCatalogState())
    private var refreshRequests = 0

    private fun start(initial: CalendarCatalogState = CalendarCatalogState()) {
        state.value = initial
        compose.setContent { MaterialTheme { CalendarCatalogPanel(state.value, { refreshRequests++ }) } }
    }

    @Test fun signedOutLoadingAndSuccessfulEmptyAreDifferentStates() {
        start()
        compose.onNodeWithTag(CalendarCatalogTags.SIGNED_OUT).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertDoesNotExist()
        compose.runOnIdle { state.value = CalendarCatalogState(status = CalendarCatalogStatus.LOADING) }
        compose.onNodeWithTag(CalendarCatalogTags.LOADING).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertIsNotEnabled()
        compose.runOnIdle { state.value = CalendarCatalogState(status = CalendarCatalogStatus.READY, canRefresh = true) }
        compose.onNodeWithTag(CalendarCatalogTags.EMPTY).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.LOADING).assertDoesNotExist()
        compose.onNodeWithTag(CalendarCatalogTags.ERROR).assertDoesNotExist()
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertIsEnabled()
    }

    @Test fun failedInitialLoadIsNeverPresentedAsAnEmptyAccount() {
        start(CalendarCatalogState(status = CalendarCatalogStatus.ERROR,
            failure = CalendarCatalogFailure.UNREACHABLE, canRefresh = true))
        compose.onNodeWithTag(CalendarCatalogTags.ERROR).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(CalendarCatalogTags.STALE).assertDoesNotExist()
        compose.onAllNodesWithTag(CalendarCatalogTags.ROW).assertCountEquals(0)
    }

    @Test fun metadataRemainsVisibleDuringRefreshAndIsMarkedStaleAfterFailure() {
        val calendars = listOf(
            CalendarSummary(CalendarId("fixture-a"), "Fixture calendar", 0x127766),
            CalendarSummary(CalendarId("fixture-b"), "", null),
        )
        start(CalendarCatalogState(status = CalendarCatalogStatus.READY, calendars = calendars, canRefresh = true))
        compose.onAllNodesWithTag(CalendarCatalogTags.ROW).assertCountEquals(2)
        compose.onNodeWithText("Fixture calendar").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.catalog_untitled)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.catalog_sync_unavailable)).assertExists()
        compose.runOnIdle { state.value = state.value.copy(status = CalendarCatalogStatus.LOADING, canRefresh = false) }
        compose.onNodeWithTag(CalendarCatalogTags.LOADING).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.STALE).assertDoesNotExist()
        compose.onAllNodesWithTag(CalendarCatalogTags.ROW).assertCountEquals(2)
        compose.runOnIdle { state.value = state.value.copy(status = CalendarCatalogStatus.ERROR,
            failure = CalendarCatalogFailure.TIMED_OUT, canRefresh = true) }
        compose.onNodeWithTag(CalendarCatalogTags.STALE).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.ERROR).assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.EMPTY).assertDoesNotExist()
        compose.onAllNodesWithTag(CalendarCatalogTags.ROW).assertCountEquals(2)
    }

    @Test fun refreshCallsOwnerAndIsDisabledDuringCooldown() {
        start(CalendarCatalogState(status = CalendarCatalogStatus.READY, canRefresh = true))
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).performClick()
        compose.runOnIdle {
            assertEquals(1, refreshRequests)
            state.value = CalendarCatalogState(status = CalendarCatalogStatus.ERROR,
                failure = CalendarCatalogFailure.RATE_LIMITED, canRefresh = false, isRetryBlocked = true)
        }
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.catalog_retry_wait)).assertExists()
        compose.runOnIdle { assertEquals(1, refreshRequests) }
    }
}

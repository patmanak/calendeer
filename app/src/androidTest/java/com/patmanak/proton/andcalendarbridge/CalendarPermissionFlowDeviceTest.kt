package com.patmanak.proton.andcalendarbridge

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarImportTags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in actual system dialog; no shell permission grants or unrelated permission changes. */
@RunWith(AndroidJUnit4::class)
class CalendarPermissionFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun declineThenAllowCalendarAccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarPermissionFlow") == "true")
        val app = compose.activity.application as BridgeApplication
        val projection = AndroidCalendarProjection(app)
        assumeTrue("This test requires an initial missing grant", !projection.hasPermissions())
        compose.waitUntil(60_000) { app.imports.state.value.authenticated }
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).performScrollTo().performClick()
        clickSystemButton("permission_deny_button")
        compose.waitForIdle()
        assertFalse(projection.hasPermissions())
        compose.onNodeWithTag(CalendarImportTags.PERMISSION).performScrollTo().performClick()
        clickSystemButton("permission_allow_button")
        compose.waitUntil(10_000) { app.imports.state.value.hasPermission }
        assertTrue(projection.hasPermissions())
    }
    private fun clickSystemButton(id: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var target: AccessibilityNodeInfo? = null
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName?.endsWith(":id/$id") == true && node.isEnabled) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        compose.waitUntil(10_000) { target = find(automation.rootInActiveWindow); target != null }
        assertTrue("Expected system permission action", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}

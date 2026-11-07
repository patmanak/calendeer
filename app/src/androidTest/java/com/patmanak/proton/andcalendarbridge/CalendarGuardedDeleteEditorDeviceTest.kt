// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.EventId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Actual system-editor deletion of one enrolled isolated interruption fixture.
 * No production consent changes and no direct Provider writes are allowed here.
 */
class CalendarGuardedDeleteEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deleteOnlyThePreparedIsolatedEventInTheSystemEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guardedDeleteEditor") == "true")
        val token = args.getString("deleteFixtureToken").orEmpty()
        val accountHash = args.getString("deleteAccountHash").orEmpty()
        assertTrue("Explicit fixture and account witnesses are required",
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        withTimeout(60_000) {
            while (!app.imports.state.value.let { it.authenticated && it.hasPermission && !it.busy &&
                    it.selections.count { selection -> selection.enabled && selection.available &&
                        selection.summary.title == "Calendeer QA Automatic Worker" } == 1 }) delay(100)
        }
        val source = app.imports.state.value.selections.single {
            it.enabled && it.available && it.summary.title == "Calendeer QA Automatic Worker"
        }.scope
        val prefs = app.getSharedPreferences("calendar-delete-guarded-v1-$token", Context.MODE_PRIVATE)
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        val physical = source.copy(generation = prefs.getLong("physical_generation", -1))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == source && it.enabled && it.available &&
                it.summary.title == "Calendeer QA Automatic Worker" } == 1 &&
            state.selections.none { it.scope == physical } }
        suspend fun guard() {
            DeleteProcessStopJourney.assertQuiet(app, source, ::accessible)
            assertTrue("Use only the exact prepared process fixture before any DELETE submission",
                frozen("token") == token && frozen("account_hash") == accountHash &&
                    frozen("scenario") in setOf("PROCESS_INTERRUPTION", "PROOF_PROCESS_INTERRUPTION", "ACK_PROCESS_INTERRUPTION", "RESPONSE_LOSS") && prefs.getBoolean("prepared", false) &&
                    !prefs.getBoolean("editor_deleted", false) && !prefs.getBoolean("delete_requested", false) &&
                    !prefs.contains("attempt_id") && !prefs.contains("process_stop_ready") &&
                    frozen("account") == source.account.value && frozen("calendar") == source.calendar.value &&
                    prefs.getLong("generation", -1) == source.generation && physical.generation > 0 && physical != source)
            assertTrue("Authentication must still match the controlled account",
                ProtonCreateSubmissionProbe.matchesAccount(app, source.account, accountHash))
        }
        val projection = AndroidCalendarProjection(app)
        projection.admitOperation = ::accessible
        val account = projection.accountFor(source.account)
        val rowId = prefs.getLong("row", -1)
        val calendarId = prefs.getLong("native_calendar", -1)
        assertTrue("Require positive enrolled native locators", rowId > 0 && calendarId > 0)
        val key = projection.eventIdentityKey(physical, EventId(frozen("event")))
        val queryUri = Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun nativeDeleted(): Boolean = app.contentResolver.query(queryUri,
            arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA4, Events.DIRTY, Events.DELETED),
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(rowId.toString(), calendarId.toString()), null)!!.use {
            assertTrue("Require the exact isolated row and both physical identity keys", it.count == 1 && it.moveToFirst() &&
                it.getLong(0) == rowId && it.getLong(1) == calendarId && it.getString(2) == key && it.getString(3) == key)
            val dirty = it.getString(4); val deleted = it.getString(5)
            assertTrue("Admit only the clean original or its editor dirty tombstone",
                dirty == "0" && deleted == "0" || dirty == "1" && deleted == "1")
            dirty == "1" && deleted == "1"
        }
        val editorResources = app.packageManager.getResourcesForApplication(EDITOR)
        val deletionMessageId = editorResources.getIdentifier("delete_this_event_title", "string", EDITOR)
        assertTrue("Require the installed editor's specific deletion question", deletionMessageId != 0)
        val deletionMessage = editorResources.getString(deletionMessageId)
        assertTrue("Require a nonempty localized deletion question", deletionMessage.isNotBlank())
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            guard()
            assertTrue("Never repeat a destructive editor journey", !nativeDeleted())
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = originalFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            compose.runOnUiThread {
                compose.activity.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, rowId))
                    .setPackage(EDITOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            suspend fun clickQualified(confirmation: Boolean) {
                withTimeout(15_000) {
                    while (!QualifiedCalendarEditorUi.qualifiedRoot(automation.rootInActiveWindow, EDITOR,
                            frozen("title"), deletionMessage, confirmation, click = false)) {
                        guard(); delay(100)
                    }
                }
                delay(150)
                guard()
                assertTrue("The enrolled row remains clean before deletion confirmation", !nativeDeleted())
                assertTrue("Requalify the editor independently before each click",
                    QualifiedCalendarEditorUi.qualifiedRoot(automation.rootInActiveWindow, EDITOR,
                        frozen("title"), deletionMessage, confirmation, click = true))
            }
            clickQualified(confirmation = false)
            clickQualified(confirmation = true)
            withTimeout(15_000) { while (!nativeDeleted()) { guard(); delay(100) } }
            guard()
        } finally {
            try { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
            finally { projection.admitOperation = { false } }
        }
        guard()
        assertTrue("Retain the exact real editor tombstone", nativeDeleted())
        assertTrue("Freeze completion only after UI restoration and row proof",
            prefs.edit().putBoolean("editor_deleted", true).commit())
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("guarded_delete_system_editor_confirmed", true) })
    }

    private companion object { const val EDITOR = "foundation.e.calendar" }
}

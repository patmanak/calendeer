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
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit UI utility for one already enrolled disposable worker fixture. Uses the actual
 * system editor and its confirmation; the Provider is read-only throughout this test.
 */
class CalendarWorkerDeleteEditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deleteOnlyThePreparedWorkerEventInTheSystemEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("workerDeleteEditor") == "true")
        val token = args.getString("deleteFixtureToken").orEmpty()
        val accountHash = args.getString("deleteAccountHash").orEmpty()
        val calendarWitness = WorkerDeleteFixtureScope.witness(args)
        assertTrue("Explicit fixture and account witnesses are required",
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        DeleteNetworkInterruptionJourney.assertOffline(app)
        withTimeout(60_000) {
            while (!app.imports.state.value.let { it.authenticated && it.hasPermission && !it.busy &&
                    it.selections.count { selection -> WorkerDeleteFixtureScope.matches(selection, calendarWitness) } == 1 }) {
                DeleteNetworkInterruptionJourney.assertOffline(app); delay(100)
            }
        }
        val target = app.imports.state.value.selections.single {
            WorkerDeleteFixtureScope.matches(it, calendarWitness)
        }.scope
        val prefs = app.getSharedPreferences("calendar-delete-worker-v1-$token", Context.MODE_PRIVATE)
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        assertTrue("All five original and owned modes must already be frozen",
            (0..4).all { prefs.contains("original_$it") && prefs.contains("owned_$it") })
        val original = (0..4).map { prefs.getBoolean("original_$it", false) }
        fun owned() = (0..4).map { prefs.getBoolean("owned_$it", false) }
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == target && WorkerDeleteFixtureScope.matches(it, calendarWitness) } == 1 }
        fun flags() = SystemCreateWorkStore(app).use {
            listOf(it.modeEnabled(target), it.titlesEnabled(target), it.textsEnabled(target), it.timesEnabled(target), it.deletesEnabled(target))
        }
        suspend fun guard() {
            DeleteNetworkInterruptionJourney.assertOffline(app)
            assertTrue("Retain the exact authenticated QA selection and compare-owned consent",
                accessible() && flags() == owned() && !original[4] && !prefs.getBoolean("mode_write_pending", false) &&
                    (original[0] || original.none { it } && args.getString("allowReadOnlyDeleteWorker") == "true" &&
                        prefs.getBoolean("readonly_activation", false)))
            assertTrue("Use only the frozen prepared offline fixture before worker dispatch",
                frozen("token") == token && frozen("account_hash") == accountHash && frozen("profile") == "TIMED" &&
                    (prefs.getBoolean("network_journey", false) xor prefs.getBoolean("process_journey", false)) &&
                    prefs.getString("calendar_witness", "") == calendarWitness && prefs.getBoolean("offline_journey", false) &&
                    prefs.getBoolean("prepared", false) && !prefs.contains("network_run_started") && !prefs.contains("process_run_requested") &&
                    !prefs.getBoolean("editor_deleted", false) && frozen("account") == target.account.value &&
                    frozen("calendar") == target.calendar.value && prefs.getLong("generation", -1) == target.generation)
            assertTrue("Current authentication must still match the controlled account",
                ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        }
        val projection = AndroidCalendarProjection(app)
        projection.admitOperation = ::accessible
        val account = projection.accountFor(target.account)
        val rowId = prefs.getLong("row", -1)
        val calendarId = prefs.getLong("native_calendar", -1)
        assertTrue("Require positive enrolled native locators", rowId > 0 && calendarId > 0)
        val key = projection.eventIdentityKey(target, EventId(frozen("event")))
        val queryUri = Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun nativeDeleted(): Boolean {
            return app.contentResolver.query(queryUri,
                arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA4, Events.DIRTY, Events.DELETED),
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(rowId.toString(), calendarId.toString()), null)!!.use {
                assertTrue("Require exactly the enrolled ordinary production row", it.count == 1 && it.moveToFirst() &&
                    it.getLong(0) == rowId && it.getLong(1) == calendarId && it.getString(2) == key && it.getString(3) == key)
                val dirty = it.getString(4); val deleted = it.getString(5)
                assertTrue("Only the original clean row or its editor dirty tombstone is admissible",
                    dirty == "0" && deleted == "0" || dirty == "1" && deleted == "1")
                dirty == "1" && deleted == "1"
            }
        }
        val editorResources = app.packageManager.getResourcesForApplication(EDITOR)
        val deletionMessageId = editorResources.getIdentifier("delete_this_event_title", "string", EDITOR)
        assertTrue("Require the installed editor's specific ordinary-event deletion question", deletionMessageId != 0)
        val deletionMessage = editorResources.getString(deletionMessageId)
        assertTrue("Require a nonempty localized deletion question", deletionMessage.isNotBlank())
        val automation = instrumentation.uiAutomation
        val originalServiceFlags = automation.serviceInfo.flags
        fun recoverPending() {
            if (!prefs.getBoolean("mode_write_pending", false)) return
            val before = (0..4).map { prefs.getBoolean("before_$it", false) }
            val after = (0..4).map { prefs.getBoolean("after_$it", false) }
            val current = flags()
            assertTrue("Interrupted setter recovery admits only its exact precomputed outcomes",
                accessible() && (current == before || current == after))
            val edit = prefs.edit().putBoolean("mode_write_pending", false)
            current.forEachIndexed { index, value -> edit.putBoolean("owned_$index", value) }
            assertTrue(edit.commit())
        }
        fun writeGeneral(enabled: Boolean) {
            assertTrue("Mode setter requires current source and exact owned modes on the UI thread",
                android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && accessible() && flags() == owned())
            val before = owned()
            val after = before.toMutableList().apply {
                if (!enabled) indices.forEach { this[it] = false } else this[0] = true
            }
            val pending = prefs.edit().putBoolean("mode_write_pending", true)
            before.forEachIndexed { index, value -> pending.putBoolean("before_$index", value) }
            after.forEachIndexed { index, value -> pending.putBoolean("after_$index", value) }
            assertTrue(pending.commit())
            try {
                app.systemCreates.setEnabled(app.imports.state.value.selections.single { it.scope == target }, enabled)
            } finally {
                val current = flags()
                assertTrue("Never adopt an unrelated owner preference", current == before || current == after)
                val edit = prefs.edit().putBoolean("mode_write_pending", false)
                current.forEachIndexed { index, value -> edit.putBoolean("owned_$index", value) }
                assertTrue(edit.commit())
            }
            assertTrue("Require exact synchronous mode result", flags() == after)
        }
        var restore = !prefs.getBoolean("modes_restored", false)
        var editorConfirmed = false
        try {
            recoverPending()
            guard()
            assertTrue("A fresh editor journey starts with fully restored original modes",
                prefs.getBoolean("modes_restored", false) && flags() == original)
            assertTrue("Never repeat a destructive editor journey", !nativeDeleted())
            assertEditorActivationQuiescent(app, projection, target, EventId(frozen("event")))
            guard()
            if (!original[0]) {
                restore = true
                assertTrue(prefs.edit().putBoolean("modes_restored", false).commit())
                compose.runOnUiThread { writeGeneral(true) }
                guard()
                assertTrue("The editor lease enables only general native writability",
                    flags() == listOf(true, false, false, false, false))
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = originalServiceFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            compose.runOnUiThread {
                compose.activity.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, rowId))
                    .setPackage(EDITOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            suspend fun clickQualified(confirmation: Boolean) {
                // Two independently obtained, qualified roots prevent acting on a stale tree.
                withTimeout(15_000) {
                    while (!QualifiedCalendarEditorUi.qualifiedRoot(automation.rootInActiveWindow, EDITOR, frozen("title"), deletionMessage, confirmation, click = false)) {
                        guard(); delay(100)
                    }
                }
                delay(150)
                guard()
                assertTrue("The enrolled row stays clean until the editor confirms deletion", !nativeDeleted())
                assertTrue("The same qualified editor surface must remain before clicking",
                    QualifiedCalendarEditorUi.qualifiedRoot(automation.rootInActiveWindow, EDITOR, frozen("title"), deletionMessage, confirmation, click = true))
            }
            clickQualified(confirmation = false)
            clickQualified(confirmation = true)
            withTimeout(15_000) {
                while (!nativeDeleted()) { guard(); delay(100) }
            }
            guard()
            assertTrue("Keep the real editor tombstone for production offline capture", nativeDeleted())
            editorConfirmed = true
        } finally {
            try {
                withContext(NonCancellable) {
                    withTimeout(10_000) {
                        if (restore) {
                            recoverPending()
                            assertTrue("Restore only current exact fixture ownership",
                                accessible() && flags() == owned() && ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
                            // Only the originally read-only branch obtains this lease. Its original
                            // five-mode vector is all false, so the ordinary general setter restores all.
                            assertTrue("The temporary editor lease cannot broaden its restoration rights", original.none { it })
                            compose.runOnUiThread { writeGeneral(false) }
                            assertTrue("Restore every original consent flag", flags() == original)
                            assertTrue(prefs.edit().putBoolean("modes_restored", true).commit())
                        }
                    }
                }
            } finally {
                try {
                    automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
                } finally {
                    projection.admitOperation = { false }
                    DeleteNetworkInterruptionJourney.assertOffline(app)
                }
            }
        }
        assertTrue("Editor completion requires restoration and the retained real dirty tombstone",
            editorConfirmed && prefs.getBoolean("modes_restored", false) && flags() == original && nativeDeleted())
        assertTrue("Freeze successful editor completion only after full restoration", prefs.edit().putBoolean("editor_deleted", true).commit())
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("system_delete_editor_confirmed", true) })
    }

    private suspend fun assertEditorActivationQuiescent(app: Context, projection: AndroidCalendarProjection,
        target: CalendarScope, event: EventId) {
        // The enrolled event is still clean. No enrolled-owner or dirty-row exception is
        // needed; this stronger shared audit covers every SQL family and native row.
        SystemWorkerFixtureAudit.assertQuiescent(app, projection, target, allowPendingIncoming = true)
        TitleUpdateAttemptStore(app).use { shared ->
            shared.readableDatabase.rawQuery("SELECT 1 FROM delete_attempts WHERE account=? AND calendar=? " +
                "AND (event=? OR completion IS NULL) LIMIT 1", arrayOf(target.account.value, target.calendar.value, event.value)).use {
                assertTrue("No prior target request or foreign uncertain deletion may borrow consent", it.count == 0)
            }
        }
    }

    private companion object { const val EDITOR = "foundation.e.calendar" }
}

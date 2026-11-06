// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Optional bounded lease for one existing general-only setting, across process-death tests.
 * Durable before/after ownership precedes the synchronous production setter. RESTORE can
 * recover either side of an interrupted setter; it never adopts a different preference.
 */
class CalendarProcessWritePauseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pauseOrRestoreOnlyTheFrozenGeneralWriteSetting() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("processWritePausePhase").orEmpty()
        assumeTrue(phase in setOf("PAUSE", "RESTORE"))
        val token = args.getString("deleteFixtureToken").orEmpty()
        val accountHash = args.getString("deleteAccountHash").orEmpty()
        assertTrue(token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        withTimeout(60_000) {
            while (!app.imports.state.value.let { it.authenticated && it.hasPermission && !it.busy &&
                    it.selections.isNotEmpty() } || app.systemCreates.state.value.busy) delay(100)
        }
        val prefs = app.getSharedPreferences("calendar-delete-write-pause-v1-$token", Context.MODE_PRIVATE)
        SystemCreateWorkStore(app).use { modes ->
            fun flags(scope: CalendarScope) = listOf(modes.modeEnabled(scope), modes.titlesEnabled(scope),
                modes.textsEnabled(scope), modes.timesEnabled(scope), modes.deletesEnabled(scope))
            if (!prefs.contains("token")) {
                assertTrue("Only PAUSE may enroll a lease", phase == "PAUSE")
                val selections = app.imports.state.value.selections
                val enabled = selections.filter { modes.modeEnabled(it.scope) }
                assertTrue("Pause only one existing general-only mode", enabled.size == 1 &&
                    selections.all { flags(it.scope).drop(1).none { value -> value } })
                val target = enabled.single()
                assertTrue("The selected mode must belong to the controlled accessible account",
                    target.enabled && target.available && ProtonCreateSubmissionProbe.matchesAccount(app, target.scope.account, accountHash))
                assertTrue("Freeze ownership before changing the setting", prefs.edit().putString("token", token)
                    .putString("account_hash", accountHash).putString("account", target.scope.account.value)
                    .putString("calendar", target.scope.calendar.value).putLong("generation", target.scope.generation)
                    .putBoolean("pause_requested", true).commit())
            }
            val target = CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
            suspend fun guard() {
                assertTrue("Retain the exact current accessible lease and idle writes",
                    prefs.getString("token", null) == token && prefs.getString("account_hash", null) == accountHash &&
                        prefs.getBoolean("pause_requested", false) && app.imports.state.value.let { state ->
                            state.authenticated && state.hasPermission && state.selections.count {
                                it.scope == target && it.enabled && it.available } == 1 &&
                                state.selections.filter { it.scope != target }.all { flags(it.scope).none { value -> value } }
                        } && !app.systemCreates.state.value.busy &&
                        flags(target).drop(1).none { it } && ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
            }
            guard()
            assertTrue("Never reuse a completed lease", !prefs.getBoolean("restored", false))
            if (phase == "RESTORE") {
                // pause_requested is the durable before-write owner. A process may die
                // after disabling the setting but before recording paused; both original
                // general=true and suspended general=false are safe restoration inputs.
                assertTrue("Restoration requires the durable before-write owner", prefs.getBoolean("pause_requested", false))
                assertTrue("Freeze restoration before the setter", prefs.edit().putBoolean("restore_requested", true).commit())
            } else assertTrue("Do not pause after restoration started", !prefs.getBoolean("restore_requested", false))
            val desired = phase == "RESTORE"
            guard()
            compose.runOnUiThread {
                assertTrue("Recheck exact scoped setting before synchronous mutation",
                    !app.systemCreates.state.value.busy && flags(target).drop(1).none { it })
                val selection = app.imports.state.value.selections.single { it.scope == target && it.enabled && it.available }
                app.systemCreates.setEnabled(selection, desired)
                assertTrue("The setter must retain all four disabled submodes", flags(target) == listOf(desired, false, false, false, false))
            }
            if (!desired) withTimeout(30_000) {
                while (target in app.systemCreates.state.value.enabled || app.systemCreates.state.value.busy) delay(100)
            }
            // Restoration starts normal asynchronous work. Its UI publication/settlement is
            // not required to acknowledge the synchronous persisted preference. Never wait
            // for that work to finish while claiming the preference itself is unrestored.
            assertTrue("Retain the exact restored/pause preference and account", flags(target) ==
                listOf(desired, false, false, false, false) &&
                ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
            assertTrue(prefs.edit().putBoolean(if (desired) "restored" else "paused", true).commit())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean(if (desired) "process_write_restore_verified" else "process_write_pause_verified", true)
            })
        }
    }
}

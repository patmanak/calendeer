// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonInheritedNotificationsProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit web-created QA fixture only. Retained SQLite history, real adapter, no direct HTTP.
 * Reruns inspect existing work; never reset an uncertain attempt or recreate the web event. */
class CalendarInheritedNotificationsLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun webDefaultsSurviveAnActualPersonalTitleEdit() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("inheritedNotifications") == "true")
        val hash = args.getString("personalAccountHash").orEmpty()
        assertTrue("Explicit test-account enrollment required", hash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        fun ready() = app.imports.state.value.let { it.authenticated && it.selections.isNotEmpty() }
        withTimeoutOrNull(20_000) { while (!ready()) delay(100) }
        check(ready()) {
            "Test session unavailable: destination=${app.authentication.state.value.destination}, " +
                "authenticated=${app.imports.state.value.authenticated}, busy=${app.imports.state.value.busy}, " +
                "selections=${app.imports.state.value.selections.size}"
        }
        val state = app.imports.state.value
        val account = state.selections.map { it.scope.account }.distinct().single()
        assertTrue("Wrong account", ProtonCreateSubmissionProbe.matchesAccount(app, account, hash))
        val target = state.selections.single { it.available && it.summary.title == "Calendeer QA Personal Worker" }.scope
        fun active(scope: CalendarScope) = scope == target && app.imports.state.value.let {
            it.authenticated && it.selections.any { selected -> selected.scope == target && selected.available }
        }
        val prefix = "Calendeer QA B2 default reminders 20261113"
        val updates = ProtonAuthentication.personalUpdates(app)
        val id = MutationId("web-default-title-20261113")
        // Deliberately retained: uncertain outcomes must remain inspectable across reruns.
        TitleUpdateAttemptStore(app, "qa-inherited-notifications-20261113.db").use { attempts ->
            val journal = attempts.personalJournal
            var record = journal.read(target, id, ::active)
            if (record == null) {
                val original = ProtonInheritedNotificationsProbe.find(app, target, prefix)
                assertTrue("Web-created fixture must still inherit defaults", original.inherited)
                assertEquals(listOf(EventReminder(15)), original.event.content.details!!.reminders)
                val intent = LocalEditIntent(MutationId("web-default-intent-20261113"), target,
                    ProtonAuthentication.events(app).format, original.event,
                    original.event.content.copy(title = "$prefix verified"))
                val prep = checkNotNull(ProtonAuthentication.personalPreparations(app).preparePersonal(intent,
                    Instant.now().truncatedTo(ChronoUnit.SECONDS), ::active))
                assertTrue(ProtonInheritedNotificationsProbe.preparationKeepsNull(prep))
                record = checkNotNull(updates.prepareSubmission(id, prep, ::active))
                assertTrue(journal.remember(record, ::active))
            }
            val retained = checkNotNull(record)
            val result = if (retained.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
                updates.inspect(retained, journal, ::active)
            else updates.submit(retained, journal, ::active, { scope ->
                active(scope) && ProtonInheritedNotificationsProbe.find(app, target, prefix).let {
                    it.inherited && it.event == retained.preparation.intent.base
                }
            }, ::active)
            assertTrue("Real adapter must verify the retained request; never reset UNKNOWN", result is PersonalUpdateSubmissionObservation.Verified)
            val verified = result as PersonalUpdateSubmissionObservation.Verified
            assertTrue(journal.retainVerified(PersonalUpdateVerifiedResult.of(verified.record, verified), ::active))
            val readback = ProtonInheritedNotificationsProbe.find(app, target, prefix)
            assertTrue("Unchanged reminders must preserve raw null", readback.inherited)
            assertTrue(readback.defaultEmailsPreserved)
            assertEquals("$prefix verified", readback.event.content.title)
            assertEquals(listOf(EventReminder(15)), readback.event.content.details!!.reminders)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("web_defaults_null_kept_after_real_update", true)
                putBoolean("web_defaults_email_configuration_preserved", true)
            })
        }
    }
}

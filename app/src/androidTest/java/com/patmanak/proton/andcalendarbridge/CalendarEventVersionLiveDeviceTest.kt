// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit inspection of one retained synthetic event. No submissions or Provider edits. */
@RunWith(AndroidJUnit4::class)
class CalendarEventVersionLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedFixtureVersionMetadataIsObservedWithoutMutation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarEventVersionInspection") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("An explicit retained fixture and account are required",
            token.matches(Regex("[a-f0-9]{12}")) && identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated &&
            app.imports.state.value.selections.isNotEmpty() && !app.imports.state.value.busy }
        val prefs = app.getSharedPreferences("known-empty-create-live-qualification-v2", Context.MODE_PRIVATE)
        assertTrue("Read only the previously submitted retained fixture",
            prefs.getString("token", null) == token && prefs.getBoolean("submission_requested", false))
        val scope = CalendarScope(AccountId(requireNotNull(prefs.getString("account", null))),
            CalendarId(requireNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        assertTrue("Authorize the exact account before reads",
            ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
        val selections = app.imports.state.value.selections.map { Triple(it.scope, it.enabled, it.available) }
        assertTrue("The retained calendar must be selected and accessible",
            app.imports.state.value.selections.any { it.scope == scope && it.enabled && it.available })
        val attempt = CreateAttemptStore(app, "calendar-known-empty-create-live-qualification-v2.db").use {
            requireNotNull(it.read(scope, MutationId("synthetic-known-empty-live-create-$token")))
        }
        assertTrue("Never admit a new submission", attempt.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val port = ProtonAuthentication.createSubmissions(app)
        suspend fun observe(): RemoteChange.Upsert {
            val result = port.inspect(attempt)
            assertTrue("Require verified readback", result is CreateSubmissionObservation.Verified)
            return (result as CreateSubmissionObservation.Verified).event
        }
        val base = observe()
        assertTrue("Require the recognizable simple synthetic fixture",
            base.content.title == "Calendeer QA — création sans rappels $token" &&
                base.content.details == EventDetails() && base.content.recurrence == null)
        suspend fun stored() = ImportStore(app).use { store ->
            Triple(store.projectedBaseline(scope, base.eventId), store.localEdit(scope, base.eventId),
                store.localDelete(scope, base.eventId))
        }
        val beforeStored = stored()
        assertTrue("Require the existing acknowledged baseline", beforeStored.first == base)
        val provider = AndroidCalendarProjection(app)
        assertTrue("Calendar permission is required", provider.hasPermissions())
        val witness = provider.inspectOwnedEvent(scope, base.eventId, base.revision)
        assertTrue("Require the existing owned Android projection", witness != null)
        val profile = ProtonEventVersionProbe.inspect(app, scope, base.eventId, identityHash)
        assertTrue("The authenticated metadata GET must succeed", profile != null)
        assertTrue("Decoded remote readback is unchanged", observe() == base)
        assertTrue("Import history and local intentions are unchanged", stored() == beforeStored)
        assertTrue("The Provider ownership witness is unchanged",
            provider.inspectOwnedEvent(scope, base.eventId, base.revision) == witness)
        assertTrue("Selections and access are unchanged",
            app.imports.state.value.selections.map { Triple(it.scope, it.enabled, it.available) } == selections)
        CreateAttemptStore(app, "calendar-known-empty-create-live-qualification-v2.db").use {
            assertTrue("The uncertain submission record is unchanged", it.read(scope, attempt.id) == attempt)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("event_etag_shape", requireNotNull(profile).etag.name)
            putString("event_last_modified_shape", profile.lastModified.name)
            putString("event_modify_time_shape", profile.modifyTime.name)
            putString("event_create_time_shape", profile.createTime.name)
            putString("remote_and_local_witnesses_unchanged", "true")
            putString("outgoing_update_delete_enabled", "false")
        })
    }
}

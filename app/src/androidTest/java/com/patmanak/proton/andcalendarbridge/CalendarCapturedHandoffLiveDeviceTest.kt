// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production owner, journals, Provider and retained session. CREATE is one-shot; INSPECT never sends. */
@RunWith(AndroidJUnit4::class)
class CalendarCapturedHandoffLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun capturedOriginalReachesHandoffThroughTheRealProtonAdapter() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarCapturedHandoff") == "true")
        val phase = args.getString("capturedHandoffPhase")
        assertTrue("Explicit qualification phase required", phase in setOf("CREATE", "INSPECT"))
        val token = args.getString("createFixtureToken").orEmpty()
        val accountHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Unique synthetic fixture required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Exact authorized account required", accountHash.matches(Regex("[a-f0-9]{64}")))
        val stamp = Instant.parse(args.getString("createFixtureStamp"))
        val start = Instant.parse(args.getString("createFixtureStart"))
        assertTrue("Frozen second-precision timestamps required", stamp.nano == 0 && start.nano == 0)

        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty()
        }
        val projection = AndroidCalendarProjection(app)
        assertTrue("Both calendar grants required", projection.hasPermissions())
        val prefs = app.getSharedPreferences("captured-create-live-qualification-v2", Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertTrue("An accessible selected calendar required", selected.isNotEmpty())
        val target = if (phase == "CREATE") {
            // A failure after freezing is intentionally not an invitation to create another fixture.
            assertTrue("Never replace or repeat this retained fixture", !prefs.contains("token"))
            selected.first().scope
        } else {
            assertTrue("Inspection requires the original fixture", prefs.getString("token", null) == token)
            assertTrue("Preserve frozen timestamps", prefs.getString("stamp", null) == stamp.toString() &&
                prefs.getString("start", null) == start.toString())
            CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        }
        assertTrue("The frozen calendar must stay selected and accessible", selected.any { it.scope == target })
        assertTrue("Authorize the retained test session", ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        val remote = ProtonAuthentication.events(app)
        assertTrue("The current checkpoint must use the real adapter", ImportStore(app).use { reader ->
            reader.transaction(target) { checkpoint?.format == remote.format }
        })
        val content = EventContent("Calendeer QA — création capturée $token",
            EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris")),
            "Création Android, envoi unique Proton et transfert de synchronisation ; exemple conservé.",
            "Test Calendeer", details = EventDetails())

        if (phase == "CREATE") qualifyRetainedNegativeFixture(app, target, token, remote.format)

        if (phase == "CREATE") {
            assertTrue("Freeze identities before any Provider creation", prefs.edit()
                .putString("token", token).putString("stamp", stamp.toString()).putString("start", start.toString())
                .putString("account", target.account.value).putString("calendar", target.calendar.value)
                .putLong("generation", target.generation).putString("local", UUID.randomUUID().toString())
                .putString("capture", UUID.randomUUID().toString()).putString("attempt", UUID.randomUUID().toString()).commit())
        }
        val local = LocalEventId(checkNotNull(prefs.getString("local", null)))
        val capture = MutationId(checkNotNull(prefs.getString("capture", null)))
        val attempt = MutationId(checkNotNull(prefs.getString("attempt", null)))
        val port = ProtonAuthentication.createSubmissions(app)
        assertTrue("Explicit-empty preparation must be available", port.knownEmptySubmissionFormat ==
            AdapterFormat("proton-calendar-create-submission", 2))

        if (phase == "CREATE") {
            val summary = selected.single { it.scope == target }.summary
            val calendar = projection.ensureCalendar(target, summary)
            val account = projection.accountFor(target.account)
            val uri = Events.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
            assertTrue("Freeze native creation admission", prefs.edit().putBoolean("row_creation_requested", true).commit())
            val row = ContentUris.parseId(checkNotNull(app.contentResolver.insert(uri, candidate(calendar, content))))
            assertTrue("Retain the original native locator", prefs.edit().putLong("calendar_row", calendar).putLong("event_row", row).commit())
            val captured = app.imports.captureLocalCreate(target, row, local, capture, stamp)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("capture_result", captured.name) })
            assertTrue("Capture the exact original intention", captured == LocalEditCaptureResult.RETAINED)
            status("captured_local_intention_retained")
            // The fresh preparation lease belongs to this process owner. Do not split these phases.
            assertTrue("Prepare the captured intention once", app.imports.stageLocalCreateSubmission(target, local, capture, attempt, port) ==
                LocalCreateSubmissionResult.RETAINED)
            assertTrue("Freeze submission request before entering the real port", prefs.edit().putBoolean("submission_requested", true).commit())
            app.imports.submitLocalCreate(target, local, capture, attempt, port)
        } else {
            assertTrue("Inspection never replaces a missing submission request", prefs.getBoolean("submission_requested", false))
        }

        // A missing or still-PREPARED record is a blocked one-shot journey, never permission to resend.
        val unknown = CreateAttemptStore(app).use { it.read(target, attempt) }
        assertTrue("Retain a non-resendable admission before inspection", unknown?.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val record = checkNotNull(unknown)
        status("captured_submission_admitted_unknown")
        val submission = LocalCreateStore(app).use { it.readSubmission(target, local) }
        assertTrue("The original captured preparation must survive reopening", submission?.reservation?.let {
            it.intent.reservation.id == capture && it.attempt == attempt && it.draft.content == content
        } == true)
        val identity = CapturedCreateDispatchIdentity.of(checkNotNull(submission))
        val bindingBefore = CreateAttemptStore(app).use { it.capturedBinding(identity) }
        val handoffBefore = bindingBefore?.let { binding ->
            ImportStore(app).use { it.capturedHandoff(target, binding.eventId) }
        }
        if (handoffBefore == null) {
            // This entry point uses authenticated inspectCaptured for an unknown attempt; no second PUT.
            assertTrue("Bind the authenticated captured readback", app.imports.bindLocalCreateObservation(target, local, capture, attempt, port) ==
                CapturedCreateBindingResult.RETAINED)
            status("captured_remote_binding_verified")
            assertTrue("Attach to the original native row", app.imports.attachLocalCreateBinding(target, local, capture, attempt) ==
                CapturedCreateAttachmentResult.ATTACHED)
            assertTrue("Retain immutable original creation evidence", app.imports.retainLocalCreateBaseline(target, local, capture, attempt) ==
                CapturedCreateBaselineResult.RETAINED)
            assertTrue("Acknowledge only the unchanged original", app.imports.acknowledgeLocalCreate(target, local, capture, attempt) ==
                CapturedCreateAcknowledgementResult.ACKNOWLEDGED)
            status("captured_original_acknowledged")
        }
        assertTrue("Transfer using the current reference without resubmission", app.imports.handoffLocalCreate(target, local, capture, attempt) ==
            CapturedCreateHandoffResult.HANDED_OFF)
        val binding = CreateAttemptStore(app).use { it.capturedBinding(identity) }
        assertTrue("Keep the exact captured remote association", binding != null && binding.identity == identity)
        val bound = checkNotNull(binding)
        val role = ImportStore(app).use { it.capturedHandoff(target, bound.eventId) }
        assertTrue("Keep a released current handoff", role?.let {
            it.state == CapturedCreateHandoffState.RELEASED && !it.waiting
        } == true)
        val frame = checkNotNull(role).frame
        assertTrue("Qualify the current clean reference", projection.inspectCapturedHandoffBase(frame)?.frame == frame)
        val witness = projection.inspectOwnedEvent(target, bound.eventId, frame.base.revision)
        assertTrue("Keep the same original native event and calendar", witness != null &&
            witness.eventRow == prefs.getLong("event_row", -1) && witness.calendarRow == prefs.getLong("calendar_row", -1))
        CreateAttemptStore(app).use { reopened ->
            assertTrue("Keep admission and protected response immutable", reopened.read(target, attempt) == record)
            assertTrue("Refuse another claim after reopening", !reopened.claim(record.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null)))
        }
        status("captured_handoff_released")
        status("captured_original_identity_preserved")
        status("captured_attempt_non_resendable")
        // Retain the event, original Provider row, production journals and private fixture preferences.
    }

    private fun status(name: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putBoolean(name, true) })

    /** Retain the rejected self-attendee case; never erase or readmit its consumed reservation.
     * A distinct positive fixture is permitted only after proving the old case never staged/sent. */
    private suspend fun qualifyRetainedNegativeFixture(app: BridgeApplication, target: CalendarScope, token: String, format: AdapterFormat) {
        val prior = app.getSharedPreferences("captured-create-live-qualification-v1", Context.MODE_PRIVATE)
        if (!prior.contains("token")) return
        assertTrue("A distinct positive fixture must not reuse the rejected identity", prior.getString("token", null) != token)
        val oldScope = CalendarScope(AccountId(checkNotNull(prior.getString("account", null))),
            CalendarId(checkNotNull(prior.getString("calendar", null))), prior.getLong("generation", -1))
        assertTrue("Inspect only the same authorized selected scope", oldScope == target)
        assertTrue("Never replace an uncertain submission", !prior.getBoolean("submission_requested", false))
        val oldLocal = LocalEventId(checkNotNull(prior.getString("local", null)))
        val oldAttempt = MutationId(checkNotNull(prior.getString("attempt", null)))
        LocalCreateStore(app).use { journal ->
            val original = LocalCreateReservation(MutationId(checkNotNull(prior.getString("capture", null))), oldLocal,
                target, format, Instant.parse(prior.getString("stamp", null)))
            assertTrue("Keep the exact consumed negative reservation", journal.readReservation(target, oldLocal) == original)
            assertTrue("The rejected fixture must have no intention or submission", journal.readIntent(target, oldLocal) == null &&
                journal.readSubmission(target, oldLocal) == null)
            journal.readableDatabase.rawQuery("SELECT COUNT(*) FROM local_create_submissions WHERE account=? AND calendar=? AND generation=? AND local_event=?",
                arrayOf(target.account.value, target.calendar.value, target.generation.toString(), oldLocal.value)).use {
                assertTrue("Raw staging evidence must be absent", it.moveToFirst() && it.getInt(0) == 0)
            }
        }
        CreateAttemptStore(app).use { journal ->
            assertTrue("No rejected dispatch or raw captured link may exist", journal.read(target, oldAttempt) == null && !journal.isCaptured(target, oldAttempt))
            journal.readableDatabase.rawQuery("SELECT COUNT(*) FROM create_attempts WHERE account=? AND calendar=? AND generation=? AND mutation=?",
                arrayOf(target.account.value, target.calendar.value, target.generation.toString(), oldAttempt.value)).use {
                assertTrue("Raw remote admission must be absent", it.moveToFirst() && it.getInt(0) == 0)
            }
        }
        val row = prior.getLong("event_row", -1)
        app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events.TITLE, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED,
            Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7, Events._SYNC_ID), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertTrue("Keep the exact unstamped negative original", it.count == 1 && it.moveToFirst() &&
                it.getString(0) == "Calendeer QA — création capturée ${prior.getString("token", null)}" &&
                it.getLong(1) == prior.getLong("calendar_row", -1) && it.getInt(2) == 1 && it.getInt(3) == 0 &&
                (4..7).all { column -> it.isNull(column) })
        }
        for ((uri, column, count) in listOf(Triple(Attendees.CONTENT_URI, Attendees.EVENT_ID, 1),
            Triple(Reminders.CONTENT_URI, Reminders.EVENT_ID, 0), Triple(ExtendedProperties.CONTENT_URI, ExtendedProperties.EVENT_ID, 0))) {
            app.contentResolver.query(uri, arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use {
                assertTrue("Qualify the rejected child shape without modifying it", it.count == count)
            }
        }
        status("retained_negative_fixture_has_unproven_stamp_and_no_dispatch")
    }

    private fun candidate(calendar: Long, content: EventContent) = ContentValues().apply {
        val time = content.time as EventTime.Timed
        put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
        put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
        put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
        putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
        put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
        // Setting SELF_ATTENDEE_STATUS at insertion creates a synthetic participant, even at 0.
        put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY); putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
        put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
        put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
    }
}

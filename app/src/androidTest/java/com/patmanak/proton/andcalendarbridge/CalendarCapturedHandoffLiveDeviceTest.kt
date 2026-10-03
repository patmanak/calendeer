// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentProviderOperation
import android.content.SharedPreferences
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
import java.time.LocalDate
import java.util.UUID
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONObject
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
        assertTrue("Explicit qualification phase required", phase in setOf("CREATE", "INSPECT", "PREPARE_UPDATE",
            "VERIFY_UPDATE", "CAPTURE_LOCAL", "INSPECT_LOCAL"))
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
        if (phase !in setOf("CREATE", "INSPECT")) {
            qualifyAfterHandoff(checkNotNull(phase), app, projection, prefs, target, identity, local, capture, attempt, token)
            return@runBlocking
        }
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

    /** Only CAPTURE_LOCAL admits a local edit; none of these phases submits outgoing Proton work. */
    private suspend fun qualifyAfterHandoff(phase: String, app: BridgeApplication, projection: AndroidCalendarProjection,
        prefs: SharedPreferences, target: CalendarScope, identity: CapturedCreateDispatchIdentity,
        local: LocalEventId, capture: MutationId, attempt: MutationId, token: String) {
        val binding = CreateAttemptStore(app).use { checkNotNull(it.capturedBinding(identity)) }
        assertTrue("Retain the exact original captured association", binding.identity == identity)
        suspend fun role() = ImportStore(app).use { checkNotNull(it.capturedHandoff(target, binding.eventId)) }
        suspend fun sameNativeRow(cleanFrame: CapturedCreateHandoffFrame? = null) {
            cleanFrame?.let {
                val witness = projection.inspectOwnedEvent(target, binding.eventId, it.base.revision)
                assertTrue("Bind the remote identity to the original clean native locator", witness != null &&
                    witness.eventRow == prefs.getLong("event_row", -1) && witness.calendarRow == prefs.getLong("calendar_row", -1))
            }
            app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID,
                Events.SYNC_DATA2), "${Events._ID}=?", arrayOf(prefs.getLong("event_row", -1).toString()), null)!!.use {
                assertTrue("Retain the same original owned row and current revision", it.count == 1 && it.moveToFirst() &&
                    it.getLong(0) == prefs.getLong("event_row", -1) && it.getLong(1) == prefs.getLong("calendar_row", -1) &&
                    !it.getString(2).isNullOrEmpty() && !it.getString(3).isNullOrEmpty())
            }
            if (prefs.contains("update_native")) {
                val frozen = JSONObject(checkNotNull(prefs.getString("update_native", null)))
                val current = nativeMarkers(app, prefs)
                assertTrue("Keep the immutable native association and original capture markers", listOf(Events._SYNC_ID,
                    Events.SYNC_DATA1, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7).all {
                    frozen.get(it) == current.get(it)
                })
            }
        }
        fun frozenFrame(key: String, current: CapturedCreateHandoffFrame): CapturedCreateHandoffFrame {
            assertTrue("Original creation receipt must stay immutable", prefs.getString("update_origin", null) ==
                current.receipt.fingerprint())
            return decodeFrame(checkNotNull(prefs.getString(key, null)), current.receipt)
        }
        val original = role()
        assertTrue("Require the existing released handoff", original.state == CapturedCreateHandoffState.RELEASED)
        if (phase == "PREPARE_UPDATE") {
            assertTrue("Do not prepare over a retained local edit", !original.waiting && !prefs.contains("local_edit_admitted"))
            assertTrue("Freeze a supported clean reference", supportsCapturedHandoff(original.frame.base) &&
                projection.inspectCapturedHandoffBase(original.frame)?.frame == original.frame)
            sameNativeRow(original.frame)
            val calendar = nativeCalendar(app, projection, prefs, target)
            ImportStore(app).use {
                assertTrue("Prepare only after the event queue drains", it.capturedHandoffPending(original.frame) == null &&
                    it.capturedHandoffProjection(original.frame) == null && it.localEdit(target, binding.eventId) == null &&
                    it.localDelete(target, binding.eventId) == null)
            }
            if (prefs.contains("update_frame")) {
                assertTrue("Never replace the frozen pre-update reference", frozenFrame("update_frame", original.frame) == original.frame)
                assertTrue("Never replace the frozen calendar ownership", prefs.getString("update_calendar", null) == calendar.toString())
            } else {
                assertTrue("Freeze the full reference before the external Proton edit", prefs.edit()
                    .putString("update_origin", original.frame.receipt.fingerprint())
                    .putString("update_frame", encodeFrame(original.frame))
                    .putString("update_calendar", calendar.toString())
                    .putString("update_native", nativeMarkers(app, prefs).toString()).commit())
            }
            status("captured_update_reference_frozen")
            return
        }
        val before = frozenFrame("update_frame", original.frame)
        val remoteTitle = "Calendeer QA - Proton edit $token"
        val localTitle = "Calendeer QA - Local edit $token"
        if (phase == "VERIFY_UPDATE") {
            assertTrue("Do not refresh over this fixture's later local edit", !prefs.contains("local_edit_admitted"))
            app.imports.requestSync()
            val deadline = SystemClock.elapsedRealtime() + 120_000
            var current = original
            while (current.frame.base.content.title != remoteTitle && SystemClock.elapsedRealtime() < deadline) {
                delay(500)
                current = role()
            }
            assertTrue("Incoming content must advance the released reference", current.state == CapturedCreateHandoffState.RELEASED &&
                !current.waiting && current.frame.receipt == before.receipt && current.frame.base.revision != before.base.revision &&
                current.frame.base.content == before.base.content.copy(title = remoteTitle))
            val receipt = checkNotNull(current.frame.lastProjection)
            assertTrue("Incoming receipt must start at the exact frozen reference", receipt.previousBase == before.base &&
                receipt.change == current.frame.base && projection.inspectCapturedHandoffProjection(
                    CapturedHandoffProjection(before, receipt))?.plan == CapturedHandoffProjection(before, receipt))
            assertTrue("Qualify the clean advanced native reference", projection.inspectCapturedHandoffBase(current.frame)?.frame == current.frame)
            sameNativeRow(current.frame)
            ImportStore(app).use {
                assertTrue("Consume only the qualified incoming event", it.capturedHandoffPending(current.frame) == null &&
                    it.capturedHandoffProjection(current.frame) == null && it.localEdit(target, binding.eventId) == null &&
                    it.localDelete(target, binding.eventId) == null)
            }
            val encoded = encodeFrame(current.frame)
            if (prefs.contains("advanced_frame")) assertTrue("Never replace an already qualified update", prefs.getString("advanced_frame", null) == encoded)
            else assertTrue("Retain the qualified advanced reference", prefs.edit().putString("advanced_frame", encoded)
                .putString("advanced_native", nativeMarkers(app, prefs).toString()).commit())
            status("captured_proton_update_projected_on_original_row")
            status("captured_immutable_creation_evidence_preserved")
            status("captured_incoming_event_drained")
            return
        }
        val advanced = frozenFrame("advanced_frame", original.frame)
        assertTrue("The local fixture must use the qualified advanced base", original.frame == advanced &&
            advanced.base.content == before.base.content.copy(title = remoteTitle) && advanced.lastProjection?.previousBase == before.base)
        if (phase == "CAPTURE_LOCAL") {
            if (!prefs.contains("local_edit_admitted")) {
                assertTrue("Admit only the still-clean advanced original", !original.waiting &&
                    projection.inspectCapturedHandoffBase(advanced)?.frame == advanced)
                sameNativeRow(advanced)
                val frozenCalendar = JSONObject(checkNotNull(prefs.getString("update_calendar", null)))
                assertTrue("Preserve the original calendar ownership", nativeCalendar(app, projection, prefs, target).toString() == frozenCalendar.toString())
                fun activeTarget() = app.imports.state.value.let { state -> state.authenticated &&
                    state.selections.any { it.scope == target && it.enabled && it.available } }
                assertTrue("Require active authorization before admission", activeTarget() && projection.hasPermissions())
                ImportStore(app).use {
                    assertTrue("Do not replace existing local work", it.localEdit(target, binding.eventId) == null &&
                        it.localDelete(target, binding.eventId) == null && it.capturedHandoffProjection(advanced) == null &&
                        it.capturedHandoffPending(advanced) == null)
                }
                // Persist admission first. A missing/lost response never permits replaying this write.
                assertTrue("Freeze local mutation and one-shot native admission", prefs.edit()
                    .putString("local_edit_mutation", UUID.randomUUID().toString()).putBoolean("local_edit_admitted", true).commit())
                val account = projection.accountFor(target.account)
                val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
                val selection = "${Events._ID}=? AND ${Events.CALENDAR_ID}=?"
                val values = arrayOf(prefs.getLong("event_row", -1).toString(), prefs.getLong("calendar_row", -1).toString())
                val expected = expectedSimpleNative(prefs.getLong("calendar_row", -1), advanced.base.content).apply {
                    put(Events.DIRTY, 0)
                    val frozen = JSONObject(checkNotNull(prefs.getString("advanced_native", null)))
                    for (column in markerColumns) if (frozen.isNull(column)) putNull(column) else put(column, frozen.getString(column))
                }
                val calendarExpected = ContentValues().apply {
                    for (column in calendarColumns) put(column, frozenCalendar.getString(column))
                }
                val operations = arrayListOf(ContentProviderOperation.newAssertQuery(CalendarContract.Calendars.CONTENT_URI)
                    .withSelection("${CalendarContract.Calendars._ID}=?", arrayOf(values[1]))
                    .withValues(calendarExpected).withExpectedCount(1).build(),
                    ContentProviderOperation.newAssertQuery(uri).withSelection(selection, values)
                        .withValues(expected).withExpectedCount(1).build())
                for ((childUri, eventColumn) in listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
                    Reminders.CONTENT_URI to Reminders.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
                    operations += ContentProviderOperation.newAssertQuery(childUri).withSelection("$eventColumn=?", arrayOf(values[0]))
                        .withExpectedCount(0).build()
                }
                operations += ContentProviderOperation.newUpdate(uri).withSelection(selection, values).withValues(ContentValues().apply {
                        put(Events.TITLE, localTitle); put(Events.DIRTY, 1)
                    }).withExpectedCount(1).build()
                assertTrue("Require current authorization immediately before the native batch", activeTarget() && projection.hasPermissions())
                val response = app.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
                assertTrue("Require grants after the native batch", projection.hasPermissions())
                val counts = listOf(1, 1, 0, 0, 0, 1)
                assertTrue("Require an exact confirmed native edit response", response != null && response.size == counts.size &&
                    response.indices.all { response[it]?.let { result -> result.count == counts[it] && result.uri == null &&
                        result.extras == null && result.exception == null } == true })
            }
            assertLocalNative(app, prefs, advanced, localTitle)
            val result = app.imports.reconcileHandoffLocalCreate(target, local, capture, attempt,
                MutationId(checkNotNull(prefs.getString("local_edit_mutation", null))))
            assertTrue("Retain the supported local edit without dispatch", result == CapturedCreateReconciliationResult.RETAINED)
            status("captured_later_local_intention_retained")
        } else assertTrue("Read-only inspection requires the admitted local edit", phase == "INSPECT_LOCAL" &&
            prefs.getBoolean("local_edit_admitted", false))
        val finalRole = role()
        assertTrue("Preserve the advanced reference while holding later local work", finalRole.frame == advanced && finalRole.waiting &&
            finalRole.state == CapturedCreateHandoffState.RELEASED)
        sameNativeRow()
        assertLocalNative(app, prefs, advanced, localTitle)
        ImportStore(app).use {
            val edit = it.localEdit(target, binding.eventId)
            assertTrue("Reopened intention must refer to the current base, not the sent original", edit != null &&
                edit.id.value == prefs.getString("local_edit_mutation", null) && edit.scope == target && edit.format == advanced.format &&
                edit.base == advanced.base && edit.content == advanced.base.content.copy(title = localTitle) && edit.state == LocalEditState.PAUSED &&
                it.localDelete(target, binding.eventId) == null && it.capturedHandoffProjection(advanced) == null &&
                it.capturedHandoffPending(advanced) == null)
        }
        status("captured_local_edit_uses_advanced_base")
        status("captured_local_edit_waiting_preserved")
    }

    /** Queries only: inspection cannot acknowledge DIRTY or repair missing rows. */
    private fun assertLocalNative(app: BridgeApplication, prefs: SharedPreferences, frame: CapturedCreateHandoffFrame, title: String) {
        app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events.TITLE, Events.DIRTY, Events.DELETED, Events.SYNC_DATA3),
            "${Events._ID}=?", arrayOf(prefs.getLong("event_row", -1).toString()), null)!!.use {
            assertTrue("Keep the later local edit dirty and the qualified projection marker unchanged", it.count == 1 && it.moveToFirst() &&
                it.getString(0) == title && it.getInt(1) == 1 && it.getInt(2) == 0 && it.getString(3) ==
                "calendeer-projection-receipt-v${frame.lastProjection?.protocol}:${frame.lastProjection?.nonce}")
        }
        val frozen = JSONObject(checkNotNull(prefs.getString("advanced_native", null)))
        val current = nativeMarkers(app, prefs)
        assertTrue("Keep every frozen ownership and evidence marker", markerColumns.all { frozen.get(it) == current.get(it) })
        val expected = expectedSimpleNative(prefs.getLong("calendar_row", -1), frame.base.content.copy(title = title))
        val columns = expected.keySet().toTypedArray()
        app.contentResolver.query(Events.CONTENT_URI, columns, "${Events._ID}=?",
            arrayOf(prefs.getLong("event_row", -1).toString()), null)!!.use {
            assertTrue("Preserve the complete supported local native shape", it.count == 1 && it.moveToFirst() &&
                columns.indices.all { column -> if (expected[columns[column]] == null) it.isNull(column)
                    else it.getString(column) == expected[columns[column]].toString() })
        }
        for ((uri, eventColumn) in listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
            Reminders.CONTENT_URI to Reminders.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
            app.contentResolver.query(uri, arrayOf("_id"), "$eventColumn=?",
                arrayOf(prefs.getLong("event_row", -1).toString()), null)!!.use {
                assertTrue("Keep known-empty child data without discarding anything", it.count == 0)
            }
        }
    }

    private val markerColumns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
        Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)

    private val calendarColumns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars._SYNC_ID, CalendarContract.Calendars.CAL_SYNC1)

    private fun nativeCalendar(app: BridgeApplication, projection: AndroidCalendarProjection, prefs: SharedPreferences,
        target: CalendarScope): JSONObject = app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, calendarColumns,
        "${CalendarContract.Calendars._ID}=?", arrayOf(prefs.getLong("calendar_row", -1).toString()), null)!!.use {
        val account = projection.accountFor(target.account)
        assertTrue("Require the canonical owned calendar", it.count == 1 && it.moveToFirst() &&
            it.getLong(0) == prefs.getLong("calendar_row", -1) && it.getString(1) == account.name &&
            it.getString(2) == account.type && !it.getString(3).isNullOrEmpty() && it.getString(4) == "calendar-bridge-projection-v1")
        JSONObject().apply { calendarColumns.forEachIndexed { index, name -> put(name, it.getString(index)) } }
    }

    private fun expectedSimpleNative(calendar: Long, content: EventContent) = candidate(calendar, content).apply {
        put(Events.SELF_ATTENDEE_STATUS, 0)
        for (column in arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) putNull(column)
    }

    private fun nativeMarkers(app: BridgeApplication, prefs: SharedPreferences): JSONObject =
        app.contentResolver.query(Events.CONTENT_URI, markerColumns, "${Events._ID}=?",
            arrayOf(prefs.getLong("event_row", -1).toString()), null)!!.use {
            assertTrue("Require exactly the retained native original", it.count == 1 && it.moveToFirst())
            JSONObject().apply { markerColumns.forEachIndexed { index, name ->
                put(name, if (it.isNull(index)) JSONObject.NULL else it.getString(index))
            } }
        }

    private fun encodeFrame(frame: CapturedCreateHandoffFrame) = JSONObject().apply {
        put("schema", 1); put("base", encodeSimpleChange(frame.base))
        frame.lastProjection?.let { put("last", JSONObject().apply {
            put("nonce", it.nonce); put("change", encodeSimpleChange(it.change))
            put("previous", encodeSimpleChange(checkNotNull(it.previousBase))); put("protocol", it.protocol)
        }) }
    }.toString()

    private fun decodeFrame(payload: String, receipt: CapturedCreateHandoffReceipt): CapturedCreateHandoffFrame = JSONObject(payload).let {
        require(it.getInt("schema") == 1)
        val last = if (it.has("last")) it.getJSONObject("last").let { value ->
            ProjectionReceipt(receipt.scope, receipt.format, decodeSimpleChange(value.getJSONObject("change")), value.getString("nonce"),
                decodeSimpleChange(value.getJSONObject("previous")), value.getInt("protocol"))
        } else null
        CapturedCreateHandoffFrame(receipt, decodeSimpleChange(it.getJSONObject("base")), last)
    }

    private fun encodeSimpleChange(change: RemoteChange.Upsert): JSONObject {
        require(supportsCapturedHandoff(change))
        return JSONObject().apply {
            put("id", change.eventId.value); put("revision", change.revision.value)
            put("title", change.content.title); put("description", change.content.description); put("location", change.content.location)
            when (val time = change.content.time) {
                is EventTime.Timed -> { put("timed", true); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
                is EventTime.AllDay -> { put("timed", false); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            }
        }
    }

    private fun decodeSimpleChange(value: JSONObject): RemoteChange.Upsert {
        val time = if (value.getBoolean("timed")) EventTime.Timed(Instant.parse(value.getString("start")),
            Instant.parse(value.getString("end")), ZoneId.of(value.getString("zone"))) else
            EventTime.AllDay(LocalDate.parse(value.getString("start")), LocalDate.parse(value.getString("end")))
        return RemoteChange.Upsert(EventId(value.getString("id")), Revision(value.getString("revision")),
            EventContent(value.getString("title"), time, value.getString("description"), value.getString("location"), details = EventDetails()))
    }

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

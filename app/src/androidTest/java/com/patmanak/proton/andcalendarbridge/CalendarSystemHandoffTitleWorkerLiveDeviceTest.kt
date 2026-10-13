// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two external editor saves, one original native row. Only production workers touch
 * service requests or acknowledgement. RUN remains alive through both UI rendezvous.
 * CONTINUE accepts only a verified CREATE before any title-editor request. Retained
 * failures never reopen CREATE or an already requested title edit. RECOVER qualifies only
 * an already completed title proof whose original CREATE queue has not settled; normal
 * workers reconcile that bookkeeping. REPLAY is read-only apart from
 * restoring the original write options. This qualifies the automatic pipeline, not
 * exclusive ContentObserver causality or an HTTP request counter.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSystemHandoffTitleWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemCreatedEventKeepsItsProvenanceThroughAutomaticTitleUpdateAndRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemHandoffTitleWorker") == "true")
        val phase = args.getString("handoffTitleWorkerPhase").orEmpty()
        val token = args.getString("createFixtureToken").orEmpty()
        val accountHash = args.getString("createAccountHash").orEmpty()
        val label = args.getString("titleJourneyLabel").orEmpty()
        assertTrue("Explicit phase and isolated enrolled identity required", phase in setOf("RUN", "CONTINUE", "RECOVER", "REPLAY", "DIAG", "DIAG_SETTLED", "DIAG_CREATE") &&
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")) &&
            label.matches(Regex("[a-z][a-z0-9-]{0,23}")))
        assertTrue("Only the dedicated synthetic calendar is authorized",
            args.getString("titleSourceCalendarName") == QA_CALENDAR)
        val profile = TitleJourneyTimeProfile.create(args.getString("titleJourneyAllDay"), true, true, label)
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let { it.authenticated && it.hasPermission && it.selections.isNotEmpty() } }
        val prefs = app.getSharedPreferences("calendar-system-handoff-title-worker-v1-$label", Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available && it.summary.title == QA_CALENDAR }
        assertTrue("Exactly one accessible dedicated calendar required", selected.size == 1)
        val target = selected.single().scope
        assertTrue("Authorize the exact test account", ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        val projection = AndroidCalendarProjection(app)
        assertTrue("Both native calendar permissions are required", projection.hasPermissions())
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == target && it.enabled && it.available && it.summary.title == QA_CALENDAR } == 1 }
        projection.admitOperation = ::accessible
        val calendar = existingCalendar(app, projection, target)
        val originalTitle = "Calendeer QA handoff create $token"
        val changedTitle = "Calendeer QA handoff title $token"
        val description = "Synthetic original CREATE and automatic title handoff qualification."
        val location = "Calendeer QA"
        fun modes() = SystemCreateWorkStore(app).use { it.modeEnabled(target) to it.titlesEnabled(target) }
        fun checkEnrollment() {
            assertTrue("Keep the exact frozen journey", prefs.getString("token", null) == token &&
                prefs.getString("account_hash", null) == accountHash && prefs.getString("label", null) == label &&
                prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
                prefs.getLong("generation", -1) == target.generation && prefs.getLong("calendar_row", -1) == calendar &&
                profile.matchesFrozen(prefs.getInt("content_profile", -1), prefs.getString("start", null), prefs.getString("end", null)) &&
                prefs.getString("original_title", null) == originalTitle && prefs.getString("changed_title", null) == changedTitle)
        }
        if (phase == "DIAG_CREATE") {
            try {
                checkEnrollment()
                assertTrue("Diagnose only the original failed pre-title CREATE enrollment",
                    prefs.getBoolean("create_editor_requested", false) && prefs.getLong("event_row", -1) > 0 &&
                        !prefs.getBoolean("create_verified", false) && !prefs.getBoolean("title_editor_requested", false) &&
                        !hasTitleFrame(prefs) && !prefs.getBoolean("worker_verified", false))
                val row = prefs.getLong("event_row", -1)
                val source = readCreateProof(app, target, row)
                assertTrue("No title attempt can participate in the CREATE diagnostic",
                    historyIds(app, target, source.initial.eventId).isEmpty())
                val expected = EventContent(originalTitle, profile.time, description, location, details = EventDetails())
                diagnoseCreateNative(app, projection, target, calendar, row, source, expected)
                assertTrue("Retain account and enrollment access throughout diagnostic reads", accessible())
            } finally { projection.admitOperation = { false } }
            return@runBlocking // Never enroll the failed CREATE, schedule work or open an editor.
        }
        if (phase == "DIAG") {
            checkEnrollment()
            assertTrue("Diagnostic requires the retained verified CREATE", prefs.getBoolean("create_verified", false) &&
                prefs.getLong("event_row", -1) > 0 && prefs.contains("create_checksum"))
            try {
                diagnose(app, projection, target, prefs)
                assertTrue("Keep account and scope access through diagnostic reads", accessible())
            } finally { projection.admitOperation = { false } }
            return@runBlocking // No scheduler, write option, editor, Provider write or service call.
        }
        suspend fun restoreModes() {
            assertTrue("Keep access while restoring only the enrolled options", accessible())
            val selection = app.imports.state.value.selections.single { it.scope == target }
            val creates = prefs.getBoolean("previous_create", false)
            val titles = prefs.getBoolean("previous_titles", false)
            compose.runOnUiThread {
                app.systemCreates.setEnabled(selection, creates)
                app.systemCreates.setTitlesEnabled(selection, titles)
            }
            assertTrue("Restore original dependent flags exactly", modes() == (creates to titles))
            status("handoff_title_modes_restored")
        }
        val workManager = WorkManager.getInstance(app)
        var periodicRunning = false
        fun networkWork() = workManager.getWorkInfosForUniqueWork(NETWORK_WORK).get()
        fun samplePeriodic() {
            if (workManager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().any { it.state == WorkInfo.State.RUNNING }) periodicRunning = true
        }
        suspend fun idle(expectedModes: Pair<Boolean, Boolean>? = null) = withTimeout(240_000) {
            while (true) {
                currentCoroutineContext().ensureActive()
                assertTrue("Keep current scope access", accessible())
                if (expectedModes != null) assertTrue("Keep write consent through worker convergence", modes() == expectedModes)
                samplePeriodic()
                if (networkWork().all { it.state.isFinished } &&
                    workManager.getWorkInfosForUniqueWork(LOCAL_WORK).get().all { it.state.isFinished } &&
                    workManager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                    !app.imports.state.value.busy && !app.systemCreates.state.value.busy) break
                delay(500)
            }
        }
        if (phase == "DIAG_SETTLED") {
            checkEnrollment()
            assertTrue("Settled diagnostics require the successful unchanged enrollment", prefs.getBoolean("worker_verified", false) &&
                prefs.contains("settled_frame_checksum") && prefs.contains("title_checksum") && prefs.contains("title_attempt"))
            try {
                val priorModes = modes()
                val before = settledDiagnosticSnapshot(app, target, prefs)
                status("handoff_diag_settled_before_matches_enrollment", before.checksum == prefs.getString("settled_frame_checksum", null))
                status("handoff_diag_settled_create_mode", priorModes.first)
                status("handoff_diag_settled_title_mode", priorModes.second)
                // Observe existing startup work only. No options, scheduling or enrollment writes.
                idle(priorModes)
                val afterFirst = settledDiagnosticSnapshot(app, target, prefs)
                idle(priorModes)
                val after = settledDiagnosticSnapshot(app, target, prefs)
                val output = Bundle()
                output.putBoolean("handoff_diag_settled_first_matches_enrollment", afterFirst.checksum == prefs.getString("settled_frame_checksum", null))
                output.putBoolean("handoff_diag_settled_first_raw_same", before.checksum == afterFirst.checksum)
                output.putBoolean("handoff_diag_settled_second_raw_same", afterFirst.checksum == after.checksum)
                output.putBoolean("handoff_diag_settled_first_frame_same", before.create.current.frame == afterFirst.create.current.frame)
                output.putBoolean("handoff_diag_settled_second_frame_same", afterFirst.create.current.frame == after.create.current.frame)
                output.putBoolean("handoff_diag_settled_after_matches_enrollment", after.checksum == prefs.getString("settled_frame_checksum", null))
                output.putBoolean("handoff_diag_settled_raw_rows_same", before.checksum == after.checksum)
                output.putBoolean("handoff_diag_settled_column_set_same", before.columns.keys == after.columns.keys)
                for ((name, fingerprint) in before.columns) {
                    output.putBoolean("handoff_diag_settled_${name}_same", after.columns[name] == fingerprint)
                    output.putBoolean("handoff_diag_settled_${name}_first_same", afterFirst.columns[name] == fingerprint)
                    output.putBoolean("handoff_diag_settled_${name}_second_same", afterFirst.columns[name] == after.columns[name])
                    output.putBoolean("handoff_diag_settled_${name}_null_before", fingerprint == null)
                    output.putBoolean("handoff_diag_settled_${name}_null_after", after.columns.containsKey(name) && after.columns[name] == null)
                }
                output.putBoolean("handoff_diag_settled_role_same", before.create.current == after.create.current)
                output.putBoolean("handoff_diag_settled_frame_same", before.create.current.frame == after.create.current.frame)
                output.putBoolean("handoff_diag_settled_origin_same", before.create.current.frame.receipt == after.create.current.frame.receipt)
                output.putBoolean("handoff_diag_settled_base_same", before.create.current.frame.base == after.create.current.frame.base)
                output.putBoolean("handoff_diag_settled_last_projection_same", before.create.current.frame.lastProjection == after.create.current.frame.lastProjection)
                output.putBoolean("handoff_diag_settled_title_history_same", before.title == after.title)
                for ((stage, snapshot) in listOf("before" to before, "first" to afterFirst, "after" to after)) {
                    val role = snapshot.create.current
                    val frame = role.frame
                    val result = snapshot.title.result.event
                    val last = frame.lastProjection
                    output.putBoolean("handoff_diag_settled_${stage}_create_original", snapshot.create.checksum == prefs.getString("create_checksum", null))
                    output.putBoolean("handoff_diag_settled_${stage}_title_original", snapshot.title.checksum == prefs.getString("title_checksum", null) &&
                        snapshot.title.record.id.value == prefs.getString("title_attempt", null))
                    output.putBoolean("handoff_diag_settled_${stage}_released", role.state == CapturedCreateHandoffState.RELEASED)
                    output.putBoolean("handoff_diag_settled_${stage}_waiting", role.waiting)
                    output.putBoolean("handoff_diag_settled_${stage}_base_result", frame.base == result)
                    output.putBoolean("handoff_diag_settled_${stage}_ack_frame_frozen",
                        snapshot.title.completion.receipt.handoff == frozenTitleFrame(prefs, snapshot.create))
                    output.putBoolean("handoff_diag_settled_${stage}_ack_frame_preeditor_qualified",
                        TransferredTitleFrame.matchesPreEditorFrame(frozenTitleFrame(prefs, snapshot.create), snapshot.title.completion.receipt.handoff))
                    output.putBoolean("handoff_diag_settled_${stage}_projection_ack", last == snapshot.title.completion.receipt.projection)
                    output.putBoolean("handoff_diag_settled_${stage}_projection_exact_echo", last != null &&
                        last.scope == target && last.format == frame.format && last.protocol == 1 &&
                        last.change == result && last.previousBase == result &&
                        last.representation == null && last.previousRepresentation == null)
                }
                assertTrue("Keep original access and modes through diagnostic observation", accessible() && modes() == priorModes)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
                diagnose(app, projection, target, prefs)
            } finally { projection.admitOperation = { false } }
            return@runBlocking
        }
        var restoreOnExit = false
        try {
            if (phase == "RUN" || phase == "CONTINUE") {
                val row: Long
                val original: CreateProof
                val content: EventContent
                if (phase == "RUN") {
                    assertTrue("Never replace or repeat an enrolled editor journey", !prefs.contains("token"))
                    assertScopeQuiescent(app, projection, target)
                    val prior = modes()
                    assertTrue("Freeze identity and restore options before mutation", prefs.edit()
                        .putString("token", token).putString("account_hash", accountHash).putString("label", label)
                        .putString("account", target.account.value).putString("calendar", target.calendar.value)
                        .putLong("generation", target.generation).putLong("calendar_row", calendar)
                        .putInt("content_profile", profile.contentProfile).putString("start", profile.start).putString("end", profile.end)
                        .putString("original_title", originalTitle).putString("changed_title", changedTitle)
                        .putBoolean("previous_create", prior.first).putBoolean("previous_titles", prior.second)
                        .putInt("run_pid", Process.myPid()).commit())
                    restoreOnExit = true
                    val selection = selected.single()
                    compose.runOnUiThread {
                        app.systemCreates.setEnabled(selection, true)
                        app.systemCreates.setTitlesEnabled(selection, false)
                    }
                    assertTrue("Configure only existing calendar access", app.imports.configureSystemCalendar(target))
                    idle()
                    assertScopeQuiescent(app, projection, target)
                    assertTrue("No fixture row exists before its sole creation", findRow(app, calendar, originalTitle) == null)
                    val originalRows = nativeRows(app, calendar)
                    val createWorkBefore = networkWork().map { it.id.toString() }.toSet()
                    assertTrue("Freeze CREATE editor admission before opening it", prefs.edit().putBoolean("create_editor_requested", true).commit())
                    app.startActivity(Intent(Intent.ACTION_INSERT).apply {
                        setDataAndType(Events.CONTENT_URI, "vnd.android.cursor.dir/event")
                        setPackage(args.getString("editorPackage") ?: "foundation.e.calendar")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(Events.CALENDAR_ID, calendar); putExtra(Events.TITLE, originalTitle)
                        putExtra(Events.DESCRIPTION, description); putExtra(Events.EVENT_LOCATION, location)
                        putExtra(Events.EVENT_TIMEZONE, "UTC"); putExtra(Events.EVENT_END_TIMEZONE, "UTC")
                        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, profile.allDay)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, profile.startMillis)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, profile.endMillis)
                    })
                    status("handoff_create_editor_launched")
                    // From this point onward no manual scheduling, import, capture, prepare,
                    // submission or acknowledgement API is called by the fixture.
                    row = awaitValue {
                        assertTrue("Keep current scope access during system CREATE", accessible()); samplePeriodic()
                        assertTrue("Keep only CREATE consent during the first editor save", modes() == (true to false))
                        findRow(app, calendar, originalTitle)
                    }
                    assertTrue("Freeze the original physical event", prefs.edit().putLong("event_row", row).commit())
                    original = awaitValue {
                        assertTrue("Keep current scope access during CREATE workers", accessible()); samplePeriodic()
                        assertTrue("Keep CREATE consent until its original handoff", modes() == (true to false))
                        val work = SystemCreateWorkStore(app).use { it.read(target, row) }
                        if (work?.status != SystemCreateWorkStatus.COMPLETE) null else readCreateProof(app, target, row)
                    }
                    content = original.initial.base.content
                    assertTrue("CREATE adds only the one original row", nativeRows(app, calendar) == originalRows + row)
                    assertTrue("CREATE readback must preserve the synthetic known-empty typed content", content.title == originalTitle &&
                        content.description == description && content.location == location && content.details == EventDetails() &&
                        content.colorRgb == null && content.recurrence == null && timeProfileMatches(profile, content.time))
                    assertTrue("First handoff must be released on its original base", original.current.state == CapturedCreateHandoffState.RELEASED &&
                        !original.current.waiting && original.current.frame.base == original.initial.base &&
                        original.current.frame.receipt == original.initial.receipt)
                    verifyNative(app, projection, target, calendar, row, original.current.frame, content)
                    assertTrue("No title work may predate the editor change", historyIds(app, target, original.initial.eventId).isEmpty())
                    assertTrue("Freeze the complete CREATE proof before title opt-in", prefs.edit()
                        .putString("event", original.initial.eventId.value).putString("create_checksum", original.checksum)
                        .putString("frame_fingerprint", original.initial.receipt.fingerprint())
                        .putString("original_revision", original.initial.base.revision.value)
                        .putString("zone", (content.time as? EventTime.Timed)?.zone?.id)
                        .putString("native_origin", originDigest(nativeValues(app, calendar, row)))
                        .putString("native_rows", digest(*nativeRows(app, calendar).sorted().map(Long::toString).toTypedArray()))
                        .putBoolean("create_verified", true).commit())
                    status("handoff_create_released_same_row")
                    awaitValue {
                        assertTrue("Keep access while observing CREATE completion", accessible()); samplePeriodic()
                        true.takeIf { networkWork().any { it.id.toString() !in createWorkBefore && it.state == WorkInfo.State.SUCCEEDED } }
                    }
                } else {
                    checkEnrollment()
                    assertTrue("Continue only the verified pre-title enrollment", prefs.getBoolean("create_editor_requested", false) &&
                        prefs.getBoolean("create_verified", false) && !prefs.getBoolean("title_editor_requested", false) &&
                        !prefs.getBoolean("worker_verified", false) && !hasTitleFrame(prefs) &&
                        modes() == (prefs.getBoolean("previous_create", false) to prefs.getBoolean("previous_titles", false)))
                    row = prefs.getLong("event_row", -1)
                    original = readCreateProof(app, target, row)
                    content = original.initial.base.content
                    assertTrue("Continue preserves the original CREATE and event", original.checksum == prefs.getString("create_checksum", null) &&
                        original.initial.eventId.value == prefs.getString("event", null) &&
                        original.initial.receipt.fingerprint() == prefs.getString("frame_fingerprint", null) &&
                        original.initial.base.revision.value == prefs.getString("original_revision", null) &&
                        content.title == originalTitle && content.description == description && content.location == location &&
                        timeProfileMatches(profile, content.time) && original.current.frame.base == original.initial.base &&
                        !original.current.waiting && historyIds(app, target, original.initial.eventId).isEmpty())
                    verifyNative(app, projection, target, calendar, row, original.current.frame, content)
                    assertTrue("Continue preserves all original rows and native provenance",
                        originDigest(nativeValues(app, calendar, row)) == prefs.getString("native_origin", null) &&
                        digest(*nativeRows(app, calendar).sorted().map(Long::toString).toTypedArray()) == prefs.getString("native_rows", null))
                    status("handoff_title_continue_same_create")
                }
                assertScopeQuiescent(app, projection, target)
                restoreOnExit = true
                compose.runOnUiThread {
                    app.systemCreates.setEnabled(selected.single(), true)
                    app.systemCreates.setTitlesEnabled(selected.single(), true)
                }
                idle(true to true)
                assertScopeQuiescent(app, projection, target)
                val stillOriginal = readCreateProof(app, target, row)
                assertTrue("Keep original CREATE proof and unchanged current base before editing", stillOriginal.checksum == original.checksum &&
                    stillOriginal.checksum == prefs.getString("create_checksum", null) && stillOriginal.initial == original.initial &&
                    stillOriginal.current.state == CapturedCreateHandoffState.RELEASED && !stillOriginal.current.waiting &&
                    stillOriginal.current.frame.base == original.initial.base)
                verifyNative(app, projection, target, calendar, row, stillOriginal.current.frame, content)
                val titleWorkBefore = networkWork().map { it.id.toString() }.toSet()
                assertTrue("Never replace an existing frozen title frame", !hasTitleFrame(prefs) &&
                    !prefs.getBoolean("title_editor_requested", false) && historyIds(app, target, original.initial.eventId).isEmpty())
                val frozenFrame = stillOriginal.current.frame
                assertTrue("Freeze the full title frame and sole editor admission atomically", prefs.edit()
                    .putInt("title_frame_protocol", 1)
                    .putString("title_frame_payload", framePayload(original.initial, frozenFrame, original.checksum))
                    .putBoolean("title_frame_has_projection", frozenFrame.lastProjection != null)
                    .putString("title_frame_projection_nonce", frozenFrame.lastProjection?.nonce)
                    .putInt("title_frame_projection_protocol", frozenFrame.lastProjection?.protocol ?: 0)
                    .putInt("title_pid", Process.myPid()).putBoolean("title_editor_requested", true).commit())
                assertTrue("Persist the exact current frame without replacement", frozenTitleFrame(prefs, original) == frozenFrame)
                app.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, row)).apply {
                    setPackage(args.getString("editorPackage") ?: "foundation.e.calendar"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, profile.startMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, profile.endMillis)
                })
                status("handoff_title_editor_launched")
                val proof = awaitValue {
                    assertTrue("Keep original access throughout automatic title work", accessible()); samplePeriodic()
                    assertTrue("Keep both write modes through title delivery", modes() == (true to true))
                    if (SystemCreateWorkStore(app).use { it.read(target, row)?.status } != SystemCreateWorkStatus.COMPLETE) null
                    else readTitleProof(app, target, original.initial.eventId)
                }
                idle(true to true)
                verifyTitle(app, projection, prefs, target, original, content.copy(title = changedTitle), proof)
                awaitValue {
                    assertTrue("Keep access while observing title completion", accessible()); samplePeriodic()
                    true.takeIf { networkWork().any { it.id.toString() !in titleWorkBefore && it.state == WorkInfo.State.SUCCEEDED } }
                }
                verifyTitle(app, projection, prefs, target, original, content.copy(title = changedTitle), proof)
                requireSettledTitleEvidence(app, target, original.initial.eventId)
                assertTrue("Never replace a settled frame enrollment", !prefs.contains("settled_frame_checksum") && !prefs.contains("settled_frame_payload"))
                val settledFrame = settledFramePayload(app, target, original.initial.eventId, proof.result.event)
                assertTrue("Freeze verified immutable title history", prefs.edit().putString("title_attempt", proof.record.id.value)
                    .putString("title_checksum", proof.checksum).putString("settled_frame_checksum", digest(settledFrame))
                    .putString("settled_frame_payload", settledFrame)
                    .putBoolean("worker_verified", true).commit())
                status("handoff_title_worker_verified")
                status("handoff_title_frame_advanced")
                status("handoff_title_periodic_running_observed", periodicRunning)
                // Keep the enabled mode only for the deliberately scheduled fresh-process replay.
                restoreOnExit = false
            } else if (phase == "RECOVER") {
                checkEnrollment()
                assertTrue("Recovery requires the exact already edited but unqualified enrollment",
                    prefs.getBoolean("create_verified", false) && prefs.getBoolean("title_editor_requested", false) &&
                        !prefs.getBoolean("worker_verified", false) && prefs.getInt("title_pid", -1) > 0 &&
                        prefs.getInt("title_frame_protocol", -1) == 1 &&
                        !prefs.contains("title_attempt") && !prefs.contains("title_checksum") && !prefs.contains("recovery_pid") &&
                        !prefs.contains("settled_frame_checksum") && !prefs.contains("settled_frame_payload") &&
                        modes() == (prefs.getBoolean("previous_create", false) to prefs.getBoolean("previous_titles", false)))
                val row = prefs.getLong("event_row", -1)
                val original = readCreateProof(app, target, row, normalizeQueueStatusForRecovery = true)
                val event = original.initial.eventId
                val proof = checkNotNull(readTitleProof(app, target, event)) { "Recovery cannot create missing title proof" }
                assertTrue("Only the one completed existing title attempt is eligible", historyIds(app, target, event) == listOf(proof.record.id))
                // All immutable bytes must match the first CREATE. Only its mutable queue
                // status is normalized in memory for this preflight; no database is rewritten.
                assertTrue("Recovery preserves all immutable CREATE evidence", original.checksum == prefs.getString("create_checksum", null))
                verifyTitle(app, projection, prefs, target, original, original.initial.base.content.copy(title = changedTitle), proof,
                    normalizeQueueStatusForRecovery = true)
                requireSettledTitleEvidence(app, target, event, recoveryEcho = proof)
                assertScopeQuiescent(app, projection, target, recoveringCreate = original)
                restoreOnExit = true
                compose.runOnUiThread {
                    app.systemCreates.setEnabled(selected.single(), true)
                    app.systemCreates.setTitlesEnabled(selected.single(), true)
                }
                status("handoff_title_recover_existing_proof")
                idle(true to true)
                awaitValue {
                    assertTrue("Keep original consent while normal workers settle CREATE bookkeeping", accessible() && modes() == (true to true))
                    samplePeriodic()
                    true.takeIf { SystemCreateWorkStore(app).use { it.read(target, row)?.status } == SystemCreateWorkStatus.COMPLETE }
                }
                idle(true to true)
                val finalCreate = readCreateProof(app, target, row)
                val finalProof = checkNotNull(readTitleProof(app, target, event)) { "Retained title proof disappeared" }
                assertTrue("Recovery restores the raw original CREATE checksum and exact prior title proof",
                    finalCreate.checksum == prefs.getString("create_checksum", null) && finalProof == proof &&
                        finalCreate.work == original.work.copy(status = SystemCreateWorkStatus.COMPLETE))
                verifyTitle(app, projection, prefs, target, finalCreate, finalCreate.initial.base.content.copy(title = changedTitle), finalProof)
                requireSettledTitleEvidence(app, target, event)
                assertScopeQuiescent(app, projection, target)
                assertTrue("Recovery retains one exact title attempt without replaying the editor", historyIds(app, target, event) == listOf(proof.record.id))
                assertTrue("Never replace a settled recovery frame", !prefs.contains("settled_frame_checksum") && !prefs.contains("settled_frame_payload"))
                val settledFrame = settledFramePayload(app, target, event, finalProof.result.event)
                assertTrue("Record only the successful recovery qualification", prefs.edit()
                    .putString("title_attempt", proof.record.id.value).putString("title_checksum", proof.checksum)
                    .putString("settled_frame_checksum", digest(settledFrame)).putString("settled_frame_payload", settledFrame)
                    .putInt("recovery_pid", Process.myPid()).putBoolean("worker_verified", true).commit())
                status("handoff_title_recovered_same_proof")
                status("handoff_title_frame_advanced")
                status("handoff_title_no_new_attempt")
                restoreOnExit = false
            } else {
                checkEnrollment()
                val qualifiedPid = if (prefs.contains("recovery_pid")) prefs.getInt("recovery_pid", -1) else prefs.getInt("title_pid", -1)
                assertTrue("Replay requires a completed journey in a different process", prefs.getBoolean("worker_verified", false) &&
                    qualifiedPid > 0 && qualifiedPid != Process.myPid())
                restoreOnExit = true
                assertTrue("Expose retained work to startup workers before restoring consent", modes() == (true to true))
                val enrolledEvent = EventId(checkNotNull(prefs.getString("event", null)))
                val entryCreate = readCreateProof(app, target, prefs.getLong("event_row", -1))
                val entryProof = checkNotNull(readTitleProof(app, target, enrolledEvent))
                verifyTitle(app, projection, prefs, target, entryCreate, entryCreate.initial.base.content.copy(title = changedTitle), entryProof)
                verifySettledFramePayload(app, target, enrolledEvent, prefs, entryProof)
                idle(true to true)
                val row = prefs.getLong("event_row", -1)
                val create = readCreateProof(app, target, row)
                val proof = checkNotNull(readTitleProof(app, target, create.initial.eventId)) { "Missing retained title proof" }
                assertTrue("Replay must retain the exact original title attempt and all opaque evidence",
                    proof == entryProof && proof.record.id.value == prefs.getString("title_attempt", null) && proof.checksum == prefs.getString("title_checksum", null))
                verifyTitle(app, projection, prefs, target, create, create.initial.base.content.copy(title = changedTitle), proof)
                verifySettledFramePayload(app, target, enrolledEvent, prefs, proof)
                idle(true to true)
                val finalCreate = readCreateProof(app, target, row)
                val finalProof = checkNotNull(readTitleProof(app, target, create.initial.eventId))
                assertTrue("Converged startup workers keep exact CREATE and title history",
                    finalCreate.work == create.work && finalCreate.initial == create.initial &&
                        finalCreate.checksum == create.checksum && finalProof == proof)
                verifyTitle(app, projection, prefs, target, finalCreate, finalCreate.initial.base.content.copy(title = changedTitle), finalProof)
                requireSettledTitleEvidence(app, target, enrolledEvent)
                verifySettledFramePayload(app, target, enrolledEvent, prefs, finalProof)
                assertTrue("No new production title attempt exists", historyIds(app, target, create.initial.eventId) == listOf(proof.record.id))
                status("handoff_title_replayed_new_process")
                status("handoff_title_no_new_attempt")
            }
        } finally {
            if (restoreOnExit) withContext(NonCancellable) { restoreModes() }
            projection.admitOperation = { false }
        }
    }

    private data class CreateProof(val work: SystemCreateWork, val initial: CapturedCreateHandoffFrame,
        val current: CapturedCreateHandoffRecord, val checksum: String)
    private data class TitleProof(val record: TitleUpdateAttemptRecord, val result: TitleUpdateVerifiedResult,
        val completion: TitleUpdateCompletion, val checksum: String)

    /** Compare the same enrolled row with implicit/explicit/implicit projections, without adoption. */
    private suspend fun diagnoseCreateNative(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        calendar: Long, row: Long, source: CreateProof, expected: EventContent) {
        val frame = source.current.frame
        val output = Bundle()
        val identity = projection.eventIdentityKey(scope, frame.eventId)
        val fields = listOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA4, Events.DIRTY, Events.DELETED,
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY, Events.DTSTART, Events.DTEND,
            Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        val bounds = TitleJourneyTimeProfile.bounds(expected.time)
        val exact = mapOf(Events._ID to row.toString(), Events.CALENDAR_ID to calendar.toString(), Events._SYNC_ID to identity,
            Events.SYNC_DATA4 to identity, Events.DIRTY to "0", Events.DELETED to "0", Events.TITLE to expected.title,
            Events.DESCRIPTION to expected.description, Events.EVENT_LOCATION to expected.location,
            Events.ALL_DAY to if (expected.time is EventTime.AllDay) "1" else "0",
            Events.DTSTART to bounds.first.toString(), Events.DTEND to bounds.second.toString())
        val numeric = setOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.ALL_DAY, Events.DTSTART, Events.DTEND)
        val empty = setOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        val origins = setOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)
        fun read(label: String, columns: Array<String>?): Map<String, String?> = app.contentResolver.query(
            Events.CONTENT_URI, columns, "${Events.CALENDAR_ID}=? AND ${Events._ID}=?",
            arrayOf(calendar.toString(), row.toString()), null)!!.use { cursor ->
            assertTrue("Require the one enrolled native row", cursor.count == 1 && cursor.moveToFirst())
            output.putBoolean("handoff_diag_create_${label}_unique_names", cursor.columnNames.toSet().size == cursor.columnCount)
            val values = cursor.columnNames.indices.associate { cursor.getColumnName(it) to if (cursor.isNull(it)) null else cursor.getString(it) }
            for (field in fields) {
                val indices = cursor.columnNames.indices.filter { cursor.getColumnName(it) == field }
                output.putBoolean("handoff_diag_create_${label}_${field}_present", indices.isNotEmpty())
                output.putBoolean("handoff_diag_create_${label}_${field}_unique", indices.size == 1)
                val type = indices.singleOrNull()?.let(cursor::getType)
                output.putBoolean("handoff_diag_create_${label}_${field}_type_expected", when {
                    field in empty -> type == android.database.Cursor.FIELD_TYPE_NULL
                    field in numeric -> type == android.database.Cursor.FIELD_TYPE_INTEGER
                    field == Events.EVENT_END_TIMEZONE && expected.time is EventTime.Timed ->
                        type == android.database.Cursor.FIELD_TYPE_NULL || type == android.database.Cursor.FIELD_TYPE_STRING
                    else -> type == android.database.Cursor.FIELD_TYPE_STRING
                })
                val matches = when {
                    field in exact -> values[field] == exact[field]
                    field in empty -> values[field] == null
                    field in origins -> !values[field].isNullOrEmpty()
                    expected.time is EventTime.AllDay -> values[field] == "UTC"
                    else -> values[field]?.let {
                        try { java.time.ZoneId.of(it).normalized() == (expected.time as EventTime.Timed).zone.normalized() }
                        catch (_: java.time.DateTimeException) { false }
                    } ?: (field == Events.EVENT_END_TIMEZONE)
                }
                output.putBoolean("handoff_diag_create_${label}_${field}_matches", matches)
            }
            output.putBoolean("handoff_diag_create_${label}_typed_time_matches", TitleJourneyTimeProfile.nativeMatches(expected.time,
                values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND], values[Events.EVENT_TIMEZONE], values[Events.EVENT_END_TIMEZONE]))
            values.filterKeys { it in fields }
        }
        output.putBoolean("handoff_diag_create_expected_matches_initial", expected == source.initial.base.content)
        output.putBoolean("handoff_diag_create_expected_matches_current", expected == frame.base.content)
        output.putBoolean("handoff_diag_create_scope_matches", scope == frame.scope)
        output.putBoolean("handoff_diag_create_origin_matches", frame.receipt == source.initial.receipt)
        output.putBoolean("handoff_diag_create_base_original", frame.base == source.initial.base)
        output.putBoolean("handoff_diag_create_released", source.current.state == CapturedCreateHandoffState.RELEASED)
        output.putBoolean("handoff_diag_create_not_waiting", !source.current.waiting)
        output.putBoolean("handoff_diag_create_clean_witness", projection.inspectCapturedHandoffBase(frame)?.frame == frame)
        val witness = projection.inspectOwnedEvent(scope, frame.eventId, frame.base.revision)
        output.putBoolean("handoff_diag_create_owned_witness", witness != null && witness.eventRow == row && witness.calendarRow == calendar)
        val first = read("implicit_first", null)
        currentCoroutineContext().ensureActive()
        val explicit = read("explicit", fields.toTypedArray())
        currentCoroutineContext().ensureActive()
        val second = read("implicit_second", null)
        for (field in fields) {
            output.putBoolean("handoff_diag_create_${field}_first_explicit_same", first.containsKey(field) == explicit.containsKey(field) && first[field] == explicit[field])
            output.putBoolean("handoff_diag_create_${field}_second_explicit_same", second.containsKey(field) == explicit.containsKey(field) && second[field] == explicit[field])
        }
        output.putBoolean("handoff_diag_create_all_reads_same", first == explicit && explicit == second)
        currentCoroutineContext().ensureActive()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
    }

    /** Column fingerprints and domain evidence remain in memory; nothing is persisted or printed. */
    private class SettledDiagnosticSnapshot(val checksum: String, val columns: Map<String, String?>,
        val create: CreateProof, val title: TitleProof)

    private suspend fun settledDiagnosticSnapshot(app: Context, scope: CalendarScope, prefs: SharedPreferences): SettledDiagnosticSnapshot {
        val event = EventId(checkNotNull(prefs.getString("event", null)))
        val tables = linkedMapOf(
            "captured_create_handoffs" to listOf("scope", "event", "nonce", "protocol", "ack_nonce", "ack_fingerprint", "fingerprint", "state", "waiting", "last_projection"),
            "projection_bases" to listOf("scope", "event", "payload", "representation"))
        ImportStore(app).use { store ->
            val key = scopeKey(store.readableDatabase, scope)
            fun raw() = if (prefs.contains("settled_frame_payload")) digest(rawSettledFramePayload(store.readableDatabase, key, event))
                else digest(*tables.keys.map { rowDigest(store.readableDatabase, it,
                    "scope=? AND event=?", arrayOf(key, event.value)) }.toTypedArray())
            val checksum = raw()
            val columns = buildMap<String, String?> {
                for ((table, allowed) in tables) store.readableDatabase.rawQuery(
                    "SELECT * FROM $table WHERE scope=? AND event=? LIMIT 2", arrayOf(key, event.value)).use { cursor ->
                    assertTrue("Require one bounded diagnostic row and the known schema", cursor.count == 1 && cursor.moveToFirst() &&
                        cursor.columnNames.toSet() == allowed.toSet())
                    for (name in allowed) {
                        val index = cursor.getColumnIndexOrThrow(name)
                        put("${table}_$name", if (cursor.isNull(index)) null else digest(cursor.getType(index).toString(), cursor.getString(index)))
                    }
                }
            }
            val create = readCreateProof(app, scope, prefs.getLong("event_row", -1), normalizeQueueStatusForRecovery = true)
            val title = checkNotNull(readTitleProof(app, scope, event)) { "Missing retained diagnostic title proof" }
            currentCoroutineContext().ensureActive()
            assertTrue("Do not classify a frame that changed during snapshot collection", raw() == checksum)
            return SettledDiagnosticSnapshot(checksum, columns, create, title)
        }
    }

    /** Read-only classification: missing data and failed decoding are separate witnesses.
     * Runtime workers may run independently because this test uses the real application.
     */
    private suspend fun diagnose(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope, prefs: SharedPreferences) {
        val output = Bundle()
        suspend fun <T> read(name: String, action: suspend () -> T): T? = try {
            currentCoroutineContext().ensureActive()
            action().also { currentCoroutineContext().ensureActive(); output.putBoolean("handoff_diag_${name}_valid", true) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AssertionError) { currentCoroutineContext().ensureActive(); output.putBoolean("handoff_diag_${name}_valid", false); null }
        catch (_: Exception) { output.putBoolean("handoff_diag_${name}_valid", false); null }
        val event = EventId(checkNotNull(prefs.getString("event", null)) { "Missing enrolled event" })
        val row = prefs.getLong("event_row", -1)
        val work = read("work") { SystemCreateWorkStore(app).use { it.read(scope, row) } }
        output.putBoolean("handoff_diag_work_present", work != null)
        work?.let {
            output.putString("handoff_diag_work_state", it.status.name)
            output.putBoolean("handoff_diag_work_locator_matches", it.scope == scope && it.row == row)
        }
        val role = read("handoff") { ImportStore(app).use { it.capturedHandoff(scope, event) } }
        output.putBoolean("handoff_diag_handoff_present", role != null)
        role?.let {
            val original = it.frame.receipt.acknowledgement.baseline
            val identity = original.receipt.binding.identity
            output.putBoolean("handoff_diag_work_identity_matches_origin", work != null && identity.localId == work.localId &&
                identity.captureId == work.captureId && identity.attempt.id == work.attempt && identity.attempt.scope == work.scope)
            output.putBoolean("handoff_diag_origin_fingerprint_same", it.frame.receipt.fingerprint() == prefs.getString("frame_fingerprint", null))
            output.putBoolean("handoff_diag_current_base_original", it.frame.base == original.change)
            output.putBoolean("handoff_diag_current_content_original", it.frame.base.content == original.change.content)
            output.putBoolean("handoff_diag_last_projection_present", it.frame.lastProjection != null)
            output.putBoolean("handoff_diag_released", it.state == CapturedCreateHandoffState.RELEASED)
            output.putBoolean("handoff_diag_waiting", it.waiting)
            read("create_links") {
                LocalCreateStore(app).use { local ->
                    val submitted = work?.let { value -> local.readSubmission(scope, value.localId) }
                    output.putBoolean("handoff_diag_create_submission_present", submitted != null)
                    output.putBoolean("handoff_diag_create_submission_matches_origin", submitted != null && CapturedCreateDispatchIdentity.of(submitted) == identity)
                }
                CreateAttemptStore(app).use { store ->
                    val attempt = store.read(scope, identity.attempt.id)
                    output.putBoolean("handoff_diag_create_unknown", attempt?.state == CreateAttemptState.OUTCOME_UNKNOWN)
                    output.putBoolean("handoff_diag_create_evidence", attempt?.protectedEvidence != null)
                    val binding = store.capturedBinding(identity)
                    output.putBoolean("handoff_diag_create_binding_matches_origin", binding == original.receipt.binding)
                    output.putBoolean("handoff_diag_create_attachment_matches_origin", binding != null && store.capturedAttachment(binding) == original.receipt)
                }
            }
        }
        val owner = read("owner") { ImportStore(app).use { it.localEdit(scope, event) } }
        output.putBoolean("handoff_diag_owner_present", owner != null)
        owner?.let {
            output.putString("handoff_diag_owner_state", it.state.name)
            output.putBoolean("handoff_diag_owner_title_only", it.base.content.copy(title = it.content.title) == it.content)
            output.putBoolean("handoff_diag_owner_requested_title", it.content.title == prefs.getString("changed_title", null))
            output.putBoolean("handoff_diag_owner_known_empty", it.content.details == EventDetails() && it.base.content.details == EventDetails())
            output.putBoolean("handoff_diag_owner_simple", it.content.colorRgb == null && it.content.recurrence == null)
            output.putBoolean("handoff_diag_owner_time_unchanged", it.content.time == it.base.content.time)
            output.putBoolean("handoff_diag_owner_base_matches_current", it.base == role?.frame?.base)
        }
        read("import_evidence") {
            ImportStore(app).use { store ->
                val capsule = store.localEditPreparation(scope, event)
                val projectionReceipt = store.projectionReceipt(scope, event)
                output.putBoolean("handoff_diag_capsule_present", capsule != null)
                output.putBoolean("handoff_diag_capsule_matches_owner", capsule != null && capsule.intent == owner)
                output.putBoolean("handoff_diag_delete_present", store.localDelete(scope, event) != null)
                output.putBoolean("handoff_diag_projected_base_matches_current", store.projectedBaseline(scope, event) == role?.frame?.base)
                output.putBoolean("handoff_diag_projection_receipt_present", projectionReceipt != null)
                val key = scopeKey(store.readableDatabase, scope)
                for (table in listOf("records", "deferred_events")) store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM $table WHERE scope=? AND event=?", arrayOf(key, event.value)).use { cursor ->
                    check(cursor.moveToFirst()); output.putInt("handoff_diag_${table}_count", cursor.getInt(0))
                }
                store.readableDatabase.rawQuery("SELECT pending FROM records WHERE scope=? AND event=?",
                    arrayOf(key, event.value)).use {
                    output.putBoolean("handoff_diag_projection_pending", it.moveToFirst() && it.getInt(0) == 1)
                }
            }
        }
        read("native_independent") {
            val values = nativeValues(app, prefs.getLong("calendar_row", -1), row)
            output.putBoolean("handoff_diag_native_dirty", values[Events.DIRTY] == "1")
            output.putBoolean("handoff_diag_native_deleted", values[Events.DELETED] == "1")
            output.putBoolean("handoff_diag_native_title_requested", values[Events.TITLE] == prefs.getString("changed_title", null))
            output.putBoolean("handoff_diag_native_origin_same", originDigest(values) == prefs.getString("native_origin", null))
            output.putBoolean("handoff_diag_native_all_day", values[Events.ALL_DAY] == "1")
            output.putBoolean("handoff_diag_native_no_recurrence", listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
                Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME).all { values[it] == null })
            output.putBoolean("handoff_diag_native_no_color", values[Events.EVENT_COLOR] == null && values[Events.EVENT_COLOR_KEY] == null)
            output.putBoolean("handoff_diag_native_confirmed_busy", values[Events.STATUS] == Events.STATUS_CONFIRMED.toString() &&
                values[Events.AVAILABILITY] == Events.AVAILABILITY_BUSY.toString())
            for (flag in listOf(Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.SELF_ATTENDEE_STATUS,
                Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.ACCESS_LEVEL))
                output.putBoolean("handoff_diag_native_${flag}_zero", values[flag] == "0")
            owner?.let {
                output.putBoolean("handoff_diag_native_matches_owner_fields", values[Events.TITLE] == it.content.title &&
                    (values[Events.DESCRIPTION] ?: "") == it.content.description && (values[Events.EVENT_LOCATION] ?: "") == it.content.location &&
                    TitleJourneyTimeProfile.nativeMatches(it.content.time, values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND],
                        values[Events.EVENT_TIMEZONE], values[Events.EVENT_END_TIMEZONE]))
            }
            for ((name, uri, column) in listOf(
                Triple("reminders", CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID),
                Triple("attendees", CalendarContract.Attendees.CONTENT_URI, CalendarContract.Attendees.EVENT_ID),
                Triple("extended", CalendarContract.ExtendedProperties.CONTENT_URI, CalendarContract.ExtendedProperties.EVENT_ID)))
                app.contentResolver.query(uri, arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use {
                    output.putInt("handoff_diag_native_${name}_count", it.count)
                }
        }
        val independentIds = read("title_history_independent") { historyIds(app, scope, event) }
        if (independentIds != null) output.putInt("handoff_diag_title_attempts", independentIds.size)
        read("title_reservation") {
            TitleUpdateAttemptStore(app).use { output.putBoolean("handoff_diag_title_reservation_present", it.readPreparationReservation(scope, event) != null) }
        }
        independentIds?.singleOrNull()?.let { id ->
            read("title_proof_independent") {
                TitleUpdateAttemptStore(app).use { store ->
                    val record = store.read(scope, id)
                    val result = store.readVerified(scope, id)
                    val completion = store.readCompletion(scope, id)
                    output.putBoolean("handoff_diag_title_record_present", record != null)
                    if (record != null) output.putString("handoff_diag_title_state", record.state.name)
                    output.putBoolean("handoff_diag_title_unknown", record?.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
                    output.putBoolean("handoff_diag_title_evidence", record?.protectedEvidence != null)
                    output.putBoolean("handoff_diag_title_result", result != null)
                    output.putBoolean("handoff_diag_title_completion", completion != null)
                    output.putBoolean("handoff_diag_title_result_requested", result?.event?.content?.title == prefs.getString("changed_title", null))
                    output.putBoolean("handoff_diag_title_owner_matches_preparation", record != null && record.preparation.intent == owner)
                    if (record != null && result != null && completion != null) read("title_evidence_comparison") {
                        val original = readCreateProof(app, scope, row, normalizeQueueStatusForRecovery = true)
                        val frozen = frozenTitleFrame(prefs, original)
                        val expected = original.initial.base.content.copy(title = checkNotNull(prefs.getString("changed_title", null)))
                        titleEvidenceDiagnostics("handoff_diag_title_evidence_", record, result, completion, original.initial, frozen, expected)
                    }
                    ImportStore(app).use { imports ->
                        val ack = imports.titleUpdateAcknowledgement(scope, event, id)
                        output.putBoolean("handoff_diag_title_ack_present", ack != null)
                        if (ack != null) output.putString("handoff_diag_title_ack_state", ack.state.name)
                    }
                }
            }
        }
        read("cached_title_comparison") {
            val id = independentIds?.singleOrNull()
            val result = id?.let { TitleUpdateAttemptStore(app).use { journal -> journal.readVerified(scope, it) } }
            output.putBoolean("handoff_diag_cache_result_available", result != null)
            ImportStore(app).use { store ->
                val baseline = store.projectedBaseline(scope, event)
                output.putBoolean("handoff_diag_cache_baseline_present", baseline != null)
                output.putBoolean("handoff_diag_cache_baseline_matches_result", result != null && baseline == result.event)
                output.putBoolean("handoff_diag_cache_handoff_format_matches_result", result != null &&
                    role?.frame?.format == result.record.preparation.intent.format)
                val key = scopeKey(store.readableDatabase, scope)
                // Inspect types and size before invoking the production read-only decoder.
                // A non-pending cache is deliberately not decoded through a write transaction.
                val metadata = store.readableDatabase.rawQuery(
                    "SELECT CASE WHEN typeof(pending)='integer' AND pending IN (0,1) THEN pending ELSE NULL END," +
                        "CASE WHEN typeof(payload)='text' AND length(CAST(payload AS BLOB))<=1048576 THEN 1 ELSE 0 END " +
                        "FROM records WHERE scope=? AND event=? LIMIT 2", arrayOf(key, event.value)).use {
                    check(it.count <= 1) { "Ambiguous diagnostic cache" }
                    if (!it.moveToFirst()) null else Pair(if (it.isNull(0)) null else it.getInt(0), it.getInt(1) == 1)
                }
                output.putBoolean("handoff_diag_cache_present", metadata != null)
                output.putBoolean("handoff_diag_cache_pending_valid", metadata?.first != null)
                output.putBoolean("handoff_diag_cache_pending_exactly_one", metadata?.first == 1)
                output.putBoolean("handoff_diag_cache_payload_bounded_text", metadata?.second == true)
                output.putString("handoff_diag_cache_kind", when {
                    metadata == null -> "MISSING"
                    metadata.first == null || !metadata.second -> "UNREADABLE"
                    metadata.first == 0 -> "NOT_PENDING_NOT_DECODED"
                    role == null -> "HANDOFF_UNAVAILABLE"
                    else -> "READ_UNAVAILABLE"
                })
                if (metadata?.first == 1 && metadata.second && role != null) {
                    val pending = try { store.capturedHandoffPending(role.frame) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (malformed: org.json.JSONException) {
                        output.putString("handoff_diag_cache_kind", "UNREADABLE"); throw malformed
                    } catch (malformed: IllegalArgumentException) {
                        output.putString("handoff_diag_cache_kind", "UNREADABLE"); throw malformed
                    }
                    currentCoroutineContext().ensureActive()
                    val cached = pending?.change
                    output.putString("handoff_diag_cache_kind", when {
                        cached == null -> "CHANGED_DURING_READ"
                        cached is RemoteChange.Deleted -> "TOMBSTONE"
                        result == null -> "RESULT_UNAVAILABLE"
                        cached == result.event -> "EXACT_RESULT"
                        else -> "DIVERGENT"
                    })
                    output.putBoolean("handoff_diag_cache_scope_matches", pending?.scope == scope)
                    output.putBoolean("handoff_diag_cache_full_matches_result", result != null && cached == result.event)
                    output.putBoolean("handoff_diag_cache_identity_matches_result", result != null && cached?.eventId == result.event.eventId)
                    output.putBoolean("handoff_diag_cache_revision_matches_result", result != null && cached?.revision == result.event.revision)
                    if (cached is RemoteChange.Upsert && result != null) {
                        val expected = result.event.content
                        output.putBoolean("handoff_diag_cache_content_matches_result", cached.content == expected)
                        output.putBoolean("handoff_diag_cache_title_matches_result", cached.content.title == expected.title)
                        output.putBoolean("handoff_diag_cache_description_matches_result", cached.content.description == expected.description)
                        output.putBoolean("handoff_diag_cache_location_matches_result", cached.content.location == expected.location)
                        output.putBoolean("handoff_diag_cache_time_matches_result", cached.content.time == expected.time)
                        output.putBoolean("handoff_diag_cache_details_matches_result", cached.content.details == expected.details)
                        output.putBoolean("handoff_diag_cache_color_matches_result", cached.content.colorRgb == expected.colorRgb)
                        output.putBoolean("handoff_diag_cache_recurrence_matches_result", cached.content.recurrence == expected.recurrence)
                    }
                }
            }
        }
        read("runtime") {
            val application = app as BridgeApplication
            output.putBoolean("handoff_diag_imports_busy", application.imports.state.value.busy)
            output.putBoolean("handoff_diag_system_busy", application.systemCreates.state.value.busy)
            output.putBoolean("handoff_diag_system_failed", application.systemCreates.state.value.failed)
            SystemCreateWorkStore(app).use {
                output.putBoolean("handoff_diag_create_mode", it.modeEnabled(scope)); output.putBoolean("handoff_diag_title_mode", it.titlesEnabled(scope))
            }
            val manager = WorkManager.getInstance(app)
            for ((name, unique) in listOf("local" to LOCAL_WORK, "network" to NETWORK_WORK, "periodic" to PERIODIC_WORK)) {
                val jobs = manager.getWorkInfosForUniqueWork(unique).get()
                output.putInt("handoff_diag_${name}_count", jobs.size)
                output.putInt("handoff_diag_${name}_attempt_max", jobs.maxOfOrNull { it.runAttemptCount } ?: 0)
                for (state in WorkInfo.State.values()) output.putInt("handoff_diag_${name}_${state.name}", jobs.count { it.state == state })
            }
        }
        val create = read("create") { readCreateProof(app, scope, prefs.getLong("event_row", -1)) }
        output.putBoolean("handoff_diag_title_editor_requested", prefs.getBoolean("title_editor_requested", false))
        output.putBoolean("handoff_diag_worker_verified", prefs.getBoolean("worker_verified", false))
        create?.let { proof ->
            val frame = proof.current.frame
            val last = frame.lastProjection
            output.putBoolean("handoff_diag_create_checksum_same", proof.checksum == prefs.getString("create_checksum", null))
            output.putBoolean("handoff_diag_origin_fingerprint_same", proof.initial.receipt.fingerprint() == prefs.getString("frame_fingerprint", null))
            output.putBoolean("handoff_diag_origin_revision_same", proof.initial.base.revision.value == prefs.getString("original_revision", null))
            output.putBoolean("handoff_diag_current_base_original", frame.base == proof.initial.base)
            output.putBoolean("handoff_diag_current_content_original", frame.base.content == proof.initial.base.content)
            output.putBoolean("handoff_diag_current_revision_original", frame.base.revision == proof.initial.base.revision)
            output.putBoolean("handoff_diag_current_receipt_original", frame.receipt == proof.initial.receipt)
            output.putBoolean("handoff_diag_current_frame_original", frame == proof.initial)
            output.putBoolean("handoff_diag_original_last_projection_absent", proof.initial.lastProjection == null)
            output.putBoolean("handoff_diag_released", proof.current.state == CapturedCreateHandoffState.RELEASED)
            output.putBoolean("handoff_diag_waiting", proof.current.waiting)
            output.putBoolean("handoff_diag_last_projection_present", last != null)
            output.putBoolean("handoff_diag_last_change_current", last?.change == frame.base)
            output.putBoolean("handoff_diag_last_previous_original", last?.previousBase == proof.initial.base)
            output.putBoolean("handoff_diag_last_change_original", last?.change == proof.initial.base)
            read("native") {
                val values = nativeValues(app, prefs.getLong("calendar_row", -1), prefs.getLong("event_row", -1))
                output.putBoolean("handoff_diag_native_origin_same", originDigest(values) == prefs.getString("native_origin", null))
                output.putBoolean("handoff_diag_native_clean", values[Events.DIRTY] == "0" && values[Events.DELETED] == "0")
                output.putBoolean("handoff_diag_native_title_original", values[Events.TITLE] == proof.initial.base.content.title)
                output.putBoolean("handoff_diag_native_title_requested", values[Events.TITLE] == prefs.getString("changed_title", null))
                output.putBoolean("handoff_diag_native_matches_current", projection.inspectCapturedHandoffBase(frame)?.frame == frame)
                output.putBoolean("handoff_diag_native_typed_time_original", TitleJourneyTimeProfile.nativeMatches(proof.initial.base.content.time,
                    values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND], values[Events.EVENT_TIMEZONE], values[Events.EVENT_END_TIMEZONE]))
                val owned = projection.inspectOwnedEvent(scope, frame.eventId, frame.base.revision)
                output.putBoolean("handoff_diag_native_same_original_row", owned != null &&
                    owned.eventRow == prefs.getLong("event_row", -1) && owned.calendarRow == prefs.getLong("calendar_row", -1))
            }
            val ids = read("title_history") { historyIds(app, scope, frame.eventId) }
            if (ids != null) output.putInt("handoff_diag_title_attempts", ids.size)
            ids?.singleOrNull()?.let { id ->
                read("title_proof") {
                    TitleUpdateAttemptStore(app).use { store ->
                        val record = store.read(scope, id)
                        val result = store.readVerified(scope, id)
                        val completion = store.readCompletion(scope, id)
                        output.putBoolean("handoff_diag_title_unknown", record?.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
                        output.putBoolean("handoff_diag_title_evidence", record?.protectedEvidence != null)
                        output.putBoolean("handoff_diag_title_result", result != null)
                        output.putBoolean("handoff_diag_title_completion", completion != null)
                        output.putBoolean("handoff_diag_title_base_current", record?.preparation?.intent?.base == frame.base)
                        output.putBoolean("handoff_diag_title_base_original", record?.preparation?.intent?.base == proof.initial.base)
                        output.putBoolean("handoff_diag_title_ack_frame_current", completion?.receipt?.handoff == frame)
                        output.putBoolean("handoff_diag_title_ack_frame_original", completion?.receipt?.handoff == proof.initial)
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
    }

    private suspend fun readCreateProof(app: Context, scope: CalendarScope, row: Long,
        normalizeQueueStatusForRecovery: Boolean = false): CreateProof {
        val work = SystemCreateWorkStore(app).use { checkNotNull(it.read(scope, row)) { "Missing original CREATE work" } }
        assertTrue("Retain the exact original CREATE work", work.scope == scope && work.row == row &&
            (work.status == SystemCreateWorkStatus.COMPLETE || normalizeQueueStatusForRecovery &&
                work.status in setOf(SystemCreateWorkStatus.WAITING, SystemCreateWorkStatus.UNKNOWN)))
        val submission = LocalCreateStore(app).use { checkNotNull(it.readSubmission(scope, work.localId)) { "Missing CREATE submission" } }
        val identity = CapturedCreateDispatchIdentity.of(submission)
        assertTrue("Bind all original CREATE identities", submission.reservation.attempt == work.attempt &&
            submission.reservation.intent.reservation.id == work.captureId && identity.localId == work.localId)
        val pieces = mutableListOf<String>()
        CreateAttemptStore(app).use { journal ->
            val attempt = checkNotNull(journal.read(scope, work.attempt)) { "Missing original CREATE admission" }
            assertTrue("Keep CREATE UNKNOWN and its protected response", attempt.state == CreateAttemptState.OUTCOME_UNKNOWN && attempt.protectedEvidence != null)
            val binding = checkNotNull(journal.capturedBinding(identity)) { "Missing original CREATE binding" }
            val attachment = checkNotNull(journal.capturedAttachment(binding)) { "Missing original CREATE attachment" }
            ImportStore(app).use { store ->
                val current = checkNotNull(store.capturedHandoff(scope, binding.eventId)) { "Missing original CREATE handoff" }
                // The public handoff reader validates the historical baseline and PROVEN
                // original ACK. Pre-handoff capturedBaseline/ack readers intentionally stop
                // granting a current reference once the handoff exists.
                val baseline = current.frame.receipt.acknowledgement.baseline
                assertTrue("All immutable CREATE links must agree", baseline.receipt == attachment &&
                    current.state == CapturedCreateHandoffState.RELEASED)
                val initial = CapturedCreateHandoffFrame(current.frame.receipt, baseline.change)
                val args = arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), work.attempt.value)
                for (table in listOf("create_attempts", "captured_create_links", "captured_create_bindings", "captured_create_attachments"))
                    pieces += rowDigest(journal.readableDatabase, table, "account=? AND calendar=? AND generation=? AND mutation=?", args)
                val key = scopeKey(store.readableDatabase, scope)
                for (table in listOf("captured_create_bases", "captured_create_acknowledgements"))
                    pieces += rowDigest(store.readableDatabase, table, "scope=? AND event=?", arrayOf(key, binding.eventId.value))
                pieces += initial.receipt.fingerprint()
                LocalCreateStore(app).use { local ->
                    for (table in listOf("local_creates", "local_create_submissions")) pieces += rowDigest(local.readableDatabase,
                        table, "account=? AND calendar=? AND generation=? AND local_event=?",
                        arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), work.localId.value))
                }
                SystemCreateWorkStore(app).use { queue -> pieces += rowDigest(queue.readableDatabase, "system_creates",
                    "account=? AND calendar=? AND generation=? AND native_row=?",
                    arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), row.toString()), normalizeQueueStatusForRecovery) }
                return CreateProof(work, initial, current, digest(*pieces.toTypedArray()))
            }
        }
    }

    private suspend fun readTitleProof(app: Context, scope: CalendarScope, event: EventId): TitleProof? {
        val id = historyIds(app, scope, event).singleOrNull() ?: return null
        return TitleUpdateAttemptStore(app).use { store ->
            val record = store.read(scope, id) ?: return@use null
            val result = store.readVerified(scope, id) ?: return@use null
            val completion = store.readCompletion(scope, id) ?: return@use null
            TitleProof(record, result, completion, rowDigest(store.readableDatabase, "title_update_attempts",
                "account=? AND calendar=? AND generation=? AND mutation=? AND event=?",
                arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), id.value, event.value)))
        }
    }

    private suspend fun verifyTitle(app: Context, projection: AndroidCalendarProjection, prefs: SharedPreferences,
        scope: CalendarScope, original: CreateProof, expected: EventContent, proof: TitleProof,
        normalizeQueueStatusForRecovery: Boolean = false) {
        val row = prefs.getLong("event_row", -1); val calendar = prefs.getLong("calendar_row", -1)
        val create = readCreateProof(app, scope, row, normalizeQueueStatusForRecovery)
        assertTrue("Keep immutable original CREATE provenance", create.checksum == prefs.getString("create_checksum", null) &&
            create.initial == original.initial && create.initial.receipt.fingerprint() == prefs.getString("frame_fingerprint", null) &&
            create.initial.eventId.value == prefs.getString("event", null) &&
            create.initial.base.revision.value == prefs.getString("original_revision", null))
        val frozenFrame = frozenTitleFrame(prefs, create)
        val intent = proof.record.preparation.intent
        val uuid = Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        assertTrue("Use worker-generated scoped title identities and the exact original frame", proof.record.id.value.matches(uuid) &&
            intent.id.value.matches(uuid) && proof.record.scope == scope && intent.scope == scope &&
            intent.base == frozenFrame.base && intent.content == expected && intent.state == LocalEditState.PAUSED &&
            proof.record.preparation.timestamp.nano == 0)
        val receipt = proof.completion.receipt
        titleEvidenceDiagnostics("handoff_title_evidence_", proof.record, proof.result, proof.completion, create.initial, frozenFrame, expected)
        assertTrue("Require complete durable title evidence bound to this handoff", proof.record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
            proof.record.protectedEvidence != null && proof.result.record == proof.record.copy(protectedEvidence = null) &&
            proof.result.event.eventId == create.initial.eventId && proof.result.event.content == expected &&
            proof.result.event.revision != create.initial.base.revision && proof.completion.result == proof.result &&
            TransferredTitleFrame.matchesPreEditorFrame(frozenFrame, receipt.handoff) && receipt.matches(proof.result))
        val advanced = CapturedCreateHandoffFrame(checkNotNull(receipt.handoff).receipt, proof.result.event, receipt.projection)
        val currentFrame = create.current.frame
        val echoProjection = currentFrame.lastProjection?.let {
            it.scope == scope && it.format == advanced.format && it.protocol == 1 &&
                it.nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) &&
                it.change == proof.result.event && it.previousBase == proof.result.event &&
                it.representation == null && it.previousRepresentation == null
        } == true
        assertTrue("Advance only through the exact acknowledgement or its identical incoming echo",
            currentFrame.receipt == advanced.receipt && currentFrame.base == advanced.base &&
                (currentFrame.lastProjection == advanced.lastProjection || echoProjection) &&
            create.current.state == CapturedCreateHandoffState.RELEASED && (normalizeQueueStatusForRecovery || !create.current.waiting))
        ImportStore(app).use { store ->
            val ack = store.titleUpdateAcknowledgement(scope, advanced.eventId, proof.record.id)
            assertTrue("Restore the SQL baseline and consume only the qualified title owner", store.projectedBaseline(scope, advanced.eventId) == proof.result.event &&
                store.localEdit(scope, advanced.eventId) == null && store.localDelete(scope, advanced.eventId) == null &&
                store.localEditPreparation(scope, advanced.eventId) == null && ack?.state == TitleUpdateAcknowledgementState.RESTORED && ack.receipt == receipt)
        }
        verifyNative(app, projection, scope, calendar, row, currentFrame, expected)
        assertTrue("Native CREATE origin markers remain immutable", originDigest(nativeValues(app, calendar, row)) == prefs.getString("native_origin", null))
        assertTrue("No extra native row may appear during this journey",
            digest(*nativeRows(app, calendar).sorted().map(Long::toString).toTypedArray()) == prefs.getString("native_rows", null))
        status("handoff_title_typed_time_preserved")
    }

    /** Predicate evidence only; neither adopts another frame nor changes the strict assertion. */
    private fun titleEvidenceDiagnostics(prefix: String, record: TitleUpdateAttemptRecord, result: TitleUpdateVerifiedResult,
        completion: TitleUpdateCompletion, original: CapturedCreateHandoffFrame, frozen: CapturedCreateHandoffFrame,
        expected: EventContent) {
        val receipt = completion.receipt
        val historical = receipt.handoff
        val last = historical?.lastProjection
        val output = Bundle()
        output.putBoolean(prefix + "unknown", record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        output.putBoolean(prefix + "response_present", record.protectedEvidence != null)
        output.putBoolean(prefix + "record_exact", result.record == record.copy(protectedEvidence = null))
        output.putBoolean(prefix + "event_identity_exact", result.event.eventId == original.eventId)
        output.putBoolean(prefix + "content_exact", result.event.content == expected)
        output.putBoolean(prefix + "revision_changed", result.event.revision != original.base.revision)
        output.putBoolean(prefix + "completion_result_exact", completion.result == result)
        output.putBoolean(prefix + "handoff_frozen_exact", historical == frozen)
        output.putBoolean(prefix + "handoff_preeditor_qualified", TransferredTitleFrame.matchesPreEditorFrame(frozen, historical))
        output.putBoolean(prefix + "receipt_matches_result", receipt.matches(result))
        output.putBoolean(prefix + "handoff_present", historical != null)
        output.putBoolean(prefix + "handoff_origin_exact", historical?.receipt == frozen.receipt)
        output.putBoolean(prefix + "handoff_base_exact", historical?.base == frozen.base)
        output.putBoolean(prefix + "handoff_base_identity_exact", historical?.base?.eventId == frozen.base.eventId)
        output.putBoolean(prefix + "handoff_base_revision_exact", historical?.base?.revision == frozen.base.revision)
        output.putBoolean(prefix + "handoff_base_content_exact", historical?.base?.content == frozen.base.content)
        output.putBoolean(prefix + "handoff_scope_exact", historical?.scope == frozen.scope)
        output.putBoolean(prefix + "handoff_format_exact", historical?.format == frozen.format)
        output.putBoolean(prefix + "handoff_last_projection_exact", historical != null && last == frozen.lastProjection)
        output.putBoolean(prefix + "handoff_last_projection_present", last != null)
        output.putBoolean(prefix + "frozen_last_projection_present", frozen.lastProjection != null)
        output.putBoolean(prefix + "last_projection_scope_exact", last?.scope == frozen.scope)
        output.putBoolean(prefix + "last_projection_format_exact", last?.format == frozen.format)
        output.putBoolean(prefix + "last_projection_protocol_exact", last?.protocol == 1)
        output.putBoolean(prefix + "last_projection_nonce_valid", last?.nonce?.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) == true)
        output.putBoolean(prefix + "last_projection_change_exact", last?.change == frozen.base)
        output.putBoolean(prefix + "last_projection_previous_exact", last?.previousBase == frozen.base)
        output.putBoolean(prefix + "last_projection_representations_absent", last != null && last.representation == null && last.previousRepresentation == null)
        output.putBoolean(prefix + "historical_frame_is_exact_base_echo", historical != null &&
            historical.receipt == frozen.receipt && historical.base == frozen.base && historical.scope == frozen.scope &&
            historical.format == frozen.format && last != null && last.scope == frozen.scope && last.format == frozen.format &&
            last.protocol == 1 && last.nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) &&
            last.change == frozen.base && last.previousBase == frozen.base && last.representation == null && last.previousRepresentation == null)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
    }

    private suspend fun verifyNative(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        calendar: Long, row: Long, frame: CapturedCreateHandoffFrame, expected: EventContent) {
        val clean = projection.inspectCapturedHandoffBase(frame)
        val witness = projection.inspectOwnedEvent(scope, frame.eventId, frame.base.revision)
        assertTrue("Keep the qualified transferred original on the same native row", clean?.frame == frame && witness != null &&
            witness.eventRow == row && witness.calendarRow == calendar)
        val output = Bundle()
        val calendarOwner = projection.accountFor(scope.account).name
        val cleanFrameVerified = clean?.frame == frame
        // This is the same query and map construction as nativeValues. Both diagnostics
        // and the qualified transferred-row assertion below consume this one cursor snapshot.
        val values = app.contentResolver.query(Events.CONTENT_URI, null,
            "${Events.CALENDAR_ID}=? AND ${Events._ID}=?", arrayOf(calendar.toString(), row.toString()), null)!!.use { cursor ->
            assertTrue("One retained native row required", cursor.count == 1 && cursor.moveToFirst())
            val fields = listOf(Events._SYNC_ID, Events.SYNC_DATA4, Events.DIRTY, Events.DELETED, Events.TITLE,
                Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY, Events.DTSTART, Events.DTEND,
                Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
                Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10, Events.ORGANIZER, Events.HAS_ATTENDEE_DATA,
                Events.IS_ORGANIZER, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
                Events.GUESTS_CAN_MODIFY, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
            val numeric = setOf(Events.DIRTY, Events.DELETED, Events.ALL_DAY, Events.DTSTART, Events.DTEND,
                Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
                Events.GUESTS_CAN_MODIFY, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
            val empty = setOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
            val snapshot = cursor.columnNames.indices.associate { cursor.getColumnName(it) to if (cursor.isNull(it)) null else cursor.getString(it) }
            val qualifiedNullEnd = expected.time is EventTime.AllDay && snapshot[Events.EVENT_END_TIMEZONE] == null &&
                TransferredTitleNativeTime.nativeMatches(expected.time, snapshot, calendarOwner, cleanFrameVerified)
            output.putBoolean("handoff_native_column_names_unique", cursor.columnNames.toSet().size == cursor.columnCount)
            for (field in fields) {
                val indices = cursor.columnNames.indices.filter { cursor.getColumnName(it) == field }
                val type = indices.singleOrNull()?.let(cursor::getType)
                output.putBoolean("handoff_native_${field}_present", indices.isNotEmpty())
                output.putBoolean("handoff_native_${field}_unique", indices.size == 1)
                output.putBoolean("handoff_native_${field}_type_expected", when {
                    field in empty -> type == android.database.Cursor.FIELD_TYPE_NULL
                    field in numeric -> type == android.database.Cursor.FIELD_TYPE_INTEGER
                    field == Events.EVENT_END_TIMEZONE && expected.time is EventTime.Timed ->
                        type == android.database.Cursor.FIELD_TYPE_STRING || type == android.database.Cursor.FIELD_TYPE_NULL
                    field == Events.EVENT_END_TIMEZONE && qualifiedNullEnd -> type == android.database.Cursor.FIELD_TYPE_NULL
                    else -> type == android.database.Cursor.FIELD_TYPE_STRING
                })
            }
            snapshot
        }
        val identity = projection.eventIdentityKey(scope, frame.eventId)
        val qualifiedPlaceholder = TransferredTitleNativeTime.qualifiedPlaceholder(values, calendarOwner, cleanFrameVerified)
        val typedTimeMatches = TransferredTitleNativeTime.nativeMatches(expected.time, values, calendarOwner, cleanFrameVerified)
        output.putBoolean("handoff_native_qualified_placeholder", qualifiedPlaceholder)
        output.putBoolean("handoff_native_clean_frame", clean?.frame == frame)
        output.putBoolean("handoff_native_owned_same_row", witness != null && witness.eventRow == row && witness.calendarRow == calendar)
        output.putBoolean("handoff_native_sync_identity", values[Events._SYNC_ID] == identity)
        output.putBoolean("handoff_native_remote_identity", values[Events.SYNC_DATA4] == identity)
        output.putBoolean("handoff_native_dirty_zero", values[Events.DIRTY] == "0")
        output.putBoolean("handoff_native_deleted_zero", values[Events.DELETED] == "0")
        output.putBoolean("handoff_native_title_exact", values[Events.TITLE] == expected.title)
        output.putBoolean("handoff_native_description_exact", values[Events.DESCRIPTION] == expected.description)
        output.putBoolean("handoff_native_location_exact", values[Events.EVENT_LOCATION] == expected.location)
        output.putBoolean("handoff_native_typed_time_exact", typedTimeMatches)
        val bounds = TitleJourneyTimeProfile.bounds(expected.time)
        output.putBoolean("handoff_native_time_start_exact", values[Events.DTSTART] == bounds.first.toString())
        output.putBoolean("handoff_native_time_end_exact", values[Events.DTEND] == bounds.second.toString())
        output.putBoolean("handoff_native_all_day_exact", values[Events.ALL_DAY] == if (expected.time is EventTime.AllDay) "1" else "0")
        output.putBoolean("handoff_native_end_zone_null", values[Events.EVENT_END_TIMEZONE] == null)
        for (field in listOf(Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE)) {
            val zone = values[field]
            val matches = when (val time = expected.time) {
                is EventTime.AllDay -> zone == "UTC" ||
                    (field == Events.EVENT_END_TIMEZONE && zone == null && qualifiedPlaceholder && typedTimeMatches)
                is EventTime.Timed -> if (zone == null) field == Events.EVENT_END_TIMEZONE else try {
                    java.time.ZoneId.of(zone).normalized() == time.zone.normalized()
                } catch (_: java.time.DateTimeException) { false }
            }
            output.putBoolean("handoff_native_${field}_matches", matches)
        }
        for (field in listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7))
            output.putBoolean("handoff_native_${field}_nonempty", !values[field].isNullOrEmpty())
        for (field in listOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10))
            output.putBoolean("handoff_native_${field}_empty", values[field] == null)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
        assertTrue("Keep the exact remote identity and clean typed content", values[Events._SYNC_ID] == identity && values[Events.SYNC_DATA4] == identity &&
            values[Events.DIRTY] == "0" && values[Events.DELETED] == "0" && values[Events.TITLE] == expected.title &&
            values[Events.DESCRIPTION] == expected.description && values[Events.EVENT_LOCATION] == expected.location &&
            typedTimeMatches &&
            listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7).all { !values[it].isNullOrEmpty() } &&
            listOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).all { values[it] == null })
        app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
            arrayOf(calendar.toString(), identity, identity), null)!!.use {
            assertTrue("Never insert a second projection of the original", it.count == 1 && it.moveToFirst() && it.getLong(0) == row)
        }
    }

    private suspend fun requireSettledTitleEvidence(app: Context, scope: CalendarScope, event: EventId,
        recoveryEcho: TitleProof? = null) {
        TitleUpdateAttemptStore(app).use {
            assertTrue("A completed title cannot retain an open preparation reservation", it.readPreparationReservation(scope, event) == null)
        }
        ImportStore(app).use { store ->
            assertTrue("Recovery cannot consume or replace another owner", store.localEdit(scope, event) == null &&
                store.localEditPreparation(scope, event) == null && store.localDelete(scope, event) == null)
            val key = scopeKey(store.readableDatabase, scope)
            for (table in listOf("deferred_events", "projection_receipts")) store.readableDatabase.rawQuery(
                "SELECT 1 FROM $table WHERE scope=? AND event=? LIMIT 1", arrayOf(key, event.value)).use {
                assertTrue("Recovery refuses unresolved remote or projection work", !it.moveToFirst())
            }
            val pending = store.readableDatabase.rawQuery("SELECT CASE WHEN typeof(pending)='integer' AND pending IN (0,1) " +
                "THEN pending ELSE NULL END FROM records WHERE scope=? AND event=? LIMIT 2", arrayOf(key, event.value)).use {
                assertTrue("Require one valid retained remote observation", it.count == 1 && it.moveToFirst() && !it.isNull(0))
                it.getInt(0)
            }
            if (pending == 1 && recoveryEcho != null) {
                val role = checkNotNull(store.capturedHandoff(scope, event)) { "Missing qualified recovery handoff" }
                // capturedHandoff validates the persisted import format as well as the full frame.
                assertTrue("Recovery admits only the already verified identical pending echo",
                    recoveryEcho.record.scope == scope && recoveryEcho.result.event.eventId == event &&
                        role.state == CapturedCreateHandoffState.RELEASED &&
                        role.frame.format == recoveryEcho.record.preparation.intent.format &&
                        role.frame.base == recoveryEcho.result.event &&
                        store.projectedBaseline(scope, event) == recoveryEcho.result.event &&
                        store.capturedHandoffPending(role.frame) == ProjectionIntent(scope, recoveryEcho.result.event))
            } else {
                assertTrue("Require a settled retained remote observation", pending == 0)
            }
        }
    }

    /** Fixture-only typed SQL snapshot; no migration or reconstruction of hash-only enrollments. */
    private fun rawSettledFramePayload(db: SQLiteDatabase, key: String, event: EventId): String {
        val columns = linkedMapOf(
            "captured_create_handoffs" to listOf("scope", "event", "nonce", "protocol", "ack_nonce", "ack_fingerprint", "fingerprint", "state", "waiting", "last_projection"),
            "projection_bases" to listOf("scope", "event", "payload", "representation"))
        val snapshot = JSONObject()
        for ((table, expected) in columns) db.rawQuery("SELECT * FROM $table WHERE scope=? AND event=? LIMIT 2",
            arrayOf(key, event.value)).use { cursor ->
            assertTrue("Require the exact complete snapshot schema and one row", cursor.count == 1 && cursor.moveToFirst() &&
                cursor.columnNames.toSet() == expected.toSet())
            for (column in expected) {
                val index = cursor.getColumnIndexOrThrow(column)
                val type = cursor.getType(index)
                assertTrue("Only original SQL text, integer and null fields are supported", type in setOf(0, 1, 3))
                snapshot.put("${table}_$column", org.json.JSONArray().put(type)
                    .put(if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index)))
            }
        }
        return snapshot.toString().also { assertTrue("Bound the retained fixture snapshot", it.length <= 2_097_152) }
    }

    private suspend fun settledFramePayload(app: Context, scope: CalendarScope, event: EventId,
        expected: RemoteChange.Upsert): String =
        ImportStore(app).use { store ->
            val key = scopeKey(store.readableDatabase, scope)
            val payload = rawSettledFramePayload(store.readableDatabase, key, event)
            val role = checkNotNull(store.capturedHandoff(scope, event)) { "Missing settled frame" }
            assertTrue("Only a validated released frame on the complete verified result can be frozen",
                role.state == CapturedCreateHandoffState.RELEASED && !role.waiting && role.frame.base == expected)
            assertTrue("The decoded frame must belong to the exact frozen SQL rows",
                rawSettledFramePayload(store.readableDatabase, key, event) == payload)
            payload
        }

    private suspend fun verifySettledFramePayload(app: Context, scope: CalendarScope, event: EventId,
        prefs: SharedPreferences, proof: TitleProof) {
        val payload = checkNotNull(prefs.getString("settled_frame_payload", null)) {
            "Hash-only historical enrollments cannot qualify this replay"
        }
        assertTrue("Retain the original complete snapshot and checksum together", payload.length <= 2_097_152 &&
            digest(payload) == prefs.getString("settled_frame_checksum", null))
        val original = JSONObject(payload)
        ImportStore(app).use { store ->
            val key = scopeKey(store.readableDatabase, scope)
            val currentPayload = rawSettledFramePayload(store.readableDatabase, key, event)
            val current = JSONObject(currentPayload)
            assertTrue("Keep every enrolled SQL column", original.keys().asSequence().toSet() == current.keys().asSequence().toSet())
            val echoColumn = "captured_create_handoffs_last_projection"
            for (column in current.keys()) if (column != echoColumn) {
                assertTrue("Keep every original SQL value except an identical incoming projection receipt",
                    original.getJSONArray(column).toString() == current.getJSONArray(column).toString())
            }
            val role = checkNotNull(store.capturedHandoff(scope, event)) { "Missing replay handoff" }
            assertTrue("The current complete decoded frame remains bound to the historical acknowledgement",
                role.state == CapturedCreateHandoffState.RELEASED && !role.waiting && role.frame.base == proof.result.event &&
                    role.frame.receipt == proof.completion.receipt.handoff?.receipt &&
                    role.frame.format == proof.record.preparation.intent.format)
            if (original.getJSONArray(echoColumn).toString() != current.getJSONArray(echoColumn).toString()) {
                val last = role.frame.lastProjection
                assertTrue("Only a complete exact echo may replace the import projection receipt", last != null &&
                    last.scope == scope && last.format == role.frame.format && last.protocol == 1 &&
                    last.nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) &&
                    last.change == proof.result.event && last.previousBase == proof.result.event &&
                    last.representation == null && last.previousRepresentation == null)
            }
            assertTrue("Do not qualify a mixed or concurrently changing snapshot",
                rawSettledFramePayload(store.readableDatabase, key, event) == currentPayload)
        }
    }

    private fun timeProfileMatches(profile: TitleJourneyTimeProfile, time: EventTime): Boolean = when (time) {
        is EventTime.AllDay -> profile.allDay && time == profile.time
        is EventTime.Timed -> !profile.allDay && TitleJourneyTimeProfile.bounds(time) == (profile.startMillis to profile.endMillis)
    }
    /** Fixture-only canonical snapshot. This narrowly admits an incoming projection of the
     * exact original base; another revision/content requires a different qualification.
     * Immutable CREATE provenance is referenced by its full retained-row checksum and the
     * original receipt fingerprint; the current base and last projection are persisted whole.
     */
    private fun framePayload(original: CapturedCreateHandoffFrame, frame: CapturedCreateHandoffFrame, creationChecksum: String): String {
        require(frame.receipt == original.receipt && frame.base == original.base)
        fun scope(value: CalendarScope) = JSONObject().put("account", value.account.value)
            .put("calendar", value.calendar.value).put("generation", value.generation)
        fun format(value: AdapterFormat) = JSONObject().put("id", value.id).put("version", value.version)
        fun event(value: RemoteChange.Upsert): JSONObject {
            val content = value.content
            require(content.details == EventDetails() && content.colorRgb == null && content.recurrence == null)
            val time = when (val valueTime = content.time) {
                is EventTime.AllDay -> JSONObject().put("kind", "DATE").put("start", valueTime.start.toString()).put("endExclusive", valueTime.endExclusive.toString())
                is EventTime.Timed -> JSONObject().put("kind", "TIMED").put("start", valueTime.start.toString())
                    .put("end", valueTime.end.toString()).put("zone", valueTime.zone.id)
            }
            return JSONObject().put("id", value.eventId.value).put("revision", value.revision.value)
                .put("title", content.title).put("description", content.description).put("location", content.location)
                .put("time", time).put("knownEmptyDetails", true).put("color", JSONObject.NULL).put("recurrence", JSONObject.NULL)
        }
        val last = frame.lastProjection?.let {
            require(it.scope == frame.scope && it.format == frame.format && it.change == original.base &&
                it.previousBase == original.base && it.representation == null && it.previousRepresentation == null)
            JSONObject().put("scope", scope(it.scope)).put("format", format(it.format)).put("protocol", it.protocol)
                .put("nonce", it.nonce).put("change", event(it.change)).put("previousBase", event(checkNotNull(it.previousBase)))
                .put("representation", JSONObject.NULL).put("previousRepresentation", JSONObject.NULL)
        }
        return JSONObject().put("protocol", 1).put("createChecksum", creationChecksum)
            .put("originalReceipt", original.receipt.fingerprint()).put("scope", scope(frame.scope)).put("format", format(frame.format))
            .put("base", event(frame.base)).put("lastProjection", last ?: JSONObject.NULL).toString()
    }
    private fun frozenTitleFrame(prefs: SharedPreferences, create: CreateProof): CapturedCreateHandoffFrame {
        assertTrue("Require the immutable full title-frame enrollment", prefs.getInt("title_frame_protocol", -1) == 1 &&
            prefs.contains("title_frame_has_projection") && create.checksum == prefs.getString("create_checksum", null))
        val initial = create.initial
        val last = if (prefs.getBoolean("title_frame_has_projection", false)) {
            assertTrue("Keep the original projection protocol", prefs.getInt("title_frame_projection_protocol", -1) == 1)
            ProjectionReceipt(initial.scope, initial.format, initial.base,
                checkNotNull(prefs.getString("title_frame_projection_nonce", null)) { "Missing frozen projection nonce" }, initial.base)
        } else {
            assertTrue("Absent projection cannot borrow metadata", prefs.getString("title_frame_projection_nonce", null) == null &&
                prefs.getInt("title_frame_projection_protocol", -1) == 0)
            null
        }
        return CapturedCreateHandoffFrame(initial.receipt, initial.base, last).also {
            assertTrue("Reconstruct every field of the exact frozen title frame",
                framePayload(initial, it, create.checksum) == prefs.getString("title_frame_payload", null))
        }
    }
    private fun hasTitleFrame(prefs: SharedPreferences) = listOf("title_frame_protocol", "title_frame_payload",
        "title_frame_has_projection", "title_frame_projection_nonce", "title_frame_projection_protocol", "title_pid").any(prefs::contains)
    private fun originDigest(values: Map<String, String?>) = digest(*listOf(Events.SYNC_DATA5, Events.SYNC_DATA6,
        Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).map { it + ":" + values[it] }.toTypedArray())
    private fun nativeValues(app: Context, calendar: Long, row: Long): Map<String, String?> = app.contentResolver.query(
        Events.CONTENT_URI, null, "${Events.CALENDAR_ID}=? AND ${Events._ID}=?", arrayOf(calendar.toString(), row.toString()), null)!!.use { cursor ->
        assertTrue("One retained native row required", cursor.count == 1 && cursor.moveToFirst())
        cursor.columnNames.indices.associate { cursor.getColumnName(it) to if (cursor.isNull(it)) null else cursor.getString(it) }
    }
    private fun findRow(app: Context, calendar: Long, title: String): Long? = app.contentResolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?", arrayOf(calendar.toString(), title), null)!!.use {
        assertTrue("The synthetic editor identity must remain unique", it.count <= 1)
        if (it.moveToFirst()) it.getLong(0) else null
    }
    private fun nativeRows(app: Context, calendar: Long): Set<Long> = app.contentResolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { cursor ->
        assertTrue("The dedicated calendar exceeds bounded fixture enrollment", cursor.count <= 512)
        buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }
    private fun existingCalendar(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope): Long {
        val account = projection.accountFor(scope.account)
        return app.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, digest("calendar-v1", scope.account.value, scope.calendar.value, scope.generation.toString())), null)!!.use {
            assertTrue("Require the existing unique production mirror", it.count == 1 && it.moveToFirst() && it.getString(1) == "calendar-bridge-projection-v1")
            it.getLong(0)
        }
    }
    private fun scopeKey(db: SQLiteDatabase, scope: CalendarScope): String = db.rawQuery(
        "SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?",
        arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString())).use {
        assertTrue("Require one production import scope", it.count == 1 && it.moveToFirst()); it.getString(0)
    }
    private fun rowDigest(db: SQLiteDatabase, table: String, where: String, args: Array<String>,
        normalizeQueueStatusForRecovery: Boolean = false): String =
        db.rawQuery("SELECT * FROM $table WHERE $where LIMIT 2", args).use { cursor ->
            require(!normalizeQueueStatusForRecovery || table == "system_creates")
            assertTrue("Require exactly one immutable evidence row", cursor.count == 1 && cursor.moveToFirst())
            digest(table, *cursor.columnNames.indices.flatMap { listOf(cursor.getColumnName(it),
                if (normalizeQueueStatusForRecovery && cursor.getColumnName(it) == "status") {
                    assertTrue("Only the known mutable CREATE tracking states may be normalized", cursor.getString(it) in setOf("WAITING", "UNKNOWN", "COMPLETE"))
                    "VALUE:COMPLETE"
                } else if (cursor.isNull(it)) "NULL" else "VALUE:" + cursor.getString(it)) }.toTypedArray())
        }
    private fun historyIds(app: Context, scope: CalendarScope, event: EventId): List<MutationId> = TitleUpdateAttemptStore(app).use {
        it.readableDatabase.rawQuery("SELECT mutation FROM title_update_attempts WHERE account=? AND calendar=? AND event=? LIMIT 2",
            arrayOf(scope.account.value, scope.calendar.value, event.value)).use { cursor ->
            assertTrue("No duplicate title attempts may exist", cursor.count <= 1)
            buildList { while (cursor.moveToNext()) add(MutationId(cursor.getString(0))) }
        }
    }
    private suspend fun <T : Any> awaitValue(read: suspend () -> T?): T = withTimeout(300_000) {
        var result = read()
        while (result == null) { delay(500); currentCoroutineContext().ensureActive(); result = read() }
        result
    }
    private fun digest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { val bytes = it.toByteArray(Charsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun status(name: String, value: Boolean = true) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putBoolean(name, value) })

    private suspend fun assertScopeQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        recovering: TitleUpdateAttemptRecord? = null, recoveringRow: Long? = null, recoveringCreate: CreateProof? = null) {
        require((recovering == null) == (recoveringRow == null))
        require(recovering == null || (recovering.scope == scope && checkNotNull(recoveringRow) > 0))
        require(recoveringCreate == null || recovering == null && recoveringCreate.work.scope == scope &&
            recoveringCreate.work.row > 0 && recoveringCreate.current.state == CapturedCreateHandoffState.RELEASED)
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        ImportStore(app).use { store ->
            val inCalendar = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?)"
            val retainedScope = if (recovering == null) null else store.readableDatabase.rawQuery(
                "SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?",
                scopeArgs + scope.generation.toString()).use {
                assertTrue("Recovery requires exactly the enrolled import scope", it.count == 1 && it.moveToFirst())
                it.getString(0)
            }
            val ownerExclusion = if (recovering == null) "" else " AND NOT (scope=? AND event=? AND mutation=?)"
            val ownerArgs = if (recovering == null) scopeArgs else scopeArgs + arrayOf(checkNotNull(retainedScope),
                recovering.preparation.intent.base.eventId.value, recovering.preparation.intent.id.value)
            for (table in listOf("local_edits", "local_edit_preparations")) empty(store.readableDatabase,
                "SELECT 1 FROM $table WHERE $inCalendar$ownerExclusion LIMIT 1", ownerArgs,
                message = "The calendar retains another local owner or capsule")
            empty(store.readableDatabase, "SELECT 1 FROM title_update_acknowledgements WHERE $inCalendar AND state!='RESTORED' LIMIT 1",
                message = "The calendar retains an unfinished title acknowledgement")
            val createExclusion = if (recoveringCreate == null) "" else " AND NOT (scope=? AND event=? AND state='RELEASED' AND waiting=1)"
            val createArgs = if (recoveringCreate == null) scopeArgs else scopeArgs + arrayOf(scopeKey(store.readableDatabase, scope), recoveringCreate.initial.eventId.value)
            empty(store.readableDatabase, "SELECT 1 FROM captured_create_handoffs WHERE $inCalendar AND (state!='RELEASED' OR waiting!=0)$createExclusion LIMIT 1", createArgs,
                message = "The calendar retains an unfinished CREATE handoff")
        }
        val finishedCreates = SystemCreateWorkStore(app).use { store ->
            val work = recoveringCreate?.work
            val exclusion = if (work == null) "" else " AND NOT (generation=? AND native_row=? AND local_id=? AND capture_id=? AND attempt=? AND timestamp=? AND status IN ('WAITING','UNKNOWN'))"
            val workArgs = if (work == null) scopeArgs else scopeArgs + arrayOf(work.scope.generation.toString(), work.row.toString(),
                work.localId.value, work.captureId.value, work.attempt.value, work.timestamp.toString())
            empty(store.readableDatabase, "SELECT 1 FROM system_creates WHERE account=? AND calendar=? AND status!='COMPLETE'$exclusion LIMIT 1", workArgs,
                message = "The calendar retains unfinished system CREATE work")
            metadata(store.readableDatabase, "system_creates", listOf("generation", "attempt", "local_id", "capture_id"), scopeArgs)
        }
        LocalCreateStore(app).use { store ->
            for (row in metadata(store.readableDatabase, "local_creates", listOf("generation", "local_event", "mutation"), scopeArgs)) {
                assertTrue("A retained CREATE capture lacks closed production work", finishedCreates.count {
                    it[0] == row[0] && it[2] == row[1] && it[3] == row[2]
                } == 1)
            }
            for (row in metadata(store.readableDatabase, "local_create_submissions", listOf("generation", "local_event", "attempt"), scopeArgs)) {
                assertTrue("A retained CREATE reservation lacks closed production work", finishedCreates.count {
                    it[0] == row[0] && it[2] == row[1] && it[1] == row[2]
                } == 1)
            }
        }
        CreateAttemptStore(app).use { store ->
            for (row in metadata(store.readableDatabase, "create_attempts", listOf("generation", "mutation", "state"), scopeArgs)) {
                val completed = finishedCreates.singleOrNull { it[0] == row[0] && it[1] == row[1] }
                assertTrue("An existing CREATE attempt is not closed production history", row[2] == "OUTCOME_UNKNOWN" && completed != null)
                val work = checkNotNull(completed)
                store.readableDatabase.rawQuery("SELECT 1 FROM captured_create_attachments WHERE account=? AND calendar=? AND generation=? " +
                    "AND mutation=? AND local_id=? AND capture_id=? LIMIT 2",
                    scopeArgs + arrayOf(row[0], row[1], work[2], work[3])).use {
                    assertTrue("Closed CREATE work must retain its original attachment", it.count == 1)
                }
            }
        }
        TitleUpdateAttemptStore(app).use { store ->
            val closed = metadata(store.readableDatabase, "title_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)
            val recoveringIdentity = recovering?.let { listOf(scope.generation.toString(), it.id.value, it.preparation.intent.base.eventId.value) }
            for (row in closed) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val completion = store.readCompletion(retainedScope, MutationId(row[1]))
                if (row == recoveringIdentity) {
                    assertTrue("Only the exact frozen attempt may remain incomplete for recovery",
                        store.read(retainedScope, MutationId(row[1])) == recovering && completion == null)
                } else assertTrue("The calendar retains an unfinished or invalid title attempt", completion != null &&
                        completion.result.record.scope == retainedScope && completion.result.record.id.value == row[1] &&
                        completion.result.event.eventId.value == row[2])
            }
            for (row in metadata(store.readableDatabase, "title_update_preparation_reservations", listOf("generation", "mutation", "event"), scopeArgs)) {
                val reservation = store.readPreparationReservation(scope.copy(generation = row[0].toLong()), EventId(row[2]))
                if (row == recoveringIdentity) assertTrue("Only the exact recovery reservation may remain active",
                    row in closed && reservation?.matches(checkNotNull(recovering)) == true)
                else assertTrue("The calendar retains a bare or mismatched title reservation", row in closed && reservation == null)
            }
        }
        val account = projection.accountFor(scope.account)
        fun uri(value: android.net.Uri) = value.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        val calendarKey = digest("calendar-v1", scope.account.value, scope.calendar.value, scope.generation.toString())
        app.contentResolver.query(uri(CalendarContract.Calendars.CONTENT_URI), arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, calendarKey), null)!!.use { calendars ->
            assertTrue("The production native calendar must be unique", calendars.count <= 1)
            if (recovering != null) assertTrue("Recovery requires the original scoped native calendar", calendars.count == 1)
            val exclusion = if (recoveringRow == null) "" else " AND ${Events._ID}!=?"
            if (calendars.moveToFirst()) app.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND (${Events.DIRTY} IS NULL OR ${Events.DIRTY}!=0 OR ${Events.DELETED} IS NULL OR " +
                    "${Events.DELETED}!=0 OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID}='' OR ${Events.SYNC_DATA4} IS NULL)$exclusion",
                if (recoveringRow == null) arrayOf(calendars.getLong(0).toString())
                else arrayOf(calendars.getLong(0).toString(), recoveringRow.toString()), null)!!.use { rows ->
                assertTrue("The calendar contains another new, dirty, deleted or unowned native row", !rows.moveToFirst())
            }
            if (recovering != null) app.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events._ID}=? AND ${Events._SYNC_ID}=? AND ${Events.SYNC_DATA4}=?",
                arrayOf(calendars.getLong(0).toString(), checkNotNull(recoveringRow).toString(),
                    projection.eventIdentityKey(scope, recovering.preparation.intent.base.eventId),
                    projection.eventIdentityKey(scope, recovering.preparation.intent.base.eventId)), null)!!.use {
                assertTrue("The sole recovery exception must belong to the enrolled native calendar", it.count == 1)
            }
        }
    }

    /** Only bounded metadata is read; no pending event/payload is decoded or resumed. */
    private fun metadata(db: SQLiteDatabase, table: String, columns: List<String>, args: Array<String>): List<List<String>> {
        val selected = columns.joinToString(",") { "CASE WHEN length(CAST($it AS BLOB))<=4096 THEN CAST($it AS TEXT) END" }
        return db.rawQuery("SELECT $selected FROM $table WHERE account=? AND calendar=? LIMIT 513", args).use { cursor ->
            assertTrue("The enrollment metadata exceeds its bounded audit", cursor.count <= 512)
            buildList { while (cursor.moveToNext()) add(columns.indices.map {
                assertTrue("The enrollment metadata is incomplete", !cursor.isNull(it)); cursor.getString(it)
            }) }
        }
    }


    private companion object {
        const val QA_CALENDAR = "Calendeer QA Automatic Worker"
        const val NETWORK_WORK = "calendar-import-network"
        const val LOCAL_WORK = "calendar-import-local"
        const val PERIODIC_WORK = "calendar-import-periodic"
    }
}

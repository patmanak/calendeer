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
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.AndroidTitleRecoveryCursorStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly enrolled ordinary imported event. RUN stays alive while the external runner
 * saves a title in the system editor, so the production ContentObserver remains installed.
 * Only production WorkManager jobs prepare, submit, inspect and acknowledge the edit. This
 * qualifies the automatic pipeline, not exclusive ContentObserver causality: the periodic
 * worker is sampled as well and can also trigger normal processing. RECOVER only resumes
 * an enrolled, already verified UNKNOWN proof through automatic jobs; it is not a new
 * editor journey and never prepares or submits an edit itself. REPLAY
 * observes immutable local history in a new process; it never manually drives recovery.
 * The visible synthetic event and all production evidence remain retained.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSystemTitleWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemEditorTitleRunsThroughAutomaticWorkerAndRemainsClosedAfterRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemTitleWorker") == "true")
        val phase = args.getString("titleWorkerPhase").orEmpty()
        val token = args.getString("createFixtureToken").orEmpty()
        val accountHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit RUN, RECOVER, REPLAY or DIAG and enrolled identity are required", phase in setOf("RUN", "RECOVER", "REPLAY", "DIAG") &&
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let { it.authenticated && it.selections.isNotEmpty() && (phase == "DIAG" || !it.busy) } }
        val automaticSource = args.getString("automaticTitleSource") == "true"
        val namespace = TitleJourneyNamespace.create(automaticSource, args.getString("systemTitleJourney") == "true",
            args.getString("titleJourneyLabel"))
        val requestedCalendarName = args.getString("titleSourceCalendarName").orEmpty()
        assertTrue("Automatic source requires the dedicated QA calendar name", !automaticSource ||
            requestedCalendarName == "Calendeer QA Automatic Worker")
        val source = app.getSharedPreferences(namespace.workerSourcePreferences, Context.MODE_PRIVATE)
        assertTrue("Require the existing qualified service fixture", source.getString("token", null) == token &&
            source.getString("account_hash", null) == accountHash && source.getInt("content_profile", -1) == 1 &&
            source.getString("journey_label", null) == namespace.label)
        fun sourceValue(key: String) = checkNotNull(source.getString(key, null)) { "Missing enrolled source field" }
        val target = CalendarScope(AccountId(sourceValue("account")), CalendarId(sourceValue("calendar")), source.getLong("generation", -1))
        val event = EventId(sourceValue("event"))
        val originalContent = EventContent(sourceValue("changed_title"),
            EventTime.Timed(Instant.parse(sourceValue("start")), Instant.parse(sourceValue("end")), ZoneOffset.UTC),
            sourceValue("description"), sourceValue("location"), details = EventDetails())
        val editTitle = "Calendeer QA automatic title $token"
        val expectedContent = originalContent.copy(title = editTitle)
        assertTrue("The source must be an ordinary initial-generation synthetic fixture", target.generation == 0L &&
            originalContent.title == "Calendeer QA — guarded UPDATE $token")
        assertTrue("Authorize the exact retained account before mutation",
            ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.any { it.scope == target && it.enabled && it.available &&
                (!automaticSource || it.summary.title == requestedCalendarName) } }
        assertTrue("The enrolled calendar must be accessible", accessible())
        if (automaticSource) {
            assertTrue("Require the exact frozen automatic source calendar", source.getString("source_calendar_name", null) == requestedCalendarName &&
                app.imports.state.value.selections.count { it.enabled && it.available && it.summary.title == requestedCalendarName } == 1)
            val sourceAttempt = MutationId(sourceValue("attempt_id"))
            TitleUpdateAttemptStore(app, namespace.sourceTitleDatabase).use { journal ->
                val record = journal.read(target, sourceAttempt)
                val result = journal.readVerified(target, sourceAttempt)
                val completion = journal.readCompletion(target, sourceAttempt)
                assertTrue("The independent guarded API journey must already be durably completed", record != null && result != null &&
                    completion != null && record.scope == target && record.id == sourceAttempt &&
                    record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                    result.record == record.copy(protectedEvidence = null) && result.event.eventId == event &&
                    result.event.content == originalContent && completion.result == result && completion.receipt.matches(result))
            }
        }
        if (phase == "DIAG") {
            diagnostics(app, target, event, originalContent)
            titleDiagnostics(app, target, event, originalContent, expectedContent, token, accountHash, automaticSource, namespace)
            return@runBlocking // No scheduler, mode, editor or coordinator mutation in this path.
        }
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permissions are required", projection.hasPermissions())
        projection.admitOperation = ::accessible
        val prefs = app.getSharedPreferences(namespace.workerPreferences, Context.MODE_PRIVATE)
        val manager = WorkManager.getInstance(app)
        fun networkWork() = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get()
        var periodicRunningObserved = false
        fun periodicWork(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().also { work ->
            if (work.any { it.state == WorkInfo.State.RUNNING }) periodicRunningObserved = true
        }
        suspend fun idleQueues() = withTimeout(240_000) {
            var nextReport = 0L
            while (true) {
                assertTrue("Keep the original accessible session while waiting", accessible())
                val local = manager.getWorkInfosForUniqueWork(LOCAL_WORK).get()
                val periodic = periodicWork()
                if (networkWork().all { it.state.isFinished } && local.all { it.state.isFinished } &&
                    periodic.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                    !app.imports.state.value.busy && !app.systemCreates.state.value.busy) break
                if (System.nanoTime() >= nextReport) {
                    diagnostics(app, target, event, originalContent)
                    nextReport = System.nanoTime() + 30_000_000_000L
                }
                delay(500)
            }
        }
        suspend fun baseline() = ImportStore(app).use { it.projectedBaseline(target, event) }
        suspend fun noLocalOwner() = ImportStore(app).use { reader ->
            reader.localEdit(target, event) == null && reader.localDelete(target, event) == null &&
                reader.localEditPreparation(target, event) == null && reader.capturedHandoff(target, event) == null
        }
        fun selection() = app.imports.state.value.selections.single { it.scope == target }
        fun modes() = SystemCreateWorkStore(app).use { it.modeEnabled(target) to it.titlesEnabled(target) }
        suspend fun restoreModes() {
            val previousCreate = prefs.getBoolean("previous_create", false)
            val previousTitles = prefs.getBoolean("previous_titles", false)
            // Disabling CREATE clears its dependent title option; restore in dependency order.
            val current = selection()
            compose.runOnUiThread {
                app.systemCreates.setEnabled(current, previousCreate)
                app.systemCreates.setTitlesEnabled(current, previousTitles)
            }
            assertTrue("Restore the exact original mode flags", modes() == (previousCreate to previousTitles))
            status("system_title_modes_restored")
        }

        var restore = false
        try {
            if (phase == "RUN") {
                val resumeBeforeEditor = prefs.contains("token")
                if (resumeBeforeEditor) {
                    assertTrue("Only an unchanged enrolled pre-editor run can resume", prefs.getString("token", null) == token &&
                        prefs.getString("journey_label", null) == namespace.label &&
                        prefs.getString("account_hash", null) == accountHash && prefs.getBoolean("automatic_source", false) == automaticSource &&
                        prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
                        prefs.getLong("generation", -1) == target.generation && prefs.getString("event", null) == event.value &&
                        prefs.getString("edit_title", null) == editTitle && !prefs.getBoolean("editor_requested", false) &&
                        !prefs.getBoolean("worker_verified", false) &&
                        modes() == (prefs.getBoolean("previous_create", false) to prefs.getBoolean("previous_titles", false)))
                    val retainedBase = baseline()
                    assertTrue("Resume requires the exact original baseline", retainedBase != null && retainedBase.content == originalContent &&
                        retainedBase.revision.value == prefs.getString("base_revision", null) && noLocalOwner())
                }
                assertTrue("No production title history may already own this event", historyIds(app, target, event).isEmpty())
                TitleUpdateAttemptStore(app).use {
                    assertTrue("No bare production reservation may own this event", it.readPreparationReservation(target, event) == null)
                }
                assertScopeQuiescent(app, projection, target)
                // Only this pre-edit phase may request a regular import. It ensures the source
                // event exists in the production mirror; it does not prepare an outgoing edit.
                app.imports.requestSync()
                var original: RemoteChange.Upsert? = null
                withTimeout(240_000) {
                    while (true) {
                        val candidate = baseline()
                        if (candidate != null && candidate.content == originalContent && noLocalOwner() &&
                            projection.inspectOwnedEvent(target, event, candidate.revision) != null) {
                            original = candidate; break
                        }
                        assertTrue("Keep the original accessible account during import", accessible())
                        delay(500)
                    }
                }
                idleQueues()
                val base = checkNotNull(original)
                assertTrue("Require an unchanged ordinary timed known-empty base", baseline() == base &&
                    base.content == originalContent && noLocalOwner())
                val owned = checkNotNull(projection.inspectOwnedEvent(target, event, base.revision)) { "Missing owned production event" }
                val time = base.content.time as EventTime.Timed
                val before = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                assertTrue("The enrolled row must start clean and present", before[Events.DIRTY] == "0" &&
                    before[Events.DELETED] == "0" && before[Events.TITLE] == originalContent.title && ordinary(before))
                val previous = modes()
                assertScopeQuiescent(app, projection, target)
                if (resumeBeforeEditor) {
                    assertTrue("A pre-editor retry cannot replace the frozen native row or event", owned.eventRow == prefs.getLong("row", -1) &&
                        owned.calendarRow == prefs.getLong("native_calendar", -1) && base.revision.value == prefs.getString("base_revision", null) &&
                        time.start.toEpochMilli() == prefs.getLong("start", -1) && time.end.toEpochMilli() == prefs.getLong("end", -1))
                    status("system_title_pre_editor_resume")
                } else assertTrue("Freeze fixture and original flags before enabling either mode", prefs.edit()
                    .putString("token", token).putString("account_hash", accountHash)
                    .putString("journey_label", namespace.label)
                    .putBoolean("automatic_source", automaticSource)
                    .putString("account", target.account.value).putString("calendar", target.calendar.value)
                    .putLong("generation", target.generation).putString("event", event.value)
                    .putString("base_revision", base.revision.value).putString("edit_title", editTitle)
                    .putLong("row", owned.eventRow).putLong("native_calendar", owned.calendarRow)
                    .putLong("start", time.start.toEpochMilli()).putLong("end", time.end.toEpochMilli())
                    .putBoolean("previous_create", previous.first).putBoolean("previous_titles", previous.second).commit())
                restore = true
                val selected = selection()
                compose.runOnUiThread {
                    app.systemCreates.setEnabled(selected, true)
                    app.systemCreates.setTitlesEnabled(selected, true)
                }
                assertTrue("Configure only the existing calendar access policy", app.imports.configureSystemCalendar(target))
                assertTrue("Both explicit production modes must be enabled", modes() == (true to true))
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                assertTrue("No outgoing work may precede the external edit", historyIds(app, target, event).isEmpty() &&
                    baseline() == base && noLocalOwner())
                val clean = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                assertTrue("Keep the unchanged original row before opening the editor", clean[Events.DIRTY] == "0" &&
                    clean[Events.TITLE] == originalContent.title && ordinary(clean))
                val beforeIds = networkWork().map { it.id.toString() }.toSet()
                val beforePeriodicIds = periodicWork().map { it.id.toString() }.toSet()
                assertTrue("Freeze the pre-edit queue and request before opening the editor", prefs.edit()
                    .putStringSet("previous_network_work", beforeIds).putBoolean("editor_requested", true)
                    .putStringSet("previous_periodic_work", beforePeriodicIds)
                    .putInt("run_pid", Process.myPid()).commit())
                app.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = ContentUris.withAppendedId(Events.CONTENT_URI, owned.eventRow)
                    setPackage(args.getString("editorPackage") ?: "foundation.e.calendar")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time.start.toEpochMilli())
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, time.end.toEpochMilli())
                })
                status("system_title_editor_launched")
                // Keep this process and its production observer alive while the external runner
                // uses the calendar's own Edit/Save UI. No request/run/recovery call follows.
                var completed: ObservedProof? = null
                withTimeout(300_000) {
                    while (true) {
                        assertTrue("Keep the original access and modes while the editor saves", accessible() && modes() == (true to true))
                        val proof = readProof(app, target, event)
                        periodicWork()
                        val newWork = networkWork().filter { it.id.toString() !in beforeIds }
                        if (proof != null && newWork.isNotEmpty() && newWork.all { it.state == WorkInfo.State.SUCCEEDED } &&
                            !app.imports.state.value.busy && !app.systemCreates.state.value.busy) {
                            completed = proof; break
                        }
                        assertTrue("The automatically queued network work must not fail", newWork.none {
                            it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
                        })
                        delay(500)
                    }
                }
                val proof = checkNotNull(completed)
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertTrue("Freeze the complete immutable production history for restart", prefs.edit()
                    .putString("attempt", proof.record.id.value).putString("history_digest", proof.digest)
                    .putString("intent", proof.record.preparation.intent.id.value).putBoolean("worker_verified", true).commit())
                status("system_title_worker_completed")
                status("system_title_same_row_acknowledged")
                status("system_title_production_identity_generated")
                status("system_title_automatic_pipeline_qualified")
                status("system_title_periodic_sampled")
                status("system_title_periodic_running_observed", periodicRunningObserved)
                restore = false // REPLAY retains the same modes until its final restoration.
            } else if (phase == "RECOVER") {
                assertTrue("RECOVER only resumes the exact previously edited, incomplete fixture",
                    prefs.getString("token", null) == token && prefs.getString("account_hash", null) == accountHash &&
                        prefs.getString("journey_label", null) == namespace.label &&
                        prefs.getBoolean("automatic_source", false) == automaticSource &&
                        prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
                        prefs.getLong("generation", -1) == target.generation && prefs.getString("event", null) == event.value &&
                        prefs.getString("edit_title", null) == editTitle && prefs.getBoolean("editor_requested", false) &&
                        !prefs.getBoolean("worker_verified", false) && prefs.contains("previous_create") &&
                        prefs.contains("previous_titles") && prefs.getInt("run_pid", -1) > 0 &&
                        !prefs.contains("history_digest") && !prefs.contains("attempt") && !prefs.contains("intent"))
                val previous = prefs.getBoolean("previous_create", false) to prefs.getBoolean("previous_titles", false)
                assertTrue("The failed journey must already have restored its original mode flags", modes() == previous)
                idleQueues()
                val canonicalId = checkNotNull(historyIds(app, target, event).singleOrNull()) { "Require one existing canonical title attempt" }
                val frozen = TitleUpdateAttemptStore(app).use { journal ->
                    val record = checkNotNull(journal.read(target, canonicalId)) { "Missing retained title attempt" }
                    val result = checkNotNull(journal.readVerified(target, canonicalId)) { "Missing retained verified proof" }
                    assertTrue("Recovery requires UNKNOWN with its exact protected proof, not a new send",
                        record.scope == target && record.id == canonicalId && record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
                            record.protectedEvidence != null && result.record == record.copy(protectedEvidence = null) &&
                            result.event.eventId == event && result.event.content == expectedContent &&
                            result.event.revision != record.preparation.intent.base.revision && journal.readCompletion(target, canonicalId) == null &&
                            journal.readPreparationReservation(target, event)?.matches(record) == true)
                    record.frozen() to result
                }
                val originalTime = originalContent.time as EventTime.Timed
                val row = prefs.getLong("row", -1); val calendar = prefs.getLong("native_calendar", -1)
                val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                assertTrue("Bind the original production intent, baseline and native enrollment",
                    frozen.first.id.value.matches(uuid) && frozen.first.preparation.intent.id.value.matches(uuid) &&
                        frozen.first.preparation.intent.base.eventId == event && frozen.first.preparation.intent.base.content == originalContent &&
                        frozen.first.preparation.intent.content == expectedContent && frozen.first.preparation.intent.state == LocalEditState.PAUSED &&
                        frozen.first.preparation.intent.base.revision.value == prefs.getString("base_revision", null) &&
                        frozen.first.preparation.timestamp.nano == 0 && row > 0 && calendar > 0 &&
                        prefs.getLong("start", -1) == originalTime.start.toEpochMilli() &&
                        prefs.getLong("end", -1) == originalTime.end.toEpochMilli())
                ImportStore(app).use { reader ->
                    val owner = checkNotNull(reader.localEdit(target, event)) { "Missing retained local owner" }
                    assertTrue("Only the exact retained owner and frozen capsule may recover",
                        owner.state == LocalEditState.CONFLICT &&
                            owner.copy(state = LocalEditState.PAUSED) == frozen.first.preparation.intent &&
                            reader.localEditPreparation(target, event) == frozen.first.preparation &&
                            reader.projectedBaseline(target, event) == frozen.first.preparation.intent.base &&
                            reader.transaction(target) { remote(event) } == frozen.second.event &&
                            reader.localDelete(target, event) == null && reader.capturedHandoff(target, event) == null &&
                            reader.titleUpdateAcknowledgement(target, event, canonicalId) == null)
                }
                val native = nativeValues(app, projection, target, event, row, calendar)
                val account = projection.accountFor(target.account)
                assertTrue("Require only the enrolled dirty ordinary title edit, still bound to the original revision",
                    native[Events.DIRTY] == "1" && native[Events.DELETED] == "0" && native[Events.ALL_DAY] == "0" && ordinary(native) &&
                        native[Events.SYNC_DATA1] == "calendar-bridge-projection-v1" &&
                        native[Events.SYNC_DATA2] == digest("revision-v1", frozen.first.preparation.intent.base.revision.value) &&
                        native[Events.TITLE] == expectedContent.title && native[Events.DESCRIPTION].orEmpty() == expectedContent.description &&
                        native[Events.EVENT_LOCATION].orEmpty() == expectedContent.location &&
                        native[Events.DTSTART] == originalTime.start.toEpochMilli().toString() &&
                        native[Events.DTEND] == originalTime.end.toEpochMilli().toString() &&
                        native[Events.ORGANIZER] == account.name &&
                        java.time.ZoneId.of(checkNotNull(native[Events.EVENT_TIMEZONE])).normalized() == originalTime.zone.normalized() &&
                        (native[Events.EVENT_END_TIMEZONE] == null ||
                            java.time.ZoneId.of(checkNotNull(native[Events.EVENT_END_TIMEZONE])).normalized() == originalTime.zone.normalized()) &&
                        listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION, Events.ORIGINAL_ID,
                            Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY, Events.EVENT_COLOR,
                            Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI).all { native[it] == null })
                for ((child, column) in listOf(CalendarContract.Reminders.CONTENT_URI to CalendarContract.Reminders.EVENT_ID,
                    CalendarContract.Attendees.CONTENT_URI to CalendarContract.Attendees.EVENT_ID,
                    CalendarContract.ExtendedProperties.CONTENT_URI to CalendarContract.ExtendedProperties.EVENT_ID)) {
                    val uri = child.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
                    app.contentResolver.query(uri, arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use {
                        assertTrue("Recovery must not admit additional native details", it.count == 0)
                    }
                }
                assertScopeQuiescent(app, projection, target, recovering = frozen.first, recoveringRow = row)
                assertTrue("Keep original flags through the complete recovery preflight", modes() == previous)
                val beforeIds = networkWork().map { it.id.toString() }.toSet()
                restore = true
                compose.runOnUiThread {
                    app.systemCreates.setEnabled(selection(), true)
                    app.systemCreates.setTitlesEnabled(selection(), true)
                }
                assertTrue("Enable only the two explicit production options", modes() == (true to true))
                status("system_title_durable_recovery_started")
                var completed: ObservedProof? = null
                var nextRecoveryDiagnostic = 0L
                withTimeout(300_000) {
                    while (true) {
                        assertTrue("Keep the enrolled session and modes during automatic recovery", accessible() && modes() == (true to true))
                        if (System.nanoTime() >= nextRecoveryDiagnostic) {
                            diagnostics(app, target, event, originalContent)
                            titleDiagnostics(app, target, event, originalContent, expectedContent, token, accountHash, automaticSource, namespace)
                            nextRecoveryDiagnostic = System.nanoTime() + 30_000_000_000L
                        }
                        TitleUpdateAttemptStore(app).use { journal ->
                            assertTrue("Recovery must never replace the immutable UNKNOWN wire or verified result",
                                journal.read(target, canonicalId) == frozen.first && journal.readVerified(target, canonicalId) == frozen.second)
                        }
                        assertTrue("Recovery must not create another attempt", historyIds(app, target, event) == listOf(canonicalId))
                        val proof = readProof(app, target, event)
                        val newWork = networkWork().filter { it.id.toString() !in beforeIds }
                        periodicWork()
                        if (proof != null && newWork.isNotEmpty() && newWork.all { it.state == WorkInfo.State.SUCCEEDED } &&
                            !app.imports.state.value.busy && !app.systemCreates.state.value.busy) { completed = proof; break }
                        assertTrue("Automatically scheduled recovery must not fail", newWork.none {
                            it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
                        })
                        delay(500)
                    }
                }
                val proof = checkNotNull(completed)
                assertTrue("The same retained request and protected proof must complete", proof.record == frozen.first && proof.result == frozen.second)
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertTrue("Freeze the recovered immutable history for a later restart", prefs.edit()
                    .putString("attempt", proof.record.id.value).putString("history_digest", proof.digest)
                    .putString("intent", proof.record.preparation.intent.id.value).putBoolean("worker_verified", true)
                    .putBoolean("durable_recovery_verified", true).putInt("run_pid", Process.myPid()).commit())
                status("system_title_automatic_durable_proof_recovery")
                status("system_title_same_row_acknowledged")
                status("system_title_no_new_attempt")
                status("system_title_periodic_sampled")
                status("system_title_periodic_running_observed", periodicRunningObserved)
                restore = false // Successful recovery preserves the modes only until REPLAY restores them.
            } else {
                assertTrue("REPLAY requires this completed enrolled worker journey", prefs.getString("token", null) == token &&
                    prefs.getString("journey_label", null) == namespace.label &&
                    prefs.getString("account_hash", null) == accountHash && prefs.getBoolean("worker_verified", false) &&
                    prefs.getBoolean("automatic_source", false) == automaticSource &&
                    prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
                    prefs.getLong("generation", -1) == target.generation && prefs.getString("event", null) == event.value &&
                    prefs.getString("edit_title", null) == editTitle)
                restore = true
                assertTrue("REPLAY must use a newly started process", prefs.getInt("run_pid", -1) > 0 &&
                    prefs.getInt("run_pid", -1) != Process.myPid())
                assertTrue("Keep the original modes until restart verification finishes", modes() == (true to true))
                // Startup may schedule normal production work. The test only waits and reads;
                // it does not request a replay or claim that the whole app is network-free.
                idleQueues()
                val proof = checkNotNull(readProof(app, target, event)) { "Missing completed production history" }
                assertTrue("UNKNOWN, protected evidence and full completion must remain unchanged",
                    proof.record.id.value == prefs.getString("attempt", null) && proof.digest == prefs.getString("history_digest", null) &&
                        proof.record.preparation.intent.id.value == prefs.getString("intent", null))
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertTrue("Restart must not create another title attempt", historyIds(app, target, event) == listOf(proof.record.id))
                status("system_title_immutable_restart")
                status("system_title_no_new_attempt")
            }
        } finally {
            if (restore) restoreModes()
            projection.admitOperation = { false }
        }
    }

    private class ObservedProof(val record: TitleUpdateAttemptRecord, val result: TitleUpdateVerifiedResult,
        val completion: TitleUpdateCompletion, val digest: String)

    /** Read-only DIAG and RECOVER observations. Separate helpers and raw Provider queries never borrow the live coordinator's
     * leases, execute Provider assertion batches, call the service, or advance recovery.
     * A failed read is invalid, not evidence that the corresponding durable stage is absent.
     */
    private suspend fun titleDiagnostics(app: BridgeApplication, scope: CalendarScope, event: EventId,
        original: EventContent, requested: EventContent, token: String, accountHash: String, automaticSource: Boolean,
        namespace: TitleJourneyNamespace) {
        val prefs = app.getSharedPreferences(namespace.workerPreferences, Context.MODE_PRIVATE)
        assertTrue("Detailed diagnostics require the exact enrolled worker fixture", prefs.getString("token", null) == token &&
            prefs.getString("journey_label", null) == namespace.label &&
            prefs.getString("account_hash", null) == accountHash && prefs.getBoolean("automatic_source", false) == automaticSource &&
            prefs.getString("account", null) == scope.account.value && prefs.getString("calendar", null) == scope.calendar.value &&
            prefs.getLong("generation", -1) == scope.generation && prefs.getString("event", null) == event.value &&
            prefs.getString("edit_title", null) == requested.title)
        val output = Bundle()
        output.putBoolean("diag_editor_requested", prefs.getBoolean("editor_requested", false))
        output.putBoolean("diag_worker_verified", prefs.getBoolean("worker_verified", false))
        SystemCreateWorkStore(app).use {
            output.putBoolean("diag_create_mode", it.modeEnabled(scope))
            output.putBoolean("diag_title_mode", it.titlesEnabled(scope))
        }
        var record: TitleUpdateAttemptRecord? = null
        var result: TitleUpdateVerifiedResult? = null
        var completion: TitleUpdateCompletion? = null
        // Constant getter on the shared facade; no account/session/network operation is invoked.
        val liveFormat = diagnosticRead(output, "live_remote_format") { ProtonAuthentication.events(app).format }
        val recoveryCursors = AndroidTitleRecoveryCursorStore(app)
        val recoveryCursor = diagnosticRead(output, "recovery_cursor") { recoveryCursors.read(scope) }
        for ((name, cursors) in listOf("recovery" to recoveryCursors,
            "owner" to AndroidTitleRecoveryCursorStore(app, "calendar-title-owner-cursors"),
            "inspection" to AndroidTitleRecoveryCursorStore(app, "calendar-title-inspection-cursors"))) {
            diagnosticRead(output, "${name}_exhaustion") {
                output.putBoolean("diag_${name}_exhausted", cursors.exhausted(scope)); true
            }
            if (name != "recovery") diagnosticRead(output, "${name}_cursor") { cursors.read(scope) }
        }
        TitleUpdateAttemptStore(app).use { journal ->
            diagnosticRead(output, "work_first_page") {
                val page = journal.page(TitleUpdateWorkQuery(scope, limit = 25))
                output.putInt("diag_work_first_page_count", page.entries.size)
                output.putBoolean("diag_work_first_page_next", page.next != null)
                output.putBoolean("diag_work_first_page_event", page.entries.any { it.eventId == event })
                for (state in TitleUpdateAttemptState.entries)
                    output.putInt("diag_work_first_page_${state.name.lowercase()}", page.entries.count { it.state == state })
                true
            }
            if (output.getBoolean("diag_recovery_cursor_valid")) diagnosticRead(output, "work_cursor_page") {
                val page = journal.page(TitleUpdateWorkQuery(scope, limit = 25, cursor = recoveryCursor))
                output.putInt("diag_work_cursor_page_count", page.entries.size)
                output.putBoolean("diag_work_cursor_page_event", page.entries.any { it.eventId == event })
                output.putBoolean("diag_work_cursor_page_next", page.next != null)
                true
            }
            val ids = diagnosticRead(output, "attempt_metadata") {
                journal.readableDatabase.rawQuery("SELECT mutation,state,evidence IS NOT NULL,result IS NOT NULL,completion IS NOT NULL " +
                    "FROM title_update_attempts WHERE account=? AND calendar=? AND generation=? AND event=? LIMIT 3",
                    arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), event.value)).use { rows ->
                    output.putInt("diag_attempt_count_bounded", rows.count)
                    check(rows.count <= 1)
                    if (!rows.moveToFirst()) emptyList() else {
                        val id = rows.getString(0); check(id.length in 1..4096)
                        val state = TitleUpdateAttemptState.valueOf(rows.getString(1))
                        output.putString("diag_attempt_state", state.name)
                        output.putBoolean("diag_attempt_evidence_column", rows.getInt(2) != 0)
                        output.putBoolean("diag_attempt_result_column", rows.getInt(3) != 0)
                        output.putBoolean("diag_attempt_completion_column", rows.getInt(4) != 0)
                        listOf(MutationId(id))
                    }
                }
            }
            val reservation = diagnosticRead(output, "reservation") { journal.readPreparationReservation(scope, event) }
            ids?.singleOrNull()?.let { id ->
                record = diagnosticRead(output, "attempt_decode") { journal.read(scope, id) }
                result = diagnosticRead(output, "result_decode") { journal.readVerified(scope, id) }
                completion = diagnosticRead(output, "completion_decode") { journal.readCompletion(scope, id) }
            }
            record?.let { work ->
                output.putBoolean("diag_attempt_binding", work.scope == scope && work.preparation.intent.base.eventId == event)
                output.putBoolean("diag_attempt_intent_format_live", work.preparation.intent.format == liveFormat)
                output.putBoolean("diag_reservation_matches_attempt", reservation?.matches(work) == true)
                output.putBoolean("diag_attempt_requested_content", work.preparation.intent.content == requested)
                result?.let { proof ->
                    output.putBoolean("diag_result_matches_attempt", proof.record == work.copy(protectedEvidence = null))
                    output.putBoolean("diag_result_requested_content", proof.event.eventId == event && proof.event.content == requested)
                    output.putBoolean("diag_completion_matches_result", completion?.let { it.result == proof && it.receipt.matches(proof) } == true)
                }
            }
        }
        var owner: LocalEditIntent? = null
        var base: RemoteChange.Upsert? = null
        var projectionReceipt: ProjectionReceipt? = null
        ImportStore(app).use { store ->
            store.admitTransaction = { it == scope }
            val identitySql = "scope=(SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?) AND event=?"
            val identityArgs = arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), event.value)
            for (table in listOf("projection_receipts", "captured_create_bases", "captured_create_acknowledgements",
                "captured_create_handoffs", "deferred_events", "projection_bases")) {
                diagnosticRead(output, "sql_${table}") {
                    store.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE $identitySql LIMIT 2", identityArgs).use {
                        output.putInt("diag_sql_${table}_count_bounded", it.count)
                        check(it.count <= 1); true
                    }
                }
            }
            diagnosticRead(output, "sql_projection_representation") {
                store.readableDatabase.rawQuery("SELECT representation IS NULL FROM projection_bases WHERE $identitySql LIMIT 2",
                    identityArgs).use {
                    check(it.count <= 1)
                    if (it.moveToFirst()) {
                        output.putBoolean("diag_sql_projection_representation_null", it.getInt(0) == 1); true
                    } else null
                }
            }
            val cachedRemote = diagnosticRead(output, "cached_remote") { store.transaction(scope) { remote(event) } }
            output.putBoolean("diag_cached_remote_exact_verified", result != null && cachedRemote == result?.event)
            output.putBoolean("diag_cached_remote_exact_original", cachedRemote is RemoteChange.Upsert &&
                cachedRemote.content == original && cachedRemote.revision.value == prefs.getString("base_revision", null))
            val checkpoint = diagnosticRead(output, "checkpoint") { store.transaction(scope) { this.checkpoint } }
            output.putBoolean("diag_checkpoint_format_live", checkpoint != null && checkpoint.format == liveFormat)
            output.putBoolean("diag_checkpoint_complete", checkpoint?.traversalComplete == true)
            output.putBoolean("diag_checkpoint_format_attempt_intent", checkpoint != null && checkpoint.format == record?.preparation?.intent?.format)
            base = diagnosticRead(output, "baseline_decode") { store.projectedBaseline(scope, event) }
            projectionReceipt = diagnosticRead(output, "projection_receipt_decode") { store.projectionReceipt(scope, event) }
            projectionReceipt?.let { receipt ->
                output.putBoolean("diag_projection_receipt_binding", receipt.scope == scope && receipt.change.eventId == event)
                output.putBoolean("diag_projection_receipt_matches_original_base", record != null && receipt.change == record?.preparation?.intent?.base)
                output.putBoolean("diag_projection_receipt_matches_verified_result", result != null && receipt.change == result?.event)
                output.putBoolean("diag_projection_receipt_matches_current_base", base != null && receipt.change == base)
                output.putBoolean("diag_projection_receipt_previous_base_matches_intent", record != null && receipt.previousBase == record?.preparation?.intent?.base)
                output.putBoolean("diag_projection_receipt_previous_base_absent", receipt.previousBase == null)
                output.putBoolean("diag_projection_receipt_format_matches_intent", record != null && receipt.format == record?.preparation?.intent?.format)
                output.putBoolean("diag_projection_receipt_representations_absent", receipt.representation == null && receipt.previousRepresentation == null)
            }
            output.putBoolean("diag_cached_remote_exact_baseline", cachedRemote != null && cachedRemote == base)
            output.putBoolean("diag_baseline_exact_frozen_original", base?.let {
                it.eventId == event && it.content == original && it.revision.value == prefs.getString("base_revision", null)
            } == true)
            owner = diagnosticRead(output, "local_owner") { store.localEdit(scope, event) }
            diagnosticRead(output, "local_delete") { store.localDelete(scope, event) }
            diagnosticRead(output, "handoff") { store.capturedHandoff(scope, event) }
            val capsule = diagnosticRead(output, "capsule") { store.localEditPreparation(scope, event) }
            output.putBoolean("diag_capsule_exact_attempt_preparation", record != null && capsule == record?.preparation)
            output.putBoolean("diag_owner_paused_snapshot_exact_attempt", owner != null && record != null &&
                owner?.copy(state = LocalEditState.PAUSED) == record?.preparation?.intent)
            owner?.let { intent ->
                output.putString("diag_owner_state", intent.state.name)
                output.putBoolean("diag_owner_format_live", intent.format == liveFormat)
                output.putBoolean("diag_owner_format_checkpoint", intent.format == checkpoint?.format)
                output.putBoolean("diag_owner_scope", intent.scope == scope && intent.base.eventId == event)
                output.putBoolean("diag_owner_base_matches_baseline", intent.base == base)
                output.putBoolean("diag_owner_base_original_content", intent.base.content == original)
                output.putBoolean("diag_owner_base_revision_frozen", intent.base.revision.value == prefs.getString("base_revision", null))
                output.putBoolean("diag_owner_requested_content", intent.content == requested)
                output.putBoolean("diag_owner_timed", intent.base.content.time is EventTime.Timed)
                output.putBoolean("diag_owner_known_empty", intent.base.content.details == EventDetails())
                output.putBoolean("diag_owner_no_recurrence", intent.base.content.recurrence == null)
                output.putBoolean("diag_owner_no_color", intent.base.content.colorRgb == null)
                output.putBoolean("diag_owner_title_only", intent.content != intent.base.content &&
                    intent.content == intent.base.content.copy(title = intent.content.title))
                output.putBoolean("diag_owner_time_exact_base", intent.content.time == intent.base.content.time)
                output.putBoolean("diag_owner_description_exact_base", intent.content.description == intent.base.content.description)
                output.putBoolean("diag_owner_location_exact_base", intent.content.location == intent.base.content.location)
                output.putBoolean("diag_owner_details_exact_base", intent.content.details == intent.base.content.details)
                val ownerTime = intent.content.time as? EventTime.Timed
                val baseTime = intent.base.content.time as? EventTime.Timed
                output.putBoolean("diag_owner_time_instants_base", ownerTime != null && baseTime != null &&
                    ownerTime.start == baseTime.start && ownerTime.end == baseTime.end)
                output.putBoolean("diag_owner_zone_exact_base", ownerTime != null && ownerTime.zone == baseTime?.zone)
                output.putBoolean("diag_owner_zone_normalized_base", ownerTime != null &&
                    ownerTime.zone.normalized() == baseTime?.zone?.normalized())
                output.putBoolean("diag_capsule_matches_owner", capsule?.intent == intent)
            }
            record?.let { work ->
                val ack = diagnosticRead(output, "ack") { store.titleUpdateAcknowledgement(scope, event, work.id) }
                ack?.let {
                    output.putString("diag_ack_state", it.state.name)
                    result?.let { proof -> output.putBoolean("diag_ack_matches_result", ack.receipt.matches(proof)) }
                }
            }
        }
        val projection = AndroidCalendarProjection(app)
        val account = projection.accountFor(scope.account)
        fun uri(value: android.net.Uri) = value.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        val row = prefs.getLong("row", -1); val calendar = prefs.getLong("native_calendar", -1)
        val native = diagnosticRead(output, "native_row") {
            check(row > 0 && calendar > 0)
            val identity = projection.eventIdentityKey(scope, event)
            app.contentResolver.query(uri(Events.CONTENT_URI), null,
                "${Events.CALENDAR_ID}=? AND (${Events._ID}=? OR ${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
                arrayOf(calendar.toString(), row.toString(), identity, identity), null)!!.use { rows ->
                output.putInt("diag_native_row_count", rows.count); check(rows.count <= 1)
                if (!rows.moveToFirst()) null else rows.columnNames.indices.associate {
                    rows.getColumnName(it) to if (rows.isNull(it)) null else rows.getString(it)
                }.also { values ->
                    output.putBoolean("diag_native_same_identity", values[Events._ID] == row.toString() &&
                        values[Events.CALENDAR_ID] == calendar.toString() && values[Events._SYNC_ID] == identity && values[Events.SYNC_DATA4] == identity)
                }
            }
        }
        native?.let { values ->
            output.putBoolean("diag_native_dirty", values[Events.DIRTY] == "1")
            output.putBoolean("diag_native_deleted", values[Events.DELETED] == "1")
            output.putBoolean("diag_native_requested_title", values[Events.TITLE] == requested.title)
            output.putBoolean("diag_native_original_title", values[Events.TITLE] == original.title)
            output.putBoolean("diag_native_description_match", values[Events.DESCRIPTION].orEmpty() == requested.description)
            output.putBoolean("diag_native_location_match", values[Events.EVENT_LOCATION].orEmpty() == requested.location)
            output.putBoolean("diag_native_times_unchanged", values[Events.DTSTART] == prefs.getLong("start", -1).toString() &&
                values[Events.DTEND] == prefs.getLong("end", -1).toString() && values[Events.ALL_DAY] == "0")
            output.putBoolean("diag_native_origins_absent", ordinary(values))
            output.putBoolean("diag_native_reserved_8_9_10_absent", listOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
                .all { values[it] == null })
            val marker = values[Events.SYNC_DATA3]
            output.putBoolean("diag_native_marker_absent", marker == null)
            output.putBoolean("diag_native_marker_ordinary", marker == null ||
                marker.matches(Regex("calendeer-projection-receipt-v1:[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
            output.putBoolean("diag_native_marker_matches_projection_receipt", projectionReceipt?.let {
                marker == "calendeer-projection-receipt-v${it.protocol}:${it.nonce}"
            } == true)
            output.putBoolean("diag_native_base_revision", base?.let { values[Events.SYNC_DATA2] == digest("revision-v1", it.revision.value) } == true)
            output.putBoolean("diag_native_no_recurrence", listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
                Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY).all { values[it] == null })
            output.putBoolean("diag_native_no_color", values[Events.EVENT_COLOR] == null && values[Events.EVENT_COLOR_KEY] == null)
            output.putBoolean("diag_native_no_custom_app", values[Events.CUSTOM_APP_PACKAGE] == null && values[Events.CUSTOM_APP_URI] == null)
            output.putBoolean("diag_native_personal_organizer", values[Events.ORGANIZER] == account.name)
            diagnosticRead(output, "native_zones") {
                val zone = java.time.ZoneId.of(checkNotNull(values[Events.EVENT_TIMEZONE]))
                val endZone = values[Events.EVENT_END_TIMEZONE]?.let(java.time.ZoneId::of)
                val ownerTime = owner?.content?.time as? EventTime.Timed
                val baseTime = base?.content?.time as? EventTime.Timed
                output.putBoolean("diag_native_zone_exact_owner", zone == ownerTime?.zone)
                output.putBoolean("diag_native_zone_normalized_owner", zone.normalized() == ownerTime?.zone?.normalized())
                output.putBoolean("diag_native_zone_exact_base", zone == baseTime?.zone)
                output.putBoolean("diag_native_zone_normalized_base", zone.normalized() == baseTime?.zone?.normalized())
                output.putBoolean("diag_native_end_zone_present", endZone != null)
                output.putBoolean("diag_native_end_zone_exact_start", endZone == zone)
                output.putBoolean("diag_native_represented_fields_exact_owner", owner?.let { intent ->
                    ownerTime != null && values[Events.TITLE].orEmpty() == intent.content.title &&
                        values[Events.DESCRIPTION].orEmpty() == intent.content.description &&
                        values[Events.EVENT_LOCATION].orEmpty() == intent.content.location && values[Events.ALL_DAY] == "0" &&
                        values[Events.DTSTART] == ownerTime.start.toEpochMilli().toString() &&
                        values[Events.DTEND] == ownerTime.end.toEpochMilli().toString() && zone == ownerTime.zone &&
                        values[Events.EVENT_COLOR] == intent.content.colorRgb?.or(0xFF000000.toInt())?.toString()
                } == true)
                true
            }
            for (column in listOf(Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
                Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)) {
                output.putBoolean("diag_native_${column}_absent", values[column] == null)
                output.putBoolean("diag_native_${column}_zero", values[column] == "0")
                output.putBoolean("diag_native_${column}_one", values[column] == "1")
            }
            val emptyFlags = listOf(Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
                Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
            val personalOwner = base?.content?.let { it.recurrence == null && it.details == EventDetails() } == true &&
                values[Events.ORGANIZER] == account.name && values[Events.IS_ORGANIZER] == "1" &&
                emptyFlags.all { values[it] == "0" }
            output.putBoolean("diag_native_personal_owner_profile", personalOwner)
            output.putBoolean("diag_native_empty_flags_accepted", emptyFlags.all { values[it] == "0" } &&
                (values[Events.IS_ORGANIZER] == "0" || personalOwner))
            output.putBoolean("diag_native_confirmed", values[Events.STATUS] == Events.STATUS_CONFIRMED.toString())
            output.putBoolean("diag_native_busy", values[Events.AVAILABILITY] == Events.AVAILABILITY_BUSY.toString())
            for ((name, child, column) in listOf(Triple("reminders", CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID),
                Triple("attendees", CalendarContract.Attendees.CONTENT_URI, CalendarContract.Attendees.EVENT_ID),
                Triple("extended", CalendarContract.ExtendedProperties.CONTENT_URI, CalendarContract.ExtendedProperties.EVENT_ID))) {
                diagnosticRead(output, "native_${name}") {
                    app.contentResolver.query(uri(child), arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use {
                        output.putInt("diag_native_${name}_count", it.count); true
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
    }

    private suspend fun <T> diagnosticRead(output: Bundle, name: String, block: suspend () -> T?): T? = try {
        val value = block()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        output.putBoolean("diag_${name}_valid", true)
        output.putBoolean("diag_${name}_present", value != null)
        value
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        output.putBoolean("diag_${name}_valid", false)
        null
    }

    /** Sample only closed states, counts and field equality; no IDs, event text or exception messages. */
    private suspend fun diagnostics(app: BridgeApplication, scope: CalendarScope, event: EventId, expected: EventContent) {
        val output = Bundle()
        val manager = WorkManager.getInstance(app)
        for ((name, unique) in listOf("local" to LOCAL_WORK, "network" to NETWORK_WORK, "periodic" to PERIODIC_WORK)) {
            val work = manager.getWorkInfosForUniqueWork(unique).get()
            output.putInt("diag_${name}_count", work.size)
            for (state in WorkInfo.State.values()) output.putInt("diag_${name}_${state.name.lowercase()}", work.count { it.state == state })
            output.putInt("diag_${name}_attempt_max", work.maxOfOrNull { it.runAttemptCount } ?: 0)
            output.putInt("diag_${name}_retry_enqueued", work.count { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 })
        }
        val incoming = app.imports.state.value
        val selection = incoming.selections.singleOrNull { it.scope == scope }
        val outgoing = app.systemCreates.state.value
        output.putBoolean("diag_imports_busy", incoming.busy)
        output.putBoolean("diag_source_selected", selection?.enabled == true)
        output.putBoolean("diag_source_available", selection?.available == true)
        val statuses = setOf("PAUSED", "AUTH", "CAPACITY", "CREATE_PENDING", "ERROR", "HISTORY_GAP", "IMPORTED", "INCOMPLETE",
            "LOCAL_KEYS", "NETWORK", "PARTIAL", "PENDING", "PERMISSION", "RUNNING", "SERIES_PENDING", "UNSUPPORTED", "UNAVAILABLE")
        output.putString("diag_source_status", selection?.status?.takeIf { it in statuses } ?: "OTHER")
        output.putInt("diag_source_pending_projection", selection?.pendingProjectionCount ?: 0)
        output.putInt("diag_source_retained_changes", selection?.retainedLocalChangeCount ?: 0)
        output.putInt("diag_other_active_scopes", incoming.selections.count { it.scope != scope && it.enabled && it.available })
        output.putInt("diag_other_retry_scopes", incoming.selections.count {
            it.scope != scope && it.enabled && it.available && it.status in setOf("ERROR", "NETWORK", "AUTH", "PERMISSION", "LOCAL_KEYS")
        })
        output.putBoolean("diag_system_creates_busy", outgoing.busy)
        output.putBoolean("diag_system_creates_failed", outgoing.failed)
        output.putBoolean("diag_ui_source_create_enabled", scope in outgoing.enabled)
        output.putBoolean("diag_ui_source_titles_enabled", scope in outgoing.titlesEnabled)
        output.putInt("diag_system_creates_source_pending", outgoing.pending[scope] ?: 0)
        output.putInt("diag_system_creates_other_pending", outgoing.pending.filterKeys { it != scope }.values.sum())
        val base = ImportStore(app).use { it.projectedBaseline(scope, event) }
        val actual = base?.content
        output.putBoolean("diag_baseline_present", base != null)
        output.putBoolean("diag_content_match", actual == expected)
        output.putBoolean("diag_title_match", actual?.title == expected.title)
        output.putBoolean("diag_description_match", actual?.description == expected.description)
        output.putBoolean("diag_location_match", actual?.location == expected.location)
        output.putBoolean("diag_details_match", actual?.details == expected.details)
        output.putBoolean("diag_color_match", actual != null && actual.colorRgb == expected.colorRgb)
        output.putBoolean("diag_recurrence_match", actual != null && actual.recurrence == expected.recurrence)
        val actualTime = actual?.time as? EventTime.Timed
        val expectedTime = expected.time as EventTime.Timed
        output.putBoolean("diag_time_instants_match", actualTime?.start == expectedTime.start && actualTime?.end == expectedTime.end)
        output.putBoolean("diag_zone_exact", actualTime?.zone == expectedTime.zone)
        output.putBoolean("diag_zone_normalized", actualTime?.zone?.normalized() == expectedTime.zone.normalized())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output)
    }

    /** Conservative, read-only calendar-wide enrollment. Old unfinished QA is a refusal,
     * never permission to clear it. Repeat before mode activation and after its jobs settle.
     * These separate SQL/Provider reads are not a cross-store lock: the external runner must
     * leave every other event untouched throughout this explicitly enrolled journey.
     */
    private suspend fun assertScopeQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        recovering: TitleUpdateAttemptRecord? = null, recoveringRow: Long? = null) {
        require((recovering == null) == (recoveringRow == null))
        require(recovering == null || (recovering.scope == scope && checkNotNull(recoveringRow) > 0))
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
            empty(store.readableDatabase, "SELECT 1 FROM captured_create_handoffs WHERE $inCalendar AND (state!='RELEASED' OR waiting!=0) LIMIT 1",
                message = "The calendar retains an unfinished CREATE handoff")
        }
        val finishedCreates = SystemCreateWorkStore(app).use { store ->
            empty(store.readableDatabase, "SELECT 1 FROM system_creates WHERE account=? AND calendar=? AND status!='COMPLETE' LIMIT 1",
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

    /** Include closed history, which the unresolved page intentionally excludes. Metadata
     * only, scoped to the enrolled event across generations; no private value is logged.
     */
    private fun historyIds(app: Context, scope: CalendarScope, event: EventId): List<MutationId> =
        TitleUpdateAttemptStore(app).use { journal ->
            journal.readableDatabase.rawQuery("SELECT mutation FROM title_update_attempts WHERE account=? AND calendar=? AND event=? LIMIT 2",
                arrayOf(scope.account.value, scope.calendar.value, event.value)).use { cursor ->
                assertTrue("The fixture must have no duplicate production title history", cursor.count <= 1)
                buildList { while (cursor.moveToNext()) add(MutationId(cursor.getString(0))) }
            }
        }

    private suspend fun readProof(app: Context, scope: CalendarScope, event: EventId): ObservedProof? {
        val id = historyIds(app, scope, event).singleOrNull() ?: return null
        return TitleUpdateAttemptStore(app).use { journal ->
            val record = journal.read(scope, id) ?: return@use null
            val result = journal.readVerified(scope, id) ?: return@use null
            val completion = journal.readCompletion(scope, id) ?: return@use null
            val checksum = journal.readableDatabase.rawQuery("SELECT * FROM title_update_attempts WHERE account=? AND calendar=? AND generation=? AND mutation=? AND event=?",
                arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), id.value, event.value)).use { cursor ->
                assertTrue("Require one complete scoped attempt", cursor.count == 1 && cursor.moveToFirst())
                digest(*cursor.columnNames.indices.flatMap { column -> listOf(cursor.getColumnName(column),
                    if (cursor.isNull(column)) "NULL" else "TEXT:" + cursor.getString(column)) }.toTypedArray())
            }
            ObservedProof(record, result, completion, checksum)
        }
    }

    private suspend fun verifyProof(app: Context, projection: AndroidCalendarProjection, prefs: SharedPreferences,
        scope: CalendarScope, event: EventId, original: EventContent, changed: EventContent, proof: ObservedProof) {
        val record = proof.record
        val intent = record.preparation.intent
        val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        assertTrue("The worker must generate and bind both production identities", record.id.value.matches(uuid) &&
            intent.id.value.matches(uuid) && record.scope == scope && intent.scope == scope && intent.base.eventId == event)
        assertTrue("Bind the exact submitted title-only content and original revision", intent.base.content == original &&
            intent.content == changed && intent.base.revision.value == prefs.getString("base_revision", null) &&
            intent.state == LocalEditState.PAUSED && record.preparation.timestamp.nano == 0)
        assertTrue("Keep the UNKNOWN request, protected response and complete verified result",
            record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                proof.result.record == record.copy(protectedEvidence = null) && proof.result.event.content == changed &&
                proof.result.event.eventId == event && proof.result.event.revision != intent.base.revision &&
                proof.completion.result == proof.result && proof.completion.receipt.handoff == null &&
                proof.completion.receipt.matches(proof.result))
        ImportStore(app).use { reader ->
            val ack = reader.titleUpdateAcknowledgement(scope, event, record.id)
            assertTrue("The production SQL baseline and owner must be fully restored",
                reader.projectedBaseline(scope, event) == proof.result.event && reader.localEdit(scope, event) == null &&
                    reader.localEditPreparation(scope, event) == null && reader.localDelete(scope, event) == null &&
                    reader.capturedHandoff(scope, event) == null && ack != null && ack.state == TitleUpdateAcknowledgementState.RESTORED &&
                    ack.receipt == proof.completion.receipt)
        }
        val row = prefs.getLong("row", -1); val calendar = prefs.getLong("native_calendar", -1)
        val owned = projection.inspectOwnedEvent(scope, event, proof.result.event.revision)
        assertTrue("Keep the same owned production row after acknowledgement", owned != null && owned.eventRow == row && owned.calendarRow == calendar)
        val native = nativeValues(app, projection, scope, event, row, calendar)
        assertTrue("The unchanged timed event must be clean with its new title", native[Events.TITLE] == changed.title &&
            native[Events.DIRTY] == "0" && native[Events.DELETED] == "0" && native[Events.ALL_DAY] == "0" && ordinary(native) &&
            native[Events.DTSTART] == prefs.getLong("start", -1).toString() && native[Events.DTEND] == prefs.getLong("end", -1).toString() &&
            native[Events.DESCRIPTION] == changed.description && native[Events.EVENT_LOCATION] == changed.location)
    }

    private fun nativeValues(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        event: EventId, row: Long, calendar: Long): Map<String, String?> {
        val identity = projection.eventIdentityKey(scope, event)
        return app.contentResolver.query(Events.CONTENT_URI, null,
            "${Events.CALENDAR_ID}=? AND (${Events._ID}=? OR ${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
            arrayOf(calendar.toString(), row.toString(), identity, identity), null)!!.use { cursor ->
            assertTrue("Require exactly one original owned event", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                if (cursor.isNull(column)) null else cursor.getString(column) }.also { value ->
                assertTrue("Preserve exact row and remote identity", value[Events._ID] == row.toString() &&
                    value[Events._SYNC_ID] == identity && value[Events.SYNC_DATA4] == identity)
            }
        }
    }

    private fun ordinary(values: Map<String, String?>) = listOf(Events.SYNC_DATA5, Events.SYNC_DATA6,
        Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).all { values[it] == null }

    private fun digest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun status(name: String, value: Boolean = true) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putBoolean(name, value) })

    private companion object {
        const val NETWORK_WORK = "calendar-import-network"
        const val LOCAL_WORK = "calendar-import-local"
        const val PERIODIC_WORK = "calendar-import-periodic"
    }
}

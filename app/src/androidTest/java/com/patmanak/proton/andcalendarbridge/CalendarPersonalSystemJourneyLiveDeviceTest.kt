// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentUris
import android.content.Intent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.content.SharedPreferences
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
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Opt-in real editor journey. ENROLL audits with all write modes disabled, before host activation.
 * PREPARE creates a fresh service event, imports it through
 * production, and opens only the explicitly enrolled editor. The host edits through that UI.
 * VERIFY observes production proof and ACK; REPLAY requires a new process and immutable history.
 * No personal send/recovery/ACK entry point or Provider mutation is called. ACTIVATE/RESTORE
 * own only the target's exact five-mode snapshots. Host must run RESTORE in finally.
 * Unknown CREATE is inspection-only. Never clear enrollment, reset or reuse a fixture token.
 * Retain the event. This qualifies neither an OEM nor exclusive observer/worker causality.
 */
@RunWith(AndroidJUnit4::class)
class CalendarPersonalSystemJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshCombinedEditThroughEnrolledSystemEditor() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarPersonalSystemJourney") == "true")
        val phase = args.getString("personalSystemPhase").orEmpty()
        val token = args.getString("personalFixtureToken").orEmpty()
        val accountHash = args.getString("personalAccountHash").orEmpty()
        val editorPackage = args.getString("editorPackage").orEmpty()
        assertTrue("Explicit phase, token, account and editor enrollment are required",
            phase in setOf("ENROLL", "ACTIVATE", "PREPARE", "VERIFY", "REPLAY", "RESTORE", "DIAGNOSE") && token.matches(Regex("[a-f0-9]{12}")) &&
                accountHash.matches(Regex("[a-f0-9]{64}")) &&
                editorPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let {
            it.authenticated && it.hasPermission && !it.busy && it.selections.isNotEmpty() } }
        val prefix = "calendar-personal-system-v2-$token"
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val name = "Calendeer QA Personal Worker"
        if (phase == "ENROLL") {
            val accounts = app.imports.state.value.selections.map { it.scope.account }.distinct()
            assertTrue("Authorize the retained test account before catalog refresh", accounts.size == 1 &&
                ProtonCreateSubmissionProbe.matchesAccount(app, accounts.single(), accountHash))
            compose.runOnUiThread { app.calendarCatalog.refresh() }
            compose.waitUntil(60_000) { app.imports.state.value.selections.count { it.summary.title == name && it.available } == 1 }
            val fresh = app.imports.state.value.selections.single { it.summary.title == name && it.available }
            assertTrue("Enable only the dedicated QA calendar on the authorized account", fresh.scope.account == accounts.single())
            fun noWrites() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
                state.selections.all { it.scope.account == accounts.single() } && SystemCreateWorkStore(app).use { modes ->
                    state.selections.all { selection -> !modes.modeEnabled(selection.scope) &&
                        !modes.titlesEnabled(selection.scope) && !modes.textsEnabled(selection.scope) &&
                        !modes.timesEnabled(selection.scope) && !modes.deletesEnabled(selection.scope) }
                } }
            assertTrue("No retained writing consent may precede mirror activation", noWrites())
            val auditProjection = AndroidCalendarProjection(app).apply { admitOperation = ::noWrites }
            try { SystemWorkerFixtureAudit.assertQuiescent(app, auditProjection, fresh.scope, allowPendingIncoming = true) }
            finally { auditProjection.admitOperation = { false } }
            // The host creates this dedicated test calendar beforehand. Leave its
            // read-only mirror enabled so the retained examples remain inspectable.
            if (!fresh.enabled) compose.runOnUiThread {
                assertTrue("Requalify no-write policy before waking production", noWrites())
                app.imports.setEnabled(fresh, true)
            }
            compose.waitUntil(60_000) { app.imports.state.value.selections.any { it.scope == fresh.scope && it.enabled && it.available } }
        }
        val enrolled = app.imports.state.value.selections.filter { it.enabled && it.available && it.summary.title == name }
        assertTrue("Enroll exactly one dedicated existing QA calendar", enrolled.size == 1)
        val target = enrolled.single().scope
        assertTrue("Authorize exact account before mutation", target.generation == 0L &&
            ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        fun flags(scope: CalendarScope) = SystemCreateWorkStore(app).use {
            listOf(it.modeEnabled(scope), it.titlesEnabled(scope), it.textsEnabled(scope), it.timesEnabled(scope), it.deletesEnabled(scope))
        }
        val scopes = app.imports.state.value.selections.map { it.scope }.toSet()
        assertTrue("Bound the enrolled scopes", scopes.size in 1..128 && scopes.all { it.account == target.account })
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.map { it.scope }.toSet() == scopes && state.selections.count {
                it.scope == target && it.enabled && it.available && it.summary.title == name } == 1 }
        fun policy() = accessible() && scopes.all { flags(it) == if (it == target && phase !in setOf("ENROLL", "ACTIVATE"))
            listOf(true, true, true, true, false) else listOf(false, false, false, false, false) }
        if (phase != "RESTORE") assertTrue("Keep exact scoped consent for the requested phase", policy())
        val projection = AndroidCalendarProjection(app).apply { admitOperation = ::policy }
        val factual: (CalendarScope) -> Boolean = { it == target && policy() }
        suspend fun auditBeforeActivation() {
            SystemWorkerFixtureAudit.assertQuiescent(app, projection, target, allowPendingIncoming = true)
            withTimeout(240_000) {
                val manager = WorkManager.getInstance(app)
                var stable = 0
                while (stable < 2) {
                    assertTrue("Keep policy while waiting for closed queues", policy())
                    val idle = listOf(NETWORK_WORK, LOCAL_WORK).all { name ->
                        manager.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }
                    } && manager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().none {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                        !app.imports.state.value.busy && !app.systemCreates.state.value.busy
                    stable = if (idle) stable + 1 else 0
                    if (stable < 2) delay(500)
                }
            }
            SystemWorkerFixtureAudit.assertQuiescent(app, projection, target)
            // The legacy audit predates personal-v2: explicitly reject all personal history.
            TitleUpdateAttemptStore(app).use { store ->
                store.readableDatabase.rawQuery("SELECT 1 FROM personal_update_attempts WHERE account=? AND calendar=? LIMIT 1",
                    arrayOf(target.account.value, target.calendar.value)).use {
                    assertTrue("Fresh enrollment cannot adopt prior personal work", !it.moveToFirst())
                }
            }
            ImportStore(app).use { store ->
                store.readableDatabase.rawQuery("SELECT 1 FROM personal_edit_preparations WHERE scope IN " +
                    "(SELECT scope FROM calendars WHERE account=? AND calendar=?) LIMIT 1",
                    arrayOf(target.account.value, target.calendar.value)).use {
                    assertTrue("No prior personal capsule may be resumed", !it.moveToFirst())
                }
                store.readableDatabase.rawQuery("SELECT 1 FROM personal_update_acknowledgements WHERE scope IN " +
                    "(SELECT scope FROM calendars WHERE account=? AND calendar=?) AND state!='RESTORED' LIMIT 1",
                    arrayOf(target.account.value, target.calendar.value)).use {
                    assertTrue("No unfinished personal acknowledgement may be resumed", !it.moveToFirst())
                }
            }
        }
        if (phase in setOf("ENROLL", "ACTIVATE", "PREPARE")) auditBeforeActivation()
        if (!prefs.contains("token")) {
            assertTrue("Only ENROLL admits fresh fixtures before any mode activation", phase == "ENROLL" && prefs.all.isEmpty())
            assertTrue("Freeze identities before preparing or sending", prefs.edit()
                .putString("token", token).putString("account_hash", accountHash)
                .putString("account", target.account.value).putString("calendar", target.calendar.value)
                .putLong("generation", target.generation).putString("editor_package", editorPackage)
                .putString("original_modes", "00000").putString("last_written_modes", "00000")
                .putBoolean("modes_restored", true)
                .putString("scope_digest", digest(*scopes.map { it.calendar.value + ":" + it.generation }.sorted().toTypedArray()))
                .putString("create_id", UUID.randomUUID().toString())
                .putString("start", Instant.now().truncatedTo(ChronoUnit.DAYS).plusSeconds(208800).toString())
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).commit())
        }
        assertTrue("Keep exact enrollment", prefs.getString("token", null) == token &&
            prefs.getString("account_hash", null) == accountHash && prefs.getString("account", null) == target.account.value &&
            prefs.getString("calendar", null) == target.calendar.value && prefs.getLong("generation", -1) == target.generation &&
            prefs.getString("editor_package", null) == editorPackage && prefs.getString("scope_digest", null) ==
                digest(*scopes.map { it.calendar.value + ":" + it.generation }.sorted().toTypedArray()))
        fun encode(value: List<Boolean>) = value.joinToString("") { if (it) "1" else "0" }
        fun othersReadOnly() = scopes.filter { it != target }.all { flags(it) == List(5) { false } }
        fun writeMode(position: Int, enabled: Boolean) {
            compose.runOnUiThread {
                assertTrue("Setter retains exact account/scopes and other calendars", accessible() && othersReadOnly())
                val before = flags(target)
                assertTrue("Never overwrite a later mode owner", encode(before) == prefs.getString("last_written_modes", null))
                val after = if (position == 0 && !enabled) List(5) { false }
                    else before.toMutableList().also { it[position] = enabled }
                // Persist both possible outcomes before a synchronous setter can write or throw.
                assertTrue(prefs.edit().putString("planned_modes", encode(after)).putBoolean("modes_restored", false).commit())
                val selection = app.imports.state.value.selections.single { it.scope == target }
                try {
                    when (position) {
                        0 -> app.systemCreates.setEnabled(selection, enabled)
                        1 -> app.systemCreates.setTitlesEnabled(selection, enabled)
                        2 -> app.systemCreates.setTextsEnabled(selection, enabled)
                        3 -> app.systemCreates.setTimesEnabled(selection, enabled)
                        4 -> app.systemCreates.setDeletesEnabled(selection, enabled)
                        else -> error("Unsupported mode")
                    }
                } finally {
                    val actual = flags(target)
                    if (actual == after || actual == before) {
                        assertTrue(prefs.edit().putString("last_written_modes", encode(actual)).remove("planned_modes").commit())
                    } else {
                        status("personal_system_mode_ownership_lost")
                        error("MODE_OWNERSHIP_LOST")
                    }
                }
                assertTrue("Setter must persist exactly its requested flags", flags(target) == after)
            }
        }
        if (phase == "ACTIVATE") {
            assertTrue("Activate only the fresh enrolled fixture before any CREATE/editor request",
                !prefs.getBoolean("create_requested", false) && !prefs.getBoolean("editor_requested", false) &&
                    prefs.getString("original_modes", null) == "00000" && prefs.getString("last_written_modes", null) == "00000" &&
                    !prefs.contains("planned_modes"))
            // Field setters require CREATE mode. The preceding read-only audit proved
            // there is no existing local work before enabling that general gate.
            listOf(0, 1, 2, 3).forEach { writeMode(it, true) }
            assertTrue("Activate exactly three field consents and CREATE", flags(target) == listOf(true, true, true, true, false))
            status("personal_system_modes_activated")
            return@runBlocking
        }
        if (phase == "RESTORE") {
            assertTrue("Restore only exact frozen scopes and original policy", accessible() && othersReadOnly() &&
                prefs.getString("original_modes", null) == "00000")
            val current = encode(flags(target))
            assertTrue("Refuse to overwrite a later human mode change", current == prefs.getString("last_written_modes", null) ||
                current == prefs.getString("planned_modes", null))
            assertTrue(prefs.edit().putString("last_written_modes", current).remove("planned_modes").commit())
            // Disable general editing first. Remaining setters only revoke field consent.
            listOf(0, 1, 2, 3, 4).forEach { if (flags(target)[it]) writeMode(it, false) }
            assertTrue("Restore all original modes exactly", flags(target) == List(5) { false } && othersReadOnly())
            assertTrue(prefs.edit().putBoolean("modes_restored", true).commit())
            status("personal_system_modes_restored")
            return@runBlocking
        }
        if (phase == "ENROLL") {
            assertTrue("Enrollment does not create or send", !prefs.getBoolean("create_requested", false) && !prefs.contains("event"))
            status("personal_system_enrolled_readonly")
            return@runBlocking
        }
        assertTrue("Only owned activated modes admit fixture work", prefs.getString("last_written_modes", null) == "11110" &&
            !prefs.contains("planned_modes") && !prefs.getBoolean("modes_restored", true))
        val start = Instant.parse(checkNotNull(prefs.getString("start", null)))
        val day = start.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)
        val original = EventContent("Calendeer QA personal system $token", EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC),
            "Synthetic personal system source", "QA source", details = EventDetails())
        val changed = original.copy(title = "Calendeer QA personal combined $token", description = "Combined personal system notes",
            location = "QA combined", time = EventTime.AllDay(day, day.plusDays(3)))
        if (phase == "DIAGNOSE") {
            val event = EventId(checkNotNull(prefs.getString("event", null)))
            val row = nativeValues(app, projection, target, event, prefs.getLong("row", -1), prefs.getLong("native_calendar", -1))
            val base = RemoteChange.Upsert(event, Revision(checkNotNull(prefs.getString("base_revision", null))), original)
            status("personal_system_diagnostic_native_capturable", projection.captureOwnedPersonalEdit(target, base) != null)
            status("personal_system_diagnostic_exact_texts", textsMatch(row, changed))
            status("personal_system_diagnostic_exact_dates", nativeTimeMatches(changed.time, row))
            status("personal_system_diagnostic_start_matches", row[Events.DTSTART] == prefs.getLong("start_after", -1).toString())
            status("personal_system_diagnostic_end_matches", row[Events.DTEND] == prefs.getLong("end_after", -1).toString())
            status("personal_system_diagnostic_start_zone_utc", row[Events.EVENT_TIMEZONE] == "UTC")
            status("personal_system_diagnostic_end_zone_utc", row[Events.EVENT_END_TIMEZONE] == "UTC")
            status("personal_system_diagnostic_end_zone_absent", row[Events.EVENT_END_TIMEZONE] == null)
            status("personal_system_diagnostic_end_zone_fixed_utc", try {
                java.time.ZoneId.of(row[Events.EVENT_END_TIMEZONE]).normalized() == ZoneOffset.UTC
            } catch (_: Exception) { false })
            status("personal_system_diagnostic_end_zone_original", row[Events.EVENT_END_TIMEZONE] == (original.time as EventTime.Timed).zone.id)
            status("personal_system_diagnostic_dirty", row[Events.DIRTY] == "1")
            status("personal_system_diagnostic_color_empty", row[Events.EVENT_COLOR] == null && row[Events.EVENT_COLOR_KEY] == null)
            status("personal_system_diagnostic_ordinary", ordinary(row))
            status("personal_system_diagnostic_organizer_owned", row[Events.ORGANIZER] == projection.accountFor(target.account).name)
            status("personal_system_diagnostic_metadata_flags_empty", listOf(Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY,
                Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
                Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL).all { row[it] == "0" })
            status("personal_system_diagnostic_owner_flag", row[Events.IS_ORGANIZER] == "1")
            status("personal_system_diagnostic_confirmed", row[Events.STATUS] == "1")
            status("personal_system_diagnostic_busy", row[Events.AVAILABILITY] == "0")
            status("personal_system_diagnostic_extra_fields_empty", listOf(Events.RRULE, Events.RDATE, Events.EXRULE,
                Events.EXDATE, Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
                Events.ORIGINAL_ALL_DAY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI).all { row[it] == null })
            status("personal_system_diagnostic_has_attempt", historyIds(app, target, event).isNotEmpty())
            ImportStore(app).use { reader -> status("personal_system_diagnostic_has_local_owner", reader.localEdit(target, event) != null) }
            status("personal_system_diagnostic_complete")
            return@runBlocking
        }
        if (phase == "PREPARE") {
            assertTrue("Never relaunch a requested editor", !prefs.getBoolean("editor_requested", false))
            if (!prefs.contains("event")) {
                val port = ProtonAuthentication.createSubmissions(app)
                CreateAttemptStore(app, "$prefix-create.db").use { creates ->
                    creates.admitDispatch = { false }
                    try {
                        val id = MutationId(checkNotNull(prefs.getString("create_id", null)))
                        val existing = creates.read(target, id)
                        assertTrue("Missing requested CREATE cannot be recreated", existing != null || !prefs.getBoolean("create_requested", false))
                        val work = existing ?: port.prepareSubmission(CalendarCreateDraft(target, id,
                            Instant.parse(prefs.getString("timestamp", null)), original)).let { prepared ->
                            CreateAttemptRecord(id, target, prepared.format, prepared.protectedPayload).also {
                                creates.admitDispatch = factual
                                try { creates.remember(it) } finally { creates.admitDispatch = { false } }
                            }
                        }
                        assertTrue("Require explicit empty reminders/color", ProtonCreateSubmissionProbe.knownEmptyPreparationSummary(work) == "SUBMISSION_V2_EXPLICIT_EMPTY")
                        assertTrue("Recheck policy before transport", policy())
                        val observation = if (work.state == CreateAttemptState.OUTCOME_UNKNOWN) port.inspect(work) else {
                            assertTrue("Requested CREATE is inspection-only", !prefs.getBoolean("create_requested", false))
                            assertTrue(prefs.edit().putBoolean("create_requested", true).commit())
                            creates.admitDispatch = factual
                            try { port.submit(work, creates) } finally { creates.admitDispatch = { false } }
                        }
                        assertTrue("Require authenticated complete CREATE readback", observation is CreateSubmissionObservation.Verified)
                        val verified = observation as CreateSubmissionObservation.Verified
                        val unknown = checkNotNull(creates.read(target, id))
                        assertTrue("Bind exact protected fresh event", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN &&
                            unknown.protectedEvidence != null && verified.attempt == CreateAttemptIdentity.of(unknown) && verified.event.content == original)
                        creates.bind(unknown, verified)
                        assertTrue(prefs.edit().putString("event", verified.event.eventId.value).commit())
                    } finally { creates.admitDispatch = { false } }
                }
            }
            val event = EventId(checkNotNull(prefs.getString("event", null)))
            assertTrue("No prior production personal edit for the fresh fixture", historyIds(app, target, event).isEmpty())
            app.imports.requestSync()
            withTimeout(240_000) {
                while (true) {
                    assertTrue("Keep policy while importing", policy())
                    val base = ImportStore(app).use { it.projectedBaseline(target, event) }
                    val owned = base?.let { projection.inspectOwnedEvent(target, event, it.revision) }
                    if (base?.content == original && owned != null) {
                        val row = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                        assertTrue("Only exact clean imported fixture may enter editor", textsMatch(row, original) && nativeTimeMatches(original.time, row) &&
                            row[Events.DIRTY] == "0" && row[Events.DELETED] == "0" && ordinary(row))
                        assertTrue("Freeze exact native identity before editor", prefs.edit().putString("base_revision", base.revision.value)
                            .putLong("row", owned.eventRow).putLong("native_calendar", owned.calendarRow)
                            .putLong("start_after", TitleJourneyTimeProfile.bounds(changed.time).first)
                            .putLong("end_after", TitleJourneyTimeProfile.bounds(changed.time).second)
                            .putString("zone", "UTC").putString("end_zone", "UTC")
                            .putInt("prepare_pid", Process.myPid()).putBoolean("editor_requested", true).commit())
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = ContentUris.withAppendedId(Events.CONTENT_URI, owned.eventRow)
                            setPackage(editorPackage); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusSeconds(3600).toEpochMilli())
                        }
                        assertTrue("Only explicitly enrolled installed editor receives fixture", intent.resolveActivity(app.packageManager)?.packageName == editorPackage)
                        app.startActivity(intent)
                        status("personal_system_editor_launched")
                        break
                    }
                    delay(500)
                }
            }
            status("personal_system_prepare_complete")
        } else {
            assertTrue("Only observe the opened fixture", prefs.getBoolean("editor_requested", false) && prefs.contains("event"))
            val event = EventId(checkNotNull(prefs.getString("event", null)))
            if (phase == "REPLAY") assertTrue("Replay requires completed proof from another process",
                prefs.getBoolean("worker_verified", false) && prefs.getInt("verify_pid", -1) != Process.myPid())
            val manager = WorkManager.getInstance(app)
            fun network() = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get()
            val previous = network().map { it.id }.toSet()
            // The ordinary refresh action schedules production workers, without invoking
            // any personal operation directly. Periodic workers may also contribute.
            app.imports.requestSync()
            var observed: ObservedProof? = null
            withTimeout(300_000) {
                var stable = 0
                while (stable < 2) {
                    assertTrue("Retain exact consents while observing production", policy())
                    observed = readProof(app, target, event, factual)
                    val fresh = network().filter { it.id !in previous }
                    assertTrue("Fresh production work must not fail", fresh.none {
                        it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED })
                    val quiet = fresh.isNotEmpty() && fresh.all { it.state == WorkInfo.State.SUCCEEDED } &&
                        manager.getWorkInfosForUniqueWork(LOCAL_WORK).get().all { it.state.isFinished } &&
                        manager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().none {
                            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                        !app.imports.state.value.busy && !app.systemCreates.state.value.busy
                    stable = if (observed != null && quiet) stable + 1 else 0
                    if (stable < 2) delay(500)
                }
            }
            val proof = checkNotNull(observed)
            verifyProof(app, projection, prefs, target, event, original, changed, proof)
            assertNoOtherHistory(app, target, event)
            if (phase == "VERIFY") {
                assertTrue("Never replace a completed verification", !prefs.getBoolean("worker_verified", false))
                assertTrue(prefs.edit().putString("attempt", proof.record.id.value).putString("intent", proof.record.preparation.intent.id.value)
                    .putString("history_digest", proof.digest).putInt("verify_pid", Process.myPid()).putBoolean("worker_verified", true).commit())
                status("personal_system_worker_completed"); status("personal_system_same_row_acknowledged")
            } else {
                assertTrue("Restart retains full immutable request/result/completion", proof.record.id.value == prefs.getString("attempt", null) &&
                    proof.record.preparation.intent.id.value == prefs.getString("intent", null) && proof.digest == prefs.getString("history_digest", null))
                status("personal_system_immutable_restart"); status("personal_system_no_new_attempt")
            }
        }
        assertTrue("Fixture leaves mode precondition unchanged", policy())
        status("personal_system_modes_unchanged")
    }
    private class ObservedProof(val record: PersonalUpdateAttemptRecord, val result: PersonalUpdateVerifiedResult,
        val completion: PersonalUpdateCompletion, val digest: String)

    private fun assertNoOtherHistory(app: Context, scope: CalendarScope, event: EventId) {
        TitleUpdateAttemptStore(app).use { shared ->
            for (table in listOf("title_update_attempts", "title_update_preparation_reservations", "text_update_attempts", "time_update_attempts", "delete_attempts")) {
                shared.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE account=? AND calendar=? AND event=? LIMIT 1",
                    arrayOf(scope.account.value, scope.calendar.value, event.value)).use {
                    assertTrue("The enrolled personal event cannot borrow another write family", !it.moveToFirst())
                }
            }
        }
    }

    private fun historyIds(app: Context, scope: CalendarScope, event: EventId): List<MutationId> =
        TitleUpdateAttemptStore(app).use { shared ->
            shared.readableDatabase.rawQuery("SELECT CASE WHEN typeof(mutation)='text' AND " +
                "length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN mutation END FROM personal_update_attempts " +
                "WHERE account=? AND calendar=? AND event=? LIMIT 2",
                arrayOf(scope.account.value, scope.calendar.value, event.value)).use { cursor ->
                assertTrue("The fixture must not have duplicate personal history", cursor.count <= 1)
                buildList { while (cursor.moveToNext()) {
                    assertTrue("The retained request identity must be valid metadata", !cursor.isNull(0))
                    add(MutationId(cursor.getString(0)))
                } }
            }
        }

    private suspend fun readProof(app: Context, scope: CalendarScope, event: EventId,
        admit: (CalendarScope) -> Boolean): ObservedProof? {
        val id = historyIds(app, scope, event).singleOrNull() ?: return null
        return TitleUpdateAttemptStore(app).use { shared ->
            val journal = shared.personalJournal
            val record = journal.read(scope, id, admit) ?: return@use null
            val result = journal.readVerified(scope, id, admit) ?: return@use null
            val completion = journal.readCompletion(scope, id, admit) ?: return@use null
            ObservedProof(record, result, completion, historyDigest(shared.readableDatabase, scope, event, id))
        }
    }

    /** Hash every typed SQL field, including opaque BLOB bytes, using bounded chunks. */
    private fun historyDigest(db: SQLiteDatabase, scope: CalendarScope, event: EventId, id: MutationId): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val where = "account=? AND calendar=? AND generation=? AND mutation=? AND event=?"
        val args = arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), id.value, event.value)
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        field("personal-system-full-sql-history-v1")
        for (column in listOf("account", "calendar", "generation", "mutation", "event", "state", "capsule", "result", "completion")) {
            val metadata = db.rawQuery("SELECT typeof($column),length(CAST($column AS BLOB)) FROM personal_update_attempts WHERE $where", args).use {
                assertTrue("Require one complete scoped history row", it.count == 1 && it.moveToFirst())
                it.getString(0) to it.getLong(1)
            }
            val type = metadata.first; val size = metadata.second
            val payload = column in setOf("capsule", "result", "completion")
            val expectedType = if (payload) "blob" else if (column == "generation") "integer" else "text"
            assertTrue("Require bounded typed history", size in 1..(16L * 1024 * 1024) && type == expectedType)
            field(column); field(type); field(size.toString())
            var offset = 0L
            while (offset < size) {
                val count = minOf(65536L, size - offset).toInt()
                val part = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,?) FROM personal_update_attempts WHERE $where",
                    arrayOf((offset + 1).toString(), count.toString()) + args).use {
                    assertTrue("Keep the exact history row during hashing", it.count == 1 && it.moveToFirst() && !it.isNull(0))
                    it.getBlob(0)
                }
                assertTrue("Read every original byte without truncation", part.size == count)
                hash.update(part); offset += count
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun verifyProof(app: Context, projection: AndroidCalendarProjection, prefs: SharedPreferences,
        scope: CalendarScope, event: EventId, original: EventContent, changed: EventContent, proof: ObservedProof) {
        val record = proof.record
        val intent = record.preparation.intent
        val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        assertTrue("Production generates a native owner and deterministic personal request",
            intent.id.value.matches(uuid) && record.id == PersonalUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                record.scope == scope && intent.scope == scope && intent.base.eventId == event)
        assertTrue("Bind the exact original revision and Provider time edit",
            intent.base.content == original && intent.content == changed && PersonalEditCandidate(intent).requiredConsent == setOf(PersonalEditField.TITLE, PersonalEditField.TEXT, PersonalEditField.TIME) &&
                intent.base.revision.value == prefs.getString("base_revision", null) &&
                intent.state == LocalEditState.PAUSED && record.preparation.timestamp.nano == 0)
        assertTrue("Keep complete UNKNOWN work, protected response and actual canonical result",
            record.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                proof.result.record == record.copy(protectedEvidence = null) && proof.result.event.content == changed &&
                proof.result.event.eventId == event && proof.result.event.revision != intent.base.revision &&
                proof.completion.result == proof.result && proof.completion.receipt.handoff == null &&
                proof.completion.receipt.matches(proof.result))
        ImportStore(app).use { reader ->
            val acknowledgement = reader.personalUpdateAcknowledgement(scope, event, record.id)
            assertTrue("SQL restoration consumes only the completed owner and capsule",
                reader.projectedBaseline(scope, event) == proof.result.event && reader.localEdit(scope, event) == null &&
                    reader.localTimeEditPreparation(scope, event) == null && reader.localTextEditPreparation(scope, event) == null && reader.localEditPreparation(scope, event) == null &&
                    reader.personalEditPreparation(scope, event) == null && reader.localDelete(scope, event) == null && reader.capturedHandoff(scope, event) == null &&
                    acknowledgement != null && acknowledgement.state == PersonalUpdateAcknowledgementState.RESTORED &&
                    acknowledgement.receipt == proof.completion.receipt)
        }
        val row = prefs.getLong("row", -1); val calendar = prefs.getLong("native_calendar", -1)
        val owned = projection.inspectOwnedEvent(scope, event, proof.result.event.revision)
        assertTrue("The original owned row is unchanged", owned != null && owned.eventRow == row && owned.calendarRow == calendar)
        val native = nativeValues(app, projection, scope, event, row, calendar)
        // Ordinary incoming refresh may reproject this exact revision with a fresh nonce.
        // Historical ACK proof remains exact in SQL; current native ownership/content must
        // remain exact, but SYNC_DATA3 names the latest native projection, not a perpetual ACK.
        val currentProjectionMarker = native[Events.SYNC_DATA3]?.matches(Regex(
            "calendeer-projection-receipt-v1:[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) == true
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("personal_system_native_current_projection_valid", currentProjectionMarker)
            putBoolean("personal_system_native_texts_match", textsMatch(native, changed))
            putBoolean("personal_system_native_ordinary", ordinary(native))
            putBoolean("personal_system_native_time_match", nativeTimeMatches(changed.time, native))
            putBoolean("personal_system_native_start_match", native[Events.DTSTART] == prefs.getLong("start_after", -1).toString())
            putBoolean("personal_system_native_end_match", native[Events.DTEND] == prefs.getLong("end_after", -1).toString())
            putBoolean("personal_system_native_zone_match", native[Events.EVENT_TIMEZONE] == prefs.getString("zone", null))
            putBoolean("personal_system_native_end_zone_match", personalEndZoneMatches(changed.time, native[Events.EVENT_END_TIMEZONE], prefs.getString("end_zone", null)))
            putBoolean("personal_system_native_revision_match", native[Events.SYNC_DATA2] == digest("revision-v1", proof.result.event.revision.value))
            putBoolean("personal_system_native_ack_nonce_match", native[Events.SYNC_DATA3] ==
                "calendeer-projection-receipt-v1:" + proof.completion.receipt.projection.nonce)
        })
        assertTrue("The same ordinary event is clean with exact texts and changed dates",
            textsMatch(native, changed) && native[Events.DIRTY] == "0" && native[Events.DELETED] == "0" &&
                ordinary(native) && nativeTimeMatches(changed.time, native) &&
                native[Events.DTSTART] == prefs.getLong("start_after", -1).toString() &&
                native[Events.DTEND] == prefs.getLong("end_after", -1).toString() &&
                native[Events.EVENT_TIMEZONE] == prefs.getString("zone", null) &&
                personalEndZoneMatches(changed.time, native[Events.EVENT_END_TIMEZONE], prefs.getString("end_zone", null)) &&
                native[Events.SYNC_DATA2] == digest("revision-v1", proof.result.event.revision.value) &&
                currentProjectionMarker)
    }

    private fun textsMatch(values: Map<String, String?>, content: EventContent) =
        values[Events.TITLE] == content.title && values[Events.DESCRIPTION].orEmpty() == content.description &&
            values[Events.EVENT_LOCATION].orEmpty() == content.location

    private fun nativeTimeMatches(time: EventTime, values: Map<String, String?>): Boolean {
        val end = if (time is EventTime.AllDay && personalEndZoneMatches(time, values[Events.EVENT_END_TIMEZONE], "UTC"))
            "UTC" else values[Events.EVENT_END_TIMEZONE]
        return TitleJourneyTimeProfile.nativeMatches(time, values[Events.ALL_DAY], values[Events.DTSTART],
            values[Events.DTEND], values[Events.EVENT_TIMEZONE], end)
    }
    private fun personalEndZoneMatches(time: EventTime, actual: String?, expected: String?): Boolean =
        if (time is EventTime.AllDay) try { actual != null && java.time.ZoneId.of(actual).normalized() == ZoneOffset.UTC }
        catch (_: Exception) { false } else actual == expected

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

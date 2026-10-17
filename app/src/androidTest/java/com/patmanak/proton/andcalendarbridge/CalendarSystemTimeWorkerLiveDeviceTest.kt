// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
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

/** Production controller/WorkManager qualification of an ordinary time-only Provider user edit.
 * This does not qualify a calendar editor or OEM. No direct preparation, submission, inspection,
 * acknowledgement or manual worker entry point is called. RUN and restarted REPLAY temporarily
 * change only the target's four write modes, retaining exact restoration provenance. Other
 * calendars keep their original consent: shared WorkManager jobs may process their already
 * authorized work. Only the target pipeline is qualified, without global quiet or traffic claims.
 * Periodic jobs may contribute; exclusive observer causality is not claimed. Retain the event.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSystemTimeWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun providerTimeEditRunsThroughProductionWorkersAndStaysClosedAfterRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemTimeWorker") == "true")
        val phase = args.getString("timeWorkerPhase").orEmpty()
        val token = args.getString("timeFixtureToken").orEmpty()
        val accountHash = args.getString("timeAccountHash").orEmpty()
        assertTrue("Explicit phase, fixture and account are required", phase in setOf("RUN", "REPLAY") &&
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let { it.authenticated && it.selections.isNotEmpty() && !it.busy } }
        assertTrue("Production temporal controllers must be composed", app.systemCreates.state.value.timeSupportAvailable)
        val sourcePrefix = "calendar-time-guarded-v1-$token"
        val prefix = "calendar-time-worker-v2-$token"
        val source = app.getSharedPreferences(sourcePrefix, Context.MODE_PRIVATE)
        assertTrue("The independent service journey must already have durable ACK", source.getString("token", null) == token &&
            source.getString("account_hash", null) == accountHash && source.getBoolean("ack_completed", false) &&
            source.getString("journey_label", null) == sourcePrefix &&
            source.getString("source_calendar_name", null) == "Calendeer QA Automatic Worker")
        fun sourceValue(key: String) = checkNotNull(source.getString(key, null)) { "Missing enrolled source field" }
        val target = CalendarScope(AccountId(sourceValue("account")), CalendarId(sourceValue("calendar")), source.getLong("generation", -1))
        val event = EventId(sourceValue("event"))
        val profile = TemporalJourneyProfile.create(args.getString("timeProfile"))
        assertTrue("Preserve the exact enrolled time profile", profile.matchesFrozen(source.getInt("content_profile", -1),
            source.getString("start", null), source.getString("end", null)) &&
            source.getString("time_profile", "utc") == profile.name && source.getString("time_zone", "Z") == profile.zone)
        val original = EventContent(sourceValue("create_title"), profile.after,
            sourceValue("description"), sourceValue("location"), details = EventDetails())
        val changed = original.copy(time = when (val time = original.time) {
            is EventTime.Timed -> time.copy(start = time.start.plusSeconds(3_600), end = time.end.plusSeconds(3_600))
            is EventTime.AllDay -> time.copy(start = time.start.plusDays(1), endExclusive = time.endExclusive.plusDays(1))
        })
        assertTrue("Require the exact ordinary synthetic source", target.generation == 0L &&
            original.title == "Calendeer QA — time CREATE $token")
        assertTrue("Authorize the enrolled account before any mutation",
            ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == target && it.enabled && it.available &&
                it.summary.title == "Calendeer QA Automatic Worker" } == 1 }
        assertTrue("The dedicated source remains accessible", accessible())
        val factual: (CalendarScope) -> Boolean = { it == target && accessible() }
        TitleUpdateAttemptStore(app, "$sourcePrefix-shared-attempts.db").use { shared ->
            val journal = shared.timeJournal; val id = MutationId(sourceValue("attempt_id"))
            val record = journal.read(target, id, factual); val result = journal.readVerified(target, id, factual)
            val completion = journal.readCompletion(target, id, factual)
            assertTrue("Bind the independent completed temporal proof", record != null && result != null && completion != null &&
                record.scope == target && record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                record.id == TimeUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                result.record == record.copy(protectedEvidence = null) && result.event.eventId == event &&
                result.event.content == original && completion.result == result && completion.receipt.matches(result))
        }
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val restoration = app.getSharedPreferences("$prefix-mode-restoration", Context.MODE_PRIVATE)
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permissions are required", projection.hasPermissions())
        projection.admitOperation = ::accessible
        fun flags(scope: CalendarScope) = SystemCreateWorkStore(app).use {
            listOf(it.modeEnabled(scope), it.titlesEnabled(scope), it.textsEnabled(scope), it.timesEnabled(scope))
        }
        val selections = app.imports.state.value.selections.toList()
        assertTrue("Bound and authorize every restoration scope", selections.size in 1..128 &&
            selections.map { it.scope }.distinct().size == selections.size && selections.all { it.scope.account == target.account })
        val originals = selections.sortedWith(compareBy({ it.scope.calendar.value }, { it.scope.generation }))
            .associate { it.scope to flags(it.scope) }
        // Other calendars retain their existing consent and held work. The fixture never
        // changes their flags or decodes their intentions; its complete work audit is target-only.
        val strictAuditScopes = setOf(target)
        fun emitPreflightCounts() {
            val groups = linkedMapOf(
                "target" to listOf(target),
                "writable_other" to originals.filter { (scope, saved) -> scope != target && saved.any { it } }.keys.toList(),
                "readonly_other" to originals.filter { (scope, saved) -> scope != target && saved.none { it } }.keys.toList())
            val evidence = Bundle()
            ImportStore(app).use { store ->
                for ((category, scopes) in groups) {
                    // Match the full audit's all-generation calendar boundary. Return counts only;
                    // no identity, content or unknown intent/capsule is fetched or decoded.
                    val calendars = scopes.map { it.calendar.value }.distinct()
                    val placeholders = calendars.joinToString(",") { "?" }
                    for (table in listOf("local_edits", "local_edit_preparations",
                        "local_text_edit_preparations", "local_time_edit_preparations")) {
                        val count = if (calendars.isEmpty()) 0 else store.readableDatabase.rawQuery(
                            "SELECT COUNT(*) FROM (SELECT 1 FROM $table WHERE scope IN " +
                                "(SELECT scope FROM calendars WHERE account=? AND calendar IN ($placeholders)) LIMIT 513)",
                            arrayOf(target.account.value) + calendars.toTypedArray()).use {
                            check(it.moveToFirst()); it.getInt(0)
                        }
                        val key = "system_time_preflight_${category}_$table"
                        evidence.putInt("${key}_count", count.coerceAtMost(512))
                        evidence.putBoolean("${key}_overflow", count > 512)
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
        }
        val provenance = linkedMapOf<String, Any>("schema" to 1, "token" to token, "account_hash" to accountHash,
            "account" to target.account.value, "target_calendar" to target.calendar.value,
            "target_generation" to target.generation, "count" to originals.size)
        originals.entries.forEachIndexed { index, entry ->
            provenance["calendar_$index"] = entry.key.calendar.value; provenance["generation_$index"] = entry.key.generation
            listOf("create", "titles", "texts", "times").forEachIndexed { position, name -> provenance["${name}_$index"] = entry.value[position] }
        }
        assertTrue("Never replace frozen four-mode provenance", restoration.all.isEmpty() || restoration.all == provenance)
        fun sameScopes() = app.imports.state.value.selections.map { it.scope }.toSet() == originals.keys
        fun modesMatchOriginal() = originals.all { flags(it.key) == it.value }
        fun temporaryModes() = originals.all { (scope, saved) ->
            flags(scope) == if (scope == target) listOf(true, false, false, true) else saved
        }
        fun isolatedOriginalModes() = modesMatchOriginal()
        fun selected(scope: CalendarScope) = app.imports.state.value.selections.single { it.scope == scope }.also {
            assertTrue("Mode changes require the same accessible account and selection set", accessible() && sameScopes() &&
                (originals.getValue(scope).none { flag -> flag } || it.enabled && it.available))
        }
        // UI-thread-only snapshots of the values this fixture actually writes. Never adopt a
        // later observed policy as a new restoration baseline or as fresh fixture ownership.
        val lastWritten = linkedMapOf<CalendarScope, List<Boolean>>()
        fun writeMode(scope: CalendarScope, position: Int, enabled: Boolean) {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            assertTrue("Other calendars never receive fixture setters", scope == target)
            val before = lastWritten[scope] ?: originals.getValue(scope)
            val owned = flags(scope) == before
            if (!owned) status("system_time_restoration_not_owned")
            assertTrue("The fixture no longer owns the current mode flags", owned)
            val selection = selected(scope)
            assertTrue("Mode writes require unchanged frozen provenance", restoration.all == provenance)
            val after = before.toMutableList().apply {
                if (position == 0 && !enabled) indices.forEach { this[it] = false }
                else this[position] = enabled && (position == 0 || before[0])
            }.toList()
            try {
                when (position) {
                    0 -> app.systemCreates.setEnabled(selection, enabled)
                    1 -> app.systemCreates.setTitlesEnabled(selection, enabled)
                    2 -> app.systemCreates.setTextsEnabled(selection, enabled)
                    3 -> app.systemCreates.setTimesEnabled(selection, enabled)
                    else -> error("Unsupported mode position")
                }
            } finally {
                // A setter can persist synchronously and then fail. Retain only its exact
                // precomputed result; an unrelated observed value never grants ownership.
                val current = flags(scope)
                if (current == after) lastWritten[scope] = after
                else if (current != before) status("system_time_restoration_not_owned")
            }
            assertTrue("The synchronous setter must persist its exact expected flags", flags(scope) == after)
        }
        val manager = WorkManager.getInstance(app)
        fun queue(name: String) = manager.getWorkInfosForUniqueWork(name).get()
        var periodicObserved = false
        suspend fun idleQueues(temporary: Boolean?) = withTimeout(240_000) {
            var samples = 0
            while (samples < 2) {
                assertTrue("Keep the authorized session, scopes and exact policy", accessible() && sameScopes() &&
                    (when (temporary) { true -> temporaryModes(); false -> modesMatchOriginal(); null -> isolatedOriginalModes() }))
                val periodic = queue(PERIODIC_WORK)
                periodicObserved = periodicObserved || periodic.any { it.state == WorkInfo.State.RUNNING }
                val idle = queue(NETWORK_WORK).all { it.state.isFinished } && queue(LOCAL_WORK).all { it.state.isFinished } &&
                    periodic.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                    !app.imports.state.value.busy && !app.systemCreates.state.value.busy
                samples = if (idle) samples + 1 else 0
                if (samples < 2) delay(500)
            }
        }
        suspend fun baseline() = ImportStore(app).use { it.projectedBaseline(target, event) }
        suspend fun noOwner() = ImportStore(app).use {
            it.localEdit(target, event) == null && it.localDelete(target, event) == null &&
                it.localEditPreparation(target, event) == null && it.localTextEditPreparation(target, event) == null &&
                it.localTimeEditPreparation(target, event) == null && it.capturedHandoff(target, event) == null
        }
        fun frozenIdentity() = prefs.getString("token", null) == token && prefs.getString("account_hash", null) == accountHash &&
            prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
            prefs.getLong("generation", -1) == target.generation && prefs.getString("event", null) == event.value &&
            prefs.getLong("start", -1) == TitleJourneyTimeProfile.bounds(changed.time).first &&
            prefs.getLong("end", -1) == TitleJourneyTimeProfile.bounds(changed.time).second
        var restore = false
        try {
            assertTrue("Start from unchanged original policy", modesMatchOriginal())
            if (phase == "RUN") {
                assertTrue("Never repeat an already requested Provider edit", !prefs.getBoolean("provider_edit_requested", false) &&
                    !prefs.getBoolean("worker_verified", false))
                if (prefs.contains("token")) assertTrue("Never replace a pre-edit enrollment", frozenIdentity())
                assertTrue("The production event has no prior temporal history", historyIds(app, target, event).isEmpty())
            } else assertTrue("Replay requires the same completed fixture in a new process", frozenIdentity() &&
                prefs.getBoolean("provider_edit_requested", false) && prefs.getBoolean("worker_verified", false) &&
                prefs.getBoolean("modes_restored", false) && prefs.getInt("run_pid", -1) > 0 && prefs.getInt("run_pid", -1) != Process.myPid())
            assertNoOtherHistory(app, target, event)
            emitPreflightCounts()
            // Reject target local/uncertain work before waiting for shared ordinary queues.
            for (scope in strictAuditScopes) assertScopeQuiescent(app, projection, scope)
            status("system_time_initial_queues_waiting")
            idleQueues(false)
            status("system_time_initial_queues_idle")
            // Recheck the target before its temporary consent can resume outgoing work.
            for (scope in strictAuditScopes) assertScopeQuiescent(app, projection, scope)
            if (restoration.all.isEmpty()) {
                val editor = restoration.edit()
                provenance.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> error("Unsupported restoration field")
                } }
                assertTrue("Freeze every original mode before any setter", editor.commit())
            }
            assertTrue("Exact four-mode provenance is durable", restoration.all == provenance)
            restore = true
            assertTrue(prefs.edit().putBoolean("modes_restored", false).commit())
            if (phase == "RUN") {
                // Ordinary production import only; no outgoing entry point is called by this fixture.
                app.imports.requestSync()
                withTimeout(240_000) {
                    var samples = 0
                    while (true) {
                        assertTrue("Keep source access while importing", accessible() && sameScopes())
                        val base = baseline()
                        val free = noOwner()
                        val owned = base?.let { projection.inspectOwnedEvent(target, event, it.revision) != null } ?: false
                        if (samples++ % 60 == 0) {
                            status("system_time_import_base_present", base != null)
                            status("system_time_import_time_matches", base?.content?.time == original.time)
                            status("system_time_import_text_matches", base?.content?.let {
                                it.title == original.title && it.description == original.description && it.location == original.location
                            } == true)
                            status("system_time_import_details_match", base?.content?.details == original.details)
                            status("system_time_import_no_owner", free)
                            status("system_time_import_native_owned", owned)
                        }
                        if (base?.content == original && free && owned) break
                        delay(500)
                    }
                }
                val base = checkNotNull(baseline()); val owned = checkNotNull(projection.inspectOwnedEvent(target, event, base.revision))
                val clean = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                assertTrue("Start from the exact ordinary clean service result", base.content == original && noOwner() &&
                    clean[Events.DIRTY] == "0" && clean[Events.DELETED] == "0" && ordinary(clean) &&
                    textsMatch(clean, original) && nativeTimeMatches(original.time, clean))
                if (prefs.contains("token")) assertTrue("Pre-edit retry preserves exact row and revision",
                    prefs.getLong("row", -1) == owned.eventRow && prefs.getLong("native_calendar", -1) == owned.calendarRow &&
                        prefs.getString("base_revision", null) == base.revision.value)
                else assertTrue("Freeze exact fixture identity before activation", prefs.edit()
                    .putString("token", token).putString("account_hash", accountHash).putString("account", target.account.value)
                    .putString("calendar", target.calendar.value).putLong("generation", target.generation).putString("event", event.value)
                    .putString("base_revision", base.revision.value).putLong("row", owned.eventRow).putLong("native_calendar", owned.calendarRow)
                    .putString("zone", clean[Events.EVENT_TIMEZONE]).putString("end_zone", clean[Events.EVENT_END_TIMEZONE])
                    .putLong("start", TitleJourneyTimeProfile.bounds(changed.time).first)
                    .putLong("end", TitleJourneyTimeProfile.bounds(changed.time).second).commit())
            }
            // KEEP scheduling may absorb activation into an existing incoming job. Converge first
            // so a fresh automatic production job must be observed after the consent setters.
            idleQueues(null)
            val activationIds = queue(NETWORK_WORK).map { it.id.toString() }.toSet()
            compose.runOnUiThread {
                writeMode(target, 0, true)
                writeMode(target, 1, false)
                writeMode(target, 2, false)
                writeMode(target, 3, true)
            }
            assertTrue("Target CREATE/time consent and original other flags are exact", temporaryModes())
            withTimeout(240_000) {
                while (queue(NETWORK_WORK).none { it.id.toString() !in activationIds }) {
                    assertTrue("Activation keeps exact scope and policy", accessible() && sameScopes() && temporaryModes()); delay(200)
                }
            }
            idleQueues(true)
            for (scope in strictAuditScopes) assertScopeQuiescent(app, projection, scope)
            if (phase == "RUN") {
                val base = checkNotNull(baseline())
                assertTrue("Activation cannot substitute the original baseline or create work", base.content == original && noOwner() &&
                    base.revision.value == prefs.getString("base_revision", null) && historyIds(app, target, event).isEmpty())
                assertNoOtherHistory(app, target, event)
                val before = nativeValues(app, projection, target, event, prefs.getLong("row", -1), prefs.getLong("native_calendar", -1))
                assertTrue("Only the frozen clean ordinary event may be edited", textsMatch(before, original) && nativeTimeMatches(original.time, before) &&
                    before[Events.DIRTY] == "0" && before[Events.DELETED] == "0" && ordinary(before) &&
                    before[Events.SYNC_DATA2] == digest("revision-v1", base.revision.value))
                val beforeIds = queue(NETWORK_WORK).map { it.id.toString() }.toSet()
                assertTrue("Freeze the sole Provider edit before mutation", prefs.edit().putBoolean("provider_edit_requested", true)
                    .putInt("run_pid", Process.myPid()).putStringSet("previous_network_work", beforeIds).commit())
                // No sync-adapter flag: an ordinary caller writes dates only; Provider marks DIRTY.
                val changedRows = app.contentResolver.update(Events.CONTENT_URI, ContentValues().apply {
                    put(Events.DTSTART, prefs.getLong("start", -1)); put(Events.DTEND, prefs.getLong("end", -1))
                }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=? AND ${Events.SYNC_DATA4}=? AND " +
                    "${Events.DIRTY}=0 AND ${Events.DELETED}=0 AND ${Events.ALL_DAY}=? AND ${Events.DTSTART}=? AND ${Events.DTEND}=? AND " +
                    "${Events.TITLE}=? AND COALESCE(${Events.DESCRIPTION},'')=? AND COALESCE(${Events.EVENT_LOCATION},'')=? AND " +
                    "${Events.EVENT_TIMEZONE}=? AND COALESCE(${Events.EVENT_END_TIMEZONE},'')=? AND ${Events.SYNC_DATA2}=?",
                    arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString(),
                        checkNotNull(before[Events._SYNC_ID]), checkNotNull(before[Events.SYNC_DATA4]),
                        checkNotNull(before[Events.ALL_DAY]), checkNotNull(before[Events.DTSTART]), checkNotNull(before[Events.DTEND]), original.title, original.description, original.location,
                        checkNotNull(before[Events.EVENT_TIMEZONE]), before[Events.EVENT_END_TIMEZONE].orEmpty(), checkNotNull(before[Events.SYNC_DATA2])))
                assertTrue("The guarded Provider edit changes exactly one enrolled row", changedRows == 1)
                status("system_time_provider_edit_requested")
                var completed: ObservedProof? = null
                withTimeout(300_000) {
                    while (true) {
                        assertTrue("Keep exact consent and scope while observing production", accessible() && sameScopes() && temporaryModes())
                        val proof = readProof(app, target, event, factual)
                        val work = queue(NETWORK_WORK).filter { it.id.toString() !in beforeIds }
                        periodicObserved = periodicObserved || queue(PERIODIC_WORK).any { it.state == WorkInfo.State.RUNNING }
                        assertTrue("Automatically queued network work must not fail", work.none {
                            it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED
                        })
                        if (proof != null && work.isNotEmpty() && work.all { it.state == WorkInfo.State.SUCCEEDED } &&
                            !app.imports.state.value.busy && !app.systemCreates.state.value.busy) { completed = proof; break }
                        delay(500)
                    }
                }
                idleQueues(true)
                val proof = checkNotNull(readProof(app, target, event, factual)); val observed = checkNotNull(completed)
                assertTrue("Keep complete history after queues converge", proof.record == observed.record && proof.result == observed.result &&
                    proof.completion == observed.completion && proof.digest == observed.digest)
                verifyProof(app, projection, prefs, target, event, original, changed, proof)
                assertTrue("Freeze exact production history for restarted replay", prefs.edit().putString("attempt", proof.record.id.value)
                    .putString("intent", proof.record.preparation.intent.id.value).putString("history_digest", proof.digest)
                    .putBoolean("worker_verified", true).commit())
                status("system_time_worker_completed"); status("system_time_same_row_acknowledged")
                status("system_time_provider_worker_pipeline_qualified"); status("system_time_periodic_running_observed", periodicObserved)
            } else {
                val proof = checkNotNull(readProof(app, target, event, factual))
                assertTrue("The full original history remains immutable after active restart", proof.record.id.value == prefs.getString("attempt", null) &&
                    proof.record.preparation.intent.id.value == prefs.getString("intent", null) && proof.digest == prefs.getString("history_digest", null))
                verifyProof(app, projection, prefs, target, event, original, changed, proof)
                assertTrue("Replay cannot allocate another time attempt", historyIds(app, target, event) == listOf(proof.record.id))
                status("system_time_immutable_restart"); status("system_time_no_new_attempt")
            }
            assertNoOtherHistory(app, target, event)
            for (scope in strictAuditScopes) assertScopeQuiescent(app, projection, scope)
        } finally {
            try {
                if (restore) {
                    assertTrue("Restore only exact frozen scopes in the authorized account", accessible() && sameScopes() && restoration.all == provenance)
                    compose.runOnUiThread {
                        assertTrue("Recheck restoration identity and provenance before any UI-thread setter",
                            accessible() && sameScopes() && restoration.all == provenance)
                        // Only the target can be touched. Refuse restoration if a later owner
                        // changed its consent; every other calendar remains untouched throughout.
                        val restorable = lastWritten.filter { (scope, expected) -> flags(scope) == expected }
                        val stillOwned = restorable.size == lastWritten.size
                        restorable.keys.forEach { scope ->
                            originals.getValue(scope).forEachIndexed { position, enabled -> writeMode(scope, position, enabled) }
                        }
                        if (!stillOwned) status("system_time_restoration_not_owned")
                        assertTrue("Restoration no longer owns all temporary mode flags", stillOwned)
                    }
                    val restored = modesMatchOriginal()
                    if (!restored) status("system_time_restoration_not_owned")
                    assertTrue("Restore all four modes without overwriting later owner consent", restored)
                    assertTrue(prefs.edit().putBoolean("modes_restored", true).commit())
                    status("system_time_modes_restored"); status("system_time_other_modes_unchanged")
                }
            } finally { projection.admitOperation = { false } }
        }
    }

    private class ObservedProof(val record: TimeUpdateAttemptRecord, val result: TimeUpdateVerifiedResult,
        val completion: TimeUpdateCompletion, val digest: String)

    private fun assertNoOtherHistory(app: Context, scope: CalendarScope, event: EventId) {
        TitleUpdateAttemptStore(app).use { shared ->
            for (table in listOf("title_update_attempts", "title_update_preparation_reservations", "text_update_attempts")) {
                shared.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE account=? AND calendar=? AND event=? LIMIT 1",
                    arrayOf(scope.account.value, scope.calendar.value, event.value)).use {
                    assertTrue("The enrolled temporal event cannot borrow another write family", !it.moveToFirst())
                }
            }
        }
    }

    private suspend fun assertScopeQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        ImportStore(app).use { store ->
            val inCalendar = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?)"
            for (table in listOf("local_edits", "local_edit_preparations", "local_text_edit_preparations", "local_time_edit_preparations")) {
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar LIMIT 1",
                    message = "The calendar retains a local owner or capsule")
            }
            for (table in listOf("text_update_acknowledgements", "time_update_acknowledgements")) {
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar AND state!='RESTORED' LIMIT 1",
                    message = "The calendar retains an unfinished acknowledgement")
            }
            empty(store.readableDatabase, "SELECT 1 FROM projection_receipts WHERE $inCalendar LIMIT 1",
                message = "The calendar retains an unfinished projection receipt")
            empty(store.readableDatabase, "SELECT 1 FROM records WHERE $inCalendar AND pending!=0 LIMIT 1",
                message = "The calendar retains a pending incoming projection")
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
            for (row in metadata(store.readableDatabase, "text_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val id = MutationId(row[1])
                val journal = store.textJournal
                val record = journal.read(retainedScope, id) { it == retainedScope }
                val result = journal.readVerified(retainedScope, id) { it == retainedScope }
                val completion = journal.readCompletion(retainedScope, id) { it == retainedScope }
                assertTrue("The calendar retains unfinished or invalid text work", record != null && result != null &&
                    completion != null && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN &&
                    record.scope == retainedScope && record.id == id && record.eventId.value == row[2] &&
                    result.record == record.copy(protectedEvidence = null) && completion.result == result &&
                    completion.receipt.matches(result))
            }
            for (row in metadata(store.readableDatabase, "time_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val id = MutationId(row[1]); val journal = store.timeJournal
                val record = journal.read(retainedScope, id) { it == retainedScope }
                val result = journal.readVerified(retainedScope, id) { it == retainedScope }
                val completion = journal.readCompletion(retainedScope, id) { it == retainedScope }
                assertTrue("The calendar retains unfinished or invalid time work", record != null && result != null && completion != null &&
                    record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null && record.scope == retainedScope &&
                    record.id == id && record.eventId.value == row[2] && result.record == record.copy(protectedEvidence = null) &&
                    completion.result == result && completion.receipt.matches(result))
            }
            val closed = metadata(store.readableDatabase, "title_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)
            for (row in closed) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val completion = store.readCompletion(retainedScope, MutationId(row[1]))
                assertTrue("The calendar retains an unfinished or invalid title attempt", completion != null &&
                        completion.result.record.scope == retainedScope && completion.result.record.id.value == row[1] &&
                        completion.result.event.eventId.value == row[2])
            }
            for (row in metadata(store.readableDatabase, "title_update_preparation_reservations", listOf("generation", "mutation", "event"), scopeArgs)) {
                val reservation = store.readPreparationReservation(scope.copy(generation = row[0].toLong()), EventId(row[2]))
                assertTrue("The calendar retains a bare or mismatched title reservation", row in closed && reservation == null)
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
            if (calendars.moveToFirst()) app.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND (${Events.DIRTY} IS NULL OR ${Events.DIRTY}!=0 OR ${Events.DELETED} IS NULL OR " +
                    "${Events.DELETED}!=0 OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID}='' OR ${Events.SYNC_DATA4} IS NULL)",
                arrayOf(calendars.getLong(0).toString()), null)!!.use { rows ->
                assertTrue("The calendar contains another new, dirty, deleted or unowned native row", !rows.moveToFirst())
            }

        }
    }

    /** Only bounded metadata is read; no pending event/payload is decoded or resumed. */
    private fun metadata(db: SQLiteDatabase, table: String, columns: List<String>, args: Array<String>): List<List<String>> {
        val selected = columns.joinToString(",") {
            val type = if (it == "generation") "integer" else "text"
            "CASE WHEN typeof($it)='$type' AND length(CAST($it AS BLOB)) BETWEEN 1 AND 4096 THEN CAST($it AS TEXT) END"
        }
        return db.rawQuery("SELECT $selected FROM $table WHERE account=? AND calendar=? LIMIT 513", args).use { cursor ->
            assertTrue("The enrollment metadata exceeds its bounded audit", cursor.count <= 512)
            buildList { while (cursor.moveToNext()) add(columns.indices.map {
                assertTrue("The enrollment metadata is incomplete", !cursor.isNull(it)); cursor.getString(it)
            }) }
        }
    }

    private fun historyIds(app: Context, scope: CalendarScope, event: EventId): List<MutationId> =
        TitleUpdateAttemptStore(app).use { shared ->
            shared.readableDatabase.rawQuery("SELECT CASE WHEN typeof(mutation)='text' AND " +
                "length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN mutation END FROM time_update_attempts " +
                "WHERE account=? AND calendar=? AND event=? LIMIT 2",
                arrayOf(scope.account.value, scope.calendar.value, event.value)).use { cursor ->
                assertTrue("The fixture must not have duplicate time history", cursor.count <= 1)
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
            val journal = shared.timeJournal
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
        field("time-worker-full-sql-history-v1")
        for (column in listOf("account", "calendar", "generation", "mutation", "event", "state", "capsule", "result", "completion")) {
            val metadata = db.rawQuery("SELECT typeof($column),length(CAST($column AS BLOB)) FROM time_update_attempts WHERE $where", args).use {
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
                val part = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,?) FROM time_update_attempts WHERE $where",
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
        assertTrue("Production generates a native owner and deterministic time request",
            intent.id.value.matches(uuid) && record.id == TimeUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                record.scope == scope && intent.scope == scope && intent.base.eventId == event)
        assertTrue("Bind the exact original revision and Provider time edit",
            intent.base.content == original && intent.content == changed && SystemTimeEditProfile.accepts(intent) &&
                intent.base.revision.value == prefs.getString("base_revision", null) &&
                intent.state == LocalEditState.PAUSED && record.preparation.timestamp.nano == 0)
        assertTrue("Keep complete UNKNOWN work, protected response and actual canonical result",
            record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                proof.result.record == record.copy(protectedEvidence = null) && proof.result.event.content == changed &&
                proof.result.event.eventId == event && proof.result.event.revision != intent.base.revision &&
                proof.completion.result == proof.result && proof.completion.receipt.handoff == null &&
                proof.completion.receipt.matches(proof.result))
        ImportStore(app).use { reader ->
            val acknowledgement = reader.timeUpdateAcknowledgement(scope, event, record.id)
            assertTrue("SQL restoration consumes only the completed owner and capsule",
                reader.projectedBaseline(scope, event) == proof.result.event && reader.localEdit(scope, event) == null &&
                    reader.localTimeEditPreparation(scope, event) == null && reader.localTextEditPreparation(scope, event) == null && reader.localEditPreparation(scope, event) == null &&
                    reader.localDelete(scope, event) == null && reader.capturedHandoff(scope, event) == null &&
                    acknowledgement != null && acknowledgement.state == TimeUpdateAcknowledgementState.RESTORED &&
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
            putBoolean("system_time_native_current_projection_valid", currentProjectionMarker)
            putBoolean("system_time_native_texts_match", textsMatch(native, changed))
            putBoolean("system_time_native_ordinary", ordinary(native))
            putBoolean("system_time_native_time_match", nativeTimeMatches(changed.time, native))
            putBoolean("system_time_native_start_match", native[Events.DTSTART] == prefs.getLong("start", -1).toString())
            putBoolean("system_time_native_end_match", native[Events.DTEND] == prefs.getLong("end", -1).toString())
            putBoolean("system_time_native_zone_match", native[Events.EVENT_TIMEZONE] == prefs.getString("zone", null))
            putBoolean("system_time_native_end_zone_match", native[Events.EVENT_END_TIMEZONE] == prefs.getString("end_zone", null))
            putBoolean("system_time_native_revision_match", native[Events.SYNC_DATA2] == digest("revision-v1", proof.result.event.revision.value))
            putBoolean("system_time_native_ack_nonce_match", native[Events.SYNC_DATA3] ==
                "calendeer-projection-receipt-v1:" + proof.completion.receipt.projection.nonce)
        })
        assertTrue("The same ordinary event is clean with exact texts and changed dates",
            textsMatch(native, changed) && native[Events.DIRTY] == "0" && native[Events.DELETED] == "0" &&
                ordinary(native) && nativeTimeMatches(changed.time, native) &&
                native[Events.DTSTART] == prefs.getLong("start", -1).toString() &&
                native[Events.DTEND] == prefs.getLong("end", -1).toString() &&
                native[Events.EVENT_TIMEZONE] == prefs.getString("zone", null) &&
                native[Events.EVENT_END_TIMEZONE] == prefs.getString("end_zone", null) &&
                native[Events.SYNC_DATA2] == digest("revision-v1", proof.result.event.revision.value) &&
                currentProjectionMarker)
    }

    private fun textsMatch(values: Map<String, String?>, content: EventContent) =
        values[Events.TITLE] == content.title && values[Events.DESCRIPTION].orEmpty() == content.description &&
            values[Events.EVENT_LOCATION].orEmpty() == content.location

    private fun nativeTimeMatches(time: EventTime, values: Map<String, String?>) = TitleJourneyTimeProfile.nativeMatches(time,
        values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND], values[Events.EVENT_TIMEZONE], values[Events.EVENT_END_TIMEZONE])

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

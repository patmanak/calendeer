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
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
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

/** Enrolls the previously qualified synthetic text event in the ordinary production mirror.
 * An external runner edits description/location using the real calendar editor. After launch,
 * this fixture only observes production jobs and their complete history; it never calls an
 * outgoing preparation, send, inspection, acknowledgement or manual worker entry point.
 * Periodic jobs may contribute, so this does not establish exclusive observer causality.
 * RUN and REPLAY restore all temporarily changed mode flags. Explicit isolation may temporarily
 * make other accessible calendars of the same verified account read-only, with frozen provenance.
 * RECOVER qualifies automatic completion of the already saved editor change, not a fresh editor
 * journey. It neither reconstructs an intent nor sends or acknowledges work directly.
 * The event, native row and immutable production history remain available for inspection.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSystemTextWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemEditorTextRunsThroughProductionWorkersAndStaysClosedAfterRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemTextWorker") == "true")
        val phase = args.getString("textWorkerPhase").orEmpty()
        val token = args.getString("textFixtureToken").orEmpty()
        val accountHash = args.getString("textAccountHash").orEmpty()
        val editorPackage = args.getString("editorPackage").orEmpty()
        assertTrue("Explicit phase, fixture and account are required", phase in setOf("RUN", "RECOVER", "REPLAY") &&
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue("RUN requires an explicit calendar editor package", phase != "RUN" ||
            editorPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let { it.authenticated && it.selections.isNotEmpty() && !it.busy } }
        assertTrue("The real text controllers must be composed for worker qualification", app.systemCreates.state.value.textSupportAvailable)
        val sourcePrefix = "calendar-text-guarded-v1-$token"
        val prefix = "calendar-text-worker-v1-$token"
        val source = app.getSharedPreferences(sourcePrefix, Context.MODE_PRIVATE)
        assertTrue("The separate service journey must already be complete", source.getString("token", null) == token &&
            source.getString("account_hash", null) == accountHash && source.getBoolean("recover_completed", false) &&
            source.getString("journey_label", null) == sourcePrefix &&
            source.getString("source_calendar_name", null) == "Calendeer QA Automatic Worker")
        fun sourceValue(key: String) = checkNotNull(source.getString(key, null)) { "Missing enrolled source field" }
        val target = CalendarScope(AccountId(sourceValue("account")), CalendarId(sourceValue("calendar")), source.getLong("generation", -1))
        val event = EventId(sourceValue("event"))
        val timeProfile = TitleJourneyTimeProfile.create(null, false, false, null)
        assertTrue("Preserve the exact enrolled timed profile", timeProfile.matchesFrozen(source.getInt("content_profile", -1),
            source.getString("start", null), source.getString("end", null)))
        fun canonical(value: String) = value.replace("\r\n", "\n")
        val originalContent = EventContent(canonical(sourceValue("changed_title")), timeProfile.time,
            canonical(sourceValue("changed_description")), canonical(sourceValue("changed_location")), details = EventDetails())
        val editDescription = "Calendeer QA automatic notes $token"
        val editLocation = "Calendeer QA automatic room $token"
        val expectedContent = originalContent.copy(description = editDescription, location = editLocation)
        assertTrue("Require the exact initial-generation synthetic source", target.generation == 0L &&
            originalContent.title == "Calendeer QA — text UPDATE $token\nUnicode é")
        assertTrue("Authorize the enrolled account before any mutation",
            ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == target && it.enabled && it.available &&
                it.summary.title == "Calendeer QA Automatic Worker" } == 1 }
        assertTrue("The dedicated source calendar remains accessible", accessible())
        val factual: (CalendarScope) -> Boolean = { it == target && accessible() }
        val sourceProof = TitleUpdateAttemptStore(app, "$sourcePrefix-shared-attempts.db").use { shared ->
            val journal = shared.textJournal
            val id = MutationId(sourceValue("attempt_id"))
            val record = journal.read(target, id, factual)
            val result = journal.readVerified(target, id, factual)
            val completion = journal.readCompletion(target, id, factual)
            assertTrue("Bind the independent completed service proof before enrolling production", record != null &&
                result != null && completion != null && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN &&
                record.protectedEvidence != null && result.record == record.copy(protectedEvidence = null) &&
                result.event.eventId == event && result.event.content == originalContent &&
                completion.result == result && completion.receipt.matches(result))
            checkNotNull(result)
        }
        val pinnedFormat = sourceProof.record.preparation.intent.format
        assertTrue("The complete source proof uses the current event observation format",
            sourceProof.record.scope == target && sourceProof.record.eventId == event &&
                pinnedFormat == ProtonAuthentication.events(app).format)
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permissions are required", projection.hasPermissions())
        projection.admitOperation = ::accessible
        fun selection() = app.imports.state.value.selections.single { it.scope == target }
        fun modes(): List<Boolean> = SystemCreateWorkStore(app).use {
            listOf(it.modeEnabled(target), it.titlesEnabled(target), it.textsEnabled(target))
        }
        fun originalModes() = listOf(prefs.getBoolean("previous_create", false),
            prefs.getBoolean("previous_titles", false), prefs.getBoolean("previous_texts", false))
        fun otherCalendarsReadOnly() = SystemCreateWorkStore(app).use { store ->
            app.imports.state.value.selections.filter { it.scope != target }.all {
                !store.modeEnabled(it.scope) && !store.titlesEnabled(it.scope) && !store.textsEnabled(it.scope)
            }
        }
        data class OtherModes(val scope: CalendarScope, val flags: List<Boolean>)
        val isolateOthers = args.getString("isolateOtherModes") == "true"
        val initialSelections = app.imports.state.value.selections.toList()
        assertTrue("Bound the enrolled selection set without duplicate scopes", initialSelections.size <= 128 &&
            initialSelections.map { it.scope }.distinct().size == initialSelections.size)
        assertTrue("Isolation never changes another account", initialSelections.all { it.scope.account == target.account })
        // This historical fixture saves three flags. Refuse a fourth-mode preference rather
        // than clearing time consent through a temporary CREATE disable that it cannot restore.
        SystemCreateWorkStore(app).use { store ->
            assertTrue("The historical text fixture cannot own time consent",
                initialSelections.none { store.timesEnabled(it.scope) })
        }
        val others = SystemCreateWorkStore(app).use { store ->
            initialSelections.filter { it.scope != target }.sortedWith(compareBy({ it.scope.calendar.value }, { it.scope.generation }))
                .map { selected -> OtherModes(selected.scope, listOf(store.modeEnabled(selected.scope),
                    store.titlesEnabled(selected.scope), store.textsEnabled(selected.scope))) }
        }
        assertTrue("Writable other scopes require explicit temporary isolation", isolateOthers || others.none { it.flags.any { flag -> flag } })
        assertTrue("Every enabled mode must be restorable through the public controller", others.all { saved ->
            saved.flags.none { it } || (saved.flags[0] && initialSelections.single { it.scope == saved.scope }.let { it.enabled && it.available })
        })
        val isolation = app.getSharedPreferences("$prefix-mode-restoration", Context.MODE_PRIVATE)
        val provenance = linkedMapOf<String, Any>("schema" to 1, "token" to token, "account_hash" to accountHash,
            "account" to target.account.value, "target_calendar" to target.calendar.value,
            "target_generation" to target.generation, "count" to others.size)
        others.forEachIndexed { index, saved ->
            provenance["calendar_$index"] = saved.scope.calendar.value
            provenance["generation_$index"] = saved.scope.generation
            provenance["create_$index"] = saved.flags[0]
            provenance["titles_$index"] = saved.flags[1]
            provenance["texts_$index"] = saved.flags[2]
        }
        assertTrue("A retry preserves the complete original isolation provenance", isolation.all.isEmpty() || isolation.all == provenance)
        fun currentOtherSelection(saved: OtherModes): ImportStore.Selection {
            assertTrue("Restoration requires the same active authorized session", accessible())
            return app.imports.state.value.selections.single { it.scope == saved.scope }.also {
                assertTrue("Enabled modes require a still-accessible selection", saved.flags.none { flag -> flag } || (it.enabled && it.available))
            }
        }
        fun otherModesMatchOriginal() = SystemCreateWorkStore(app).use { store -> others.all {
            listOf(store.modeEnabled(it.scope), store.titlesEnabled(it.scope), store.textsEnabled(it.scope)) == it.flags
        } }
        fun sameSelectionScopes() = app.imports.state.value.selections.map { it.scope }.toSet() == initialSelections.map { it.scope }.toSet()
        fun freezeOtherModes() {
            assertTrue("Keep the verified selection set before isolation", accessible() && sameSelectionScopes() && otherModesMatchOriginal())
            if (isolation.all.isEmpty()) {
                val editor = isolation.edit()
                provenance.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> error("Unsupported restoration field")
                } }
                assertTrue("Persist every original flag before any isolation setter", editor.commit())
            }
            assertTrue("Restoration provenance is frozen exactly", isolation.all == provenance)
        }
        val manager = WorkManager.getInstance(app)
        fun networkWork() = manager.getWorkInfosForUniqueWork(NETWORK_WORK).get()
        var periodicRunningObserved = false
        fun periodicWork(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(PERIODIC_WORK).get().also {
            if (it.any { work -> work.state == WorkInfo.State.RUNNING }) periodicRunningObserved = true
        }
        suspend fun idleQueues() = withTimeout(240_000) {
            while (true) {
                assertTrue("Keep the enrolled session and unrelated read-only scopes", accessible() && sameSelectionScopes() && otherCalendarsReadOnly())
                val local = manager.getWorkInfosForUniqueWork(LOCAL_WORK).get()
                val periodic = periodicWork()
                if (networkWork().all { it.state.isFinished } && local.all { it.state.isFinished } &&
                    periodic.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                    !app.imports.state.value.busy && !app.systemCreates.state.value.busy) break
                delay(500)
            }
        }
        suspend fun awaitIncomingBeforeRecovery() = withTimeout(240_000) {
            var idleSamples = 0
            while (idleSamples < 2) {
                // Isolation has not started: retain the original policy, including writable
                // unrelated scopes, while existing ordinary incoming jobs finish by themselves.
                assertTrue("Keep the authorized session, scope set and original policy during passive recovery wait",
                    accessible() && sameSelectionScopes() && modes() == originalModes() && otherModesMatchOriginal() &&
                        isolation.all == provenance)
                val local = manager.getWorkInfosForUniqueWork(LOCAL_WORK).get()
                val periodic = periodicWork()
                val idle = networkWork().all { it.state.isFinished } && local.all { it.state.isFinished } &&
                    periodic.none { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } &&
                    !app.imports.state.value.busy && !app.systemCreates.state.value.busy
                idleSamples = if (idle) idleSamples + 1 else 0
                if (idleSamples < 2) delay(500)
            }
        }
        suspend fun baseline() = ImportStore(app).use { it.projectedBaseline(target, event) }
        suspend fun noOwner() = ImportStore(app).use {
            it.localEdit(target, event) == null && it.localDelete(target, event) == null &&
                it.localEditPreparation(target, event) == null && it.localTextEditPreparation(target, event) == null &&
                it.capturedHandoff(target, event) == null
        }
        fun frozenIdentity() = prefs.getString("token", null) == token && prefs.getString("account_hash", null) == accountHash &&
            prefs.getString("account", null) == target.account.value && prefs.getString("calendar", null) == target.calendar.value &&
            prefs.getLong("generation", -1) == target.generation && prefs.getString("event", null) == event.value &&
            prefs.getString("edit_description", null) == editDescription && prefs.getString("edit_location", null) == editLocation &&
            prefs.getLong("start", -1) == timeProfile.startMillis && prefs.getLong("end", -1) == timeProfile.endMillis
        suspend fun restoreModes() {
            val before = originalModes()
            compose.runOnUiThread {
                val selected = selection()
                app.systemCreates.setEnabled(selected, before[0])
                app.systemCreates.setTitlesEnabled(selected, before[1])
                app.systemCreates.setTextsEnabled(selected, before[2])
            }
            assertTrue("Restore all three original target flags exactly", modes() == before)
            status("system_text_modes_restored")
        }
        var recoveryComplete: ObservedProof? = null
        suspend fun recoveryDiagnostics() {
            ImportStore(app).use { store ->
                val db = store.readableDatabase
                fun exists(table: String, targetEvent: Boolean, pending: Boolean): Boolean = db.rawQuery(
                    "SELECT 1 FROM $table WHERE scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?) " +
                        "AND event${if (targetEvent) "=" else "!="}?${if (pending) " AND pending!=0" else ""} LIMIT 1",
                    arrayOf(target.account.value, target.calendar.value, event.value)).use { it.moveToFirst() }
                status("system_text_recovery_receipt_target", exists("projection_receipts", true, false))
                status("system_text_recovery_receipt_other", exists("projection_receipts", false, false))
                status("system_text_recovery_pending_target", exists("records", true, true))
                status("system_text_recovery_pending_other", exists("records", false, true))
                val receipt = store.projectionReceipt(target, event)
                val base = store.projectedBaseline(target, event)
                status("system_text_recovery_receipt_change_equals_base", receipt != null && base != null && receipt.change == base)
                status("system_text_recovery_receipt_previous_equals_base", receipt != null && base != null && receipt.previousBase == base)
                val id = historyIds(app, target, event).singleOrNull()
                val verified = if (id == null) null else TitleUpdateAttemptStore(app).use { it.textJournal.readVerified(target, id, factual) }
                status("system_text_recovery_verified_result_present", verified != null)
                status("system_text_recovery_receipt_change_equals_result", receipt != null && verified != null && receipt.change == verified.event)
            }
        }
        suspend fun recoveryWindow(): RecoveryWindow? {
            assertTrue("Recovery only resumes the same unfinished editor fixture", frozenIdentity() &&
                prefs.getBoolean("editor_requested", false) && !prefs.getBoolean("worker_verified", false) &&
                modes() == originalModes() && isolation.all == provenance && prefs.getLong("row", -1) > 0 &&
                prefs.getLong("native_calendar", -1) > 0 && prefs.getInt("run_pid", -1) > 0)
            assertNoTitleHistory(app, target, event)
            // Boolean diagnostics only; never print event data, identifiers, capsules or SQL rows.
            recoveryDiagnostics()
            val complete = readProof(app, target, event, factual)
            if (complete != null) {
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, complete)
                assertScopeQuiescent(app, projection, target)
                recoveryComplete?.let { previous -> assertTrue("An already completed recovery proof is immutable",
                    previous.record == complete.record && previous.result == complete.result &&
                        previous.completion == complete.completion && previous.digest == complete.digest) }
                recoveryComplete = complete
                return null
            }
            val native = nativeValues(app, projection, target, event, prefs.getLong("row", -1), prefs.getLong("native_calendar", -1))
            assertTrue("Recovery requires exactly the saved editor change on the original dirty row",
                native[Events.DIRTY] == "1" && native[Events.DELETED] == "0" && ordinary(native) &&
                    textsMatch(native, expectedContent) && nativeTimeMatches(originalContent.time, native) &&
                    native[Events.SYNC_DATA2] == digest("revision-v1", checkNotNull(prefs.getString("base_revision", null))))
            val base = checkNotNull(baseline())
            assertTrue("Recovery cannot adopt another projected baseline", base.eventId == event && base.content == originalContent &&
                base.revision.value == prefs.getString("base_revision", null))
            fun matches(intent: LocalEditIntent) = intent.id.value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) &&
                intent.scope == target && intent.base == base &&
                intent.content == expectedContent && intent.state == LocalEditState.PAUSED && SystemTextEditProfile.accepts(intent)
            val owner = ImportStore(app).use { store ->
                assertTrue("Recovery cannot borrow another local path", store.localDelete(target, event) == null &&
                    store.localEditPreparation(target, event) == null && store.capturedHandoff(target, event) == null &&
                    store.textUpdateAcknowledgement(target, event) == null)
                store.localEdit(target, event).also { if (it != null) assertTrue("Keep the exact raw editor owner", matches(it)) }
            }
            val preparation = ImportStore(app).use { it.localTextEditPreparation(target, event) }
            if (preparation != null) assertTrue("The retained capsule belongs to the exact owner", owner != null &&
                preparation.intent == owner && matches(preparation.intent) && preparation.timestamp.nano == 0)
            val redundantReceipt = ImportStore(app).use { store ->
                val receipt = store.projectionReceipt(target, event)
                val observation = store.transaction(target) { Triple(checkpoint, remote(event), (this as StagedSyncTransaction).deferred(event)) }
                val exactScope = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?) AND event=?"
                val identity = arrayOf(target.account.value, target.calendar.value, target.generation.toString(), event.value)
                val directBase = store.readableDatabase.rawQuery("SELECT representation IS NULL FROM projection_bases WHERE $exactScope", identity).use {
                    it.count == 1 && it.moveToFirst() && it.getInt(0) == 1
                }
                val noOtherProvenance = listOf("captured_create_bases", "captured_create_acknowledgements", "captured_create_handoffs").all { table ->
                    store.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE $exactScope LIMIT 1", identity).use { !it.moveToFirst() }
                }
                val redundant = receipt != null && receipt.scope == target && receipt.protocol == 1 &&
                    receipt.change == base && receipt.previousBase == base && receipt.representation == null &&
                    receipt.previousRepresentation == null && receipt.format == pinnedFormat &&
                    pinnedFormat == ProtonAuthentication.events(app).format &&
                    (observation.first == null || (observation.first?.scope == target && observation.first?.format == receipt.format)) &&
                    observation.second == base && observation.third == null &&
                    (owner == null || owner.format == receipt.format) && directBase && noOtherProvenance
                mapOf("scope" to (receipt?.scope == target), "protocol" to (receipt?.protocol == 1),
                    "representation_absent" to (receipt?.representation == null),
                    "previous_representation_absent" to (receipt?.previousRepresentation == null),
                    "checkpoint_scope" to (observation.first?.scope == target),
                    "checkpoint_format" to (observation.first?.format == receipt?.format),
                    "checkpoint_absent" to (observation.first == null),
                    "pinned_source_and_live_format" to (receipt?.format == pinnedFormat && pinnedFormat == ProtonAuthentication.events(app).format),
                    "cache_equals_base" to (observation.second == base), "deferred_absent" to (observation.third == null),
                    "owner_format" to (owner == null || owner.format == receipt?.format),
                    "base_direct" to directBase, "no_create_provenance" to noOtherProvenance).forEach {
                    (name, valid) -> status("system_text_recovery_receipt_$name", valid)
                }
                status("system_text_recovery_receipt_redundant_exact", redundant)
                assertTrue("Only an exactly redundant direct projection may accompany recovery", receipt == null || redundant)
                receipt
            }
            val id = historyIds(app, target, event).singleOrNull()
            val record = if (id == null) null else TitleUpdateAttemptStore(app).use { store ->
                val journal = store.textJournal
                val retained = checkNotNull(journal.read(target, id, factual))
                assertTrue("Never substitute or retry a different prepared or uncertain request",
                    retained.scope == target && retained.eventId == event && retained.id == id &&
                        retained.id == TextUpdateAttemptIdentity.requestId(retained.preparation, retained.format) &&
                        preparation != null && samePreparation(retained.preparation, preparation) &&
                        retained.state in setOf(TextUpdateAttemptState.PREPARED, TextUpdateAttemptState.OUTCOME_UNKNOWN))
                val result = journal.readVerified(target, id, factual)
                if (result != null) assertTrue("An existing proof must describe the exact editor contents",
                    result.record == retained.copy(protectedEvidence = null) && result.event.eventId == event &&
                        result.event.content == expectedContent && result.event.revision != base.revision)
                assertTrue("Incomplete recovery cannot borrow another completion", journal.readCompletion(target, id, factual) == null)
                retained
            }
            return RecoveryWindow(event, prefs.getLong("row", -1), owner, preparation, record, redundantReceipt, pinnedFormat).also {
                assertScopeQuiescent(app, projection, target, it)
            }
        }
        var restore = false
        var restoreOthers = false
        var recovery: RecoveryWindow? = null
        try {
            // Validate phase and original policy before changing any other scope.
            if (phase == "RUN") {
                assertTrue("A previously opened editor cannot be enrolled again", !prefs.getBoolean("editor_requested", false) &&
                    !prefs.getBoolean("worker_verified", false))
                if (prefs.contains("token")) assertTrue("Retry preserves the original target policy", frozenIdentity() && modes() == originalModes())
                assertTrue("No production text history already belongs to the event", historyIds(app, target, event).isEmpty())
                assertNoTitleHistory(app, target, event)
            } else if (phase == "RECOVER") {
                assertTrue("Only the saved unfinished fixture may enter the passive recovery wait", frozenIdentity() &&
                    prefs.getBoolean("editor_requested", false) && !prefs.getBoolean("worker_verified", false))
                awaitIncomingBeforeRecovery()
                recovery = recoveryWindow()
            } else {
                assertTrue("Restart requires the original completed fixture and restored policy", frozenIdentity() &&
                    prefs.getBoolean("editor_requested", false) && prefs.getBoolean("worker_verified", false) &&
                    prefs.getInt("run_pid", -1) > 0 &&
                    prefs.getInt("recovery_pid", prefs.getInt("run_pid", -1)) != Process.myPid() && modes() == originalModes())
            }
            if (isolateOthers) {
                freezeOtherModes()
                restoreOthers = true
                compose.runOnUiThread {
                    others.filter { it.flags.any { flag -> flag } }.forEach { saved ->
                        app.systemCreates.setEnabled(currentOtherSelection(saved), false)
                    }
                }
            }
            assertTrue("Only the target may become writable during this journey", sameSelectionScopes() && otherCalendarsReadOnly())
            if (phase == "RUN") {
                assertTrue("A previously opened editor cannot be replayed as a fresh RUN", !prefs.getBoolean("editor_requested", false) &&
                    !prefs.getBoolean("worker_verified", false))
                if (prefs.contains("token")) assertTrue("Never replace an enrolled pre-editor fixture",
                    frozenIdentity() && modes() == originalModes())
                assertTrue("No production text history already belongs to the event", historyIds(app, target, event).isEmpty())
                assertNoTitleHistory(app, target, event)
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                // Only pre-editor setup may request a normal incoming sync.
                app.imports.requestSync()
                withTimeout(240_000) {
                    while (true) {
                        assertTrue("Keep source access while importing", accessible())
                        val base = baseline()
                        if (base?.content == originalContent && noOwner() &&
                            projection.inspectOwnedEvent(target, event, base.revision) != null) break
                        delay(500)
                    }
                }
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                val base = checkNotNull(baseline())
                assertTrue("Only the canonical service result can be the editor baseline", base.content == originalContent && noOwner())
                val owned = checkNotNull(projection.inspectOwnedEvent(target, event, base.revision))
                val clean = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                assertTrue("Start from the exact clean ordinary row", clean[Events.DIRTY] == "0" && clean[Events.DELETED] == "0" &&
                    ordinary(clean) && textsMatch(clean, originalContent) && nativeTimeMatches(originalContent.time, clean))
                if (prefs.contains("token")) assertTrue("A pre-editor retry preserves row and revision",
                    prefs.getLong("row", -1) == owned.eventRow && prefs.getLong("native_calendar", -1) == owned.calendarRow &&
                        prefs.getString("base_revision", null) == base.revision.value)
                else {
                    val before = modes()
                    assertTrue("Freeze original flags and full fixture identity before activation", prefs.edit()
                        .putString("token", token).putString("account_hash", accountHash)
                        .putString("account", target.account.value).putString("calendar", target.calendar.value)
                        .putLong("generation", target.generation).putString("event", event.value)
                        .putString("base_revision", base.revision.value).putString("edit_description", editDescription)
                        .putString("edit_location", editLocation).putLong("row", owned.eventRow)
                        .putLong("native_calendar", owned.calendarRow).putLong("start", timeProfile.startMillis)
                        .putLong("end", timeProfile.endMillis).putBoolean("previous_create", before[0])
                        .putBoolean("previous_titles", before[1]).putBoolean("previous_texts", before[2]).commit())
                }
                restore = true
                compose.runOnUiThread {
                    val selected = selection()
                    app.systemCreates.setEnabled(selected, true)
                    app.systemCreates.setTitlesEnabled(selected, false)
                    app.systemCreates.setTextsEnabled(selected, true)
                }
                assertTrue("Only CREATE plus text consent is enabled for the target", modes() == listOf(true, false, true))
                assertTrue("Configure the existing production calendar access", app.imports.configureSystemCalendar(target))
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                assertTrue("No intent or history may precede the external editor", baseline() == base && noOwner() &&
                    historyIds(app, target, event).isEmpty())
                assertNoTitleHistory(app, target, event)
                val beforeEditor = nativeValues(app, projection, target, event, owned.eventRow, owned.calendarRow)
                assertTrue("The editor must receive the original clean row", beforeEditor[Events.DIRTY] == "0" &&
                    beforeEditor[Events.DELETED] == "0" && textsMatch(beforeEditor, originalContent) &&
                    ordinary(beforeEditor) && nativeTimeMatches(originalContent.time, beforeEditor))
                val beforeIds = networkWork().map { it.id.toString() }.toSet()
                val beforePeriodic = periodicWork().map { it.id.toString() }.toSet()
                assertTrue("Freeze queue identities before handing control to the external editor", prefs.edit()
                    .putStringSet("previous_network_work", beforeIds).putStringSet("previous_periodic_work", beforePeriodic)
                    .putBoolean("editor_requested", true).putInt("run_pid", Process.myPid()).commit())
                app.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = ContentUris.withAppendedId(Events.CONTENT_URI, owned.eventRow)
                    setPackage(editorPackage); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeProfile.startMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, timeProfile.endMillis)
                })
                status("system_text_editor_launched")
                // Observation only until evidence is complete. No request/run/process/recovery here.
                var completed: ObservedProof? = null
                withTimeout(300_000) {
                    while (true) {
                        assertTrue("Keep exact consent and scope while observing", accessible() &&
                            modes() == listOf(true, false, true) && sameSelectionScopes() && otherCalendarsReadOnly())
                        val proof = readProof(app, target, event, factual)
                        periodicWork()
                        val work = networkWork().filter { it.id.toString() !in beforeIds }
                        if (proof != null && work.isNotEmpty() && work.all { it.state == WorkInfo.State.SUCCEEDED } &&
                            !app.imports.state.value.busy && !app.systemCreates.state.value.busy) {
                            completed = proof; break
                        }
                        assertTrue("The automatically queued network work must not fail",
                            work.none { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED })
                        delay(500)
                    }
                }
                val observed = checkNotNull(completed)
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                val proof = checkNotNull(readProof(app, target, event, factual))
                assertTrue("Keep the exact completed proof after every queue converges",
                    proof.record == observed.record && proof.result == observed.result &&
                        proof.completion == observed.completion && proof.digest == observed.digest)
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertNoTitleHistory(app, target, event)
                assertTrue("Freeze immutable production history for restart", prefs.edit()
                    .putString("attempt", proof.record.id.value).putString("intent", proof.record.preparation.intent.id.value)
                    .putString("history_digest", proof.digest).putBoolean("worker_verified", true).commit())
                status("system_text_worker_completed")
                status("system_text_same_row_acknowledged")
                status("system_text_automatic_pipeline_qualified")
                status("system_text_periodic_running_observed", periodicRunningObserved)
            } else if (phase == "RECOVER") {
                // No new fixture, editor launch, incoming sync or manual outgoing invocation.
                // Requalify after isolating unrelated scopes, before activating automatic jobs.
                val current = recoveryWindow()
                assertTrue("Isolation cannot substitute the enrolled recovery state", sameRecovery(recovery, current))
                restore = true
                compose.runOnUiThread {
                    val selected = selection()
                    app.systemCreates.setEnabled(selected, true)
                    app.systemCreates.setTitlesEnabled(selected, false)
                    app.systemCreates.setTextsEnabled(selected, true)
                }
                assertTrue("Recovery activates only the existing target text policy", modes() == listOf(true, false, true))
                var completed: ObservedProof? = null
                withTimeout(300_000) {
                    while (true) {
                        assertTrue("Keep recovery consent, account and isolation", accessible() && sameSelectionScopes() &&
                            otherCalendarsReadOnly() && modes() == listOf(true, false, true))
                        completed = readProof(app, target, event, factual)
                        if (completed != null && !app.imports.state.value.busy && !app.systemCreates.state.value.busy) break
                        delay(500)
                    }
                }
                val observed = checkNotNull(completed)
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                val proof = checkNotNull(readProof(app, target, event, factual))
                assertTrue("Recovery freezes only the exact converged completed proof", proof.record == observed.record &&
                    proof.result == observed.result && proof.completion == observed.completion && proof.digest == observed.digest)
                recoveryComplete?.let { previous -> assertTrue("Activation cannot replace an already completed proof",
                    previous.record == proof.record && previous.result == proof.result &&
                        previous.completion == proof.completion && previous.digest == proof.digest) }
                recovery?.owner?.let { assertTrue("Recovery preserves the captured raw owner", proof.record.preparation.intent == it) }
                recovery?.preparation?.let { assertTrue("Recovery preserves the original capsule", samePreparation(proof.record.preparation, it)) }
                recovery?.record?.let { assertTrue("Recovery preserves the first request without replacing uncertain work",
                    proof.record.copy(state = it.state, protectedEvidence = it.protectedEvidence) == it &&
                        (it.protectedEvidence == null || proof.record.protectedEvidence == it.protectedEvidence)) }
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertNoTitleHistory(app, target, event)
                assertTrue("Freeze recovery history without replacing the original editor enrollment", prefs.edit()
                    .putString("attempt", proof.record.id.value).putString("intent", proof.record.preparation.intent.id.value)
                    .putString("history_digest", proof.digest).putBoolean("worker_verified", true)
                    .putBoolean("automatic_recovery_qualified", true).putInt("recovery_pid", Process.myPid()).commit())
                status("system_text_automatic_recovery_qualified")
                status("system_text_same_row_acknowledged")
            } else {
                assertTrue("Replay requires the complete original editor journey", frozenIdentity() &&
                    prefs.getBoolean("editor_requested", false) && prefs.getBoolean("worker_verified", false) &&
                    prefs.getInt("run_pid", -1) > 0 &&
                    prefs.getInt("recovery_pid", prefs.getInt("run_pid", -1)) != Process.myPid())
                assertTrue("RUN must already have restored its original mode flags", modes() == originalModes())
                restore = true
                // Re-enable the same scoped policy so restart is tested with active production
                // controllers, not merely an inert disabled queue. No manual processing follows.
                compose.runOnUiThread {
                    val selected = selection()
                    app.systemCreates.setEnabled(selected, true)
                    app.systemCreates.setTitlesEnabled(selected, false)
                    app.systemCreates.setTextsEnabled(selected, true)
                }
                assertTrue("Replay activates only the original target text path", modes() == listOf(true, false, true))
                idleQueues()
                assertScopeQuiescent(app, projection, target)
                val proof = checkNotNull(readProof(app, target, event, factual))
                assertTrue("The full retained production history is immutable",
                    proof.record.id.value == prefs.getString("attempt", null) && proof.digest == prefs.getString("history_digest", null) &&
                        proof.record.preparation.intent.id.value == prefs.getString("intent", null))
                verifyProof(app, projection, prefs, target, event, originalContent, expectedContent, proof)
                assertTrue("Restart cannot create another text attempt", historyIds(app, target, event) == listOf(proof.record.id))
                assertNoTitleHistory(app, target, event)
                status("system_text_immutable_restart"); status("system_text_no_new_attempt")
            }
        } finally {
            try {
                if (restore) restoreModes()
            } finally {
                try {
                    if (restoreOthers) {
                        assertTrue("Restore only the frozen original scopes and policy", isolation.all == provenance && sameSelectionScopes())
                        compose.runOnUiThread {
                            others.filter { it.flags.any { flag -> flag } }.forEach { saved ->
                                val selected = currentOtherSelection(saved)
                                app.systemCreates.setEnabled(selected, saved.flags[0])
                                app.systemCreates.setTitlesEnabled(selected, saved.flags[1])
                                app.systemCreates.setTextsEnabled(selected, saved.flags[2])
                            }
                        }
                        assertTrue("Restore every original other-scope flag exactly, including untouched disabled modes", otherModesMatchOriginal())
                        status("system_text_other_modes_restored")
                    }
                } finally {
                    projection.admitOperation = { false }
                }
            }
        }
    }

    private class ObservedProof(val record: TextUpdateAttemptRecord, val result: TextUpdateVerifiedResult,
        val completion: TextUpdateCompletion, val digest: String)

    private class RecoveryWindow(val event: EventId, val row: Long, val owner: LocalEditIntent?,
        val preparation: LocalTextEditPreparation?, val record: TextUpdateAttemptRecord?, val redundantReceipt: ProjectionReceipt?,
        val pinnedFormat: AdapterFormat)

    private fun samePreparation(a: LocalTextEditPreparation, b: LocalTextEditPreparation) =
        a.intent == b.intent && a.timestamp == b.timestamp && a.format == b.format && a.protectedPayload == b.protectedPayload

    private fun sameRecovery(a: RecoveryWindow?, b: RecoveryWindow?): Boolean {
        if (a == null || b == null) return a == b
        val sameCapsule = if (a.preparation == null || b.preparation == null) a.preparation == b.preparation
            else samePreparation(a.preparation, b.preparation)
        return a.event == b.event && a.row == b.row && a.owner == b.owner && a.record == b.record &&
            a.redundantReceipt == b.redundantReceipt && a.pinnedFormat == b.pinnedFormat && sameCapsule
    }

    private suspend fun assertScopeQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        recovery: RecoveryWindow? = null) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        ImportStore(app).use { store ->
            val inCalendar = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?)"
            for (table in listOf("local_edits", "local_edit_preparations", "local_text_edit_preparations")) {
                val allowed = when (table) {
                    "local_edits" -> recovery?.owner
                    "local_text_edit_preparations" -> recovery?.preparation?.intent
                    else -> null
                }
                val exclusion = if (allowed == null) "" else " AND NOT(scope IN (SELECT scope FROM calendars " +
                    "WHERE account=? AND calendar=? AND generation=?) AND event=? AND mutation=?)"
                val parameters = if (allowed == null) scopeArgs else scopeArgs + arrayOf(scope.account.value,
                    scope.calendar.value, scope.generation.toString(), allowed.base.eventId.value, allowed.id.value)
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar$exclusion LIMIT 1", parameters,
                    message = "The calendar retains another local owner or capsule")
            }
            empty(store.readableDatabase, "SELECT 1 FROM text_update_acknowledgements WHERE $inCalendar AND state!='RESTORED' LIMIT 1",
                message = "The calendar retains an unfinished text acknowledgement")
            val redundant = recovery?.redundantReceipt
            val receiptExclusion = if (redundant == null) "" else " AND NOT(scope IN (SELECT scope FROM calendars " +
                "WHERE account=? AND calendar=? AND generation=?) AND event=? AND nonce=?)"
            val receiptArgs = if (redundant == null) scopeArgs else scopeArgs + arrayOf(scope.account.value,
                scope.calendar.value, scope.generation.toString(), redundant.change.eventId.value, redundant.nonce)
            if (redundant != null) assertTrue("Keep the complete redundant receipt unchanged during preflight",
                store.projectionReceipt(scope, redundant.change.eventId) == redundant)
            empty(store.readableDatabase, "SELECT 1 FROM projection_receipts WHERE $inCalendar$receiptExclusion LIMIT 1", receiptArgs,
                message = "The calendar retains an unfinished projection receipt")
            if (recovery == null) {
                empty(store.readableDatabase, "SELECT 1 FROM records WHERE $inCalendar AND pending!=0 LIMIT 1",
                    message = "The calendar retains a pending incoming projection")
            } else {
                // Only ordinary incoming work may queue behind the interrupted no-change
                // projection. No owner/ACK/request exception is granted to another event.
                val exactScope = "SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?"
                empty(store.readableDatabase, "SELECT 1 FROM records r WHERE $inCalendar AND pending!=0 AND " +
                    "(typeof(pending)!='integer' OR pending!=1 OR scope NOT IN ($exactScope) OR " +
                    "EXISTS(SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event) OR " +
                    "EXISTS(SELECT 1 FROM captured_create_bases b WHERE b.scope=r.scope AND b.event=r.event) OR " +
                    "EXISTS(SELECT 1 FROM captured_create_acknowledgements a WHERE a.scope=r.scope AND a.event=r.event) OR " +
                    "EXISTS(SELECT 1 FROM captured_create_handoffs h WHERE h.scope=r.scope AND h.event=r.event)) LIMIT 1",
                    scopeArgs + arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString()),
                    message = "Recovery cannot resume nonordinary incoming work or another generation")
                val incoming = store.readableDatabase.rawQuery("SELECT CASE WHEN typeof(event)='text' AND " +
                    "length(CAST(event AS BLOB)) BETWEEN 1 AND 4096 THEN event END FROM records WHERE $inCalendar AND pending!=0 LIMIT 513",
                    scopeArgs).use { cursor ->
                    assertTrue("Bound the ordinary incoming recovery audit", cursor.count <= 512)
                    buildList { while (cursor.moveToNext()) {
                        assertTrue("Require valid incoming event metadata", !cursor.isNull(0))
                        add(EventId(cursor.getString(0)))
                    } }
                }
                for (event in incoming) assertTrue("Only supported ordinary incoming snapshots may remain queued",
                    store.transaction(scope) {
                        val observed = remote(event)
                        val currentCheckpoint = checkpoint
                        // A null checkpoint may precede normal staged bootstrap. This permits
                        // observation-only enrollment, never preparation or dispatch authority.
                        val qualifiedCheckpoint = if (currentCheckpoint == null) redundant != null &&
                            redundant.format == recovery.pinnedFormat && recovery.pinnedFormat == ProtonAuthentication.events(app).format
                        else currentCheckpoint.scope == scope && currentCheckpoint.format == recovery.pinnedFormat
                        qualifiedCheckpoint && observed is RemoteChange.Upsert && observed.eventId == event &&
                            observed.content.recurrence == null && ProjectionReceipt.supports(observed) &&
                            (this as StagedSyncTransaction).deferred(event) == null
                    })
            }
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
                if (recovery?.record != null && retainedScope == scope && id == recovery.record.id && row[2] == recovery.event.value) {
                    assertTrue("The only unfinished request must remain the exact qualified recovery record",
                        record == recovery.record && completion == null &&
                            (result == null || result.record == record?.copy(protectedEvidence = null)))
                    continue
                }
                assertTrue("The calendar retains unfinished or invalid text work", record != null && result != null &&
                    completion != null && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN &&
                    record.scope == retainedScope && record.id == id && record.eventId.value == row[2] &&
                    result.record == record.copy(protectedEvidence = null) && completion.result == result &&
                    completion.receipt.matches(result))
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
                    "${Events.DELETED}!=0 OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID}='' OR ${Events.SYNC_DATA4} IS NULL)" +
                    (if (recovery == null) "" else " AND ${Events._ID}!=?"),
                if (recovery == null) arrayOf(calendars.getLong(0).toString())
                else arrayOf(calendars.getLong(0).toString(), recovery.row.toString()), null)!!.use { rows ->
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

    /** No title history or reservation may be borrowed for this disjoint text event. */
    private fun assertNoTitleHistory(app: Context, scope: CalendarScope, event: EventId) {
        TitleUpdateAttemptStore(app).use { shared ->
            for (table in listOf("title_update_attempts", "title_update_preparation_reservations")) {
                shared.readableDatabase.rawQuery("SELECT 1 FROM $table WHERE account=? AND calendar=? AND event=? LIMIT 1",
                    arrayOf(scope.account.value, scope.calendar.value, event.value)).use {
                    assertTrue("The event already has title work or history", !it.moveToFirst())
                }
            }
        }
    }

    private fun historyIds(app: Context, scope: CalendarScope, event: EventId): List<MutationId> =
        TitleUpdateAttemptStore(app).use { shared ->
            shared.readableDatabase.rawQuery("SELECT CASE WHEN typeof(mutation)='text' AND " +
                "length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN mutation END FROM text_update_attempts " +
                "WHERE account=? AND calendar=? AND event=? LIMIT 2",
                arrayOf(scope.account.value, scope.calendar.value, event.value)).use { cursor ->
                assertTrue("The fixture must not have duplicate text history", cursor.count <= 1)
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
            val journal = shared.textJournal
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
        field("text-worker-full-sql-history-v1")
        for (column in listOf("account", "calendar", "generation", "mutation", "event", "state", "capsule", "result", "completion")) {
            val metadata = db.rawQuery("SELECT typeof($column),length(CAST($column AS BLOB)) FROM text_update_attempts WHERE $where", args).use {
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
                val part = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,?) FROM text_update_attempts WHERE $where",
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
        assertTrue("Production generates a native owner and deterministic text request",
            intent.id.value.matches(uuid) && record.id == TextUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                record.scope == scope && intent.scope == scope && intent.base.eventId == event)
        assertTrue("Bind the exact original revision and editor description/location",
            intent.base.content == original && intent.content == changed && SystemTextEditProfile.accepts(intent) &&
                intent.base.revision.value == prefs.getString("base_revision", null) &&
                intent.state == LocalEditState.PAUSED && record.preparation.timestamp.nano == 0)
        assertTrue("Keep complete UNKNOWN work, protected response and actual canonical result",
            record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                proof.result.record == record.copy(protectedEvidence = null) && proof.result.event.content == changed &&
                proof.result.event.eventId == event && proof.result.event.revision != intent.base.revision &&
                proof.completion.result == proof.result && proof.completion.receipt.handoff == null &&
                proof.completion.receipt.matches(proof.result))
        ImportStore(app).use { reader ->
            val acknowledgement = reader.textUpdateAcknowledgement(scope, event, record.id)
            assertTrue("SQL restoration consumes only the completed owner and capsule",
                reader.projectedBaseline(scope, event) == proof.result.event && reader.localEdit(scope, event) == null &&
                    reader.localTextEditPreparation(scope, event) == null && reader.localEditPreparation(scope, event) == null &&
                    reader.localDelete(scope, event) == null && reader.capturedHandoff(scope, event) == null &&
                    acknowledgement != null && acknowledgement.state == TextUpdateAcknowledgementState.RESTORED &&
                    acknowledgement.receipt == proof.completion.receipt)
        }
        val row = prefs.getLong("row", -1); val calendar = prefs.getLong("native_calendar", -1)
        val owned = projection.inspectOwnedEvent(scope, event, proof.result.event.revision)
        assertTrue("The original owned row is unchanged", owned != null && owned.eventRow == row && owned.calendarRow == calendar)
        val native = nativeValues(app, projection, scope, event, row, calendar)
        assertTrue("The same ordinary event is clean with exact texts and unchanged dates",
            textsMatch(native, changed) && native[Events.DIRTY] == "0" && native[Events.DELETED] == "0" &&
                ordinary(native) && nativeTimeMatches(changed.time, native) &&
                native[Events.DTSTART] == prefs.getLong("start", -1).toString() &&
                native[Events.DTEND] == prefs.getLong("end", -1).toString() &&
                native[Events.SYNC_DATA2] == digest("revision-v1", proof.result.event.revision.value) &&
                native[Events.SYNC_DATA3] == "calendeer-projection-receipt-v1:" + proof.completion.receipt.projection.nonce)
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

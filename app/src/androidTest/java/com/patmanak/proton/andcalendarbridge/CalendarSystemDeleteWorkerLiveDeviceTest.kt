// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteProofStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit production worker qualification with one new disposable QA service event.
 * calendarSystemDeleteWorker=true, deleteFixtureToken=hex12, deleteAccountHash=SHA256,
 * deleteWorkerPhase=PREPARE|OFFLINE_CAPTURE|OFFLINE_REPLAY|RUN|REPLAY,
 * deleteWorkerProfile=TIMED|ALL_DAY_SINGLE|ALL_DAY_MULTIPLE. Optional deleteWorkerOffline=true
 * is frozen at PREPARE; its two offline phases require externally disconnected networking.
 * An initially read-only QA scope additionally
 * requires allowReadOnlyDeleteWorker=true and the complete quiescence audit.
 * PREPARE opens the ordinary production mirror
 * event in foundation.e.calendar; an external driver deletes it there before RUN.
 * RUN invokes only consent setters and the normal scheduling trigger. REPLAY must run
 * in a new process. No direct DELETE preparation, capture, submission, inspection,
 * acknowledgement or manual worker entry point is used in the ordinary journey.
 * Opt-in deleteWorkerNetwork=true requires deleteWorkerOffline=true and TIMED. It adds
 * NETWORK_RUN, NETWORK_OFFLINE_REPLAY and NETWORK_RECONNECT. Its sole preparation
 * exception is a read-only authentic preview to bind the socket probe before activation;
 * the preview is never journaled, claimed or submitted. Reconnect may use a diagnostic
 * read-only GET, which grants no proof of worker inspection. The host restores radios.
 * Opt-in deleteWorkerProcess=true requires offline TIMED and excludes the network journey.
 * PROCESS_RUN kills the actual worker process at authenticated request body start;
 * PROCESS_OFFLINE_REPLAY retains UNKNOWN and PROCESS_RECONNECT uses normal scheduling.
 * PROCESS_RESTORE is offline-only compare-owned mode cleanup, without recovery claims.
 * The host separately observes death before disconnecting and restarting the fixture.
 * Process qualification also requires all other selected calendars' five modes disabled.
 * Five target modes have persistent compare-owned restoration. In the ordinary/network
 * profiles other calendars retain consent and may run authorized work. No global traffic claim.
 */
class CalendarSystemDeleteWorkerLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editorDeletionRunsThroughProductionWorkersAndStaysClosedAfterRestart() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemDeleteWorker") == "true")
        val phase = args.getString("deleteWorkerPhase").orEmpty()
        val token = args.getString("deleteFixtureToken").orEmpty()
        val hash = args.getString("deleteAccountHash").orEmpty()
        val allowReadOnly = args.getString("allowReadOnlyDeleteWorker") == "true"
        val profile = args.getString("deleteWorkerProfile") ?: "TIMED"
        val offlineJourney = args.getString("deleteWorkerOffline") == "true"
        val networkJourney = args.getString("deleteWorkerNetwork") == "true"
        val processJourney = args.getString("deleteWorkerProcess") == "true"
        val calendarWitness = WorkerDeleteFixtureScope.witness(args)
        val networkPhase = phase.startsWith("NETWORK_")
        val processPhase = phase.startsWith("PROCESS_")
        val offlinePhase = phase in setOf("OFFLINE_CAPTURE", "OFFLINE_REPLAY", "NETWORK_OFFLINE_REPLAY", "PROCESS_OFFLINE_REPLAY", "PROCESS_RESTORE")
        assertTrue("Explicit fixture, controlled account and phase are required", phase in setOf("PREPARE", "OFFLINE_CAPTURE", "OFFLINE_REPLAY", "RUN", "REPLAY", "NETWORK_RUN", "NETWORK_OFFLINE_REPLAY", "NETWORK_RECONNECT", "PROCESS_RUN", "PROCESS_OFFLINE_REPLAY", "PROCESS_RECONNECT", "PROCESS_RESTORE") &&
            (!processJourney || offlineJourney && profile == "TIMED" && !networkJourney && phase != "RUN") &&
            (!processPhase || processJourney) && (!networkJourney || !processJourney) &&
            (!networkJourney || offlineJourney && profile == "TIMED" && phase != "RUN") && (!networkPhase || networkJourney) &&
            (!offlinePhase || offlineJourney) &&
            token.matches(Regex("[a-f0-9]{12}")) && hash.matches(Regex("[a-f0-9]{64}")) &&
            profile in setOf("TIMED", "ALL_DAY_SINGLE", "ALL_DAY_MULTIPLE"))
        val app = compose.activity.application as BridgeApplication
        if (offlinePhase) assertOffline(app)
        compose.waitUntil(60_000) {
            if (offlinePhase) assertOffline(app)
            app.imports.state.value.let { it.authenticated && it.selections.isNotEmpty() && !it.busy }
        }
        val selected = app.imports.state.value.selections.filter {
            WorkerDeleteFixtureScope.matches(it, calendarWitness)
        }
        assertTrue("Resolve exactly the dedicated QA calendar", selected.size == 1)
        val target = selected.single().scope
        assertTrue("The private source witness uses the initial logical generation", target.generation == 0L)
        assertTrue("Authorize the exact account before mutation", ProtonCreateSubmissionProbe.matchesAccount(app, target.account, hash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.count { it.scope == target && WorkerDeleteFixtureScope.matches(it, calendarWitness) } == 1 }
        val admit: (CalendarScope) -> Boolean = { it == target && accessible() }
        val prefix = "calendar-delete-worker-v1-$token"
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        fun flags() = SystemCreateWorkStore(app).use {
            listOf(it.modeEnabled(target), it.titlesEnabled(target), it.textsEnabled(target), it.timesEnabled(target), it.deletesEnabled(target))
        }
        if (!prefs.contains("token")) {
            assertTrue("Only PREPARE can enroll a new fixture", phase == "PREPARE" && accessible())
            val original = flags()
            assertTrue("Deletion sending must start disabled", !original[4])
            assertTrue("Read-only QA activation needs explicit opt-in and no other edit consent",
                original[0] || (allowReadOnly && original.none { it }))
            if (!original[0]) SystemWorkerFixtureAudit.assertQuiescent(app, AndroidCalendarProjection(app), target)
            val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
            val edit = prefs.edit().putString("token", token).putString("account_hash", hash)
                .putString("profile", profile)
                .putBoolean("offline_journey", offlineJourney)
                .putBoolean("network_journey", networkJourney)
                .putBoolean("process_journey", processJourney).putString("calendar_witness", calendarWitness)
                .putString("account", target.account.value).putString("calendar", target.calendar.value).putLong("generation", target.generation)
                .putBoolean("readonly_activation", !original[0])
                .putString("create_id", UUID.randomUUID().toString())
                .putString("title", "Calendeer QA — disposable worker DELETE $token")
                .putString("start", start.toString()).putString("end", start.plus(1, ChronoUnit.HOURS).toString())
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).putBoolean("modes_restored", true)
            if (profile != "TIMED") {
                val date = start.atZone(ZoneOffset.UTC).toLocalDate()
                edit.putString("civil_start", date.toString())
                    .putString("civil_end", date.plusDays(if (profile == "ALL_DAY_SINGLE") 1 else 3).toString())
            }
            original.forEachIndexed { index, value -> edit.putBoolean("original_$index", value).putBoolean("owned_$index", value) }
            assertTrue("Freeze identities and all five modes before CREATE or any setter", edit.commit())
        }
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        val originalModes = (0..4).map { prefs.getBoolean("original_$it", false) }
        fun ownedModes() = (0..4).map { prefs.getBoolean("owned_$it", false) }
        assertTrue("Never move or replace the enrolled fixture", frozen("token") == token && frozen("account_hash") == hash &&
            prefs.getBoolean("offline_journey", false) == offlineJourney &&
            prefs.getBoolean("network_journey", false) == networkJourney &&
            prefs.getBoolean("process_journey", false) == processJourney &&
            prefs.getString("calendar_witness", "").orEmpty() == calendarWitness &&
            (prefs.getString("profile", null) ?: "TIMED") == profile &&
            frozen("account") == target.account.value && frozen("calendar") == target.calendar.value &&
            prefs.getLong("generation", -1) == target.generation && !originalModes[4] && accessible() &&
            (originalModes[0] || (allowReadOnly && originalModes.none { it } && prefs.getBoolean("readonly_activation", false))))
        val time = if (profile == "TIMED") EventTime.Timed(Instant.parse(frozen("start")),
            Instant.parse(frozen("end")), ZoneOffset.UTC) else EventTime.AllDay(
            LocalDate.parse(frozen("civil_start")), LocalDate.parse(frozen("civil_end")))
        val content = EventContent(frozen("title"), time, details = EventDetails())
        val projection = AndroidCalendarProjection(app)
        projection.admitOperation = ::accessible
        val account = projection.accountFor(target.account)
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun row(): Map<String, String?> = app.contentResolver.query(uri, null, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString()), null)!!.use { cursor ->
            assertTrue("Require exactly the enrolled native row", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { index -> cursor.getColumnName(index) to
                if (cursor.isNull(index)) null else cursor.getString(index) }.also {
                val key = projection.eventIdentityKey(target, EventId(frozen("event")))
                assertTrue("Keep exact ordinary production ownership", it[Events._SYNC_ID] == key && it[Events.SYNC_DATA4] == key)
                if (time is EventTime.AllDay) {
                    assertTrue("Preserve civil dates as UTC midnight with exclusive end", it[Events.ALL_DAY] == "1" &&
                        it[Events.EVENT_TIMEZONE] == "UTC" && it[Events.EVENT_END_TIMEZONE] == "UTC" &&
                        it[Events.DTSTART]?.toLongOrNull() == time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() &&
                        it[Events.DTEND]?.toLongOrNull() == time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                }
            }
        }
        fun writeMode(position: Int, enabled: Boolean) {
            assertTrue("Mode writes require UI thread, frozen ownership and current source",
                android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && accessible() && flags() == ownedModes())
            val before = ownedModes()
            val after = before.toMutableList().apply {
                if (position == 0 && !enabled) indices.forEach { this[it] = false }
                else this[position] = enabled && (position == 0 || before[0])
            }
            // Persist the only two admissible outcomes before a synchronous setter. A crash
            // cannot turn a later arbitrary owner preference into fixture restoration rights.
            val pending = prefs.edit().putBoolean("mode_write_pending", true)
            before.forEachIndexed { index, value -> pending.putBoolean("before_$index", value) }
            after.forEachIndexed { index, value -> pending.putBoolean("after_$index", value) }
            assertTrue(pending.commit())
            val selection = app.imports.state.value.selections.single { it.scope == target }
            try {
                when (position) {
                    0 -> app.systemCreates.setEnabled(selection, enabled)
                    1 -> app.systemCreates.setTitlesEnabled(selection, enabled)
                    2 -> app.systemCreates.setTextsEnabled(selection, enabled)
                    3 -> app.systemCreates.setTimesEnabled(selection, enabled)
                    4 -> app.systemCreates.setDeletesEnabled(selection, enabled)
                }
            } finally {
                val current = flags()
                assertTrue("Never adopt unrelated consent during a setter", current == before || current == after)
                val edit = prefs.edit().putBoolean("mode_write_pending", false)
                current.forEachIndexed { index, value -> edit.putBoolean("owned_$index", value) }
                assertTrue(edit.commit())
            }
            assertTrue("Require the setter's exact synchronous result", flags() == after)
        }
        fun recoverPendingModeWrite() {
            if (!prefs.getBoolean("mode_write_pending", false)) return
            val before = (0..4).map { prefs.getBoolean("before_$it", false) }
            val after = (0..4).map { prefs.getBoolean("after_$it", false) }
            val current = flags()
            assertTrue("Interrupted setter restores only its exact precomputed outcomes", current == before || current == after)
            val edit = prefs.edit().putBoolean("mode_write_pending", false)
            current.forEachIndexed { index, value -> edit.putBoolean("owned_$index", value) }
            assertTrue(edit.commit())
        }
        val manager = WorkManager.getInstance(app)
        fun waitingOffline() = offlinePhase || phase == "NETWORK_RUN" && prefs.getBoolean("network_loss_observed", false)
        suspend fun idle() = withTimeout(240_000) {
            var samples = 0
            while (samples < 2) {
                if (waitingOffline()) assertOffline(app)
                assertTrue("Retain exact fixture consent while waiting", accessible() && flags() == ownedModes())
                val oneShot = listOf("calendar-import-network", "calendar-import-local").all { name ->
                    manager.getWorkInfosForUniqueWork(name).get().all {
                        it.state.isFinished || (waitingOffline() && name == "calendar-import-network" && it.state == WorkInfo.State.ENQUEUED)
                    }
                }
                val periodic = manager.getWorkInfosForUniqueWork("calendar-import-periodic").get().none {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED
                }
                samples = if (oneShot && periodic && !app.imports.state.value.busy && !app.systemCreates.state.value.busy) samples + 1 else 0
                if (samples < 2) delay(500)
            }
        }
        var network: DeleteWorkerNetworkJourney? = null
        var process: DeleteWorkerProcessJourney? = null
        var restore = !prefs.getBoolean("modes_restored", false)
        try {
            recoverPendingModeWrite()
            assertTrue("The fixture still owns its exact modes", flags() == ownedModes())
            // Emergency cleanup does not activate, preview or inspect deletion work. The
            // existing finally block restores only the frozen compare-owned mode vector.
            if (phase == "PROCESS_RESTORE") {
                assertOffline(app)
                restore = true
                return@runBlocking
            }
            idle()
            if (phase == "PREPARE") {
                if (!originalModes[0] && !prefs.getBoolean("prepared", false))
                    SystemWorkerFixtureAudit.assertQuiescent(app, projection, target)
                assertTrue("PREPARE cannot replace an active/completed deletion", flags() == originalModes && !prefs.getBoolean("worker_verified", false))
                val remote = ProtonAuthentication.events(app)
                assertTrue("Never change the frozen remote format", !prefs.contains("format_id") ||
                    frozen("format_id") == remote.format.id && prefs.getInt("format_version", -1) == remote.format.version)
                assertTrue(prefs.edit().putString("format_id", remote.format.id).putInt("format_version", remote.format.version).commit())
                ImportStore(app, "$prefix-source.db").use { evidence ->
                    evidence.remember(target.account, listOf(selected.single().summary)); evidence.setEnabled(target, true)
                    var base = prefs.getString("event", null)?.let { evidence.transaction(target) { remote(EventId(it)) as? RemoteChange.Upsert } }
                    if (base == null) {
                        val create = ProtonAuthentication.createSubmissions(app)
                        CreateAttemptStore(app, "$prefix-create.db").use { creates ->
                            creates.admitDispatch = { false }
                            try {
                                val id = MutationId(frozen("create_id")); val previous = creates.read(target, id)
                                assertTrue("Missing requested CREATE cannot be regenerated", previous != null || !prefs.getBoolean("create_requested", false))
                                val work = previous ?: create.prepareSubmission(CalendarCreateDraft(target, id,
                                    Instant.parse(frozen("timestamp")), content)).let { prepared ->
                                    CreateAttemptRecord(id, target, prepared.format, prepared.protectedPayload).also {
                                        creates.admitDispatch = admit
                                        try { creates.remember(it) } finally { creates.admitDispatch = { false } }
                                    }
                                }
                                assertTrue("Require explicit empty CREATE details", ProtonCreateSubmissionProbe.knownEmptyPreparationSummary(work) == "SUBMISSION_V2_EXPLICIT_EMPTY")
                                val observation = if (work.state == CreateAttemptState.OUTCOME_UNKNOWN) create.inspect(work) else {
                                    assertTrue("A requested CREATE is inspect-only", !prefs.getBoolean("create_requested", false))
                                    assertTrue(prefs.edit().putBoolean("create_requested", true).commit())
                                    creates.admitDispatch = admit
                                    try { create.submit(work, creates) } finally { creates.admitDispatch = { false } }
                                }
                                assertTrue("CREATE needs authenticated full readback", observation is CreateSubmissionObservation.Verified)
                                val verified = observation as CreateSubmissionObservation.Verified
                                val unknown = checkNotNull(creates.read(target, id))
                                assertTrue("Require exact immutable CREATE and known-empty event", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN &&
                                    unknown.protectedEvidence != null && verified.attempt == CreateAttemptIdentity.of(unknown) && verified.event.content == content)
                                creates.bind(unknown, verified); base = verified.event
                                evidence.transaction(target) { putRemote(verified.event); checkpoint = PageCheckpoint(target, remote.format) }
                                assertTrue(prefs.edit().putString("event", verified.event.eventId.value).commit())
                            } finally { creates.admitDispatch = { false } }
                        }
                    }
                    val original = checkNotNull(base)
                    assertTrue("Require exact retained full baseline", original.content == content)
                    val calendar = projection.ensureCalendarState(target, selected.single().summary, true)
                    assertTrue("Keep the actual production native calendar", !prefs.contains("native_calendar") || prefs.getLong("native_calendar", -1) == calendar.id)
                    assertTrue(prefs.edit().putLong("native_calendar", calendar.id).commit())
                    if (!prefs.contains("row")) {
                        val count = app.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                            arrayOf(calendar.id.toString(), projection.eventIdentityKey(target, original.eventId)), null)!!.use { it.count }
                        assertTrue("Never duplicate the fixture native identity", count <= 1)
                        if (count == 0) projection.apply(ProjectionIntent(target, original))
                        val owned = checkNotNull(projection.inspectOwnedEvent(target, original.eventId, original.revision))
                        assertTrue(prefs.edit().putLong("row", owned.eventRow).commit())
                    }
                    assertTrue("Seed only the exact clean ordinary native projection", projection.inspectOwnedEvent(target,
                        original.eventId, original.revision)?.eventRow == prefs.getLong("row", -1))
                    // Separate helper, same production SQL: never borrow the coordinator's
                    // mutable guards. This is explicit fixture setup, not incoming qualification.
                    ImportStore(app).use { store ->
                        store.admitTransaction = admit
                        assertTrue("No fixture owner may predate the editor", store.localEdit(target, original.eventId) == null && store.localDelete(target, original.eventId) == null)
                        store.transaction(target) {
                            assertTrue("Seed only under the current import format", checkpoint?.format == remote.format)
                            val existing = remote(original.eventId)
                            assertTrue("Never replace a production observation", existing == null || existing == original)
                            if (existing == null) { putRemote(original); putProjection(ProjectionIntent(target, original)) }
                        }
                        val baseline = store.projectedBaseline(target, original.eventId)
                        assertTrue("Never replace a production baseline", baseline == null || baseline == original)
                        if (baseline == null) assertTrue(store.acknowledge(ProjectionIntent(target, original)) == 1)
                    }
                    assertTrue("No prior DELETE history can belong to a new fixture", historyIds(app, target, original.eventId).isEmpty())
                    row()
                    assertTrue(prefs.edit().putBoolean("prepared", true).commit())
                    compose.runOnUiThread { compose.activity.startActivity(Intent(Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(Events.CONTENT_URI, prefs.getLong("row", -1))).setPackage("foundation.e.calendar")) }
                    status("system_delete_prepared"); status("system_delete_dedicated_event_opened")
                }
                return@runBlocking
            }
            assertTrue("Require completed PREPARE and production deletion composition", prefs.getBoolean("prepared", false) &&
                app.systemCreates.state.value.deleteSupportAvailable)
            val event = EventId(frozen("event"))
            val offlineRowHash = if (offlinePhase) rowHash(row()) else null
            if (offlinePhase && !networkPhase && !processPhase) {
                assertTrue("Offline phases cannot follow a completed online deletion", !prefs.getBoolean("worker_verified", false))
                assertTrue("Offline capture starts from the real editor's dirty tombstone",
                    row()[Events.DELETED] == "1" && row()[Events.DIRTY] == "1")
                if (phase == "OFFLINE_REPLAY") assertTrue("Offline replay requires completed capture in another process",
                    prefs.getBoolean("offline_captured", false) && prefs.getBoolean("modes_restored", false) &&
                        prefs.getInt("offline_pid", -1) > 0 && prefs.getInt("offline_pid", -1) != Process.myPid())
                offlinePreflight(app, target, event, content, prefs, phase, checkNotNull(offlineRowHash))
            }
            if (offlineJourney && phase == "RUN") assertTrue("Online RUN requires completed offline replay and restoration",
                prefs.getBoolean("offline_replayed", false) && prefs.getBoolean("modes_restored", false))
            if (phase == "REPLAY") assertTrue("REPLAY needs completed RUN in another process", prefs.getBoolean("worker_verified", false) &&
                prefs.getBoolean("modes_restored", false) && prefs.getInt("run_pid", -1) > 0 && prefs.getInt("run_pid", -1) != Process.myPid())
            else assertTrue("RUN needs a real editor tombstone or its exact already completed history", row()[Events.DELETED] == "1" &&
                (row()[Events.DIRTY] == "1" || readProof(app, target, event, admit) != null))
            // Consent can cover a calendar, so reject other pending deletion history or
            // native tombstones before enabling it. Read only bounded metadata of others.
            TitleUpdateAttemptStore(app).use { shared -> shared.readableDatabase.rawQuery(
                "SELECT 1 FROM delete_attempts WHERE account=? AND calendar=? AND event!=? AND completion IS NULL LIMIT 1",
                arrayOf(target.account.value, target.calendar.value, event.value)).use {
                assertTrue("Another uncertain deletion must not borrow fixture consent", it.count == 0)
            } }
            ImportStore(app).use { store ->
                var position: RetainedLocalChangeCursor? = null; var count = 0
                do {
                    val local = store.retainedLocalChanges(RetainedLocalChangeQuery(target, 25, position))
                    assertTrue("Other retained deletions must not borrow fixture consent",
                        local.entries.none { it.kind == MutationKind.DELETE && it.eventId != event })
                    position = local.next; count++
                    assertTrue("Bound the retained owner preflight", position == null || count < 20)
                } while (position != null)
            }
            val closedDeleteRows = SystemWorkerFixtureAudit.closedDeleteRows(app, projection, target, event)
            var cursor = 0L; var pages = 0
            do {
                val page = checkNotNull(projection.discoverSystemChanges(target, cursor, 25))
                assertTrue("No other native change may borrow temporary fixture consent", page.changes.none {
                    it.row !in closedDeleteRows &&
                        (it.row != prefs.getLong("row", -1) || it.nativeKey != projection.eventIdentityKey(target, event))
                })
                cursor = page.afterRow; pages++
                assertTrue("Bound the target native preflight", !page.more || pages < 20)
            } while (page.more)
            SystemWorkerFixtureAudit.assertDeleteActivationQuiescent(app, projection, target, event)
            if (networkPhase) {
                network = DeleteWorkerNetworkJourney(app, target, event, content, prefs, hash, admit, ::row, ::rowHash,
                    { historyIds(app, target, event) }, { readProof(app, target, event, admit) != null }, ::status)
                network.beforeActivation(phase)
            }
            if (processPhase) {
                process = DeleteWorkerProcessJourney(app, target, event, content, prefs, hash, admit, ::row, ::rowHash,
                    { historyIds(app, target, event) }, { readProof(app, target, event, admit) != null }, ::status)
                process.beforeActivation(phase)
            }
            restore = true
            assertTrue(prefs.edit().putBoolean("modes_restored", false).commit())
            compose.runOnUiThread {
                writeMode(0, true); writeMode(1, false); writeMode(2, false); writeMode(3, false); writeMode(4, true)
            }
            app.systemCreates.request()
            if (networkPhase && !checkNotNull(network).afterActivation(phase, ::idle) {
                assertTrue("Preserve fixture access and exact active consent", accessible() && flags() == ownedModes())
            }) return@runBlocking
            if (processPhase && !checkNotNull(process).afterActivation(phase, ::idle) {
                assertTrue("Preserve fixture access and exact active consent", accessible() && flags() == ownedModes())
            }) return@runBlocking
            if (offlinePhase && !networkPhase && !processPhase) {
                verifyOfflineWorker(app, target, event, content, prefs, phase, checkNotNull(offlineRowHash), ::row, ::idle) {
                    assertTrue("Preserve fixture access and exact active consent", accessible() && flags() == ownedModes())
                }
                return@runBlocking
            }
            val proof = withTimeout(240_000) {
                var found: Proof? = null
                while (found == null) {
                    assertTrue("Preserve fixture access and exact active consent", accessible() && flags() == ownedModes())
                    found = readProof(app, target, event, admit)
                    if (found == null) delay(500)
                }
                checkNotNull(found)
            }
            idle()
            assertTrue("Production worker completed the exact ordinary deletion", proof.record.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                (if (processJourney || networkJourney && frozen("network_evidence_shape") == "NONE") proof.record.protectedEvidence == null
                 else proof.record.protectedEvidence != null) && proof.record.intent.scope == target && proof.record.eventId == event &&
                proof.record.intent.base.content == content && proof.record.intent.content == content &&
                proof.record.id == DeleteAttemptIdentity.requestId(proof.record.intent, proof.record.format) &&
                proof.result.record == proof.record.copy(protectedEvidence = null) && proof.completion.result == proof.result)
            if (offlineJourney) assertTrue("Online proof belongs to the original retained offline owner",
                prefs.getBoolean("offline_replayed", false) && proof.record.intent.id.value == frozen("offline_owner") &&
                    DeleteAttemptIdentity.intentFingerprint(proof.record.intent) == frozen("offline_owner_hash"))
            ImportStore(app).use { store ->
                val receipt = checkNotNull(store.deleteAcknowledgement(target, event, proof.record.id))
                assertTrue("Native/SQL ACK matches exact durable completion", receipt.state == DeleteAcknowledgementState.RESTORED &&
                    proof.completion == DeleteCompletion.of(proof.result, receipt) &&
                    projection.inspectDeleteAcknowledgement(receipt.receipt)?.receipt == receipt.receipt)
                assertTrue("Retain the exact native clean tombstone", row()[Events.DELETED] == "1" && row()[Events.DIRTY] == "0")
                assertTrue("The production deletion barrier suppresses incoming recreation", store.pending(target).none { it.change.eventId == event } &&
                    store.stabilizedProjection(target, event) == null)
            }
            if (phase == "RUN" || phase == "NETWORK_RECONNECT" || phase == "PROCESS_RECONNECT") {
                assertTrue("Never replace a previously recorded completion", !prefs.contains("proof_hash") || frozen("proof_hash") == proof.hash)
                assertTrue(prefs.edit().putString("attempt", proof.record.id.value).putString("proof_hash", proof.hash)
                    .putInt("run_pid", Process.myPid()).putBoolean("worker_verified", true).commit())
                status("system_delete_worker_verified"); status("system_delete_same_row_ack")
            } else {
                assertTrue("Active restarted worker preserves one exact immutable history", frozen("attempt") == proof.record.id.value &&
                    frozen("proof_hash") == proof.hash && historyIds(app, target, event) == listOf(proof.record.id))
                assertTrue(prefs.edit().putBoolean("replay_verified", true).commit())
                status("system_delete_replay_new_process"); status("system_delete_replay_no_new_attempt"); status("system_delete_history_immutable")
            }
        } finally {
            try {
                if (restore) {
                    recoverPendingModeWrite()
                    compose.runOnUiThread {
                        assertTrue("Restore only fixture-owned five-mode state", accessible() && flags() == ownedModes())
                        originalModes.forEachIndexed { index, enabled -> writeMode(index, enabled) }
                    }
                    assertTrue("Restore the original five target modes", flags() == originalModes)
                    assertTrue(prefs.edit().putBoolean("modes_restored", true).commit())
                    status("system_delete_five_modes_restored")
                }
            } finally {
                network?.close()
                process?.close()
                projection.admitOperation = { false }
                if (offlinePhase) assertOffline(app)
            }
        }
    }

    private fun assertOffline(app: Context) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        assertTrue("Host must keep every INTERNET network disconnected throughout the offline phase",
            connectivity.allNetworks.none { network ->
                connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            })
    }

    private fun rowHash(values: Map<String, String?>): String = MessageDigest.getInstance("SHA-256").run {
        // AOSP's Events view joins this calendar permission. The normal consent setters
        // switch READ/EDITOR; it is the sole excluded field, not event-row mutation.
        val access = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        if (values.containsKey(access)) assertTrue("Joined calendar permission remains READ or EDITOR",
            values[access] in setOf(CalendarContract.Calendars.CAL_ACCESS_READ.toString(),
                CalendarContract.Calendars.CAL_ACCESS_EDITOR.toString()))
        values.filterKeys { it != access }.toSortedMap().forEach { (key, value) ->
            for (part in listOf(key, value)) {
                val bytes = part?.toByteArray(Charsets.UTF_8)
                update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                if (bytes != null) update(bytes)
            }
        }
        digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun offlinePreflight(
        app: Context, target: CalendarScope, event: EventId, content: EventContent,
        prefs: SharedPreferences, phase: String, nativeHash: String
    ) {
        assertOffline(app)
        assertTrue("Offline activation starts without any DELETE request", historyIds(app, target, event).isEmpty())
        val sourceName = "calendar-delete-worker-v1-${checkNotNull(prefs.getString("token", null))}-source.db"
        val base = ImportStore(app, sourceName).use { source ->
            checkNotNull(source.transaction(target) { remote(event) as? RemoteChange.Upsert })
        }
        assertTrue("Offline preflight retains the complete frozen source", base.content == content)
        ImportStore(app).use { store ->
            assertTrue("Offline activation starts without an ACK or a changed baseline/current observation",
                store.deleteAcknowledgement(target, event) == null && store.projectedBaseline(target, event) == base &&
                    store.transaction(target) { remote(event) } == base)
            val owner = store.localDelete(target, event)
            assertTrue("Existing offline owner must already be the exact PAUSED deletion",
                owner == null || owner.state == LocalEditState.PAUSED && owner.scope == target && owner.base == base && owner.content == content)
            if (phase == "OFFLINE_REPLAY" || prefs.getBoolean("offline_captured", false)) {
                val retained = checkNotNull(owner)
                assertTrue("Require immutable enrolled owner and native row before temporary consent",
                    retained.id.value == prefs.getString("offline_owner", null) &&
                        DeleteAttemptIdentity.intentFingerprint(retained) == prefs.getString("offline_owner_hash", null) &&
                        nativeHash == prefs.getString("offline_row_hash", null))
            }
        }
    }

    /** Read-only witnesses around normal worker activation; never creates or repairs an owner. */
    private suspend fun verifyOfflineWorker(
        app: Context, target: CalendarScope, event: EventId, content: EventContent,
        prefs: SharedPreferences, phase: String, nativeHash: String,
        row: () -> Map<String, String?>, idle: suspend () -> Unit, assertOwned: () -> Unit
    ) {
        val replay = phase == "OFFLINE_REPLAY"
        val sourceName = "calendar-delete-worker-v1-${checkNotNull(prefs.getString("token", null))}-source.db"
        val base = ImportStore(app, sourceName).use { source ->
            checkNotNull(source.transaction(target) { remote(event) as? RemoteChange.Upsert })
        }
        assertTrue("Offline witnesses use the frozen complete source baseline", base.content == content)
        if (replay || prefs.getBoolean("offline_captured", false)) assertTrue("Retain the complete original native tombstone",
            prefs.getString("offline_row_hash", null) == nativeHash)
        suspend fun witness(): LocalDeleteIntent? {
            assertOffline(app); assertOwned()
            assertTrue("Offline workers cannot allocate any DELETE request", historyIds(app, target, event).isEmpty())
            val values = row()
            assertTrue("Offline workers preserve every event column, other joined metadata and dirty deletion",
                rowHash(values) == nativeHash && values[Events.DIRTY] == "1" && values[Events.DELETED] == "1")
            return ImportStore(app).use { store ->
                assertTrue("No offline DELETE acknowledgement or baseline change is allowed",
                    store.deleteAcknowledgement(target, event) == null && store.projectedBaseline(target, event) == base &&
                        store.transaction(target) { remote(event) } == base)
                store.localDelete(target, event)?.also { owner ->
                    assertTrue("Production capture retains one exact PAUSED deletion owner",
                        owner.state == LocalEditState.PAUSED && owner.scope == target && owner.base == base && owner.content == content)
                    if (replay || prefs.getBoolean("offline_captured", false)) assertTrue("Restart cannot replace or relabel the retained owner",
                        owner.id.value == prefs.getString("offline_owner", null) &&
                            DeleteAttemptIdentity.intentFingerprint(owner) == prefs.getString("offline_owner_hash", null))
                }
            }
        }
        if (replay) checkNotNull(witness())
        val owner = withTimeout(240_000) {
            var found = witness()
            while (found == null) { delay(500); found = witness() }
            checkNotNull(found)
        }
        idle()
        assertTrue("Settled production workers retain exactly the captured owner", witness() == owner)
        val edit = prefs.edit()
        if (replay) {
            if (!prefs.contains("offline_replay_pid")) edit.putInt("offline_replay_pid", Process.myPid())
        } else if (!prefs.getBoolean("offline_captured", false)) {
            edit.putString("offline_owner", owner.id.value)
                .putString("offline_owner_hash", DeleteAttemptIdentity.intentFingerprint(owner))
                .putString("offline_row_hash", nativeHash).putInt("offline_pid", Process.myPid())
        }
        assertTrue("Atomically persist first immutable offline evidence and success", edit
            .putBoolean(if (replay) "offline_replayed" else "offline_captured", true).commit())
        status(if (replay) "system_delete_offline_replay_verified" else "system_delete_offline_capture_verified")
        status("system_delete_offline_no_attempt"); status("system_delete_offline_native_immutable")
    }

    private class Proof(val record: DeleteAttemptRecord, val result: DeleteVerifiedResult, val completion: DeleteCompletion) {
        val hash: String = MessageDigest.getInstance("SHA-256").run {
            update(DeleteAttemptStorageCodec.encode(record)); update(DeleteProofStorageCodec.encodeResult(result))
            update(DeleteProofStorageCodec.encodeCompletion(completion))
            digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
    private fun historyIds(app: Context, target: CalendarScope, event: EventId): List<MutationId> =
        TitleUpdateAttemptStore(app).use { shared -> shared.readableDatabase.rawQuery(
            "SELECT CASE WHEN typeof(mutation)='text' AND length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN mutation END " +
                "FROM delete_attempts WHERE account=? AND calendar=? AND event=? LIMIT 2",
            arrayOf(target.account.value, target.calendar.value, event.value)).use { cursor ->
            assertTrue("At most one immutable DELETE request may exist", cursor.count <= 1)
            buildList { while (cursor.moveToNext()) { assertTrue(!cursor.isNull(0)); add(MutationId(cursor.getString(0))) } }
        } }
    private suspend fun readProof(app: Context, target: CalendarScope, event: EventId, admit: (CalendarScope) -> Boolean): Proof? {
        val id = historyIds(app, target, event).singleOrNull() ?: return null
        return TitleUpdateAttemptStore(app).use { shared ->
            val journal = shared.deleteJournal
            val record = journal.read(target, id, admit) ?: return@use null
            val result = journal.readVerified(target, id, admit) ?: return@use null
            val completion = journal.readCompletion(target, id, admit) ?: return@use null
            Proof(record, result, completion)
        }
    }
    private fun status(code: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putBoolean(code, true) })
}

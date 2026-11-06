// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteProofStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonDeleteInspectionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonDeleteNetworkInterruptionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.DeleteReadResult
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in disposable real-service event, isolated native calendar and persistent journals.
 * Arguments: liveDeleteJourney=true, deleteFixtureToken (12 lowercase hex),
 * deleteAccountHash (SHA-256), deleteJourneyScenario=NORMAL|REMOTE_CONFLICT|EVIDENCE_INTERRUPTION|NETWORK_INTERRUPTION|PROCESS_INTERRUPTION,
 * deleteJourneyPhase=PREPARE|SEND|LOCAL|INSPECT|ACK|REPLAY|CONFLICT|CONFLICT_REVIEW|CONFLICT_REPLAY.
 * deleteConflictExpectedZone defaults to Z and is frozen during fresh PREPARE; an
 * official-editor zone change must be declared explicitly. CONFLICT_REVIEW audits an
 * already entered preflight locally and can bind a missing legacy expected zone once.
 * PREPARE opens only the dedicated event in foundation.e.calendar. The external operator
 * must delete it there before SEND. Stop the process before ACK and again before REPLAY.
 * REMOTE_CONFLICT uses a fresh token: PREPARE also seals the genuine DELETE wire before
 * the operator deletes that native row and changes only its title in official Proton to
 * "Calendeer QA — retained DELETE conflict <token>". CONFLICT is preflight-only;
 * stop the process before CONFLICT_REPLAY. Retain the modified service event afterwards.
 * EVIDENCE_INTERRUPTION uses PREPARE, SEND, restarted UNKNOWN_REPLAY, restarted INSPECT,
 * ACK and REPLAY. SEND injects cancellation at evidence persistence after a transport
 * attempt; this proves neither successful remote deletion nor actual network loss.
 * NETWORK_INTERRUPTION additionally interrupts real Wi-Fi at the authenticated HTTP body's
 * start and requires a natural socket failure. Its protected driver must restore network
 * settings. Restarted inspection may hold a still-present original event without resending.
 * Offline sentinels cover this fixture composition, not independent application traffic.
 * PROCESS_INTERRUPTION kills this actual process after a complete bound DELETE response in
 * memory and before evidence persistence. Response reception never establishes acceptance.
 * It uses the interruption phases, requires all
 * production write flags off, and still needs authenticated absence inspection before ACK.
 * This is not packet-loss/server-atomicity or production WorkManager kill qualification.
 * No production deletion qualification or user preference is changed. Retain the native
 * tombstone and isolated journals for inspection; the service event is explicitly disposable.
 */
class CalendarDeleteGuardedJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class IsolatedProjection(private val native: AndroidCalendarProjection,
        private val logical: CalendarScope, private val physical: CalendarScope,
        private val access: () -> Boolean) : CalendarProjection {
        val writes = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val deleteAcknowledgementProtocol get() = native.deleteAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("No coordinator calendar creation in this fixture")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No fixture content projection")
        override suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedSimpleDelete(physical, base)
        }
        private fun mapped(receipt: DeleteAcknowledgementReceipt): DeleteAcknowledgementReceipt {
            check(receipt.intent.scope == logical)
            val intent = receipt.intent.copy(scope = physical)
            // Scope is part of the full intent fingerprint. Preserve the actual logical wire
            // fingerprint and request ID; map the native-only scope and intent together.
            return receipt.copy(intent = intent, attempt = receipt.attempt.copy(scope = physical,
                intentFingerprint = DeleteAttemptIdentity.intentFingerprint(intent)))
        }
        override suspend fun acknowledgeDelete(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? {
            writes.incrementAndGet()
            val expected = mapped(receipt)
            return native.acknowledgeDelete(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedDeleteAcknowledgement(receipt) }
        }
        override suspend fun inspectDeleteAcknowledgement(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? {
            val expected = mapped(receipt)
            return native.inspectDeleteAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedDeleteAcknowledgement(receipt) }
        }
    }

    @Test fun disposableEditorDeletionThenRestartedOfflineAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveDeleteJourney") == "true")
        val token = args.getString("deleteFixtureToken").orEmpty()
        val hash = args.getString("deleteAccountHash").orEmpty()
        val phase = args.getString("deleteJourneyPhase").orEmpty()
        val scenario = args.getString("deleteJourneyScenario") ?: "NORMAL"
        val conflictScenario = scenario == "REMOTE_CONFLICT"
        val networkScenario = scenario == "NETWORK_INTERRUPTION"
        val processScenario = scenario == "PROCESS_INTERRUPTION"
        val interruptionScenario = scenario == "EVIDENCE_INTERRUPTION" || networkScenario || processScenario
        val expectedConflictZone = ZoneId.of(args.getString("deleteConflictExpectedZone") ?: "Z")
        assertTrue("Require explicit fixture, account and phase", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && scenario in setOf("NORMAL", "REMOTE_CONFLICT", "EVIDENCE_INTERRUPTION", "NETWORK_INTERRUPTION", "PROCESS_INTERRUPTION") &&
            phase in (if (conflictScenario) setOf("PREPARE", "CONFLICT", "CONFLICT_REVIEW", "CONFLICT_REPLAY")
                else if (interruptionScenario) setOf("PREPARE", "SEND", "UNKNOWN_REPLAY", "INSPECT", "ACK", "REPLAY")
                else setOf("PREPARE", "SEND", "LOCAL", "INSPECT", "ACK", "REPLAY")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let {
            it.authenticated && it.selections.isNotEmpty() && !it.busy
        } }
        val prefix = "calendar-delete-guarded-v1-$token"
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val calendarName = "Calendeer QA Automatic Worker"
        val selection = app.imports.state.value.selections.filter {
            it.enabled && it.available && it.summary.title == calendarName
        }
        assertTrue("Resolve exactly the dedicated accessible QA calendar", selection.size == 1)
        val source = selection.single().scope
        assertTrue("Authorize the exact controlled account before mutation",
            ProtonCreateSubmissionProbe.matchesAccount(app, source.account, hash))
        assertTrue("Use the initial logical generation", source.generation == 0L)
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.any { it.scope == source && it.enabled && it.available && it.summary.title == calendarName } }
        assertTrue("Require current accessible source", accessible())
        if (processScenario) DeleteProcessStopJourney.assertQuiet(app, source, ::accessible)
        if (!prefs.contains("token")) {
            assertTrue("Only PREPARE can enroll a new disposable fixture", phase == "PREPARE")
            val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
            assertTrue("Freeze fixture identities before CREATE", prefs.edit().putString("token", token)
                .putString("scenario", scenario)
                .putString("conflict_expected_zone", expectedConflictZone.id)
                .putString("account_hash", hash).putString("account", source.account.value)
                .putString("calendar", source.calendar.value).putLong("generation", source.generation)
                .putString("source_calendar_name", calendarName)
                .putLong("physical_generation", (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1))
                .putString("create_id", UUID.randomUUID().toString()).putString("intent_id", UUID.randomUUID().toString())
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                .putString("start", start.toString()).putString("end", start.plus(1, ChronoUnit.HOURS).toString())
                .putString("title", "Calendeer QA — disposable DELETE $token").commit())
        }
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        fun id(key: String) = MutationId(frozen(key))
        assertTrue("Never change the frozen scenario; legacy fixtures are NORMAL",
            (prefs.getString("scenario", null) ?: "NORMAL") == scenario)
        if (conflictScenario) {
            val frozenZone = prefs.getString("conflict_expected_zone", null)
            assertTrue("Never override a frozen expected conflict zone", frozenZone == expectedConflictZone.id ||
                (frozenZone == null && (expectedConflictZone.id == "Z" || phase == "CONFLICT_REVIEW")))
            if (phase == "CONFLICT_REVIEW" && frozenZone == null)
                assertTrue("Legacy review must explicitly declare its expected zone", args.containsKey("deleteConflictExpectedZone"))
        }
        if (conflictScenario && phase == "PREPARE") assertTrue("Conflict enrollment cannot replace any existing DELETE history",
            !prefs.getBoolean("prepared", false) && !prefs.contains("attempt_id") &&
                !prefs.getBoolean("delete_requested", false) && !prefs.getBoolean("conflict_entered", false))
        if (processScenario && phase == "PREPARE") assertTrue("Process interruption requires a fresh PREPARE",
            !prefs.getBoolean("prepared", false) && !prefs.contains("attempt_id") &&
                !prefs.getBoolean("create_requested", false) && !prefs.getBoolean("delete_requested", false))
        assertTrue("Never move or replace an enrolled fixture", frozen("token") == token && frozen("account_hash") == hash &&
            frozen("account") == source.account.value && frozen("calendar") == source.calendar.value &&
            prefs.getLong("generation", -1) == source.generation && frozen("source_calendar_name") == calendarName)
        val physical = source.copy(generation = prefs.getLong("physical_generation", -1))
        assertTrue("Use a distinct frozen native namespace", physical.generation > 0 && physical != source &&
            app.imports.state.value.selections.none { it.scope == physical })
        val timestamp = Instant.parse(frozen("timestamp"))
        val content = EventContent(frozen("title"), EventTime.Timed(Instant.parse(frozen("start")),
            Instant.parse(frozen("end")), ZoneOffset.UTC), details = EventDetails())
        val actualRemote = if (phase in setOf("PREPARE", "SEND", "INSPECT")) ProtonAuthentication.events(app) else null
        if (actualRemote != null) {
            assertTrue("Never replace the frozen adapter format", !prefs.contains("format_id") ||
                frozen("format_id") == actualRemote.format.id && prefs.getInt("format_version", -1) == actualRemote.format.version)
            assertTrue(prefs.edit().putString("format_id", actualRemote.format.id)
                .putInt("format_version", actualRemote.format.version).commit())
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = AdapterFormat(frozen("format_id"), prefs.getInt("format_version", -1))
            override suspend fun currentAccount() = actualRemote?.currentAccount() ?: source.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No fixture import transport")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No fixture import dispatch")
        }
        val native = AndroidCalendarProjection(app)
        val account = native.accountFor(source.account)
        assertTrue("Require existing native account and permissions", native.hasPermissions() &&
            AccountManager.get(app).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun values(): Map<String, String?> = app.contentResolver.query(uri, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString()), null)!!.use { cursor ->
            assertTrue("Keep exactly the enrolled native row", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                if (cursor.isNull(column)) null else cursor.getString(column) }.also { row ->
                val key = native.eventIdentityKey(physical, EventId(frozen("event")))
                assertTrue("Require the full ordinary native identity", row[Events._SYNC_ID] == key && row[Events.SYNC_DATA4] == key)
            }
        }
        fun witness(vararg names: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { names.forEach { putBoolean(it, true) } })
        val projection = IsolatedProjection(native, source, physical, ::accessible)
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ImportStore(app, "$prefix-import.db").use { store ->
                fun allowStore() { store.admitTransaction = { it == source && accessible() } }
                allowStore()
                if (phase == "PREPARE") {
                    assertTrue("PREPARE cannot replace an already requested deletion", !prefs.getBoolean("delete_requested", false))
                    store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic disposable deletion")))
                    store.setEnabled(source, true)
                    var base = prefs.getString("event", null)?.let { store.transaction(source) { remote(EventId(it)) as? RemoteChange.Upsert } }
                    if (base == null) {
                        val createPort = ProtonAuthentication.createSubmissions(app)
                        CreateAttemptStore(app, "$prefix-create.db").use { creates ->
                            creates.admitDispatch = { false }
                            try {
                                val existing = creates.read(source, id("create_id"))
                                assertTrue("Missing requested CREATE cannot allocate another request", existing != null ||
                                    !prefs.getBoolean("create_requested", false))
                                val work = existing ?: createPort.prepareSubmission(CalendarCreateDraft(source,
                                    id("create_id"), timestamp, content)).let { prepared ->
                                    CreateAttemptRecord(id("create_id"), source, prepared.format, prepared.protectedPayload).also {
                                        creates.admitDispatch = { requested -> requested == source && accessible() }
                                        try { creates.remember(it) } finally { creates.admitDispatch = { false } }
                                    }
                                }
                                assertTrue("Require explicit known-empty CREATE preparation",
                                    ProtonCreateSubmissionProbe.knownEmptyPreparationSummary(work) == "SUBMISSION_V2_EXPLICIT_EMPTY")
                                val observation = if (work.state == CreateAttemptState.OUTCOME_UNKNOWN) createPort.inspect(work) else {
                                    assertTrue("A requested CREATE is inspect-only", !prefs.getBoolean("create_requested", false))
                                    assertTrue(prefs.edit().putBoolean("create_requested", true).commit())
                                    creates.admitDispatch = { it == source && accessible() }
                                    try { createPort.submit(work, creates) } finally { creates.admitDispatch = { false } }
                                }
                                assertTrue("Require authenticated full CREATE readback", observation is CreateSubmissionObservation.Verified)
                                val verified = observation as CreateSubmissionObservation.Verified
                                val unknown = checkNotNull(creates.read(source, work.id))
                                assertTrue("Keep exact request and known-empty content", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN &&
                                    unknown.protectedEvidence != null && verified.attempt == CreateAttemptIdentity.of(unknown) && verified.event.content == content)
                                creates.bind(unknown, verified)
                                base = verified.event
                                store.transaction(source) {
                                    val old = remote(verified.event.eventId)
                                    assertTrue("Never replace a baseline", old == null || old == verified.event)
                                    putRemote(verified.event); putProjection(ProjectionIntent(source, verified.event))
                                    checkpoint = PageCheckpoint(source, remote.format)
                                }
                                assertTrue(prefs.edit().putString("event", verified.event.eventId.value).commit())
                            } finally { creates.admitDispatch = { false } }
                        }
                    }
                    val original = checkNotNull(base)
                    assertTrue("Require the frozen complete baseline", original.content == content)
                    native.admitOperation = ::accessible
                    val namespace = framedHash("calendar-v1", physical.account.value, physical.calendar.value, physical.generation.toString())
                    if (!prefs.getBoolean("namespace_reserved", false)) {
                        app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
                            arrayOf(account.name, account.type, namespace), null)!!.use {
                            assertTrue("A new isolated native namespace must not exist", it.count == 0)
                        }
                        assertTrue(prefs.edit().putBoolean("namespace_reserved", true).commit())
                    }
                    val calendar = native.ensureCalendarState(physical, CalendarSummary(source.calendar, "Calendeer QA — disposable deletion"), true)
                    assertTrue("Keep the frozen native calendar", !prefs.contains("native_calendar") || prefs.getLong("native_calendar", -1) == calendar.id)
                    assertTrue(prefs.edit().putLong("native_calendar", calendar.id).commit())
                    if (!prefs.contains("row")) {
                        val count = app.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                            arrayOf(calendar.id.toString(), native.eventIdentityKey(physical, original.eventId)), null)!!.use { it.count }
                        assertTrue("The enrolled native event must be unique", count <= 1)
                        if (count == 0) native.apply(ProjectionIntent(physical, original))
                        val row = checkNotNull(native.inspectOwnedEvent(physical, original.eventId, original.revision)).eventRow
                        assertTrue(prefs.edit().putLong("row", row).commit())
                    }
                    assertTrue("The editor can open only the exact clean imported event",
                        native.inspectOwnedEvent(physical, original.eventId, original.revision)?.eventRow == prefs.getLong("row", -1))
                    if (store.projectedBaseline(source, original.eventId) == null)
                        assertTrue(store.acknowledge(ProjectionIntent(source, original)) == 1)
                    assertTrue(store.projectedBaseline(source, original.eventId) == original)
                    if (conflictScenario) {
                        assertTrue("Conflict PREPARE is fresh and never replaces DELETE history",
                            !prefs.getBoolean("prepared", false) && !prefs.contains("attempt_id") &&
                                store.localDelete(source, original.eventId) == null)
                        val deletePort = ProtonAuthentication.deletions(app)
                        val intent = DeleteSubmissionPolicy.freezeIntent(LocalDeleteIntent(id("intent_id"), source,
                            remote.format, original, content))
                        val attempt = DeleteAttemptIdentity.requestId(intent, checkNotNull(deletePort.deleteFormat))
                        TitleUpdateAttemptStore(app, "$prefix-shared-attempts.db").use { shared ->
                            val admit: (CalendarScope) -> Boolean = { it == source && accessible() }
                            assertTrue("No earlier DELETE history may be reused",
                                shared.deleteJournal.page(DeleteWorkQuery(source), admit)?.entries?.isEmpty() == true &&
                                    shared.deleteJournal.read(source, attempt, admit) == null)
                            // Preparation needs the unchanged authenticated baseline. The later real
                            // submit performs the conflict preflight after the external operator edit.
                            val record = checkNotNull(deletePort.prepareSubmission(attempt, intent, admit))
                            assertTrue("Freeze the genuine unclaimed wire", record.id == attempt && record.intent == intent &&
                                record.state == DeleteAttemptState.PREPARED && record.protectedEvidence == null &&
                                shared.deleteJournal.remember(record, admit))
                            assertTrue(prefs.edit().putString("attempt_id", attempt.value)
                                .putString("conflict_wire_hash", bytesHash(DeleteAttemptStorageCodec.encode(record))).commit())
                        }
                    }
                    assertTrue(prefs.edit().putBoolean("prepared", true).commit())
                    compose.runOnUiThread {
                        compose.activity.startActivity(Intent(Intent.ACTION_VIEW,
                            ContentUris.withAppendedId(Events.CONTENT_URI, prefs.getLong("row", -1)))
                            .setPackage("foundation.e.calendar"))
                    }
                    witness("guarded_delete_prepared", "guarded_delete_dedicated_event_opened")
                    if (interruptionScenario) witness("guarded_delete_interruption_prepared")
                    return@runBlocking
                }
                assertTrue("SEND and recovery require the enrolled PREPARE", prefs.getBoolean("prepared", false))
                TitleUpdateAttemptStore(app, "$prefix-shared-attempts.db").use { shared ->
                    val journal = shared.deleteJournal
                    shared.admitDispatch = { error("No title dispatch in deletion fixture") }
                    val factual: (CalendarScope) -> Boolean = { it == source && accessible() }
                    val consent = AtomicBoolean(phase in setOf("SEND", "INSPECT", "CONFLICT", "UNKNOWN_REPLAY"))
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() }, systemTitlesEnabled = { false },
                        systemTextsEnabled = { false }, systemTimesEnabled = { false },
                        systemDeletesEnabled = { it == source && consent.get() && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val event = EventId(frozen("event"))
                        val actualPort = if (phase in setOf("SEND", "INSPECT", "CONFLICT")) ProtonAuthentication.deletions(app) else null
                        var prepares = 0; var sends = 0; var inspections = 0
                        var claims = 0
                        var evidenceCheckpoints = 0
                        var networkProbe: ProtonDeleteNetworkInterruptionProbe.Handle? = null
                        val interruption = if (processScenario) null else CancellationException("guarded_delete_interruption_evidence_checkpoint")
                        var conflictReturned: DeleteSubmissionObservation.Conflict? = null
                        var wireReturned = false; var verifiedReturned = false
                        val port = object : CalendarDeleteSubmissionPort {
                            override val deleteFormat get() = actualPort?.deleteFormat ?: if (interruptionScenario)
                                AdapterFormat(frozen("interruption_format_id"), prefs.getInt("interruption_format_version", -1)) else null
                            override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
                                admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
                                prepares++; check(phase == "SEND" && actualPort != null)
                                assertTrue("Freeze deterministic DELETE identity before preparation",
                                    id == DeleteAttemptIdentity.requestId(intent, checkNotNull(deleteFormat)) &&
                                        (!prefs.contains("attempt_id") || frozen("attempt_id") == id.value))
                                assertTrue(prefs.edit().putString("attempt_id", id.value).commit())
                                return actualPort.prepareSubmission(id, intent, admit).also { wireReturned = it != null }
                            }
                            override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
                                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
                                admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
                                sends++; check(phase in setOf("SEND", "CONFLICT") && actualPort != null)
                                assertTrue("Never resend a requested DELETE", !prefs.getBoolean("delete_requested", false) &&
                                    record.id.value == frozen("attempt_id"))
                                if (phase == "SEND") assertTrue(prefs.edit().putBoolean("delete_requested", true)
                                    .putInt("submit_pid", Process.myPid()).commit())
                                else assertTrue("Conflict preflight cannot be entered twice",
                                    !prefs.getBoolean("conflict_entered", false) &&
                                        prefs.edit().putBoolean("conflict_entered", true).commit())
                                val processNativeBeforeSend = if (processScenario) values().also {
                                    DeleteProcessStopJourney.assertQuiet(app, source, ::accessible)
                                } else null
                                val guarded = if (phase == "CONFLICT") object : DeleteAttemptJournal by journal {
                                    override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
                                        claims++
                                        error("Conflict fixture forbids claiming or sending DELETE")
                                    }
                                } else if (interruptionScenario) object : DeleteAttemptJournal by journal {
                                    override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
                                        claims++
                                        assertTrue("Exactly one real durable claim", claims == 1 && journal.claim(expected, admit))
                                        assertTrue("Real claim preserves the full UNKNOWN request", journal.read(source, expected.id, factual) ==
                                            expected.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN))
                                        return true
                                    }
                                    override suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String,
                                        admit: (CalendarScope) -> Boolean): Boolean {
                                        evidenceCheckpoints++
                                        val unknown = checkNotNull(journal.read(source, record.id, factual))
                                        assertTrue("Interrupt only the first evidence checkpoint after claim", claims == 1 &&
                                            evidenceCheckpoints == 1 && !prefs.contains("interruption_unknown_hash") &&
                                            unknown == expectedUnknown && unknown == record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN) &&
                                            unknown.protectedEvidence == null && protectedEvidence.isNotEmpty() && admit(source))
                                        val reservation = checkNotNull(store.deleteAcknowledgement(source, event, record.id))
                                        assertTrue("The original native receipt is reserved before interruption",
                                            reservation.state == DeleteAcknowledgementState.PENDING &&
                                                reservation.receipt.attempt == DeleteAttemptIdentity.of(unknown))
                                        if (networkScenario) DeleteNetworkInterruptionJourney.retainMeasurement(prefs,
                                            checkNotNull(networkProbe).observation())
                                        val row = values()
                                        if (processScenario) assertTrue("Stop preserves the exact full owner, baseline, tombstone and pending receipt without proof",
                                            unknown.scope == source && unknown.eventId == event && unknown.intent.id == id("intent_id") &&
                                                unknown.id == DeleteAttemptIdentity.requestId(unknown.intent, unknown.format) &&
                                                unknown.intent.content == content && unknown.intent.base.content == content &&
                                                store.localDelete(source, event) == unknown.intent &&
                                                store.projectedBaseline(source, event) == unknown.intent.base &&
                                                store.transaction(source) { remote(event) } == unknown.intent.base &&
                                                reservation.receipt.intent == unknown.intent &&
                                                row == processNativeBeforeSend && row[Events.DELETED] == "1" && row[Events.DIRTY] == "1" &&
                                                shared.deleteJournal.readVerified(source, record.id, factual) == null &&
                                                shared.deleteJournal.readCompletion(source, record.id, factual) == null &&
                                                !prefs.getBoolean("submit_completed", false) && !prefs.getBoolean("ack_completed", false) &&
                                                !verifiedReturned && projection.writes.get() == 0)
                                        // Deliberately do not delegate persistence of the supplied protected response.
                                        val interruptionEdit = prefs.edit().putString("interruption_unknown_hash", bytesHash(DeleteAttemptStorageCodec.encode(unknown)))
                                            .putString("interruption_native_hash", nativeHash(row))
                                            .putString("interruption_ack_nonce", reservation.receipt.nonce)
                                            .putString("interruption_format_id", unknown.format.id)
                                            .putInt("interruption_format_version", unknown.format.version)
                                            .putInt("interruption_pid", Process.myPid()).putBoolean("evidence_interrupted", true)
                                        if (processScenario) DeleteProcessStopJourney.stop(app, prefs, unknown, protectedEvidence,
                                            interruptionEdit, ::accessible)
                                        assertTrue(interruptionEdit.commit())
                                        throw checkNotNull(interruption)
                                    }
                                } else journal
                                if (networkScenario) networkProbe = ProtonDeleteNetworkInterruptionProbe.arm(app, record, hash) {
                                    DeleteNetworkInterruptionJourney.cutWifi(app, prefs)
                                }
                                try { return actualPort.submit(record, guarded, admit, qualifySource, admitEvidence).also {
                                    if (it is DeleteSubmissionObservation.Conflict) conflictReturned = it
                                    if (it is DeleteSubmissionObservation.Verified) {
                                        verifiedReturned = true
                                        consent.set(false); coordinator.systemCreatePolicyChanged(source, true)
                                    }
                                } } finally { networkProbe?.close() }
                            }
                            override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
                                admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
                                inspections++; check(phase in setOf("SEND", "INSPECT") && actualPort != null)
                                return actualPort.inspect(record, journal, admit).also {
                                    if (it is DeleteSubmissionObservation.Verified) {
                                        verifiedReturned = true
                                        consent.set(false); coordinator.systemCreatePolicyChanged(source, true)
                                    }
                                }
                            }
                        }
                        val owners = object : SystemDeleteLocalOwner by coordinator.localDeleteOwners {
                            override suspend fun complete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
                                admit: (CalendarScope) -> Boolean): Boolean =
                                if (conflictScenario) error("Conflict fixture forbids completion")
                                else coordinator.localDeleteOwners.complete(result, journal, admit)
                            override suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
                                admit: (CalendarScope) -> Boolean): Boolean =
                                if (conflictScenario) error("Conflict fixture forbids native ACK")
                                else if (phase in setOf("SEND", "INSPECT")) false else coordinator.localDeleteOwners.acknowledge(result, journal, admit)
                        }
                        val operations = CoordinatorSystemDeleteOperations(owners, journal, port,
                            discoverLocal = { error("Only the exact enrolled deletion owner may be supplied") },
                            evidenceAdmission = coordinator::systemDeleteEvidenceAdmission)
                        if (conflictScenario) {
                            runRemoteConflict(phase, token, expectedConflictZone, prefs, source, event, content, timestamp, store, journal,
                                coordinator, operations, projection, factual, ::allowStore, ::values,
                                actualPort == null && actualRemote == null, { conflictReturned }) {
                                prepares == 0 && inspections == 0 && claims == 0 && !verifiedReturned &&
                                    sends == (if (phase == "CONFLICT") 1 else 0)
                            }
                            return@runBlocking
                        }
                        if (interruptionScenario && phase == "UNKNOWN_REPLAY") {
                            if (networkScenario && !prefs.getBoolean("network_inspection_held", false))
                                DeleteNetworkInterruptionJourney.assertOffline(app)
                            runUnknownReplay(prefs, source, event, content, store, journal, coordinator, operations, projection,
                                factual, ::allowStore, ::values, actualPort == null && actualRemote == null) {
                                prepares == 0 && sends == 0 && inspections == 0 && claims == 0 && evidenceCheckpoints == 0 && !verifiedReturned
                            }
                            return@runBlocking
                        }
                        if (phase == "LOCAL") {
                            // Local protected evidence only: no preparation, submission,
                            // inspection, native write or result retention is reachable.
                            val retained = checkNotNull(journal.read(source, id("attempt_id"), factual))
                            assertTrue("Local diagnosis needs the exact existing UNKNOWN", prefs.getBoolean("delete_requested", false) &&
                                retained.state == DeleteAttemptState.OUTCOME_UNKNOWN && retained.intent.id == id("intent_id") && retained.eventId == event)
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                putString("guarded_delete_evidence_shape", ProtonDeleteInspectionProbe.localEvidence(retained))
                            })
                            assertTrue("Local diagnosis has no remote or native write path", prepares == 0 && sends == 0 &&
                                inspections == 0 && projection.writes.get() == 0 && actualPort == null)
                            witness("guarded_delete_local_journal_only")
                            return@runBlocking
                        }
                        if (phase in setOf("SEND", "INSPECT")) {
                            // SEND is first-dispatch-only. INSPECT cannot reach preparation or
                            // submission and requires the exact durable UNKNOWN before any GET.
                            val requested = prefs.getBoolean("delete_requested", false)
                            if (phase == "SEND") assertTrue("SEND cannot repeat a requested deletion", !requested)
                            else {
                                assertTrue("INSPECT requires a previously requested deletion", requested)
                                val retained = checkNotNull(journal.read(source, id("attempt_id"), factual))
                                assertTrue("INSPECT needs the exact durable UNKNOWN", retained.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                                    retained.intent.id == id("intent_id") && retained.eventId == event)
                                if (interruptionScenario) {
                                    assertTrue("Inspection requires prior offline UNKNOWN replay in a different process",
                                        prefs.getBoolean("unknown_replayed", false) && prefs.getInt("unknown_replay_pid", -1) > 0 &&
                                            prefs.getInt("unknown_replay_pid", -1) != Process.myPid() &&
                                            prefs.getInt("interruption_pid", -1) > 0 && prefs.getInt("interruption_pid", -1) != Process.myPid())
                                    assertInterruptionState(prefs, source, event, content, store, journal, factual, values())
                                    assertTrue("Inspection starts with the only original history", deleteHistoryCount(app, prefix, source) == 1)
                                }
                                val evidenceShape = ProtonDeleteInspectionProbe.localEvidence(retained)
                                val shape = ProtonDeleteInspectionProbe.inspect(app, retained, hash)
                                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                    putString("guarded_delete_evidence_shape", evidenceShape)
                                    putString("guarded_delete_read_shape", shape.toString())
                                })
                                if (networkScenario && shape?.result == DeleteReadResult.PRESENT) {
                                    assertTrue("The reconnected read must retain the original authenticated context", shape.contextMatched)
                                    runNetworkPresentInspection(prefs, source, event, content, store, journal, coordinator,
                                        operations, projection, factual, ::allowStore, ::values) {
                                        prepares == 0 && sends == 0 && inspections == 1 && claims == 0 && evidenceCheckpoints == 0
                                    }
                                    return@runBlocking
                                }
                            }
                            val dirty = values()
                            assertTrue("Require the editor's present dirty tombstone", dirty[Events.DELETED] == "1" && dirty[Events.DIRTY] == "1")
                            var wakes = 0
                            if (phase == "SEND") {
                                assertTrue("Capture only the exact editor tombstone",
                                    coordinator.captureLocalDelete(source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
                                allowStore()
                                val local = checkNotNull(store.localDelete(source, event))
                                assertTrue("Require exact known-empty ordinary deletion", local.id == id("intent_id") &&
                                    local.base.content == content && local.content == content && local.state == LocalEditState.PAUSED)
                                var interrupted = false
                                try {
                                    assertTrue("Guarded DELETE orchestration must not fail", !operations.process(source,
                                        RetainedLocalChangeRef(event, local.id, MutationKind.DELETE, LocalEditState.PAUSED),
                                        MutationId("unused-traversal-candidate"), timestamp, coordinator.systemDeleteRunAdmission(source)) { wakes++ })
                                } catch (cancelled: CancellationException) {
                                    if (!interruptionScenario || processScenario || cancelled !== interruption) throw cancelled
                                    interrupted = true
                                }
                                assertTrue("Exactly one preparation and submission", prepares == 1 && sends == 1 && inspections == 0)
                                if (interruptionScenario) {
                                    assertTrue("Only the injected persistence cancellation escapes; no proof or native ACK", interrupted &&
                                        claims == 1 && evidenceCheckpoints == 1 && !verifiedReturned && wakes == 0 && projection.writes.get() == 0 && values() == dirty)
                                    allowStore()
                                    assertInterruptionState(prefs, source, event, content, store, journal, factual, dirty)
                                    assertTrue("Retain exactly one immutable history", deleteHistoryCount(app, prefix, source) == 1)
                                    witness("guarded_delete_interruption_checkpoint", "guarded_delete_interruption_unknown_retained",
                                        "guarded_delete_interruption_no_proof_or_ack")
                                    if (networkScenario) witness("guarded_delete_network_socket_failure", "guarded_delete_network_offline_confirmed")
                                    return@runBlocking
                                }
                            } else {
                                operations.inspect(TitleUpdateWorkQuery(source), coordinator.systemDeleteRunAdmission(source)) { wakes++ }
                                assertTrue("An uncertain request is inspect-only", prepares == 0 && sends == 0)
                            }
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                putBoolean("guarded_delete_wire_entered", prepares > 0)
                                putBoolean("guarded_delete_wire_returned", wireReturned)
                                putBoolean("guarded_delete_submit_entered", sends > 0)
                                putBoolean("guarded_delete_inspect_entered", inspections > 0)
                                putBoolean("guarded_delete_verified_returned", verifiedReturned)
                                putBoolean("guarded_delete_proof_woke_recovery", wakes > 0)
                            })
                            val attempt = id("attempt_id")
                            val record = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            // A restart may find proof already retained before SEND's final
                            // bookkeeping. The stop-ACK owner above also covers that window.
                            consent.set(false); coordinator.systemCreatePolicyChanged(source, true)
                            assertTrue("Keep durable UNKNOWN, authenticated absence and frozen content",
                                record.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
                                    (if (interruptionScenario) record.protectedEvidence == null &&
                                        frozen("interruption_unknown_hash") == bytesHash(DeleteAttemptStorageCodec.encode(record))
                                     else record.protectedEvidence != null) &&
                                    record.id == DeleteAttemptIdentity.requestId(record.intent, record.format) &&
                                    record.intent.id == id("intent_id") && record.intent.base.eventId == event &&
                                    record.intent.content == content && result.record == record.copy(protectedEvidence = null))
                            allowStore()
                            assertTrue("Retain proof with pending native ACK and unchanged tombstone", !consent.get() &&
                                projection.writes.get() == 0 && values() == dirty &&
                                store.deleteAcknowledgement(source, event, attempt)?.state == DeleteAcknowledgementState.PENDING &&
                                journal.readCompletion(source, attempt, factual) == null)
                            val proofEdit = prefs.edit().putString("attempt_hash", bytesHash(DeleteAttemptStorageCodec.encode(record)))
                                .putString("result_hash", bytesHash(DeleteProofStorageCodec.encodeResult(result)))
                                .putBoolean("submit_completed", true)
                            if (interruptionScenario) {
                                assertTrue("Never replace a successful inspection process witness", !prefs.contains("interruption_inspect_pid"))
                                proofEdit.putInt("interruption_inspect_pid", Process.myPid())
                            }
                            assertTrue(proofEdit.commit())
                            witness("guarded_delete_verified_absence", "guarded_delete_proof_survives_revocation", "guarded_delete_native_ack_deferred")
                            if (interruptionScenario) {
                                assertTrue("Inspection retains the only history", deleteHistoryCount(app, prefix, source) == 1)
                                witness("guarded_delete_interruption_verified_absence", "guarded_delete_interruption_native_ack_deferred")
                            }
                        } else {
                            runOfflineAcknowledgement(phase, interruptionScenario, prefs, source, event, store, journal,
                                coordinator, operations, projection, factual, ::allowStore, ::values) {
                                actualPort == null && actualRemote == null && prepares == 0 && sends == 0 && inspections == 0
                            }
                        }
                    } finally {
                        coordinator.authenticationChanged(false)
                        jobs.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            }
        } finally {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            native.admitOperation = { false }
        }
    }

    private suspend fun runNetworkPresentInspection(
        prefs: android.content.SharedPreferences, source: CalendarScope, event: EventId, content: EventContent,
        store: ImportStore, journal: DeleteRecoveryJournal, coordinator: ImportCoordinator,
        operations: CoordinatorSystemDeleteOperations, projection: IsolatedProjection,
        factual: (CalendarScope) -> Boolean, allowStore: () -> Unit, values: () -> Map<String, String?>,
        noSend: () -> Boolean,
    ) {
        assertTrue("Require the measured interrupted original call", prefs.getBoolean("network_socket_failure", false) &&
            prefs.getInt("network_calls", 0) == 1 && prefs.getInt("network_body_starts", 0) == 1)
        val before = values()
        allowStore()
        val original = assertInterruptionState(prefs, source, event, content, store, journal, factual, before)
        var wakes = 0
        val progress = operations.inspect(TitleUpdateWorkQuery(source), coordinator.systemDeleteRunAdmission(source)) { wakes++ }
        assertTrue("A still-present original is held, never resent or falsely acknowledged", progress.held == 1 &&
            progress.completed == 0 && progress.failed == 0 && !progress.inactive && wakes == 0 && noSend() && projection.writes.get() == 0)
        allowStore()
        assertTrue("Inspection preserves exact UNKNOWN, pending native receipt and tombstone",
            assertInterruptionState(prefs, source, event, content, store, journal, factual, values()) == original && values() == before)
        assertTrue(prefs.edit().putBoolean("network_inspection_held", true).putInt("network_inspection_pid", Process.myPid()).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("guarded_delete_network_inspection_held", true)
            putBoolean("guarded_delete_network_no_resend_or_ack", true)
        })
    }

    /** Keep offline acknowledgement in a separate coroutine to bound generated method size. */
    private suspend fun runOfflineAcknowledgement(
        phase: String, interruptionScenario: Boolean, prefs: android.content.SharedPreferences,
        source: CalendarScope, event: EventId, store: ImportStore, journal: DeleteRecoveryJournal,
        coordinator: ImportCoordinator, operations: CoordinatorSystemDeleteOperations,
        projection: IsolatedProjection, factual: (CalendarScope) -> Boolean,
        allowStore: () -> Unit, values: () -> Map<String, String?>, noTraffic: () -> Boolean,
    ) {
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        fun id(key: String) = MutationId(frozen(key))
        fun witness(vararg names: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { names.forEach { putBoolean(it, true) } })
        val app = compose.activity.application
        val prefix = "calendar-delete-guarded-v1-${frozen("token")}"
        assertTrue("Recovery requires completed SEND in another process", prefs.getBoolean("submit_completed", false) &&
            prefs.getInt("submit_pid", -1) > 0 && prefs.getInt("submit_pid", -1) != Process.myPid())
        if (interruptionScenario) assertTrue("ACK and replay require successful inspection in another process",
            prefs.getInt("interruption_inspect_pid", -1) > 0 && prefs.getInt("interruption_inspect_pid", -1) != Process.myPid())
        if (phase == "REPLAY") assertTrue("REPLAY requires ACK in another process", prefs.getBoolean("ack_completed", false) &&
            prefs.getInt("ack_pid", -1) > 0 && prefs.getInt("ack_pid", -1) != Process.myPid())
        val attempt = id("attempt_id")
        val original = checkNotNull(journal.read(source, attempt, factual))
        val result = checkNotNull(journal.readVerified(source, attempt, factual))
        assertTrue("Bind immutable request and proof before recovery",
            frozen("attempt_hash") == bytesHash(DeleteAttemptStorageCodec.encode(original)) &&
                frozen("result_hash") == bytesHash(DeleteProofStorageCodec.encodeResult(result)))
        val before = values()
        val complete = journal.readCompletion(source, attempt, factual) != null
        val admission = coordinator.systemDeleteProofAdmission(source)
        val progress = operations.recoverOffline(TitleUpdateWorkQuery(source), admission)
        assertTrue("Recover the one durable deletion without HTTP", progress ==
            if (complete) TitleUpdateOfflineRecoveryProgress() else TitleUpdateOfflineRecoveryProgress(completed = 1))
        allowStore()
        val receipt = checkNotNull(store.deleteAcknowledgement(source, event, attempt))
        val completion = checkNotNull(journal.readCompletion(source, attempt, factual))
        val after = values()
        assertTrue("Keep deleted row, restore exact ACK and durable completion", after[Events.DELETED] == "1" &&
            after[Events.DIRTY] == "0" && receipt.state == DeleteAcknowledgementState.RESTORED &&
            receipt.receipt.matches(DeleteSubmissionObservation.Verified(result.record)) &&
            completion == DeleteCompletion.of(result, receipt))
        assertTrue("Only ACK metadata and Provider-cleared mutator history may change", (before - ACK_FIELDS) == (after - ACK_FIELDS) &&
            after[Events.MUTATORS] == null &&
            projection.writes.get() == if (complete) 0 else 1)
        allowStore()
        store.transaction(source) { putRemote(original.intent.base); putProjection(ProjectionIntent(source, original.intent.base)) }
        store.requeueAll(source)
        assertTrue("A staged old upsert cannot recreate the acknowledged deletion", store.pending(source).isEmpty() &&
            store.stabilizedProjection(source, event) == null && values() == after)
        assertTrue("Completed deletion is idempotent", operations.recoverOffline(TitleUpdateWorkQuery(source), admission) ==
            TitleUpdateOfflineRecoveryProgress() && coordinator.localDeleteOwners.acknowledge(result, journal, admission) &&
            coordinator.localDeleteOwners.complete(result, journal, admission))
        assertTrue("No offline preparation, inspection, resend or replacement history", noTraffic() &&
            journal.read(source, attempt, factual) == original && journal.readVerified(source, attempt, factual) == result &&
            journal.readCompletion(source, attempt, factual) == completion &&
            journal.page(DeleteWorkQuery(source), factual)?.entries?.isEmpty() == true)
        if (interruptionScenario) {
            assertTrue("Completion preserves the original UNKNOWN history", original.protectedEvidence == null &&
                frozen("interruption_unknown_hash") == bytesHash(DeleteAttemptStorageCodec.encode(original)) &&
                deleteHistoryCount(app, prefix, source) == 1)
            witness(if (phase == "ACK") "guarded_delete_interruption_ack" else "guarded_delete_interruption_replay")
        }
        if (phase == "ACK") {
            assertTrue(prefs.edit().putBoolean("ack_completed", true).putInt("ack_pid", Process.myPid()).commit())
            witness("guarded_delete_offline_ack_new_process", "guarded_delete_same_row_ack", "guarded_delete_old_upsert_suppressed")
        } else {
            assertTrue("REPLAY starts completed and performs no native write", complete && projection.writes.get() == 0 && before == after)
            assertTrue(prefs.edit().putBoolean("replay_completed", true).commit())
            witness("guarded_delete_replay_new_process", "guarded_delete_replay_no_resend", "guarded_delete_history_immutable")
        }
    }

    private suspend fun assertInterruptionState(
        prefs: android.content.SharedPreferences, source: CalendarScope, event: EventId, content: EventContent,
        store: ImportStore, journal: DeleteRecoveryJournal, factual: (CalendarScope) -> Boolean,
        row: Map<String, String?>,
    ): DeleteAttemptRecord {
        if (prefs.getString("scenario", null) == "PROCESS_INTERRUPTION") DeleteProcessStopJourney.assertRestarted(prefs)
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        val attempt = MutationId(frozen("attempt_id"))
        val record = checkNotNull(journal.read(source, attempt, factual))
        val reservation = checkNotNull(store.deleteAcknowledgement(source, event, attempt))
        assertTrue("Bind the original full UNKNOWN request, source, base, owner, native row and pending receipt",
            prefs.getBoolean("evidence_interrupted", false) && prefs.getBoolean("delete_requested", false) &&
                !prefs.getBoolean("submit_completed", false) && !prefs.getBoolean("ack_completed", false) &&
                record.state == DeleteAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence == null &&
                record.scope == source && record.eventId == event && record.intent.id.value == frozen("intent_id") &&
                record.id == DeleteAttemptIdentity.requestId(record.intent, record.format) &&
                record.format == AdapterFormat(frozen("interruption_format_id"), prefs.getInt("interruption_format_version", -1)) &&
                record.intent.content == content && record.intent.base.content == content &&
                frozen("interruption_unknown_hash") == bytesHash(DeleteAttemptStorageCodec.encode(record)) &&
                frozen("interruption_native_hash") == nativeHash(row) && row[Events.DELETED] == "1" && row[Events.DIRTY] == "1" &&
                store.localDelete(source, event) == record.intent && store.projectedBaseline(source, event) == record.intent.base &&
                store.transaction(source) { remote(event) } == record.intent.base &&
                reservation.state == DeleteAcknowledgementState.PENDING && reservation.receipt.intent == record.intent &&
                reservation.receipt.attempt == DeleteAttemptIdentity.of(record) &&
                journal.readVerified(source, attempt, factual) == null && journal.readCompletion(source, attempt, factual) == null)
        assertTrue("Preserve the original reserved receipt", frozen("interruption_ack_nonce") == reservation.receipt.nonce)
        return record
    }

    private suspend fun runUnknownReplay(
        prefs: android.content.SharedPreferences, source: CalendarScope, event: EventId, content: EventContent,
        store: ImportStore, journal: DeleteRecoveryJournal, coordinator: ImportCoordinator,
        operations: CoordinatorSystemDeleteOperations, projection: IsolatedProjection,
        factual: (CalendarScope) -> Boolean, allowStore: () -> Unit, values: () -> Map<String, String?>,
        offlinePorts: Boolean, noTraffic: () -> Boolean,
    ) {
        assertTrue("UNKNOWN replay requires a separate process with no HTTP composition", offlinePorts &&
            prefs.getInt("interruption_pid", -1) > 0 && prefs.getInt("interruption_pid", -1) != Process.myPid())
        allowStore()
        val before = values()
        val original = assertInterruptionState(prefs, source, event, content, store, journal, factual, before)
        val reservation = checkNotNull(store.deleteAcknowledgement(source, event, original.id))
        val prefix = "calendar-delete-guarded-v1-${checkNotNull(prefs.getString("token", null))}"
        val app = compose.activity.application
        assertTrue("Replay starts with exactly one history", deleteHistoryCount(app, prefix, source) == 1)
        assertTrue("Enable the isolated normal process admission so UNKNOWN is exercised",
            coordinator.systemDeleteRunAdmission(source)(source))
        var wakes = 0
        assertTrue("Normal process traverses the UNKNOWN guard without preparation or submission", !operations.process(source,
            RetainedLocalChangeRef(event, original.intent.id, MutationKind.DELETE, LocalEditState.PAUSED),
            MutationId("unused-traversal-candidate"), Instant.parse(checkNotNull(prefs.getString("timestamp", null))),
            coordinator.systemDeleteRunAdmission(source)) { wakes++ })
        assertTrue("Offline recovery holds the unresolved request", operations.recoverOffline(TitleUpdateWorkQuery(source),
            coordinator.systemDeleteProofAdmission(source)) == TitleUpdateOfflineRecoveryProgress(held = 1))
        allowStore()
        assertTrue("Replay keeps the full owner, receipt, native row and immutable single history", noTraffic() && wakes == 0 &&
            projection.writes.get() == 0 && values() == before &&
            assertInterruptionState(prefs, source, event, content, store, journal, factual, values()) == original &&
            store.deleteAcknowledgement(source, event, original.id) == reservation && deleteHistoryCount(app, prefix, source) == 1)
        assertTrue("The first replay witness and success must be retained atomically",
            prefs.getBoolean("unknown_replayed", false) == prefs.contains("unknown_replay_pid") &&
                (!prefs.contains("unknown_replay_pid") || prefs.getInt("unknown_replay_pid", -1) > 0))
        val replayEdit = prefs.edit().putBoolean("unknown_replayed", true)
        if (!prefs.contains("unknown_replay_pid")) replayEdit.putInt("unknown_replay_pid", Process.myPid())
        assertTrue(replayEdit.commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("guarded_delete_interruption_unknown_replay", true)
            putBoolean("guarded_delete_interruption_no_http_or_ack", true)
            putBoolean("guarded_delete_interruption_history_immutable", true)
        })
    }

    /** Separate coroutine state machine keeps conflict checks out of the normal journey. */
    private suspend fun runRemoteConflict(
        phase: String, token: String, expectedZone: ZoneId, prefs: android.content.SharedPreferences,
        source: CalendarScope, event: EventId, content: EventContent, timestamp: Instant,
        store: ImportStore, journal: DeleteRecoveryJournal, coordinator: ImportCoordinator,
        operations: CoordinatorSystemDeleteOperations, projection: IsolatedProjection,
        factual: (CalendarScope) -> Boolean, allowStore: () -> Unit, values: () -> Map<String, String?>,
        offlinePorts: Boolean, conflictObservation: () -> DeleteSubmissionObservation.Conflict?,
        trafficIsExpected: () -> Boolean,
    ) {
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        fun id(key: String) = MutationId(frozen(key))
        fun witness(vararg names: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { names.forEach { putBoolean(it, true) } })
        val expectedContent = content.copy(title = "Calendeer QA — retained DELETE conflict $token",
            time = (content.time as EventTime.Timed).copy(zone = expectedZone))
        assertTrue("Conflict journeys never accept requested or completed deletion history",
            !prefs.getBoolean("delete_requested", false) && !prefs.getBoolean("submit_completed", false) &&
                !prefs.getBoolean("ack_completed", false))
        val attempt = id("attempt_id")
        val original = checkNotNull(journal.read(source, attempt, factual))
        assertTrue("Require exact frozen unclaimed preparation", original.state == DeleteAttemptState.PREPARED &&
            original.protectedEvidence == null && original.intent.id == id("intent_id") &&
            original.eventId == event && original.intent.content == content && original.intent.base.content == content &&
            original.id == DeleteAttemptIdentity.requestId(original.intent, original.format) &&
            frozen("conflict_wire_hash") == bytesHash(DeleteAttemptStorageCodec.encode(original)) &&
            journal.readVerified(source, attempt, factual) == null &&
            journal.readCompletion(source, attempt, factual) == null)
        val before = values()
        assertTrue("Conflict requires the exact editor tombstone",
            before[Events.DELETED] == "1" && before[Events.DIRTY] == "1")
        fun nativeHash(row: Map<String, String?>) = framedHash(*row.toSortedMap().flatMap {
            listOf(it.key, if (it.value == null) "NULL" else "VALUE", it.value.orEmpty())
        }.toTypedArray())
        var wakes = 0
        if (phase == "CONFLICT") {
            assertTrue("Conflict is first-preflight-only with no existing local owner",
                !prefs.getBoolean("conflict_entered", false) && !prefs.getBoolean("conflict_completed", false) &&
                    store.localDelete(source, event) == null &&
                    store.deleteAcknowledgement(source, event, attempt) == null &&
                    store.conflictArchives(ConflictArchiveQuery(source)).entries.isEmpty())
            assertTrue("Capture only the exact enrolled tombstone",
                coordinator.captureLocalDelete(source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
            allowStore()
            assertTrue("Captured owner must match prepared wire", store.localDelete(source, event) == original.intent)
            assertTrue("Guarded conflict orchestration must succeed", !operations.process(source,
                RetainedLocalChangeRef(event, id("intent_id"), MutationKind.DELETE, LocalEditState.PAUSED),
                MutationId("unused-traversal-candidate"), timestamp, coordinator.systemDeleteRunAdmission(source)) { wakes++ })
            val observed = checkNotNull(conflictObservation())
            assertTrue("Require typed authenticated title conflict with the declared editor zone before claim",
                observed.record == original && observed.event.eventId == event &&
                    observed.event != original.intent.base && observed.event.content ==
                    expectedContent &&
                    trafficIsExpected() && wakes == 0)
        } else if (phase == "CONFLICT_REVIEW") {
            assertTrue("Local review requires the already entered first preflight and offline ports",
                prefs.getBoolean("conflict_entered", false) && offlinePorts)
        } else {
            assertTrue("Conflict replay requires successful preflight in another process",
                prefs.getBoolean("conflict_completed", false) &&
                    prefs.getInt("conflict_pid", -1) > 0 && prefs.getInt("conflict_pid", -1) != Process.myPid() &&
                    offlinePorts)
        }
        allowStore()
        val owner = checkNotNull(store.localDelete(source, event))
        val refs = store.conflictArchives(ConflictArchiveQuery(source))
        assertTrue("Retain one exact conflict owner and dossier", owner == original.intent.copy(state = LocalEditState.CONFLICT) &&
            refs.next == null && refs.entries.size == 1)
        val ref = refs.entries.single()
        assertTrue("Bind archive to exact DELETE owner", ref.eventId == event && ref.mutationId == owner.id && ref.kind == MutationKind.DELETE)
        val detail = checkNotNull(store.conflictArchive(ref))
        val observations = store.conflictObservations(ConflictObservationQuery(ref))
        val received = observations.entries.filter { it.origin == ConflictObservationOrigin.RECEIVED }
        val retained = received.singleOrNull()?.change as? RemoteChange.Upsert
        assertTrue("Retain full original and full observed variant with the declared editor zone", detail.base == original.intent.base &&
            detail.local == content && detail.format == owner.format && observations.next == null &&
            retained != null && retained.eventId == event && retained.content ==
            expectedContent &&
            received.single().kind == ConflictObservationKind.VERIFIED && received.single().format == owner.format &&
            (phase != "CONFLICT" || retained == conflictObservation()?.event))
        val archiveHash = framedHash(ref.id.toString(), owner.state.name,
            TextUpdateAcknowledgementCodec.encodeChange(detail.base).toString(),
            *observations.entries.flatMap { observation -> listOf(observation.id.toString(), observation.kind.name,
                observation.origin.name, observation.observedAt?.toString().orEmpty(),
                observation.format?.id.orEmpty(), observation.format?.version?.toString().orEmpty(),
                observation.change?.let { TextUpdateAcknowledgementCodec.encodeChange(it).toString() }.orEmpty(),
                observation.deferredReason?.name.orEmpty()) }.toTypedArray())
        val reservation = checkNotNull(store.deleteAcknowledgement(source, event, attempt))
        if (phase == "CONFLICT_REPLAY") {
            assertTrue("Restart retains immutable native row and full conflict evidence",
                nativeHash(before) == frozen("conflict_native_hash") && archiveHash == frozen("conflict_archive_hash") &&
                    reservation.receipt.nonce == frozen("conflict_ack_nonce"))
            assertTrue("Offline recovery holds the unclaimed conflict without HTTP or ACK",
                operations.recoverOffline(TitleUpdateWorkQuery(source), coordinator.systemDeleteProofAdmission(source)) ==
                    TitleUpdateOfflineRecoveryProgress(held = 1))
        }
        allowStore()
        assertTrue("No claim, evidence, completion, ACK or native mutation",
            trafficIsExpected() && wakes == 0 &&
                journal.read(source, attempt, factual) == original &&
                journal.readVerified(source, attempt, factual) == null && journal.readCompletion(source, attempt, factual) == null &&
                reservation.state == DeleteAcknowledgementState.PENDING && reservation.receipt.intent == original.intent &&
                store.deleteAcknowledgement(source, event, attempt) == reservation &&
                store.localDelete(source, event) == owner && projection.writes.get() == 0 && values() == before)
        val afterRefs = store.conflictArchives(ConflictArchiveQuery(source))
        val afterDetail = checkNotNull(store.conflictArchive(ref))
        val afterObservations = store.conflictObservations(ConflictObservationQuery(ref))
        assertTrue("Offline traversal cannot alter full conflict dossier", afterRefs.entries == refs.entries &&
            afterRefs.next == refs.next && afterDetail.format == detail.format && afterDetail.base == detail.base &&
            afterDetail.local == detail.local && afterObservations.next == observations.next &&
            afterObservations.entries.size == observations.entries.size &&
            afterObservations.entries.zip(observations.entries).all { (after, beforeObservation) ->
                after.id == beforeObservation.id && after.kind == beforeObservation.kind &&
                    after.origin == beforeObservation.origin && after.observedAt == beforeObservation.observedAt &&
                    after.format == beforeObservation.format && after.change == beforeObservation.change &&
                    after.deferredReason == beforeObservation.deferredReason
            })
        if (phase in setOf("CONFLICT", "CONFLICT_REVIEW")) {
            assertTrue("Bind a legacy zone only after full immutable local conflict validation",
                prefs.getString("conflict_expected_zone", null)?.let { it == expectedZone.id } ?: (phase == "CONFLICT_REVIEW"))
            if (prefs.getBoolean("conflict_completed", false)) {
                assertTrue("Completed local review never replaces frozen conflict evidence",
                    phase == "CONFLICT_REVIEW" && nativeHash(before) == frozen("conflict_native_hash") &&
                        archiveHash == frozen("conflict_archive_hash") && reservation.receipt.nonce == frozen("conflict_ack_nonce"))
            } else assertTrue(prefs.edit().putBoolean("conflict_completed", true).putInt("conflict_pid", Process.myPid())
                    .putString("conflict_expected_zone", expectedZone.id)
                    .putString("conflict_native_hash", nativeHash(before)).putString("conflict_archive_hash", archiveHash)
                    .putString("conflict_ack_nonce", reservation.receipt.nonce).commit())
            if (phase == "CONFLICT") witness("guarded_delete_remote_conflict", "guarded_delete_conflict_preclaim",
                "guarded_delete_conflict_variant_retained")
            else witness("guarded_delete_conflict_review_local_only", "guarded_delete_conflict_variant_retained",
                "guarded_delete_conflict_history_immutable")
        } else witness("guarded_delete_conflict_replay_new_process", "guarded_delete_conflict_history_immutable",
            "guarded_delete_conflict_replay_no_http_or_ack")
    }

    companion object {
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA3, Events.MUTATORS)
        private fun nativeHash(row: Map<String, String?>) = framedHash(*row.toSortedMap().flatMap {
            listOf(it.key, if (it.value == null) "NULL" else "VALUE", it.value.orEmpty())
        }.toTypedArray())
        private fun deleteHistoryCount(context: Context, prefix: String, source: CalendarScope): Int =
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("$prefix-shared-attempts.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT count(*) FROM delete_attempts WHERE account=? AND calendar=? AND generation=?",
                    arrayOf(source.account.value, source.calendar.value, source.generation.toString())).use { cursor ->
                    check(cursor.moveToFirst()); cursor.getInt(0)
                }
            }
        private fun bytesHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun framedHash(vararg parts: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            parts.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

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
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonDeleteInspectionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
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
 * deleteAccountHash (SHA-256), deleteJourneyPhase=PREPARE|SEND|LOCAL|INSPECT|ACK|REPLAY.
 * PREPARE opens only the dedicated event in foundation.e.calendar. The external operator
 * must delete it there before SEND. Stop the process before ACK and again before REPLAY.
 * Offline sentinels cover this fixture composition, not independent application traffic.
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
        assertTrue("Require explicit fixture, account and phase", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && phase in setOf("PREPARE", "SEND", "LOCAL", "INSPECT", "ACK", "REPLAY"))
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
        if (!prefs.contains("token")) {
            assertTrue("Only PREPARE can enroll a new disposable fixture", phase == "PREPARE")
            val start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
            assertTrue("Freeze fixture identities before CREATE", prefs.edit().putString("token", token)
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
                    assertTrue(prefs.edit().putBoolean("prepared", true).commit())
                    compose.runOnUiThread {
                        compose.activity.startActivity(Intent(Intent.ACTION_VIEW,
                            ContentUris.withAppendedId(Events.CONTENT_URI, prefs.getLong("row", -1)))
                            .setPackage("foundation.e.calendar"))
                    }
                    witness("guarded_delete_prepared", "guarded_delete_dedicated_event_opened")
                    return@runBlocking
                }
                assertTrue("SEND and recovery require the enrolled PREPARE", prefs.getBoolean("prepared", false))
                TitleUpdateAttemptStore(app, "$prefix-shared-attempts.db").use { shared ->
                    val journal = shared.deleteJournal
                    shared.admitDispatch = { error("No title dispatch in deletion fixture") }
                    val factual: (CalendarScope) -> Boolean = { it == source && accessible() }
                    val consent = AtomicBoolean(phase in setOf("SEND", "INSPECT"))
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() }, systemTitlesEnabled = { false },
                        systemTextsEnabled = { false }, systemTimesEnabled = { false },
                        systemDeletesEnabled = { it == source && consent.get() && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val event = EventId(frozen("event"))
                        val actualPort = if (phase in setOf("SEND", "INSPECT")) ProtonAuthentication.deletions(app) else null
                        var prepares = 0; var sends = 0; var inspections = 0
                        var wireReturned = false; var verifiedReturned = false
                        val port = object : CalendarDeleteSubmissionPort {
                            override val deleteFormat get() = actualPort?.deleteFormat
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
                                sends++; check(phase == "SEND" && actualPort != null)
                                assertTrue("Never resend a requested DELETE", !prefs.getBoolean("delete_requested", false) &&
                                    record.id.value == frozen("attempt_id"))
                                assertTrue(prefs.edit().putBoolean("delete_requested", true).putInt("submit_pid", Process.myPid()).commit())
                                return actualPort.submit(record, journal, admit, qualifySource, admitEvidence).also {
                                    if (it is DeleteSubmissionObservation.Verified) {
                                        verifiedReturned = true
                                        consent.set(false); coordinator.systemCreatePolicyChanged(source, true)
                                    }
                                }
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
                            override suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
                                admit: (CalendarScope) -> Boolean): Boolean =
                                if (phase in setOf("SEND", "INSPECT")) false else coordinator.localDeleteOwners.acknowledge(result, journal, admit)
                        }
                        val operations = CoordinatorSystemDeleteOperations(owners, journal, port,
                            discoverLocal = { error("Only the exact enrolled deletion owner may be supplied") },
                            evidenceAdmission = coordinator::systemDeleteEvidenceAdmission)
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
                                val evidenceShape = ProtonDeleteInspectionProbe.localEvidence(retained)
                                val shape = ProtonDeleteInspectionProbe.inspect(app, retained, hash)
                                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                    putString("guarded_delete_evidence_shape", evidenceShape)
                                    putString("guarded_delete_read_shape", shape.toString())
                                })
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
                                assertTrue("Guarded DELETE orchestration must not fail", !operations.process(source,
                                    RetainedLocalChangeRef(event, local.id, MutationKind.DELETE, LocalEditState.PAUSED),
                                    MutationId("unused-traversal-candidate"), timestamp, coordinator.systemDeleteRunAdmission(source)) { wakes++ })
                                assertTrue("Exactly one preparation and submission", prepares == 1 && sends == 1 && inspections == 0)
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
                                record.state == DeleteAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                                    record.id == DeleteAttemptIdentity.requestId(record.intent, record.format) &&
                                    record.intent.id == id("intent_id") && record.intent.base.eventId == event &&
                                    record.intent.content == content && result.record == record.copy(protectedEvidence = null))
                            allowStore()
                            assertTrue("Retain proof with pending native ACK and unchanged tombstone", !consent.get() &&
                                projection.writes.get() == 0 && values() == dirty &&
                                store.deleteAcknowledgement(source, event, attempt)?.state == DeleteAcknowledgementState.PENDING &&
                                journal.readCompletion(source, attempt, factual) == null)
                            assertTrue(prefs.edit().putString("attempt_hash", bytesHash(DeleteAttemptStorageCodec.encode(record)))
                                .putString("result_hash", bytesHash(DeleteProofStorageCodec.encodeResult(result)))
                                .putBoolean("submit_completed", true).commit())
                            witness("guarded_delete_verified_absence", "guarded_delete_proof_survives_revocation", "guarded_delete_native_ack_deferred")
                        } else {
                            assertTrue("Recovery requires completed SEND in another process", prefs.getBoolean("submit_completed", false) &&
                                prefs.getInt("submit_pid", -1) > 0 && prefs.getInt("submit_pid", -1) != Process.myPid())
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
                            assertTrue("No offline preparation, inspection, resend or replacement history", prepares == 0 && sends == 0 && inspections == 0 &&
                                journal.read(source, attempt, factual) == original && journal.readVerified(source, attempt, factual) == result &&
                                journal.readCompletion(source, attempt, factual) == completion &&
                                journal.page(DeleteWorkQuery(source), factual)?.entries?.isEmpty() == true)
                            if (phase == "ACK") {
                                assertTrue(prefs.edit().putBoolean("ack_completed", true).putInt("ack_pid", Process.myPid()).commit())
                                witness("guarded_delete_offline_ack_new_process", "guarded_delete_same_row_ack", "guarded_delete_old_upsert_suppressed")
                            } else {
                                assertTrue("REPLAY starts completed and performs no native write", complete && projection.writes.get() == 0 && before == after)
                                assertTrue(prefs.edit().putBoolean("replay_completed", true).commit())
                                witness("guarded_delete_replay_new_process", "guarded_delete_replay_no_resend", "guarded_delete_history_immutable")
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

    companion object {
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA3, Events.MUTATORS)
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

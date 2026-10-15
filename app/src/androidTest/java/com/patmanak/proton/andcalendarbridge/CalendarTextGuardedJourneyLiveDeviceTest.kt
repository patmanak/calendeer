// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real text service journey, retaining a synthetic event and isolated journals.
 * Explicit composition uses the production owner, preparation, submission and offline recovery
 * boundaries while the application feature gate remains closed. This is an ordinary imported
 * timed event, not CREATE handoff, an editor journey or automatic worker qualification.
 * Recovery's sentinels forbid fixture-path preparation/transport; they do not measure traffic
 * from the independently running application. No account security or user mode is changed.
 */
class CalendarTextGuardedJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class IsolatedProjection(private val native: AndroidCalendarProjection,
        private val logical: CalendarScope, private val physical: CalendarScope,
        private val access: () -> Boolean) : CalendarProjection {
        val writes = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val textUpdateAcknowledgementProtocol get() = native.textUpdateAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("The coordinator cannot create calendars")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No content projection during recovery")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedSimpleEdit(physical, base)
        }
        private fun mapped(receipt: TextUpdateAcknowledgementReceipt): TextUpdateAcknowledgementReceipt {
            check(receipt.intent.scope == logical && receipt.handoff == null)
            return receipt.copy(attempt = receipt.attempt.copy(scope = physical),
                intent = receipt.intent.copy(scope = physical), projection = receipt.projection.copy(scope = physical))
        }
        override suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            writes.incrementAndGet()
            val expected = mapped(receipt)
            return native.acknowledgeTextUpdate(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTextUpdateAcknowledgement(receipt) }
        }
        override suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            val expected = mapped(receipt)
            return native.inspectTextUpdateAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTextUpdateAcknowledgement(receipt) }
        }
    }

    @Test fun guardedSubmissionThenRestartedOfflineAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTextGuardedJourney") == "true")
        val token = args.getString("textFixtureToken").orEmpty()
        val hash = args.getString("textAccountHash").orEmpty()
        val phase = args.getString("textGuardedPhase").orEmpty()
        assertTrue("Explicit fixture, account and phase are required", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && phase in setOf("submit", "recover"))
        val requestedCalendarName = "Calendeer QA Automatic Worker"
        val prefix = "calendar-text-guarded-v1-$token"
        val timeProfile = TitleJourneyTimeProfile.create(null, false, false, null)
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let {
            it.authenticated && it.selections.isNotEmpty() && !it.busy
        } }
        val prefs = app.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available &&
            it.summary.title == requestedCalendarName }
        assertTrue("An accessible existing calendar is required", selected.isNotEmpty())
        assertTrue("The dedicated QA calendar must resolve uniquely", selected.size == 1)
        val source = if (prefs.contains("token")) {
            assertTrue("Never replace an enrolled fixture", prefs.getString("token", null) == token &&
                prefs.getString("account_hash", null) == hash && prefs.getString("journey_label", null) == prefix)
            assertTrue("Never change the enrolled time profile or its exclusive dates", timeProfile.matchesFrozen(
                prefs.getInt("content_profile", -1), prefs.getString("start", null), prefs.getString("end", null)))
            CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        } else {
            assertTrue("Recovery cannot enroll a new fixture", phase == "submit")
            selected.first().scope
        }
        assertTrue("Never move the enrolled source to another calendar", selected.single().scope == source &&
            (!prefs.contains("token") || prefs.getString("source_calendar_name", null) == requestedCalendarName))
        assertTrue("Authorize the exact account before mutation", ProtonCreateSubmissionProbe.matchesAccount(app, source.account, hash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.any { it.scope == source && it.enabled && it.available &&
                it.summary.title == requestedCalendarName } }
        assertTrue("The frozen calendar remains accessible", accessible())
        assertTrue("This isolated import fixture uses the initial logical generation", source.generation == 0L)
        if (!prefs.contains("token")) {
            val generation = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
            assertTrue("Freeze all identities before creating or preparing anything", prefs.edit()
                .putString("token", token).putString("account_hash", hash)
                .putString("journey_label", prefix)
                .putString("source_calendar_name", requestedCalendarName)
                .putString("account", source.account.value).putString("calendar", source.calendar.value)
                .putLong("generation", source.generation).putLong("physical_generation", generation)
                .putString("create_id", UUID.randomUUID().toString()).putString("intent_id", UUID.randomUUID().toString())
                .putString("later_id", UUID.randomUUID().toString())
                .putInt("content_profile", timeProfile.contentProfile)
                .putString("create_title", "Calendeer QA — text CREATE $token")
                .putString("changed_title", "Calendeer QA — text UPDATE $token\r\nUnicode é")
                .putString("start", timeProfile.start).putString("end", timeProfile.end)
                .putString("description", "Synthetic text journey; retained for inspection.")
                .putString("location", "Calendeer QA")
                .putString("changed_description", "Synthetic notes $token\r\nUnicode é; comma, slash \\ preserved")
                .putString("changed_location", "QA room $token\r\nSecond floor")
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).commit())
        }
        val physical = source.copy(generation = prefs.getLong("physical_generation", -1))
        assertTrue("Use a distinct frozen native namespace", physical.generation > 0 && physical != source &&
            app.imports.state.value.selections.none { it.scope == physical })
        fun id(key: String) = MutationId(checkNotNull(prefs.getString(key, null)))
        val timestamp = Instant.parse(prefs.getString("timestamp", null))
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        assertTrue("Require the exact frozen known-empty time profile", timeProfile.matchesFrozen(
            prefs.getInt("content_profile", -1), frozen("start"), frozen("end")))
        val content = EventContent(frozen("create_title"), timeProfile.time,
            frozen("description"), frozen("location"), details = EventDetails())
        val changed = content.copy(title = frozen("changed_title"), description = frozen("changed_description"),
            location = frozen("changed_location"))
        val canonical = changed.copy(title = changed.title.replace("\r\n", "\n"),
            description = changed.description.replace("\r\n", "\n"), location = changed.location.replace("\r\n", "\n"))
        val laterContent = canonical.copy(description = "Calendeer QA — later local notes $token")
        fun exactResult(record: TextUpdateAttemptRecord?, result: TextUpdateVerifiedResult?): Boolean =
            record != null && result != null && record.scope == source &&
                record.id.value == prefs.getString("attempt_id", null) &&
                record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                record.preparation.intent.id == id("intent_id") && record.preparation.timestamp == timestamp &&
                record.preparation.intent.base.eventId.value == prefs.getString("event", null) &&
                record.preparation.intent.base.content == content && record.preparation.intent.content == changed &&
                record.id == TextUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                result.record == record.copy(protectedEvidence = null) && result.event.content == canonical
        val native = AndroidCalendarProjection(app)
        val account = native.accountFor(source.account)
        assertTrue("Require permissions and the existing native account", native.hasPermissions() &&
            AccountManager.get(app).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun values(): Map<String, String?> = app.contentResolver.query(uri, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString()), null)!!.use { cursor ->
            assertTrue("Require exactly the enrolled native row", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                if (cursor.isNull(column)) null else cursor.getString(column) }.also { row ->
                val event = EventId(checkNotNull(prefs.getString("event", null)))
                assertTrue("Retain the exact native event ownership", row[Events._SYNC_ID] == native.eventIdentityKey(physical, event) &&
                    row[Events.SYNC_DATA4] == native.eventIdentityKey(physical, event))
                assertTrue("Retain the exact typed dates and native timezone", TitleJourneyTimeProfile.nativeMatches(content.time,
                    row[Events.ALL_DAY], row[Events.DTSTART], row[Events.DTEND], row[Events.EVENT_TIMEZONE], row[Events.EVENT_END_TIMEZONE]))
            }
        }
        fun textsMatch(row: Map<String, String?>, expected: EventContent) =
            row[Events.TITLE] == expected.title && row[Events.DESCRIPTION].orEmpty() == expected.description &&
                row[Events.EVENT_LOCATION].orEmpty() == expected.location
        fun edit(from: Map<String, String?>, replacement: EventContent) {
            assertTrue("Mutate only the accessible enrolled row", accessible() && from[Events.DELETED] == "0")
            assertTrue("Require the exact prior texts and dirty state", app.contentResolver.update(uri,
                ContentValues().apply { put(Events.TITLE, replacement.title); put(Events.DESCRIPTION, replacement.description)
                    put(Events.EVENT_LOCATION, replacement.location); put(Events.DIRTY, 1) },
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND " +
                    "COALESCE(${Events.DESCRIPTION},'')=? AND COALESCE(${Events.EVENT_LOCATION},'')=? AND ${Events.DIRTY}=? AND ${Events.DELETED}=0",
                arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString(),
                    checkNotNull(from[Events.TITLE]), from[Events.DESCRIPTION].orEmpty(), from[Events.EVENT_LOCATION].orEmpty(),
                    checkNotNull(from[Events.DIRTY]))) == 1)
        }
        fun witness(vararg names: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { names.forEach { putBoolean(it, true) } })
        val actualRemote = ProtonAuthentication.events(app)
        val remote = object : ReadOnlyCalendarRemote {
            override val format get() = actualRemote.format
            override suspend fun currentAccount() = actualRemote.currentAccount()
            override suspend fun fetch(request: PageRequest): RemotePage = error("This journey never downloads an import page")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("This journey never dispatches through import")
        }
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
                TitleUpdateAttemptStore(app, "$prefix-shared-attempts.db").use { shared ->
                    val journal = shared.textJournal
                    shared.admitDispatch = { error("No title dispatch is part of this text fixture") }
                    val factual: (CalendarScope) -> Boolean = { it == source && accessible() }
                    if (phase == "submit" && prefs.getBoolean("text_requested", false)) {
                        val attempt = id("attempt_id")
                        assertTrue("An uncertain text request is never resubmitted",
                            exactResult(journal.read(source, attempt, factual), journal.readVerified(source, attempt, factual)))
                        witness("guarded_text_existing_result_no_resubmit")
                        return@runBlocking
                    }
                    if (phase == "submit") {
                        store.admitTransaction = { it == source && accessible() }
                        store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic guarded text journey")))
                        store.setEnabled(source, true)
                        // A verified full CREATE observation is committed before any native projection.
                        var base = prefs.getString("event", null)?.let { event ->
                            store.transaction(source) { remote(EventId(event)) as? RemoteChange.Upsert }
                        }
                        if (base == null) {
                            val createPort = ProtonAuthentication.createSubmissions(app)
                            CreateAttemptStore(app, "$prefix-create.db").use { creates ->
                                creates.admitDispatch = { false }
                                try {
                                    val existing = creates.read(source, id("create_id"))
                                    assertTrue("Missing work after a CREATE request cannot be recreated", existing != null ||
                                        !prefs.getBoolean("create_requested", false))
                                    val work = existing ?: createPort.prepareSubmission(CalendarCreateDraft(source,
                                        id("create_id"), timestamp, content)).let { prepared ->
                                        CreateAttemptRecord(id("create_id"), source, prepared.format, prepared.protectedPayload)
                                            .also { record ->
                                                // This legacy CREATE journal also guards wire storage with its
                                                // mutable gate. Scoped retention does not claim or send.
                                                creates.admitDispatch = { it == source && accessible() }
                                                try { creates.remember(record) } finally { creates.admitDispatch = { false } }
                                            }
                                    }
                                    assertTrue("Require explicit known-empty CREATE preparation",
                                        ProtonCreateSubmissionProbe.knownEmptyPreparationSummary(work) == "SUBMISSION_V2_EXPLICIT_EMPTY")
                                    val observation = if (work.state == CreateAttemptState.OUTCOME_UNKNOWN) createPort.inspect(work)
                                    else {
                                        assertTrue("A requested CREATE can only be inspected", !prefs.getBoolean("create_requested", false))
                                        assertTrue(prefs.edit().putBoolean("create_requested", true).commit())
                                        creates.admitDispatch = { it == source && accessible() }
                                        try { createPort.submit(work, creates) } finally { creates.admitDispatch = { false } }
                                    }
                                    assertTrue("CREATE needs authenticated complete readback", observation is CreateSubmissionObservation.Verified)
                                    val verified = observation as CreateSubmissionObservation.Verified
                                    val unknown = checkNotNull(creates.read(source, work.id))
                                    assertTrue("Keep exact admitted CREATE and complete synthetic content",
                                        unknown.state == CreateAttemptState.OUTCOME_UNKNOWN && unknown.protectedEvidence != null &&
                                            verified.attempt == CreateAttemptIdentity.of(unknown) && verified.event.content == content)
                                    creates.bind(unknown, verified)
                                    base = verified.event
                                    store.transaction(source) {
                                        val old = remote(verified.event.eventId)
                                        assertTrue("Never replace an existing baseline", old == null || old == verified.event)
                                        putRemote(verified.event); putProjection(ProjectionIntent(source, verified.event))
                                        checkpoint = PageCheckpoint(source, remote.format)
                                    }
                                    assertTrue(prefs.edit().putString("event", verified.event.eventId.value).commit())
                                } finally { creates.admitDispatch = { false } }
                            }
                        }
                        val original = checkNotNull(base)
                        assertTrue("Retained CREATE content is exact", original.content == content)
                        native.admitOperation = ::accessible
                        val digest = MessageDigest.getInstance("SHA-256")
                        listOf("calendar-v1", physical.account.value, physical.calendar.value, physical.generation.toString()).forEach {
                            val bytes = it.toByteArray(Charsets.UTF_8)
                            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
                        }
                        val namespace = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!prefs.getBoolean("namespace_reserved", false)) {
                            app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                                "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
                                arrayOf(account.name, account.type, namespace), null)!!.use {
                                assertTrue("A new native namespace must not exist", it.count == 0)
                            }
                            assertTrue(prefs.edit().putBoolean("namespace_reserved", true).commit())
                        }
                        val calendar = native.ensureCalendarState(physical,
                            CalendarSummary(source.calendar, "Calendeer QA — guarded text journey"), true)
                        assertTrue("Keep the frozen native calendar", !prefs.contains("native_calendar") ||
                            prefs.getLong("native_calendar", -1) == calendar.id)
                        assertTrue(prefs.edit().putLong("native_calendar", calendar.id).commit())
                        if (!prefs.contains("row")) {
                            val count = app.contentResolver.query(uri, arrayOf(Events._ID),
                                "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                                arrayOf(calendar.id.toString(), native.eventIdentityKey(physical, original.eventId)), null)!!.use { it.count }
                            assertTrue("The fixture identity is unique", count <= 1)
                            // After interruption, adopt only the exact clean baseline. Never
                            // overwrite an unrecorded dirty row to repair fixture bookkeeping.
                            if (count == 0) native.apply(ProjectionIntent(physical, original))
                            val row = checkNotNull(native.inspectOwnedEvent(physical, original.eventId, original.revision)).eventRow
                            assertTrue(prefs.edit().putLong("row", row).commit())
                        }
                        if (store.projectedBaseline(source, original.eventId) == null) {
                            assertTrue("Acknowledge only the actual initial native projection",
                                native.inspectOwnedEvent(physical, original.eventId, original.revision)?.eventRow == prefs.getLong("row", -1))
                            assertTrue(store.acknowledge(ProjectionIntent(source, original)) == 1)
                        }
                        assertTrue("Use only the exact imported baseline", store.projectedBaseline(source, original.eventId) == original)
                        val beforeEdit = values()
                        if (beforeEdit[Events.DIRTY] == "0") {
                            assertTrue("Never replace other native content", textsMatch(beforeEdit, content))
                            edit(beforeEdit, changed)
                        }
                        assertTrue("Keep only the frozen dirty fixture text", textsMatch(values(), changed) && values()[Events.DIRTY] == "1")
                    } else {
                        assertTrue("Recovery requires a previously requested text update", prefs.getBoolean("text_requested", false))
                        assertTrue("Recovery must run in a different process", prefs.getInt("submit_pid", -1) > 0 &&
                            prefs.getInt("submit_pid", -1) != Process.myPid())
                    }
                    val event = EventId(checkNotNull(prefs.getString("event", null)))
                    val textConsent = AtomicBoolean(phase == "submit")
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() },
                        systemTitlesEnabled = { false },
                        systemTextsEnabled = { it == source && textConsent.get() && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val owners = coordinator.localTextOwners
                        val actualCapsule = if (phase == "submit") ProtonAuthentication.localTextPreparations(app) else null
                        val actualPort = if (phase == "submit") ProtonAuthentication.textUpdates(app) else null
                        var capsuleCalls = 0
                        var prepareCalls = 0
                        var submitCalls = 0
                        var inspectCalls = 0
                        val capsules = object : CalendarLocalTextPreparationPort {
                            override val localTextFormat get() = actualCapsule?.localTextFormat
                            override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation =
                                error("Unrestricted text preparation is forbidden")
                            override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
                                admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
                                capsuleCalls++
                                check(phase == "submit" && actualCapsule != null)
                                return actualCapsule.prepareLocalText(intent, timestamp, admit)
                            }
                        }
                        val port = object : CalendarTextUpdateSubmissionPort {
                            override val textUpdateFormat get() = actualPort?.textUpdateFormat
                            override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
                                admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
                                prepareCalls++
                                check(phase == "submit" && actualPort != null)
                                assertTrue("Freeze the deterministic request identity before wire preparation",
                                    id == TextUpdateAttemptIdentity.requestId(preparation, checkNotNull(textUpdateFormat)) &&
                                        (!prefs.contains("attempt_id") || prefs.getString("attempt_id", null) == id.value))
                                assertTrue(prefs.edit().putString("attempt_id", id.value).commit())
                                return actualPort.prepareSubmission(id, preparation, admit)
                            }
                            override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
                                admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
                                submitCalls++
                                check(phase == "submit" && actualPort != null)
                                assertTrue("An already requested text update must never be sent twice",
                                    !prefs.getBoolean("text_requested", false) && record.id.value == prefs.getString("attempt_id", null))
                                assertTrue(prefs.edit().putBoolean("text_requested", true).putInt("submit_pid", Process.myPid()).commit())
                                val observation = actualPort.submit(record, journal, admit, qualifySource, admitEvidence)
                                if (observation is TextUpdateSubmissionObservation.Verified) {
                                    // Revoke only fixture policy after the real reply. Evidence survives;
                                    // native acknowledgement waits for the explicitly restarted process.
                                    textConsent.set(false)
                                    coordinator.systemCreatePolicyChanged(source, true)
                                }
                                return observation
                            }
                            override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
                                inspectCalls++
                                error("This journey never inspects or resends an uncertain text request")
                            }
                        }
                        val operations = CoordinatorSystemTextOperations(owners, journal, capsules, port,
                            discoverLocal = { error("Only the exact enrolled owner may be supplied") },
                            evidenceAdmission = coordinator::systemTextEvidenceAdmission)
                        fun attemptHash(record: TextUpdateAttemptRecord) = bytesHash(TextUpdateAttemptStorageCodec.encode(record))
                        fun resultHash(result: TextUpdateVerifiedResult) = bytesHash(TextUpdateProofStorageCodec.encodeResult(result))
                        if (phase == "submit") {
                            assertTrue("Capture the exact native full-text owner without preparing it",
                                coordinator.captureLocalEdit(source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
                            allowStore()
                            val local = checkNotNull(store.localEdit(source, event))
                            assertTrue("Retain complete raw multiline content and exact baseline",
                                local.id == id("intent_id") && local.content == changed && local.base.content == content &&
                                    local.content != canonical && SystemTextEditProfile.accepts(local))
                            val dirty = values()
                            assertTrue("Require the exact raw dirty row before preparation",
                                textsMatch(dirty, changed) && dirty[Events.DIRTY] == "1")
                            var wakes = 0
                            val failed = operations.process(source,
                                RetainedLocalChangeRef(event, local.id, MutationKind.UPDATE, LocalEditState.PAUSED),
                                MutationId("unused-traversal-candidate"), timestamp,
                                coordinator.systemTextRunAdmission(source)) { wakes++ }
                            assertTrue("The explicit guarded orchestration must not fail", !failed)
                            val attempt = id("attempt_id")
                            val unknown = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Retain exact UNKNOWN request, response evidence and canonical remote content", exactResult(unknown, result))
                            allowStore()
                            val retained = checkNotNull(store.localTextEditPreparation(source, event))
                            assertTrue("Raw local intention survives canonical remote readback",
                                retained.intent == local && retained.timestamp == timestamp &&
                                    retained.protectedPayload == unknown.preparation.protectedPayload)
                            assertTrue("Mode revocation retains proof without native ACK or completion",
                                !textConsent.get() && wakes == 1 && values() == dirty && projection.writes.get() == 0 &&
                                    store.textUpdateAcknowledgement(source, event, attempt) == null &&
                                    journal.readCompletion(source, attempt, factual) == null)
                            assertTrue("Exactly one guarded preparation and submission, no text inspection",
                                capsuleCalls == 1 && prepareCalls == 1 && submitCalls == 1 && inspectCalls == 0)
                            assertTrue("Freeze the full durable attempt and proof for a separate process",
                                prefs.edit().putString("attempt_hash", attemptHash(unknown))
                                    .putString("result_hash", resultHash(result)).putBoolean("submit_completed", true).commit())
                            witness("guarded_text_submit_verified", "guarded_text_raw_crlf_retained",
                                "guarded_text_canonical_lf_verified", "guarded_text_proof_survives_mode_revocation",
                                "guarded_text_same_row_dirty")
                        } else {
                            val attempt = id("attempt_id")
                            val original = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Bind complete first-process history before recovery", exactResult(original, result) &&
                                prefs.getBoolean("submit_completed", false) &&
                                prefs.getString("attempt_hash", null) == attemptHash(original) &&
                                prefs.getString("result_hash", null) == resultHash(result))
                            val proofAdmission = coordinator.systemTextProofAdmission(source)
                            val dirty = values()
                            val wasComplete = journal.readCompletion(source, attempt, factual) != null
                            allowStore()
                            val prior = store.textUpdateAcknowledgement(source, event, attempt)
                            val progress = operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission)
                            assertTrue("Recover the single retained text attempt without transport",
                                progress == if (wasComplete) TitleUpdateOfflineRecoveryProgress()
                                    else TitleUpdateOfflineRecoveryProgress(completed = 1))
                            allowStore()
                            val clean = values()
                            val receipt = checkNotNull(store.textUpdateAcknowledgement(source, event, attempt))
                            val completion = checkNotNull(journal.readCompletion(source, attempt, factual))
                            assertTrue("Exact restored receipt and matching durable completion",
                                receipt.state == TextUpdateAcknowledgementState.RESTORED && receipt.receipt.matches(result) &&
                                    completion == TextUpdateCompletion.of(result, receipt) &&
                                    store.projectedBaseline(source, event) == result.event &&
                                    store.localTextEditPreparation(source, event) == null)
                            if (!wasComplete && prior == null) assertTrue("ACK changes only the three texts and sync metadata on the same row",
                                dirty[Events.DIRTY] == "1" && clean[Events.DIRTY] == "0" &&
                                    textsMatch(clean, canonical) && (dirty - ACK_FIELDS) == (clean - ACK_FIELDS) &&
                                    projection.writes.get() == 1)
                            else assertTrue("A retained receipt never repeats the native write", clean == dirty && projection.writes.get() == 0)
                            assertTrue("Restored receipt binds exact revision and nonce",
                                clean[Events.SYNC_DATA2] == digest("revision-v1", result.event.revision.value) &&
                                    clean[Events.SYNC_DATA3] == "calendeer-projection-receipt-v1:${receipt.receipt.projection.nonce}")
                            val previousOwner = store.localEdit(source, event)
                            assertTrue("No unrelated owner can be adopted", previousOwner == null ||
                                (previousOwner.id == id("later_id") && previousOwner.scope == source &&
                                    previousOwner.base == result.event && previousOwner.content == laterContent &&
                                    previousOwner.state == LocalEditState.PAUSED))
                            if (clean[Events.DIRTY] == "0") {
                                assertTrue("Only the canonical result may become the later edit", textsMatch(clean, canonical))
                                edit(clean, laterContent)
                            }
                            val later = values()
                            assertTrue("Keep the exact later dirty text edit", later[Events.DIRTY] == "1" && textsMatch(later, laterContent))
                            val writes = projection.writes.get()
                            assertTrue("A completed page does not rediscover closed work",
                                operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission) == TitleUpdateOfflineRecoveryProgress())
                            assertTrue("Historical ACK/completion cannot rewrite the later native edit",
                                owners.acknowledge(result, journal, proofAdmission) && owners.complete(result, journal, proofAdmission))
                            assertTrue("Capture the later owner against the acknowledged actual baseline",
                                coordinator.captureLocalEdit(source, event, id("later_id")) == LocalEditCaptureResult.RETAINED)
                            allowStore()
                            val laterOwner = checkNotNull(store.localEdit(source, event))
                            assertTrue("The later owner is exact", laterOwner.id == id("later_id") &&
                                laterOwner.base == result.event && laterOwner.content == laterContent)
                            assertTrue("Historical ACK/completion cannot consume the newer owner",
                                owners.acknowledge(result, journal, proofAdmission) && owners.complete(result, journal, proofAdmission))
                            allowStore()
                            assertTrue("Preserve newer intent, raw first request and first proof",
                                store.localEdit(source, event) == laterOwner && values() == later &&
                                    projection.writes.get() == writes && journal.read(source, attempt, factual) == original &&
                                    journal.readVerified(source, attempt, factual) == result &&
                                    journal.readCompletion(source, attempt, factual) == completion)
                            assertTrue("Offline recovery never enters preparation, send or inspection",
                                capsuleCalls == 0 && prepareCalls == 0 && submitCalls == 0 && inspectCalls == 0)
                            assertTrue(prefs.edit().putBoolean("recover_completed", true).commit())
                            witness("guarded_text_recover_new_process", "guarded_text_offline_completed",
                                "guarded_text_canonical_same_row_ack", "guarded_text_newer_owner_preserved",
                                "guarded_text_history_immutable", "guarded_text_no_repeat_submission")
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
            // Keep the visible QA example, its native row, and all private journals for restart.
        }
    }

    companion object {
        private fun digest(vararg parts: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            parts.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun bytesHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3,
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION)
    }
}

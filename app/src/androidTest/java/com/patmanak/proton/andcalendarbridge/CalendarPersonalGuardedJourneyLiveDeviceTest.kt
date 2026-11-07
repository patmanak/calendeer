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
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit QA-account service qualification using isolated SQL and a private native calendar.
 * submit sends at most one v2 edit; recover must run in another process and uses only retained
 * proof plus local Provider ACK. Its sentinels measure this fixture, not independent app traffic.
 * The initial CREATE has known-empty reminders/color: richer metadata is qualified separately.
 * Keep the token, private journals and retained visible example; never repair an UNKNOWN send.
 */
class CalendarPersonalGuardedJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class IsolatedProjection(private val native: AndroidCalendarProjection,
        private val logical: CalendarScope, private val physical: CalendarScope,
        private val access: () -> Boolean) : CalendarProjection {
        val writes = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val personalUpdateAcknowledgementProtocol get() = native.personalUpdateAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("The coordinator cannot create calendars")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No coordinator projection during recovery")
        override suspend fun captureOwnedPersonalEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedPersonalEdit(physical, base)
        }
        private fun mapped(receipt: PersonalUpdateAcknowledgementReceipt): PersonalUpdateAcknowledgementReceipt {
            check(receipt.intent.scope == logical && receipt.handoff == null)
            return receipt.copy(attempt = receipt.attempt.copy(scope = physical),
                intent = receipt.intent.copy(scope = physical), projection = receipt.projection.copy(scope = physical))
        }
        override suspend fun acknowledgePersonalUpdate(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
            writes.incrementAndGet()
            val expected = mapped(receipt)
            return native.acknowledgePersonalUpdate(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedPersonalUpdateAcknowledgement(receipt) }
        }
        override suspend fun inspectPersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
            val expected = mapped(receipt)
            return native.inspectPersonalUpdateAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedPersonalUpdateAcknowledgement(receipt) }
        }
    }

    @Test fun guardedSubmissionThenRestartedOfflineAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarPersonalGuardedJourney") == "true")
        val token = args.getString("personalFixtureToken").orEmpty()
        val hash = args.getString("personalAccountHash").orEmpty()
        val phase = args.getString("personalGuardedPhase").orEmpty()
        assertTrue("Explicit fixture, account and phase are required", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && phase in setOf("submit", "recover"))
        val requestedCalendarName = "Calendeer QA Automatic Worker"
        val prefix = "calendar-personal-guarded-v2-$token"
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
            CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        } else {
            assertTrue("Recovery cannot enroll a new fixture", phase == "submit")
            selected.first().scope
        }
        assertTrue("Never move the enrolled source to another calendar", selected.single().scope == source &&
            (!prefs.contains("token") || prefs.getString("source_calendar_name", null) == requestedCalendarName))
        if (phase == "submit") assertTrue("Authorize the exact account before mutation", ProtonCreateSubmissionProbe.matchesAccount(app, source.account, hash))
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
                .putString("start", Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(86_400).toString())
                .putString("create_title", "Calendeer QA — personal v2 CREATE $token")
                .putString("description", "Synthetic personal v2 journey; retained for inspection.")
                .putString("location", "Calendeer QA")
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).commit())
        }
        val physical = source.copy(generation = prefs.getLong("physical_generation", -1))
        assertTrue("Use a distinct frozen native namespace", physical.generation > 0 && physical != source &&
            app.imports.state.value.selections.none { it.scope == physical })
        fun id(key: String) = MutationId(checkNotNull(prefs.getString(key, null)))
        val timestamp = Instant.parse(prefs.getString("timestamp", null))
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        val start = Instant.parse(frozen("start"))
        val before = EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC)
        val day = start.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)
        val content = EventContent(frozen("create_title"), before,
            frozen("description"), frozen("location"), details = EventDetails())
        val changed = content.copy(title = "Calendeer QA \uFFFD personal combined $token",
            description = "Combined personal notes\r\nRetained raw local intent.", location = "Combined QA place",
            time = EventTime.AllDay(day, day.plusDays(3)))
        val canonical = changed.copy(description = changed.description.replace("\r\n", "\n"))
        assertTrue("Use a frozen combined v2 transition with three independent consents",
            PersonalEditCandidate(LocalEditIntent(id("intent_id"), source,
                AdapterFormat("fixture-profile-check", 1),
                RemoteChange.Upsert(EventId("fixture-profile-check"), Revision("fixture-profile-check"), content), changed))
                .requiredConsent == setOf(PersonalEditField.TITLE, PersonalEditField.TEXT, PersonalEditField.TIME))
        fun exactResult(record: PersonalUpdateAttemptRecord?, result: PersonalUpdateVerifiedResult?): Boolean =
            record != null && result != null && record.scope == source &&
                record.id.value == prefs.getString("attempt_id", null) &&
                record.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                record.preparation.intent.id == id("intent_id") && record.preparation.timestamp == timestamp &&
                record.preparation.intent.base.eventId.value == prefs.getString("event", null) &&
                record.preparation.intent.base.content == content && record.preparation.intent.content == changed &&
                record.id == PersonalUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                result.record == record.copy(protectedEvidence = null) && PersonalUpdateContentPolicy.sameSubmittedContent(result.event.content, changed) &&
                result.event.content.description == changed.description.replace("\r\n", "\n")
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
            }
        }
        fun textsMatch(row: Map<String, String?>, expected: EventContent) =
            row[Events.TITLE] == expected.title && row[Events.DESCRIPTION].orEmpty() == expected.description &&
                row[Events.EVENT_LOCATION].orEmpty() == expected.location
        fun timesMatch(row: Map<String, String?>, expected: EventContent) =
            TitleJourneyTimeProfile.nativeMatches(expected.time, row[Events.ALL_DAY], row[Events.DTSTART],
                row[Events.DTEND], row[Events.EVENT_TIMEZONE], row[Events.EVENT_END_TIMEZONE])
        fun edit(from: Map<String, String?>) {
            assertTrue("Mutate only the accessible enrolled row", accessible() && from[Events.DELETED] == "0" &&
                textsMatch(from, content) && timesMatch(from, content) && from[Events.DIRTY] == "0")
            val bounds = TitleJourneyTimeProfile.bounds(changed.time)
            assertTrue("Guard exact text, original dates, zone and dirty state", app.contentResolver.update(uri,
                ContentValues().apply { put(Events.DTSTART, bounds.first); put(Events.DTEND, bounds.second)
                    put(Events.TITLE, changed.title); put(Events.DESCRIPTION, changed.description); put(Events.EVENT_LOCATION, changed.location)
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC"); put(Events.DIRTY, 1) },
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND " +
                    "COALESCE(${Events.DESCRIPTION},'')=? AND COALESCE(${Events.EVENT_LOCATION},'')=? AND " +
                    "${Events.DTSTART}=? AND ${Events.DTEND}=? AND ${Events.ALL_DAY}=? AND " +
                    "${Events.EVENT_TIMEZONE}=? AND COALESCE(${Events.EVENT_END_TIMEZONE},'')=? AND " +
                    "${Events.DIRTY}=0 AND ${Events.DELETED}=0",
                arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString(),
                    checkNotNull(from[Events.TITLE]), from[Events.DESCRIPTION].orEmpty(), from[Events.EVENT_LOCATION].orEmpty(),
                    checkNotNull(from[Events.DTSTART]), checkNotNull(from[Events.DTEND]), checkNotNull(from[Events.ALL_DAY]),
                    checkNotNull(from[Events.EVENT_TIMEZONE]), from[Events.EVENT_END_TIMEZONE].orEmpty())) == 1)
        }
        fun witness(vararg names: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { names.forEach { putBoolean(it, true) } })
        val actualRemote = if (phase == "submit") ProtonAuthentication.events(app) else null
        if (actualRemote != null) {
            val format = actualRemote.format
            assertTrue("Never replace the frozen remote format", !prefs.contains("remote_format_id") ||
                prefs.getString("remote_format_id", null) == format.id && prefs.getInt("remote_format_version", -1) == format.version)
            assertTrue(prefs.edit().putString("remote_format_id", format.id).putInt("remote_format_version", format.version).commit())
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = AdapterFormat(frozen("remote_format_id"), prefs.getInt("remote_format_version", -1))
            // Exact local identity was already authorized; offline recovery never enter the authenticated facade.
            override suspend fun currentAccount() = if (phase == "submit") checkNotNull(actualRemote).currentAccount() else source.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No fixture import transport")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No fixture import dispatch")
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
                    val journal = shared.personalJournal
                    shared.admitDispatch = { error("No legacy title dispatch is part of this personal fixture") }
                    val factual: (CalendarScope) -> Boolean = { it == source && accessible() }
                    if (phase == "submit" && prefs.getBoolean("personal_requested", false)) {
                        val attempt = id("attempt_id")
                        assertTrue("An uncertain personal request is never resubmitted",
                            exactResult(journal.read(source, attempt, factual), journal.readVerified(source, attempt, factual)))
                        witness("personal_guarded_existing_result_no_resubmit")
                        return@runBlocking
                    }
                    if (phase == "submit") {
                        store.admitTransaction = { it == source && accessible() }
                        store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic guarded personal v2 journey")))
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
                            CalendarSummary(source.calendar, "Calendeer QA — guarded personal v2 journey"), true)
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
                            edit(beforeEdit)
                        }
                        assertTrue("Keep only the frozen dirty combined fixture", textsMatch(values(), changed) && timesMatch(values(), changed) && values()[Events.DIRTY] == "1")
                    } else {
                        assertTrue("Recovery requires a previously requested personal update", prefs.getBoolean("personal_requested", false))
                        assertTrue("Recovery must run in a different process", prefs.getInt("submit_pid", -1) > 0 &&
                            prefs.getInt("submit_pid", -1) != Process.myPid())
                    }
                    val event = EventId(checkNotNull(prefs.getString("event", null)))
                    val personalConsent = AtomicBoolean(phase == "submit")
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() },
                        systemTitlesEnabled = { it == source && personalConsent.get() && accessible() },
                        systemTextsEnabled = { it == source && personalConsent.get() && accessible() },
                        systemTimesEnabled = { it == source && personalConsent.get() && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val owners = coordinator.personalOwners
                        val actualCapsule = if (phase == "submit") ProtonAuthentication.personalPreparations(app) else null
                        val actualPort = if (phase == "submit") ProtonAuthentication.personalUpdates(app) else null
                        var capsuleCalls = 0
                        var prepareCalls = 0
                        var submitCalls = 0
                        var inspectCalls = 0
                        var capsuleReturned = false
                        var wireReturned = false
                        var verifiedReturned = false
                        val capsules = object : CalendarPersonalPreparationPort {
                            override val personalFormat get() = actualCapsule?.personalFormat
                            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation =
                                error("Unrestricted personal preparation is forbidden")
                            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
                                admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                                capsuleCalls++
                                check(phase == "submit" && actualCapsule != null)
                                return actualCapsule.preparePersonal(intent, timestamp, admit).also {
                                    capsuleReturned = it != null
                                }
                            }
                        }
                        val port = object : CalendarPersonalUpdatePort {
                            override val personalUpdateFormat get() = actualPort?.personalUpdateFormat
                            override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
                                admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
                                prepareCalls++
                                check(phase == "submit" && actualPort != null)
                                assertTrue("Freeze the deterministic request identity before wire preparation",
                                    id == PersonalUpdateAttemptIdentity.requestId(preparation, checkNotNull(personalUpdateFormat)) &&
                                        (!prefs.contains("attempt_id") || prefs.getString("attempt_id", null) == id.value))
                                assertTrue(prefs.edit().putString("attempt_id", id.value).commit())
                                return actualPort.prepareSubmission(id, preparation, admit).also {
                                    wireReturned = it != null
                                }
                            }
                            override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
                                admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
                                submitCalls++
                                check(phase == "submit" && actualPort != null)
                                assertTrue("An already requested personal update must never be sent twice",
                                    !prefs.getBoolean("personal_requested", false) && record.id.value == prefs.getString("attempt_id", null))
                                assertTrue(prefs.edit().putBoolean("personal_requested", true).putInt("submit_pid", Process.myPid()).commit())
                                val observation = actualPort.submit(record, journal, admit, qualifySource, admitEvidence)
                                if (observation is PersonalUpdateSubmissionObservation.Verified) {
                                    verifiedReturned = true
                                }
                                return observation
                            }
                            override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
                                inspectCalls++
                                error("This journey never inspects or resends an uncertain personal request")
                            }
                        }
                        val operations = CoordinatorSystemPersonalOperations(owners, journal, capsules, port,
                            discoverLocal = { error("Only the exact enrolled owner may be supplied") },
                            evidenceAdmission = coordinator::systemPersonalEvidenceAdmission,
                            consentAdmission = coordinator::systemPersonalConsentAdmission)
                        fun attemptHash(record: PersonalUpdateAttemptRecord) = bytesHash(PersonalUpdateAttemptStorageCodec.encode(record))
                        fun resultHash(result: PersonalUpdateVerifiedResult) = bytesHash(PersonalUpdateProofStorageCodec.encodeResult(result))
                        if (phase == "submit") {
                            assertTrue("Capture the exact native combined personal owner without preparing it",
                                coordinator.captureLocalEdit(source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
                            allowStore()
                            val local = checkNotNull(store.localEdit(source, event))
                            assertTrue("Retain complete combined personal content and exact baseline",
                                local.id == id("intent_id") && local.content == changed && local.base.content == content &&
                                    local.content != content && SystemPersonalEditProfile.accepts(local))
                            val dirty = values()
                            assertTrue("Require the exact raw dirty row before preparation",
                                textsMatch(dirty, changed) && timesMatch(dirty, changed) && dirty[Events.DIRTY] == "1")
                            var wakes = 0
                            val failed = operations.process(source,
                                RetainedLocalChangeRef(event, local.id, MutationKind.UPDATE, LocalEditState.PAUSED),
                                MutationId("unused-traversal-candidate"), timestamp,
                                coordinator.systemPersonalRunAdmission(source)) {
                                wakes++
                                // The first verified proof is durable before fixture sending is revoked.
                                personalConsent.set(false)
                                coordinator.systemCreatePolicyChanged(source, true)
                            }
                            // Closed branch evidence only. A strict original/API-metadata refusal is
                            // observable without logging IDs, event JSON, offsets or adapter exceptions.
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                putBoolean("personal_guarded_capsule_entered", capsuleCalls > 0)
                                putBoolean("personal_guarded_capsule_returned", capsuleReturned)
                                putBoolean("personal_guarded_wire_entered", prepareCalls > 0)
                                putBoolean("personal_guarded_wire_returned", wireReturned)
                                putBoolean("personal_guarded_submit_entered", submitCalls > 0)
                                putBoolean("personal_guarded_verified_returned", verifiedReturned)
                                putBoolean("personal_guarded_orchestration_failed", failed)
                            })
                            assertTrue("The explicit guarded orchestration must not fail", !failed)
                            assertTrue("Strict personal v2 capsule, wire and service readback must qualify",
                                capsuleReturned && wireReturned && verifiedReturned)
                            val attempt = id("attempt_id")
                            val unknown = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Retain exact UNKNOWN request, response evidence and exact personal v2 content", exactResult(unknown, result))
                            allowStore()
                            val retained = checkNotNull(store.personalEditPreparation(source, event))
                            assertTrue("Exact personal v2 intention survives verified remote readback",
                                retained.intent == local && retained.timestamp == timestamp &&
                                    retained.protectedPayload == unknown.preparation.protectedPayload)
                            assertTrue("Mode revocation retains proof without native ACK or completion",
                                !personalConsent.get() && wakes == 1 && values() == dirty && projection.writes.get() == 0 &&
                                    store.personalUpdateAcknowledgement(source, event, attempt) == null &&
                                    journal.readCompletion(source, attempt, factual) == null)
                            assertTrue("Exactly one guarded preparation and submission, no personal inspection",
                                capsuleCalls == 1 && prepareCalls == 1 && submitCalls == 1 && inspectCalls == 0)
                            assertTrue("Freeze the full durable attempt and proof for a separate process",
                                prefs.edit().putString("attempt_hash", attemptHash(unknown))
                                    .putString("result_hash", resultHash(result)).putBoolean("submit_completed", true).commit())
                            witness("personal_guarded_submit_verified", "personal_guarded_exact_combined_readback",
                                "personal_guarded_canonical_lf", "personal_guarded_proof_survives_mode_revocation",
                                "personal_guarded_same_row_dirty")
                        } else {
                            val attempt = id("attempt_id")
                            val original = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Bind complete first-process history before recovery", exactResult(original, result) &&
                                prefs.getBoolean("submit_completed", false) &&
                                prefs.getString("attempt_hash", null) == attemptHash(original) &&
                                prefs.getString("result_hash", null) == resultHash(result))
                            val proofAdmission = coordinator.systemPersonalProofAdmission(source)
                            if (prefs.getBoolean("ack_completed", false)) {
                                val completion = checkNotNull(journal.readCompletion(source, attempt, factual))
                                val row = values()
                                assertTrue("A repeated recovery preserves completed history and newer local content",
                                    prefs.getString("completion_hash", null) == bytesHash(PersonalUpdateProofStorageCodec.encodeCompletion(completion)) &&
                                        row[Events.TITLE] == "Calendeer QA - personal newer local $token" && row[Events.DIRTY] == "1" &&
                                        operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission) == TitleUpdateOfflineRecoveryProgress() &&
                                        values() == row && projection.writes.get() == 0)
                                witness("personal_guarded_recover_new_process", "personal_guarded_offline_completed",
                                    "personal_guarded_no_repeat_submission", "personal_guarded_newer_edit_preserved")
                                return@runBlocking
                            }
                            val dirty = values()
                            val wasComplete = journal.readCompletion(source, attempt, factual) != null
                            allowStore()
                            val prior = store.personalUpdateAcknowledgement(source, event, attempt)
                            val progress = operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission)
                            assertTrue("Recover the single retained personal attempt without transport",
                                progress == if (wasComplete) TitleUpdateOfflineRecoveryProgress()
                                    else TitleUpdateOfflineRecoveryProgress(completed = 1))
                            allowStore()
                            val clean = values()
                            val receipt = checkNotNull(store.personalUpdateAcknowledgement(source, event, attempt))
                            val completion = checkNotNull(journal.readCompletion(source, attempt, factual))
                            assertTrue("Exact restored receipt and matching durable completion",
                                receipt.state == PersonalUpdateAcknowledgementState.RESTORED && receipt.receipt.matches(result) &&
                                    completion == PersonalUpdateCompletion.of(result, receipt) &&
                                    store.projectedBaseline(source, event) == result.event &&
                                    store.personalEditPreparation(source, event) == null)
                            if (!wasComplete && prior == null) assertTrue("ACK changes only canonical text and sync metadata on the same enrolled row",
                                dirty[Events.DIRTY] == "1" && clean[Events.DIRTY] == "0" &&
                                    textsMatch(clean, canonical) && timesMatch(clean, changed) &&
                                    (dirty - ACK_FIELDS) == (clean - ACK_FIELDS) && projection.writes.get() == 1)
                            else assertTrue("A retained receipt never repeats the native write", clean == dirty && projection.writes.get() == 0)
                            assertTrue("Restored receipt binds exact revision and nonce",
                                clean[Events.SYNC_DATA2] == digest("revision-v1", result.event.revision.value) &&
                                    clean[Events.SYNC_DATA3] == "calendeer-projection-receipt-v1:${receipt.receipt.projection.nonce}")
                            assertTrue("The verified personal v2 result becomes the clean baseline",
                                textsMatch(clean, canonical) && timesMatch(clean, changed) && clean[Events.DIRTY] == "0" &&
                                    store.localEdit(source, event) == null)
                            val writes = projection.writes.get()
                            assertTrue("Completed pages never rediscover closed personal v2 work",
                                operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission) == TitleUpdateOfflineRecoveryProgress())
                            assertTrue("Historical typed ACK and completion are idempotent",
                                owners.acknowledge(result, journal, proofAdmission) && owners.complete(result, journal, proofAdmission))
                            allowStore()
                            assertTrue("Preserve the first request, proof, completion and clean row",
                                values() == clean && projection.writes.get() == writes &&
                                    journal.read(source, attempt, factual) == original &&
                                    journal.readVerified(source, attempt, factual) == result &&
                                    journal.readCompletion(source, attempt, factual) == completion)
                            assertTrue("Offline phases never enter preparation, send or inspection",
                                capsuleCalls == 0 && prepareCalls == 0 && submitCalls == 0 && inspectCalls == 0 &&
                                    actualRemote == null && actualCapsule == null && actualPort == null)
                            assertTrue("No new attempt is allocated during offline completion or replay", prefs.getString("attempt_id", null) == attempt.value &&
                                journal.page(PersonalUpdateWorkQuery(source), factual)?.entries?.isEmpty() == true)
                            val newerTitle = "Calendeer QA - personal newer local $token"
                            assertTrue("Create a newer local fixture edit only after exact ACK completion", app.contentResolver.update(uri,
                                ContentValues().apply { put(Events.TITLE, newerTitle); put(Events.DIRTY, 1) },
                                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND ${Events.DIRTY}=0 AND ${Events.DELETED}=0",
                                arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString(), changed.title)) == 1)
                            val newer = values()
                            assertTrue("Keep the newer dirty event and canonical description", newer[Events.TITLE] == newerTitle &&
                                newer[Events.DIRTY] == "1" && newer[Events.DESCRIPTION] == canonical.description && timesMatch(newer, changed))
                            assertTrue("Historical completion cannot consume newer content",
                                operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission) == TitleUpdateOfflineRecoveryProgress() &&
                                    values() == newer && projection.writes.get() == writes &&
                                    journal.readCompletion(source, attempt, factual) == completion)
                            val completionHash = bytesHash(PersonalUpdateProofStorageCodec.encodeCompletion(completion))
                            assertTrue("Freeze deterministic completion history", !prefs.contains("completion_hash") ||
                                prefs.getString("completion_hash", null) == completionHash)
                            assertTrue(prefs.edit().putString("completion_hash", completionHash).putBoolean("ack_completed", true)
                                .putInt("ack_pid", Process.myPid()).commit())
                            witness("personal_guarded_recover_new_process", "personal_guarded_offline_completed",
                                "personal_guarded_same_row_ack", "personal_guarded_history_immutable", "personal_guarded_no_repeat_submission",
                                "personal_guarded_newer_edit_preserved")
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

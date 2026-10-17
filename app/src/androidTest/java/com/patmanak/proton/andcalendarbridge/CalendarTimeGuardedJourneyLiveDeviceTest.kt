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
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonTemporalMetadataProbe
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

/** Opt-in real temporal service journey, retaining a synthetic event and isolated journals.
 * Explicit composition uses the production owner, preparation, submission and offline recovery
 * boundaries with its independent scoped consent. This is an ordinary imported
 * timed/all-day controlled-owner fixture, not CREATE handoff, a system editor journey or worker qualification.
 * Recovery's sentinels forbid fixture-path preparation/transport; they do not measure traffic
 * from the independently running application. No account attributes or real user settings change.
 * Each profile needs its own successful RUN through the strict real ports.
 * Arguments: liveTimeJourney=true, timeFixtureToken (12 lowercase hex), timeAccountHash (SHA-256),
 * timeProfile selects a closed synthetic profile; timeMetadataProbe=true stops after authenticated GET.
 * timeJourneyPhase=RUN|ACK|REPLAY. Stop the process between each phase; retain the same token.
 */
class CalendarTimeGuardedJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class IsolatedProjection(private val native: AndroidCalendarProjection,
        private val logical: CalendarScope, private val physical: CalendarScope,
        private val access: () -> Boolean) : CalendarProjection {
        val writes = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val timeUpdateAcknowledgementProtocol get() = native.timeUpdateAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("The coordinator cannot create calendars")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No content projection during recovery")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedSimpleEdit(physical, base)
        }
        private fun mapped(receipt: TimeUpdateAcknowledgementReceipt): TimeUpdateAcknowledgementReceipt {
            check(receipt.intent.scope == logical && receipt.handoff == null)
            return receipt.copy(attempt = receipt.attempt.copy(scope = physical),
                intent = receipt.intent.copy(scope = physical), projection = receipt.projection.copy(scope = physical))
        }
        override suspend fun acknowledgeTimeUpdate(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
            writes.incrementAndGet()
            val expected = mapped(receipt)
            return native.acknowledgeTimeUpdate(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTimeUpdateAcknowledgement(receipt) }
        }
        override suspend fun inspectTimeUpdateAcknowledgement(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
            val expected = mapped(receipt)
            return native.inspectTimeUpdateAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTimeUpdateAcknowledgement(receipt) }
        }
    }

    @Test fun guardedSubmissionThenRestartedOfflineAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveTimeJourney") == "true")
        val token = args.getString("timeFixtureToken").orEmpty()
        val hash = args.getString("timeAccountHash").orEmpty()
        val phase = args.getString("timeJourneyPhase").orEmpty()
        assertTrue("Explicit fixture, account and phase are required", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && phase in setOf("RUN", "ACK", "REPLAY"))
        val requestedCalendarName = "Calendeer QA Automatic Worker"
        val prefix = "calendar-time-guarded-v1-$token"
        val timeProfile = TemporalJourneyProfile.create(args.getString("timeProfile"))
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
            assertTrue("Recovery cannot enroll a new fixture", phase == "RUN")
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
                .putInt("content_profile", timeProfile.kind).putString("time_profile", timeProfile.name).putString("time_zone", timeProfile.zone)
                .putString("create_title", "Calendeer QA — time CREATE $token")
                .putString("start", timeProfile.start).putString("end", timeProfile.end)
                .putString("description", "Synthetic time journey; retained for inspection.")
                .putString("location", "Calendeer QA")
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
        val content = EventContent(frozen("create_title"), timeProfile.before,
            frozen("description"), frozen("location"), details = EventDetails())
        require(prefs.getString("time_profile", "utc") == timeProfile.name && prefs.getString("time_zone", "Z") == timeProfile.zone)
        val changed = content.copy(time = timeProfile.after)
        assertTrue("Use an enrolled same-kind time-only fixture", changed.title == content.title &&
            changed.description == content.description && changed.location == content.location &&
            LocalTimeEditCandidate(LocalEditIntent(id("intent_id"), source, ProtonAuthentication.events(app).format,
                RemoteChange.Upsert(EventId("synthetic-profile-check"), Revision("synthetic-profile-check"), content), changed)).intent.content == changed)
        fun exactResult(record: TimeUpdateAttemptRecord?, result: TimeUpdateVerifiedResult?): Boolean =
            record != null && result != null && record.scope == source &&
                record.id.value == prefs.getString("attempt_id", null) &&
                record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                record.preparation.intent.id == id("intent_id") && record.preparation.timestamp == timestamp &&
                record.preparation.intent.base.eventId.value == prefs.getString("event", null) &&
                record.preparation.intent.base.content == content && record.preparation.intent.content == changed &&
                record.id == TimeUpdateAttemptIdentity.requestId(record.preparation, record.format) &&
                result.record == record.copy(protectedEvidence = null) && result.event.content == changed
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
                assertTrue("Retain the enrolled kind and original native timezone",
                    row[Events.ALL_DAY] == (if (timeProfile.allDay) "1" else "0") &&
                    row[Events.EVENT_TIMEZONE] == timeProfile.zone && row[Events.EVENT_END_TIMEZONE] == timeProfile.zone)
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
                    put(Events.DIRTY, 1) },
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
        val actualRemote = if (phase == "RUN") ProtonAuthentication.events(app) else null
        if (actualRemote != null) {
            val format = actualRemote.format
            assertTrue("Never replace the frozen remote format", !prefs.contains("remote_format_id") ||
                prefs.getString("remote_format_id", null) == format.id && prefs.getInt("remote_format_version", -1) == format.version)
            assertTrue(prefs.edit().putString("remote_format_id", format.id).putInt("remote_format_version", format.version).commit())
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = AdapterFormat(frozen("remote_format_id"), prefs.getInt("remote_format_version", -1))
            // Exact local identity was already authorized; ACK/REPLAY never enter the authenticated facade.
            override suspend fun currentAccount() = if (phase == "RUN") checkNotNull(actualRemote).currentAccount() else source.account
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
                    val journal = shared.timeJournal
                    shared.admitDispatch = { error("No title dispatch is part of this time fixture") }
                    val factual: (CalendarScope) -> Boolean = { it == source && accessible() }
                    if (phase == "RUN" && prefs.getBoolean("time_requested", false)) {
                        val attempt = id("attempt_id")
                        assertTrue("An uncertain time request is never resubmitted",
                            exactResult(journal.read(source, attempt, factual), journal.readVerified(source, attempt, factual)))
                        witness("guarded_time_existing_result_no_resubmit")
                        return@runBlocking
                    }
                    if (phase == "RUN") {
                        store.admitTransaction = { it == source && accessible() }
                        store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic guarded time journey")))
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
                        if (args.getString("timeMetadataProbe") == "true") {
                            assertTrue("Probe is read-only after an enrolled CREATE", phase == "RUN")
                            val metadata = checkNotNull(ProtonTemporalMetadataProbe.inspect(app, source,
                                original.eventId, content.time, hash))
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                putLong("time_metadata_full_day", metadata.fullDay)
                                putString("time_metadata_start_delta", metadata.startDeltaSeconds?.toString() ?: "OUT_OF_RANGE")
                                putString("time_metadata_end_delta", metadata.endDeltaSeconds?.toString() ?: "OUT_OF_RANGE")
                                putString("time_metadata_start_zone", metadata.startZone.name)
                                putString("time_metadata_end_zone", metadata.endZone.name)
                            })
                            return@runBlocking
                        }

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
                            CalendarSummary(source.calendar, "Calendeer QA — guarded time journey"), true)
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
                        assertTrue("Keep only the frozen dirty fixture time", textsMatch(values(), content) && timesMatch(values(), changed) && values()[Events.DIRTY] == "1")
                    } else {
                        if (phase == "REPLAY") assertTrue("Replay requires completed ACK in another process",
                            prefs.getBoolean("ack_completed", false) && prefs.getInt("ack_pid", -1) > 0 &&
                                prefs.getInt("ack_pid", -1) != Process.myPid())
                        assertTrue("Recovery requires a previously requested time update", prefs.getBoolean("time_requested", false))
                        assertTrue("Recovery must run in a different process", prefs.getInt("submit_pid", -1) > 0 &&
                            prefs.getInt("submit_pid", -1) != Process.myPid())
                    }
                    val event = EventId(checkNotNull(prefs.getString("event", null)))
                    val timeConsent = AtomicBoolean(phase == "RUN")
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() },
                        systemTitlesEnabled = { false },
                        systemTextsEnabled = { false },
                        systemTimesEnabled = { it == source && timeConsent.get() && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val owners = coordinator.localTimeOwners
                        val actualCapsule = if (phase == "RUN") ProtonAuthentication.localTimePreparations(app) else null
                        val actualPort = if (phase == "RUN") ProtonAuthentication.timeUpdates(app) else null
                        var capsuleCalls = 0
                        var prepareCalls = 0
                        var submitCalls = 0
                        var inspectCalls = 0
                        var capsuleReturned = false
                        var wireReturned = false
                        var verifiedReturned = false
                        val capsules = object : CalendarLocalTimePreparationPort {
                            override val localTimeFormat get() = actualCapsule?.localTimeFormat
                            override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation =
                                error("Unrestricted time preparation is forbidden")
                            override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant,
                                admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
                                capsuleCalls++
                                check(phase == "RUN" && actualCapsule != null)
                                return actualCapsule.prepareLocalTime(intent, timestamp, admit).also {
                                    capsuleReturned = it != null
                                }
                            }
                        }
                        val port = object : CalendarTimeUpdateSubmissionPort {
                            override val timeUpdateFormat get() = actualPort?.timeUpdateFormat
                            override suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
                                admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
                                prepareCalls++
                                check(phase == "RUN" && actualPort != null)
                                assertTrue("Freeze the deterministic request identity before wire preparation",
                                    id == TimeUpdateAttemptIdentity.requestId(preparation, checkNotNull(timeUpdateFormat)) &&
                                        (!prefs.contains("attempt_id") || prefs.getString("attempt_id", null) == id.value))
                                assertTrue(prefs.edit().putString("attempt_id", id.value).commit())
                                return actualPort.prepareSubmission(id, preparation, admit).also {
                                    wireReturned = it != null
                                }
                            }
                            override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
                                admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
                                submitCalls++
                                check(phase == "RUN" && actualPort != null)
                                assertTrue("An already requested time update must never be sent twice",
                                    !prefs.getBoolean("time_requested", false) && record.id.value == prefs.getString("attempt_id", null))
                                assertTrue(prefs.edit().putBoolean("time_requested", true).putInt("submit_pid", Process.myPid()).commit())
                                val observation = actualPort.submit(record, journal, admit, qualifySource, admitEvidence)
                                if (observation is TimeUpdateSubmissionObservation.Verified) {
                                    verifiedReturned = true
                                    // Revoke only fixture policy after the real reply. Evidence survives;
                                    // native acknowledgement waits for the explicitly restarted process.
                                    timeConsent.set(false)
                                    coordinator.systemCreatePolicyChanged(source, true)
                                }
                                return observation
                            }
                            override suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
                                admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
                                inspectCalls++
                                error("This journey never inspects or resends an uncertain time request")
                            }
                        }
                        val operations = CoordinatorSystemTimeOperations(owners, journal, capsules, port,
                            discoverLocal = { error("Only the exact enrolled owner may be supplied") },
                            evidenceAdmission = coordinator::systemTimeEvidenceAdmission)
                        fun attemptHash(record: TimeUpdateAttemptRecord) = bytesHash(TimeUpdateAttemptStorageCodec.encode(record))
                        fun resultHash(result: TimeUpdateVerifiedResult) = bytesHash(TimeUpdateProofStorageCodec.encodeResult(result))
                        if (phase == "RUN") {
                            assertTrue("Capture the exact native time-only owner without preparing it",
                                coordinator.captureLocalEdit(source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
                            allowStore()
                            val local = checkNotNull(store.localEdit(source, event))
                            assertTrue("Retain complete time-only content and exact baseline",
                                local.id == id("intent_id") && local.content == changed && local.base.content == content &&
                                    local.content != content && SystemTimeEditProfile.accepts(local))
                            val dirty = values()
                            assertTrue("Require the exact raw dirty row before preparation",
                                textsMatch(dirty, content) && timesMatch(dirty, changed) && dirty[Events.DIRTY] == "1")
                            var wakes = 0
                            val failed = operations.process(source,
                                RetainedLocalChangeRef(event, local.id, MutationKind.UPDATE, LocalEditState.PAUSED),
                                MutationId("unused-traversal-candidate"), timestamp,
                                coordinator.systemTimeRunAdmission(source)) { wakes++ }
                            // Closed branch evidence only. A strict original/API-metadata refusal is
                            // observable without logging IDs, event JSON, offsets or adapter exceptions.
                            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                                putBoolean("guarded_time_capsule_entered", capsuleCalls > 0)
                                putBoolean("guarded_time_capsule_returned", capsuleReturned)
                                putBoolean("guarded_time_wire_entered", prepareCalls > 0)
                                putBoolean("guarded_time_wire_returned", wireReturned)
                                putBoolean("guarded_time_submit_entered", submitCalls > 0)
                                putBoolean("guarded_time_verified_returned", verifiedReturned)
                                putBoolean("guarded_time_orchestration_failed", failed)
                            })
                            assertTrue("The explicit guarded orchestration must not fail", !failed)
                            assertTrue("Strict temporal capsule, wire and service readback must qualify",
                                capsuleReturned && wireReturned && verifiedReturned)
                            val attempt = id("attempt_id")
                            val unknown = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Retain exact UNKNOWN request, response evidence and exact temporal content", exactResult(unknown, result))
                            allowStore()
                            val retained = checkNotNull(store.localTimeEditPreparation(source, event))
                            assertTrue("Exact temporal intention survives verified remote readback",
                                retained.intent == local && retained.timestamp == timestamp &&
                                    retained.protectedPayload == unknown.preparation.protectedPayload)
                            assertTrue("Mode revocation retains proof without native ACK or completion",
                                !timeConsent.get() && wakes == 1 && values() == dirty && projection.writes.get() == 0 &&
                                    store.timeUpdateAcknowledgement(source, event, attempt) == null &&
                                    journal.readCompletion(source, attempt, factual) == null)
                            assertTrue("Exactly one guarded preparation and submission, no time inspection",
                                capsuleCalls == 1 && prepareCalls == 1 && submitCalls == 1 && inspectCalls == 0)
                            assertTrue("Freeze the full durable attempt and proof for a separate process",
                                prefs.edit().putString("attempt_hash", attemptHash(unknown))
                                    .putString("result_hash", resultHash(result)).putBoolean("submit_completed", true).commit())
                            witness("guarded_time_submit_verified", "guarded_time_exact_temporal_readback",
                                "guarded_time_text_unchanged", "guarded_time_proof_survives_mode_revocation",
                                "guarded_time_same_row_dirty")
                        } else {
                            val attempt = id("attempt_id")
                            val original = checkNotNull(journal.read(source, attempt, factual))
                            val result = checkNotNull(journal.readVerified(source, attempt, factual))
                            assertTrue("Bind complete first-process history before recovery", exactResult(original, result) &&
                                prefs.getBoolean("submit_completed", false) &&
                                prefs.getString("attempt_hash", null) == attemptHash(original) &&
                                prefs.getString("result_hash", null) == resultHash(result))
                            val proofAdmission = coordinator.systemTimeProofAdmission(source)
                            val dirty = values()
                            val wasComplete = journal.readCompletion(source, attempt, factual) != null
                            allowStore()
                            val prior = store.timeUpdateAcknowledgement(source, event, attempt)
                            val progress = operations.recoverOffline(TitleUpdateWorkQuery(source), proofAdmission)
                            assertTrue("Recover the single retained time attempt without transport",
                                progress == if (wasComplete) TitleUpdateOfflineRecoveryProgress()
                                    else TitleUpdateOfflineRecoveryProgress(completed = 1))
                            allowStore()
                            val clean = values()
                            val receipt = checkNotNull(store.timeUpdateAcknowledgement(source, event, attempt))
                            val completion = checkNotNull(journal.readCompletion(source, attempt, factual))
                            assertTrue("Exact restored receipt and matching durable completion",
                                receipt.state == TimeUpdateAcknowledgementState.RESTORED && receipt.receipt.matches(result) &&
                                    completion == TimeUpdateCompletion.of(result, receipt) &&
                                    store.projectedBaseline(source, event) == result.event &&
                                    store.localTimeEditPreparation(source, event) == null)
                            if (!wasComplete && prior == null) assertTrue("ACK changes only sync metadata on the same timed row",
                                dirty[Events.DIRTY] == "1" && clean[Events.DIRTY] == "0" &&
                                    textsMatch(clean, content) && timesMatch(clean, changed) &&
                                    (dirty - ACK_FIELDS) == (clean - ACK_FIELDS) && projection.writes.get() == 1)
                            else assertTrue("A retained receipt never repeats the native write", clean == dirty && projection.writes.get() == 0)
                            assertTrue("Restored receipt binds exact revision and nonce",
                                clean[Events.SYNC_DATA2] == digest("revision-v1", result.event.revision.value) &&
                                    clean[Events.SYNC_DATA3] == "calendeer-projection-receipt-v1:${receipt.receipt.projection.nonce}")
                            assertTrue("The verified temporal result becomes the clean baseline",
                                textsMatch(clean, content) && timesMatch(clean, changed) && clean[Events.DIRTY] == "0" &&
                                    store.localEdit(source, event) == null)
                            val writes = projection.writes.get()
                            assertTrue("Completed pages never rediscover closed temporal work",
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
                                capsuleCalls == 0 && prepareCalls == 0 && submitCalls == 0 && inspectCalls == 0)
                            assertTrue("No new attempt is allocated during offline completion or replay", prefs.getString("attempt_id", null) == attempt.value &&
                                journal.page(TimeUpdateWorkQuery(source), factual)?.entries?.isEmpty() == true)
                            if (phase == "ACK") {
                                assertTrue(prefs.edit().putBoolean("ack_completed", true).putInt("ack_pid", Process.myPid()).commit())
                                witness("guarded_time_ack_new_process", "guarded_time_offline_completed",
                                    "guarded_time_same_row_ack", "guarded_time_history_immutable", "guarded_time_no_repeat_submission")
                            } else {
                                assertTrue("Replay begins with durable completion", wasComplete && prior != null && projection.writes.get() == 0)
                                assertTrue(prefs.edit().putBoolean("replay_completed", true).commit())
                                witness("guarded_time_replay_new_process", "guarded_time_replay_no_new_attempt",
                                    "guarded_time_replay_no_resend", "guarded_time_history_immutable")
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
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
    }
}

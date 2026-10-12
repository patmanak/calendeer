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
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
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
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real service journey, retained across an explicit process restart. The synthetic
 * event is imported into an isolated native generation; this does not qualify CREATE handoff.
 * Recovery has no service mutation/inspection port. That is a fixture-path guarantee, not a
 * claim that the independently running production application has no network traffic.
 */
class CalendarTitleGuardedJourneyLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class IsolatedProjection(private val native: AndroidCalendarProjection,
        private val logical: CalendarScope, private val physical: CalendarScope,
        private val access: () -> Boolean) : CalendarProjection {
        val writes = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val titleUpdateAcknowledgementProtocol get() = native.titleUpdateAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("The coordinator cannot create calendars")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No content projection during recovery")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedSimpleEdit(physical, base)
        }
        private fun mapped(receipt: TitleUpdateAcknowledgementReceipt): TitleUpdateAcknowledgementReceipt {
            check(receipt.intent.scope == logical && receipt.handoff == null)
            return receipt.copy(attempt = receipt.attempt.copy(scope = physical),
                intent = receipt.intent.copy(scope = physical), projection = receipt.projection.copy(scope = physical))
        }
        override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
            writes.incrementAndGet()
            val expected = mapped(receipt)
            return native.acknowledgeTitleUpdate(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTitleUpdateAcknowledgement(receipt) }
        }
        override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
            val expected = mapped(receipt)
            return native.inspectTitleUpdateAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTitleUpdateAcknowledgement(receipt) }
        }
    }

    private class GuardedPort(private val actual: CalendarTitleUpdateSubmissionPort) : CalendarTitleUpdateSubmissionPort {
        var preparations = 0
        var submissions = 0
        override val titleUpdateFormat get() = actual.titleUpdateFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
            error("Unrestricted preparation is forbidden")
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
            preparations++
            return actual.prepareSubmission(reservation, admit)
        }
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
            error("Unrestricted submission is forbidden")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = error("Source qualification is required")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean,
            qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
            submissions++
            return actual.submit(record, journal, admit, qualifySource)
        }
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No title inspection")
        override suspend fun inspect(record: TitleUpdateAttemptRecord,
            admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = error("No title inspection")
    }

    @Test fun guardedSubmissionThenRestartedOfflineAcknowledgement() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTitleGuardedJourney") == "true")
        val systemJourney = args.getString("systemTitleJourney") == "true"
        val automaticSource = args.getString("automaticTitleSource") == "true"
        val requestedCalendarName = args.getString("titleSourceCalendarName").orEmpty()
        assertTrue("Automatic source enrollment requires the explicit system journey and dedicated QA calendar",
            !automaticSource || (systemJourney && requestedCalendarName == "Calendeer QA Automatic Worker"))
        val namespace = TitleJourneyNamespace.create(automaticSource, systemJourney, args.getString("titleJourneyLabel"))
        val timeProfile = TitleJourneyTimeProfile.create(args.getString("titleJourneyAllDay"), automaticSource, systemJourney, namespace.label)
        val token = args.getString("createFixtureToken").orEmpty()
        val hash = args.getString("createAccountHash").orEmpty()
        val phase = args.getString("titleGuardedPhase").orEmpty()
        assertTrue("Explicit fixture, account and phase are required", token.matches(Regex("[a-f0-9]{12}")) &&
            hash.matches(Regex("[a-f0-9]{64}")) && phase in setOf("submit", "recover"))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.let {
            it.authenticated && it.selections.isNotEmpty() && !it.busy
        } }
        val prefs = app.getSharedPreferences(namespace.sourcePreferences, Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available &&
            (!automaticSource || it.summary.title == requestedCalendarName) }
        assertTrue("An accessible existing calendar is required", selected.isNotEmpty())
        assertTrue("The dedicated QA calendar must resolve uniquely", !automaticSource || selected.size == 1)
        val source = if (prefs.contains("token")) {
            assertTrue("Never replace an enrolled fixture", prefs.getString("token", null) == token &&
                prefs.getString("account_hash", null) == hash && prefs.getString("journey_label", null) == namespace.label)
            assertTrue("Never change the enrolled time profile or its exclusive dates", timeProfile.matchesFrozen(
                prefs.getInt("content_profile", -1), prefs.getString("start", null), prefs.getString("end", null)))
            CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        } else {
            assertTrue("Recovery cannot enroll a new fixture", phase == "submit")
            selected.first().scope
        }
        assertTrue("Never move an enrolled automatic source to another calendar", !automaticSource ||
            (selected.single().scope == source && (!prefs.contains("token") ||
                prefs.getString("source_calendar_name", null) == requestedCalendarName)))
        assertTrue("Authorize the exact account before mutation", ProtonCreateSubmissionProbe.matchesAccount(app, source.account, hash))
        fun accessible() = app.imports.state.value.let { state -> state.authenticated && state.hasPermission &&
            state.selections.any { it.scope == source && it.enabled && it.available &&
                (!automaticSource || it.summary.title == requestedCalendarName) } }
        assertTrue("The frozen calendar remains accessible", accessible())
        assertTrue("This isolated import fixture uses the initial logical generation", source.generation == 0L)
        if (!prefs.contains("token")) {
            val generation = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
            assertTrue("Freeze all identities before creating or preparing anything", prefs.edit()
                .putString("token", token).putString("account_hash", hash)
                .putString("journey_label", namespace.label)
                .putString("source_calendar_name", if (automaticSource) requestedCalendarName else null)
                .putString("account", source.account.value).putString("calendar", source.calendar.value)
                .putLong("generation", source.generation).putLong("physical_generation", generation)
                .putString("create_id", UUID.randomUUID().toString()).putString("intent_id", UUID.randomUUID().toString())
                .putString("attempt_id", UUID.randomUUID().toString()).putString("later_id", UUID.randomUUID().toString())
                .putInt("content_profile", timeProfile.contentProfile)
                .putString("create_title", "Calendeer QA — guarded CREATE $token")
                .putString("changed_title", "Calendeer QA — guarded UPDATE $token")
                .putString("later_title", "Calendeer QA — later local guarded title $token")
                .putString("start", timeProfile.start).putString("end", timeProfile.end)
                .putString("description", "Synthetic guarded title journey; retained for inspection.")
                .putString("location", "Calendeer QA")
                .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).commit())
        }
        val physical = source.copy(generation = prefs.getLong("physical_generation", -1))
        assertTrue("Use a distinct frozen native namespace", physical.generation > 0 && physical != source &&
            app.imports.state.value.selections.none { it.scope == physical })
        fun id(key: String) = MutationId(checkNotNull(prefs.getString(key, null)))
        val attempt = id("attempt_id")
        val timestamp = Instant.parse(prefs.getString("timestamp", null))
        fun frozen(key: String) = checkNotNull(prefs.getString(key, null))
        assertTrue("Require the exact frozen known-empty time profile", timeProfile.matchesFrozen(
            prefs.getInt("content_profile", -1), frozen("start"), frozen("end")))
        val content = EventContent(frozen("create_title"), timeProfile.time,
            frozen("description"), frozen("location"), details = EventDetails())
        val changed = content.copy(title = frozen("changed_title"))
        val laterTitle = frozen("later_title")
        fun exactResult(record: TitleUpdateAttemptRecord?, result: TitleUpdateVerifiedResult?): Boolean =
            record != null && result != null && record.scope == source && record.id == attempt &&
                record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                record.preparation.intent.id == id("intent_id") && record.preparation.timestamp == timestamp &&
                record.preparation.intent.base.eventId.value == prefs.getString("event", null) &&
                record.preparation.intent.base.content == content && record.preparation.intent.content == changed &&
                result.record == record.copy(protectedEvidence = null) && result.event.content == changed
        if (phase == "recover" && args.getString("titleGuardedDropMarkers") == "true") {
            assertTrue("Simulate lost descriptive phase markers only", prefs.edit()
                .remove("submit_completed").remove("later_captured").commit())
        }
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
        fun edit(from: Map<String, String?>, title: String) {
            assertTrue("Mutate only the accessible enrolled row", accessible() && from[Events.DELETED] == "0")
            assertTrue("The exact native title and dirty state must still match", app.contentResolver.update(uri,
                ContentValues().apply { put(Events.TITLE, title); put(Events.DIRTY, 1) },
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND ${Events.DIRTY}=? AND ${Events.DELETED}=0",
                arrayOf(prefs.getLong("row", -1).toString(), prefs.getLong("native_calendar", -1).toString(),
                    checkNotNull(from[Events.TITLE]), checkNotNull(from[Events.DIRTY]))) == 1)
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
            ImportStore(app, namespace.sourceImportDatabase).use { store ->
                fun allowStore() { store.admitTransaction = { it == source && accessible() } }
                TitleUpdateAttemptStore(app, namespace.sourceTitleDatabase).use { journal ->
                    journal.admitDispatch = { error("The fixture never uses global title dispatch admission") }
                    if (phase == "submit" && prefs.getBoolean("title_requested", false)) {
                        val old = journal.read(source, attempt)
                        val result = journal.readVerified(source, attempt)
                        assertTrue("An uncertain title is inspect-only; never resubmit", exactResult(old, result))
                        witness("guarded_title_existing_result_no_resubmit")
                        return@runBlocking
                    }
                    if (phase == "submit") {
                        store.admitTransaction = { it == source && accessible() }
                        store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic guarded title journey")))
                        store.setEnabled(source, true)
                        // A verified full CREATE observation is committed before any native projection.
                        var base = prefs.getString("event", null)?.let { event ->
                            store.transaction(source) { remote(EventId(event)) as? RemoteChange.Upsert }
                        }
                        if (base == null) {
                            val createPort = ProtonAuthentication.createSubmissions(app)
                            CreateAttemptStore(app, namespace.sourceCreateDatabase).use { creates ->
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
                            CalendarSummary(source.calendar, "Calendeer QA — guarded title journey"), true)
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
                            assertTrue("Never replace another native title", beforeEdit[Events.TITLE] == content.title)
                            edit(beforeEdit, changed.title)
                        }
                        assertTrue("Keep only the frozen dirty fixture title", values()[Events.TITLE] == changed.title && values()[Events.DIRTY] == "1")
                    } else {
                        assertTrue("Recovery requires a previously requested title", prefs.getBoolean("title_requested", false))
                        assertTrue("Recovery must run in a different process", prefs.getInt("submit_pid", -1) > 0 &&
                            prefs.getInt("submit_pid", -1) != Process.myPid())
                    }
                    val event = EventId(checkNotNull(prefs.getString("event", null)))
                    val coordinator = ImportCoordinator(app, store, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == source && accessible() },
                        systemTitlesEnabled = { systemJourney && phase == "submit" && it == source && accessible() })
                    try {
                        coordinator.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        if (phase == "submit") {
                            val port = GuardedPort(ProtonAuthentication.titleUpdates(app))
                            val actualCapsule = ProtonAuthentication.localEditPreparations(app)
                            var guardedCapsules = 0
                            val guardedCapsule = object : CalendarLocalEditPreparationPort by actualCapsule {
                                override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation =
                                    error("Production capsule preparation requires the current lease")
                                override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                                    admit: (CalendarScope) -> Boolean): LocalEditPreparation? {
                                    guardedCapsules++
                                    return actualCapsule.prepareLocalTitle(intent, timestamp, admit)
                                }
                            }
                            val systemPrepared = if (systemJourney) {
                                assertTrue("Capture without entering the capsule port", coordinator.captureLocalEdit(
                                    source, event, id("intent_id")) == LocalEditCaptureResult.RETAINED)
                                coordinator.prepareSystemTitle(source, event, id("intent_id"), attempt, timestamp,
                                    journal, guardedCapsule, port, { it == source && accessible() })
                            } else {
                                val captured = coordinator.captureAndPrepareLocalTitle(source, event, id("intent_id"), timestamp, actualCapsule)
                                assertTrue("Capture and seal the real native title edit", captured in setOf(
                                    LocalTitlePreparationResult.RETAINED, LocalTitlePreparationResult.HISTORICAL))
                                null
                            }
                            allowStore()
                            val capsule = checkNotNull(store.localEditPreparation(source, event))
                            assertTrue("The complete retained local title is exact", capsule.intent.content == changed &&
                                capsule.intent.id == id("intent_id") && capsule.timestamp == timestamp)
                            val prepared = systemPrepared ?: coordinator.prepareLocalTitleAttempt(TitleUpdatePreparationReservation(attempt,
                                capsule, checkNotNull(port.titleUpdateFormat)), journal, port, allowNetwork = true)
                            assertTrue("Guarded wire preparation is durable", prepared is LocalTitleAttemptPreparation.Prepared)
                            val record = (prepared as LocalTitleAttemptPreparation.Prepared).record
                            val dirty = values()
                            assertTrue("Freeze the one submission request before transport", prefs.edit()
                                .putBoolean("title_requested", true).putInt("submit_pid", Process.myPid()).commit())
                            val result = if (systemJourney) coordinator.submitSystemTitle(record, journal, port,
                                { it == source && accessible() }) else
                                coordinator.submitAndRetainLocalTitleAttempt(record, journal, journal, port, allowNetwork = true)
                            assertTrue("Guarded submission must retain its full verified proof", result != null)
                            allowStore()
                            val unknown = checkNotNull(journal.read(source, attempt))
                            assertTrue("Retain UNKNOWN, response and the exact first result", unknown.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
                                unknown.protectedEvidence != null && result!!.record == unknown.copy(protectedEvidence = null) &&
                                result.event.content == changed && journal.readVerified(source, attempt) == result)
                            assertTrue("No Provider ACK occurs during submission", values() == dirty && dirty[Events.DIRTY] == "1" &&
                                projection.writes.get() == 0 && journal.readCompletion(source, attempt) == null &&
                                store.localEditPreparation(source, event) == capsule)
                            assertTrue("Only the guarded submission was invoked", port.submissions == 1 && port.preparations <= 1)
                            if (systemJourney) {
                                assertTrue("A fresh production capsule uses only the guarded overload", guardedCapsules == 1)
                                witness("system_title_guarded_capsule", "system_title_guarded_submit")
                            }
                            assertTrue(prefs.edit().putBoolean("submit_completed", true).commit())
                            witness("guarded_title_submit_verified", "guarded_title_unknown_durable", "guarded_title_same_row_dirty")
                            if (timeProfile.allDay) witness("guarded_title_all_day_dates_preserved")
                        } else {
                            val original = checkNotNull(journal.read(source, attempt))
                            val result = checkNotNull(journal.readVerified(source, attempt))
                            assertTrue("Recovery binds complete durable evidence", exactResult(original, result))
                            val dirty = values()
                            val alreadyClosed = journal.readCompletion(source, attempt) != null
                            allowStore()
                            val priorReceipt = store.titleUpdateAcknowledgement(source, event, attempt)
                            val progress = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(source), journal)
                            allowStore()
                            assertTrue("The single page recovers only this retained attempt", progress ==
                                if (alreadyClosed) TitleUpdateRecoveryProgress() else TitleUpdateRecoveryProgress(completed = 1))
                            val clean = values()
                            if (!alreadyClosed && priorReceipt == null) assertTrue("ACK changes only synchronization metadata on the same row",
                                dirty[Events.DIRTY] == "1" && clean[Events.DIRTY] == "0" &&
                                    (dirty - ACK_FIELDS) == (clean - ACK_FIELDS) && projection.writes.get() == 1)
                            else assertTrue("A historical receipt never rewrites the native row", clean == dirty && projection.writes.get() == 0)
                            val receipt = checkNotNull(store.titleUpdateAcknowledgement(source, event, attempt))
                            assertTrue("The exact restored receipt supplies revision and nonce metadata",
                                receipt.state == TitleUpdateAcknowledgementState.RESTORED && receipt.receipt.matches(result) &&
                                    clean[Events.SYNC_DATA2] == digest("revision-v1", result.event.revision.value) &&
                                    clean[Events.SYNC_DATA3] == "calendeer-projection-receipt-v1:${receipt.receipt.projection.nonce}")
                            val currentOwner = store.localEdit(source, event)
                            assertTrue("Completion consumes the owner and protected preparation",
                                journal.readCompletion(source, attempt)?.result == result &&
                                    store.projectedBaseline(source, event) == result.event &&
                                    store.localEditPreparation(source, event) == null &&
                                    (currentOwner == null || (currentOwner.id == id("later_id") &&
                                        currentOwner.scope == source && currentOwner.state == LocalEditState.PAUSED &&
                                        currentOwner.base == result.event && currentOwner.content == changed.copy(title = laterTitle))))
                            if (clean[Events.DIRTY] == "0") edit(clean, laterTitle)
                            val later = values()
                            assertTrue("A later dirty edit remains visible", later[Events.DIRTY] == "1" && later[Events.TITLE] == laterTitle)
                            val writes = projection.writes.get()
                            assertTrue("The completed page performs no new work", coordinator.recoverLocalTitles(TitleUpdateWorkQuery(source), journal) == TitleUpdateRecoveryProgress())
                            assertTrue("Historical ACK is read-only", coordinator.acknowledgeLocalTitle(source, event, attempt, journal) == TitleUpdateAcknowledgementResult.ACKNOWLEDGED)
                            assertTrue("Historical completion is read-only", coordinator.completeLocalTitle(source, event, attempt, journal) == TitleUpdateCompletionResult.COMPLETED)
                            assertTrue("The next local owner can be captured", coordinator.captureLocalEdit(source, event, id("later_id")) == LocalEditCaptureResult.RETAINED)
                            allowStore()
                            val laterOwner = checkNotNull(store.localEdit(source, event))
                            assertTrue("The new owner binds the new baseline and complete later content", laterOwner.id == id("later_id") &&
                                laterOwner.base == result.event && laterOwner.content == changed.copy(title = laterTitle))
                            assertTrue(prefs.edit().putBoolean("later_captured", true).commit())
                            assertTrue("Old recovery cannot consume the next owner", coordinator.recoverLocalTitles(TitleUpdateWorkQuery(source), journal) == TitleUpdateRecoveryProgress() &&
                                coordinator.acknowledgeLocalTitle(source, event, attempt, journal) == TitleUpdateAcknowledgementResult.ACKNOWLEDGED &&
                                coordinator.completeLocalTitle(source, event, attempt, journal) == TitleUpdateCompletionResult.COMPLETED)
                            allowStore()
                            assertTrue("Keep the later owner and immutable historical evidence",
                                store.localEdit(source, event) == laterOwner && journal.read(source, attempt) == original &&
                                journal.readVerified(source, attempt) == result && values() == later && projection.writes.get() == writes)
                            witness("guarded_title_recover_new_process", "guarded_title_offline_completed",
                                "guarded_title_metadata_only_ack", "guarded_title_later_dirty_preserved", "guarded_title_no_repeat_submission")
                            if (timeProfile.allDay) witness("guarded_title_all_day_dates_preserved")
                            if (args.getString("titleGuardedDropMarkers") == "true") witness("guarded_title_lost_markers_recovered")
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
        private val ACK_FIELDS = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
    }
}

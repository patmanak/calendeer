// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicitly enrolled historical service attempt, real guarded readback and real native ACK.
 * Reconstructs the local imported owner in isolated databases/Provider generation. This is not
 * a new capture-to-PUT journey, CREATE-handoff qualification or automatic worker activation.
 * The original request, result, visible event, native account and production store stay intact.
 */
class CalendarTitleRecoveryLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Only translates native namespace keys; service records/proofs keep their exact scope.
     * No synthetic content or acknowledgement witness substitutes for the actual Provider.
     */
    private class IsolatedProjection(
        private val native: AndroidCalendarProjection,
        private val logical: CalendarScope,
        private val physical: CalendarScope,
        private val access: () -> Boolean,
    ) : CalendarProjection {
        val writes = AtomicInteger()
        val inspections = AtomicInteger()
        override var admitOperation: () -> Boolean
            get() = native.admitOperation
            set(value) { native.admitOperation = { access() && value() } }
        override val titleUpdateAcknowledgementProtocol get() = native.titleUpdateAcknowledgementProtocol
        override fun hasPermissions() = native.hasPermissions()
        override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
            error("The coordinator cannot create a native calendar")
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) =
            error("Recovery cannot project content")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(scope == logical)
            return native.captureOwnedSimpleEdit(physical, base)
        }
        private fun mapped(receipt: TitleUpdateAcknowledgementReceipt): TitleUpdateAcknowledgementReceipt {
            check(receipt.intent.scope == logical && receipt.handoff == null)
            // These translated identity fields are Provider lookup context only, never a
            // substitute service proof or a request to decode ciphertext under another scope.
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
            inspections.incrementAndGet()
            val expected = mapped(receipt)
            return native.inspectTitleUpdateAcknowledgement(expected)?.takeIf { it.receipt == expected }
                ?.let { AppliedTitleUpdateAcknowledgement(receipt) }
        }
    }

    @Test fun guardedServiceProofCompletesNativeOwnerAndOfflineReplayPreservesLaterEdit() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTitleRecoveryLive") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val hash = args.getString("createAccountHash").orEmpty()
        assertTrue("Require an explicit historical fixture and account",
            token.matches(Regex("[a-f0-9]{12}")) && hash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated &&
            app.imports.state.value.selections.isNotEmpty() && !app.imports.state.value.busy }
        val fixture = app.getSharedPreferences("title-update-live-qualification-v1", Context.MODE_PRIVATE)
        assertTrue("Require the previously enrolled verified fixture", fixture.getString("token", null) == token &&
            fixture.getString("result_account_hash", null) == hash && fixture.getBoolean("verified_result_retained", false))
        val source = CalendarScope(AccountId(checkNotNull(fixture.getString("account", null))),
            CalendarId(checkNotNull(fixture.getString("calendar", null))), fixture.getLong("generation", -1))
        val id = MutationId(checkNotNull(fixture.getString("attempt", null)))
        assertTrue("This fixture reconstructs the original import generation only", source.generation == 0L)
        assertTrue("Authorize the retained account before any read", ProtonCreateSubmissionProbe.matchesAccount(app, source.account, hash))
        fun accessible(): Boolean = app.imports.state.value.let { current ->
            current.authenticated && current.hasPermission &&
                current.selections.any { it.scope == source && it.enabled && it.available }
        }
        assertTrue("Require the existing accessible selection", accessible())
        // Cold-start access reconciliation can still change READ/EDITOR after the first UI
        // state is ready. Settle the retained production mode through its actual owner before
        // taking snapshots; do not change preferences, authentication or ignore view columns.
        assertTrue("Settle the existing native access policy", app.imports.configureSystemCalendar(source))
        val originalName = "calendar-title-update-live-qualification-v1.db"
        val original = TitleUpdateAttemptStore(app, originalName).use { checkNotNull(it.read(source, id)) }
        val historical = TitleUpdateAttemptStore(app, originalName).use { checkNotNull(it.readVerified(source, id)) }
        assertTrue("Use only the historical UNKNOWN", original.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
            original.protectedEvidence != null && historical.record == original.copy(protectedEvidence = null))
        val preparation = original.preparation
        val intent = preparation.intent
        assertTrue("Bind the full retained synthetic title operation",
            intent.base.content.title == "Calendeer QA — création système $token" &&
                intent.content.title == "Calendeer QA — titre UPDATE $token" &&
                intent.id.value == fixture.getString("intent", null) &&
                preparation.timestamp.toString() == fixture.getString("timestamp", null))
        ImportStore(app, "title-update-live-preparation-v1.db").use {
            assertTrue("Historical preparation remains exact", it.localEditPreparation(source, intent.base.eventId) == preparation)
        }
        val native = AndroidCalendarProjection(app)
        assertTrue("Require calendar permissions", native.hasPermissions())
        val account = native.accountFor(source.account)
        fun calendars(): Set<Long> = app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type), null)!!.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        assertTrue("Never create or remove the existing native account",
            AccountManager.get(app).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
        val beforeCalendars = calendars()
        fun productionRow(): Map<String, String?> = app.contentResolver.query(Events.CONTENT_URI, null,
            "${Events._SYNC_ID}=? AND ${Events.CALENDAR_ID} IN (${beforeCalendars.joinToString(",")})",
            arrayOf(native.eventIdentityKey(source, intent.base.eventId)), null)!!.use { cursor ->
            assertTrue("Require the exact existing production row", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                if (cursor.isNull(column)) null else cursor.getString(column) }
        }
        val originalNative = productionRow()
        val physical = source.copy(generation = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1))
        assertTrue("The native namespace must differ from every production selection",
            physical != source && app.imports.state.value.selections.none { it.scope == physical })
        val selections = app.imports.state.value.selections.map { it.scope to it.enabled }
        // Do not alter or depend on the production owner's mutable transaction admission.
        suspend fun productionSnapshot() = ImportStore(app).use { observer ->
            Triple(observer.projectedBaseline(source, intent.base.eventId),
                observer.localEdit(source, intent.base.eventId), observer.localEditPreparation(source, intent.base.eventId))
        }
        val production = productionSnapshot()
        val projection = IsolatedProjection(native, source, physical, ::accessible)
        val actualRemote = ProtonAuthentication.events(app)
        val remote = object : ReadOnlyCalendarRemote {
            override val format get() = actualRemote.format
            override suspend fun currentAccount() = actualRemote.currentAccount()
            override suspend fun fetch(request: PageRequest): RemotePage = error("No import downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No mutation dispatch")
        }
        val actualPort = ProtonAuthentication.titleUpdates(app)
        var serviceInspections = 0
        val port = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat get() = actualPort.titleUpdateFormat
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                error("Never prepare a new title request")
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                error("Never resend the historical UNKNOWN")
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                error("Never fall back to unrestricted inspection")
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                assertTrue("Inspect only the exact original work", record == original)
                serviceInspections++
                return actualPort.inspect(record) { candidate -> candidate == source && accessible() && admit(candidate) }
            }
        }
        val suffix = UUID.randomUUID().toString()
        val importName = "title-recovery-live-$suffix.db"
        val journalName = "title-recovery-live-attempt-$suffix.db"
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        fun owner(store: ImportStore): Pair<ImportCoordinator, CoroutineScope> {
            val lifecycle = CoroutineScope(SupervisorJob(jobs.coroutineContext[Job]) + Dispatchers.Default)
            return ImportCoordinator(app, store, remote, projection, lifecycle, scheduler,
                systemCreatesEnabled = { it == source && accessible() }) to lifecycle
        }
        suspend fun start(owner: ImportCoordinator) {
            val before = schedules.get()
            owner.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        var created = false
        var calendar = -1L
        var row = -1L
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        fun values(): Map<String, String?> = app.contentResolver.query(uri, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString()), null)!!.use { cursor ->
            assertTrue("Require one exact isolated row", cursor.count == 1 && cursor.moveToFirst())
            cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                if (cursor.isNull(column)) null else cursor.getString(column) }
        }
        fun edit(title: String) {
            assertTrue("Change only the new native fixture", created && accessible())
            assertTrue("Exactly one row changes", app.contentResolver.update(uri, ContentValues().apply {
                put(Events.TITLE, title); put(Events.DIRTY, 1)
            }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())) == 1)
        }
        try {
            native.admitOperation = ::accessible
            val namespaceHash = MessageDigest.getInstance("SHA-256")
            listOf("calendar-v1", physical.account.value, physical.calendar.value, physical.generation.toString()).forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                namespaceHash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); namespaceHash.update(bytes)
            }
            val namespace = namespaceHash.digest().joinToString("") { "%02x".format(it) }
            app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
                arrayOf(account.name, account.type, namespace), null)!!.use {
                assertTrue("Reject a preexisting native namespace before creation", it.count == 0)
            }
            val ensured = native.ensureCalendarState(physical, CalendarSummary(source.calendar, "Calendeer QA — isolated title recovery"), true)
            calendar = ensured.id
            created = ensured.created && calendar !in beforeCalendars
            assertTrue("Only a newly created isolated calendar can be changed", created)
            native.apply(ProjectionIntent(physical, intent.base))
            row = checkNotNull(native.inspectOwnedEvent(physical, intent.base.eventId, intent.base.revision)).eventRow
            edit(intent.content.title)
            ImportStore(app, importName).use { store ->
                store.remember(source.account, listOf(CalendarSummary(source.calendar, "Synthetic recovery qualification")))
                store.setEnabled(source, true)
                store.transaction(source) {
                    putRemote(intent.base); putProjection(ProjectionIntent(source, intent.base))
                    checkpoint = PageCheckpoint(source, intent.format)
                }
                assertTrue("Seed the exact acknowledged historical import", store.acknowledge(ProjectionIntent(source, intent.base)) == 1)
                val (coordinator, lifecycle) = owner(store)
                try {
                    start(coordinator)
                    assertTrue("Capture the real Provider edit", coordinator.captureLocalEdit(source, intent.base.eventId, intent.id) == LocalEditCaptureResult.RETAINED)
                    store.admitTransaction = { it == source && accessible() }
                    assertTrue("The captured full intent must equal the historical preparation", store.localEdit(source, intent.base.eventId) == intent)
                    assertTrue(store.retainLocalEditPreparation(preparation))
                    TitleUpdateAttemptStore(app, journalName).use { journal ->
                        val prepared = original.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null)
                        journal.remember(prepared)
                        journal.admitDispatch = { it == source && accessible() }
                        assertTrue("Reconstruct only local UNKNOWN state, without any transport", journal.claim(prepared))
                        journal.retainEvidence(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), original.protectedEvidence!!)
                        journal.admitDispatch = { error("Recovery must never admit dispatch") }
                        assertTrue(journal.read(source, id) == original && journal.readVerified(source, id) == null)
                        val dirty = values()
                        assertTrue("Fresh inspection closes one owner", coordinator.inspectAndRecoverLocalTitles(
                            TitleUpdateWorkQuery(source), journal, port, allowNetwork = true) == TitleUpdateRecoveryProgress(completed = 1))
                        assertTrue("Exactly one guarded service inspection", serviceInspections == 1)
                        val result = checkNotNull(journal.readVerified(source, id))
                        assertTrue("Bind the real signed complete result", result.record == historical.record &&
                            result.event.content == historical.event.content && result.event.eventId == historical.event.eventId)
                        store.admitTransaction = { it == source && accessible() }
                        val receipt = checkNotNull(store.titleUpdateAcknowledgement(source, intent.base.eventId, id))
                        assertTrue(receipt.state == TitleUpdateAcknowledgementState.RESTORED && journal.readCompletion(source, id) != null)
                        val clean = values()
                        assertTrue("Only dirty/revision/nonce metadata can change",
                            (dirty - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)) ==
                                (clean - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)))
                        assertTrue("Keep the same row and submitted title", clean[Events.DIRTY] == "0" && clean[Events.TITLE] == intent.content.title)
                        assertTrue(store.projectedBaseline(source, intent.base.eventId) == result.event && store.localEdit(source, intent.base.eventId) == null)
                        assertTrue(journal.read(source, id) == original && projection.writes.get() == 1)
                    }
                } finally { coordinator.authenticationChanged(false); lifecycle.coroutineContext[Job]!!.cancelAndJoin() }
            }
            edit("Calendeer QA — later offline title $token")
            val later = values()
            ImportStore(app, importName).use { reopened ->
                val (coordinator, lifecycle) = owner(reopened)
                try {
                    start(coordinator)
                    TitleUpdateAttemptStore(app, journalName).use { journal ->
                        journal.admitDispatch = { error("Reopening cannot admit dispatch") }
                        val durable = checkNotNull(journal.readVerified(source, id))
                        val completion = checkNotNull(journal.readCompletion(source, id))
                        assertTrue("Read the complete durable history after reopening", completion.result == durable &&
                            durable.record == historical.record && durable.event.content == historical.event.content)
                        assertTrue("Completion removes the work index entry", coordinator.inspectAndRecoverLocalTitles(
                            TitleUpdateWorkQuery(source), journal, port) == TitleUpdateRecoveryProgress())
                        assertTrue("Historical ACK is reusable", coordinator.acknowledgeLocalTitle(source, intent.base.eventId, id, journal) == TitleUpdateAcknowledgementResult.ACKNOWLEDGED)
                        assertTrue("Historical completion is reusable", coordinator.completeLocalTitle(source, intent.base.eventId, id, journal) == TitleUpdateCompletionResult.COMPLETED)
                        assertTrue("Keep original UNKNOWN and protected response", journal.read(source, id) == original)
                    }
                } finally { coordinator.authenticationChanged(false); lifecycle.coroutineContext[Job]!!.cancelAndJoin() }
            }
            assertTrue("Preserve every field of the later dirty edit", values() == later && later[Events.DIRTY] == "1")
            assertTrue("Reopening needs neither network nor native write", serviceInspections == 1 && projection.writes.get() == 1 && projection.inspections.get() == 0)
            assertTrue("Production baseline, intent and preparation stay intact",
                productionSnapshot() == production)
            assertTrue("Selections stay intact", app.imports.state.value.selections.map { it.scope to it.enabled } == selections)
            val currentNative = productionRow()
            if (currentNative != originalNative) InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("title_recovery_production_changed_columns", (originalNative.keys + currentNative.keys)
                    .filter { originalNative[it] != currentNative[it] }.sorted().joinToString(","))
            })
            assertTrue("The existing production native row stays intact", currentNative == originalNative)
            TitleUpdateAttemptStore(app, originalName).use {
                assertTrue("Original historical journal stays intact", it.read(source, id) == original && it.readVerified(source, id) == historical)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("title_recovery_real_guarded_service_to_native", true)
                putBoolean("title_recovery_same_row_complete_content", true)
                putBoolean("title_recovery_reopened_preserves_later_dirty_edit", true)
                putBoolean("title_recovery_no_submit_or_prepare", true)
            })
        } finally {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            try {
                if (created) { native.admitOperation = { true }; native.removeOwnedCalendar(physical) }
                assertTrue("Keep the original native calendar set", calendars() == beforeCalendars)
                assertTrue("Keep the existing native account", AccountManager.get(app)
                    .getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
            } finally { app.deleteDatabase(importName); app.deleteDatabase(journalName) }
        }
    }
}

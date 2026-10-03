// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith



/** Real original Provider row and durable journals; fake remote transport only. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateReconciliationIntegrationDeviceTest {
    @Test fun unchangedDirtyOriginalDoesNotConsumeIncomingWorkOrReleaseHold() = fixture {
        ready(); incoming(verifiedEvent); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.UNCHANGED, reconcile())
        assertNull(edit()); assertNull(deletion()); assertEquals(1, rawPending()); assertEquals(before, providerSnapshot())
        assertTrue(creates.capturedProjectionHold(target, format).holds(verifiedEvent.eventId))
        assertEquals(1, sends); assertEquals(1, attachmentWrites)
    }
    @Test fun supportedTitleChangeIsRetainedAgainstTheSentReferenceWithoutAndroidAck() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local after send") }); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile())
        assertEquals(content.copy(title = "Local after send"), edit()!!.content); assertEquals(verifiedEvent, edit()!!.base)
        assertEquals(1, rawPending()); assertEquals(before, providerSnapshot()); assertNull(deletion())
        assertTrue(creates.capturedProjectionHold(target, format).holds(verifiedEvent.eventId))
    }
    @Test fun descriptionLocationAndDatesAreRetainedAsOneSupportedSnapshot() = fixture {
        ready(); incoming(verifiedEvent); val oldTime = content.time as EventTime.Timed
        mutate(ContentValues().apply { put(Events.DESCRIPTION, "Local description"); put(Events.EVENT_LOCATION, "Local location")
            put(Events.DTSTART, oldTime.start.plusSeconds(3600).toEpochMilli()); put(Events.DTEND, oldTime.end.plusSeconds(3600).toEpochMilli()) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val local = edit()!!.content
        assertEquals("Local description", local.description); assertEquals("Local location", local.location)
        assertEquals(oldTime.copy(start = oldTime.start.plusSeconds(3600), end = oldTime.end.plusSeconds(3600)), local.time)
    }
    @Test fun presentTombstoneRetainsDeletionWithoutConsumingDirtyOrDeletedFlags() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); assertEquals(content, deletion()!!.content)
        assertEquals(verifiedEvent, deletion()!!.base); assertNull(edit()); assertEquals(1, rawPending()); assertEquals(before, providerSnapshot())
    }
    @Test fun editBeforeTombstoneIsPreservedInsideTheDeletionIntent() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Edited before delete"); put(Events.DELETED, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile())
        assertEquals(content.copy(title = "Edited before delete"), deletion()!!.content); assertEquals("1", field(Events.DELETED))
    }
    @Test fun reminderAfterSendingIsUnsupportedAndRemainsIntact() = fixture {
        ready(); addReminder(); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcile()); assertNull(edit()); assertNull(deletion())
        assertEquals(before, providerSnapshot()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun recurrenceAfterSendingIsUnsupportedAndRemainsIntact() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=3") }); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcile()); assertNull(edit()); assertEquals(before, providerSnapshot())
    }
    @Test fun customColorAfterSendingIsUnsupportedAndRemainsIntact() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0xff123456.toInt()) }); val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcile()); assertNull(edit()); assertEquals(before, providerSnapshot())
    }
    @Test fun purgedOriginalNeverBecomesADeletionIntentOrANewRow() = fixture {
        ready(); assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())))
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(deletion()); assertNull(edit()); assertEquals(0, rows())
    }
    @Test fun externallyCleanedRowCannotQualifyCurrentReconciliation() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.DIRTY, 0) })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit()); assertEquals("0", field(Events.DIRTY))
    }
    @Test fun damagedAttachmentReceiptCannotAuthorizeLocalCapture() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.SYNC_DATA6, "damaged"); put(Events.TITLE, "Local change") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit())
    }
    @Test fun missingLocalMarkerCannotFallBackToTheRemoteIdentity() = fixture {
        ready(); mutate(ContentValues().apply { putNull(Events.SYNC_DATA5); put(Events.TITLE, "Local change") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit())
    }
    @Test fun duplicateLocalMarkerCannotAuthorizeCapture() = fixture {
        ready(); context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit()); assertEquals(2, rows())
    }
    @Test fun competingRemoteIdentityCannotAuthorizeCapture() = fixture {
        ready(); context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!!
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit()); assertEquals(2, rows())
    }
    @Test fun reopeningReinspectsCurrentOriginalAndRetainsTheSameIntention() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "First local edit") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val first = edit(); val before = providerSnapshot(); reopen()
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); assertEquals(first, edit()); assertEquals(before, providerSnapshot())
        assertEquals(1, sends); assertEquals(1, attachmentWrites)
    }
    @Test fun secondEditCannotReplaceTheFirstRetainedIntention() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "First local edit") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val first = edit()
        mutate(ContentValues().apply { put(Events.TITLE, "Second local edit") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertEquals(first, edit()); assertEquals("Second local edit", field(Events.TITLE))
    }
    @Test fun tombstoneAfterRetainedUpdateCannotCoalesceKinds() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "First local edit") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val first = edit(); mutate(ContentValues().apply { put(Events.DELETED, 1) })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertEquals(first, edit()); assertNull(deletion())
    }
    @Test fun revertingToSentContentCannotHideARetainedLocalIntention() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "First local edit") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val first = edit(); mutate(ContentValues().apply { put(Events.TITLE, content.title) })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertEquals(first, edit()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun remoteDifferenceBeforeCaptureRetainsBothCopiesAndRecordsConflict() = fixture {
        ready(); val later = verifiedEvent.copy(revision = Revision("later"), content = content.copy(title = "Remote later")); incoming(later)
        mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile())
        assertEquals(later, remoteRecord()); assertEquals(1, rawPending()); assertEquals(LocalEditState.CONFLICT, edit()!!.state)
    }
    @Test fun remoteDifferenceAfterCapturePreservesPayloadAndPromotesConflict() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val before = providerSnapshot()
        val later = verifiedEvent.copy(content = content.copy(title = "Same revision remote later")); incoming(later)
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile()); assertEquals(later, remoteRecord())
        assertEquals(LocalEditState.CONFLICT, edit()!!.state); assertEquals(before, providerSnapshot()); assertEquals(0, rawPending())
    }
    @Test fun absentIncomingCacheConservativelyRetainsAConflictingLocalEdit() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); assertNull(remoteRecord())
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile()); assertNotNull(edit()); assertNull(remoteRecord())
    }
    @Test fun observedAccountLossDuringCurrentInspectionCannotCapture() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        beforeCurrent = { coordinator.authenticationChanged(false) }
        assertEquals(CapturedCreateReconciliationResult.INACTIVE, reconcile()); assertNull(edit()); assertEquals(1, rawPending())
    }
    @Test fun controlledPermissionLossAfterCurrentInspectionCannotCapture() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); afterCurrent = { permitted = false }
        assertEquals(CapturedCreateReconciliationResult.INACTIVE, reconcile()); assertNull(edit()); assertEquals(1, rawPending())
    }
    @Test fun cancellationDuringCurrentInspectionPropagatesWithoutCapture() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); beforeCurrent = { throw CancellationException("Synthetic cancellation") }
        try { reconcile(); fail("Cancellation must propagate") } catch (_: CancellationException) {}
        assertNull(edit()); beforeCurrent = {}; assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile())
    }
    @Test fun adapterReplacementCannotCaptureAgainstTheHistoricalFormat() = fixture {
        ready(); currentFormat = AdapterFormat("replacement", 1); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit())
    }
    @Test fun missingRetainedBaselineCannotBeCreatedImplicitlyByReconciliation() = fixture {
        attached(); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(baseline()); assertNull(edit())
    }
    @Test fun structurallyValidImportProvenanceDriftCannotAuthorizeCapture() = fixture {
        ready(); store.writableDatabase.execSQL("UPDATE captured_create_bases SET nonce='00000000-0000-0000-0000-000000000999'")
        mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit())
    }
    @Test fun failedLocalCommitRollsBackAndLeavesOriginalDirtyForRetry() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); val before = providerSnapshot()
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_current BEFORE INSERT ON local_edits BEGIN SELECT RAISE(ABORT, 'Synthetic capture failure'); END")
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit()); assertEquals(1, rawPending()); assertEquals(before, providerSnapshot())
        store.writableDatabase.execSQL("DROP TRIGGER fixture_fail_current"); assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile())
    }
    @Test fun editAfterSnapshotLeavesFirstHistoricalIntentAndRequiresResolution() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "First local edit") }); var injected = false
        afterCurrent = { if (!injected) { injected = true; mutate(ContentValues().apply { put(Events.TITLE, "Concurrent later edit") }) } }
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertEquals("First local edit", edit()!!.content.title)
        assertEquals("Concurrent later edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertEquals(1, rawPending())
    }
    @Test fun accessLossAfterDurableCaptureRetainsTheIntentForReopening() = fixture {
        ready(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        onAccount = {
            if (store.admitTransaction(target) && store.localEdit(target, verifiedEvent.eventId) != null)
                coordinator.authenticationChanged(false)
        }
        assertEquals(CapturedCreateReconciliationResult.INACTIVE, reconcile()); val first = edit(); assertNotNull(first)
        onAccount = {}; reopen(); assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); assertEquals(first, edit())
        assertEquals("1", field(Events.DIRTY)); assertEquals(1, sends)
    }
    @Test fun disabledCalendarCannotRetainTheCurrentLocalIntention() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Local later") }); allowStore(); store.setEnabled(target, false)
        assertEquals(CapturedCreateReconciliationResult.INACTIVE, reconcile()); assertNull(edit())
    }
    @Test fun createIdentityCannotBeReusedAsTheFollowingLocalMutation() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Local later") })
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile(id)); assertNull(edit())
    }
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-integration-import-$token.db"
        private val dispatchName = "captured-provider-dispatch-$token.db"
        private val journalName = "dispatch-integration-capture-$token.db"
        val target = CalendarScope(AccountId("dispatch-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("dispatch-integration-import", 1)
        var currentFormat = format
        val preparationFormat = AdapterFormat("controlled-known-empty-submission", 2)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
        val attempt = MutationId(UUID.randomUUID().toString())
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val content = EventContent("Preparation integration fixture",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        val intent = LocalCreateIntent(LocalCreateReservation(id, localId, target, format, timestamp), content)
        val projection = AndroidCalendarProjection(context)
        var attachmentWrites = 0
        var permitted = true
        var beforeCurrent: suspend () -> Unit = {}
        var afterCurrent: suspend () -> Unit = {}
        var beforeInspection: suspend () -> Unit = {}
        var afterInspection: suspend () -> Unit = {}
        var onAccount: suspend () -> Unit = {}
        var beforeAttachment: suspend () -> Unit = {}
        var afterAttachment: suspend () -> Unit = {}
        val attachmentProjection = object : CalendarProjection by projection {
            override suspend fun captureCapturedCreateCurrent(baseline: CapturedCreateBaseline): CapturedCreateCurrentObservation {
                beforeCurrent()
                val observed = projection.captureCapturedCreateCurrent(baseline)
                afterCurrent()
                return observed
            }
            override fun hasPermissions() = permitted && projection.hasPermissions()
            override val capturedAttachmentProtocol get() = projection.capturedAttachmentProtocol
            override suspend fun inspectCapturedAttachment(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
                beforeInspection()
                val proof = projection.inspectCapturedAttachment(receipt)
                afterInspection()
                return proof
            }
            override suspend fun attachCapturedCreate(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
                beforeAttachment()
                val proof = projection.attachCapturedCreate(receipt)
                if (proof != null) attachmentWrites++
                afterAttachment()
                return proof
            }
        }
        val store = ImportStore(context, importName)
        val creates = CreateAttemptStore(context, dispatchName)
        var journal = LocalCreateStore(context, journalName)
        var calendar = -1L
        var row = -1L
        var sends = 0
        var inspections = 0
        var beforeClaim: suspend () -> Unit = { }
        val verifiedEvent get() = RemoteChange.Upsert(EventId("synthetic-remote"), Revision("synthetic-revision"), content)
        var calls = 0
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, "synthetic-protected-preparation-$calls")
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat: AdapterFormat get() = preparationFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), draft)
                calls++; return onPrepare(draft)
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No generic upload")
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal): CreateSubmissionObservation {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), expected)
                beforeClaim()
                if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                sends++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), RemoteChange.Upsert(
                    EventId("synthetic-remote"), Revision("synthetic-revision"), content))
            }
            override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
                inspections++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), RemoteChange.Upsert(
                    EventId("synthetic-remote"), Revision("synthetic-revision"), content))
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No remote inspection")
        }
        val uri get() = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        private val remote = object : ReadOnlyCalendarRemote {
            override val format get() = currentFormat
            override suspend fun currentAccount(): AccountId { onAccount(); return target.account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No imports")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler, creates = creates, localCreates = journal)
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer preparation integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            calendar = projection.ensureCalendar(target, summary)
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            start()
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalCreate(target, row, localId, id, timestamp))
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        fun candidateValues() = ContentValues().apply {
            val time = content.time as EventTime.Timed
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
            put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
        }
        val mutation = MutationId("after-create-change")
        suspend fun ready() { attached(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain()) }
        suspend fun reconcile(request: MutationId = mutation) = coordinator.reconcileLocalCreate(target, localId, id, attempt, request)
        suspend fun edit(): LocalEditIntent? { allowStore(); return store.localEdit(target, verifiedEvent.eventId) }
        suspend fun deletion(): LocalDeleteIntent? { allowStore(); return store.localDelete(target, verifiedEvent.eventId) }
        fun rawPending(): Int? = store.readableDatabase.rawQuery("SELECT pending FROM records WHERE event=?",
            arrayOf(verifiedEvent.eventId.value)).use { if (it.moveToFirst()) it.getInt(0) else null }
        fun addReminder() {
            val child = CalendarContract.Reminders.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
            assertNotNull(context.contentResolver.insert(child, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }))
        }
        suspend fun admitted() { assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); assertTrue(submit() is CreateSubmissionObservation.Verified) }
        suspend fun bind() = coordinator.bindLocalCreateObservation(target, localId, id, attempt, port)
        suspend fun attached() { admitted(); assertEquals(CapturedCreateBindingResult.RETAINED, bind()); assertEquals(CapturedCreateAttachmentResult.ATTACHED, attach()) }
        suspend fun retain() = coordinator.retainLocalCreateBaseline(target, localId, id, attempt)
        fun allowStore() { store.admitTransaction = { it == target } }
        suspend fun baseline(): CapturedCreateBaseline? { allowStore(); return store.capturedBaseline(target, verifiedEvent.eventId) }
        suspend fun currentBase(): RemoteChange.Upsert? { allowStore(); return store.projectedBaseline(target, verifiedEvent.eventId) }
        suspend fun remoteRecord(): RemoteChange? { allowStore(); return store.transaction(target) { remote(verifiedEvent.eventId) } }
        suspend fun expectedBaseline() = CapturedCreateBaseline(retainedReceipt(), verifiedEvent)
        suspend fun attach() = coordinator.attachLocalCreateBinding(target, localId, id, attempt)
        suspend fun retainedReceipt(): CapturedCreateAttachmentReceipt {
            val identity = checkNotNull(creates.capturedIdentity(target, attempt))
            val binding = checkNotNull(creates.capturedBinding(identity))
            return checkNotNull(creates.capturedAttachment(binding))
        }
        fun dataSnapshot() = providerSnapshot().filterKeys { it !in setOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.SYNC_DATA6) }
        suspend fun incoming(vararg changes: RemoteChange) { store.admitTransaction = { it == target }; store.transaction(target) { changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) } } }
        suspend fun pendingCount() = store.selections(target.account).single().pendingProjectionCount
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }
        fun remoteRows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA4} IS NOT NULL", arrayOf(calendar.toString()), null)!!.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) if (cursor.getLong(0) == row) count++
            count
        }
        suspend fun prepare() = coordinator.stageLocalCreateSubmission(target, localId, id, attempt, port)
        suspend fun submit() = coordinator.submitLocalCreate(target, localId, id, attempt, port)
        suspend fun record() = creates.read(target, attempt)
        suspend fun retainedSubmission(): LocalCreateSubmission? {
            journal.admitCapture = { it == target }; return journal.readSubmission(target, localId)
        }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun providerSnapshot() = listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
            Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
            Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.STATUS, Events.AVAILABILITY, Events.DIRTY, Events.DELETED,
            Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).associateWith(::field)
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun failPreparation(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(journalName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_paused_prep BEFORE UPDATE OF payload ON local_create_submissions " +
                    "WHEN NEW.payload IS NOT NULL BEGIN SELECT RAISE(ABORT, 'fixture preparation commit interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_paused_prep")
            }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); journal.close()
            journal = LocalCreateStore(context, journalName); jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler, creates = creates, localCreates = journal); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); creates.close(); context.deleteDatabase(dispatchName); context.deleteDatabase(importName); context.deleteDatabase(journalName)
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

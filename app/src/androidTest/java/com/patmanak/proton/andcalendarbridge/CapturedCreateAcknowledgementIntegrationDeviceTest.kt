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
class CapturedCreateAcknowledgementIntegrationDeviceTest {
    @Test fun cleanQualificationChecksTheOriginalWithoutWritingOrReleasingPendingWork() = fixture {
        ready(); incoming(verifiedEvent); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val before = providerSnapshot(); val saved = ackRecord()
        assertEquals(CapturedCreateCleanResult.CLEAN, qualify())
        assertEquals(1, cleanInspections); assertEquals(1, ackAttempts); assertEquals(1, sends)
        assertEquals(saved, ackRecord()); assertEquals(before, providerSnapshot()); assertEquals(1, rawPending()); assertHold()
    }
    @Test fun cleanQualificationAfterReopenRechecksTheProviderAndDoesNotWriteAnAck() = fixture {
        ready(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); reopen()
        assertEquals(CapturedCreateCleanResult.CLEAN, qualify()); assertEquals(CapturedCreateCleanResult.CLEAN, qualify())
        assertEquals(2, cleanInspections); assertEquals(1, ackAttempts); assertHold()
    }
    @Test fun cleanQualificationCannotInventAnAcknowledgement() = fixture {
        ready(); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertNull(ackRecord())
        assertEquals(0, cleanInspections); assertEquals(0, ackAttempts); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun cleanQualificationDoesNotPromoteUnknownEvenWithACommittedMarker() = fixture {
        ready(); afterAck = { throw IllegalStateException("Controlled lost response") }; acknowledge(); afterAck = {}
        assertEquals("0", field(Events.DIRTY)); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify())
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, ackRecord()!!.state); assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationRefusesNewLocalEditWithoutCleaningIt() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Later clean qualification edit") })
        val before = providerSnapshot(); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify())
        assertEquals(before, providerSnapshot()); assertEquals(1, ackAttempts); assertHold()
    }
    @Test fun cleanQualificationRefusesContentDriftEvenWithCleanFlags() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.TITLE, "Untracked clean content drift") })
        val before = providerSnapshot(); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(before, providerSnapshot())
    }
    @Test fun cleanQualificationRefusesLocalTombstoneAndLeavesItPresent() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        val before = providerSnapshot(); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(before, providerSnapshot())
    }
    @Test fun cleanQualificationDoesNotInferDeletionOrInsertAMissingOriginal() = fixture {
        ready(); acknowledge(); context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(0, rows()); assertEquals(1, sends)
    }
    @Test fun cleanQualificationRefusesNewReminderWithoutRemovingIt() = fixture {
        ready(); acknowledge(); addReminder(); val before = providerSnapshot()
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(before, providerSnapshot()); assertHold()
    }
    @Test fun cleanQualificationRefusesDamagedHistoricalMarker() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.SYNC_DATA7, "foreign") })
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals("foreign", field(Events.SYNC_DATA7))
    }
    @Test fun cleanQualificationCannotConsumeARetainedLocalIntentionAfterAndroidRevert() = fixture {
        ready(); incoming(verifiedEvent); acknowledge(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Retained before clean qualification") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile()); val saved = edit()
        mutate(ContentValues().apply { put(Events.DIRTY, 0); put(Events.TITLE, content.title) })
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(saved, edit()); assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationPreservesDifferentIncomingDataAndTheHold() = fixture {
        ready(); acknowledge(); val newer = verifiedEvent.copy(revision = Revision("qualification-later"), content = content.copy(title = "Different incoming qualification")); incoming(newer)
        assertEquals(CapturedCreateCleanResult.CLEAN, qualify()); assertEquals(newer, remoteRecord()); assertEquals(1, rawPending()); assertHold()
    }
    @Test fun cleanQualificationClosesAdmissionWhenPermissionIsUnavailable() = fixture {
        ready(); acknowledge(); permitted = false
        assertEquals(CapturedCreateCleanResult.INACTIVE, qualify()); assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationClosesAdmissionAfterPermissionLossFollowingInspection() = fixture {
        ready(); acknowledge(); afterClean = { permitted = false }
        assertEquals(CapturedCreateCleanResult.INACTIVE, qualify()); assertEquals(1, cleanInspections); assertEquals("0", field(Events.DIRTY))
    }
    @Test fun cleanQualificationRefusesAdapterFormatDriftBeforeProviderInspection() = fixture {
        ready(); acknowledge(); currentFormat = AdapterFormat(format.id, format.version + 1)
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationRefusesSourceDriftFollowingProviderInspection() = fixture {
        ready(); acknowledge(); afterClean = { currentFormat = AdapterFormat(format.id, format.version + 1) }
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(1, cleanInspections); assertHold()
    }
    @Test fun cleanQualificationClosesAllLeasesAfterAnInterruptedInspection() = fixture {
        ready(); acknowledge(); beforeClean = { throw IllegalStateException("Controlled clean qualification interruption") }
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify())
        assertFalse(store.admitTransaction(target)); assertFalse(journal.admitCapture(target)); assertFalse(attachmentProjection.admitOperation())
        beforeClean = {}; assertEquals(CapturedCreateCleanResult.CLEAN, qualify()); assertEquals(1, ackAttempts)
    }
    @Test fun cleanQualificationIsOnlyAnObservationAndCannotPreventTheNextLocalEdit() = fixture {
        ready(); acknowledge(); assertEquals(CapturedCreateCleanResult.CLEAN, qualify())
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Edit after clean observation") })
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals("Edit after clean observation", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertHold()
    }
    @Test fun cleanQualificationRejectsDifferentCaptureAndAttemptBeforeProviderRead() = fixture {
        ready(); acknowledge()
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, coordinator.qualifyAcknowledgedLocalCreate(target, localId, MutationId("other-capture"), attempt))
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, coordinator.qualifyAcknowledgedLocalCreate(target, localId, id, MutationId("other-attempt")))
        assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationRejectsAnUnrelatedProviderWitness() = fixture {
        ready(); acknowledge(); transformClean = { it?.let { proof -> proof.copy(receipt = proof.receipt.copy(nonce = UUID.randomUUID().toString())) } }
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify()); assertEquals(1, cleanInspections); assertHold()
    }
    @Test fun cleanQualificationRefusesAPersistentlyPausedCalendar() = fixture {
        ready(); acknowledge(); val selected = coordinator.state.value.selections.single { it.scope == target }
        coordinator.setEnabled(selected, false)
        withTimeout(5_000) { while (store.selections(target.account).single { it.scope == target }.enabled) delay(10) }
        assertEquals(CapturedCreateCleanResult.INACTIVE, qualify()); assertEquals(0, cleanInspections)
    }
    @Test fun cleanQualificationClosesAdmissionOnSignOutFollowingInspection() = fixture {
        ready(); acknowledge(); afterClean = { coordinator.authenticationChanged(false) }
        assertEquals(CapturedCreateCleanResult.INACTIVE, qualify()); assertEquals(1, cleanInspections)
        assertFalse(store.admitTransaction(target)); assertFalse(journal.admitCapture(target)); assertFalse(attachmentProjection.admitOperation())
    }
    @Test fun unchangedOriginalIsAcknowledgedOnceWithoutConsumingIncomingEvidenceOrHold() = fixture {
        ready(); incoming(verifiedEvent); val before = providerSnapshot()
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals("0", field(Events.DIRTY)); assertNotNull(field(Events.SYNC_DATA7))
        val unchangedColumns = before.keys - setOf(Events.DIRTY, Events.SYNC_DATA7)
        assertEquals(before.filterKeys { it in unchangedColumns }, providerSnapshot().filterKeys { it in unchangedColumns })
        assertEquals(CapturedCreateAcknowledgementState.PROVEN, ackRecord()!!.state)
        assertEquals(1, ackAttempts); assertEquals(1, rawPending()); assertEquals(verifiedEvent, remoteRecord()); assertHold()
        assertEquals(1, sends); assertEquals(1, attachmentWrites)
    }
    @Test fun reopeningInspectsTheSameAdmissionWithoutAnotherAckWrite() = fixture {
        ready(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); val saved = ackRecord()
        reopen(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(saved, ackRecord()); assertEquals(1, ackAttempts); assertEquals(1, ackInspections); assertHold()
    }
    @Test fun laterTitleCannotBeCleanedOrReserveAck() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Later edit") }); val before = providerSnapshot()
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertNull(ackRecord()); assertEquals(0, ackAttempts); assertEquals(before, providerSnapshot())
    }
    @Test fun presentDeletionCannotBeCleanedOrReserveAck() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.DELETED, 1) })
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertNull(ackRecord()); assertEquals("1", field(Events.DELETED))
    }
    @Test fun existingReminderIsUnsupportedAndPreserved() = fixture {
        ready(); addReminder(); val before = providerSnapshot()
        assertEquals(CapturedCreateAcknowledgementResult.UNSUPPORTED, acknowledge()); assertNull(ackRecord()); assertEquals(before, providerSnapshot())
    }
    @Test fun retainedLocalIntentionPreventsAckEvenWhenAndroidWasReverted() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.TITLE, "Later edit") }); assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile())
        val saved = edit(); mutate(ContentValues().apply { put(Events.TITLE, content.title) })
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertNull(ackRecord()); assertEquals(saved, edit()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun editAfterPreflightLeavesUnknownAdmissionWithoutReplayWrite() = fixture {
        ready(); afterCurrent = { afterCurrent = {}; mutate(ContentValues().apply { put(Events.TITLE, "Changed after preflight") }) }
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); val saved = ackRecord()
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, saved!!.state); assertEquals("1", field(Events.DIRTY))
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(saved, ackRecord()); assertEquals(1, ackAttempts)
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, reconcile()); assertNull(edit()); assertHold()
    }
    @Test fun interruptedBeforeProviderKeepsFenceAndNeverRearms() = fixture {
        ready(); beforeAck = { throw IllegalStateException("Controlled pre-batch interruption") }
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertNull(field(Events.SYNC_DATA7))
        val saved = ackRecord(); beforeAck = {}
        reopen(); assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(saved, ackRecord()); assertEquals(1, ackAttempts); assertEquals("1", field(Events.DIRTY)); assertHold()
    }
    @Test fun lostResponseRecoversHistoricalProofAndCapturesNewEditWithoutReAck() = fixture {
        ready(); afterAck = { throw IllegalStateException("Controlled lost batch response") }
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); val nonce = ackRecord()!!.receipt.nonce
        mutate(ContentValues().apply { put(Events.TITLE, "Edit after committed ACK"); put(Events.DIRTY, 1) }); val before = providerSnapshot()
        afterAck = {}; reopen(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(before, providerSnapshot()); assertEquals(nonce, ackRecord()!!.receipt.nonce); assertEquals(1, ackAttempts)
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile()); assertEquals("Edit after committed ACK", edit()!!.content.title); assertHold()
    }
    @Test fun lostResponseWithLaterTombstonePreservesDeletionAndCapturesIt() = fixture {
        ready(); afterAck = { throw IllegalStateException("Controlled lost batch response") }; assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge())
        mutate(ContentValues().apply { put(Events.DELETED, 1); put(Events.DIRTY, 1) }); afterAck = {}
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals("1", field(Events.DELETED))
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcile()); assertNotNull(deletion()); assertEquals(1, ackAttempts)
    }
    @Test fun lostResponseWithLaterReminderRecoversOnlyHistoricalProof() = fixture {
        ready(); afterAck = { throw IllegalStateException("Controlled lost batch response") }; assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge())
        addReminder(); mutate(ContentValues().apply { put(Events.DIRTY, 1) }); afterAck = {}; val before = providerSnapshot()
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(before, providerSnapshot())
        assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcile()); assertNull(edit()); assertEquals(1, ackAttempts)
    }
    @Test fun provenAckReplaysAfterNewIntentWithoutModifyingIt() = fixture {
        ready(); incoming(verifiedEvent); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        mutate(ContentValues().apply { put(Events.TITLE, "After ACK"); put(Events.DIRTY, 1) }); assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcile())
        val saved = edit(); val before = providerSnapshot(); reopen()
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(saved, edit()); assertEquals(before, providerSnapshot()); assertEquals(1, ackAttempts)
    }
    @Test fun proofCommitFailureResumesByInspectionWithoutSecondBatch() = fixture {
        ready(); beforeAck = { failAckCommit(true) }
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals("0", field(Events.DIRTY))
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, ackRecord()!!.state); failAckCommit(false); beforeAck = {}
        reopen(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(1, ackAttempts)
    }
    @Test fun missingOriginalAfterUncertainAckNeverCreatesOrRearms() = fixture {
        ready(); afterAck = { throw IllegalStateException("Controlled lost batch response") }; assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge())
        val saved = ackRecord(); assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))); afterAck = {}
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(saved, ackRecord()); assertEquals(0, rows()); assertEquals(1, ackAttempts)
    }
    @Test fun corruptedAckMarkerCannotBeAdoptedFromProvenSqlState() = fixture {
        ready(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); mutate(ContentValues().apply { put(Events.SYNC_DATA7, "foreign") })
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(1, ackAttempts); assertHold()
    }
    @Test fun differentRemoteBeforeAckRemainsPendingAndHeld() = fixture {
        ready(); val newer = verifiedEvent.copy(content = content.copy(title = "Remote later")); incoming(newer)
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(newer, remoteRecord()); assertEquals(1, rawPending()); assertHold()
    }
    @Test fun differentRemoteAfterAckRemainsPendingAndHeld() = fixture {
        ready(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); val newer = verifiedEvent.copy(revision = Revision("later"), content = content.copy(title = "Remote later")); incoming(newer)
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(newer, remoteRecord()); assertEquals(1, rawPending()); assertEquals(content.title, field(Events.TITLE)); assertHold()
    }
    @Test fun disabledCalendarClosesAckAdmission() = fixture {
        ready(); coordinator.setEnabled(store.selections(target.account).single(), false)
        // Selection changes are asynchronous. Establish the durable inactive precondition.
        withTimeout(5_000) { while (store.selections(target.account).single().enabled) delay(10) }
        assertFalse(store.selections(target.account).single().enabled)
        assertEquals(CapturedCreateAcknowledgementResult.INACTIVE, acknowledge()); allowStore(); assertNull(ackRecord()); assertEquals(0, ackAttempts)
    }
    @Test fun accountLossBeforeAckClosesAdmission() = fixture {
        ready(); coordinator.authenticationChanged(false)
        assertEquals(CapturedCreateAcknowledgementResult.INACTIVE, acknowledge()); assertEquals(0, ackAttempts); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun controlledPermissionLossBeforeAckClosesAdmission() = fixture {
        ready(); permitted = false
        assertEquals(CapturedCreateAcknowledgementResult.INACTIVE, acknowledge()); assertEquals(0, ackAttempts)
    }
    @Test fun formatReplacementCannotAdoptBaseline() = fixture {
        ready(); currentFormat = AdapterFormat("replacement", 2)
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(0, ackAttempts)
    }
    @Test fun missingBaselineCannotReserveOrAck() = fixture {
        attached(); assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertNull(ackRecord()); assertEquals(0, ackAttempts)
    }
    @Test fun attachmentMarkerDriftCannotReserveOrAck() = fixture {
        ready(); mutate(ContentValues().apply { put(Events.SYNC_DATA6, "foreign") })
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertNull(ackRecord()); assertEquals(0, ackAttempts)
    }
    @Test fun accessLossAfterCommittedBatchPreservesHistoricalRecovery() = fixture {
        ready(); afterAck = { permitted = false }
        assertEquals(CapturedCreateAcknowledgementResult.INACTIVE, acknowledge()); assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, ackRecord()!!.state)
        permitted = true; afterAck = {}; assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge()); assertEquals(1, ackAttempts)
    }
    @Test fun provenReplayRechecksAuthoritativeSourceAfterInspection() = fixture {
        ready(); assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        afterAckInspection = { currentFormat = AdapterFormat("replacement", 2) }
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(1, ackAttempts)
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
        var ackAttempts = 0
        var ackInspections = 0
        var cleanInspections = 0
        var beforeClean: suspend () -> Unit = {}
        var afterClean: suspend () -> Unit = {}
        var transformClean: (CapturedCreateCleanWitness?) -> CapturedCreateCleanWitness? = { it }
        var beforeAck: suspend () -> Unit = {}
        var afterAck: suspend () -> Unit = {}
        var beforeAckInspection: suspend () -> Unit = {}
        var afterAckInspection: suspend () -> Unit = {}
        var permitted = true
        var beforeCurrent: suspend () -> Unit = {}
        var afterCurrent: suspend () -> Unit = {}
        var beforeInspection: suspend () -> Unit = {}
        var afterInspection: suspend () -> Unit = {}
        var onAccount: suspend () -> Unit = {}
        var beforeAttachment: suspend () -> Unit = {}
        var afterAttachment: suspend () -> Unit = {}
        val attachmentProjection = object : CalendarProjection by projection {
            override suspend fun inspectAcknowledgedCreateClean(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCleanWitness? {
                cleanInspections++; beforeClean()
                val proof = projection.inspectAcknowledgedCreateClean(receipt)
                afterClean(); return transformClean(proof)
            }
            override suspend fun acknowledgeCapturedCreate(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? {
                ackAttempts++; beforeAck()
                val proof = projection.acknowledgeCapturedCreate(receipt)
                afterAck(); return proof
            }
            override suspend fun inspectCapturedAcknowledgement(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? {
                ackInspections++; beforeAckInspection()
                val proof = projection.inspectCapturedAcknowledgement(receipt)
                afterAckInspection(); return proof
            }
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
        var store = ImportStore(context, importName)
        var creates = CreateAttemptStore(context, dispatchName)
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
        suspend fun acknowledge() = coordinator.acknowledgeLocalCreate(target, localId, id, attempt)
        suspend fun qualify() = coordinator.qualifyAcknowledgedLocalCreate(target, localId, id, attempt)
        suspend fun ackRecord(): CapturedCreateAcknowledgementRecord? { allowStore(); return store.capturedAcknowledgement(target, verifiedEvent.eventId) }
        suspend fun assertHold() = assertTrue(creates.capturedProjectionHold(target, format).holds(verifiedEvent.eventId))
        fun failAckCommit(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(importName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_ack BEFORE UPDATE OF state ON captured_create_acknowledgements BEGIN SELECT RAISE(ABORT, 'fixture acknowledgement commit interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_ack")
            }
        }
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
            store.close(); creates.close(); store = ImportStore(context, importName); creates = CreateAttemptStore(context, dispatchName)
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

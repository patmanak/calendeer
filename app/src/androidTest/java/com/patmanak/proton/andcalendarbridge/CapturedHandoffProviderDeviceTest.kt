// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.Manifest
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native conditional update and historical recovery qualification; no server writes. */
@RunWith(AndroidJUnit4::class)
class CapturedHandoffProviderDeviceTest {
    @Test fun originalCurrentBaseHasOnlySixAssertionsAndNoMutation() = fixture {
        val before = values(); val adapter = intercepted()
        assertEquals(CapturedHandoffCleanWitness(frame), adapter.inspectCapturedHandoffBase(frame))
        assertEquals(1, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun conditionalUpdateChangesContentAndReceiptWithoutDuplicatingOrErasingOrigin() = fixture {
        val before = values(); val plan = plan(); val adapter = intercepted(mode = 7)
        assertEquals(AppliedCapturedHandoffProjection(plan), adapter.applyCapturedHandoffProjection(plan))
        assertEquals(1, realBatchAttempts); assertEquals(1, rows())
        assertEquals(plan.receipt.change.content.title, field(Events.TITLE)); assertEquals("0", field(Events.DIRTY))
        assertNotEquals(before[Events.SYNC_DATA2], field(Events.SYNC_DATA2))
        assertEquals("calendeer-projection-receipt-v1:${plan.receipt.nonce}", field(Events.SYNC_DATA3))
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7))
            assertEquals(before[column], field(column))
    }

    @Test fun allDayUpdateRetainsExclusiveEndAndCanQualifyItsCurrentFrame() = fixture(allDay = true) {
        val current = applied(); val before = values()
        assertEquals(CapturedHandoffCleanWitness(current), projection.inspectCapturedHandoffBase(current))
        assertEquals(before, values()); assertEquals("1", field(Events.ALL_DAY)); assertEquals("UTC", field(Events.EVENT_TIMEZONE))
    }

    @Test fun secondProjectionUsesTheAdvancedBaseAndPreservesOriginalProvenance() = fixture {
        val origin = field(Events.SYNC_DATA7); val first = applied(); val next = plan(first, "second")
        val current = applied(next)
        assertEquals(CapturedHandoffCleanWitness(current), projection.inspectCapturedHandoffBase(current))
        assertEquals(origin, field(Events.SYNC_DATA7)); assertEquals(1, rows())
        assertNull(projection.inspectCapturedHandoffBase(first)); assertNull(projection.inspectCapturedHandoffBase(frame))
    }

    @Test fun historicalInspectionUsesOnlyThreeAssertions() = fixture {
        val plan = plan(); applied(plan); val before = values(); val adapter = intercepted(mode = 3)
        assertEquals(AppliedCapturedHandoffProjection(plan), adapter.inspectCapturedHandoffProjection(plan))
        assertEquals(1, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun historicalProofIgnoresAndPreservesLaterDirtyContentChildrenAndTombstone() = fixture {
        val plan = plan(); applied(plan)
        mutate(ContentValues().apply { put(Events.TITLE, "Later local content"); put(Events.DIRTY, 1); put(Events.DELETED, 1) }); reminder()
        val before = values()
        assertEquals(AppliedCapturedHandoffProjection(plan), projection.inspectCapturedHandoffProjection(plan))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun lostApplyResponseRecoversHistoricallyWithoutReapplyingLaterContent() = fixture {
        val plan = plan(); val adapter = intercepted(mode = 7, afterBatch = { throw android.os.RemoteException("Controlled response loss") })
        try { adapter.applyCapturedHandoffProjection(plan); fail("The controlled response must be lost") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, failure.category) }
        assertEquals(plan.receipt.change.content.title, field(Events.TITLE))
        mutate(ContentValues().apply { put(Events.TITLE, "Later edit after lost response"); put(Events.DIRTY, 1) }); val before = values()
        assertEquals(AppliedCapturedHandoffProjection(plan), projection.inspectCapturedHandoffProjection(plan))
        assertNull(projection.applyCapturedHandoffProjection(plan)); assertEquals(before, values()); assertEquals(1, rows())
    }

    @Test fun malformedApplyResponsesNeverEstablishAWitnessDespiteACommittedWrite() = fixture {
        var current = frame
        for ((index, transform) in malformedResponses().withIndex()) {
            val plan = plan(current, "malformed-$index")
            assertUnavailable { intercepted(mode = 7, batchResponse = transform).applyCapturedHandoffProjection(plan) }
            assertEquals(plan.receipt.change.content.title, field(Events.TITLE))
            assertEquals(AppliedCapturedHandoffProjection(plan), projection.inspectCapturedHandoffProjection(plan))
            current = advanced(plan)
        }
        assertEquals(1, rows())
    }

    @Test fun missingCleanAssertionResponseNeverEstablishesACurrentWitness() = fixture {
        val before = values()
        assertUnavailable { intercepted(afterBatch = { throw android.os.RemoteException("Controlled clean response loss") })
            .inspectCapturedHandoffBase(frame) }
        for (transform in malformedResponses())
            assertUnavailable { intercepted(batchResponse = transform).inspectCapturedHandoffBase(frame) }
        assertEquals(before, values())
    }

    @Test fun missingHistoricalResponseNeverEstablishesAnAcknowledgementWitness() = fixture {
        val before = values()
        assertUnavailable { intercepted(mode = 3, afterBatch = { throw android.os.RemoteException("Controlled ACK history response loss") })
            .inspectCapturedAcknowledgement(ack) }
        for (transform in malformedResponses())
            assertUnavailable { intercepted(mode = 3, batchResponse = transform).inspectCapturedAcknowledgement(ack) }
        assertEquals(before, values())
    }

    @Test fun missingHistoricalResponseNeverEstablishesAHandoffProjectionWitness() = fixture {
        val plan = plan(); applied(plan); val before = values()
        assertUnavailable { intercepted(mode = 3, afterBatch = { throw android.os.RemoteException("Controlled handoff history response loss") })
            .inspectCapturedHandoffProjection(plan) }
        for (transform in malformedResponses())
            assertUnavailable { intercepted(mode = 3, batchResponse = transform).inspectCapturedHandoffProjection(plan) }
        assertEquals(before, values())
    }

    @Test fun missingRowNeverFallsBackToInsertionOrAnUnmarkedReplacement() = fixture {
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(0, rows())
        context.contentResolver.insert(uri, candidateValues())!!
        assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(1, rows())
    }

    @Test fun staleOriginalFrameCannotOverwriteAnAlreadyAdvancedRow() = fixture {
        val plan = plan(); applied(plan); val before = values()
        assertNull(projection.applyCapturedHandoffProjection(plan)); assertEquals(before, values())
        assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
    }

    @Test fun dirtyContentMatchingCurrentBaseStillRefusesProjection() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 1) }); val before = values()
        assertNull(projection.applyCapturedHandoffProjection(plan())); assertNull(projection.inspectCapturedHandoffBase(frame))
        assertEquals(before, values())
    }

    @Test fun contentChangedWithDirtyZeroStillRefusesProjection() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Unstamped local change") }); val before = values()
        assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values())
    }

    @Test fun cleanTombstoneRefusesProjectionWithoutInferringDeletion() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values()); assertEquals(1, rows())
    }

    @Test fun childRowsRefuseProjectionAndRemainIntact() = fixture {
        reminder(); val before = values(); assertNull(projection.applyCapturedHandoffProjection(plan()))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun unsupportedFlagsColorPrecisionAndRecurrenceRefuseProjection() = fixture {
        for ((column, changed) in listOf(Events.EVENT_COLOR to "-5631710", Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(),
            Events.GUESTS_CAN_MODIFY to "1", Events.RRULE to "FREQ=DAILY", Events.DTSTART to
                ((content.time as EventTime.Timed).start.toEpochMilli() + 1).toString())) {
            val previous = field(column); mutate(ContentValues().apply { put(column, changed) }); val before = values()
            assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values())
            mutate(ContentValues().apply { if (previous == null) putNull(column) else put(column, previous) })
        }
    }

    @Test fun substitutedOrUnknownSyncMetadataRefusesConditionalUpdateWithoutRepair() = fixture {
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4,
            Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val previous = field(column); mutate(ContentValues().apply { put(column, "unknown-handoff-proof") }); val before = values()
            assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values())
            mutate(ContentValues().apply { if (previous == null) putNull(column) else put(column, previous) })
        }
    }

    @Test fun changedFullOriginalCaptureSourceCannotAdoptCurrentRow() = fixture {
        val origin = ack.baseline.receipt.copy(binding = ack.baseline.receipt.binding.copy(identity =
            ack.baseline.receipt.binding.identity.copy(captureId = MutationId("different-handoff-capture"))))
        val changed = CapturedCreateHandoffFrame(frame.receipt.copy(acknowledgement = ack.copy(baseline = baseline.copy(receipt = origin))), baseline.change)
        val before = values(); assertNull(projection.applyCapturedHandoffProjection(plan(changed))); assertEquals(before, values())
    }

    @Test fun duplicatedLocalMarkerRefusesProjectionWithoutMerging() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        val before = values(); assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun duplicatedRemoteMarkerRefusesProjectionWithoutMerging() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!!
        val before = values(); assertNull(projection.applyCapturedHandoffProjection(plan())); assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun concurrentDirtyFlagAbortsConditionalWriteAndPreservesOldReceipt() = fixture {
        val adapter = intercepted(mode = 7) { mutate(ContentValues().apply { put(Events.DIRTY, 1) }) }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }
        assertEquals("1", field(Events.DIRTY)); assertEquals(content.title, field(Events.TITLE)); assertNull(field(Events.SYNC_DATA3))
    }

    @Test fun concurrentContentEditEvenWithDirtyZeroAbortsConditionalWrite() = fixture {
        val adapter = intercepted(mode = 7) { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent clean local edit") }) }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }
        assertEquals("Concurrent clean local edit", field(Events.TITLE)); assertNull(field(Events.SYNC_DATA3))
    }

    @Test fun concurrentTombstoneAbortsConditionalWrite() = fixture {
        val adapter = intercepted(mode = 7) { mutate(ContentValues().apply { put(Events.DELETED, 1) }) }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }; assertEquals("1", field(Events.DELETED)); assertNull(field(Events.SYNC_DATA3))
    }

    @Test fun concurrentChildAbortsConditionalWriteWithoutDiscardingIt() = fixture {
        val adapter = intercepted(mode = 7) { reminder() }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }
        assertEquals(content.title, field(Events.TITLE)); assertNull(field(Events.SYNC_DATA3)); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun concurrentOriginSubstitutionAbortsConditionalWriteWithoutRepair() = fixture {
        val adapter = intercepted(mode = 7) { mutate(ContentValues().apply { put(Events.SYNC_DATA7, "concurrent-origin") }) }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }; assertEquals("concurrent-origin", field(Events.SYNC_DATA7)); assertNull(field(Events.SYNC_DATA3))
    }

    @Test fun concurrentDuplicateLocalMarkerAbortsConditionalWrite() = fixture {
        val adapter = intercepted(mode = 7) {
            context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }; assertEquals(content.title, field(Events.TITLE)); assertEquals(2, rows())
    }

    @Test fun concurrentDuplicateRemoteMarkerAbortsConditionalWrite() = fixture {
        val adapter = intercepted(mode = 7) {
            context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!!
        }
        assertBatchRejected { adapter.applyCapturedHandoffProjection(plan()) }; assertEquals(content.title, field(Events.TITLE)); assertEquals(2, rows())
    }

    @Test fun historicalProofRejectsChangedNonceRevisionOrOriginWithoutRepair() = fixture {
        val plan = plan(); applied(plan); val before = values()
        assertNull(projection.inspectCapturedHandoffProjection(plan.copy(receipt = plan.receipt.copy(nonce = UUID.randomUUID().toString()))))
        assertNull(projection.inspectCapturedHandoffProjection(plan.copy(receipt = plan.receipt.copy(change = plan.receipt.change.copy(revision = Revision("wrong-revision"))))))
        mutate(ContentValues().apply { put(Events.SYNC_DATA6, "wrong-origin") }); val changed = values()
        assertNull(projection.inspectCapturedHandoffProjection(plan)); assertEquals(changed, values()); assertEquals(before[Events.SYNC_DATA3], field(Events.SYNC_DATA3))
    }

    @Test fun historicalProofRejectsConcurrentMarkerChangeAtomically() = fixture {
        val plan = plan(); applied(plan)
        val adapter = intercepted(mode = 3) { mutate(ContentValues().apply { put(Events.SYNC_DATA3, "concurrent-receipt") }) }
        assertBatchRejected { adapter.inspectCapturedHandoffProjection(plan) }; assertEquals("concurrent-receipt", field(Events.SYNC_DATA3))
    }

    @Test fun laterDirtySnapshotCarriesAdvancedBaseWithoutCleaningAndroid() = fixture {
        val current = applied(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Post-handoff local edit") }); val before = values()
        val adapter = intercepted(); val observed = adapter.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
        assertEquals(current, observed.snapshot.frame); assertEquals("Post-handoff local edit", observed.snapshot.content.title)
        assertFalse(observed.snapshot.deleted); assertFalse(observed.snapshot.unchanged); assertEquals(before, values())
        assertEquals(CapturedHandoffCurrentObservation.Unresolved, projection.captureCapturedHandoffCurrent(frame))
    }

    @Test fun laterPresentDirtyTombstoneIsCapturedWithoutDeletionOrAcknowledgement() = fixture {
        val current = applied(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) }); val before = values()
        val observed = projection.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
        assertTrue(observed.snapshot.deleted); assertEquals(current.base.content, observed.snapshot.content); assertEquals(before, values()); assertEquals(1, rows())
    }

    @Test fun laterDirtySnapshotWithChildrenIsUnsupportedAndPreserved() = fixture {
        val current = applied(); mutate(ContentValues().apply { put(Events.DIRTY, 1) }); reminder(); val before = values()
        assertEquals(CapturedHandoffCurrentObservation.Unsupported, projection.captureCapturedHandoffCurrent(current))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun concurrentDirtySnapshotContentChangeAbortsThePointInTimeObservation() = fixture {
        val current = applied(); mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent post-handoff edit") }) }
        assertBatchRejected { adapter.captureCapturedHandoffCurrent(current) }; assertEquals("Concurrent post-handoff edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun inactiveLeasePreventsProjectionQueriesOrWrite() = fixture {
        val before = values(); val adapter = intercepted(mode = 7); adapter.admitOperation = { false }
        try { adapter.applyCapturedHandoffProjection(plan()); fail("Inactive lease must cancel") } catch (_: CancellationException) {}
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun controlledGrantDenialPreventsProjectionWithoutChangingRealPermissions() = fixture {
        val before = values(); val adapter = intercepted(mode = 7, permissionGranted = { false })
        try { adapter.applyCapturedHandoffProjection(plan()); fail("Denied grants must block") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, interceptedQueries); assertEquals(before, values())
    }

    @Test fun accessLossAfterWriteReturnsNoWitnessButRetainsHistoricalRecovery() = fixture {
        val plan = plan(); var admitted = true
        val adapter = intercepted(mode = 7, afterBatch = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.applyCapturedHandoffProjection(plan); fail("Lost lease must cancel") } catch (_: CancellationException) {}
        assertEquals(AppliedCapturedHandoffProjection(plan), projection.inspectCapturedHandoffProjection(plan)); assertEquals(1, rows())
    }

    @Test fun genericProjectionAndGenericCaptureStayFencedAfterHandoffAdvancement() = fixture {
        val current = applied(); val before = values()
        assertIdentityRefused { projection.apply(ProjectionIntent(target, plan(current).receipt.change)) }
        assertIdentityRefused { projection.applyTracked(plan(current).receipt) }
        mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        assertNull(projection.captureOwnedSimpleEdit(target, current.base)); assertNull(projection.captureOwnedSimpleDelete(target, current.base))
        assertEquals(before[Events.SYNC_DATA5], field(Events.SYNC_DATA5)); assertEquals(before[Events.SYNC_DATA7], field(Events.SYNC_DATA7)); assertEquals("1", field(Events.DIRTY))
    }

    private class Fixture(val context: Context, val allDay: Boolean) {
        val target = CalendarScope(AccountId("captured-handoff-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val projection = AndroidCalendarProjection(context)
        val content = EventContent("Captured handoff provider fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        private val captured = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), localId, target,
            AdapterFormat("handoff-provider-import", 1), Instant.parse("2030-10-01T12:00:00Z")), content)
        private val stage = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
            MutationId(UUID.randomUUID().toString()), AdapterFormat("handoff-provider-submission", 2)), "opaque-fixture")
        val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(stage),
            EventId("fixture-remote"), Revision("fixture-revision")), UUID.randomUUID().toString())
        val baseline = CapturedCreateBaseline(receipt, RemoteChange.Upsert(receipt.binding.eventId, receipt.binding.verifiedRevision, content))
        val ack = CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())
        val frame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack, UUID.randomUUID().toString()), baseline.change)
        fun plan(from: CapturedCreateHandoffFrame = frame, suffix: String = "next") = CapturedHandoffProjection(from,
            ProjectionReceipt(target, baseline.format, from.base.copy(revision = Revision("handoff-$suffix"),
                content = content.copy(title = "Handoff remote $suffix")), UUID.randomUUID().toString(), from.base))
        fun advanced(plan: CapturedHandoffProjection) = CapturedCreateHandoffFrame(frame.receipt, plan.receipt.change, plan.receipt)
        suspend fun applied(plan: CapturedHandoffProjection = plan()): CapturedCreateHandoffFrame {
            assertEquals(AppliedCapturedHandoffProjection(plan), projection.applyCapturedHandoffProjection(plan))
            return advanced(plan)
        }
        val marker = "calendeer-captured-ack-v1:" + ack.fingerprint()
        suspend fun acknowledged() {
            assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.acknowledgeCapturedCreate(ack))
        }
        var calendar = -1L
        var row = -1L
        var interceptedQueries = 0
        var interceptedBatches = 0
        var realBatchAttempts = 0

        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri {
            val account = projection.accountFor(target.account)
            return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        fun candidateValues() = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            when (val time = content.time) {
                is EventTime.Timed -> {
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                }
                is EventTime.AllDay -> {
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                }
            }
        }
        suspend fun seed() {
            calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer captured current provider fixture"))
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            assertEquals(content, projection.captureAndStampSimpleCreate(target, row, localId)!!.content)
            assertEquals(AppliedCapturedCreateAttachment(receipt), projection.attachCapturedCreate(receipt))
            acknowledged()
        }
        fun ordinary() {
            mutate(ContentValues().apply { putNull(Events.SYNC_DATA5); putNull(Events.SYNC_DATA6); putNull(Events.SYNC_DATA7) })
        }
        fun updated() = baseline.change.copy(revision = Revision("generic-next"), content = content.copy(title = "Generic remote title"))
        fun tracked() = ProjectionReceipt(target, baseline.format, updated(), UUID.randomUUID().toString(), baseline.change)
        fun fieldAt(event: Long, column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(event.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
            }
        suspend fun series(): Pair<RemoteChange.Upsert, Long> {
            val master = baseline.change.copy(eventId = EventId("clean-series-master"),
                content = content.copy(recurrence = EventRecurrence("clean-fixture-series", "FREQ=DAILY;COUNT=3")))
            projection.apply(ProjectionIntent(target, master))
            val child = baseline.change.copy(eventId = EventId("clean-series-child"), content = content.copy(title = "Clean series child",
                recurrence = EventRecurrence("clean-fixture-series", original = RecurrencePoint.Timed(Instant.parse("2030-10-28T00:30:00Z")))))
            projection.apply(ProjectionIntent(target, child))
            val childRow = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?",
                arrayOf(calendar.toString(), "Clean series child"), null)!!.use {
                    assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
                }
            return master to childRow
        }
        suspend fun exceptionParent(): Pair<Long, RemoteChange.Upsert> {
            val master = baseline.change.copy(eventId = EventId("clean-exception-parent"),
                content = content.copy(title = "Clean standalone master", recurrence = EventRecurrence("clean-attachment-series", "FREQ=DAILY;COUNT=3")))
            projection.apply(ProjectionIntent(target, master))
            val parent = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?",
                arrayOf(calendar.toString(), "Clean standalone master"), null)!!.use {
                    assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
                }
            val child = baseline.change.copy(eventId = EventId("clean-unattached-exception"), content = content.copy(title = "Clean unattached child",
                recurrence = EventRecurrence("clean-attachment-series", original = RecurrencePoint.Timed(Instant.parse("2030-10-28T00:30:00Z")))))
            return parent to child
        }
        suspend fun capture(candidate: CapturedCreateBaseline = baseline) = projection.captureCapturedCreateCurrent(candidate)
        suspend fun supported() = (capture() as CapturedCreateCurrentObservation.Supported).snapshot
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun reminder() {
            context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })!!
        }
        fun children(child: Uri, column: String) = context.contentResolver.query(syncUri(child), arrayOf("_id"),
            "$column=?", arrayOf(row.toString()), null)!!.use { it.count }
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
            arrayOf(calendar.toString()), null)!!.use { it.count }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun values(): Map<String, String?> {
            val columns = listOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
                Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DIRTY, Events.DELETED,
                Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
                Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10,
                Events.STATUS, Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
                Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION, Events.ORGANIZER,
                Events.ACCESS_LEVEL, Events.AVAILABILITY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
            return columns.associateWith(::field)
        }
        fun intercepted(mode: Int = 6, afterBatch: () -> Unit = {},
            batchResponse: (Array<ContentProviderResult>) -> Array<ContentProviderResult> = { it },
            permissionGranted: () -> Boolean = { true }, beforeBatch: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Only scoped atomic batches are allowed")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Only scoped atomic batches are allowed")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Only scoped atomic batches are allowed")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    interceptedQueries++; return real.query(uri, columns, selection, args, order)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertEquals(mode, operations.size)
                    if (mode == 7) {
                        assertTrue(operations.take(6).all { it.isAssertQuery })
                        assertTrue(operations.last().isUpdate)
                    } else assertTrue(operations.all { it.isAssertQuery })
                    interceptedBatches++; beforeBatch(); realBatchAttempts++
                    val result = real.applyBatch(CalendarContract.AUTHORITY, operations); afterBatch(); return batchResponse(result)
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                    if ((permission == Manifest.permission.READ_CALENDAR || permission == Manifest.permission.WRITE_CALENDAR) && !permissionGranted())
                        return PackageManager.PERMISSION_DENIED
                    return super.checkPermission(permission, pid, uid)
                }
            }
            return AndroidCalendarProjection(wrapped)
        }
        suspend fun close() {
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target); AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun assertBatchRejected(block: suspend () -> Any?) {
        try { block(); fail("The changed atomic batch must be refused") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
    }
    private fun malformedResponses(): List<(Array<ContentProviderResult>) -> Array<ContentProviderResult>> = listOf(
        { emptyArray() },
        { it.dropLast(1).toTypedArray() },
        { it.copyOf().apply { this[0] = ContentProviderResult(0) } },
        { it.copyOf().apply { this[0] = ContentProviderResult(Uri.parse("content://com.android.calendar/events/1")) } },
        { it.copyOf().apply { this[0] = ContentProviderResult(android.os.Bundle()) } },
        { it.copyOf().apply { this[0] = ContentProviderResult(android.os.RemoteException("Controlled result exception")) } },
    )
    private suspend fun assertUnavailable(block: suspend () -> Any?) {
        try { block(); fail("A missing or malformed response must not establish a witness") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, failure.category) }
    }
    private suspend fun assertIdentityRefused(block: suspend () -> Any?) {
        try { block(); fail("Captured identity must not enter generic projection") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
    }
}

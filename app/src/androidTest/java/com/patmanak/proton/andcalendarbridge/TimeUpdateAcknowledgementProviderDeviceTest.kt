// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.Manifest
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated local Provider fixtures only. No Proton session, service request or user-owned rows. */
class TimeUpdateAcknowledgementProviderDeviceTest {
    @Test fun exactOrdinaryTemporalEditRetainsDesiredTimeOnTheSameRow() = fixture {
        val before = values(); val adapter = intercepted(writes = 1)
        assertEquals(1, adapter.timeUpdateAcknowledgementProtocol)
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), adapter.acknowledgeTimeUpdate(receipt))
        assertEquals(1, batches); assertEquals(1, rows())
        assertEquals(before + expectedChanges(), values())
        val after = values()
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted().inspectTimeUpdateAcknowledgement(receipt))
        assertEquals(after, values())
    }
    @Test fun exclusiveAllDayDatesRemainAtDesiredValuesWhileTextIsCanonicalized() = fixture(allDay = true) {
        mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
        val before = values(); acknowledge()
        assertEquals(before + expectedChanges(), values())
    }
    @Test fun changedContentBeforeAcknowledgementIsPreserved() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Later title") }); val before = values()
        assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
    }
    @Test fun laterNativeStartOrEndEditIsPreservedBeforeAndDuringBatch() = fixture {
        for (column in listOf(Events.DTSTART, Events.DTEND)) {
            val old = field(column)!!
            mutate(ContentValues().apply { put(column, old.toLong() + 60_000) })
            val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, old.toLong()) })
            rejected { intercepted(writes = 1, before = {
                mutate(ContentValues().apply { put(column, old.toLong() + 60_000) })
            }).acknowledgeTimeUpdate(receipt) }
            assertEquals((old.toLong() + 60_000).toString(), field(column)); unchangedMarkers()
            mutate(ContentValues().apply { put(column, old.toLong()) })
        }
    }
    @Test fun mismatchedScopeAndGenerationNeverAcknowledgeTheExistingRow() = fixture {
        val before = values()
        for (otherScope in listOf(scope.copy(generation = scope.generation + 1),
            scope.copy(calendar = CalendarId("different-calendar")), scope.copy(account = AccountId("different-account")))) {
            val other = TimeUpdateAcknowledgementReceipt(receipt.attempt.copy(scope = otherScope),
                receipt.intent.copy(scope = otherScope), receipt.projection.copy(scope = otherScope))
            assertNull(projection.acknowledgeTimeUpdate(other))
            assertNull(projection.inspectTimeUpdateAcknowledgement(other))
            assertEquals(before, values())
        }
    }
    @Test fun tombstoneCannotBeAcknowledged() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
    }
    @Test fun everyUnsupportedChildBlocksAcknowledgement() = fixture {
        for (kind in 0..2) {
            val child = addChild(kind); val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
            removeChild(child)
        }
    }
    @Test fun wrongBaseRevisionAndAllOriginOrReservedMarkersAreRefused() = fixture {
        val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "unrelated") }); val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
    }
    @Test fun finiteUnknownAndMalformedPreviousReceiptMarkersAreRefused() = fixture {
        for (value in listOf("calendeer-finite-daily-dates-v1-receipt-v1:${UUID.randomUUID()}", "unknown", "calendeer-projection-receipt-v1:broken")) {
            mutate(ContentValues().apply { put(Events.SYNC_DATA3, value) }); val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
        }
    }
    @Test fun concurrentFullContentChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DESCRIPTION, "Concurrent detail") }) })
        rejected { adapter.acknowledgeTimeUpdate(receipt) }; assertEquals(1, batches)
        assertEquals("Concurrent detail", field(Events.DESCRIPTION)); unchangedMarkers()
    }
    @Test fun concurrentTombstoneAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DELETED, 1) }) })
        rejected { adapter.acknowledgeTimeUpdate(receipt) }; assertEquals("1", field(Events.DELETED)); unchangedMarkers()
    }
    @Test fun concurrentReceiptOrReservedMarkerChangeAbortsTheAtomicBatch() = fixture {
        for (column in listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val before = field(column)
            rejected { intercepted(writes = 1, before = { mutate(ContentValues().apply { put(column, "changed-marker") }) })
                .acknowledgeTimeUpdate(receipt) }
            assertEquals("1", field(Events.DIRTY)); assertEquals(digest("revision-v1", base.revision.value), field(Events.SYNC_DATA2))
            assertEquals("changed-marker", field(column)); mutate(ContentValues().apply { put(column, before) })
        }
    }
    @Test fun eachConcurrentChildAbortsTheAtomicBatch() = fixture {
        for (kind in 0..2) {
            var child: Uri? = null
            val adapter = intercepted(writes = 1, before = { child = addChild(kind) })
            rejected { adapter.acknowledgeTimeUpdate(receipt) }; unchangedMarkers()
            removeChild(checkNotNull(child))
        }
    }
    @Test fun concurrentRemoteAndSyncIdentityDuplicatesAbortTheAtomicBatch() = fixture {
        for (remote in listOf(true, false)) {
            var duplicateRow: Uri? = null
            val adapter = intercepted(writes = 1, before = { duplicateRow = duplicate(remote) })
            rejected { adapter.acknowledgeTimeUpdate(receipt) }; unchangedMarkers()
            context.contentResolver.delete(syncUri(checkNotNull(duplicateRow)), null, null)
        }
    }
    @Test fun concurrentCalendarOwnershipChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { context.contentResolver.update(syncUri(Calendars.CONTENT_URI),
            ContentValues().apply { put(Calendars.CAL_SYNC1, "foreign-owner") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) })
        try { rejected { adapter.acknowledgeTimeUpdate(receipt) }; unchangedMarkers() }
        finally { context.contentResolver.update(syncUri(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.CAL_SYNC1, "calendar-bridge-projection-v1") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) }
    }
    @Test fun historicalProofAfterLostReplyPreservesLaterEditDeletionAndChildren() = fixture {
        val adapter = intercepted(writes = 1, after = { throw RemoteException("synthetic lost reply") })
        unavailable { adapter.acknowledgeTimeUpdate(receipt) }; assertEquals(1, batches)
        mutate(ContentValues().apply { put(Events.TITLE, "Later local edit"); put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        addChild(0); val before = values()
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted().inspectTimeUpdateAcknowledgement(receipt))
        assertEquals(before, values()); assertNull(projection.acknowledgeTimeUpdate(receipt))
    }
    @Test fun interruptionBeforeExecutionCreatesNoHistoricalProof() = fixture {
        val before = values(); val adapter = intercepted(writes = 1, before = { throw RemoteException("synthetic interruption") })
        unavailable { adapter.acknowledgeTimeUpdate(receipt) }
        assertEquals(0, batches); assertNull(projection.inspectTimeUpdateAcknowledgement(receipt)); assertEquals(before, values())
    }
    @Test fun malformedBatchResponseNeverProducesAWitnessButHistoryRemainsRecoverable() = fixture {
        unavailable { intercepted(writes = 1, malformed = true).acknowledgeTimeUpdate(receipt) }
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), projection.inspectTimeUpdateAcknowledgement(receipt))
    }
    @Test fun historicalInspectionRefusesNonceRevisionOriginsAndReservedMetadataMismatch() = fixture {
        acknowledge(); val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "other") }); val before = values()
            assertNull(projection.inspectTimeUpdateAcknowledgement(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
        assertNull(projection.inspectTimeUpdateAcknowledgement(receipt.copy(projection = receipt.projection.copy(nonce = UUID.randomUUID().toString()))))
    }
    @Test fun historicalInspectionAtomicallyRejectsConcurrentOriginChange() = fixture {
        acknowledge()
        rejected { intercepted(before = { mutate(ContentValues().apply { put(Events.SYNC_DATA5, "foreign-origin") }) })
            .inspectTimeUpdateAcknowledgement(receipt) }
        assertEquals("foreign-origin", field(Events.SYNC_DATA5))
    }
    @Test fun modeAndPermissionGatesBlockBeforeQueries() = fixture {
        val before = values(); val closed = intercepted(writes = 1); closed.admitOperation = { false }
        try { closed.acknowledgeTimeUpdate(receipt); fail("Inactive mode must cancel") } catch (_: CancellationException) { }
        try { intercepted(writes = 1, denied = true).acknowledgeTimeUpdate(receipt); fail("Permission gate must refuse") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, queries); assertEquals(0, batches); assertEquals(before, values())
    }
    @Test fun modeLossAfterCommitDoesNotReturnAWitnessAndHistoryCanRecover() = fixture {
        var admitted = true
        val adapter = intercepted(writes = 1, after = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.acknowledgeTimeUpdate(receipt); fail("Lost mode must cancel witness return") } catch (_: CancellationException) { }
        assertEquals(1, batches)
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), projection.inspectTimeUpdateAcknowledgement(receipt))
    }


    @Test fun identicalVerifiedRawTextWritesOnlyMetadata() = fixture(canonicalMask = 0) {
        val before = values()
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted(writes = 1).acknowledgeTimeUpdate(receipt))
        assertEquals(before + expectedChanges(), values())
    }
    @Test fun eachChangedTextColumnIsIndependentlyConditional() {
        for (mask in listOf(1, 2, 4)) fixture(canonicalMask = mask) {
            val before = values()
            assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted(writes = 1).acknowledgeTimeUpdate(receipt))
            assertEquals(before + expectedChanges(), values())
        }
    }
    @Test fun canonicallyEquivalentButDifferentNativeRawTextIsNotAcknowledged() = fixture {
        for (column in listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION)) {
            val original = field(column)!!
            mutate(ContentValues().apply { put(column, original.replace("\r\n", "\n")) })
            val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original) })
        }
    }
    @Test fun concurrentCanonicalEquivalentRawChangeStillAborts() = fixture {
        rejected { intercepted(writes = 1, before = {
            mutate(ContentValues().apply { put(Events.DESCRIPTION, observed.content.description) })
        }).acknowledgeTimeUpdate(receipt) }
        unchangedMarkers(); assertEquals(observed.content.description, field(Events.DESCRIPTION))
    }
    @Test fun changedTimeZonePrecisionAndFlagsCannotBeAcknowledged() = fixture {
        val original = values()
        for ((column, changed) in mapOf(Events.DTSTART to (field(Events.DTSTART)!!.toLong() + 1).toString(),
            Events.EVENT_TIMEZONE to "UTC", Events.EVENT_END_TIMEZONE to "UTC",
            Events.ALL_DAY to "1", Events.EVENT_COLOR to "123456", Events.STATUS to Events.STATUS_TENTATIVE.toString())) {
            mutate(ContentValues().apply { put(column, changed) }); val before = values()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
    }
    @Test fun closingLeaseDuringReadCancelsBeforeBatch() = fixture {
        var admitted = true
        val adapter = intercepted(writes = 1, afterQuery = { admitted = false })
        adapter.admitOperation = { admitted }
        val before = values()
        try { adapter.acknowledgeTimeUpdate(receipt); fail("Expired admission must cancel") }
        catch (_: CancellationException) { }
        assertEquals(0, batches); assertEquals(before, values())
    }
    @Test fun cancellationInsideLeaseCallbackDominatesReturnAndOrdinaryFailure() = fixture {
        for (throwsOrdinary in listOf(false, true)) supervisorScope {
            var cancelled = 0; var other = 0; var returned = 0
            val before = values()
            val child = async {
                val caller = currentCoroutineContext().job
                val adapter = intercepted(writes = 1)
                adapter.admitOperation = {
                    caller.cancel()
                    if (throwsOrdinary) throw java.io.IOException("Synthetic cancelled lease")
                    true
                }
                try { adapter.acknowledgeTimeUpdate(receipt); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { other++ }
            }
            try { child.await() } catch (_: CancellationException) { }
            assertEquals(1, cancelled); assertEquals(0, other); assertEquals(0, returned)
            assertEquals(0, queries); assertEquals(0, batches); assertEquals(before, values())
        }
    }
    @Test fun transferredTimedAndAllDayAcknowledgeSameRowWithCompleteOrigin() {
        for (allDay in listOf(false, true)) fixture(allDay = allDay, transferred = true) {
            val before = values()
            assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted(writes = 1).acknowledgeTimeUpdate(receipt))
            assertEquals(1, rows()); assertEquals(before + expectedChanges(), values())
            val next = CapturedCreateHandoffFrame(frame.receipt, observed, receipt.projection)
            assertEquals(CapturedHandoffCleanWitness(next), projection.inspectCapturedHandoffBase(next))
            assertEquals(AppliedTimeUpdateAcknowledgement(receipt), AndroidCalendarProjection(context).inspectTimeUpdateAcknowledgement(receipt))
        }
    }
    @Test fun advancedTransferredFramePreservesOriginalCreationAndAllDayDates() = fixture(allDay = true, transferred = true, advanced = true) {
        val before = values()
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), intercepted(writes = 1).acknowledgeTimeUpdate(receipt))
        assertEquals(before + expectedChanges(), values())
        assertEquals(CapturedHandoffCleanWitness(CapturedCreateHandoffFrame(frame.receipt, observed, receipt.projection)),
            projection.inspectCapturedHandoffBase(CapturedCreateHandoffFrame(frame.receipt, observed, receipt.projection)))
    }
    @Test fun transferCannotFallBackToOrdinaryOrDifferentOrigin() = fixture(transferred = true) {
        val before = values()
        val noOrigin = receipt.copy(handoff = null)
        val different = frame.copy(receipt = frame.receipt.copy(acknowledgement = createAck.copy(nonce = UUID.randomUUID().toString())))
        assertNull(projection.acknowledgeTimeUpdate(noOrigin))
        assertNull(projection.acknowledgeTimeUpdate(receipt.copy(handoff = different)))
        assertEquals(before, values()); acknowledge()
        assertNull(projection.inspectTimeUpdateAcknowledgement(noOrigin))
        assertNull(projection.inspectTimeUpdateAcknowledgement(receipt.copy(handoff = different)))
    }
    @Test fun transferredConcurrentRawEditOrChildAbortsWithoutClearingDirty() = fixture(transferred = true) {
        rejected { intercepted(writes = 1, before = {
            mutate(ContentValues().apply { put(Events.EVENT_LOCATION, "Later local place") })
        }).acknowledgeTimeUpdate(receipt) }
        unchangedMarkers()
        mutate(ContentValues().apply { put(Events.EVENT_LOCATION, intent.content.location) })
        var child: Uri? = null
        rejected { intercepted(writes = 1, before = { child = addChild(0) }).acknowledgeTimeUpdate(receipt) }
        unchangedMarkers(); removeChild(checkNotNull(child))
    }
    @Test fun transferredLostReplyReopenPreservesFutureDirtyContentAndChildren() = fixture(transferred = true, advanced = true) {
        unavailable { intercepted(writes = 1, after = { throw RemoteException("Synthetic lost time ACK reply") }).acknowledgeTimeUpdate(receipt) }
        mutate(ContentValues().apply { put(Events.DESCRIPTION, "Future local details"); put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        addChild(1); val before = values()
        assertEquals(AppliedTimeUpdateAcknowledgement(receipt), AndroidCalendarProjection(context).inspectTimeUpdateAcknowledgement(receipt))
        assertEquals(before, values())
        assertNull(projection.acknowledgeTimeUpdate(receipt))
    }

    private class Fixture(val context: Context, allDay: Boolean, val transferred: Boolean, advanced: Boolean, val canonicalMask: Int) {
        val scope = CalendarScope(AccountId("time-ack-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val format = AdapterFormat("synthetic-time-ack-import", 1)
        val content = EventContent("Original\r\nsynthetic title", if (allDay) EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1))
            else EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            description = "Original\r\nsynthetic detail", location = "Original\r\nsynthetic place", details = EventDetails())
        val localId = LocalEventId(UUID.randomUUID().toString())
        private val captured = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"), localId, scope,
            format, Instant.parse("2030-10-01T12:00:00Z")), content)
        private val stage = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
            MutationId(UUID.randomUUID().toString()), AdapterFormat("synthetic-create", 2)), "opaque-create")
        val attachment = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(stage),
            EventId("synthetic-event"), Revision("synthetic-created")), UUID.randomUUID().toString())
        val baseline = CapturedCreateBaseline(attachment, RemoteChange.Upsert(attachment.binding.eventId, attachment.binding.verifiedRevision, content))
        val createAck = CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())
        val initialFrame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(createAck, UUID.randomUUID().toString()), baseline.change)
        val incoming = if (advanced) CapturedHandoffProjection(initialFrame, ProjectionReceipt(scope, format,
            baseline.change.copy(revision = Revision("synthetic-incoming"), content = content.copy(title = "Intermediate\r\nimported title")),
            UUID.randomUUID().toString(), baseline.change)) else null
        val frame = incoming?.let { CapturedCreateHandoffFrame(initialFrame.receipt, it.receipt.change, it.receipt) } ?: initialFrame
        val base = if (transferred) frame.base else RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-old"), content)
        val desiredTime = when (val old = base.content.time) {
            is EventTime.Timed -> old.copy(start = old.start.plusSeconds(3600), end = old.end.plusSeconds(7200))
            is EventTime.AllDay -> old.copy(start = old.start.plusDays(1), endExclusive = old.endExclusive.plusDays(2))
        }
        val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, format, base, base.content.copy(time = desiredTime))
        val record = TimeUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalTimeEditPreparation(intent,
            Instant.parse("2030-10-01T12:00:00Z"), AdapterFormat("synthetic-preparation", 1), "opaque-preparation"),
            AdapterFormat("synthetic-submission", 1), "opaque-submission", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        val observed = base.copy(revision = Revision("synthetic-new"), content = intent.content.copy(
            title = if (canonicalMask and 1 != 0) intent.content.title.replace("\r\n", "\n") else intent.content.title,
            description = if (canonicalMask and 2 != 0) intent.content.description.replace("\r\n", "\n") else intent.content.description,
            location = if (canonicalMask and 4 != 0) intent.content.location.replace("\r\n", "\n") else intent.content.location))
        val receipt = TimeUpdateAcknowledgementReceipt.of(TimeUpdateVerifiedResult.of(record,
            TimeUpdateSubmissionObservation.Verified(record, observed)), UUID.randomUUID().toString(), if (transferred) frame else null)
        val marker = "calendeer-projection-receipt-v1:${receipt.projection.nonce}"
        val oldMarker = if (transferred) frame.lastProjection?.let { "calendeer-projection-receipt-v1:${it.nonce}" }
            else "calendeer-projection-receipt-v1:${UUID.randomUUID()}"
        val newRevision = digest("revision-v1", observed.revision.value)
        var calendar = -1L; var row = -1L; var queries = 0; var batches = 0
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri = projection.accountFor(scope.account).let { account -> uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build() }
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
            calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic time ACK fixture"), true).id
            if (transferred) {
                row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
                assertEquals(content, projection.captureAndStampSimpleCreate(scope, row, localId)!!.content)
                assertEquals(AppliedCapturedCreateAttachment(attachment), projection.attachCapturedCreate(attachment))
                assertEquals(AppliedCapturedCreateAcknowledgement(createAck), projection.acknowledgeCapturedCreate(createAck))
                if (incoming != null) assertEquals(AppliedCapturedHandoffProjection(incoming), projection.applyCapturedHandoffProjection(incoming))
                assertEquals(CapturedHandoffCleanWitness(frame), projection.inspectCapturedHandoffBase(frame))
            } else {
                projection.apply(ProjectionIntent(scope, base))
                row = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                    check(it.count == 1 && it.moveToFirst()); it.getLong(0)
                }
            }
            mutate(ContentValues().apply {
                put(Events.TITLE, intent.content.title); put(Events.DESCRIPTION, intent.content.description)
                put(Events.EVENT_LOCATION, intent.content.location); put(Events.DIRTY, 1); put(Events.SYNC_DATA3, oldMarker)
                when (val time = desiredTime) {
                    is EventTime.Timed -> { put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli()) }
                    is EventTime.AllDay -> {
                        put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                        put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    }
                }
            })
        }
        fun expectedChanges(): Map<String, String?> = buildMap {
            put(Events.DIRTY, "0"); put(Events.SYNC_DATA2, newRevision); put(Events.SYNC_DATA3, marker)
            if (observed.content.title != intent.content.title) put(Events.TITLE, observed.content.title)
            if (observed.content.description != intent.content.description) put(Events.DESCRIPTION, observed.content.description)
            if (observed.content.location != intent.content.location) put(Events.EVENT_LOCATION, observed.content.location)
        }

        suspend fun acknowledge() { assertEquals(AppliedTimeUpdateAcknowledgement(receipt), projection.acknowledgeTimeUpdate(receipt)) }
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun unchangedMarkers() { assertEquals("1", field(Events.DIRTY)); assertEquals(digest("revision-v1", base.revision.value), field(Events.SYNC_DATA2)); assertEquals(oldMarker, field(Events.SYNC_DATA3)) }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            check(it.count == 1 && it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun values(): Map<String, String?> = context.contentResolver.query(uri, null, "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            check(it.count == 1 && it.moveToFirst()); it.columnNames.indices.associate { index -> it.getColumnName(index) to if (it.isNull(index)) null else it.getString(index) }
        }
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }
        fun addChild(kind: Int): Uri = when (kind) {
            0 -> context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply { put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT) })!!
            1 -> context.contentResolver.insert(syncUri(Attendees.CONTENT_URI), ContentValues().apply { put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid"); put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED); put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE) })!!
            else -> context.contentResolver.insert(syncUri(ExtendedProperties.CONTENT_URI), ContentValues().apply { put(ExtendedProperties.EVENT_ID, row); put(ExtendedProperties.NAME, "synthetic"); put(ExtendedProperties.VALUE, "fixture") })!!
        }
        fun removeChild(child: Uri) {
            context.contentResolver.delete(syncUri(child), null, null)
            mutate(ContentValues().apply { put(Events.HAS_ALARM, 0); put(Events.HAS_ATTENDEE_DATA, 0) })
        }
        fun duplicate(remote: Boolean): Uri {
            val copy = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DTSTART, field(Events.DTSTART)!!.toLong())
                put(Events.DTEND, field(Events.DTEND)!!.toLong()); put(Events.ALL_DAY, field(Events.ALL_DAY)!!.toInt()); put(Events.EVENT_TIMEZONE, field(Events.EVENT_TIMEZONE))
                put(Events.SYNC_DATA1, field(Events.SYNC_DATA1)); put(Events._SYNC_ID, if (remote) "other-sync" else field(Events._SYNC_ID))
                put(Events.SYNC_DATA4, if (remote) field(Events.SYNC_DATA4) else "other-remote")
            }
            return context.contentResolver.insert(uri, copy)!!
        }
        fun intercepted(writes: Int = 0, malformed: Boolean = false, denied: Boolean = false,
            after: () -> Unit = {}, before: () -> Unit = {}, afterQuery: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("ACK must not insert")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("ACK must not delete")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Atomic batch required")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    queries++; return real.query(uri, columns, selection, args, order).also { afterQuery() }
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertEquals(writes, operations.count { it.isUpdate }); assertTrue(operations.all { it.isAssertQuery || it.isUpdate })
                    if (writes == 1) {
                        assertTrue(operations.last().isUpdate)
                        val values = operations.last().resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0)!!
                        assertEquals(expectedChanges().keys, values.keySet())
                        for ((column, expected) in expectedChanges()) assertEquals(expected, values.getAsString(column))
                        assertEquals(0, values.getAsInteger(Events.DIRTY)); assertEquals(newRevision, values.getAsString(Events.SYNC_DATA2)); assertEquals(marker, values.getAsString(Events.SYNC_DATA3))
                        val snapshots = operations.filter { it.isAssertQuery }.mapNotNull { it.resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0) }
                        val snapshot = snapshots.single { it.containsKey(Events.TITLE) }
                        assertTrue(snapshot.keySet().containsAll(listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
                            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)))
                        assertEquals(oldMarker, snapshot.getAsString(Events.SYNC_DATA3))
                        assertEquals(intent.content.title, snapshot.getAsString(Events.TITLE))
                        assertEquals(intent.content.description, snapshot.getAsString(Events.DESCRIPTION))
                        assertEquals(intent.content.location, snapshot.getAsString(Events.EVENT_LOCATION))
                        assertTrue(snapshot.keySet().containsAll(listOf(Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE,
                            Events.EVENT_END_TIMEZONE, Events.ALL_DAY, Events.DIRTY, Events.DELETED)))
                        for (column in listOf(Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE,
                            Events.EVENT_END_TIMEZONE, Events.ALL_DAY)) {
                            assertEquals(field(column), snapshot.getAsString(column))
                            assertFalse("Temporal ACK must retain editor dates", values.containsKey(column))
                        }
                        if (transferred) for (column in listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)) {
                            assertFalse(field(column).isNullOrBlank()); assertEquals(field(column), snapshot.getAsString(column))
                        }
                    }
                    before(); batches++; val results = real.applyBatch(CalendarContract.AUTHORITY, operations); after()
                    return if (malformed) emptyArray() else results
                }
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = ContentResolver.wrap(provider)
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (denied && permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
            }
            return AndroidCalendarProjection(wrapped)
        }
        suspend fun close() {
            projection.admitOperation = { true }; projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }
    private fun fixture(allDay: Boolean = false, transferred: Boolean = false, advanced: Boolean = false,
        canonicalMask: Int = 7, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay, transferred, advanced, canonicalMask); assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun rejected(block: suspend () -> Any?) {
        try { block(); fail("Atomic assertion must reject the changed snapshot") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
    }
    private suspend fun unavailable(block: suspend () -> Any?) {
        try { block(); fail("Missing valid batch reply cannot establish a witness") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, failure.category) }
    }
    private companion object {
        fun digest(vararg parts: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            parts.forEach { val bytes = it.toByteArray(); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

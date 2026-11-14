// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
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

/** Actual isolated Provider rows; acknowledgement is local and transport-free.
 * Assertion batches are counted after setup succeeds, preventing false race qualification.
 */
@RunWith(AndroidJUnit4::class)
class CapturedCreateAcknowledgementProviderDeviceTest {
    @Test fun dailySeriesCurrentAndAckPreserveTheCompleteTimedAndAllDayRows() {
        for (allDay in listOf(false, true)) for (rich in listOf(false, true)) fixture(allDay, series = true, rich = rich) {
            val before = values(); assertTrue(supported().unchanged)
            acknowledged()
            assertEquals(before + (Events.DIRTY to "0") + (Events.SYNC_DATA7 to marker), values())
            assertEquals(CapturedCreateCleanWitness(ack), projection.inspectAcknowledgedCreateClean(ack))
            assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
            assertNull(projection.acknowledgeCapturedCreate(ack))
        }
    }

    @Test fun changedSeriesRuleDurationAndTombstoneRemainDirty() {
        for (kind in 0..2) fixture(series = true) {
            assertTrue(supported().unchanged)
            mutate(ContentValues().apply { when (kind) {
                0 -> put(Events.RRULE, "FREQ=DAILY;COUNT=4")
                1 -> put(Events.DURATION, "PT7200S")
                else -> put(Events.DELETED, 1)
            } })
            val before = values(); assertFalse(supported().unchanged)
            assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
        }
    }

    @Test fun unsupportedSeriesEditsNeverLoseTheirRuleOrExclusions() {
        for ((column, value) in listOf(Events.RRULE to "FREQ=WEEKLY;COUNT=5", Events.EXDATE to "20300330T110000Z")) fixture(series = true) {
            mutate(ContentValues().apply { put(column, value) }); val before = values()
            assertEquals(CapturedCreateCurrentObservation.Unsupported, capture())
            assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
        }
    }

    @Test fun detachedSeriesChildrenBlockCurrentAndAckByLocalOrSyncParentIdentity() {
        for (bySyncId in listOf(false, true)) fixture(series = true) {
            exception(bySyncId); val before = values()
            assertEquals(CapturedCreateCurrentObservation.Unsupported, capture())
            assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values()); assertEquals(2, rows())
        }
    }

    @Test fun concurrentSeriesExceptionAbortsTheWholeAcknowledgementBatch() = fixture(series = true) {
        val adapter = intercepted(writes = 1) { exception(false) }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7)); assertEquals(2, rows())
    }

    @Test fun concurrentSeriesRuleDurationAndReminderChangesCannotBeCleaned() {
        for (kind in 0..2) fixture(series = true) {
            val adapter = intercepted(writes = 1) { when (kind) {
                0 -> mutate(ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=4") })
                1 -> mutate(ContentValues().apply { put(Events.DURATION, "PT7200S") })
                else -> reminder()
            } }
            assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
            assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
        }
    }

    @Test fun seriesLostAckResponseOnlyInspectsHistoryAndPreservesLaterEdits() = fixture(series = true, rich = true) {
        val adapter = intercepted(writes = 1, afterBatch = { throw IllegalStateException("Synthetic response lost") })
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Later series title") })
        val before = values(); val reopened = AndroidCalendarProjection(context)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), reopened.inspectCapturedAcknowledgement(ack))
        val current = (reopened.captureAcknowledgedCreateCurrent(ack) as CapturedCreateCurrentObservation.Supported).snapshot
        assertFalse(current.unchanged); assertEquals(content.copy(title = "Later series title"), current.content)
        assertNull(reopened.acknowledgeCapturedCreate(ack)); assertNull(reopened.inspectAcknowledgedCreateClean(ack))
        assertEquals(before, values())
        exception(false)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), reopened.inspectCapturedAcknowledgement(ack))
        assertEquals(CapturedCreateCurrentObservation.Unsupported, reopened.captureAcknowledgedCreateCurrent(ack))
    }

    @Test fun seriesNativeOwnerPlaceholderIsNotARemoteParticipant() = fixture(series = true, rich = true) {
        mutate(ContentValues().apply {
            put(Events.ORGANIZER, projection.accountFor(target.account).name); put(Events.IS_ORGANIZER, 1)
        })
        val before = values(); acknowledged()
        assertEquals(before + (Events.DIRTY to "0") + (Events.SYNC_DATA7 to marker), values())
    }

    @Test fun seriesLeaseRevocationBeforeAndAfterBatchPreservesItsOutcome() = fixture(series = true) {
        val blocked = intercepted(writes = 1); blocked.admitOperation = { false }
        try { blocked.acknowledgeCapturedCreate(ack); fail("Inactive series lease must cancel") } catch (_: CancellationException) { }
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts)
        var active = true
        val lost = intercepted(writes = 1, afterBatch = { active = false }); lost.admitOperation = { active }
        try { lost.acknowledgeCapturedCreate(ack); fail("Revoked lease must cancel") } catch (_: CancellationException) { }
        assertEquals(1, realBatchAttempts)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
    }

    @Test fun seriesPermissionLossBeforeAndAfterBatchNeverRearmsTheWrite() = fixture(series = true) {
        var allowed = false
        val blocked = intercepted(writes = 1, permissions = { allowed })
        try { blocked.acknowledgeCapturedCreate(ack); fail("Calendar permission required") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts)
        allowed = true
        val lost = intercepted(writes = 1, permissions = { allowed }, afterBatch = { allowed = false })
        try { lost.acknowledgeCapturedCreate(ack); fail("Permission loss must be reported") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(1, realBatchAttempts)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        assertNull(projection.acknowledgeCapturedCreate(ack))
    }

    @Test fun seriesForeignGenerationAndSubstitutedAttachmentCannotBeAdopted() = fixture(series = true) {
        val identity = receipt.binding.identity
        val foreign = baseline.copy(receipt = receipt.copy(binding = receipt.binding.copy(identity = identity.copy(
            attempt = identity.attempt.copy(scope = target.copy(generation = 1))))))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, projection.captureCapturedCreateCurrent(foreign))
        assertNull(projection.acknowledgeCapturedCreate(ack.copy(baseline = foreign)))
        val original = field(Events.SYNC_DATA6)
        mutate(ContentValues().apply { put(Events.SYNC_DATA6, "synthetic-foreign-attachment") }); val before = values()
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
        mutate(ContentValues().apply { put(Events.SYNC_DATA6, original) })
    }

    @Test fun exactUnchangedOriginalUpdatesOnlyDirtyAndAcknowledgementInOneBatch() = fixture {
        val before = values(); val adapter = intercepted(writes = 1)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), adapter.acknowledgeCapturedCreate(ack))
        assertEquals(1, realBatchAttempts)
        assertEquals(before + (Events.DIRTY to "0") + (Events.SYNC_DATA7 to marker), values())
        val after = values(); assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        assertEquals(after, values())
    }

    @Test fun allDayDatesAreAcknowledgedWithoutAnyContentRewrite() = fixture(allDay = true) {
        val before = values(); acknowledged()
        assertEquals(before + (Events.DIRTY to "0") + (Events.SYNC_DATA7 to marker), values())
    }

    @Test fun earlierUnchangedSnapshotNeverAuthorizesAcknowledgingALaterEdit() = fixture {
        assertTrue((projection.captureCapturedCreateCurrent(baseline) as CapturedCreateCurrentObservation.Supported).snapshot.unchanged)
        mutate(ContentValues().apply { put(Events.TITLE, "New edit before acknowledgement") }); val before = values()
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
    }

    @Test fun presentTombstoneIsNeverAcknowledgedAsTheSentCreate() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
    }

    @Test fun reminderChildrenCannotBeConsumedByAcknowledgement() = fixture {
        reminder(); val before = values(); assertNull(projection.acknowledgeCapturedCreate(ack))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun unsupportedColorAndPrecisionRemainDirtyAndUnmarked() = fixture {
        mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0xFFAA1122.toInt()) }); var before = values()
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
        mutate(ContentValues().apply { putNull(Events.EVENT_COLOR); put(Events.DTSTART, (content.time as EventTime.Timed).start.toEpochMilli() + 1) })
        before = values(); assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
    }

    @Test fun cleanOriginalCannotBeClaimedAsANewAtomicAcknowledgement() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 0) }); val before = values()
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
        assertEquals(before, values())
    }

    @Test fun metadataSubstitutionIsNotRepairedOrAcknowledged() = fixture {
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA3, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val previous = field(column); mutate(ContentValues().apply { put(column, "foreign-proof") }); val before = values()
            assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
            assertEquals(before, values()); mutate(ContentValues().apply { if (previous == null) putNull(column) else put(column, previous) })
        }
    }

    @Test fun duplicateLocalMarkerPreventsAcknowledgementAndHistoryAdoption() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        val before = values(); assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
        assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun duplicateRemoteIdentityPreventsAcknowledgementAndHistoryAdoption() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events._SYNC_ID, field(Events._SYNC_ID)) })!!
        val before = values(); assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
        assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun foreignCalendarOwnershipCannotAuthorizeAcknowledgement() = fixture {
        val calendars = syncUri(CalendarContract.Calendars.CONTENT_URI)
        val selection = "${CalendarContract.Calendars._ID}=?"
        val args = arrayOf(calendar.toString())
        val owner = context.contentResolver.query(calendars, arrayOf(CalendarContract.Calendars.CAL_SYNC1), selection, args, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val before = values()
        try {
            assertEquals(1, context.contentResolver.update(calendars,
                ContentValues().apply { put(CalendarContract.Calendars.CAL_SYNC1, "foreign-owner") }, selection, args))
            assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
            assertEquals(before, values())
        } finally {
            assertEquals(1, context.contentResolver.update(calendars,
                ContentValues().apply { put(CalendarContract.Calendars.CAL_SYNC1, owner) }, selection, args))
        }
    }

    @Test fun purgedOriginalNeverFallsBackToAnUnmarkedReplacement() = fixture {
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertNull(projection.inspectCapturedAcknowledgement(ack))
        val replacement = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(1, rows())
        context.contentResolver.query(uri, arrayOf(Events.SYNC_DATA7, Events.DIRTY), "${Events._ID}=?", arrayOf(replacement.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(1, it.getInt(1))
        }
    }

    @Test fun lostResponseRetainsTheExactHistoricalProofWithoutASecondWrite() = fixture {
        val adapter = intercepted(writes = 1, afterBatch = { throw IllegalStateException("Synthetic response lost") })
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("0", field(Events.DIRTY)); assertEquals(marker, field(Events.SYNC_DATA7))
        val before = values(); val reopened = AndroidCalendarProjection(context)
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), reopened.inspectCapturedAcknowledgement(ack))
        assertNull(reopened.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Edit after lost acknowledgement response") })
        val edited = values(); assertEquals(AppliedCapturedCreateAcknowledgement(ack), reopened.inspectCapturedAcknowledgement(ack))
        val captured = (reopened.captureAcknowledgedCreateCurrent(ack) as CapturedCreateCurrentObservation.Supported).snapshot
        assertEquals(ack, captured.acknowledgement)
        assertEquals(content.copy(title = "Edit after lost acknowledgement response"), captured.content)
        assertEquals(edited, values())
    }

    @Test fun historyAfterAnotherEditDoesNotCleanItAndSupportsNewCapture() = fixture {
        acknowledged(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Later acknowledged edit") })
        val before = values(); assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        val current = (projection.captureAcknowledgedCreateCurrent(ack) as CapturedCreateCurrentObservation.Supported).snapshot
        assertEquals(ack, current.acknowledgement)
        assertEquals(content.copy(title = "Later acknowledged edit"), current.content); assertFalse(current.unchanged)
        assertNull(projection.acknowledgeCapturedCreate(ack)); assertEquals(before, values())
    }

    @Test fun historyAfterLocalTombstoneDoesNotConsumeDeletion() = fixture {
        acknowledged(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        val before = values(); assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        val current = (projection.captureAcknowledgedCreateCurrent(ack) as CapturedCreateCurrentObservation.Supported).snapshot
        assertEquals(ack, current.acknowledgement); assertTrue(current.deleted)
        assertEquals(before, values())
    }

    @Test fun historyAfterReminderStillExistsWhileNewContentRemainsUnsupported() = fixture {
        acknowledged(); reminder(); mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        val before = values(); assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        assertEquals(CapturedCreateCurrentObservation.Unsupported, projection.captureAcknowledgedCreateCurrent(ack))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun historicalProofDoesNotClaimTheCurrentCleanStateIsDirtyCapture() = fixture {
        acknowledged(); val before = values()
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, projection.captureAcknowledgedCreateCurrent(ack)); assertEquals(before, values())
    }

    @Test fun anotherNonceCannotAdoptHistoricalAcknowledgementOrCurrentContent() = fixture {
        acknowledged(); mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        val other = ack.copy(nonce = UUID.randomUUID().toString()); val before = values()
        assertNull(projection.inspectCapturedAcknowledgement(other))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, projection.captureAcknowledgedCreateCurrent(other)); assertEquals(before, values())
    }

    @Test fun aDifferentFullBindingCannotAdoptTheHistoricalProof() = fixture {
        acknowledged()
        val different = receipt.copy(binding = receipt.binding.copy(identity = receipt.binding.identity.copy(captureId = MutationId("other-capture"))))
        val other = ack.copy(baseline = baseline.copy(receipt = different)); val before = values()
        assertNull(projection.inspectCapturedAcknowledgement(other))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, projection.captureAcknowledgedCreateCurrent(other)); assertEquals(before, values())
    }

    @Test fun malformedOpaqueIdentityCannotReachProviderQueries() = fixture {
        val adapter = intercepted()
        try {
            val malformed = baseline.copy(receipt = receipt.copy(binding = receipt.binding.copy(verifiedRevision = Revision("\uD800"))),
                change = baseline.change.copy(revision = Revision("\uD800")))
            adapter.inspectCapturedAcknowledgement(ack.copy(baseline = malformed)); fail("Malformed receipt must be rejected")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts)
    }

    @Test fun originalCurrentPortStillRefusesAcknowledgementMetadata() = fixture {
        val original = (projection.captureCapturedCreateCurrent(baseline) as CapturedCreateCurrentObservation.Supported).snapshot
        assertNull(original.acknowledgement)
        acknowledged(); mutate(ContentValues().apply { put(Events.DIRTY, 1) }); val before = values()
        assertEquals(CapturedCreateCurrentObservation.Unresolved, projection.captureCapturedCreateCurrent(baseline))
        val current = (projection.captureAcknowledgedCreateCurrent(ack) as CapturedCreateCurrentObservation.Supported).snapshot
        assertEquals(ack, current.acknowledgement); assertTrue(current.unchanged)
        assertEquals(before, values())
    }

    @Test fun concurrentContentEditAbortsBeforeAnyAcknowledgementCanCommit() = fixture {
        val adapter = intercepted(writes = 1) { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent acknowledged title") }) }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("Concurrent acknowledged title", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun concurrentTombstoneAbortsAndPreservesTheDeletion() = fixture {
        val adapter = intercepted(writes = 1) { mutate(ContentValues().apply { put(Events.DELETED, 1) }) }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("1", field(Events.DELETED)); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun concurrentReminderAbortsAndPreservesTheChild() = fixture {
        val adapter = intercepted(writes = 1) { reminder() }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID)); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun concurrentLocalDuplicateAbortsAllAcknowledgementWrites() = fixture {
        val adapter = intercepted(writes = 1) { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!! }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals(2, rows()); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun concurrentRemoteDuplicateAbortsAllAcknowledgementWrites() = fixture {
        val adapter = intercepted(writes = 1) { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!! }
        assertRejected { adapter.acknowledgeCapturedCreate(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals(2, rows()); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun historicalInspectionAtomicallyRefusesAConcurrentMetadataChange() = fixture {
        acknowledged(); val adapter = intercepted { mutate(ContentValues().apply { put(Events.SYNC_DATA7, "damaged-history") }) }
        assertRejected { adapter.inspectCapturedAcknowledgement(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("damaged-history", field(Events.SYNC_DATA7)); assertEquals("0", field(Events.DIRTY))
    }

    @Test fun inactiveLeaseCancelsAcknowledgementBeforeAnyProviderQuery() = fixture {
        val before = values(); val adapter = intercepted(writes = 1); adapter.admitOperation = { false }
        try { adapter.acknowledgeCapturedCreate(ack); fail("Inactive lease must cancel") } catch (_: CancellationException) {}
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun accessLossAfterCommittedAcknowledgementLeavesRecoverableProof() = fixture {
        var admitted = true; val adapter = intercepted(writes = 1, afterBatch = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.acknowledgeCapturedCreate(ack); fail("Lost lease must cancel") } catch (_: CancellationException) {}
        assertEquals(1, realBatchAttempts); assertEquals("0", field(Events.DIRTY)); assertEquals(marker, field(Events.SYNC_DATA7))
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
    }

    private class Fixture(val context: Context, val allDay: Boolean, val series: Boolean, val rich: Boolean) {
        val target = CalendarScope(AccountId("captured-ack-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val projection = AndroidCalendarProjection(context)
        val content = EventContent("Captured acknowledgement provider fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            if (series) EventTime.Timed(Instant.parse("2030-03-29T11:00:00Z"), Instant.parse("2030-03-29T12:00:00Z"), ZoneId.of("Europe/Paris")) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails(if (rich) listOf(EventReminder(10)) else emptyList()), colorRgb = if (rich) 0x123456 else null,
            recurrence = if (series) EventRecurrence("synthetic@calendeer.invalid", "FREQ=DAILY;COUNT=5") else null)
        private val captured = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), localId, target,
            AdapterFormat("ack-provider-import", 1), Instant.parse("2030-10-01T12:00:00Z")), content.copy(recurrence = null),
            if (series) CreateSeriesRule(5) else null)
        private val record = CreateAttemptRecord(MutationId(UUID.randomUUID().toString()), target,
            AdapterFormat("ack-provider-submission", if (series) 4 else 2), "opaque-fixture")
        val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(record, captured),
            EventId("fixture-remote"), Revision("fixture-revision")), UUID.randomUUID().toString())
        val baseline = CapturedCreateBaseline(receipt, RemoteChange.Upsert(receipt.binding.eventId, receipt.binding.verifiedRevision, content))
        val ack = CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())
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
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, if (rich) 1 else 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            if (rich) put(Events.EVENT_COLOR, 0xff123456.toInt()) else putNull(Events.EVENT_COLOR)
            putNull(Events.EVENT_COLOR_KEY)
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
            if (series) {
                put(Events.RRULE, content.recurrence!!.rule); putNull(Events.DTEND)
                put(Events.DURATION, if (allDay) "P2D" else "PT3600S")
            }
        }
        suspend fun seed() {
            calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer captured current provider fixture"))
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            if (rich) reminder()
            val observed = if (series) projection.captureAndStampSeriesCreate(target, row, localId)
                else projection.captureAndStampSimpleCreate(target, row, localId)
            assertEquals(content.copy(recurrence = null), observed!!.content)
            assertEquals(captured.series, observed.series)
            assertEquals(AppliedCapturedCreateAttachment(receipt), projection.attachCapturedCreate(receipt))
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
        fun exception(bySyncId: Boolean) {
            context.contentResolver.insert(uri, candidateValues().apply {
                put(Events.TITLE, "Synthetic detached series exception"); putNull(Events.RRULE); putNull(Events.DURATION)
                val start = getAsLong(Events.DTSTART)
                put(Events.DTEND, start + if (allDay) 172800000L else 3600000L)
                if (bySyncId) put(Events.ORIGINAL_SYNC_ID, field(Events._SYNC_ID)) else put(Events.ORIGINAL_ID, row)
                put(Events.ORIGINAL_INSTANCE_TIME, start); put(Events.ORIGINAL_ALL_DAY, if (allDay) 1 else 0)
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
        fun intercepted(writes: Int = 0, afterBatch: () -> Unit = {}, permissions: () -> Boolean = { true }, beforeBatch: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Acknowledgement must not insert")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Acknowledgement must not delete")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Use the atomic acknowledgement batch")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    interceptedQueries++; return real.query(uri, columns, selection, args, order)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertEquals(writes, operations.count { it.isUpdate })
                    assertTrue(operations.all { it.isAssertQuery || it.isUpdate })
                    if (writes == 1) {
                        assertEquals(if (series) 8 + (if (rich) 1 else 0) else 7, operations.size); assertTrue(operations.last().isUpdate)
                        val update = operations.last().resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0)!!
                        assertEquals(setOf(Events.DIRTY, Events.SYNC_DATA7, Events.SYNC_DATA3), update.keySet())
                        assertNull(update.getAsString(Events.SYNC_DATA3))
                        assertEquals(0, update.getAsInteger(Events.DIRTY)); assertEquals(marker, update.getAsString(Events.SYNC_DATA7))
                    } else assertTrue(operations.size == 3 || operations.size == 6)
                    interceptedBatches++; beforeBatch(); realBatchAttempts++
                    val result = real.applyBatch(CalendarContract.AUTHORITY, operations); afterBatch(); return result
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (permission in listOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR) && !permissions())
                        android.content.pm.PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
            }
            return AndroidCalendarProjection(wrapped)
        }
        suspend fun close() {
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target); AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, series: Boolean = false, rich: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay, series, rich)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun assertRejected(block: suspend () -> Any?) {
        try { block(); fail("Changed or interrupted atomic batch must be refused") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
    }
}

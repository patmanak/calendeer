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

/** Actual isolated Provider qualification; no release, acknowledgement write or remote transport. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateCleanProviderDeviceTest {
    @Test fun exactCleanAcknowledgedTimedOriginalHasOnlyAPointInTimeWitness() = fixture {
        val before = values(); val adapter = intercepted()
        assertEquals(CapturedCreateCleanWitness(ack), adapter.inspectAcknowledgedCreateClean(ack))
        assertEquals(1, realBatchAttempts); assertEquals(before, values())
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack))
    }

    @Test fun exactCleanAllDayOriginalRetainsItsExclusiveDates() = fixture(allDay = true) {
        val before = values(); assertEquals(CapturedCreateCleanWitness(ack), projection.inspectAcknowledgedCreateClean(ack))
        assertEquals(before, values())
    }

    @Test fun changedContentEvenWithDirtyZeroIsNotQualifiedByHistoricalAcknowledgement() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Changed while clean") }); val before = values()
        assertEquals("0", field(Events.DIRTY)); assertNull(projection.inspectAcknowledgedCreateClean(ack))
        assertEquals(AppliedCapturedCreateAcknowledgement(ack), projection.inspectCapturedAcknowledgement(ack)); assertEquals(before, values())
    }

    @Test fun dirtyOriginalStaysUnqualifiedAndUnconsumedEvenWhenContentMatches() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 1) }); val before = values()
        assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values())
    }

    @Test fun presentTombstoneIsNeverCurrentCleanEvidence() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values())
    }

    @Test fun reminderAddedToACleanRowIsPreservedAndRefusesTheWitness() = fixture {
        reminder(); val before = values(); assertNull(projection.inspectAcknowledgedCreateClean(ack))
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun extendedPropertyAddedToACleanRowIsPreservedAndRefusesTheWitness() = fixture {
        context.contentResolver.insert(syncUri(ExtendedProperties.CONTENT_URI), ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, row); put(ExtendedProperties.NAME, "clean-fixture"); put(ExtendedProperties.VALUE, "extra")
        })!!
        val before = values(); assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values())
        assertEquals(1, children(ExtendedProperties.CONTENT_URI, ExtendedProperties.EVENT_ID))
    }

    @Test fun unsupportedColorFlagsAndPrecisionCannotQualifyCleanEvidence() = fixture {
        for ((column, changed) in listOf(Events.EVENT_COLOR to 0xFFAA1122.toInt().toString(),
            Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(), Events.GUESTS_CAN_MODIFY to "1",
            Events.DTSTART to ((content.time as EventTime.Timed).start.toEpochMilli() + 1).toString())) {
            val previous = field(column); mutate(ContentValues().apply { put(column, changed) }); val before = values()
            assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values())
            mutate(ContentValues().apply { if (previous == null) putNull(column) else put(column, previous) })
        }
    }

    @Test fun changedNonceOrCaptureSourceCannotAdoptTheCleanOriginal() = fixture {
        val before = values(); assertNull(projection.inspectAcknowledgedCreateClean(ack.copy(nonce = UUID.randomUUID().toString())))
        val different = receipt.copy(binding = receipt.binding.copy(identity = receipt.binding.identity.copy(captureId = MutationId("other-capture"))))
        assertNull(projection.inspectAcknowledgedCreateClean(ack.copy(baseline = baseline.copy(receipt = different))))
        assertEquals(before, values())
    }

    @Test fun substitutedOrUnknownMetadataCannotQualifyAndIsNotRepaired() = fixture {
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA3, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val previous = field(column); mutate(ContentValues().apply { put(column, "unknown-clean-proof") }); val before = values()
            assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values())
            mutate(ContentValues().apply { if (previous == null) putNull(column) else put(column, previous) })
        }
    }

    @Test fun purgedOriginalNeverFallsBackToAnUnmarkedReplacement() = fixture {
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertNull(projection.inspectAcknowledgedCreateClean(ack))
        context.contentResolver.insert(uri, candidateValues())!!
        assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(1, rows())
    }

    @Test fun duplicateLocalMarkerRefusesCleanEvidenceWithoutMerging() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        val before = values(); assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun duplicateRemoteIdentityRefusesCleanEvidenceWithoutMerging() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!!
        val before = values(); assertNull(projection.inspectAcknowledgedCreateClean(ack)); assertEquals(before, values()); assertEquals(2, rows())
    }

    @Test fun concurrentContentEditWithDirtyZeroAbortsTheAtomicWitness() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent clean edit") }) }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("Concurrent clean edit", field(Events.TITLE)); assertEquals("0", field(Events.DIRTY))
    }

    @Test fun concurrentDirtyFlagAbortsTheAtomicWitnessAndRemainsDirty() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.DIRTY, 1) }) }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun concurrentTombstoneAbortsTheAtomicWitnessAndRemainsPresent() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.DELETED, 1) }) }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts); assertEquals("1", field(Events.DELETED))
    }

    @Test fun concurrentReminderAbortsTheAtomicWitnessWithoutDiscardingTheChild() = fixture {
        val adapter = intercepted { reminder() }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID)); assertEquals("0", field(Events.DIRTY))
    }

    @Test fun concurrentProofChangeAbortsTheAtomicWitnessWithoutRepair() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.SYNC_DATA7, "concurrent-unknown-proof") }) }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts)
        assertEquals("concurrent-unknown-proof", field(Events.SYNC_DATA7))
    }

    @Test fun concurrentDuplicateLocalMarkerAbortsTheAtomicWitness() = fixture {
        val adapter = intercepted { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!! }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts); assertEquals(2, rows())
    }

    @Test fun concurrentDuplicateRemoteIdentityAbortsTheAtomicWitness() = fixture {
        val adapter = intercepted { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!! }
        assertBatchRejected { adapter.inspectAcknowledgedCreateClean(ack) }; assertEquals(1, realBatchAttempts); assertEquals(2, rows())
    }

    @Test fun inactiveLeaseCancelsBeforeCleanQueries() = fixture {
        val before = values(); val adapter = intercepted(); adapter.admitOperation = { false }
        try { adapter.inspectAcknowledgedCreateClean(ack); fail("Inactive lease must cancel") } catch (_: CancellationException) {}
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun accessLossAfterAssertionsDoesNotReturnACleanWitness() = fixture {
        var admitted = true; val before = values(); val adapter = intercepted(afterBatch = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.inspectAcknowledgedCreateClean(ack); fail("Lost lease must cancel") } catch (_: CancellationException) {}
        assertEquals(1, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun controlledPermissionDenialPreventsCleanQueriesWithoutChangingRealGrants() = fixture {
        val before = values(); val adapter = intercepted(permissionGranted = { false })
        try { adapter.inspectAcknowledgedCreateClean(ack); fail("Permission gate must refuse") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, interceptedQueries); assertEquals(0, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun controlledPermissionLossAfterAssertionsDoesNotReturnACleanWitness() = fixture {
        var permitted = true; val before = values()
        val adapter = intercepted(afterBatch = { permitted = false }, permissionGranted = { permitted })
        try { adapter.inspectAcknowledgedCreateClean(ack); fail("Permission loss must refuse") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(1, realBatchAttempts); assertEquals(before, values())
    }

    @Test fun everyCapturedOriginMarkerBlocksBothGenericApplyFormsAndDelete() = fixture {
        ordinary()
        for (column in listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)) {
            mutate(ContentValues().apply { put(column, "retained-origin") }); val before = values()
            assertIdentityRefused { projection.apply(ProjectionIntent(target, updated())) }
            assertIdentityRefused { projection.applyTracked(tracked()) }
            assertIdentityRefused { projection.apply(ProjectionIntent(target, RemoteChange.Deleted(baseline.change.eventId, Revision("deleted")))) }
            assertEquals(before, values()); mutate(ContentValues().apply { putNull(column) })
        }
    }

    @Test fun everyCapturedOriginMarkerBlocksGenericEditAndDeleteCapture() = fixture {
        ordinary(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Generic later title") })
        for (column in listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)) {
            mutate(ContentValues().apply { put(column, "retained-origin") }); val before = values()
            assertNull(projection.captureOwnedSimpleEdit(target, baseline.change))
            mutate(ContentValues().apply { put(Events.DELETED, 1) }); val tombstone = values()
            assertNull(projection.captureOwnedSimpleDelete(target, baseline.change)); assertEquals(tombstone, values())
            mutate(ContentValues().apply { put(Events.DELETED, 0) }); assertEquals(before, values())
            mutate(ContentValues().apply { putNull(column) })
        }
    }

    @Test fun unmarkedOrdinaryGenericProjectionStillUpdatesTheExistingRow() = fixture {
        ordinary(); val original = row
        projection.apply(ProjectionIntent(target, updated())); assertEquals(1, rows()); assertEquals(original, row)
        assertEquals("Generic remote title", field(Events.TITLE)); assertNull(field(Events.SYNC_DATA5)); assertNull(field(Events.SYNC_DATA6)); assertNull(field(Events.SYNC_DATA7))
    }

    @Test fun markerAppearingBeforeGenericApplyBatchAbortsTheWholeUpdate() = fixture {
        ordinary(); val adapter = intercepted(mutations = true) { mutate(ContentValues().apply { put(Events.SYNC_DATA5, "concurrent-origin") }) }
        assertBatchRejected { adapter.apply(ProjectionIntent(target, updated())) }; assertEquals(1, realBatchAttempts)
        assertEquals(content.title, field(Events.TITLE)); assertEquals("concurrent-origin", field(Events.SYNC_DATA5)); assertEquals("0", field(Events.DIRTY))
    }

    @Test fun markerAppearingBeforeTrackedBatchAbortsTheWholeUpdateAndReceiptWrite() = fixture {
        ordinary(); val adapter = intercepted(mutations = true) { mutate(ContentValues().apply { put(Events.SYNC_DATA6, "concurrent-origin") }) }
        assertBatchRejected { adapter.applyTracked(tracked()) }; assertEquals(1, realBatchAttempts)
        assertEquals(content.title, field(Events.TITLE)); assertNull(field(Events.SYNC_DATA3)); assertEquals("concurrent-origin", field(Events.SYNC_DATA6))
    }

    @Test fun markerAppearingBeforeGenericDeleteBatchPreservesTheOriginal() = fixture {
        ordinary(); val adapter = intercepted(mutations = true) { mutate(ContentValues().apply { put(Events.SYNC_DATA7, "concurrent-origin") }) }
        assertBatchRejected { adapter.apply(ProjectionIntent(target, RemoteChange.Deleted(baseline.change.eventId, Revision("deleted")))) }
        assertEquals(1, realBatchAttempts); assertEquals(1, rows()); assertEquals(content.title, field(Events.TITLE)); assertEquals("concurrent-origin", field(Events.SYNC_DATA7))
    }

    @Test fun markerAppearingBeforeGenericEditSnapshotBatchIsNotCaptured() = fixture {
        ordinary(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Generic local edit") })
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.SYNC_DATA7, "concurrent-origin") }) }
        assertBatchRejected { adapter.captureOwnedSimpleEdit(target, baseline.change) }; assertEquals(1, realBatchAttempts)
        assertEquals("Generic local edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertEquals("concurrent-origin", field(Events.SYNC_DATA7))
    }

    @Test fun markerAppearingBeforeGenericDeleteSnapshotBatchIsNotCaptured() = fixture {
        ordinary(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.SYNC_DATA6, "concurrent-origin") }) }
        assertBatchRejected { adapter.captureOwnedSimpleDelete(target, baseline.change) }; assertEquals(1, realBatchAttempts)
        assertEquals("1", field(Events.DELETED)); assertEquals("1", field(Events.DIRTY)); assertEquals("concurrent-origin", field(Events.SYNC_DATA6))
    }

    @Test fun capturedMarkerOnASeriesChildBlocksGenericMasterDeletion() = fixture {
        val (master, child) = series()
        assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(Events.SYNC_DATA5, "child-origin") }, "${Events._ID}=?", arrayOf(child.toString())))
        assertIdentityRefused { projection.apply(ProjectionIntent(target, RemoteChange.Deleted(master.eventId, Revision("master-deleted"))), "clean-fixture-series") }
        assertEquals(3, rows()); assertEquals("child-origin", fieldAt(child, Events.SYNC_DATA5))
    }

    @Test fun concurrentSeriesChildMarkerAbortsMasterDeletionAtomically() = fixture {
        val (master, child) = series()
        val adapter = intercepted(mutations = true) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(Events.SYNC_DATA6, "child-origin") }, "${Events._ID}=?", arrayOf(child.toString())))
        }
        assertBatchRejected { adapter.apply(ProjectionIntent(target, RemoteChange.Deleted(master.eventId, Revision("master-deleted"))), "clean-fixture-series") }
        assertEquals(1, realBatchAttempts); assertEquals(3, rows()); assertEquals("child-origin", fieldAt(child, Events.SYNC_DATA6))
    }

    @Test fun capturedMarkersOnAParentPreventGenericExceptionAttachment() = fixture {
        val (parent, child) = exceptionParent()
        for (column in listOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(column, "parent-origin") }, "${Events._ID}=?", arrayOf(parent.toString())))
            assertIdentityRefused { projection.apply(ProjectionIntent(target, child)) }
            assertEquals(2, rows()); assertEquals("parent-origin", fieldAt(parent, column))
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { putNull(column) }, "${Events._ID}=?", arrayOf(parent.toString())))
        }
    }

    @Test fun concurrentParentMarkerAbortsGenericExceptionInsertion() = fixture {
        val (parent, child) = exceptionParent()
        val adapter = intercepted(mutations = true) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply { put(Events.SYNC_DATA7, "parent-origin") }, "${Events._ID}=?", arrayOf(parent.toString())))
        }
        assertBatchRejected { adapter.apply(ProjectionIntent(target, child)) }
        assertEquals(1, realBatchAttempts); assertEquals(2, rows()); assertEquals("parent-origin", fieldAt(parent, Events.SYNC_DATA7))
    }

    private class Fixture(val context: Context, val allDay: Boolean) {
        val target = CalendarScope(AccountId("captured-clean-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val projection = AndroidCalendarProjection(context)
        val content = EventContent("Captured clean provider fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        private val captured = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), localId, target,
            AdapterFormat("clean-provider-import", 1), Instant.parse("2030-10-01T12:00:00Z")), content)
        private val stage = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
            MutationId(UUID.randomUUID().toString()), AdapterFormat("clean-provider-submission", 2)), "opaque-fixture")
        val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(stage),
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
            // These fixtures test captured-origin barriers on native parents/children, not finite
            // date projection or DST geometry. Use an unambiguous UTC master and ordinary
            // unbounded rule so setup does not enter the separately guarded recurrence risk path.
            val master = baseline.change.copy(eventId = EventId("clean-series-master"),
                content = content.copy(time = (content.time as EventTime.Timed).copy(zone = ZoneId.of("UTC")),
                    recurrence = EventRecurrence("clean-fixture-series", "FREQ=DAILY")))
            projection.apply(ProjectionIntent(target, master))
            val child = baseline.change.copy(eventId = EventId("clean-series-child"), content = content.copy(title = "Clean series child", time = master.content.time,
                recurrence = EventRecurrence("clean-fixture-series", original = RecurrencePoint.Timed(Instant.parse("2030-10-28T00:30:00Z")))))
            projection.apply(ProjectionIntent(target, child))
            val childRow = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?",
                arrayOf(calendar.toString(), "Clean series child"), null)!!.use {
                    assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
                }
            return master to childRow
        }
        suspend fun exceptionParent(): Pair<Long, RemoteChange.Upsert> {
            // Parent provenance admission is independent of finite views or DST geometry. The UTC
            // master avoids the original fixture's ambiguous Paris start before the assertions.
            val master = baseline.change.copy(eventId = EventId("clean-exception-parent"),
                content = content.copy(title = "Clean standalone master",
                    time = (content.time as EventTime.Timed).copy(zone = ZoneId.of("UTC")),
                    recurrence = EventRecurrence("clean-attachment-series", "FREQ=DAILY")))
            projection.apply(ProjectionIntent(target, master))
            val parent = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?",
                arrayOf(calendar.toString(), "Clean standalone master"), null)!!.use {
                    assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
                }
            val child = baseline.change.copy(eventId = EventId("clean-unattached-exception"), content = content.copy(title = "Clean unattached child", time = master.content.time,
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
        fun intercepted(mutations: Boolean = false, afterBatch: () -> Unit = {},
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
                    if (mutations) {
                        assertTrue(operations.any { it.isUpdate || it.isDelete || it.isInsert })
                        assertTrue(operations.all { it.isAssertQuery || it.isUpdate || it.isDelete || it.isInsert })
                    } else {
                        assertEquals(6, operations.size); assertTrue(operations.all { it.isAssertQuery })
                    }
                    interceptedBatches++; beforeBatch(); realBatchAttempts++
                    val result = real.applyBatch(CalendarContract.AUTHORITY, operations); afterBatch(); return result
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
    private suspend fun assertIdentityRefused(block: suspend () -> Any?) {
        try { block(); fail("Captured identity must not enter generic projection") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
    }
}

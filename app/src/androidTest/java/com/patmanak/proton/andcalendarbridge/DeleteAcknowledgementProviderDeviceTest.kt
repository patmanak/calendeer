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
class DeleteAcknowledgementProviderDeviceTest {
    @Test fun exactOrdinaryTombstoneIsRetainedCleanOnTheSameRow() = fixture {
        val before = values(); val adapter = intercepted(writes = 1)
        assertEquals(1, adapter.deleteAcknowledgementProtocol)
        assertEquals(AppliedDeleteAcknowledgement(receipt), adapter.acknowledgeDelete(receipt))
        assertEquals(1, batches); assertEquals(1, rows())
        assertEquals(before + expectedChanges(), values())
        val after = values()
        assertEquals(AppliedDeleteAcknowledgement(receipt), intercepted().inspectDeleteAcknowledgement(receipt))
        assertEquals(after, values())
        assertEquals(AppliedDeleteAcknowledgement(receipt), AndroidCalendarProjection(context).inspectDeleteAcknowledgement(receipt))
        assertEquals(after, values())
    }
    @Test fun exclusiveAllDayDatesAndRawTextAreRetained() = fixture(allDay = true) {
        mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
        val before = values(); acknowledge()
        assertEquals(before + expectedChanges(), values())
    }
    @Test fun editorMutatorHistoryIsClearedByProviderWhenMarkedClean() = fixture {
        // Use the ordinary application route, as a real calendar editor does.
        mutate(ContentValues().apply { put(Events.DELETED, 0); put(Events.DIRTY, 0) })
        val ordinary = ContentUris.withAppendedId(Events.CONTENT_URI, row)
        assertEquals(1, context.contentResolver.delete(ordinary, null, null))
        val before = values()
        assertEquals("1", before[Events.DELETED]); assertEquals("1", before[Events.DIRTY])
        assertFalse(before[Events.MUTATORS].isNullOrBlank())
        acknowledge()
        // AOSP clears MUTATORS when a sync adapter resets DIRTY. No event data changes.
        assertEquals(before + expectedChanges() + mapOf(Events.MUTATORS to null), values())
    }
    @Test fun changedContentBeforeAcknowledgementIsPreserved() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Later title") }); val before = values()
        assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
    }
    @Test fun undeleteCannotBeAcknowledged() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 0) }); val before = values()
        assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
    }
    @Test fun everyUnsupportedChildBlocksAcknowledgement() = fixture {
        for (kind in 0..2) {
            val child = addChild(kind); val before = values()
            assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
            removeChild(child)
        }
    }
    @Test fun wrongBaseRevisionAndAllOriginOrReservedMarkersAreRefused() = fixture {
        val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "unrelated") }); val before = values()
            assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
    }
    @Test fun finiteUnknownAndMalformedPreviousReceiptMarkersAreRefused() = fixture {
        for (value in listOf("calendeer-finite-daily-dates-v1-receipt-v1:${UUID.randomUUID()}", "unknown", "calendeer-projection-receipt-v1:broken")) {
            mutate(ContentValues().apply { put(Events.SYNC_DATA3, value) }); val before = values()
            assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
        }
    }
    @Test fun concurrentFullContentChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DESCRIPTION, "Concurrent detail") }) })
        rejected { adapter.acknowledgeDelete(receipt) }; assertEquals(1, batches)
        assertEquals("Concurrent detail", field(Events.DESCRIPTION)); unchangedMarkers()
    }
    @Test fun concurrentUndeleteAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DELETED, 0) }) })
        rejected { adapter.acknowledgeDelete(receipt) }; assertEquals("0", field(Events.DELETED)); unchangedMarkers()
    }
    @Test fun concurrentReceiptOrReservedMarkerChangeAbortsTheAtomicBatch() = fixture {
        for (column in listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val before = field(column)
            rejected { intercepted(writes = 1, before = { mutate(ContentValues().apply { put(column, "changed-marker") }) })
                .acknowledgeDelete(receipt) }
            assertEquals("1", field(Events.DIRTY)); assertEquals(digest("revision-v1", base.revision.value), field(Events.SYNC_DATA2))
            assertEquals("changed-marker", field(column)); mutate(ContentValues().apply { put(column, before) })
        }
    }
    @Test fun eachConcurrentChildAbortsTheAtomicBatch() = fixture {
        for (kind in 0..2) {
            var child: Uri? = null
            val adapter = intercepted(writes = 1, before = { child = addChild(kind) })
            rejected { adapter.acknowledgeDelete(receipt) }; unchangedMarkers()
            removeChild(checkNotNull(child))
        }
    }
    @Test fun concurrentRemoteAndSyncIdentityDuplicatesAbortTheAtomicBatch() = fixture {
        for (remote in listOf(true, false)) {
            var duplicateRow: Uri? = null
            val adapter = intercepted(writes = 1, before = { duplicateRow = duplicate(remote) })
            rejected { adapter.acknowledgeDelete(receipt) }; unchangedMarkers()
            context.contentResolver.delete(syncUri(checkNotNull(duplicateRow)), null, null)
        }
    }
    @Test fun concurrentCalendarOwnershipChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { context.contentResolver.update(syncUri(Calendars.CONTENT_URI),
            ContentValues().apply { put(Calendars.CAL_SYNC1, "foreign-owner") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) })
        try { rejected { adapter.acknowledgeDelete(receipt) }; unchangedMarkers() }
        finally { context.contentResolver.update(syncUri(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.CAL_SYNC1, "calendar-bridge-projection-v1") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) }
    }
    @Test fun historicalProofAfterLostReplyPreservesLaterEditDeletionAndChildren() = fixture {
        val adapter = intercepted(writes = 1, after = { throw RemoteException("synthetic lost reply") })
        unavailable { adapter.acknowledgeDelete(receipt) }; assertEquals(1, batches)
        mutate(ContentValues().apply { put(Events.TITLE, "Later local edit"); put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        addChild(0); val before = values()
        assertEquals(AppliedDeleteAcknowledgement(receipt), intercepted().inspectDeleteAcknowledgement(receipt))
        assertEquals(before, values()); assertNull(projection.acknowledgeDelete(receipt))
    }
    @Test fun interruptionBeforeExecutionCreatesNoHistoricalProof() = fixture {
        val before = values(); val adapter = intercepted(writes = 1, before = { throw RemoteException("synthetic interruption") })
        unavailable { adapter.acknowledgeDelete(receipt) }
        assertEquals(0, batches); assertNull(projection.inspectDeleteAcknowledgement(receipt)); assertEquals(before, values())
    }
    @Test fun malformedBatchResponseNeverProducesAWitnessButHistoryRemainsRecoverable() = fixture {
        unavailable { intercepted(writes = 1, malformed = true).acknowledgeDelete(receipt) }
        assertEquals(AppliedDeleteAcknowledgement(receipt), projection.inspectDeleteAcknowledgement(receipt))
    }
    @Test fun historicalInspectionRefusesNonceRevisionOriginsAndReservedMetadataMismatch() = fixture {
        acknowledge(); val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "other") }); val before = values()
            assertNull(projection.inspectDeleteAcknowledgement(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
        assertNull(projection.inspectDeleteAcknowledgement(receipt.copy(nonce = UUID.randomUUID().toString())))
    }
    @Test fun historicalInspectionAtomicallyRejectsConcurrentOriginChange() = fixture {
        acknowledge()
        rejected { intercepted(before = { mutate(ContentValues().apply { put(Events.SYNC_DATA5, "foreign-origin") }) })
            .inspectDeleteAcknowledgement(receipt) }
        assertEquals("foreign-origin", field(Events.SYNC_DATA5))
    }
    @Test fun modeAndPermissionGatesBlockBeforeQueries() = fixture {
        val before = values(); val closed = intercepted(writes = 1); closed.admitOperation = { false }
        try { closed.acknowledgeDelete(receipt); fail("Inactive mode must cancel") } catch (_: CancellationException) { }
        try { intercepted(writes = 1, denied = true).acknowledgeDelete(receipt); fail("Permission gate must refuse") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, queries); assertEquals(0, batches); assertEquals(before, values())
    }
    @Test fun modeLossAfterCommitDoesNotReturnAWitnessAndHistoryCanRecover() = fixture {
        var admitted = true
        val adapter = intercepted(writes = 1, after = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.acknowledgeDelete(receipt); fail("Lost mode must cancel witness return") } catch (_: CancellationException) { }
        assertEquals(1, batches)
        assertEquals(AppliedDeleteAcknowledgement(receipt), projection.inspectDeleteAcknowledgement(receipt))
    }


    @Test fun canonicallyEquivalentButDifferentNativeRawTextIsNotAcknowledged() = fixture {
        for (column in listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION)) {
            val original = field(column)!!
            mutate(ContentValues().apply { put(column, original.replace("\r\n", "\n")) })
            val before = values()
            assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original) })
        }
    }
    @Test fun changedTimeZonePrecisionAndFlagsCannotBeAcknowledged() = fixture {
        val original = values()
        for ((column, changed) in mapOf(Events.DTSTART to (field(Events.DTSTART)!!.toLong() + 1).toString(),
            Events.EVENT_TIMEZONE to "UTC", Events.EVENT_END_TIMEZONE to "UTC",
            Events.ALL_DAY to "1", Events.EVENT_COLOR to "123456", Events.STATUS to Events.STATUS_TENTATIVE.toString())) {
            mutate(ContentValues().apply { put(column, changed) }); val before = values()
            assertNull(projection.acknowledgeDelete(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
    }
    @Test fun closingLeaseDuringReadCancelsBeforeBatch() = fixture {
        var admitted = true
        val adapter = intercepted(writes = 1, afterQuery = { admitted = false })
        adapter.admitOperation = { admitted }
        val before = values()
        try { adapter.acknowledgeDelete(receipt); fail("Expired admission must cancel") }
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
                try { adapter.acknowledgeDelete(receipt); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { other++ }
            }
            try { child.await() } catch (_: CancellationException) { }
            assertEquals(1, cancelled); assertEquals(0, other); assertEquals(0, returned)
            assertEquals(0, queries); assertEquals(0, batches); assertEquals(before, values())
        }
    }
    @Test fun existingDuplicateRemoteOrSyncIdentityIsNeverAdopted() = fixture {
        for (remote in listOf(true, false)) {
            val other = duplicate(remote); val before = values()
            try {
                noIdentityWitness { projection.acknowledgeDelete(receipt) }
                noIdentityWitness { projection.inspectDeleteAcknowledgement(receipt) }
                assertEquals(before, values())
            } finally { context.contentResolver.delete(syncUri(other), null, null) }
        }
    }
    @Test fun recurrenceColorAndUnknownMetadataArePreservedWithoutAcknowledgement() = fixture {
        val original = values()
        for ((column, value) in mapOf(Events.RRULE to "FREQ=DAILY", Events.EVENT_COLOR to "123456",
            Events.ORIGINAL_SYNC_ID to "other-series",
            Events.SYNC_DATA1 to "foreign-owner", Events.SYNC_DATA4 to "foreign-event")) {
            mutate(ContentValues().apply { put(column, value) }); val before = values()
            noIdentityWitness { projection.acknowledgeDelete(receipt) }; assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
        val account = projection.accountFor(scope.account)
        val color = context.contentResolver.insert(syncUri(Colors.CONTENT_URI), ContentValues().apply {
            put(Colors.ACCOUNT_NAME, account.name); put(Colors.ACCOUNT_TYPE, account.type)
            put(Colors.COLOR_KEY, "synthetic-delete-ack-color"); put(Colors.COLOR_TYPE, Colors.TYPE_EVENT)
            put(Colors.COLOR, 0xFF123456.toInt())
        })!!
        try {
            mutate(ContentValues().apply { put(Events.EVENT_COLOR_KEY, "synthetic-delete-ack-color") })
            val before = values()
            assertEquals("synthetic-delete-ack-color", field(Events.EVENT_COLOR_KEY))
            noIdentityWitness { projection.acknowledgeDelete(receipt) }; assertEquals(before, values())
        } finally {
            mutate(ContentValues().apply {
                put(Events.EVENT_COLOR_KEY, original[Events.EVENT_COLOR_KEY])
                put(Events.EVENT_COLOR, original[Events.EVENT_COLOR])
            })
            // Calendar Provider exposes only the Colors collection route, not colors/{id}.
            assertEquals(1, context.contentResolver.delete(syncUri(Colors.CONTENT_URI),
                "${Colors._ID}=?", arrayOf(ContentUris.parseId(color).toString())))
        }
    }
    @Test fun missingRowIsNeverAcknowledgementProof() = fixture {
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertNull(projection.acknowledgeDelete(receipt)); assertNull(projection.inspectDeleteAcknowledgement(receipt))
        assertEquals(0, rows())
    }
    @Test fun historicalMarkerBindsAttemptPayloadAndRawReceipt() = fixture {
        acknowledge(); val before = values()
        val other = receipt.copy(attempt = receipt.attempt.copy(payloadFingerprint = "0".repeat(64)))
        assertNull(projection.inspectDeleteAcknowledgement(other))
        val otherId = receipt.copy(attempt = receipt.attempt.copy(id = MutationId("other-request")))
        assertNull(projection.inspectDeleteAcknowledgement(otherId))
        val content = receipt.intent.content.copy(title = "Different retained title")
        val intent = receipt.intent.copy(base = receipt.intent.base.copy(content = content), content = content)
        val raw = receipt.copy(intent = intent, attempt = receipt.attempt.copy(intentFingerprint = DeleteAttemptIdentity.intentFingerprint(intent)))
        assertNull(projection.inspectDeleteAcknowledgement(raw)); assertEquals(before, values())
    }
    @Test fun historicalInspectionPreservesUndeleteNewDatesAndChildren() = fixture {
        acknowledge()
        mutate(ContentValues().apply { put(Events.DELETED, 0); put(Events.DIRTY, 1)
            put(Events.TITLE, "New local event title"); put(Events.DTEND, field(Events.DTEND)!!.toLong() + 3600000) })
        addChild(2); val before = values()
        assertEquals(AppliedDeleteAcknowledgement(receipt), projection.inspectDeleteAcknowledgement(receipt))
        assertEquals(before, values()); assertNull(projection.acknowledgeDelete(receipt))
    }
    private class Fixture(val context: Context, allDay: Boolean) {
        val scope = CalendarScope(AccountId("delete-ack-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val format = AdapterFormat("synthetic-delete-ack", 1)
        val content = EventContent("Original\r\nsynthetic title", if (allDay) EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1))
            else EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            description = "Original\r\nsynthetic detail", location = "Original\r\nsynthetic place", details = EventDetails())
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content)
        val intent = LocalDeleteIntent(MutationId("synthetic-intent"), scope, format, base, content)
        val record = DeleteAttemptRecord(MutationId("synthetic-request"), intent, format, "opaque-delete", DeleteAttemptState.OUTCOME_UNKNOWN)
        val receipt = DeleteAcknowledgementReceipt.of(DeleteSubmissionObservation.Verified(record), UUID.randomUUID().toString())
        val marker = "calendeer-delete-ack-v1:" + receipt.attempt.let { a -> digest(receipt.nonce,
            a.scope.account.value, a.scope.calendar.value, a.scope.generation.toString(), a.id.value,
            a.localIntentId.value, a.eventId.value, a.baseRevision.value, a.format.id, a.format.version.toString(),
            a.intentFingerprint, a.payloadFingerprint) }
        val oldMarker = "calendeer-projection-receipt-v1:${UUID.randomUUID()}"
        var calendar = -1L; var row = -1L; var queries = 0; var batches = 0
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri = projection.accountFor(scope.account).let { account -> uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build() }
        suspend fun seed() {
            calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic delete ACK fixture"), true).id
            projection.apply(ProjectionIntent(scope, base))
            row = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                check(it.count == 1 && it.moveToFirst()); it.getLong(0)
            }
            mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1); put(Events.SYNC_DATA3, oldMarker) })
        }
        fun expectedChanges(): Map<String, String?> = mapOf(Events.DIRTY to "0", Events.SYNC_DATA3 to marker)
        suspend fun acknowledge() { assertEquals(AppliedDeleteAcknowledgement(receipt), projection.acknowledgeDelete(receipt)) }
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
                        val update = operations.single { it.isUpdate }.resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0)!!
                        assertEquals(expectedChanges().keys, update.keySet())
                        for ((column, expected) in expectedChanges()) assertEquals(expected, update.getAsString(column))
                        assertTrue(operations.last().isAssertQuery)
                        val retained = operations.last().resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0)!!
                        assertEquals("1", retained.getAsString(Events.DELETED)); assertEquals("0", retained.getAsString(Events.DIRTY))
                        val snapshot = operations.filter { it.isAssertQuery }.mapNotNull { it.resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0) }
                            .first { it.containsKey(Events.TITLE) }
                        assertTrue(snapshot.keySet().containsAll(listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
                            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)))
                        assertEquals("1", snapshot.getAsString(Events.DELETED)); assertEquals("1", snapshot.getAsString(Events.DIRTY))
                        assertEquals(intent.content.title, snapshot.getAsString(Events.TITLE))
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
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay); assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun noIdentityWitness(block: suspend () -> Any?) {
        try { assertNull(block()) }
        catch (failure: CalendarProjectionException) {
            assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category)
        }
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

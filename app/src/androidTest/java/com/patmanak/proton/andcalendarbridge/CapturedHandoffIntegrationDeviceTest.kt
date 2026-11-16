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
import com.patmanak.proton.andcalendarbridge.data.calendar.EventColorMode
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemPersonalOperations
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSeriesUpdatePipelineFixture
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith



/** Native Provider and three real journals; controlled incoming data, no network upload. */
@RunWith(AndroidJUnit4::class)
class CapturedHandoffIntegrationDeviceTest {
    @Test fun automaticSeriesEntryRetainsFromNonWaitingFrameAndReusesItsOwner() = fixture(series = true) {
        transferred(); incoming(verifiedEvent); drain(); titleMode = true
        assertFalse(role()!!.waiting)
        mutate(ContentValues().apply { put(Events.TITLE, "Automatic series backlog"); put(Events.RRULE, "FREQ=DAILY;COUNT=8"); put(Events.DIRTY, 1) })
        var rearmed = 0
        suspend fun capture() = coordinator.captureHandoffSeriesEdit(target, localId, id, attempt, null,
            beforeNewOwner = { rearmed++ })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, capture())
        val owner = edit()!!
        assertTrue(role()!!.waiting); assertEquals(1, rearmed)
        assertEquals(CapturedCreateReconciliationResult.RETAINED, capture())
        assertEquals(owner, edit()); assertEquals(1, rearmed); assertEquals(1, sends)
    }
    @Test fun automaticSeriesEntryCannotRearmAnUnknownCreateWithoutHandoffProof() = fixture(series = true) {
        prepare(); submit(); titleMode = true
        val unknown = record()!!
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        var rearmed = 0
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED,
            coordinator.captureHandoffSeriesEdit(target, localId, id, attempt, null, beforeNewOwner = { rearmed++ }))
        assertEquals(unknown, record()); assertNull(edit()); assertEquals(0, rearmed); assertEquals(1, sends)
    }
    @Test fun automaticSeriesEntryRefusesRevocationDuringDiscoveryRearming() = fixture(series = true) {
        transferred(); incoming(verifiedEvent); drain(); titleMode = true
        mutate(ContentValues().apply { put(Events.TITLE, "Revoked automatic series"); put(Events.DIRTY, 1) })
        assertEquals(CapturedCreateReconciliationResult.INACTIVE,
            coordinator.captureHandoffSeriesEdit(target, localId, id, attempt, null, beforeNewOwner = { permitted = false }))
        permitted = true
        assertNull(edit()); assertEquals("1", field(Events.DIRTY)); assertEquals(1, sends)
    }

    @Test fun transferredTimedAndAllDaySeriesReachRealCryptoDispatchReadbackAndAcknowledgement() = runBlocking {
        transferredPipeline(false)
    }
    @Test fun automaticTransferredSeriesCaptureRearmsOnceAndReachesTheExistingPipeline() = runBlocking {
        transferredPipeline(true)
    }
    @Test fun offPaletteTransferredSeriesKeepsItsRawOwnerAndNeverClaimsAnUpdate() = runBlocking {
        transferredPipeline(true, unsupportedColour = true)
    }
    private suspend fun transferredPipeline(automatic: Boolean, unsupportedColour: Boolean = false) {
        for (allDay in listOf(false, true)) assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runSeriesPipeline(allDay) { service ->
            fixture(series = true, allDay = allDay, richOrigin = true, crypto = service) {
                transferred(); incoming(verifiedEvent); drain(); titleMode = true; personalMode = true
                val original = role()!!.frame.receipt
                mutate(seriesEditValues(if (unsupportedColour) service.replacement.copy(colorRgb = 0x7868e6) else service.replacement))
                val reminderUri = CalendarContract.Reminders.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
                context.contentResolver.delete(reminderUri, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(row.toString()))
                service.replacement.details!!.reminders.forEach { addReminder(it.minutesBefore) }
                var rearmed = 0
                suspend fun capture() = if (automatic) coordinator.reconcileHandoffLocalCreate(target, localId, id, attempt,
                    beforeNewOwner = { rearmed++ }) else captureSeriesEdit()
                assertEquals(CapturedCreateReconciliationResult.RETAINED, capture())
                if (automatic) {
                    assertEquals(1, rearmed)
                    assertEquals(CapturedCreateReconciliationResult.RETAINED, capture())
                    assertEquals(1, rearmed)
                }
                val database = "transferred-series-update-${UUID.randomUUID()}.db"
                try { TitleUpdateAttemptStore(context, database).use { shared ->
                    var retained: PersonalUpdateAttemptRecord? = null
                    val journal = object : PersonalUpdateRecoveryJournal by shared.personalJournal {
                        override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
                            retained = record.frozen(); return shared.personalJournal.remember(record, admit)
                        }
                    }
                    val operations = CoordinatorSystemPersonalOperations(coordinator, journal, service.preparations, service.updates)
                    val reference = (coordinator.retainedLocalChanges(RetainedLocalChangeQuery(target)) as RetainedLocalChangeListing.Available).page.entries.single()
                    val admission = coordinator.systemPersonalRunAdmission(target)
                    assertTrue("Original scoped editing lease required", admission(target))
                    allowStore(); assertEquals(PersonalPreparationAdmission.ELIGIBLE, store.assessPersonalPreparation(edit()!!))
                    assertNotNull("Full transferred source required", coordinator.personalOwners.observe(target,
                        verifiedEvent.eventId, reference.mutationId, admission))
                    operations.process(target, reference, MutationId("unused-series-candidate"), service.timestamp,
                        coordinator.systemPersonalRunAdmission(target)) { }
                    if (unsupportedColour) {
                        assertEquals(0, service.sendCount); assertNull(retained)
                        val raw = edit()!!
                        assertEquals(0x7868e6, raw.content.colorRgb); assertEquals("1", field(Events.DIRTY))
                        assertTrue(role()!!.waiting); assertEquals(original, role()!!.frame.receipt)
                        reopen(); assertEquals(raw, edit()); assertEquals(1, sends)
                        return@fixture
                    }
                    assertEquals(1, service.sendCount)
                    val work = checkNotNull(retained)
                    val proof = checkNotNull(journal.readVerified(target, work.id) { it == target })
                    assertNotNull(journal.readCompletion(target, work.id) { it == target })
                    assertEquals(service.replacement, proof.event.content); assertNull(edit())
                    assertEquals(original, role()!!.frame.receipt); assertEquals(proof.event, role()!!.frame.base)
                    assertEquals("0", field(Events.DIRTY)); assertNull(field(Events.DTEND))
                    assertEquals(service.replacement.recurrence!!.rule, field(Events.RRULE))
                    assertEquals(duration(service.replacement.time), field(Events.DURATION))
                    reopen()
                    assertEquals(original, role()!!.frame.receipt)
                    assertNotNull(journal.readCompletion(target, work.id) { it == target }); assertEquals(1, service.sendCount)
                } } finally { context.deleteDatabase(database) }
            }
        })
    }

    @Test fun transferredSeriesPersonalAcknowledgementAdvancesTheFrameAndKeepsItsImmutableOrigin() = runBlocking {
        for (allDay in listOf(false, true)) fixture(series = true, allDay = allDay, richOrigin = true) {
            transferred(); incoming(verifiedEvent); drain()
            val original = role()!!.frame
            mutate(ContentValues().apply { put(Events.TITLE, "Transferred series edit"); put(Events.RRULE, "FREQ=DAILY;COUNT=8"); put(Events.DIRTY, 1) })
            assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
            val local = edit()!!
            val preparation = PersonalEditPreparation.forSeries(local, timestamp, AdapterFormat("series-ack-preparation", 3), "protected-series")
            allowStore()
            assertEquals(PersonalPreparationAdmission.STORED, store.retainCurrentPersonalPreparation(preparation))
            val work = PersonalUpdateAttemptRecord(MutationId("series-personal-ack"), preparation, AdapterFormat("series-ack-wire", 3),
                "protected-wire", PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            val actual = local.base.copy(revision = Revision("verified-series-edit"), content = local.content)
            val proof = PersonalUpdateVerifiedResult.of(work, PersonalUpdateSubmissionObservation.Verified(work, actual))
            val reserved = checkNotNull(store.preparePersonalUpdateAcknowledgement(proof, original))
            val receipt = reserved.record.receipt
            val rawRule = field(Events.RRULE); val rawDuration = field(Events.DURATION); val syncId = field(Events._SYNC_ID)
            projection.admitOperation = { true }
            val applied = checkNotNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals("0", field(Events.DIRTY)); assertNull(field(Events.DTEND))
            assertEquals(rawRule, field(Events.RRULE)); assertEquals(rawDuration, field(Events.DURATION)); assertEquals(syncId, field(Events._SYNC_ID))
            assertTrue(store.restorePersonalUpdateAcknowledgement(applied))
            assertEquals(original.receipt, role()!!.frame.receipt); assertEquals(actual, role()!!.frame.base)
            reopen()
            mutate(ContentValues().apply { put(Events.TITLE, "Newer transferred series edit"); put(Events.DIRTY, 1) })
            val newer = providerSnapshot()
            projection.admitOperation = { true }
            assertNotNull(projection.resumePersonalUpdateAcknowledgement(receipt))
            assertEquals(newer, providerSnapshot()); assertEquals(original.receipt, role()!!.frame.receipt)
        }
    }

    @Test fun explicitTransferredSeriesEditKeepsCurrentFrameMetadataAndNewerNativeChangesAcrossReopen() = runBlocking {
        for (allDay in listOf(false, true)) for (mode in EventColorMode.entries) fixture(series = true, allDay = allDay,
            richOrigin = true, colorMode = mode) {
            transferred()
            val next = verifiedEvent.copy(revision = Revision("advanced-series"), content = verifiedEvent.content.copy(
                recurrence = verifiedEvent.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")))
            incoming(next); drain()
            val frame = role()!!.frame; val original = baseline(); val ack = frame.receipt.acknowledgement
            mutate(ContentValues().apply { put(Events.TITLE, "Offline transferred series edit"); put(Events.DIRTY, 1)
                put(Events.RRULE, "COUNT=8;FREQ=DAILY"); put(Events.DURATION, if (allDay) "P3D" else "PT7200S") })
            addReminder(20)
            val native = providerSnapshot(); val rawRule = field(Events.RRULE); val rawDuration = field(Events.DURATION)
            assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcileTransferred()); assertNull(edit())
            assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
            val retained = edit()!!
            assertEquals(next, retained.base); assertEquals(SeriesEditProfile(CreateSeriesRule(4), CreateSeriesRule(8)), retained.seriesProfile)
            assertEquals(listOf(EventReminder(10), EventReminder(20)), retained.content.details!!.reminders)
            assertEquals(content.colorRgb, retained.content.colorRgb)
            assertEquals("Offline transferred series edit", retained.content.title)
            assertEquals(native, providerSnapshot()); assertEquals(rawRule, field(Events.RRULE)); assertEquals(rawDuration, field(Events.DURATION))
            assertEquals(frame, role()!!.frame); assertTrue(role()!!.waiting)
            assertEquals(original, baseline()); assertEquals(ack, role()!!.frame.receipt.acknowledgement); assertEquals(1, sends)
            reopen(); assertEquals(retained, edit())
            mutate(ContentValues().apply { put(Events.TITLE, "Newer untouched series edit") })
            assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
            assertEquals(retained, edit()); assertEquals("Newer untouched series edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        }
    }
    @Test fun explicitSeriesCaptureRejectsSimpleCreateAndNativeTombstone() = runBlocking {
        for (series in listOf(false, true)) fixture(series = series) {
            transferred()
            mutate(ContentValues().apply { put(Events.TITLE, "Unsupported local change"); put(Events.DIRTY, 1)
                if (series) put(Events.DELETED, 1) })
            val before = providerSnapshot()
            assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, captureSeriesEdit())
            assertNull(edit()); assertNull(deletion()); assertEquals(before, providerSnapshot())
        }
    }
    @Test fun explicitSeriesCaptureRefusesDetachedChildrenWithoutChangingTheMaster() = fixture(series = true) {
        transferred(); exception()
        mutate(ContentValues().apply { put(Events.TITLE, "Series with child"); put(Events.DIRTY, 1) })
        val before = providerSnapshot()
        assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, captureSeriesEdit())
        assertNull(edit()); assertEquals(before, providerSnapshot()); assertEquals(2, rows())
    }
    @Test fun explicitSeriesCaptureRechecksRevocationAfterNativeSnapshot() = runBlocking {
        for (reason in listOf("permissions", "account", "selection")) fixture(series = true) {
            transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Revoked series edit"); put(Events.DIRTY, 1) })
            afterHandoffSnapshot = {
                when (reason) {
                    "permissions" -> permitted = false
                    "account" -> coordinator.authenticationChanged(false)
                    else -> { allowStore(); store.setEnabled(target, false) }
                }
            }
            assertEquals(CapturedCreateReconciliationResult.INACTIVE, captureSeriesEdit()); assertNull(edit())
            assertEquals("Revoked series edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        }
    }
    @Test fun explicitSeriesCaptureRefusesSourceOrRoleChangedDuringNativeSnapshot() = runBlocking {
        for (sourceMissing in listOf(false, true)) fixture(series = true) {
            transferred(); val frame = role()!!.frame
            mutate(ContentValues().apply { put(Events.TITLE, "Stale series edit"); put(Events.DIRTY, 1) })
            afterHandoffSnapshot = {
                if (sourceMissing) expectationAvailable = false
                else { allowStore(); assertTrue(store.waitCapturedHandoff(frame)) }
            }
            assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, captureSeriesEdit()); assertNull(edit())
            assertEquals("1", field(Events.DIRTY)); assertEquals(1, sends)
        }
    }
    @Test fun explicitSeriesCaptureKeepsNewerNativeEditMadeAfterItsAssertionBatch() = fixture(series = true) {
        currentTransferred(); mutate(ContentValues().apply { put(Events.TITLE, "First series edit"); put(Events.DIRTY, 1) })
        afterHandoffSnapshot = { mutate(ContentValues().apply { put(Events.TITLE, "Newer series edit") }) }
        assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
        assertEquals("First series edit", edit()!!.content.title); assertEquals("Newer series edit", field(Events.TITLE))
        assertEquals("1", field(Events.DIRTY)); assertEquals(1, sends)
    }
    @Test fun explicitSeriesCapturePreservesARemoteConflictAcrossReopening() = fixture(series = true) {
        transferred(); val base = role()!!.frame.base
        mutate(ContentValues().apply { put(Events.TITLE, "Conflicting series edit"); put(Events.DIRTY, 1) })
        val remote = base.copy(revision = Revision("concurrent-series"), content = base.content.copy(title = "Concurrent remote series"))
        afterHandoffSnapshot = { incoming(remote) }
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, captureSeriesEdit())
        val retained = edit()!!; assertEquals(base, retained.base); assertEquals(LocalEditState.CONFLICT, retained.state)
        reopen(); assertEquals(retained, edit()); assertEquals(remote, remoteRecord()); assertTrue(role()!!.waiting)
        allowStore()
        val archive = store.conflictArchives(ConflictArchiveQuery(target)).entries.single()
        assertEquals(retained.seriesProfile, store.conflictArchive(archive)!!.seriesProfile)
    }
    @Test fun explicitSeriesCaptureRollsBackTheIntentWhenItsHandoffWaitCannotCommit() = fixture(series = true) {
        currentTransferred(); mutate(ContentValues().apply { put(Events.TITLE, "Interrupted series capture"); put(Events.DIRTY, 1) })
        sql { it.execSQL("CREATE TRIGGER fixture_fail_series_wait BEFORE UPDATE OF waiting ON captured_create_handoffs " +
            "WHEN NEW.waiting=1 BEGIN SELECT RAISE(ABORT, 'fixture wait interrupted'); END") }
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, captureSeriesEdit()); assertNull(edit()); assertFalse(role()!!.waiting)
        sql { it.execSQL("DROP TRIGGER fixture_fail_series_wait") }
        assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit()); assertNotNull(edit()); assertTrue(role()!!.waiting)
        assertEquals("1", field(Events.DIRTY)); assertEquals(1, sends)
    }
    @Test fun explicitSeriesCaptureRejectsReusedCreateIdsAndAnotherEditOwner() = fixture(series = true) {
        currentTransferred(); mutate(ContentValues().apply { put(Events.TITLE, "Owned series edit"); put(Events.DIRTY, 1) })
        for (request in listOf(id, attempt, MutationId(localId.value))) {
            assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, captureSeriesEdit(request)); assertNull(edit())
        }
        assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
        val retained = edit()!!
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, captureSeriesEdit(MutationId("another-series-edit")))
        assertEquals(retained, edit())
    }
    @Test fun explicitSeriesStoreRetryCannotReinterpretARetainedSimpleProfile() = fixture(series = true) {
        currentTransferred(); mutate(ContentValues().apply { put(Events.TITLE, "Frozen series profile"); put(Events.DIRTY, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, captureSeriesEdit())
        val retained = edit()!!
        val snapshot = CapturedHandoffCurrent(role()!!.frame, retained.content, false)
        sql { assertEquals(1, it.update("local_edits", ContentValues().apply { put("edit_profile", 1) }, null, null)) }
        allowStore(); var refused = false
        try { store.retainCapturedHandoffSeriesEdit(snapshot, mutation) } catch (_: IllegalStateException) { refused = true }
        assertTrue(refused)
        sql {
            it.rawQuery("SELECT edit_profile FROM local_edits", null).use { row -> assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0)) }
            assertEquals(1, it.update("local_edits", ContentValues().apply { put("edit_profile", 2) }, null, null))
        }
        assertEquals(retained, edit()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun explicitSeriesStorePortCannotAdoptSimpleOriginsOrDisabledScopes() = runBlocking {
        for (series in listOf(false, true)) fixture(series = series) {
            transferred(); val frame = role()!!.frame
            val snapshot = CapturedHandoffCurrent(frame, frame.base.content.copy(title = "Unqualified store edit"), false)
            if (series) { allowStore(); store.setEnabled(target, false) }
            allowStore(); var refused = false
            try { store.retainCapturedHandoffSeriesEdit(snapshot, mutation) }
            catch (_: IllegalArgumentException) { refused = true } catch (_: IllegalStateException) { refused = true }
            assertTrue(refused); assertNull(edit())
            sql { db -> db.rawQuery("SELECT waiting FROM captured_create_handoffs", null).use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            } }
        }
    }
    @Test fun seriesTransfersAndProjectsANewerCanonicalRuleAfterReopen() = fixture(series = true) {
        transferred(); assertEquals(verifiedEvent, role()!!.frame.base)
        reopen()
        val next = verifiedEvent.copy(revision = Revision("series-next"), content = verifiedEvent.content.copy(title = "Incoming series title",
            recurrence = verifiedEvent.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")))
        incoming(next); drain()
        assertEquals(next, role()!!.frame.base); assertEquals("FREQ=DAILY;COUNT=4", field(Events.RRULE))
        assertEquals("PT3600S", field(Events.DURATION)); assertEquals(1, sends)
    }
    @Test fun allDaySeriesKeepsPersonalMetadataAcrossIncomingDurationAndRuleChanges() = fixture(series = true, allDay = true, richOrigin = true) {
        transferred(); val time = verifiedEvent.content.time as EventTime.AllDay
        val next = verifiedEvent.copy(revision = Revision("series-all-day-next"), content = verifiedEvent.content.copy(
            time = time.copy(endExclusive = time.endExclusive.plusDays(1)), recurrence = verifiedEvent.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")))
        incoming(next); drain(); reopen()
        assertEquals(next, role()!!.frame.base); assertEquals("P3D", field(Events.DURATION))
        assertEquals(content.details, role()!!.frame.base.content.details); assertEquals(content.colorRgb, role()!!.frame.base.content.colorRgb)
        val inspector = AndroidCalendarProjection(context)
        assertNotNull(inspector.inspectCapturedHandoffBase(role()!!.frame))
        val second = next.copy(revision = Revision("series-all-day-second"), content = next.content.copy(title = "Second incoming series title"))
        incoming(second); drain()
        assertEquals(second, role()!!.frame.base); assertNotNull(inspector.inspectCapturedHandoffBase(role()!!.frame))
        assertEquals(1, sends)
    }
    @Test fun historicalSeriesRecoveryUsesTheOriginalCapsuleAfterAnIncomingProjection() = fixture(series = true) {
        titleMode = true; transferred()
        val next = verifiedEvent.copy(revision = Revision("series-advanced"), content = verifiedEvent.content.copy(title = "Advanced series"))
        incoming(next); drain(); reopen()
        val recovered = coordinator.recoverHistoricalSystemCreate(target, row, field(Events.SYNC_DATA5), null)
        assertEquals(localId, recovered.work!!.localId); assertEquals(attempt, recovered.work!!.attempt)
        assertEquals(next, role()!!.frame.base); assertEquals(1, sends)
        expectationAvailable = false
        assertNull(coordinator.recoverHistoricalSystemCreate(target, row, field(Events.SYNC_DATA5), null).work)
    }
    @Test fun seriesCapsuleUnavailableBeforeTransferCannotReserveARole() = fixture(series = true) {
        ready(); acknowledge(); expectationAvailable = false
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertNull(role())
        assertEquals("0", field(Events.DIRTY)); assertEquals(1, sends)
    }
    @Test fun changedSubmissionDuringSeriesTransferCannotReserveARole() = fixture(series = true) {
        ready(); acknowledge()
        beforeExpectation = { journal.writableDatabase.execSQL("UPDATE local_create_submissions SET payload=?", arrayOf("synthetic-altered")) }
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertNull(role())
    }
    @Test fun seriesPermissionLossDuringCapsuleReadPreventsTransfer() = fixture(series = true) {
        ready(); acknowledge(); beforeExpectation = { permitted = false }
        assertEquals(CapturedCreateHandoffResult.INACTIVE, handoff()); assertNull(role())
    }
    @Test fun unsupportedIncomingSeriesIdentityRulesAndExclusionsStayPending() {
        for (kind in 0..2) fixture(series = true) {
            transferred(); val recurrence = verifiedEvent.content.recurrence!!
            val next = verifiedEvent.copy(revision = Revision("unsupported-series"), content = verifiedEvent.content.copy(recurrence = when (kind) {
                0 -> recurrence.copy(uid = "foreign-series@calendeer.invalid")
                1 -> recurrence.copy(rule = "FREQ=WEEKLY;COUNT=5")
                else -> recurrence.copy(excludedDates = listOf(RecurrencePoint.Timed((content.time as EventTime.Timed).start)))
            }))
            val before = providerSnapshot(); incoming(next); drain()
            assertEquals(before, providerSnapshot()); assertEquals(verifiedEvent, role()!!.frame.base)
            assertEquals(1, rawPending()); assertEquals(0, handoffWrites)
        }
    }
    @Test fun incomingSeriesWriteResponseLossRecoversTheSameReceiptWithoutRewriting() = fixture(series = true) {
        transferred(); val next = verifiedEvent.copy(revision = Revision("series-response-lost"), content = verifiedEvent.content.copy(title = "Incoming recovered series"))
        incoming(next); afterHandoffWrite = { throw IllegalStateException("Synthetic lost series projection response") }
        interruptedDrain(); val saved = prepared()!!
        assertEquals(verifiedEvent, currentBase()); assertEquals(next.content.title, field(Events.TITLE))
        afterHandoffWrite = {}; reopen(); drain()
        assertEquals(next, role()!!.frame.base); assertEquals(saved, role()!!.frame.lastProjection)
        assertEquals(1, handoffWrites); assertEquals(1, sends)
    }
    @Test fun dirtySeriesEditsAndTombstonesStayVisibleAndCannotEnterSimpleWriters() {
        for (deleted in listOf(false, true)) fixture(series = true) {
            transferred(); incoming(verifiedEvent)
            mutate(ContentValues().apply { put(Events.DIRTY, 1); if (deleted) put(Events.DELETED, 1) else put(Events.TITLE, "Later unsupported series edit") })
            val before = providerSnapshot()
            assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, reconcileTransferred())
            assertEquals(before, providerSnapshot()); assertTrue(role()!!.waiting); assertNull(edit()); assertNull(deletion())
            assertEquals(1, sends)
        }
    }
    @Test fun seriesExceptionBetweenPreflightAndWriteKeepsThePendingProjection() = fixture(series = true) {
        transferred(); incoming(verifiedEvent.copy(revision = Revision("after-exception"), content = verifiedEvent.content.copy(title = "Must wait")))
        beforeHandoffWrite = { exception() }
        drain()
        assertEquals(content.title, field(Events.TITLE)); assertEquals(2, rows()); assertTrue(role()!!.waiting)
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); assertEquals(1, rawPending())
    }
    @Test fun personalCreationKeepsItsMetadataThroughUploadAcknowledgementReopenAndNextEdit() = fixture(safePersonal = true, richOrigin = true) {
        transferred(); incoming(verifiedEvent); titleMode = true; personalMode = true
        val original = record()
        assertEquals(1, sends); assertEquals("0", field(Events.DIRTY))
        assertEquals(content, role()!!.frame.base.content)
        reopen(); titleMode = true; personalMode = true
        assertEquals(content, role()!!.frame.base.content)
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, "Personal creation next edit") })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred())
        assertEquals(content.details, edit()!!.content.details)
        assertEquals(content.colorRgb, edit()!!.content.colorRgb)
        assertEquals(original, record()); assertEquals(1, sends)
    }
    @Test fun nativeCalendarColorFallbackCanBeAcknowledgedWithoutRewritingColor() = fixture {
        mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0) })
        transferred()
        assertEquals("0", field(Events.EVENT_COLOR)); assertEquals("0", field(Events.DIRTY))
        assertNull(role()!!.frame.base.content.colorRgb)
    }
    @Test fun transferredMetadataUsesThePersonalOwnerAndAcknowledgesThroughRealSqlAndProvider() = transferredMetadata(false)
    @Test fun registeredKeySurvivesTransferredCaptureAndAcknowledgementThroughSqlAndProvider() = transferredMetadata(true)
    private fun transferredMetadata(keyBased: Boolean) = fixture(safePersonal = true) {
        transferred(); incoming(verifiedEvent); titleMode = true; personalMode = true
        val database = "personal-handoff-ack-${UUID.randomUUID()}.db"
        try {
            TitleUpdateAttemptStore(context, database).use { attempts ->
                val origin = record()
                addReminder()
                if (keyBased) {
                    val color = com.patmanak.proton.andcalendarbridge.protonauth.ProtonCalendarEventColors.colors().first()
                    val account = projection.accountFor(target.account)
                    val colorUri = CalendarContract.Colors.CONTENT_URI.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Colors.ACCOUNT_NAME, account.name)
                        .appendQueryParameter(CalendarContract.Colors.ACCOUNT_TYPE, account.type).build()
                    context.contentResolver.insert(colorUri, ContentValues().apply {
                        put(CalendarContract.Colors.COLOR_KEY, color.key); put(CalendarContract.Colors.COLOR_TYPE, CalendarContract.Colors.TYPE_EVENT)
                        put(CalendarContract.Colors.COLOR, color.rgb or 0xff000000.toInt())
                    }) ?: error("Synthetic color insertion failed")
                    insertedPalette = true
                    mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.EVENT_COLOR_KEY, color.key) })
                } else mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.EVENT_COLOR, 0xff123456.toInt()) })
                if (keyBased) {
                    projection.admitOperation = { true }
                    assertTrue("Native registered-key handoff must be supported",
                        projection.captureCapturedHandoffCurrent(role()!!.frame) is CapturedHandoffCurrentObservation.Supported)
                }
                assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred())
                val local = checkNotNull(edit())
                val admission = coordinator.systemPersonalRunAdmission(target)
                assertEquals(local, coordinator.personalOwners.observe(target, verifiedEvent.eventId, local.id, admission)!!.intent)
                val prep = PersonalEditPreparation(local, timestamp, AdapterFormat("synthetic-personal", 2), "opaque-personal")
                assertNotNull(coordinator.personalOwners.retain(prep, admission))
                val wire = PersonalUpdateAttemptRecord(MutationId("handoff-personal-request"), prep, AdapterFormat("synthetic-personal-wire", 2), "opaque-wire")
                val journal = attempts.personalJournal
                assertTrue(journal.remember(wire) { true }); assertTrue(journal.claim(wire) { true })
                val unknown = wire.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
                val result = PersonalUpdateVerifiedResult.of(unknown, PersonalUpdateSubmissionObservation.Verified(unknown,
                    local.base.copy(revision = Revision("personal-verified"), content = local.content)))
                assertTrue(journal.retainVerified(result) { true })
                assertTrue(coordinator.personalOwners.acknowledge(result, journal, coordinator.systemPersonalProofAdmission(target)))
                assertTrue(coordinator.personalOwners.complete(result, journal) { true })
                assertEquals("0", field(Events.DIRTY)); assertEquals(local.content, role()!!.frame.base.content)
                if (keyBased) assertNotNull(field(Events.EVENT_COLOR_KEY))
                assertEquals(origin, record()); assertEquals(1, sends)
                reopen()
                assertTrue(coordinator.personalOwners.acknowledge(result, journal, coordinator.systemPersonalProofAdmission(target)))
                val nextColor = if (keyBased) 0x258723 else 0x654321
                mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.EVENT_COLOR, nextColor or 0xff000000.toInt()) })
                if (keyBased) {
                    assertEquals(CapturedCreateReconciliationResult.UNSUPPORTED, coordinator.reconcileHandoffLocalCreate(target,
                        localId, id, attempt, MutationId("personal-second-owner")))
                    assertNull(edit()); assertEquals("1", field(Events.DIRTY))
                    mutate(ContentValues().apply { putNull(Events.EVENT_COLOR_KEY) })
                }
                assertEquals(CapturedCreateReconciliationResult.RETAINED, coordinator.reconcileHandoffLocalCreate(target,
                    localId, id, attempt, MutationId("personal-second-owner")))
                val next = checkNotNull(edit())
                assertEquals(local.content, next.base.content)
                assertEquals(nextColor, next.content.colorRgb)
                assertNotNull(coordinator.personalOwners.observe(target, verifiedEvent.eventId, next.id,
                    coordinator.systemPersonalRunAdmission(target)))
                assertEquals(origin, record()); assertEquals(1, sends)
            }
        } finally { context.deleteDatabase(database) }
    }
    @Test fun missingSystemWorkCanRecoverOnlyTheCompleteHistoricalCreationAndSurviveRestart() = fixture {
        transferred(); titleMode = true
        val marker = field(Events.SYNC_DATA5)
        val before = providerSnapshot(); val original = record(); val savedRole = role()
        val recovered = coordinator.recoverHistoricalSystemCreate(target, row, marker, null)
        assertNotNull(recovered.work)
        assertEquals(localId, recovered.work!!.localId); assertEquals(id, recovered.work!!.captureId)
        assertEquals(attempt, recovered.work!!.attempt)
        assertEquals(com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus.COMPLETE, recovered.work!!.status)
        assertEquals(before, providerSnapshot()); assertEquals(original, record()); assertEquals(savedRole, role())
        reopen(); titleMode = true
        assertEquals(recovered, coordinator.recoverHistoricalSystemCreate(target, row, marker, null))
        assertEquals(1, sends); assertEquals(1, ackAttempts); assertEquals(before, providerSnapshot())
    }

    @Test fun historicalRecoveryRefusesUnrelatedMarkerRowAndScopeWithoutChangingAnything() = fixture {
        transferred(); titleMode = true
        val marker = field(Events.SYNC_DATA5); val before = providerSnapshot(); val original = record()
        assertNull(coordinator.recoverHistoricalSystemCreate(target, row, "unrelated", null).work)
        assertNull(coordinator.recoverHistoricalSystemCreate(target, row + 1, marker, null).work)
        assertNull(coordinator.recoverHistoricalSystemCreate(target.copy(generation = target.generation + 1), row, marker, null).work)
        assertEquals(before, providerSnapshot()); assertEquals(original, record()); assertEquals(1, sends)
    }

    @Test fun partialAndMissingHistoricalProofRemainHeldAcrossReopen() = fixture {
        ready(); titleMode = true
        val marker = field(Events.SYNC_DATA5); val before = providerSnapshot(); val original = record()
        assertNull(coordinator.recoverHistoricalSystemCreate(target, row, marker, null).work)
        acknowledge(); handoff(); loseDispatch(); val captured = providerSnapshot()
        assertNull(coordinator.recoverHistoricalSystemCreate(target, row, marker, null).work)
        reopen(); titleMode = true
        val result = coordinator.recoverHistoricalSystemCreate(target, row, marker, null)
        assertNull(result.work)
        assertEquals(com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateIssueReason.INCOMPLETE_PROOF, result.reason)
        assertEquals(captured, providerSnapshot()); assertNull(record()); assertNotNull(original); assertEquals(1, sends)
        assertNotEquals(before, captured)
    }

    @Test fun corruptHistoricalSubmissionCannotRepairWorkOrIssueAnotherRequest() = fixture {
        transferred(); titleMode = true
        val before = providerSnapshot(); val original = record()
        journal.writableDatabase.execSQL("UPDATE local_create_submissions SET captured_content='corrupt'")
        val result = coordinator.recoverHistoricalSystemCreate(target, row, field(Events.SYNC_DATA5), null)
        assertNull(result.work)
        assertEquals(com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateIssueReason.INCOMPLETE_PROOF, result.reason)
        assertEquals(before, providerSnapshot()); assertEquals(original, record()); assertEquals(1, sends)
    }

    private class TitlePreparationPort(private val wire: TitleUpdateAttemptRecord,
        private val duringPreparation: suspend () -> Unit = {}) : CalendarTitleUpdateSubmissionPort {
        var calls = 0
        override val titleUpdateFormat = wire.format
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
            error("No unrestricted title preparation")
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
            check(admit(reservation.scope)); calls++; duringPreparation()
            return wire.copy(id = reservation.id)
        }
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
            error("No title dispatch")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No title inspection")
    }
    private suspend fun Fixture.withTitlePreparation(block: suspend (TitleUpdatePreparationReservation,
        TitleUpdateAttemptRecord, com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore) -> Unit) {
        transferred(); incoming(verifiedEvent); titleMode = true
        mutate(ContentValues().apply { put(Events.TITLE, "Synthetic handoff title edit"); put(Events.DIRTY, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred())
        val preparation = LocalEditPreparation(checkNotNull(edit()), timestamp,
            AdapterFormat("controlled-handoff-title-preparation", 1), "synthetic-handoff-title-capsule")
        allowStore(); assertTrue(store.retainLocalEditPreparation(preparation))
        val wire = TitleUpdateAttemptRecord(MutationId("synthetic-handoff-title-attempt"), preparation,
            AdapterFormat("controlled-handoff-title-wire", 1), "synthetic-handoff-title-wire")
        val name = "handoff-title-preparation-${UUID.randomUUID()}.db"
        try {
            com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore(context, name).use {
                block(TitleUpdatePreparationReservation.of(wire), wire, it)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun guardedTitlePreparationUsesTransferredProvenanceAndPreservesNativeRow() = fixture {
        withTitlePreparation { candidate, wire, attempts ->
            val before = providerSnapshot(); val original = record(); val currentRole = role()
            val port = TitlePreparationPort(wire)
            assertEquals(LocalTitleAttemptPreparation.Prepared(wire),
                coordinator.prepareLocalTitleAttempt(candidate, attempts, port, allowNetwork = true))
            assertEquals(1, port.calls); assertEquals(wire, attempts.read(target, wire.id))
            assertEquals(before, providerSnapshot()); assertEquals(original, record()); assertEquals(currentRole, role())
            assertEquals(LocalTitleAttemptPreparation.Prepared(wire),
                coordinator.prepareLocalTitleAttempt(candidate.copy(id = MutationId("another-proposed-id")), attempts, port))
            assertEquals(1, port.calls); assertEquals(before, providerSnapshot()); assertEquals(1, sends)
        }
    }
    @Test fun guardedTitlePreparationHoldsTransferredWorkAfterProvenanceLoss() = fixture {
        withTitlePreparation { candidate, wire, attempts ->
            val before = providerSnapshot(); val owner = edit()
            val port = TitlePreparationPort(wire) { journal.writableDatabase.execSQL("DELETE FROM local_create_submissions") }
            assertEquals(LocalTitleAttemptPreparation.Unresolved,
                coordinator.prepareLocalTitleAttempt(candidate, attempts, port, allowNetwork = true))
            assertEquals(1, port.calls); assertNull(attempts.read(target, wire.id))
            assertEquals(candidate, attempts.readPreparationReservation(target, wire.preparation.intent.base.eventId))
            assertEquals(before, providerSnapshot()); assertEquals(owner, edit()); assertEquals(1, sends)
        }
    }
    @Test fun guardedTitlePreparationPreservesALaterTransferredNativeEdit() = fixture {
        withTitlePreparation { candidate, wire, attempts ->
            val owner = edit()
            val port = TitlePreparationPort(wire) {
                mutate(ContentValues().apply { put(Events.TITLE, "Synthetic later handoff edit"); put(Events.DIRTY, 1) })
            }
            assertEquals(LocalTitleAttemptPreparation.Unresolved,
                coordinator.prepareLocalTitleAttempt(candidate, attempts, port, allowNetwork = true))
            assertEquals(1, port.calls); assertNull(attempts.read(target, wire.id)); assertEquals(owner, edit())
            assertEquals("Synthetic later handoff edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
            assertTrue(role()!!.waiting); assertEquals(1, sends)
        }
    }
    @Test fun cleanOriginalCanTransferWithoutAnotherAcknowledgementOrSubmission() = fixture {
        ready(); acknowledge(); incoming(verifiedEvent)
        val before = providerSnapshot()
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, coordinator.handoffLocalCreate(target, localId, id, attempt))
        assertEquals(before, providerSnapshot()); assertEquals(1, sends); assertEquals(1, ackAttempts)
        assertEquals(1, rawPending()); assertHold()
    }
    @Test fun incomingUpdateAdvancesCurrentReferenceWithoutChangingOriginalOrDuplicating() = fixture {
        transferred(); val original = role()!!.frame.receipt; val markers = providerSnapshot().filterKeys { it in setOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7) }
        val newer = next(); incoming(newer); assertEquals(ImportRunResult.COMPLETE, drain())
        assertEquals(newer, currentBase()); assertEquals(original, role()!!.frame.receipt); assertEquals(newer, role()!!.frame.base)
        assertEquals(markers, providerSnapshot().filterKeys { it in markers.keys }); assertEquals(1, rows()); assertEquals(0, rawPending())
        assertEquals(1, handoffWrites); assertNull(prepared()); assertEquals(1, sends); assertEquals(1, ackAttempts); assertHold()
    }
    @Test fun reopenedTransferUsesAdvancedReferenceAndCannotRestoreOriginal() = fixture {
        transferred(); val newer = next(); incoming(newer); drain(); val saved = role(); val before = providerSnapshot(); reopen()
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(saved, role()); assertEquals(before, providerSnapshot())
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify())
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertEquals(newer, currentBase()); assertEquals(1, ackAttempts)
    }
    @Test fun dirtyOriginalLeavesPendingTransferAndIncomingIntact() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.TITLE, "Original local change"); put(Events.DIRTY, 1) }); incoming(next())
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertEquals(CapturedCreateHandoffState.PENDING, role()!!.state)
        drain(); assertEquals("Original local change", field(Events.TITLE)); assertEquals(1, rawPending()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun editAfterInitialCleanObservationCannotBeOverwrittenByTransfer() = fixture {
        ready(); acknowledge(); afterClean = { afterClean = {}; mutate(ContentValues().apply { put(Events.TITLE, "After observation"); put(Events.DIRTY, 1) }) }
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); incoming(next()); drain()
        assertEquals("After observation", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
        assertEquals(0, handoffWrites); assertEquals(0, continuations.get()); assertEquals(1, rawPending())
    }
    @Test fun promotionFailureReopensTheSamePendingTransferAndDoesNotReAck() = fixture {
        ready(); acknowledge(); afterClean = { failPromotion(true) }
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); val saved = role()!!.frame.receipt
        failPromotion(false); afterClean = {}; reopen(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff())
        assertEquals(saved, role()!!.frame.receipt); assertEquals(1, ackAttempts); assertEquals(1, sends)
    }
    @Test fun lostProviderResponseRecoversHistoricallyWithoutAnotherWriteOrCleaningLaterEdit() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { throw IllegalStateException("Controlled lost handoff response") }; drain()
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); mutate(ContentValues().apply { put(Events.TITLE, "After committed write"); put(Events.DIRTY, 1) })
        afterHandoffWrite = {}; reopen(); assertEquals(CapturedCreateHandoffResult.WAITING, handoff())
        assertEquals(newer, currentBase()); assertNull(prepared()); assertEquals(1, handoffWrites); assertEquals("After committed write", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); assertEquals(newer, edit()!!.base)
    }
    @Test fun lostSqlRestoreRecoversTheWrittenReferenceAfterReopen() = fixture {
        transferred(); val newer = next(); incoming(newer); beforeHandoffWrite = { failRestore(true) }; drain()
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); assertEquals(newer.content.title, field(Events.TITLE))
        failRestore(false); beforeHandoffWrite = {}; reopen(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff())
        assertEquals(newer, currentBase()); assertNull(prepared()); assertEquals(1, handoffWrites); assertEquals(0, rawPending())
    }
    @Test fun newerPendingAfterWriteSurvivesRestoreAndProjectsOnNextRun() = fixture {
        transferred(); val first = next(); val second = next("Second remote version", "second-reference"); incoming(first)
        afterHandoffWrite = { afterHandoffWrite = {}; incoming(second) }
        assertEquals(ImportRunResult.CONTINUE, drain()); assertEquals(first, currentBase()); assertEquals(second, remoteRecord()); assertEquals(1, rawPending())
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(second, currentBase()); assertEquals(second.content.title, field(Events.TITLE)); assertEquals(2, handoffWrites); assertEquals(1, rows())
    }
    @Test fun recoveryReloadsANewerPendingInsteadOfReusingTheOldLoadedIntent() = fixture {
        transferred(); val first = next(); incoming(first); afterHandoffWrite = { throw IllegalStateException("Lost old response") }; drain()
        afterHandoffWrite = {}; val second = next("Newer before recovery", "second-reference"); incoming(second); reopen()
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(second, currentBase()); assertEquals(second.content.title, field(Events.TITLE)); assertEquals(2, handoffWrites); assertNull(prepared())
    }
    @Test fun localEditAfterWriteUsesAdvancedBaseAndPreservesHistoricalMarkers() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { afterHandoffWrite = {}; mutate(ContentValues().apply { put(Events.TITLE, "Later local intention"); put(Events.DIRTY, 1) }) }; drain()
        val before = providerSnapshot(); assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred())
        assertEquals(newer, edit()!!.base); assertEquals("Later local intention", edit()!!.content.title); assertEquals(before, providerSnapshot()); assertTrue(role()!!.waiting)
    }
    @Test fun remoteDeletionRemainsHeldAndDoesNotRemoveTheOriginal() = fixture {
        transferred(); incoming(RemoteChange.Deleted(verifiedEvent.eventId, Revision("deleted"))); val before = providerSnapshot()
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(before, providerSnapshot()); assertEquals(1, rawPending()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun unknownRemoteDetailsRemainHeldWithoutDiscardingThem() = fixture {
        transferred(); val unknown = next().copy(content = content.copy(details = null)); incoming(unknown)
        drain(); assertEquals(unknown, remoteRecord()); assertEquals(content.title, field(Events.TITLE)); assertEquals(1, rawPending()); assertEquals(0, handoffWrites)
    }
    @Test fun corruptedPreparedReceiptRemainsHeldWhileOtherEventsDrain() = fixture {
        transferred(); incoming(next()); allowStore(); val frame = role()!!.frame
        store.prepareCapturedHandoffProjection(frame, ProjectionIntent(target, next())); sql { it.execSQL("UPDATE projection_receipts SET payload='corrupt'") }
        val other = next("Ordinary beside corrupt receipt").copy(eventId = EventId("ordinary")); incoming(other)
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(2, rows()); assertEquals(content.title, field(Events.TITLE)); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun missingDispatchCannotReturnCapturedRowsToOrdinaryProjection() = fixture {
        transferred(); loseDispatch(); incoming(next(), next("Ordinary beside missing dispatch").copy(eventId = EventId("ordinary")))
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(2, rows()); assertEquals(content.title, field(Events.TITLE)); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun permissionLossBeforeTransferDoesNotReserveARole() = fixture {
        ready(); acknowledge(); permitted = false; assertEquals(CapturedCreateHandoffResult.INACTIVE, handoff())
        assertNull(role()); assertEquals(0, cleanInspections)
    }
    @Test fun permissionLossAfterInitialInspectionKeepsOnlyPendingAdmission() = fixture {
        ready(); acknowledge(); afterClean = { permitted = false }
        assertEquals(CapturedCreateHandoffResult.INACTIVE, handoff()); assertEquals(CapturedCreateHandoffState.PENDING, role()!!.state)
    }
    @Test fun permissionLossAfterWriteCannotAdvanceSqlUntilHistoricalRecovery() = fixture {
        transferred(); val newer = next(); incoming(newer); afterHandoffWrite = { permitted = false }; interruptedDrain()
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(newer.content.title, field(Events.TITLE))
        permitted = true; afterHandoffWrite = {}; assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(newer, currentBase()); assertEquals(1, handoffWrites)
    }
    @Test fun disablingBetweenProviderAndSqlPreventsConsumption() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { coordinator.setEnabled(coordinator.state.value.selections.single { it.scope == target }, false) }; interruptedDrain()
        withTimeout(5_000) { while (store.selections(target.account).single { it.scope == target }.enabled) delay(10) }
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(1, rawPending()); assertEquals(newer.content.title, field(Events.TITLE))
    }
    @Test fun signOutBetweenProviderAndSqlPreservesPreparedEvidence() = fixture {
        transferred(); incoming(next()); afterHandoffWrite = { coordinator.authenticationChanged(false) }; interruptedDrain()
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(1, rawPending()); assertEquals(1, handoffWrites)
    }
    @Test fun dirtyPreflightWaitsWithoutPreparingAndOtherEventsContinue() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Offline dirty"); put(Events.DIRTY, 1) })
        incoming(next(), next("Ordinary while dirty").copy(eventId = EventId("ordinary"))); drain()
        assertEquals("Offline dirty", field(Events.TITLE)); assertEquals(2, rows()); assertTrue(role()!!.waiting); assertNull(prepared()); assertEquals(0, continuations.get())
        drain(); assertEquals(0, continuations.get()); assertEquals(0, handoffWrites)
    }
    @Test fun editBetweenPreflightAndConditionalWriteKeepsPreparedEvidenceAndWaits() = fixture {
        transferred(); incoming(next()); beforeHandoffWrite = { mutate(ContentValues().apply { put(Events.TITLE, "Racing local edit"); put(Events.DIRTY, 1) }) }
        drain(); assertEquals("Racing local edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); assertEquals(1, rawPending()); assertEquals(0, continuations.get())
    }
    @Test fun missingOriginalNeverInsertsAndWaitsWithoutContinuations() = fixture {
        transferred(); context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())); incoming(next()); drain()
        assertEquals(0, rows()); assertTrue(role()!!.waiting); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun newReminderIsPreservedAndHeld() = fixture {
        transferred(); addReminder(); val before = providerSnapshot(); incoming(next()); drain()
        assertEquals(before, providerSnapshot()); assertTrue(role()!!.waiting); assertNull(prepared()); assertEquals(0, handoffWrites)
    }
    @Test fun laterEditRetainsImmutableCurrentBaseAndPendingRemote() = fixture {
        transferred(); val current = next(); incoming(current); drain(); mutate(ContentValues().apply { put(Events.TITLE, "Captured later"); put(Events.DIRTY, 1) })
        val pending = next("Concurrent remote", "concurrent"); incoming(pending)
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcileTransferred()); val saved = edit(); val before = providerSnapshot()
        assertEquals(current, saved!!.base); assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcileTransferred()); assertEquals(saved, edit()); assertEquals(pending, remoteRecord()); assertEquals(1, rawPending()); assertEquals(before, providerSnapshot())
    }
    @Test fun presentTombstoneIsCapturedAgainstCurrentBaseWithoutDeletingAndroid() = fixture {
        transferred(); val current = next(); incoming(current); drain(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); assertEquals(current, deletion()!!.base); assertEquals(1, rows()); assertEquals("1", field(Events.DELETED)); assertTrue(role()!!.waiting)
    }
    @Test fun waitingCanResumeOnlyAfterCurrentCleanReferenceReturnsWithoutAnIntention() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Temporary local change"); put(Events.DIRTY, 1) }); incoming(next()); drain(); assertTrue(role()!!.waiting)
        mutate(ContentValues().apply { put(Events.TITLE, content.title); put(Events.DIRTY, 0) }); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertFalse(role()!!.waiting)
        drain(); assertEquals(next(), currentBase()); assertEquals(1, handoffWrites)
    }
    @Test fun retainedIntentionCannotResumeEvenIfAndroidIsReverted() = fixture {
        transferred(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Retained local change"); put(Events.DIRTY, 1) }); reconcileTransferred(); val saved = edit()
        mutate(ContentValues().apply { put(Events.TITLE, content.title); put(Events.DIRTY, 0) }); handoff(); assertTrue(role()!!.waiting); assertEquals(saved, edit()); assertEquals(1, rawPending())
    }
    @Test fun genericCaptureCannotAdoptAReleasedCreation() = fixture {
        transferred(); incoming(verifiedEvent); drain(); mutate(ContentValues().apply { put(Events.TITLE, "Generic must refuse"); put(Events.DIRTY, 1) })
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalEdit(target, verifiedEvent.eventId, mutation)); assertNull(edit()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun interruptedTransferClosesAllCallbacksAndReusesItsReservation() = fixture {
        ready(); acknowledge(); beforeClean = { throw IllegalStateException("Interrupted transfer inspection") }; handoff()
        assertFalse(store.admitTransaction(target)); assertFalse(journal.admitCapture(target)); assertFalse(attachmentProjection.admitOperation())
        val saved = role()!!.frame.receipt; beforeClean = {}; assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(saved, role()!!.frame.receipt); assertEquals(1, ackAttempts)
    }
    @Test fun malformedRawEventIdentityBlocksScopeWithoutBusyContinuation() = fixture {
        transferred(); sql { it.execSQL("UPDATE captured_create_bases SET event=''") }; incoming(next(), next().copy(eventId = EventId("ordinary")))
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(1, rows()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun handoffRequiresExactCaptureAndAttemptAndAdapterFormat() = fixture {
        ready(); acknowledge()
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, coordinator.handoffLocalCreate(target, localId, MutationId("other"), attempt))
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, coordinator.handoffLocalCreate(target, localId, id, MutationId("other")))
        currentFormat = format.copy(version = 2); assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertNull(role()); assertEquals(0, cleanInspections)
    }
    @Test fun genericCaptureCannotReplayASpecializedRetainedIntention() = fixture {
        transferred(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Specialized retained edit"); put(Events.DIRTY, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); val saved = edit()
        mutate(ContentValues().apply { put(Events.TITLE, "Later than retained edit"); put(Events.DIRTY, 1) })
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalEdit(target, verifiedEvent.eventId, mutation))
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalDelete(target, verifiedEvent.eventId, mutation))
        assertEquals(saved, edit()); assertEquals("Later than retained edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
    }
    @Test fun heldDirtyCreationDoesNotStarveAFourHundredEventBacklogOrScheduleAnExtraContinuation() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Held during backlog"); put(Events.DIRTY, 1) })
        incoming(next(), *(1..400).map { next("Ordinary backlog $it").copy(eventId = EventId("ordinary-$it")) }.toTypedArray())
        var runs = 0
        var result: ImportRunResult
        do { result = drain(); runs++; assertTrue("Backlog must finish in bounded batches", runs <= 5) }
        while (result == ImportRunResult.CONTINUE)
        assertEquals(ImportRunResult.COMPLETE, result); assertTrue(runs in 4..5); assertEquals(runs - 1, continuations.get()); assertEquals(401, rows()); assertEquals(1, rawPending())
        assertEquals("Held during backlog", field(Events.TITLE)); assertTrue(role()!!.waiting); assertEquals(0, handoffWrites)
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(runs - 1, continuations.get())
    }
    private class Fixture(val context: Context, safePersonal: Boolean = false, val richOrigin: Boolean = false,
        val series: Boolean = false, val allDay: Boolean = false, colorMode: EventColorMode = EventColorMode.EVENT,
        val crypto: ProtonSeriesUpdatePipelineFixture? = null) {
        var insertedPalette = false
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-integration-import-$token.db"
        private val dispatchName = "captured-provider-dispatch-$token.db"
        private val journalName = "dispatch-integration-capture-$token.db"
        val target = crypto?.scope ?: CalendarScope(AccountId("dispatch-integration-$token"), CalendarId("fixture"), 0)
        val format = crypto?.format ?: AdapterFormat("dispatch-integration-import", 1)
        var currentFormat = format
        val preparationFormat = AdapterFormat("controlled-known-empty-submission", if (series) 4 else if (richOrigin) 3 else 2)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId(UUID.randomUUID().toString())
        val attempt = MutationId(UUID.randomUUID().toString())
        val timestamp = crypto?.timestamp ?: Instant.parse("2030-10-01T12:00:00Z")
        val content = crypto?.base?.content?.copy(recurrence = null) ?: EventContent("Preparation integration fixture",
            if (allDay) EventTime.AllDay(LocalDate.parse("2030-03-29"), LocalDate.parse("2030-03-31")) else
            if (series || safePersonal) EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneId.of("Europe/Paris")) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails(if (richOrigin) listOf(EventReminder(10)) else emptyList()),
            colorRgb = if (richOrigin) 0x123456 else null)
        val intent = LocalCreateIntent(LocalCreateReservation(id, localId, target, format, timestamp), content, if (series) crypto?.base?.content?.recurrence?.rule?.let(CreateSeriesRule::parse) ?: CreateSeriesRule(5) else null)
        val projection = AndroidCalendarProjection(context) { colorMode }
        var handoffWrites = 0
        var handoffInspections = 0
        var beforeHandoffWrite: suspend () -> Unit = {}
        var afterHandoffWrite: suspend () -> Unit = {}
        var beforeHandoffBase: suspend () -> Unit = {}
        var afterHandoffBase: suspend () -> Unit = {}
        var afterHandoffSnapshot: suspend () -> Unit = {}
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
            override suspend fun applyCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? {
                handoffWrites++; beforeHandoffWrite()
                val result = projection.applyCapturedHandoffProjection(plan)
                afterHandoffWrite(); return result
            }
            override suspend fun inspectCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? {
                handoffInspections++; return projection.inspectCapturedHandoffProjection(plan)
            }
            override suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? {
                beforeHandoffBase(); val result = projection.inspectCapturedHandoffBase(frame)
                afterHandoffBase(); return result
            }
            override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation {
                val result = projection.captureCapturedHandoffCurrent(frame); afterHandoffSnapshot(); return result
            }
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
        val verifiedEvent get() = crypto?.base ?: RemoteChange.Upsert(EventId("synthetic-remote"), Revision("synthetic-revision"),
            if (series) content.copy(recurrence = EventRecurrence("synthetic-series@calendeer.invalid", "FREQ=DAILY;COUNT=5")) else content)
        var expectationAvailable = true
        var beforeExpectation: suspend () -> Unit = {}
        var calls = 0
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, "synthetic-protected-preparation-$calls")
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat: AdapterFormat get() = preparationFormat.copy(version = 2)
            override val personalSubmissionFormat: AdapterFormat? get() = preparationFormat.copy(version = 3).takeIf { richOrigin }
            override val seriesSubmissionFormat: AdapterFormat? get() = preparationFormat.takeIf { series }
            override suspend fun readCapturedExpectation(record: CreateAttemptRecord, expected: CalendarCreateDraft): EventContent? {
                assertEquals(target, record.scope); assertEquals(attempt, record.id)
                beforeExpectation()
                return if (expectationAvailable) expected.canonicalContent(crypto?.base?.content?.recurrence?.uid ?: "synthetic-series@calendeer.invalid") else null
            }
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content, intent.series), draft)
                calls++; return onPrepare(draft)
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No generic upload")
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal): CreateSubmissionObservation {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content, intent.series), expected)
                beforeClaim()
                if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                sends++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), verifiedEvent)
            }
            override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
                inspections++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), verifiedEvent)
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No remote inspection")
        }
        val uri get() = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        private val remote = object : ReadOnlyCalendarRemote, CalendarCreateSubmissionPort by port {
            override val format get() = currentFormat
            override suspend fun currentAccount(): AccountId { onAccount(); return target.account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No imports")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        private val schedules = AtomicInteger()
        val continuations = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) { continuations.incrementAndGet() }
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var titleMode = false
        var personalMode = false
        var coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler,
            creates = creates, localCreates = journal, systemCreatesEnabled = { titleMode },
            systemTitlesEnabled = { personalMode && titleMode },
            systemTextsEnabled = { crypto != null && personalMode && titleMode },
            systemTimesEnabled = { crypto != null && personalMode && titleMode })
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer preparation integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            calendar = projection.ensureCalendar(target, summary)
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            if (richOrigin) addReminder(10)
            start()
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalCreate(target, row, localId, id, timestamp, series))
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        fun candidateValues() = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            if (richOrigin) put(Events.EVENT_COLOR, content.colorRgb!!.or(0xff000000.toInt())) else putNull(Events.EVENT_COLOR)
            putNull(Events.EVENT_COLOR_KEY)
            when (val time = content.time) {
                is EventTime.Timed -> {
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                }
                is EventTime.AllDay -> {
                    put(Events.DTSTART, time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                }
            }
            if (series) { put(Events.RRULE, intent.series!!.rule); put(Events.DURATION, duration(content.time)); putNull(Events.DTEND) }
        }
        fun exception() {
            context.contentResolver.insert(uri, candidateValues().apply {
                put(Events.TITLE, "Synthetic detached exception"); putNull(Events.RRULE); putNull(Events.DURATION)
                val start = getAsLong(Events.DTSTART)
                put(Events.DTEND, start + if (allDay) 172800000L else 3600000L)
                put(Events.ORIGINAL_ID, row); put(Events.ORIGINAL_INSTANCE_TIME, start); put(Events.ORIGINAL_ALL_DAY, if (allDay) 1 else 0)
            })!!
        }
        val mutation = MutationId("after-create-change")
        suspend fun handoff() = coordinator.handoffLocalCreate(target, localId, id, attempt)
        suspend fun role(): CapturedCreateHandoffRecord? { allowStore(); return store.capturedHandoff(target, verifiedEvent.eventId) }
        suspend fun prepared(): ProjectionReceipt? { allowStore(); return store.projectionReceipt(target, verifiedEvent.eventId) }
        suspend fun drain(): ImportRunResult {
            val result = coordinator.run(network = false)
            executeImportWork({ true }, { result }, { scheduler.continueWork(false) })
            return result
        }
        suspend fun interruptedDrain() {
            try { coordinator.run(network = false) }
            catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        }
        suspend fun transferred() { ready(); acknowledge(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()) }
        suspend fun currentTransferred() { transferred(); incoming(verifiedEvent); drain() }
        suspend fun reconcileTransferred() = coordinator.reconcileHandoffLocalCreate(target, localId, id, attempt, mutation)
        suspend fun captureSeriesEdit(request: MutationId = mutation) = coordinator.captureHandoffSeriesEdit(target, localId, id, attempt, request)
        fun sql(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(importName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
        fun failRestore(enabled: Boolean) = sql {
            if (enabled) it.execSQL("CREATE TRIGGER fixture_restore_failure BEFORE UPDATE OF payload ON projection_bases BEGIN SELECT RAISE(ABORT, 'Controlled restore failure'); END")
            else it.execSQL("DROP TRIGGER IF EXISTS fixture_restore_failure")
        }
        fun failPromotion(enabled: Boolean) = sql {
            if (enabled) it.execSQL("CREATE TRIGGER fixture_promote_failure BEFORE UPDATE OF state ON captured_create_handoffs BEGIN SELECT RAISE(ABORT, 'Controlled promotion failure'); END")
            else it.execSQL("DROP TRIGGER IF EXISTS fixture_promote_failure")
        }
        fun loseDispatch() = SQLiteDatabase.openDatabase(context.getDatabasePath(dispatchName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'", null).use { rows ->
                val tables = mutableListOf<String>(); while(rows.moveToNext()) tables += rows.getString(0)
                tables.forEach { table -> it.execSQL("DELETE FROM $table") }
            }
        }
        fun next(title: String = "Incoming after handoff", revision: String = "later-reference") = verifiedEvent.copy(revision = Revision(revision), content = content.copy(title = title))
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
        fun addReminder(minutes: Int = 30) {
            val child = CalendarContract.Reminders.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
            assertNotNull(context.contentResolver.insert(child, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, minutes)
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
        suspend fun incoming(vararg changes: RemoteChange) {
            val previous = store.admitTransaction; store.admitTransaction = { it == target }
            try { store.transaction(target) { changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) } } }
            finally { store.admitTransaction = previous }
        }
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
            coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler,
                creates = creates, localCreates = journal, systemCreatesEnabled = { titleMode },
                systemTitlesEnabled = { personalMode && titleMode },
            systemTextsEnabled = { crypto != null && personalMode && titleMode },
            systemTimesEnabled = { crypto != null && personalMode && titleMode }); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); creates.close(); context.deleteDatabase(dispatchName); context.deleteDatabase(importName); context.deleteDatabase(journalName)
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            if (insertedPalette) {
                val account = projection.accountFor(target.account)
                val colors = CalendarContract.Colors.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Colors.ACCOUNT_NAME, account.name)
                    .appendQueryParameter(CalendarContract.Colors.ACCOUNT_TYPE, account.type).build()
                context.contentResolver.delete(colors, "${CalendarContract.Colors.ACCOUNT_NAME}=? AND ${CalendarContract.Colors.ACCOUNT_TYPE}=?",
                    arrayOf(account.name, account.type))
            }
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(safePersonal: Boolean = false, richOrigin: Boolean = false, series: Boolean = false, allDay: Boolean = false,
        colorMode: EventColorMode = EventColorMode.EVENT, crypto: ProtonSeriesUpdatePipelineFixture? = null,
        block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), safePersonal, richOrigin, series, allDay, colorMode, crypto)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

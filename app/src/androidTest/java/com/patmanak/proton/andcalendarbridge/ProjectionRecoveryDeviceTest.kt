// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Attendees
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.experiments.FiniteDailyRecurrenceExperiment
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated production Provider/SQLite/coordinator recovery; controlled remote, no service writes. */
@RunWith(AndroidJUnit4::class)
class ProjectionRecoveryDeviceTest {
    /** Recovery proves the synthetic date view only; the canonical RRULE is not stored as its base. */
    @Test fun finiteDailyDateViewRecoversLostAcknowledgementWithoutClaimingCanonicalRuleProof() = runBlocking {
        for (spring in listOf(false, true)) for (dirty in listOf(false, true)) withFixture(reminders = true, participants = true) { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            val starts = if (spring) listOf("2030-03-30T01:30:00Z", "2030-03-31T01:30:00Z", "2030-04-01T00:30:00Z") else
                listOf("2030-10-26T00:30:00Z", "2030-10-27T00:30:00Z", "2030-10-28T01:30:00Z")
            val canonical = f.latest.copy(content = f.latest.content.copy(time = EventTime.Timed(Instant.parse(starts[0]),
                Instant.parse(starts[0]).plusSeconds(7200), ZoneId.of("Europe/Paris")),
                recurrence = EventRecurrence("finite-recovery-series", "FREQ=DAILY;COUNT=3")))
            val schedule = FiniteDailyRecurrenceExperiment.plan(canonical.content.time, canonical.content.recurrence!!)
            val view = canonical.copy(content = canonical.content.copy(recurrence = schedule.native))
            f.projectWithoutAcknowledgement(view)
            val row = f.row(); val applies = f.applies.get()
            val expected = starts.map { Triple(row, Instant.parse(it).toEpochMilli(), Instant.parse(it).plusSeconds(7200).toEpochMilli()) }
            val from = if (spring) "2030-03-29T00:00:00Z" else "2030-10-25T00:00:00Z"
            val until = if (spring) "2030-04-08T00:00:00Z" else "2030-11-02T00:00:00Z"
            assertEquals(expected, f.instances(from, until)); assertNull(f.field(Events.RRULE)); assertEquals("UTC", f.field(Events.EVENT_TIMEZONE))
            val reminders = f.reminders(); val people = f.attendees()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(view, receipt.change)
            assertEquals(AppliedProjectionWitness(receipt), f.native.inspectProjectionReceipt(receipt))
            assertNull("A native date-view proof must not authenticate its canonical rule", f.native.inspectProjectionReceipt(receipt.copy(change = canonical)))
            if (dirty) f.edit("Later local finite-date edit")
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(view, f.store.projectedBaseline(f.target, f.base.eventId))
            assertNotEquals(canonical, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals("FREQ=DAILY;COUNT=3", canonical.content.recurrence!!.rule)
            assertEquals(row, f.row()); assertEquals(applies, f.applies.get()); assertEquals(expected, f.instances(from, until))
            assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
            assertEquals(if (dirty) "Later local finite-date edit" else view.content.title, f.field(Events.TITLE))
            assertEquals(if (dirty) "1" else "0", f.field(Events.DIRTY))
            assertNull(f.store.projectionReceipt(f.target, f.base.eventId)); assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun laterDailyRuleRefusalRetainsItsPreparationAndBaselineAcrossReopening() = runBlocking {
        for (start in listOf("2030-10-26T00:30:00Z", "2030-03-30T01:30:00Z")) withFixture(reminders = true, participants = true) { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            val candidate = f.latest.copy(content = f.latest.content.copy(time = EventTime.Timed(Instant.parse(start), Instant.parse(start).plusSeconds(3600), ZoneId.of("Europe/Paris")),
                recurrence = EventRecurrence("daily-risk-series", "FREQ=DAILY;COUNT=3")))
            val before = f.instances(); val row = f.row(); val reminders = f.reminders(); val people = f.attendees()
            f.queue(candidate); f.coordinator.run(false)
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertNull(f.native.inspectProjectionReceipt(receipt))
            f.reopen(); f.coordinator.run(false)
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, candidate)), f.store.pending(f.target))
            assertEquals(before, f.instances()); assertEquals(row, f.row()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
        }
    }

    @Test fun explicitUtcProjectionRecoveryRetainsParisBaselineAndDoesNotRepairHistoricalLegacyRows() = runBlocking {
        for (legacy in listOf(false, true)) for (dirty in listOf(false, true)) withFixture(master = true, rdateOnly = true) { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            val starts = listOf("2030-10-27T00:30:00Z", "2030-10-27T01:30:00Z", "2030-10-28T01:30:00Z")
            val candidate = f.latest.copy(content = f.latest.content.copy(time = EventTime.Timed(Instant.parse(starts[0]),
                Instant.parse(starts[0]).plusSeconds(7200), ZoneId.of("Europe/Paris")), recurrence = EventRecurrence("fixture-primary-series",
                additionalDates = starts.drop(1).map { RecurrencePoint.Timed(Instant.parse(it)) })))
            f.projectWithoutAcknowledgement(candidate)
            val row = f.row(); val applies = f.applies.get()
            assertEquals("UTC", f.field(Events.EVENT_TIMEZONE)); assertEquals("UTC", f.field(Events.EVENT_END_TIMEZONE))
            fun slots(values: List<String>) = values.map { Triple(row, Instant.parse(it).toEpochMilli(), Instant.parse(it).plusSeconds(7200).toEpochMilli()) }
            assertEquals(slots(starts), f.instances())
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(candidate, receipt.change)
            if (legacy) f.mutate(ContentValues().apply {
                put(Events.EVENT_TIMEZONE, "Europe/Paris"); put(Events.EVENT_END_TIMEZONE, "Europe/Paris")
                put(Events.DTSTART, Instant.parse(starts[0]).toEpochMilli()); putNull(Events.DTEND)
                put(Events.DURATION, "PT7200S"); putNull(Events.RRULE); putNull(Events.EXDATE); put(Events.ALL_DAY, 0)
                put(Events.RDATE, "20301027T003000Z,20301027T013000Z,20301028T013000Z")
            })
            if (dirty) f.edit("Later local explicit-date edit")
            val observed = if (legacy) slots(starts.drop(1)) else slots(starts)
            assertEquals(observed, f.instances())
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(ZoneId.of("Europe/Paris"), (candidate.content.time as EventTime.Timed).zone)
            assertEquals(row, f.row()); assertEquals(applies, f.applies.get()); assertEquals(observed, f.instances())
            assertEquals(if (legacy) "Europe/Paris" else "UTC", f.field(Events.EVENT_TIMEZONE))
            assertEquals(if (dirty) "Later local explicit-date edit" else candidate.content.title, f.field(Events.TITLE))
            assertEquals(if (dirty) "1" else "0", f.field(Events.DIRTY))
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun ambiguousRuleRefusalRetainsItsPreparationAndBaselineAfterReopening() = runBlocking {
        withFixture(reminders = true, participants = true) { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            val candidate = f.latest.copy(content = f.latest.content.copy(recurrence = EventRecurrence("ambiguous-series", "FREQ=DAILY;COUNT=3")))
            val before = f.instances(); val row = f.row(); val reminders = f.reminders(); val people = f.attendees()
            f.queue(candidate); f.coordinator.run(false)
            val prepared = f.store.projectionReceipt(f.target, f.base.eventId)
            assertNotNull("Rejected write must retain its preparation", prepared)
            val receipt = prepared!!
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, candidate)), f.store.pending(f.target))
            assertEquals(before, f.instances()); assertEquals(row, f.row()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
            f.reopen(); f.coordinator.run(false)
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId)); assertEquals(candidate, f.store.pending(f.target).single().change)
            assertEquals(before, f.instances()); assertEquals(row, f.row()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
        }
    }

    @Test fun convertedOccurrenceInstancesStayExactAfterLostAcknowledgementRecovery() = runBlocking {
        for (allDay in listOf(false, true)) for (cancelled in listOf(false, true)) withFixture(allDay = allDay) { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            f.importMaster(if (allDay) null else EventTime.Timed(Instant.parse("2030-10-27T09:00:00Z"),
                Instant.parse("2030-10-27T11:00:00Z"), ZoneId.of("Europe/Paris")))
            assertEquals(4, f.instances().size) // The independent simple fixture and three master slots are cached first.
            val converted = f.exception(cancelled = cancelled, changeType = true)
            val candidate = if (allDay) converted else converted.copy(content = converted.content.copy(
                recurrence = converted.content.recurrence!!.copy(original = RecurrencePoint.Timed(Instant.parse("2030-10-27T09:00:00Z")))))
            f.projectWithoutAcknowledgement(candidate)
            val parent = f.masterRow!!; val child = f.row(); val applies = f.applies.get()
            fun slot(event: Long, start: String, end: String) = Triple(event, Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli())
            val remaining = if (allDay) listOf(
                slot(parent, "2030-10-28T00:00:00Z", "2030-10-29T00:00:00Z"),
                slot(parent, "2030-10-29T00:00:00Z", "2030-10-30T00:00:00Z"),
            ) else listOf(
                slot(parent, "2030-10-28T09:00:00Z", "2030-10-28T11:00:00Z"),
                slot(parent, "2030-10-29T09:00:00Z", "2030-10-29T11:00:00Z"),
            )
            val moved = if (cancelled) emptyList() else listOf(if (allDay)
                slot(child, "2030-10-28T09:00:00Z", "2030-10-28T10:00:00Z") else
                slot(child, "2030-10-28T00:00:00Z", "2030-10-29T00:00:00Z"))
            val expected = (remaining + moved).sortedBy { it.second }
            assertEquals(expected, f.instances())
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(expected, f.instances()); assertEquals(applies, f.applies.get()); assertEquals(child, f.row())
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun recurringMastersRecoverRulesAndDateSetsWithoutRepeatingWrites() = runBlocking {
        for (allDay in listOf(false, true)) for (rdateOnly in listOf(false, true)) {
            withFixture(reminders = true, participants = true, allDay = allDay, master = true, rdateOnly = rdateOnly) { f ->
                assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
                val row = f.row(); val candidate = f.changedMaster()
                f.projectWithoutAcknowledgement(candidate)
                assertNotEquals(f.field(Events.SYNC_DATA4), f.field(Events._SYNC_ID))
                assertNull(f.field(Events.DTEND)); assertNotNull(f.field(Events.DURATION))
                assertEquals(candidate.content.recurrence!!.rule, f.field(Events.RRULE))
                assertNotNull(f.field(Events.RDATE)); assertNotNull(f.field(Events.EXDATE))
                val times = f.instances(); assertEquals(if (rdateOnly) 3 else 5, times.size)
                val reminders = f.reminders(); val people = f.attendees(); val applies = f.applies.get()
                f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
                assertEquals(row, f.row()); assertEquals(applies, f.applies.get())
                assertEquals(times, f.instances()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
                assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
                assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            }
        }
    }

    @Test fun historicalMasterRecoveryPreservesLaterRuleRemovalAndLocalTombstones() = runBlocking {
        for (deleted in listOf(false, true)) withFixture(master = true, reminders = true, participants = true) { f ->
            f.coordinator.run(false); val candidate = f.changedMaster(); f.projectWithoutAcknowledgement(candidate)
            f.mutate(ContentValues().apply {
                put(Events.TITLE, "Locally changed series"); put(Events.DIRTY, 1); put(Events.DELETED, if (deleted) 1 else 0)
                putNull(Events.RRULE); putNull(Events.RDATE); putNull(Events.EXDATE); putNull(Events.DURATION)
                put(Events.DTEND, (f.base.content.time as EventTime.Timed).end.toEpochMilli())
            })
            val row = f.row(); val reminders = f.reminders(); val people = f.attendees(); val applies = f.applies.get()
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId)); assertEquals(row, f.row())
            assertEquals("Locally changed series", f.field(Events.TITLE)); assertNull(f.field(Events.RRULE)); assertNull(f.field(Events.RDATE))
            assertEquals("1", f.field(Events.DIRTY)); assertEquals(if (deleted) "1" else "0", f.field(Events.DELETED))
            assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees()); assertEquals(applies, f.applies.get())
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(LocalEditCaptureResult.UNQUALIFIED, f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-master-edit")))
        }
    }

    @Test fun olderMasterProofLeavesNewerRemoteRulesPendingAndLocalContentUntouched() = runBlocking {
        withFixture(master = true) { f ->
            f.coordinator.run(false); val candidate = f.changedMaster(); f.projectWithoutAcknowledgement(candidate)
            val newer = candidate.copy(revision = Revision("v3"), content = candidate.content.copy(
                recurrence = candidate.content.recurrence!!.copy(rule = "FREQ=WEEKLY;COUNT=4")))
            f.queue(newer); f.edit("Later local series edit")
            val applies = f.applies.get(); f.reopen(); f.coordinator.run(false)
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, newer)), f.store.pending(f.target))
            val newerReceipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(newer, newerReceipt.change); assertEquals(candidate, newerReceipt.previousBase)
            assertNull(f.native.inspectProjectionReceipt(newerReceipt)); assertEquals(applies + 1, f.applies.get())
            assertEquals("Later local series edit", f.field(Events.TITLE)); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun missingNonceOrForeignSeriesIdentityCannotAcknowledgeAMasterImport() = runBlocking {
        for (failure in listOf("missing", "foreign-nonce", "foreign-series")) withFixture(master = true) { f ->
            f.coordinator.run(false); val candidate = f.changedMaster(); f.projectWithoutAcknowledgement(candidate)
            f.mutate(ContentValues().apply {
                put(Events.DIRTY, 1)
                when (failure) {
                    "missing" -> putNull(Events.SYNC_DATA3)
                    "foreign-nonce" -> put(Events.SYNC_DATA3, "calendeer-projection-receipt-v1:${UUID.randomUUID()}")
                    else -> put(Events._SYNC_ID, "foreign-series-fixture")
                }
            })
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertNull(f.native.inspectProjectionReceipt(receipt))
            f.reopen(); f.coordinator.run(false)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, candidate)), f.store.pending(f.target)); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun duplicateSeriesInsertedAtProofTimeRefusesHistoricalAcknowledgement() = runBlocking {
        withFixture(master = true) { f ->
            f.coordinator.run(false); val candidate = f.changedMaster(); f.projectWithoutAcknowledgement(candidate)
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val real = f.context.contentResolver; var collision: Long? = null
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Proof must not insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Proof must not update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Proof must not delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue(operations.all { it.isAssertQuery }); collision = f.insertSeriesCollision()
                    return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val resolver = ContentResolver.wrap(provider)
            val context = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = resolver
            }
            try {
                var refused = false
                try { refused = AndroidCalendarProjection(context).inspectProjectionReceipt(receipt) == null }
                catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { refused = true }
                assertTrue(refused); assertNotNull(collision)
                // The second lookup also refuses a duplicate that already exists before inspection.
                try { assertNull(f.native.inspectProjectionReceipt(receipt)) }
                catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { }
                assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
                assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            } finally { collision?.let { f.removeRow(it) } }
            assertEquals(candidate.content.title, f.field(Events.TITLE))
        }
    }

    @Test fun recoveringMasterImportsDoesNotRewriteLinkedExceptions() = runBlocking {
        withFixture(master = true, reminders = true, participants = true) { f ->
            f.coordinator.run(false); val child = f.importLinkedException()
            val snapshot = f.linkedException(); val reminders = f.reminders(f.occurrenceRow!!); val people = f.attendees(f.occurrenceRow!!)
            val candidate = f.changedMaster(); f.projectWithoutAcknowledgement(candidate)
            val applies = f.applies.get(); f.reopen()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            // Inspect this isolated boundary directly; the previous owner's Provider lease is closed.
            val inspector = AndroidCalendarProjection(f.context)
            assertTrue(f.store.restoreProjection(inspector.inspectProjectionReceipt(receipt)!!))
            assertEquals(snapshot, f.linkedException()); assertEquals(reminders, f.reminders(f.occurrenceRow!!))
            assertEquals(people, f.attendees(f.occurrenceRow!!)); assertEquals(child, f.store.projectedBaseline(f.target, child.eventId))
            assertEquals(applies, f.applies.get()); assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, child)), f.store.pending(f.target))
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies + 1, f.applies.get()); assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(child, f.store.projectedBaseline(f.target, child.eventId)); assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun failedMasterToSimpleTransitionRollsBackExceptionRemovalAndAllDetails() = runBlocking {
        withFixture(master = true, reminders = true, participants = true) { f ->
            f.coordinator.run(false); f.importLinkedException()
            val child = f.linkedException(); val reminders = f.reminders(); val people = f.attendees(); val marker = f.field(Events.SYNC_DATA3)
            val childReminders = f.reminders(f.occurrenceRow!!); val childPeople = f.attendees(f.occurrenceRow!!)
            val foreign = f.changedMaster().let { it.copy(content = it.content.copy(recurrence = it.content.recurrence!!.copy(uid = "replacement-series"))) }
            try { f.queue(foreign); fail("Existing series identity must remain immutable") }
            catch (_: IllegalArgumentException) { }
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            val candidate = f.latest.copy(content = f.latest.content.copy(recurrence = null))
            f.queue(candidate); val receipt = f.store.prepareProjection(ProjectionIntent(f.target, candidate), f.format)
            rejectAtomicBatch(f, receipt, expectParticipants = true)
            assertEquals(child, f.linkedException()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
            assertEquals(childReminders, f.reminders(f.occurrenceRow!!)); assertEquals(childPeople, f.attendees(f.occurrenceRow!!))
            assertEquals(f.base.content.title, f.field(Events.TITLE)); assertEquals(marker, f.field(Events.SYNC_DATA3))
            assertNull(f.native.inspectProjectionReceipt(receipt)); assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun masterDateAliasesCannotChangePreparedOrAppliedEvidenceAtAdmission() = runBlocking {
        withFixture(master = true) { f ->
            f.coordinator.run(false); val candidate = f.changedMaster()
            val additions = candidate.content.recurrence!!.additionalDates.toMutableList()
            val exclusions = candidate.content.recurrence!!.excludedDates.toMutableList()
            val alias = candidate.copy(content = candidate.content.copy(recurrence = candidate.content.recurrence!!.copy(
                additionalDates = additions, excludedDates = exclusions)))
            f.queue(alias); val receipt = f.store.prepareProjection(ProjectionIntent(f.target, alias), f.format)
            val prepared = receipt.copy(change = alias)
            f.native.admitOperation = { additions.clear(); exclusions.clear(); true }
            assertEquals(receipt, f.native.applyTracked(prepared).receipt)
            f.native.admitOperation = { true }; assertNotNull(f.field(Events.RDATE)); assertNotNull(f.field(Events.EXDATE))
            val inspectedAdditions = receipt.change.content.recurrence!!.additionalDates.toMutableList()
            val inspectedExclusions = receipt.change.content.recurrence!!.excludedDates.toMutableList()
            val inspection = receipt.copy(change = receipt.change.copy(content = receipt.change.content.copy(
                recurrence = receipt.change.content.recurrence!!.copy(additionalDates = inspectedAdditions, excludedDates = inspectedExclusions))))
            f.native.admitOperation = { inspectedAdditions.clear(); inspectedExclusions.clear(); true }
            assertEquals(receipt, f.native.inspectProjectionReceipt(inspection)!!.receipt)
            f.native.admitOperation = { true }
            assertTrue(f.store.restoreProjection(AppliedProjectionWitness(receipt)))
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
        }
    }

    @Test fun movedAndCancelledExceptionsRecoverWithoutRewritingTheirParentOrDetails() = runBlocking {
        for (allDay in listOf(false, true)) for (cancelled in listOf(false, true)) for (changeType in listOf(false, true)) {
            withFixture(nonAmbiguousStart = true, reminders = true, participants = true, allDay = allDay) { f ->
                f.coordinator.run(false); f.importMaster()
                val candidate = f.exception(cancelled, changeType)
                val parent = f.parent(); val parentReminders = f.reminders(f.masterRow!!); val parentPeople = f.attendees(f.masterRow!!)
                f.projectWithoutAcknowledgement(candidate)
                val row = f.row(); val reminders = f.reminders(); val people = f.attendees(); val applies = f.applies.get()
                assertEquals(f.masterRow.toString(), f.field(Events.ORIGINAL_ID))
                f.assertExceptionDates(candidate)
                assertEquals(if (cancelled) Events.STATUS_CANCELED.toString() else Events.STATUS_CONFIRMED.toString(), f.field(Events.STATUS))
                assertEquals(if (cancelled) emptyList<Int>() else listOf(5, 15), reminders.values.map { it.first }.sorted())
                f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
                assertEquals(applies, f.applies.get()); assertEquals(row, f.row()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
                assertEquals(if (cancelled) Events.STATUS_CANCELED.toString() else Events.STATUS_CONFIRMED.toString(), f.field(Events.STATUS))
                assertEquals(f.masterRow.toString(), f.field(Events.ORIGINAL_ID))
                f.assertExceptionDates(candidate)
                assertEquals(parent, f.parent()); assertEquals(parentReminders, f.reminders(f.masterRow!!)); assertEquals(parentPeople, f.attendees(f.masterRow!!))
                assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
                assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            }
        }
    }

    @Test fun historicalExceptionRecoveryPreservesLaterDirtyEditsAndLocalTombstones() = runBlocking {
        for (allDay in listOf(false, true)) for (changeType in listOf(false, true)) for (deleted in listOf(false, true))
            withFixture(nonAmbiguousStart = true, reminders = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = changeType)
            f.projectWithoutAcknowledgement(candidate)
            f.mutate(ContentValues().apply { put(Events.TITLE, "Local occurrence edit"); put(Events.DIRTY, 1); put(Events.DELETED, if (deleted) 1 else 0) })
            val parent = f.parent(); val reminders = f.reminders(); val row = f.row(); val applies = f.applies.get()
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row()); assertEquals(reminders, f.reminders()); assertEquals(parent, f.parent())
            assertEquals("Local occurrence edit", f.field(Events.TITLE)); assertEquals("1", f.field(Events.DIRTY))
            assertEquals(if (deleted) "1" else "0", f.field(Events.DELETED))
            f.assertExceptionDates(candidate)
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId)); assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-occurrence-edit")))
        }
    }

    @Test fun olderExceptionProofDoesNotConsumeNewerRemoteContentOrOverwriteADirtyOccurrence() = runBlocking {
        for (allDay in listOf(false, true)) for (changeType in listOf(false, true)) withFixture(nonAmbiguousStart = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = changeType); f.projectWithoutAcknowledgement(candidate)
            val historical = f.store.projectionReceipt(f.target, f.base.eventId)!!; val applies = f.applies.get()
            val newer = candidate.copy(revision = Revision("v3"), content = candidate.content.copy(title = "Newer remote occurrence"))
            f.queue(newer); f.edit("Local occurrence edit")
            val parent = f.parent()
            f.reopen(); f.coordinator.run(false)
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, newer)), f.store.pending(f.target))
            assertEquals(newer, f.store.transaction(f.target) { remote(f.base.eventId) })
            val prepared = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(newer, prepared.change); assertEquals(candidate, prepared.previousBase); assertNotEquals(historical.nonce, prepared.nonce)
            assertNull(f.native.inspectProjectionReceipt(prepared)); assertEquals(applies + 1, f.applies.get())
            f.assertExceptionDates(candidate)
            assertEquals("Local occurrence edit", f.field(Events.TITLE)); assertEquals("1", f.field(Events.DIRTY)); assertEquals(parent, f.parent())
        }
    }

    @Test fun missingOrForeignExceptionNonceCannotQualifyAnInterruptedOccurrence() = runBlocking {
        for (allDay in listOf(false, true)) for (changeType in listOf(false, true))
            for (marker in listOf(null, "calendeer-projection-receipt-v1:${UUID.randomUUID()}")) withFixture(nonAmbiguousStart = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = changeType); f.projectWithoutAcknowledgement(candidate)
            f.edit("Local occurrence without proof")
            f.mutate(ContentValues().apply { if (marker == null) putNull(Events.SYNC_DATA3) else put(Events.SYNC_DATA3, marker) })
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!; val parent = f.parent()
            assertNull(f.native.inspectProjectionReceipt(receipt))
            f.reopen(); f.coordinator.run(false)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, candidate)), f.store.pending(f.target))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId)); assertEquals(parent, f.parent())
            assertEquals("Local occurrence without proof", f.field(Events.TITLE)); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun aDisappearedNativeParentRefusesExceptionApplicationWithoutPromotingTheReceipt() = runBlocking {
        for (allDay in listOf(false, true)) for (changeType in listOf(false, true)) withFixture(nonAmbiguousStart = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = changeType); f.queue(candidate)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, candidate), f.format)
            f.removeMaster()
            try { f.native.applyTracked(prepared); fail("Exception requires its owned native parent") }
            catch (failure: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) {
                assertEquals(com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category)
            }
            assertNull(f.native.inspectProjectionReceipt(prepared))
            assertEquals(f.base.content.title, f.field(Events.TITLE)); assertNull(f.field(Events.ORIGINAL_ID))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, candidate)), f.store.pending(f.target))
            assertEquals(prepared, f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun failedExceptionBatchLeavesTheParentAndPreviousProjectionIntact() = runBlocking {
        for (allDay in listOf(false, true)) for (changeType in listOf(false, true))
            withFixture(nonAmbiguousStart = true, reminders = true, participants = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = changeType); f.queue(candidate)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, candidate), f.format)
            val parent = f.parent(); val reminders = f.reminders(); val people = f.attendees(); val marker = f.field(Events.SYNC_DATA3)
            rejectAtomicBatch(f, prepared, expectParticipants = true)
            assertEquals(parent, f.parent()); assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
            assertEquals(marker, f.field(Events.SYNC_DATA3)); assertNull(f.field(Events.ORIGINAL_ID))
            assertNull(f.native.inspectProjectionReceipt(prepared))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(prepared, f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun changedDisplayTypeDoesNotReplaceTheRequiredParentSlotType() = runBlocking {
        for (allDay in listOf(false, true)) withFixture(nonAmbiguousStart = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = true)
            f.queue(candidate); val receipt = f.store.prepareProjection(ProjectionIntent(f.target, candidate), f.format)
            // The native parent now matches display, but no longer matches the original slot.
            f.changeParentType(candidate.content.time is EventTime.AllDay)
            val parent = f.parent(); val marker = f.field(Events.SYNC_DATA3)
            try { f.native.applyTracked(receipt); fail("The original slot must match the master's date type") }
            catch (failure: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) {
                assertEquals(com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category)
            }
            assertEquals(parent, f.parent()); assertEquals(marker, f.field(Events.SYNC_DATA3)); assertNull(f.field(Events.ORIGINAL_ID))
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId)); assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(candidate, f.store.pending(f.target).single().change)
        }
    }

    @Test fun historicalTypeChangeProofPreservesASubsequentNativeDisplayConversion() = runBlocking {
        for (allDay in listOf(false, true)) withFixture(nonAmbiguousStart = true, reminders = true, participants = true, allDay = allDay) { f ->
            f.coordinator.run(false); f.importMaster()
            val candidate = f.exception(changeType = true); f.projectWithoutAcknowledgement(candidate)
            f.changeDisplay(f.base.content.time)
            val local = candidate.copy(content = candidate.content.copy(time = f.base.content.time))
            val row = f.row(); val reminders = f.reminders(); val people = f.attendees(); val applies = f.applies.get()
            val parent = f.parent()
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            f.assertExceptionDates(local); assertEquals("1", f.field(Events.DIRTY))
            assertEquals(row, f.row()); assertEquals(applies, f.applies.get()); assertEquals(parent, f.parent())
            assertEquals(reminders, f.reminders()); assertEquals(people, f.attendees())
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-type-change-edit")))
        }
    }

    @Test fun participantImportsRecoverTimedAndAllDayEventsWithoutRepeatingChildWrites() = runBlocking {
        for (allDay in listOf(false, true)) withFixture(reminders = true, participants = true, allDay = allDay) { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.projectLatestWithoutAcknowledgement()
            val people = f.attendees(); val reminders = f.reminders(); val applies = f.applies.get()
            assertEquals(2, people.size)
            assertEquals(1, people.values.count { it.email == "organizer-v2@example.test" })
            assertEquals(Attendees.RELATIONSHIP_ORGANIZER,
                people.values.single { it.email == "organizer-v2@example.test" }.relationship)
            assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED,
                people.values.single { it.email == "organizer-v2@example.test" }.status)
            assertEquals(Attendees.TYPE_OPTIONAL, people.values.single { it.email == "guest@example.test" }.role)
            assertEquals("organizer-v2@example.test", f.field(Events.ORGANIZER))
            for (column in listOf(Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS))
                assertEquals("0", f.field(column))
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row())
            assertEquals(people, f.attendees()); assertEquals(reminders, f.reminders())
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun organizerOnlyAndParticipantOnlyImportsRecoverTheirExactNativeDetails() = runBlocking {
        val guest = EventAttendee(EventPerson("guest@example.test"), AttendanceRole.RESOURCE, AttendanceStatus.DECLINED)
        for (details in listOf(EventDetails(organizer = EventPerson("organizer-only@example.test")),
            EventDetails(attendees = listOf(guest)))) withFixture { f ->
            f.coordinator.run(false)
            val candidate = f.latest.copy(content = f.latest.content.copy(details = details))
            f.projectWithoutAcknowledgement(candidate)
            val people = f.attendees(); val row = f.row(); val applies = f.applies.get()
            assertEquals(1, people.size); assertEquals(details.organizer?.email, f.field(Events.ORGANIZER))
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row()); assertEquals(people, f.attendees())
            assertEquals(candidate, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun historicalParticipantReceiptPreservesLaterDirtyOrganizerAndResponseChanges() = runBlocking {
        withFixture(reminders = true, participants = true) { f ->
            f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
            val row = f.row(); val applies = f.applies.get()
            f.editParticipant()
            val people = f.attendees(); val reminders = f.reminders(); val marker = f.field(Events.SYNC_DATA3)
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row())
            assertEquals(people, f.attendees()); assertEquals(reminders, f.reminders())
            assertEquals("local-organizer@example.test", f.field(Events.ORGANIZER))
            assertEquals(marker, f.field(Events.SYNC_DATA3)); assertEquals("1", f.field(Events.DIRTY))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-participant-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId)); assertEquals(people, f.attendees())
        }
    }

    @Test fun recoveredParticipantBaselineDoesNotConsumeANewerImportOrOverwriteDirtyDetails() = runBlocking {
        withFixture(participants = true) { f ->
            f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
            val newer = f.latest.copy(revision = Revision("v3"), content = f.latest.content.copy(details = EventDetails()))
            f.queue(newer); f.editParticipant()
            val people = f.attendees()
            f.reopen(); f.coordinator.run(false)
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, newer)), f.store.pending(f.target))
            assertEquals(newer, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(people, f.attendees()); assertEquals("1", f.field(Events.DIRTY))
            assertEquals("local-organizer@example.test", f.field(Events.ORGANIZER))
        }
    }

    @Test fun missingOrForeignParticipantNonceKeepsPendingEvidenceAndDirtyChildren() = runBlocking {
        for (marker in listOf(null, "calendeer-projection-receipt-v1:${UUID.randomUUID()}")) {
            withFixture(participants = true) { f ->
                f.coordinator.run(false); f.projectLatestWithoutAcknowledgement(); f.editParticipant()
                f.mutate(ContentValues().apply { if (marker == null) putNull(Events.SYNC_DATA3) else put(Events.SYNC_DATA3, marker) })
                val people = f.attendees(); val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
                assertNull(f.native.inspectProjectionReceipt(receipt))
                f.reopen(); f.coordinator.run(false)
                assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
                assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
                assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
                assertEquals(people, f.attendees()); assertEquals("1", f.field(Events.DIRTY)); assertEquals(1, f.rowCount())
            }
        }
    }

    @Test fun participantApplyAndInspectionOwnListsBeforeProviderAdmission() = runBlocking {
        withFixture(participants = true) { f ->
            f.coordinator.run(false); f.queue(f.latest)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, f.latest), f.format)
            val people = prepared.change.content.details!!.attendees.toMutableList()
            val alias = prepared.copy(change = prepared.change.copy(content = prepared.change.content.copy(
                details = prepared.change.content.details!!.copy(attendees = people))))
            f.native.admitOperation = { people.clear(); true }
            assertEquals(prepared, f.native.applyTracked(alias).receipt)
            val rows = f.attendees()
            assertEquals(2, rows.size); assertEquals("organizer-v2@example.test", f.field(Events.ORGANIZER))
            people.addAll(prepared.change.content.details!!.attendees)
            val inspected = f.native.inspectProjectionReceipt(alias)!!
            assertEquals(prepared, inspected.receipt); assertEquals(rows, f.attendees())
            f.native.admitOperation = { true }
            assertTrue(f.store.restoreProjection(inspected))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
        }
    }

    @Test fun participantBatchFailureRollsBackOrganizerChildrenAndNonceAtomically() = runBlocking {
        withFixture(reminders = true, participants = true) { f ->
            f.coordinator.run(false); f.queue(f.latest)
            val receipt = f.store.prepareProjection(ProjectionIntent(f.target, f.latest), f.format)
            val people = f.attendees(); val reminders = f.reminders(); val marker = f.field(Events.SYNC_DATA3)
            rejectAtomicBatch(f, receipt, expectParticipants = true)
            assertEquals(people, f.attendees()); assertEquals(reminders, f.reminders())
            assertEquals("organizer-v1@example.test", f.field(Events.ORGANIZER))
            assertEquals(marker, f.field(Events.SYNC_DATA3)); assertEquals(f.base.content.title, f.field(Events.TITLE))
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
        }
    }

    @Test fun reminderImportReopensAfterLostAckWithoutReapplyingOrDuplicatingReminderRows() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.projectLatestWithoutAcknowledgement()
            val rows = f.reminders()
            assertEquals(listOf(5, 15), rows.values.map { it.first }.sorted())
            assertTrue(rows.values.all { it.second == Reminders.METHOD_ALERT })
            val applies = f.applies.get()
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row())
            assertEquals(rows, f.reminders()); assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty()); assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
        }
    }

    @Test fun allDayReminderImportRecoversTheSameProviderEventAndReminderRows() = runBlocking {
        withFixture(reminders = true, allDay = true) { f ->
            f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
            val row = f.row(); val rows = f.reminders(); val applies = f.applies.get()
            assertEquals("1", f.field(Events.ALL_DAY)); assertEquals(listOf(5, 15), rows.values.map { it.first }.sorted())
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row()); assertEquals(rows, f.reminders())
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun historicalReminderReceiptPreservesSubsequentDirtyReminderChanges() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
            val row = f.row(); val applies = f.applies.get()
            f.editReminder(99)
            val rows = f.reminders(); val marker = f.field(Events.SYNC_DATA3)
            f.reopen(); assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(applies, f.applies.get()); assertEquals(row, f.row()); assertEquals(rows, f.reminders())
            assertEquals(marker, f.field(Events.SYNC_DATA3)); assertEquals("1", f.field(Events.DIRTY))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-reminder-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId)); assertEquals(rows, f.reminders())
        }
    }

    @Test fun restoringAnOlderReminderReceiptPreservesNewerRemoteAndDirtyProviderContents() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val newer = f.latest.copy(revision = Revision("v3"), content = f.latest.content.copy(
                details = EventDetails(listOf(EventReminder(60)))))
            f.queue(newer); f.editReminder(99)
            val rows = f.reminders()
            val witness = f.native.inspectProjectionReceipt(receipt)!!
            assertTrue(f.store.restoreProjection(witness))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, newer)), f.store.pending(f.target))
            assertEquals(newer, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(rows, f.reminders()); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun missingOrForeignNonceCannotAcknowledgeAReminderReceipt() = runBlocking {
        for (marker in listOf(null, "calendeer-projection-receipt-v1:${UUID.randomUUID()}")) {
            withFixture(reminders = true) { f ->
                f.coordinator.run(false); f.projectLatestWithoutAcknowledgement()
                f.editReminder(99)
                f.mutate(ContentValues().apply { if (marker == null) putNull(Events.SYNC_DATA3) else put(Events.SYNC_DATA3, marker) })
                val rows = f.reminders()
                val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
                assertNull(f.native.inspectProjectionReceipt(receipt))
                f.reopen(); f.coordinator.run(false)
                assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
                assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
                assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
                assertEquals(rows, f.reminders()); assertEquals("1", f.field(Events.DIRTY)); assertEquals(1, f.rowCount())
            }
        }
    }

    @Test fun trackedReminderApplyAndInspectionSnapshotInputsBeforeProviderAdmission() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false)
            f.queue(f.latest)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, f.latest), f.format)
            val reminders = prepared.change.content.details!!.reminders.toMutableList()
            val alias = prepared.copy(change = prepared.change.copy(content = prepared.change.content.copy(details = EventDetails(reminders))))
            f.native.admitOperation = { reminders.clear(); true }
            val applied = f.native.applyTracked(alias)
            assertEquals(prepared, applied.receipt)
            assertEquals(listOf(5, 15), f.reminders().values.map { it.first }.sorted())
            reminders.addAll(listOf(EventReminder(5), EventReminder(15)))
            val rows = f.reminders()
            val inspected = f.native.inspectProjectionReceipt(alias)!!
            assertEquals(prepared, inspected.receipt); assertEquals(rows, f.reminders())
            f.native.admitOperation = { true }
            assertTrue(f.store.restoreProjection(inspected))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
        }
    }

    @Test fun refusedReminderWriteCannotTurnPreparationIntoHistoricalEvidence() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false)
            val rows = f.reminders(); val marker = f.field(Events.SYNC_DATA3)
            f.editReminder(99); val dirtyRows = f.reminders()
            f.queue(f.latest); f.coordinator.run(false)
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(marker, f.field(Events.SYNC_DATA3)); assertNotEquals(rows, dirtyRows)
            f.reopen(); f.coordinator.run(false)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals(dirtyRows, f.reminders()); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun rejectedAtomicReminderBatchRollsBackTheNonceEventAndChildRowsTogether() = runBlocking {
        withFixture(reminders = true) { f ->
            f.coordinator.run(false); f.queue(f.latest)
            val receipt = f.store.prepareProjection(ProjectionIntent(f.target, f.latest), f.format)
            val rows = f.reminders(); val marker = f.field(Events.SYNC_DATA3)
            rejectAtomicBatch(f, receipt)
            assertEquals(rows, f.reminders()); assertEquals(marker, f.field(Events.SYNC_DATA3))
            assertEquals(f.base.content.title, f.field(Events.TITLE)); assertEquals("0", f.field(Events.DIRTY))
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
        }
    }

    @Test fun dirtyEditAfterLostProjectionAckRetainsTheActuallyProjectedBaseAfterReopening() = runBlocking {
        withFixture { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            val row = f.row()
            f.queue(f.latest)
            f.ackFailure(true)
            try { f.coordinator.run(false) } finally { f.ackFailure(false) }
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
            assertEquals(row, f.row())
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(f.latest, f.store.transaction(f.target) { remote(f.base.eventId) })
            f.edit("Recovery local edit after projection")
            f.reopen()
            assertEquals(LocalEditCaptureResult.RETAINED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.latest, retained.base)
            assertEquals(f.latest.content.copy(title = "Recovery local edit after projection"), retained.content)
            assertEquals(LocalEditState.PAUSED, retained.state)
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(row, f.row())
            assertEquals("Recovery local edit after projection", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun cleanLostAcknowledgementRecoversOfflineWithoutDuplicatingTheRow() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(f.latest, receipt.change)
            assertNotNull(f.native.inspectProjectionReceipt(receipt))
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(row, f.row())
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals("0", f.field(Events.DIRTY))
        }
    }

    @Test fun receiptPreparedBeforeARefusedWriteCannotPromoteTheRemoteVersion() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.edit("Recovery edit before projection")
            f.queue(f.latest)
            f.coordinator.run(false)
            val unapplied = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertNull(f.native.inspectProjectionReceipt(unapplied))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            f.reopen()
            assertEquals(LocalEditCaptureResult.CONFLICT,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.base, retained.base)
            assertEquals("Recovery edit before projection", retained.content.title)
            assertEquals(f.latest, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(row, f.row()); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun newerRemoteCacheDoesNotReplaceTheAppliedReceiptUsedAsAnEditBase() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val third = f.latest.copy(revision = Revision("v3"), content = f.latest.content.copy(title = "Recovery remote v3"))
            f.queue(third)
            f.edit("Recovery local edit against v2")
            f.reopen()
            assertEquals(LocalEditCaptureResult.CONFLICT,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.latest, retained.base)
            assertEquals(f.latest.content.copy(title = "Recovery local edit against v2"), retained.content)
            assertEquals(third, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals("Recovery local edit against v2", f.field(Events.TITLE))
        }
    }

    @Test fun matchingRevisionAloneCannotWitnessANewUnappliedNonce() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.store.requeueAll(f.target)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, f.base), f.format)
            assertNull("The old row has the same revision but a different projection nonce",
                f.native.inspectProjectionReceipt(prepared))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals("0", f.field(Events.DIRTY))
            assertEquals(f.base.content.title, f.field(Events.TITLE))
        }
    }

    @Test fun missingNonceLeavesAChangedDirtyProjectionUnqualified() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            f.edit("Recovery local edit without receipt marker")
            f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
            f.reopen()
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals("Recovery local edit without receipt marker", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun tombstoneMarkerCanProvePastProjectionWithoutResurrectingTheEvent() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val row = f.row()
            f.mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
            assertNotNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals("1", f.field(Events.DELETED)); assertEquals("1", f.field(Events.DIRTY))
            f.reopen()
            f.coordinator.run(false)
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(row, f.row())
            assertEquals("1", f.field(Events.DELETED)); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun recreatedMirrorCannotWitnessTheDisappearedProjection() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            f.native.removeOwnedCalendar(f.target)
            f.recreateCalendar()
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun duplicateIdentityCannotWitnessOrRecoverAProjection() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            f.duplicate()
            var refused = false
            try { refused = f.native.inspectProjectionReceipt(receipt) == null }
            catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { refused = true }
            assertTrue("Duplicate ownership cannot prove the applied receipt", refused)
            f.reopen()
            f.coordinator.run(false)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals(2, f.rowCount())
        }
    }

    @Test fun missingNonceAndReusedOpaqueRevisionCannotChooseAnOlderEditBase() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val ambiguous = f.base.copy(content = f.base.content.copy(title = "Recovery different content same revision"))
            f.queue(ambiguous)
            f.ackFailure(true)
            try { f.coordinator.run(false) } finally { f.ackFailure(false) }
            assertEquals(ambiguous.content.title, f.field(Events.TITLE))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(ambiguous, receipt.change)
            f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
            f.edit("Recovery local edit with ambiguous base")
            f.reopen()
            assertEquals("Equal opaque revisions cannot disambiguate two different projected contents",
                LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(ambiguous, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, ambiguous)), f.store.pending(f.target))
            assertEquals("Recovery local edit with ambiguous base", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
            assertNull(f.field(Events.SYNC_DATA3))
        }
    }

    @Test fun nonceChangedBeforeProofAssertionCannotRestoreTheProjectionBaseline() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val real = f.context.contentResolver
            var injected = false
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Receipt inspection must not insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
                    error("Receipt inspection must not update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
                    error("Receipt inspection must not delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue("Proof batch must contain assertions only", operations.all { it.isAssertQuery })
                    injected = true
                    f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
                    return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrappedContext = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
            }
            var refused = false
            try {
                val witness = AndroidCalendarProjection(wrappedContext).inspectProjectionReceipt(receipt)
                refused = witness == null
                if (witness != null) f.store.restoreProjection(witness)
            } catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { refused = true }
            assertTrue("An invalidated nonce must not qualify a projection witness", refused)
            assertTrue("Race must occur immediately before the atomic proof assertions", injected)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertNull(f.field(Events.SYNC_DATA3))
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
        }
    }

    private suspend fun rejectAtomicBatch(f: Fixture, receipt: ProjectionReceipt, expectParticipants: Boolean = false) {
        val real = f.context.contentResolver
        var injected = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Expected atomic batch")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Expected atomic batch")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Expected atomic batch")
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                real.query(uri, columns, selection, args, order)
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                assertTrue(operations.any { it.uri.path == Reminders.CONTENT_URI.path && it.isInsert })
                if (expectParticipants) assertTrue(operations.any { it.uri.path == Attendees.CONTENT_URI.path && it.isInsert })
                injected = true
                val failing = ArrayList(operations)
                failing += ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                    .withSelection("${Events._ID}=?", arrayOf("-1")).withExpectedCount(1).build()
                return real.applyBatch(CalendarContract.AUTHORITY, failing)
            }
        }
        val wrappedResolver = ContentResolver.wrap(provider)
        val wrappedContext = object : ContextWrapper(f.context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = wrappedResolver
        }
        try { AndroidCalendarProjection(wrappedContext).applyTracked(receipt); fail("Injected assertion must reject the batch") }
        catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { }
        assertTrue(injected)
    }

    private class Fixture(val context: Context, reminders: Boolean = false, allDay: Boolean = false, participants: Boolean = false,
        master: Boolean = false, rdateOnly: Boolean = false, val nonAmbiguousStart: Boolean = false) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("projection-recovery-$token"), CalendarId("fixture"), 0)
        private val name = "projection-recovery-$token.db"
        val native = AndroidCalendarProjection(context)
        val format = AdapterFormat("projection-recovery-fixture", 1)
        val base = RemoteChange.Upsert(EventId("fixture"), Revision("v1"), EventContent("Recovery base v1",
            if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 27), LocalDate.of(2030, 10, 28))
            else EventTime.Timed(Instant.parse(if (nonAmbiguousStart) "2030-10-27T09:00:00Z" else "2030-10-27T00:30:00Z"), Instant.parse(if (nonAmbiguousStart) "2030-10-27T11:00:00Z" else "2030-10-27T02:30:00Z"),
                ZoneId.of("Europe/Paris")), recurrence = if (master) EventRecurrence("fixture-primary-series",
                    if (rdateOnly) null else "FREQ=DAILY;COUNT=3", listOf(fixturePoint(allDay, 4)), listOf(fixturePoint(allDay, 1))) else null,
                details = EventDetails(if (reminders) listOf(EventReminder(10), EventReminder(30)) else emptyList(),
                organizer = if (participants) EventPerson("organizer-v1@example.test", "Organizer v1") else null,
                attendees = if (participants) listOf(EventAttendee(EventPerson("guest@example.test", "Guest"),
                    AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)) else emptyList())))
        val latest = base.copy(revision = Revision("v2"), content = base.content.copy(title = "Recovery base v2",
            details = EventDetails(if (reminders) listOf(EventReminder(5), EventReminder(15)) else emptyList(),
                organizer = if (participants) EventPerson("organizer-v2@example.test", "Organizer v2") else null,
                attendees = if (participants) listOf(
                    EventAttendee(EventPerson("organizer-v2@example.test", "Organizer v2"), AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED),
                    EventAttendee(EventPerson("guest@example.test", "Guest"), AttendanceRole.OPTIONAL, AttendanceStatus.TENTATIVE)) else emptyList())))
        val applies = AtomicInteger()
        private val counted = object : CalendarProjection by native {
            override suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness {
                applies.incrementAndGet(); return native.applyTracked(receipt)
            }
        }
        var store = ImportStore(context, name)
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("Recovery must not download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Recovery must not upload")
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, counted, jobs, scheduler)
        private var calendar = -1L
        var masterRow: Long? = null
            private set
        var occurrenceRow: Long? = null
            private set
        private val secondaryRow get() = masterRow ?: occurrenceRow
        private val primarySelection get() = if (secondaryRow == null) "${Events.CALENDAR_ID}=?" else "${Events.CALENDAR_ID}=? AND ${Events._ID}<>?"
        private val primaryArgs get() = if (secondaryRow == null) arrayOf(calendar.toString()) else arrayOf(calendar.toString(), secondaryRow.toString())
        private val uri get() = Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, native.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, native.accountFor(target.account).type).build()

        suspend fun initialize() {
            val summary = CalendarSummary(target.calendar, "Calendeer projection recovery fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendar(target, summary)
            queue(base)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            start()
        }

        private suspend fun start() {
            val before = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }

        suspend fun queue(change: RemoteChange.Upsert) = store.transaction(target) {
            putRemote(change); putProjection(ProjectionIntent(target, change))
        }

        suspend fun projectLatestWithoutAcknowledgement() = projectWithoutAcknowledgement(latest)

        suspend fun projectWithoutAcknowledgement(change: RemoteChange.Upsert) {
            queue(change); ackFailure(true)
            try { coordinator.run(false) } finally { ackFailure(false) }
            assertEquals(change.content.title, field(Events.TITLE))
            assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertEquals(listOf(ProjectionIntent(target, change)), store.pending(target).filter { it.change.eventId == base.eventId })
        }

        suspend fun importMaster(time: EventTime? = null) {
            val master = base.copy(eventId = EventId("fixture-master"), content = base.content.copy(title = "Recovery series master",
                time = time ?: base.content.time, recurrence = EventRecurrence("fixture-series", "FREQ=DAILY;COUNT=3")))
            queue(master); assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
            masterRow = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.RRULE}=?",
                arrayOf(calendar.toString(), "FREQ=DAILY;COUNT=3"), null)!!.use { assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0) }
            assertEquals(master, store.projectedBaseline(target, master.eventId))
        }

        fun exception(cancelled: Boolean = false, changeType: Boolean = false): RemoteChange.Upsert {
            val original = when (val time = base.content.time) {
                is EventTime.AllDay -> RecurrencePoint.AllDay(time.start)
                is EventTime.Timed -> RecurrencePoint.Timed(time.start)
            }
            val moved = if (changeType) when (val time = latest.content.time) {
                is EventTime.AllDay -> {
                    val start = time.start.plusDays(1).atTime(10, 0).atZone(ZoneId.of("Europe/Paris")).toInstant()
                    EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
                }
                is EventTime.Timed -> {
                    val date = time.start.atZone(time.zone).toLocalDate().plusDays(1)
                    EventTime.AllDay(date, date.plusDays(1))
                }
            } else when (val time = latest.content.time) {
                is EventTime.AllDay -> time.copy(start = time.start.plusDays(1), endExclusive = time.endExclusive.plusDays(1))
                is EventTime.Timed -> time.copy(start = time.start.plusSeconds(3600), end = time.end.plusSeconds(3600))
            }
            return latest.copy(content = latest.content.copy(time = moved,
                recurrence = EventRecurrence("fixture-series", original = original, cancelled = cancelled)))
        }

        fun assertExceptionDates(candidate: RemoteChange.Upsert) {
            val original = candidate.content.recurrence!!.original!!
            val originalMillis = when (original) {
                is RecurrencePoint.AllDay -> original.date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                is RecurrencePoint.Timed -> original.instant.toEpochMilli()
            }
            assertEquals(if (original is RecurrencePoint.AllDay) "1" else "0", field(Events.ORIGINAL_ALL_DAY))
            assertEquals(originalMillis.toString(), field(Events.ORIGINAL_INSTANCE_TIME))
            assertEquals(if (candidate.content.time is EventTime.AllDay) "1" else "0", field(Events.ALL_DAY))
            val (start, end) = when (val time = candidate.content.time) {
                is EventTime.AllDay -> time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() to
                    time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                is EventTime.Timed -> time.start.toEpochMilli() to time.end.toEpochMilli()
            }
            assertEquals(start.toString(), field(Events.DTSTART)); assertEquals(end.toString(), field(Events.DTEND))
        }

        fun changeParentType(allDay: Boolean) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                put(Events.ALL_DAY, if (allDay) 1 else 0); put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.DTSTART, Instant.parse("2030-10-27T00:00:00Z").toEpochMilli()); put(Events.DURATION, "P1D")
            }, "${Events._ID}=?", arrayOf(masterRow!!.toString())))
        }

        fun changeDisplay(time: EventTime) {
            val values = ContentValues().apply { put(Events.DIRTY, 1) }
            when (time) {
                is EventTime.AllDay -> values.apply {
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                }
                is EventTime.Timed -> values.apply {
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                }
            }
            mutate(values)
        }

        fun parent(): Map<String, String?> {
            val columns = arrayOf(Events._ID, Events.TITLE, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE, Events.ALL_DAY, Events.DIRTY, Events.DELETED)
            return context.contentResolver.query(uri, columns, "${Events.CALENDAR_ID}=? AND ${Events._ID}=?",
                arrayOf(calendar.toString(), masterRow!!.toString()), null)!!.use { cursor ->
                assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
                columns.mapIndexed { index, column -> column to if (cursor.isNull(index)) null else cursor.getString(index) }.toMap()
            }
        }

        fun removeMaster() {
            assertEquals(1, context.contentResolver.delete(uri, "${Events.CALENDAR_ID}=? AND ${Events._ID}=?",
                arrayOf(calendar.toString(), masterRow!!.toString())))
        }

        private fun fixturePoint(allDay: Boolean, days: Long): RecurrencePoint = if (allDay)
            RecurrencePoint.AllDay(LocalDate.of(2030, 10, 27).plusDays(days))
        else RecurrencePoint.Timed(Instant.parse(if (nonAmbiguousStart) "2030-10-27T09:00:00Z" else "2030-10-27T00:30:00Z").atZone(ZoneId.of("Europe/Paris")).plusDays(days).toInstant())

        fun changedMaster(): RemoteChange.Upsert = latest.copy(content = latest.content.copy(
            recurrence = latest.content.recurrence!!.copy(rule = latest.content.recurrence!!.rule?.let { "FREQ=DAILY;COUNT=4" },
                additionalDates = listOf(fixturePoint(base.content.time is EventTime.AllDay, 4), fixturePoint(base.content.time is EventTime.AllDay, 6)),
                excludedDates = listOf(fixturePoint(base.content.time is EventTime.AllDay, 2)))))

        fun instances(from: String = "2030-10-26T00:00:00Z", until: String = "2030-11-05T00:00:00Z"): List<Triple<Long, Long, Long>> {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, Instant.parse(from).toEpochMilli())
            ContentUris.appendId(builder, Instant.parse(until).toEpochMilli())
            return context.contentResolver.query(builder.build(), arrayOf(CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), "${CalendarContract.Instances.BEGIN} ASC")!!.use { cursor ->
                buildList { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))) }
            }
        }

        suspend fun importLinkedException(): RemoteChange.Upsert {
            val parent = row()
            val child = latest.copy(eventId = EventId("fixture-linked-occurrence"), content = latest.content.copy(
                title = "Linked occurrence", recurrence = EventRecurrence(base.content.recurrence!!.uid,
                    original = fixturePoint(base.content.time is EventTime.AllDay, 0))))
            queue(child); assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
            occurrenceRow = context.contentResolver.query(uri, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID}=?", arrayOf(calendar.toString(), parent.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
            }
            return child
        }

        fun linkedException(): Map<String, String?> {
            val columns = arrayOf(Events._ID, Events.TITLE, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
                Events.DTSTART, Events.DTEND, Events.STATUS, Events.DIRTY, Events.DELETED)
            return context.contentResolver.query(uri, columns, "${Events._ID}=?", arrayOf(occurrenceRow!!.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst())
                columns.mapIndexed { index, column -> column to if (it.isNull(index)) null else it.getString(index) }.toMap()
            }
        }

        fun insertSeriesCollision(): Long {
            val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DURATION, Events.RRULE,
                Events.RDATE, Events.EXDATE, Events.ALL_DAY)
            val values = context.contentResolver.query(uri, columns, primarySelection, primaryArgs, null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); ContentValues().apply {
                    columns.forEachIndexed { index, column -> if (it.isNull(index)) putNull(column) else put(column, it.getString(index)) }
                }
            }.apply { put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Series identity collision"); put(Events.SYNC_DATA4, "collision-fixture") }
            return ContentUris.parseId(context.contentResolver.insert(uri, values)!!)
        }

        fun removeRow(id: Long) { assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(id.toString()))) }

        suspend fun recreateCalendar() {
            calendar = native.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer projection recovery fixture"))
        }

        fun ackFailure(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_projection_ack BEFORE UPDATE OF pending ON records " +
                    "WHEN NEW.pending=0 BEGIN SELECT RAISE(ABORT, 'fixture interrupted projection acknowledgement'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_projection_ack")
            }
        }

        fun row(): Long = context.contentResolver.query(uri, arrayOf(Events._ID),
            primarySelection, primaryArgs, null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
        }

        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            primarySelection, primaryArgs, null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }

        fun edit(title: String) {
            mutate(ContentValues().apply {
                put(Events.TITLE, title); put(Events.DIRTY, 1)
            })
        }

        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values,
                primarySelection, primaryArgs))
        }

        fun rowCount() = context.contentResolver.query(uri, arrayOf(Events._ID),
            primarySelection, primaryArgs, null)!!.use { it.count }

        private val reminderUri get() = Reminders.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, native.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, native.accountFor(target.account).type).build()

        fun reminders(event: Long = row()): Map<Long, Pair<Int, Int>> = context.contentResolver.query(reminderUri,
            arrayOf(Reminders._ID, Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?",
            arrayOf(event.toString()), null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getInt(1) to cursor.getInt(2)) }
        }

        fun editReminder(minutes: Int) {
            val first = reminders().keys.minOrNull()!!
            assertEquals(1, context.contentResolver.update(reminderUri, ContentValues().apply { put(Reminders.MINUTES, minutes) },
                "${Reminders.EVENT_ID}=? AND ${Reminders._ID}=?", arrayOf(row().toString(), first.toString())))
            mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        }

        private val attendeeUri get() = Attendees.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, native.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, native.accountFor(target.account).type).build()

        fun attendees(event: Long = row()): Map<Long, ParticipantRow> = context.contentResolver.query(attendeeUri,
            arrayOf(Attendees._ID, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_TYPE,
                Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP), "${Attendees.EVENT_ID}=?",
            arrayOf(event.toString()), null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), ParticipantRow(cursor.getString(1), cursor.getString(2),
                cursor.getInt(3), cursor.getInt(4), cursor.getInt(5))) }
        }

        fun editParticipant() {
            val guest = attendees().entries.single { it.value.email == "guest@example.test" }.key
            assertEquals(1, context.contentResolver.update(attendeeUri, ContentValues().apply {
                put(Attendees.ATTENDEE_NAME, "Local guest"); put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_DECLINED)
            }, "${Attendees.EVENT_ID}=? AND ${Attendees._ID}=?", arrayOf(row().toString(), guest.toString())))
            mutate(ContentValues().apply { put(Events.ORGANIZER, "local-organizer@example.test"); put(Events.DIRTY, 1) })
        }

        fun duplicate() {
            val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DTEND)
            val values = context.contentResolver.query(uri, columns, "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); ContentValues().apply {
                    columns.forEachIndexed { index, column -> put(column, it.getString(index)) }
                }
            }.apply { put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Recovery duplicate fixture") }
            assertNotNull(context.contentResolver.insert(uri, values))
        }

        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); store.close()
            store = ImportStore(context, name)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, counted, jobs, scheduler)
            start()
        }

        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
            native.admitOperation = { true }; native.removeOwnedCalendar(target)
            val account = native.accountFor(target.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                assertTrue(manager.removeAccountExplicitly(account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(account.name + ":" + target.calendar.value).commit()
        }
    }

    private data class ParticipantRow(val email: String, val name: String, val role: Int, val status: Int, val relationship: Int)

    private suspend fun withFixture(reminders: Boolean = false, allDay: Boolean = false, participants: Boolean = false,
        master: Boolean = false, rdateOnly: Boolean = false, nonAmbiguousStart: Boolean = master,
        test: suspend (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), reminders, allDay, participants, master, rdateOnly, nonAmbiguousStart)
        assumeTrue(fixture.native.hasPermissions())
        try { fixture.initialize(); test(fixture) } finally { fixture.close() }
    }
}

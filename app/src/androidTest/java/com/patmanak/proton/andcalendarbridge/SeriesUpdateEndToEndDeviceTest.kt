// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Provider, SQLite, native crypto and production adapters. Only the service is controlled. */
@RunWith(AndroidJUnit4::class)
class SeriesUpdateEndToEndDeviceTest {
    @Test fun aNativeExceptionAfterCaptureKeepsTheSeriesIntentWithoutPreparingOrSending() = runBlocking {
        journey {
            val frozen = checkNotNull(localEdit())
            val child = insertNativeException()
            process(expectCurrentSource = false)
            assertEquals(0, crypto.sendCount); assertNull(retained)
            // The existing provenance-conflict route retains every frozen field and reports the hold.
            assertEquals(frozen.copy(state = LocalEditState.CONFLICT), localEdit()); assertEquals("1", field(Events.DIRTY))
            assertEquals("1", context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events.DIRTY),
                "${Events._ID}=?", arrayOf(child.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getString(0) })
        }
    }

    @Test fun aNativeExceptionDuringPreparationCannotBeRetainedClaimedOrSent() = runBlocking {
        journey(detachDuringPreparation = true) {
            val frozen = checkNotNull(localEdit())
            process()
            val child = checkNotNull(childDuringPreparation) { "Real preparation must finish and insert the exception" }
            assertEquals("1", context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events.DIRTY),
                "${Events._ID}=?", arrayOf(child.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getString(0) })
            assertEquals(0, crypto.sendCount); assertNull(retained)
            assertEquals(frozen, localEdit()); assertEquals("1", field(Events.DIRTY))
            store.admitTransaction = allow
            assertNull(store.personalEditPreparation(crypto.scope, crypto.base.eventId))
        }
    }

    @Test fun offPaletteSeriesEditKeepsItsRawNativeAndSqlIntentWithoutClaimOrSend() = runBlocking {
        journey(unsupportedColour = true) {
            val raw = checkNotNull(localEdit())
            assertEquals(0x7868e6, raw.content.colorRgb)
            process()
            assertEquals(0, crypto.sendCount); assertNull(retained)
            assertEquals(raw, localEdit()); assertEquals("1", field(Events.DIRTY))
            assertEquals(0xff7868e6.toInt().toString(), field(Events.EVENT_COLOR))
        }
    }
    @Test fun automaticOrdinarySeriesCaptureRearmsOnceAndReachesTheExistingPipeline() = runBlocking {
        for (allDay in listOf(false, true)) journey(allDay, automatic = true) {
            assertEquals(1, rearmed)
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureSystemLocalEdit(crypto.scope, crypto.base.eventId) { rearmed++ })
            assertEquals(1, rearmed)
            process()
            assertEquals(1, crypto.sendCount); assertEquals("0", field(Events.DIRTY)); assertNull(localEdit())
        }
    }

    @Test fun syntheticServicePortsQualifyTheFullSignedFamilyBeforePreparing() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runSeriesPipeline { service ->
            val local = LocalEditIntent(MutationId("pipeline-port-check"), service.scope, service.format, service.base,
                service.replacement, seriesProfile = SeriesEditProfile.of(service.base.content, service.replacement))
            val preparation = checkNotNull(service.preparations.prepareSeries(local, service.timestamp) { it == service.scope })
            assertTrue(preparation.isSeries)
            val id = PersonalUpdateAttemptIdentity.requestId(preparation, service.updates.seriesUpdateFormat!!)
            assertNotNull(service.updates.prepareSubmission(id, preparation) { it == service.scope })
            assertEquals(0, service.sendCount)
        })
    }

    @Test fun lostTransportResponseIsInspectedAfterOfflineRetentionWithoutAnotherDispatch() = runBlocking {
        journey {
            crypto.loseResponse = true; crypto.holdReadback = true
            process()
            val work = checkNotNull(retained)
            val unknown = checkNotNull(journal.read(crypto.scope, work.id, allow))
            assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, unknown.state)
            assertNotNull(unknown.protectedEvidence); assertEquals(1, crypto.sendCount)
            assertEquals("1", field(Events.DIRTY)); assertNull(journal.readVerified(crypto.scope, work.id, allow))
            val offline = operations.recoverOffline(TitleUpdateWorkQuery(crypto.scope), allow)
            assertEquals(1, offline.held); assertEquals(1, crypto.sendCount)
            assertEquals(unknown, journal.read(crypto.scope, work.id, allow))
            crypto.holdReadback = false
            val recovered = operations.inspect(TitleUpdateWorkQuery(crypto.scope), allow) { }
            assertEquals(1, recovered.completed)
            assertEquals(unknown, journal.read(crypto.scope, work.id, allow))
            assertEquals("0", field(Events.DIRTY)); assertEquals(1, crypto.sendCount)
            assertNotNull(journal.readCompletion(crypto.scope, work.id, allow))
        }
    }

    @Test fun ordinaryTimedAndAllDaySeriesReachNativeAndSqlAcknowledgementWithOneDispatch() = runBlocking {
        for (allDay in listOf(false, true)) journey(allDay) {
            assertFalse(process())
            assertEquals(1, crypto.sendCount)
            assertEquals("0", field(Events.DIRTY)); assertNull(field(Events.DTEND))
            assertEquals(crypto.replacement.recurrence!!.rule, field(Events.RRULE))
            assertEquals(duration(crypto.replacement.time), field(Events.DURATION))
            val work = checkNotNull(retained)
            val proof = checkNotNull(journal.readVerified(crypto.scope, work.id, allow))
            assertEquals(crypto.replacement, proof.event.content)
            assertNotNull(journal.readCompletion(crypto.scope, work.id, allow))
            assertNull(localEdit())
            store.admitTransaction = allow
            assertEquals(proof.event, store.projectedBaseline(crypto.scope, crypto.base.eventId))
            val snapshot = field(Events.SYNC_DATA3)
            operations.recoverOffline(TitleUpdateWorkQuery(crypto.scope), allow)
            assertEquals(snapshot, field(Events.SYNC_DATA3)); assertEquals(1, crypto.sendCount)
            val instances = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, Instant.parse("2026-10-23T00:00:00Z").toEpochMilli())
                ContentUris.appendId(it, Instant.parse("2026-11-02T00:00:00Z").toEpochMilli())
            }.build()
            context.contentResolver.query(instances, arrayOf(CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use { assertEquals(4, it.count) }
        }
    }

    @Test fun aLostNativeAcknowledgementResumesOfflineAndPreservesTheNextPhoneEdit() = runBlocking {
        journey(loseNativeReply = true) {
            process()
            assertEquals(1, crypto.sendCount); assertEquals("0", field(Events.DIRTY))
            assertNotNull(localEdit())
            mutate(ContentValues().apply { put(Events.TITLE, "Newer local series title"); put(Events.DIRTY, 1) })
            val before = field(Events.SYNC_DATA3)
            val progress = operations.recoverOffline(TitleUpdateWorkQuery(crypto.scope), allow)
            assertEquals(1, progress.completed)
            assertEquals(before, field(Events.SYNC_DATA3)); assertEquals("1", field(Events.DIRTY))
            assertEquals("Newer local series title", field(Events.TITLE)); assertEquals(1, crypto.sendCount)
            assertNull(localEdit())
            assertEquals(LocalEditCaptureResult.RETAINED,
                coordinator.captureLocalSeriesEdit(crypto.scope, crypto.base.eventId, MutationId("newer-series-owner")))
            assertEquals("Newer local series title", localEdit()!!.content.title)
        }
    }

    @Test fun aNewerPhoneEditBeforeAcknowledgementCannotBeCleanedOrCompleted() = runBlocking {
        journey(newerBeforeAck = true) {
            process()
            assertEquals(1, crypto.sendCount); assertEquals("1", field(Events.DIRTY))
            assertEquals("Newer local series title", field(Events.TITLE))
            val work = checkNotNull(retained)
            assertNotNull(journal.readVerified(crypto.scope, work.id, allow))
            assertNull(journal.readCompletion(crypto.scope, work.id, allow))
            assertNotNull(localEdit())
            operations.recoverOffline(TitleUpdateWorkQuery(crypto.scope), allow)
            assertEquals("1", field(Events.DIRTY)); assertEquals(1, crypto.sendCount)
        }
    }

    @Test fun remoteExceptionsAndSessionRevocationKeepTheRawLocalSeriesWithoutClaiming() = runBlocking {
        for (revoke in listOf(false, true)) journey {
            if (revoke) crypto.revoke() else crypto.detachedChild = true
            process()
            assertEquals(0, crypto.sendCount); assertNull(retained)
            assertEquals("1", field(Events.DIRTY))
            assertEquals(crypto.replacement, localEdit()!!.content)
        }
    }

    private suspend fun journey(allDay: Boolean = false, loseNativeReply: Boolean = false,
        newerBeforeAck: Boolean = false, automatic: Boolean = false, unsupportedColour: Boolean = false,
        detachDuringPreparation: Boolean = false,
        action: suspend Fixture.() -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val result = ProtonSharedTitleCryptoProbe.runSeriesPipeline(allDay) { crypto ->
            val fixture = Fixture(crypto, loseNativeReply, newerBeforeAck, automatic, unsupportedColour, detachDuringPreparation)
            try { fixture.seed(); fixture.action() } finally { fixture.close() }
        }
        assertEquals("RESULT=SUCCESS", result)
    }

    private class Fixture(val crypto: ProtonSeriesUpdatePipelineFixture, loseNativeReply: Boolean, newerBeforeAck: Boolean,
        private val automatic: Boolean, private val unsupportedColour: Boolean, detachDuringPreparation: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val name = "series-network-import-$token.db"
        private val outgoing = "series-network-outgoing-$token.db"
        val store = ImportStore(context, name)
        private val attempts = TitleUpdateAttemptStore(context, outgoing)
        val allow: (CalendarScope) -> Boolean = { it == crypto.scope }
        var retained: PersonalUpdateAttemptRecord? = null
        var rearmed = 0
        private val realJournal = attempts.personalJournal
        val journal = object : PersonalUpdateRecoveryJournal by realJournal {
            override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
                retained = record.frozen(); return realJournal.remember(record, admit)
            }
        }
        val projection = AndroidCalendarProjection(context) { EventColorMode.EVENT }
        private var loseReply = loseNativeReply
        private var newer = newerBeforeAck
        private val native = object : CalendarProjection by projection {
            override suspend fun acknowledgePersonalUpdate(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
                if (newer) { newer = false; mutate(ContentValues().apply { put(Events.TITLE, "Newer local series title"); put(Events.DIRTY, 1) }) }
                val applied = projection.acknowledgePersonalUpdate(receipt)
                if (loseReply && applied != null) { loseReply = false; throw java.io.IOException("Synthetic lost native response") }
                return applied
            }
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val scheduled = AtomicBoolean()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.set(true) }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = crypto.format
            override suspend fun currentAccount() = crypto.scope.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No incoming traversal")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic outgoing")
        }
        val coordinator = ImportCoordinator(context, store, remote, native, jobs, scheduler,
            systemCreatesEnabled = { true }, systemTitlesEnabled = { true }, systemTextsEnabled = { true }, systemTimesEnabled = { true })
        var childDuringPreparation: Long? = null
            private set
        private val preparations = object : CalendarPersonalPreparationPort by crypto.preparations {
            override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                val prepared = crypto.preparations.prepareSeries(intent, timestamp, admit)
                if (detachDuringPreparation && prepared != null) childDuringPreparation = insertNativeException()
                return prepared
            }
        }
        val operations = CoordinatorSystemPersonalOperations(coordinator, journal, preparations, crypto.updates)
        var row = -1L
        private var createdAccount = false
        fun uri(source: Uri) = source.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(crypto.scope.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(crypto.scope.account).type).build()
        suspend fun seed() {
            check(projection.hasPermissions())
            val account = projection.accountFor(crypto.scope.account)
            check(!AccountManager.get(context).getAccountsByType(account.type).contains(account)) { "Synthetic account already exists" }
            createdAccount = true
            val summary = CalendarSummary(crypto.scope.calendar, "Synthetic series pipeline")
            val calendar = projection.ensureCalendar(crypto.scope, summary)
            projection.apply(ProjectionIntent(crypto.scope, crypto.base))
            context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                check(it.count == 1 && it.moveToFirst()); row = it.getLong(0)
            }
            store.remember(crypto.scope.account, listOf(summary)); store.setEnabled(crypto.scope, true)
            store.transaction(crypto.scope) {
                putRemote(crypto.base); putProjection(ProjectionIntent(crypto.scope, crypto.base))
                checkpoint = PageCheckpoint(crypto.scope, crypto.format, traversalComplete = true)
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(crypto.scope, crypto.base)))
            mutate(seriesEditValues(if (unsupportedColour) crypto.replacement.copy(colorRgb = 0x7868e6) else crypto.replacement))
            val reminderUri = uri(CalendarContract.Reminders.CONTENT_URI)
            context.contentResolver.delete(reminderUri, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(row.toString()))
            for (reminder in crypto.replacement.details!!.reminders) context.contentResolver.insert(reminderUri, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, reminder.minutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }) ?: error("Synthetic reminder insertion failed")
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (!scheduled.get()) delay(10) }
            assertEquals(LocalEditCaptureResult.RETAINED, if (automatic)
                coordinator.captureSystemLocalEdit(crypto.scope, crypto.base.eventId) { rearmed++ }
                else coordinator.captureLocalSeriesEdit(crypto.scope, crypto.base.eventId, MutationId("series-local-owner")))
        }
        suspend fun process(expectCurrentSource: Boolean = true): Boolean {
            val reference = (coordinator.retainedLocalChanges(RetainedLocalChangeQuery(crypto.scope)) as RetainedLocalChangeListing.Available).page.entries.single()
            val admission = coordinator.systemPersonalRunAdmission(crypto.scope)
            assertTrue("Original scoped editing lease required", admission(crypto.scope))
            val source = coordinator.personalOwners.observe(crypto.scope, crypto.base.eventId, reference.mutationId, admission)
            if (expectCurrentSource) assertNotNull("Full local source required before preparation", source) else assertNull(source)
            return operations.process(crypto.scope, reference, MutationId("unused-candidate"), crypto.timestamp,
                coordinator.systemPersonalRunAdmission(crypto.scope)) { }
        }
        fun insertNativeException(): Long = ContentUris.parseId(checkNotNull(context.contentResolver.insert(uri(Events.CONTENT_URI),
            ContentValues().apply {
                put(Events.CALENDAR_ID, context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events.CALENDAR_ID),
                    "${Events._ID}=?", arrayOf(row.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) })
                put(Events.TITLE, "Synthetic retained occurrence"); put(Events.ORIGINAL_ID, row)
                put(Events.ORIGINAL_INSTANCE_TIME, Instant.parse("2026-10-25T09:00:00Z").toEpochMilli())
                put(Events.ORIGINAL_ALL_DAY, 0); put(Events.DTSTART, Instant.parse("2026-10-25T12:00:00Z").toEpochMilli())
                put(Events.DTEND, Instant.parse("2026-10-25T13:00:00Z").toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC"); put(Events.DIRTY, 1)
            })))
        suspend fun localEdit(): LocalEditIntent? {
            store.admitTransaction = allow
            return store.localEdit(crypto.scope, crypto.base.eventId)
        }
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(uri(Events.CONTENT_URI), values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun field(column: String): String? = context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            projection.admitOperation = { true }
            if (createdAccount) {
                projection.removeOwnedCalendar(crypto.scope)
                val account = projection.accountFor(crypto.scope.account)
                val accounts = AccountManager.get(context)
                if (accounts.getAccountsByType(account.type).contains(account)) check(accounts.removeAccountExplicitly(account))
            }
            store.close(); attempts.close(); context.deleteDatabase(name); context.deleteDatabase(outgoing)
        }
    }
}

internal fun duration(time: EventTime) = when (time) {
    is EventTime.AllDay -> "P${ChronoUnit.DAYS.between(time.start, time.endExclusive)}D"
    is EventTime.Timed -> "PT${Duration.between(time.start, time.end).seconds}S"
}

internal fun seriesEditValues(content: EventContent) = ContentValues().apply {
    put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description); put(Events.EVENT_LOCATION, content.location)
    put(Events.RRULE, content.recurrence!!.rule); put(Events.DURATION, duration(content.time)); putNull(Events.DTEND); put(Events.DIRTY, 1)
    if (content.colorRgb == null) putNull(Events.EVENT_COLOR) else put(Events.EVENT_COLOR, content.colorRgb!!.or(0xff000000.toInt()))
    put(Events.HAS_ALARM, if (content.details!!.reminders.isEmpty()) 0 else 1)
    when (val time = content.time) {
        is EventTime.AllDay -> {
            put(Events.DTSTART, time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
        }
        is EventTime.Timed -> {
            put(Events.DTSTART, time.start.toEpochMilli()); put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Calendar Provider and SQL, controlled preparation; never Proton service writes. */
@RunWith(AndroidJUnit4::class)
class LocalTitleCapturePreparationDeviceTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "capture-title-$token.db"
        val target = CalendarScope(AccountId("capture-title-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-title-import", 1)
        val preparedFormat = AdapterFormat("controlled-title-preparation", 1)
        val id = MutationId("synthetic-title-edit")
        val timestamp = Instant.parse("2030-10-02T10:00:00Z")
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("original"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 3), LocalDate.of(2030, 10, 4)), details = EventDetails()))
        val local = base.content.copy(title = "Local title fixture")
        val expected = LocalEditIntent(id, target, format, base, local)
        val native = AndroidCalendarProjection(context)
        val store = ImportStore(context, name)
        var calendar = 0L
        var row = 0L
        var reads = 0
        var calls = 0
        var permission = true
        var account: AccountId? = target.account
        var onAccount: suspend () -> Unit = {}
        var onCaptured: suspend () -> Unit = {}
        var onGateClosed: () -> Unit = {}
        var onPrepare: suspend (LocalEditIntent, Instant) -> LocalEditPreparation = { intent, time ->
            LocalEditPreparation(intent, time, preparedFormat, "synthetic-protected-fixture")
        }
        val projection = object : CalendarProjection by native {
            override var admitOperation: () -> Boolean
                get() = native.admitOperation
                set(value) { native.admitOperation = value; if (!value()) onGateClosed() }
            override fun hasPermissions() = permission && native.hasPermissions()
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                reads++; val result = native.captureOwnedSimpleEdit(scope, base); onCaptured(); return result
            }
        }
        val port = object : CalendarLocalEditPreparationPort {
            override val localTitleFormat = preparedFormat
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation {
                calls++; return onPrepare(intent, timestamp)
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { onAccount(); return account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val owner = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun seed() {
            native.admitOperation = { true }
            calendar = native.ensureCalendar(target, CalendarSummary(target.calendar, "Synthetic title capture"))
            native.apply(ProjectionIntent(target, base))
            row = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0) }
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic title capture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            mutate(ContentValues().apply { put(Events.TITLE, local.title); put(Events.DIRTY, 1) })
            owner.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        fun mutate(values: ContentValues) {
            val account = native.accountFor(target.account)
            val uri: Uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun field(column: String): String? = context.contentResolver.query(Events.CONTENT_URI, arrayOf(column),
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
            }
        suspend fun run(mutation: MutationId = id, time: Instant = timestamp) =
            owner.captureAndPrepareLocalTitle(target, base.eventId, mutation, time, port)
        suspend fun saved() = ImportStore(context, name).use { it.localEditPreparation(target, base.eventId) }
        suspend fun intent() = ImportStore(context, name).use { it.localEdit(target, base.eventId) }
        suspend fun cache(change: RemoteChange) {
            store.transaction(target) { putRemote(change); putProjection(ProjectionIntent(target, change)) }
        }
        suspend fun close() {
            onGateClosed = {}
            owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            native.admitOperation = { true }; native.removeOwnedCalendar(target)
            val manager = AccountManager.get(context)
            val account = native.accountFor(target.account)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account }) {
                assertTrue(manager.removeAccountExplicitly(account))
            }
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
    }

    @Test fun capturesRealProviderTitleAndRetainsPreparationWithoutClearingDirty() = fixture {
        assertEquals(LocalTitlePreparationResult.RETAINED, run())
        assertEquals(expected, intent()); assertEquals(expected, saved()!!.intent)
        assertEquals(timestamp, saved()!!.timestamp); assertEquals(1, reads); assertEquals(1, calls)
        assertEquals(local.title, field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        ImportStore(context, name).use { inspector ->
            assertEquals(base, inspector.projectedBaseline(target, base.eventId))
            assertTrue(inspector.pending(target).isEmpty())
        }
        assertFalse(projection.admitOperation())
    }

    @Test fun replayPreservesFirstOwnerAndPreparationWhileLaterAndroidEditRemainsDirty() = fixture {
        assertEquals(LocalTitlePreparationResult.RETAINED, run())
        val first = saved()
        mutate(ContentValues().apply { put(Events.TITLE, "Later Android edit"); put(Events.DIRTY, 1) })
        assertEquals(LocalTitlePreparationResult.HISTORICAL, run(time = timestamp.plusSeconds(60)))
        assertEquals(first, saved()); assertEquals(expected, intent()); assertEquals(1, reads); assertEquals(1, calls)
        assertEquals("Later Android edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run(mutation = MutationId("different")))
        assertEquals(first, saved()); assertEquals(1, reads); assertEquals(1, calls)
    }
    @Test fun editDuringPreparationStaysVisibleWithoutReplacingCapturedContent() = fixture {
        onPrepare = { intent, time ->
            mutate(ContentValues().apply { put(Events.TITLE, "Concurrent Android edit"); put(Events.DIRTY, 1) })
            LocalEditPreparation(intent, time, preparedFormat, "synthetic-protected-fixture")
        }
        assertEquals(LocalTitlePreparationResult.RETAINED, run())
        assertEquals(expected, saved()!!.intent); assertEquals(expected, intent())
        assertEquals("Concurrent Android edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun nonTitleContentIsCapturedButNeverPartiallyPrepared() = fixture {
        mutate(ContentValues().apply { put(Events.DESCRIPTION, "Changed description") })
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run())
        assertEquals(expected.copy(content = local.copy(description = "Changed description")), intent())
        assertNull(saved()); assertEquals(0, calls); assertEquals("Changed description", field(Events.DESCRIPTION))
        assertEquals("1", field(Events.DIRTY))
    }
    @Test fun invalidPreparationTimeKeepsCaptureWithoutCallingPort() = fixture {
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run(time = timestamp.plusNanos(1)))
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls)
        assertEquals("1", field(Events.DIRTY))
    }
    @Test fun cleanOrWrongRevisionProviderRowDoesNotProduceIntentOrPreparation() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 0) })
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run())
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.TITLE, base.content.title) })
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run())
        mutate(ContentValues().apply { put(Events.TITLE, local.title); put(Events.SYNC_DATA2, "Other revision") })
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, run())
        assertNull(intent()); assertNull(saved()); assertEquals(0, calls); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun newerImportBeforeCaptureRetainsConflictAndNeverCallsPreparer() = fixture {
        cache(base.copy(revision = Revision("newer"), content = base.content.copy(title = "Remote fixture")))
        assertEquals(LocalTitlePreparationResult.CONFLICT, run())
        assertEquals(expected.copy(state = LocalEditState.CONFLICT), intent()); assertNull(saved()); assertEquals(0, calls)
        assertEquals(local.title, field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun remoteChangeDuringPreparationRefusesCapsuleAndPreservesConflictedCapture() = fixture {
        onPrepare = { intent, time ->
            cache(base.copy(revision = Revision("newer"), content = base.content.copy(title = "Remote fixture")))
            LocalEditPreparation(intent, time, preparedFormat, "synthetic-protected-fixture")
        }
        assertEquals(LocalTitlePreparationResult.CONFLICT, run())
        assertEquals(expected.copy(state = LocalEditState.CONFLICT), intent()); assertNull(saved()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun reconnectAtCaptureBoundaryCannotRenewTheCombinedSessionLease() = fixture {
        onGateClosed = { onGateClosed = {}; owner.authenticationChanged(false); owner.authenticationChanged(true) }
        assertEquals(LocalTitlePreparationResult.INACTIVE, run())
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls); assertFalse(projection.admitOperation())
    }
    @Test fun pauseAndResumeAtCaptureBoundaryCannotRenewCombinedPolicy() = fixture {
        val selection = store.selections(target.account).single()
        onGateClosed = { onGateClosed = {}; owner.setEnabled(selection, false); owner.setEnabled(selection, true) }
        assertEquals(LocalTitlePreparationResult.INACTIVE, run())
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls)
    }
    @Test fun permissionsAndAccountBeforeCapturePreventAllProviderAndPortReads() = fixture {
        permission = false; assertEquals(LocalTitlePreparationResult.INACTIVE, run())
        permission = true; account = AccountId("foreign-fixture"); assertEquals(LocalTitlePreparationResult.INACTIVE, run())
        assertEquals(0, reads); assertEquals(0, calls); assertNull(intent()); assertNull(saved())
    }
    @Test fun permissionLossAtCaptureBoundaryRetainsIntentButDoesNotPrepare() = fixture {
        onGateClosed = { onGateClosed = {}; permission = false }
        assertEquals(LocalTitlePreparationResult.INACTIVE, run())
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun preparationFailureRetriesSameOwnerWithoutRecapturingProvider() = fixture {
        onPrepare = { _, _ -> throw LocalEditPreparationException(LocalEditPreparationFailure.NETWORK) }
        assertEquals(LocalTitlePreparationResult.UNAVAILABLE, run())
        assertEquals(expected, intent()); assertNull(saved()); assertFalse(projection.admitOperation())
        mutate(ContentValues().apply { put(Events.TITLE, "Later Android edit") })
        onPrepare = { intent, time -> LocalEditPreparation(intent, time, preparedFormat, "synthetic-protected-fixture") }
        assertEquals(LocalTitlePreparationResult.RETAINED, run(time = timestamp.plusSeconds(60)))
        assertEquals(expected, saved()!!.intent); assertEquals(1, reads); assertEquals(2, calls)
        assertEquals("Later Android edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun cancellationAfterCapturePreservesDurableOwnerAndClosesLeases() = fixture {
        coroutineScope {
            val command = async(start = CoroutineStart.LAZY) { run() }
            onGateClosed = { onGateClosed = {}; command.cancel() }
            command.start()
            try { command.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertTrue(command.isCancelled)
        }
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls); assertFalse(projection.admitOperation())
        assertEquals(LocalTitlePreparationResult.RETAINED, run()); assertEquals(1, reads)
    }
    @Test fun cancellationInPreparationPreservesCaptureForExplicitRetry() = fixture {
        onPrepare = { _, _ -> throw CancellationException("Controlled interruption") }
        try { run(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(expected, intent()); assertNull(saved()); assertFalse(projection.admitOperation())
        onPrepare = { intent, time -> LocalEditPreparation(intent, time, preparedFormat, "synthetic-protected-fixture") }
        assertEquals(LocalTitlePreparationResult.RETAINED, run()); assertEquals(1, reads); assertEquals(2, calls)
    }
    @Test fun unsupportedPreparationRetainsTheCapturedOwnerAndDirtyProvider() = fixture {
        onPrepare = { _, _ -> throw LocalEditPreparationException(LocalEditPreparationFailure.UNSUPPORTED) }
        assertEquals(LocalTitlePreparationResult.UNSUPPORTED, run())
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(1, calls)
        assertEquals("1", field(Events.DIRTY)); assertEquals(local.title, field(Events.TITLE))
    }
    @Test fun initialAccountReadFailureIsSanitizedAndClosesAllLeases() = fixture {
        onAccount = { error("Controlled account storage failure") }
        assertEquals(LocalTitlePreparationResult.UNAVAILABLE, run())
        assertEquals(0, reads); assertEquals(0, calls); assertNull(intent()); assertNull(saved())
        assertFalse(projection.admitOperation())
    }
    @Test fun accountReadFailureAfterCaptureKeepsOwnerWithoutCallingPreparer() = fixture {
        onGateClosed = { onGateClosed = {}; onAccount = { error("Controlled account storage failure") } }
        assertEquals(LocalTitlePreparationResult.UNAVAILABLE, run())
        assertEquals(expected, intent()); assertNull(saved()); assertEquals(0, calls); assertFalse(projection.admitOperation())
    }
}

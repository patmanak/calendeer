package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.content.OperationApplicationException
import android.os.RemoteException
import android.database.sqlite.*
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real isolated SQLite/Provider, injected interruption after a real write, no Proton traffic. */
@RunWith(AndroidJUnit4::class)
class ImportRecoveryDeviceTest {
    @Test fun lostProjectionAcknowledgementRetriesOfflineWithoutDuplicatingTheEvent() = runBlocking {
        withFixture { f ->
            f.failAfterWrite = true
            assertEquals(ImportRunResult.RETRY, f.coordinator.run(false))
            assertEquals("PENDING", f.selection().status)
            assertEquals(1, f.store.pending(f.target).size)
            val firstId = f.eventId()
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals("IMPORTED", f.selection().status)
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(firstId, f.eventId())
        }
    }

    @Test fun successfulLocalRetryDoesNotHideAnExistingNetworkFailure() = runBlocking {
        withFixture { f ->
            f.store.status(f.target, "NETWORK")
            f.failAfterWrite = true
            assertEquals(ImportRunResult.RETRY, f.coordinator.run(false))
            assertEquals("NETWORK", f.selection().status)
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals("NETWORK", f.selection().status)
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun permissionRefusalRetainsIntentWithoutRetryLoopAndRestoresOffline() = runBlocking {
        withFixture { f ->
            f.permission = false
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals("PERMISSION", f.selection().status)
            assertEquals(1, f.store.pending(f.target).size)
            f.permission = true
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals("IMPORTED", f.selection().status)
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun temporarySqliteFailuresAtWorkerBoundaryRetryButCorruptionDoesNot() = runBlocking {
        for (failure in listOf(SQLiteFullException(), SQLiteDiskIOException(), SQLiteCantOpenDatabaseException(),
            SQLiteDatabaseLockedException(), SQLiteTableLockedException())) {
            assertEquals(ImportRunResult.RETRY, executeImportWork({ true }, { throw failure }, { fail() }))
            assertEquals(ImportRunResult.RETRY, executeImportWork({ throw failure }, { fail(); ImportRunResult.COMPLETE }, { fail() }))
        }
        val corrupt = SQLiteDatabaseCorruptException()
        try {
            executeImportWork({ true }, { throw corrupt }, { fail() })
            fail("Corrupt storage must not be treated as transient")
        } catch (actual: SQLiteDatabaseCorruptException) { assertSame(corrupt, actual) }
    }

    @Test fun providerSanitationKeepsPermanentRejectionsOutOfTheRetryLoop() {
        for (failure in listOf(IllegalArgumentException(), IllegalStateException(),
            OperationApplicationException(), SQLiteConstraintException(), SQLiteDatabaseCorruptException())) {
            val category = providerFailureCategory(failure)
            assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, category)
            assertFalse(retryableImportFailure(CalendarProjectionException(category)))
        }
        for (failure in listOf(RemoteException(), SQLiteFullException(), SQLiteDatabaseLockedException())) {
            val category = providerFailureCategory(failure)
            assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, category)
            assertTrue(retryableImportFailure(CalendarProjectionException(category)))
        }
    }

    private class Fixture(val context: Context) {
        val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("recovery-fixture-$token"), CalendarId("fixture"), 0)
        val name = "recovery-fixture-$token.db"
        val native = AndroidCalendarProjection(context)
        var store = ImportStore(context, name)
        var failAfterWrite = false
        var permission = true
        val format = AdapterFormat("recovery-fixture", 1)
        val event = RemoteChange.Upsert(EventId("fixture"), Revision("one"), EventContent("Recovery fixture",
            EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2))))
        private val projection = object : CalendarProjection by native {
            override fun hasPermissions() = permission && native.hasPermissions()
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) {
                native.apply(intent, seriesUid)
                if (failAfterWrite) {
                    failAfterWrite = false
                    throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
                }
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
            override suspend fun fetch(request: PageRequest): RemotePage = error("Offline fixture must not fetch")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun start() {
            val before = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close()
            store = ImportStore(context, name)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
            start()
        }
        suspend fun selection() = store.selections(target.account).single()
        suspend fun eventId(): Long {
            val calendar = native.ensureCalendarState(target, CalendarSummary(target.calendar, "Recovery fixture")).id
            return context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
            }
        }
        suspend fun close() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            native.admitOperation = { true }
            native.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(native.accountFor(target.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(native.accountFor(target.account).name + ":" + target.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }
    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.native.hasPermissions())
        try {
            fixture.store.remember(fixture.target.account, listOf(CalendarSummary(fixture.target.calendar, "Recovery fixture")))
            fixture.store.setEnabled(fixture.target, true)
            fixture.store.transaction(fixture.target) {
                putRemote(fixture.event)
                putProjection(ProjectionIntent(fixture.target, fixture.event))
                checkpoint = PageCheckpoint(fixture.target, fixture.format, traversalComplete = true)
            }
            fixture.start()
            test(fixture)
        } finally { fixture.close() }
    }
}

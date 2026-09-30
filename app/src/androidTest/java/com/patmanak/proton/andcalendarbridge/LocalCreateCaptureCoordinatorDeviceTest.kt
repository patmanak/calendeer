// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real separate SQLite journals; controlled Provider/account ports, no API calls or CREATE dispatch. */
@RunWith(AndroidJUnit4::class)
class LocalCreateCaptureCoordinatorDeviceTest {
    @Test fun simpleLocalCreateIsRetainedOfflineWithoutDispatch() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(LocalCreateIntent(reservation, content), retained())
        assertEquals(1, stamps); assertEquals(0, inspections)
    }

    @Test fun retainedIntentReturnsItsFirstContentAndFrozenTimestampWithoutProviderAccess() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        snapshot = snapshot!!.copy(content = content.copy(title = "Create fixture later edit"))
        assertEquals(LocalEditCaptureResult.RETAINED, capture(time = timestamp.plusSeconds(30)))
        assertEquals(LocalCreateIntent(reservation, content), retained())
        assertEquals(1, stamps); assertEquals(0, inspections)
    }

    @Test fun reservedButUnstampedWorkNeverFallsBackToAReboundRowHint() = fixture {
        journal.admitCapture = { it == target }; assertTrue(journal.reserve(reservation))
        snapshot = null
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture(row = 999))
        assertEquals(0, stamps); assertEquals(1, inspections)
        assertNull(retained())
        journal.admitCapture = { it == target }
        assertEquals(reservation, journal.readReservation(target, localId))
    }

    @Test fun orphanWithAQualifiedMarkerUsesInspectionAndTheFrozenTimestamp() = fixture {
        journal.admitCapture = { it == target }; assertTrue(journal.reserve(reservation))
        snapshot = snapshot!!.copy(eventRow = 88)
        assertEquals(LocalEditCaptureResult.RETAINED, capture(row = 999, time = timestamp.plusSeconds(30)))
        assertEquals(0, stamps); assertEquals(1, inspections)
        assertEquals(LocalCreateIntent(reservation, content), retained())
    }

    @Test fun failedFirstCandidateCannotBeStampedAgainWithAnotherRowHint() = fixture {
        snapshot = null
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture(row = 999))
        assertEquals(1, stamps); assertEquals(1, inspections)
        assertNull(retained())
    }

    @Test fun anotherMutationIdCannotReuseAnExistingReservation() = fixture {
        journal.admitCapture = { it == target }; journal.reserve(reservation)
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture(mutation = MutationId("other")))
        assertEquals(0, stamps); assertEquals(0, inspections); assertNull(retained())
    }

    @Test fun checkpointFormatMismatchPreventsReservationAndProviderAccess() = fixture {
        store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("foreign-format", 1)) }
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertEquals(0, stamps); assertEquals(0, inspections); assertNull(retained())
        journal.admitCapture = { it == target }; assertNull(journal.readReservation(target, localId))
    }

    @Test fun permissionsAndAnotherAccountRefuseCaptureBeforeNativeAccess() = fixture {
        permission = false
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        permission = true; account = AccountId("create-fixture-foreign")
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertEquals(0, stamps); assertEquals(0, inspections); assertNull(retained())
    }

    @Test fun disabledOrUnknownScopeCannotReserveThisCreate() = fixture {
        assertEquals(LocalEditCaptureResult.INACTIVE,
            coordinator.captureLocalCreate(target.copy(generation = 1), 7, localId, id, timestamp))
        store.setEnabled(target, false)
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertEquals(0, stamps); assertNull(retained())
    }

    @Test fun sessionInvalidationAfterStampPreventsIntentCommitButRetainsReservation() = fixture {
        onRead = { coordinator.authenticationChanged(false) }
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertNull(retained())
        journal.admitCapture = { it == target }; assertEquals(reservation, journal.readReservation(target, localId))
    }

    @Test fun permissionInvalidationAfterStampPreventsIntentCommit() = fixture {
        onRead = { permission = false }
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertNull(retained())
    }

    @Test fun cancellationAfterStampLeavesOnlyAnInspectableReservation() = fixture {
        onRead = { throw CancellationException("fixture interrupted after stamp") }
        var cancelled = false
        try { capture() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertNull(retained())
        journal.admitCapture = { it == target }; assertEquals(reservation, journal.readReservation(target, localId))
        onRead = {}
        assertEquals(LocalEditCaptureResult.RETAINED, capture(row = 999))
        assertEquals(1, stamps); assertEquals(1, inspections)
        assertEquals(LocalCreateIntent(reservation, content), retained())
    }

    @Test fun reopeningAnOrphanUsesTheMarkerInsteadOfTheRowHint() = fixture {
        journal.admitCapture = { it == target }; journal.reserve(reservation)
        reopen()
        assertEquals(LocalEditCaptureResult.RETAINED, capture(row = 999, time = timestamp.plusSeconds(60)))
        assertEquals(0, stamps); assertEquals(1, inspections)
        assertEquals(LocalCreateIntent(reservation, content), retained())
    }

    @Test fun competingHelperBetweenReservationAndIntentReadsCannotReturnForeignWorkAsRetained() = fixture {
        var guardedReadChecks = 0
        var injected = false
        val foreign = LocalCreateIntent(reservation.copy(id = MutationId("competing-create")),
            content.copy(title = "Create fixture competing helper snapshot"))
        permissionHook = {
            // readReservation is the owner's first journal operation. Its two lease checks bracket
            // the real SQLite read; inject after the absent result, before the separate readIntent.
            // D8 can rename the guardedRead lambda, so identify the journal rather than that lambda.
            val journalClass = LocalCreateStore::class.java.name
            val insideJournalRead = Thread.currentThread().stackTrace.any {
                it.className == journalClass || it.className.startsWith(journalClass + "$")
            }
            if (!injected && insideJournalRead && ++guardedReadChecks == 2) {
                injected = true
                competingRetain(foreign)
            }
        }
        val result = capture()
        permissionHook = {}
        assertTrue("Competing commit must occur after the absent reservation read", injected)
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, result)
        assertEquals(0, stamps); assertEquals(0, inspections)
        assertEquals(foreign, retained())
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val importName = "create-owner-import-$token.db"
        private val createName = "create-owner-capture-$token.db"
        val target = CalendarScope(AccountId("create-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("create-owner-fixture", 1)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val reservation = LocalCreateReservation(id, localId, target, format, timestamp)
        val content = EventContent("Create owner fixture", EventTime.AllDay(LocalDate.of(2030, 10, 1),
            LocalDate.of(2030, 10, 2)), details = EventDetails())
        val store = ImportStore(context, importName)
        var journal = LocalCreateStore(context, createName)
        var permission = true
        var permissionHook: () -> Unit = {}
        var account: AccountId? = target.account
        var stamps = 0
        var inspections = 0
        var onRead: suspend () -> Unit = {}
        var snapshot: OwnedLocalEditSnapshot? = OwnedLocalEditSnapshot(3, 7, content)
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions(): Boolean { permissionHook(); return permission }
            override fun accountFor(accountId: AccountId) = Account("create-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No import during capture")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No projection during capture")
            override suspend fun captureAndStampSimpleCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(7, eventRow.toInt()); assertEquals(this@Fixture.localId, localId)
                check(admitOperation()); stamps++; onRead(); return snapshot
            }
            override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.localId, localId)
                check(admitOperation()); inspections++; onRead(); return snapshot
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No API downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No API uploads")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal)
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Create capture fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            start()
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        suspend fun capture(row: Long = 7, mutation: MutationId = id, time: Instant = timestamp) =
            coordinator.captureLocalCreate(target, row, localId, mutation, time)
        suspend fun retained(): LocalCreateIntent? {
            journal.admitCapture = { it == target }; return journal.readIntent(target, localId)
        }
        fun competingRetain(intent: LocalCreateIntent) = runBlocking {
            LocalCreateStore(context, createName).use { competing ->
                competing.admitCapture = { it == target }
                assertTrue(competing.reserve(intent.reservation))
                assertEquals(intent, competing.retain(intent))
            }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); journal.close()
            journal = LocalCreateStore(context, createName); jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); context.deleteDatabase(importName); context.deleteDatabase(createName)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}

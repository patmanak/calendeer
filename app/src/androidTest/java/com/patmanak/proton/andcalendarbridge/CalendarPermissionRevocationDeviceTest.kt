package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
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

/** Opt-in host-orchestrated real grant revocation; isolated store and owned Provider rows only. */
@RunWith(AndroidJUnit4::class)
class CalendarPermissionRevocationDeviceTest {
    @Test fun pendingProjectionSurvivesSystemRevocationAndResumesOffline() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("calendarPermissionRevocation") == "true")
        val phase = arguments.getString("revocationPhase")
        require(phase in setOf("prepare", "denied", "restored", "cleanup"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("permission-revocation-fixture", Context.MODE_PRIVATE)
        val token = if (phase == "prepare") UUID.randomUUID().toString() else
            preferences.getString("token", null) ?: error("Missing preparation fixture")
        val target = CalendarScope(AccountId("permission-fixture-$token"), CalendarId("fixture"), 0)
        val databaseName = "permission-fixture-$token.db"
        val projection = AndroidCalendarProjection(context)
        val store = ImportStore(context, databaseName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = object : ReadOnlyCalendarRemote {
            override val format = AdapterFormat("permission-fixture", 1)
            override suspend fun currentAccount() = target.account
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
            override suspend fun fetch(request: PageRequest): RemotePage = error("Offline fixture must not fetch")
        }
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() = Unit
            override fun continueWork(network: Boolean) = Unit
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, scope, scheduler)
        try {
            if (phase == "prepare") {
                assertTrue("Initial calendar grants required", projection.hasPermissions())
                val event = RemoteChange.Upsert(EventId("fixture"), Revision("one"),
                    EventContent("BridgeQA permission recovery", EventTime.AllDay(
                        LocalDate.of(2031, 1, 1), LocalDate.of(2031, 1, 2))))
                store.remember(target.account, listOf(CalendarSummary(target.calendar, "Permission recovery fixture")))
                store.setEnabled(target, true)
                store.transaction(target) {
                    putRemote(event)
                    putProjection(ProjectionIntent(target, event))
                    checkpoint = PageCheckpoint(target, remote.format, traversalComplete = true)
                }
                assertEquals(1, store.pending(target).size)
                assertTrue(preferences.edit().putString("token", token).commit())
            } else if (phase == "denied") {
                assertFalse("System calendar grants must be revoked", projection.hasPermissions())
                assertEquals(1, store.pending(target).size)
                coordinator.authenticationChanged(true)
                assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false))
                assertEquals("PERMISSION", store.selections(target.account).single().status)
                assertEquals(1, store.pending(target).size)
            } else if (phase == "restored") {
                assertTrue("Calendar grants must be restored", projection.hasPermissions())
                assertEquals(1, store.pending(target).size)
                coordinator.authenticationChanged(true)
                assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false))
                assertEquals("IMPORTED", store.selections(target.account).single().status)
                assertTrue(store.pending(target).isEmpty())
                val calendar = projection.ensureCalendarState(target,
                    CalendarSummary(target.calendar, "Permission recovery fixture")).id
                context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
                    "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                    assertEquals("Pending event must be projected exactly once", 1, it.count)
                }
            } else {
                assertTrue("Calendar grants required for fixture cleanup", projection.hasPermissions())
                projection.removeOwnedCalendar(target)
                AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
                assertTrue(preferences.edit().clear().commit())
            }
        } finally {
            coordinator.authenticationChanged(false)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            store.close()
            if (phase == "cleanup") context.deleteDatabase(databaseName)
        }
    }
}

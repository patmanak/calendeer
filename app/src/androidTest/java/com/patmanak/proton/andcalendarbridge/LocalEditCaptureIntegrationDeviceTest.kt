// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Provider + SQLite + production owner; controlled remote, no live service mutation. */
@RunWith(AndroidJUnit4::class)
class LocalEditCaptureIntegrationDeviceTest {
    @Test fun newerImportAndAnActualAndroidEditSurviveCaptureAndDatabaseReopening() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("capture-integration-$token"), CalendarId("fixture"), 0)
        val name = "capture-integration-$token.db"
        val format = AdapterFormat("integration-fixture", 1)
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Integration baseline",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"),
                ZoneId.of("Europe/Paris")), details = EventDetails()))
        val local = base.content.copy(title = "Integration local edit")
        val latest = base.copy(revision = Revision("newer"), content = base.content.copy(title = "Integration remote edit"))
        val remote = object : ReadOnlyCalendarRemote {
            override val format = format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
        }
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ImportStore(context, name)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        try {
            val summary = CalendarSummary(target.calendar, "Calendeer capture integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            val calendar = projection.ensureCalendar(target, summary)
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            projection.apply(ProjectionIntent(target, base))
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            val account = projection.accountFor(target.account)
            val uri = Events.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                put(Events.TITLE, local.title); put(Events.DIRTY, 1)
            }, "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString())))
            store.transaction(target) { putRemote(latest); putProjection(ProjectionIntent(target, latest)) }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
            assertEquals(LocalEditCaptureResult.CONFLICT, coordinator.captureLocalEdit(target, base.eventId, MutationId("edit")))
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close()
            ImportStore(context, name).use { reopened ->
                val retained = reopened.localEdit(target, base.eventId)!!
                assertEquals(base, retained.base); assertEquals(local, retained.content)
                assertEquals(LocalEditState.CONFLICT, retained.state)
                assertEquals(latest, reopened.transaction(target) { remote(base.eventId) })
                reopened.requeueAll(target)
                assertTrue(reopened.pending(target).isEmpty())
                assertEquals(0, reopened.acknowledge(ProjectionIntent(target, latest)))
            }
            context.contentResolver.query(uri, arrayOf(Events.TITLE, Events.DIRTY),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst())
                assertEquals(local.title, it.getString(0)); assertEquals(1, it.getInt(1))
            }
        } finally {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target)
            val account = projection.accountFor(target.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                assertTrue(manager.removeAccountExplicitly(account))
        }
    }
}

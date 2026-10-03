// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.accounts.Account
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Core crypto and isolated SQLite; no account, network or Provider operations. */
@RunWith(AndroidJUnit4::class)
class LocalEditPreparationCryptoDeviceTest {
    @Test fun binarySignedTitleRecoversFromJournalWithOriginalPacketAndRenewedSignatures() = verify(false)
    @Test fun textSignedTitleRecoversFromJournalWithoutReplacingThePausedOwner() = verify(true)

    private fun verify(textSignatures: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-crypto-journal-${UUID.randomUUID()}.db"
        try {
            assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runProtectedJournal(textSignatures) { value ->
                val intent = value.intent
                val scope = intent.scope
                ImportStore(context, name).use { store ->
                    store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
                    assertEquals(scope, store.selections(scope.account).single().scope)
                    store.setEnabled(scope, true)
                    val projection = ProjectionIntent(scope, intent.base)
                    store.transaction(scope) { putRemote(intent.base); putProjection(projection); checkpoint = PageCheckpoint(scope, intent.format) }
                    assertEquals(1, store.acknowledge(projection))
                    assertEquals(intent, store.captureLocalEdit(intent))
                    val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val schedules = AtomicInteger()
                    var calls = 0
                    val scheduler = object : ImportScheduler {
                        override fun cancel() = Unit
                        override fun requestLocal() = Unit
                        override fun requestNetwork() { schedules.incrementAndGet() }
                        override fun continueWork(network: Boolean) = Unit
                    }
                    val remote = object : ReadOnlyCalendarRemote {
                        override val format = intent.format
                        override suspend fun currentAccount() = scope.account
                        override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
                        override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
                    }
                    val provider = object : CalendarProjection {
                        override var admitOperation: () -> Boolean = { false }
                        override fun hasPermissions() = true
                        override fun accountFor(accountId: AccountId) = Account("synthetic-title", "fixture")
                        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider writes")
                        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider writes")
                    }
                    val owner = ImportCoordinator(context, store, remote, provider, jobs, scheduler)
                    val prepared = object : CalendarLocalEditPreparationPort {
                        override val localTitleFormat = value.format
                        override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: java.time.Instant): LocalEditPreparation {
                            check(intent == value.intent && timestamp == value.timestamp); calls++; return value
                        }
                    }
                    try {
                        owner.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        assertEquals(LocalTitlePreparationResult.RETAINED,
                            owner.prepareLocalTitle(scope, intent.base.eventId, intent.id, value.timestamp, prepared))
                        assertEquals(LocalTitlePreparationResult.HISTORICAL,
                            owner.prepareLocalTitle(scope, intent.base.eventId, intent.id, value.timestamp.plusSeconds(60), prepared))
                        assertEquals(1, calls)
                    } finally { owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
                }
                // A different helper reads committed SQL, then a different Keystore/codec opens
                // only the retained record, without an externally captured UID or binding.
                // This is not a killed-process or automatic worker recovery journey.
                ImportStore(context, name).use { reopened ->
                    val recovered = requireNotNull(reopened.localEditPreparation(scope, intent.base.eventId))
                    assertEquals(value, recovered)
                    assertEquals(intent, reopened.localEdit(scope, intent.base.eventId))
                    assertEquals(intent.base, reopened.projectedBaseline(scope, intent.base.eventId))
                    assertEquals(intent.base, reopened.transaction(scope) { remote(intent.base.eventId) })
                    assertTrue(reopened.pending(scope).isEmpty())
                    assertFalse(reopened.retainLocalEditPreparation(recovered))
                    recovered
                }
            })
        } finally { context.deleteDatabase(name) }
    }
}

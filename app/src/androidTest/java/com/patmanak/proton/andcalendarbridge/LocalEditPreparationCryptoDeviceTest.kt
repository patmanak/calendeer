// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
                    store.transaction(scope) { putRemote(intent.base); putProjection(projection) }
                    assertEquals(1, store.acknowledge(projection))
                    assertEquals(intent, store.captureLocalEdit(intent))
                    assertTrue(store.retainLocalEditPreparation(value))
                }
                // A different helper reads committed SQL, then a different Keystore/codec opens it.
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

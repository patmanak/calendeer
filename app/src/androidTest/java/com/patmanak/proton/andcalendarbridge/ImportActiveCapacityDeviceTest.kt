package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real production capacity transactions, isolated SQL, no Provider or service access.
 * Synthetic receipts exercise accounting only; they are never admitted as protocol proofs. */
@RunWith(AndroidJUnit4::class)
class ImportActiveCapacityDeviceTest {
    private fun fixture(block: suspend (ImportStore, CalendarScope) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "capacity-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        val scope = CalendarScope(AccountId("synthetic-capacity"), CalendarId("fixture"), 0)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic capacity")))
            store.setEnabled(scope, true)
            block(store, scope)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    private fun seed(store: ImportStore, table: String, count: Int, state: String? = null) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            repeat(count) { index ->
                db.insertOrThrow(table, null, ContentValues().apply {
                    put("scope", "synthetic-history"); put("event", "event-$index")
                    if (table == "delete_acknowledgements") {
                        put("mutation", "mutation-$index"); put("attempt", "attempt-$index")
                        put("payload", "synthetic-deletion-barrier")
                    } else {
                        if (table.endsWith("_history")) put("attempt", "attempt-$index")
                        put("nonce", "nonce-$index"); put("receipt", "synthetic-completed-proof")
                    }
                    state?.let { put("state", it) }
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Test fun completedProofsAndDeletionBarriersDoNotConsumeActiveWorkBudget() = fixture { store, scope ->
        seed(store, "text_update_acknowledgement_history", 2100)
        seed(store, "time_update_acknowledgement_history", 2100)
        seed(store, "text_update_acknowledgements", 2100, "RESTORED")
        seed(store, "time_update_acknowledgements", 2100, "RESTORED")
        seed(store, "delete_acknowledgements", 2100, "RESTORED")
        val checkpoint = PageCheckpoint(scope, AdapterFormat("synthetic", 1), traversalComplete = true)
        store.transaction(scope) { this.checkpoint = checkpoint }
        assertEquals(checkpoint, store.transaction(scope) { this.checkpoint })
        for (table in listOf("text_update_acknowledgement_history", "time_update_acknowledgement_history",
            "text_update_acknowledgements", "time_update_acknowledgements", "delete_acknowledgements")) {
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Proofs/barriers must never be pruned", 2100, it.getInt(0))
            }
        }
    }

    @Test fun unresolvedReceiptsStillEnforceCapacityAndRollBackNewWork() = fixture { store, scope ->
        seed(store, "text_update_acknowledgements", 2100, "PENDING")
        try {
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, AdapterFormat("synthetic", 1)) }
            fail("Active work must still be bounded")
        } catch (_: ImportCapacityException) { }
        store.readableDatabase.rawQuery("SELECT checkpoint FROM calendars", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM text_update_acknowledgements WHERE state='PENDING'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(2100, it.getInt(0))
        }
    }
}

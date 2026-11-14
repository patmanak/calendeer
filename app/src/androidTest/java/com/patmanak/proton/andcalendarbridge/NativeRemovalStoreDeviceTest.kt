package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** App-private SQL fixtures; no session, network or Calendar Provider mutations. */
@RunWith(AndroidJUnit4::class)
class NativeRemovalStoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CalendarScope(AccountId("removal-account"), CalendarId("removal-calendar"), 0)
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val name = "native-removal-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }

    @Test fun pendingQueueSurvivesReopenAndPartialExactScopeAcknowledgement() = fixture { store, name ->
        val old = CalendarScope(AccountId("old-account"), scope.calendar, 7)
        store.writableDatabase.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,enabled,available,status) VALUES(?,?,?,?,?,0,0,'UNAVAILABLE')",
            arrayOf<Any>("[\"old-account\",\"removal-calendar\",7]", old.account.value, old.calendar.value, 7, "Old fixture"))
        store.setEnabled(scope, true); store.status(scope, "IMPORTED", 123)
        store.writableDatabase.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,0)",
            arrayOf("[\"removal-account\",\"removal-calendar\",0]", "retained-event", "opaque preserved bytes"))
        store.writableDatabase.execSQL("INSERT INTO captured_create_acknowledgements(scope,event,nonce,protocol,fingerprint,state) VALUES(?,?,?,1,?,'UNKNOWN')",
            arrayOf("[\"removal-account\",\"removal-calendar\",0]", "retained-event", "fixture-nonce", "fixture-fingerprint"))
        val retained = listOf("records", "captured_create_acknowledgements").associateWith { rows(store.readableDatabase, it) }
        val before = store.selections(scope.account)
        store.queueNativeRemovals(); store.queueNativeRemovals()
        assertEquals(setOf(scope, old), store.pendingNativeRemovals().toSet())
        assertEquals(before, store.selections(scope.account))
        store.remember(scope.account, emptyList())
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
        assertEquals(setOf(scope, old), store.pendingNativeRemovals().toSet())
        store.completeNativeRemoval(scope.copy(generation = 99))
        assertEquals(setOf(scope, old), store.pendingNativeRemovals().toSet())
        store.completeNativeRemoval(scope); store.completeNativeRemoval(scope)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(listOf(old), reopened.pendingNativeRemovals())
            reopened.completeNativeRemoval(old)
            assertTrue(reopened.pendingNativeRemovals().isEmpty())
            assertEquals(setOf(scope, old), reopened.calendarScopes().toSet())
            assertTrue(reopened.selections(scope.account).single().enabled)
            assertEquals(retained, retained.keys.associateWith { rows(reopened.readableDatabase, it) })
        }
    }

    @Test fun migrationsFromTwentyThreeAndEighteenPreserveBytesAndInitializePendingFalse() {
        for (version in listOf(23, 18)) fixture { store, name ->
            store.setEnabled(scope, true); store.status(scope, "NETWORK", 321)
            val db = store.writableDatabase
            // Rebuild only the fixture's calendar table to represent a genuine pre-24 schema.
            val columns = "scope,account,calendar,generation,title,color,enabled,checkpoint,status,completed_at,next_attempt,available"
            val trigger = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='trigger' AND tbl_name='calendars'", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            db.execSQL("CREATE TEMP TABLE calendar_snapshot AS SELECT $columns FROM calendars")
            db.execSQL("DROP TABLE calendars")
            db.execSQL("CREATE TABLE calendars(scope TEXT PRIMARY KEY,account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,title TEXT NOT NULL,color INTEGER,enabled INTEGER NOT NULL DEFAULT 0,checkpoint TEXT,status TEXT NOT NULL DEFAULT 'PAUSED',completed_at INTEGER,next_attempt INTEGER NOT NULL DEFAULT 0,available INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("INSERT INTO calendars($columns) SELECT $columns FROM calendar_snapshot")
            db.execSQL("DROP TABLE calendar_snapshot")
            trigger.forEach { db.execSQL(it) }
            if (version == 18) for (table in listOf("personal_edit_preparations", "personal_update_acknowledgements", "personal_update_acknowledgement_history", "delete_acknowledgements", "local_time_edit_preparations", "time_update_acknowledgements", "time_update_acknowledgement_history", "local_text_edit_preparations", "text_update_acknowledgements", "text_update_acknowledgement_history")) db.execSQL("DROP TABLE $table")
            val before = rows(db, "calendars")
            db.version = version; store.close()
            ImportStore(context, name).use { reopened ->
                assertEquals(25, reopened.readableDatabase.version)
                assertTrue(reopened.pendingNativeRemovals().isEmpty())
                assertEquals(before, rows(reopened.readableDatabase, "calendars").map { it.dropLast(1) })
                reopened.queueNativeRemovals()
                assertEquals(listOf(scope), reopened.pendingNativeRemovals())
            }
            ImportStore(context, name).use { assertEquals(listOf(scope), it.pendingNativeRemovals()) }
        }
    }

    @Test fun pendingColumnRejectsNonBooleanValues() = fixture { store, _ ->
        for (value in listOf("2", "-1", "NULL")) {
            try {
                store.writableDatabase.execSQL("UPDATE calendars SET native_removal_pending=$value")
                fail("Non-boolean native removal state accepted")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        }
    }
}

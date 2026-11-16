// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQL with synthetic series; no Provider, account session or service access. */
@RunWith(AndroidJUnit4::class)
class SeriesEditStoreDeviceTest {
    @Test fun explicitSeriesPreparationAndPendingAcknowledgementKeepTheirProfileAcrossReopen() = fixture { store, name ->
        val local = edit()
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format, traversalComplete = true) }
        assertEquals(local, store.captureSeriesEdit(local))
        val preparation = PersonalEditPreparation.forSeries(local, java.time.Instant.parse("2030-04-01T12:00:00Z"),
            AdapterFormat("series-preparation-fixture", 3), "protected-series")
        assertEquals(PersonalPreparationAdmission.STORED, store.retainCurrentPersonalPreparation(preparation))
        val work = PersonalUpdateAttemptRecord(MutationId("series-request"), preparation, AdapterFormat("series-request-fixture", 3),
            "protected-request", PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val event = base.copy(revision = Revision("verified-series-revision"), content = local.content)
        val proof = PersonalUpdateVerifiedResult.of(work, PersonalUpdateSubmissionObservation.Verified(work, event))
        val acknowledgement = checkNotNull(store.preparePersonalUpdateAcknowledgement(proof))
        assertTrue(acknowledgement.fresh); assertTrue(acknowledgement.record.receipt.isSeries)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(local, reopened.personalEditPreparation(scope, base.eventId)!!.intent)
            assertTrue(reopened.personalEditPreparation(scope, base.eventId)!!.isSeries)
            assertEquals(acknowledgement.record, reopened.personalUpdateAcknowledgement(scope, base.eventId, work.id))
            assertEquals(acknowledgement.copy(fresh = false), reopened.preparePersonalUpdateAcknowledgement(proof))
        }
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CalendarScope(AccountId("series-edit-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("series-edit-fixture", 1)
    private val rule = CreateSeriesRule(5)
    private val base = RemoteChange.Upsert(EventId("master"), Revision("opaque-revision"), EventContent("Series fixture",
        EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2)), details = EventDetails(listOf(EventReminder(10))),
        colorRgb = 0x123456, recurrence = EventRecurrence("fixture@calendeer.invalid", rule.rule)))
    private fun edit() = LocalEditIntent(MutationId("edit"), scope, format, base, base.content.copy(title = "Edited series"),
        seriesProfile = SeriesEditProfile(rule, rule))
    private val key get() = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()

    @Test fun explicitSeriesCaptureSurvivesReopenAndBlocksIncomingProjection() = fixture { store, name ->
        val expected = SeriesEditCandidate(edit()).intent
        assertEquals(expected, store.captureSeriesEdit(edit()))
        assertEquals(expected, store.captureSeriesEdit(edit()))
        assertEquals(expected, store.inspectLocalEdit(scope, base.eventId, expected.id)!!.owner)
        store.requeueAll(scope); assertTrue(store.pending(scope).isEmpty())
        assertEquals(1, store.selections(scope.account).single().retainedLocalChangeCount)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(expected, reopened.localEdit(scope, base.eventId))
            assertEquals(PersonalPreparationAdmission.UNQUALIFIED, reopened.assessPersonalPreparation(expected))
            assertTrue(reopened.pending(scope).isEmpty())
        }
    }
    @Test fun newRemoteVariantKeepsTheOriginalSeriesIntentAsAConflict() = fixture { store, name ->
        store.captureSeriesEdit(edit())
        val remote = base.copy(revision = Revision("other-revision"), content = base.content.copy(description = "Remote variant"))
        store.transaction(scope) { putRemote(remote) }
        val retained = store.localEdit(scope, base.eventId)!!
        assertEquals(edit().copy(state = LocalEditState.CONFLICT), retained)
        assertEquals(retained, store.captureSeriesEdit(edit()))
        assertEquals(retained, store.inspectLocalEdit(scope, base.eventId, retained.id)!!.owner)
        assertTrue(store.pending(scope).isEmpty())
        val ref = store.conflictArchives(ConflictArchiveQuery(scope)).entries.single()
        store.close()
        ImportStore(context, name).use { reopened ->
            val archive = reopened.conflictArchive(ref)!!
            assertEquals(retained.seriesProfile, archive.seriesProfile)
            assertEquals(base, archive.base); assertEquals(retained.content, archive.local)
            val variants = reopened.conflictObservations(ConflictObservationQuery(ref)).entries.mapNotNull { it.change }
            assertTrue(variants.contains(base)); assertTrue(variants.contains(remote))
        }
    }
    @Test fun initiallyConflictedSeriesKeepsItsExplicitArchiveProfileAndRejectsDowngrade() = fixture { store, _ ->
        val remote = base.copy(content = base.content.copy(description = "Already changed remotely"))
        store.transaction(scope) { putRemote(remote) }
        assertEquals(edit().copy(state = LocalEditState.CONFLICT), store.captureSeriesEdit(edit()))
        val ref = store.conflictArchives(ConflictArchiveQuery(scope)).entries.single()
        assertEquals(edit().seriesProfile, store.conflictArchive(ref)!!.seriesProfile)
        assertThrows(SQLiteException::class.java) { store.writableDatabase.execSQL("UPDATE conflict_archives SET edit_profile=3") }
        store.writableDatabase.execSQL("UPDATE conflict_archives SET edit_profile=1")
        assertThrows(IllegalStateException::class.java) { runBlocking { store.conflictArchive(ref) } }
        store.writableDatabase.execSQL("UPDATE conflict_archives SET edit_profile=2")
        assertEquals(edit().content, store.conflictArchive(ref)!!.local)
    }
    @Test fun simpleEntryRejectsSeriesBeforeSuspensionAndDoesNotTouchSql() = fixture { store, _ ->
        var admissions = 0
        store.admitTransaction = { admissions++; false }
        try { store.captureLocalEdit(edit()); fail("Simple capture cannot adopt a series") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, admissions)
        store.admitTransaction = { true }
        assertNull(store.localEdit(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun profileVersionAndCanonicalUidCannotBeReinterpreted() = fixture { store, _ ->
        store.captureSeriesEdit(edit())
        assertThrows(SQLiteException::class.java) { store.writableDatabase.execSQL("UPDATE local_edits SET edit_profile=3") }
        assertThrows(SQLiteException::class.java) { store.writableDatabase.execSQL("UPDATE local_edits SET kind='DELETE'") }
        store.writableDatabase.execSQL("UPDATE local_edits SET edit_profile=1")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.localEdit(scope, base.eventId) } }
        store.writableDatabase.execSQL("UPDATE local_edits SET edit_profile=2")
        val foreign = base.copy(content = edit().content.copy(recurrence = base.content.recurrence!!.copy(uid = "foreign@calendeer.invalid")))
        store.writableDatabase.execSQL("UPDATE local_edits SET local=?", arrayOf(ImportPayloadCodec.encodeChange(foreign)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.localEdit(scope, base.eventId) } }
    }
    @Test fun migrationFromIndependentVersionTwentyFiveKeepsEveryHistoricalByteAndDefaultsToSimple() = legacy { name, before ->
        ImportStore(context, name).use { store ->
            val db = store.writableDatabase
            assertEquals(26, db.version); assertEquals(before, rows(db))
            db.rawQuery("SELECT edit_profile FROM local_edits", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT base,local,edit_profile FROM conflict_archives", null).use {
                assertTrue(it.moveToFirst()); assertEquals(before.single()[5], it.getString(0))
                assertEquals(before.single()[6], it.getString(1)); assertEquals(1, it.getInt(2))
            }
            val retained = store.localEdit(scope, EventId("legacy"))!!
            assertNull(retained.seriesProfile); assertEquals(LocalEditState.CONFLICT, retained.state)
        }
    }
    @Test fun interruptedProfileMigrationRollsBackSchemaAndPayloadThenReopens() = legacy { name, before ->
        object : SQLiteOpenHelper(context, name, null, 26) {
            override fun onCreate(db: SQLiteDatabase) = error("Legacy database required")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                ImportSchema.upgrade(db, oldVersion, newVersion) { }; error("Synthetic interrupted profile migration")
            }
        }.use { helper -> assertThrows(IllegalStateException::class.java) { helper.writableDatabase } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(25, it.version); assertEquals(before, rows(it))
            for (table in listOf("local_edits", "conflict_archives")) it.rawQuery("PRAGMA table_info($table)", null).use { columns ->
                while (columns.moveToNext()) assertNotEquals("edit_profile", columns.getString(1))
            }
        }
        ImportStore(context, name).use { assertEquals(26, it.readableDatabase.version); assertEquals(before, rows(it.readableDatabase)) }
    }
    private fun rows(db: SQLiteDatabase) = db.rawQuery("SELECT scope,mutation,event,adapter,version,base,local,state,kind FROM local_edits ORDER BY event", null).use {
        buildList { while (it.moveToNext()) add((0 until it.columnCount).map { index -> it.getString(index) }) }
    }
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val name = "series-edit-${UUID.randomUUID()}.db"
        try { ImportStore(context, name).use { store ->
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Series fixture"))); store.setEnabled(scope, true)
            store.transaction(scope) { putRemote(base); putProjection(ProjectionIntent(scope, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            block(store, name)
        } } finally { context.deleteDatabase(name) }
    }
    private fun legacy(block: suspend (String, List<List<String>>) -> Unit) = runBlocking {
        val name = "legacy-series-edit-${UUID.randomUUID()}.db"
        try {
            val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE calendars(scope TEXT PRIMARY KEY,account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,title TEXT NOT NULL,color INTEGER,enabled INTEGER NOT NULL DEFAULT 0,checkpoint TEXT,status TEXT NOT NULL DEFAULT 'PAUSED',completed_at INTEGER,next_attempt INTEGER NOT NULL DEFAULT 0,available INTEGER NOT NULL DEFAULT 1,native_removal_pending INTEGER NOT NULL DEFAULT 0 CHECK(native_removal_pending IN (0,1)))")
                db.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,enabled) VALUES(?,?,?,?,?,1)",
                    arrayOf<Any>(key, scope.account.value, scope.calendar.value, scope.generation, "Legacy fixture"))
                db.execSQL("CREATE TABLE local_edits(scope TEXT NOT NULL,mutation TEXT NOT NULL,event TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,base TEXT NOT NULL,local TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PAUSED','CONFLICT')),kind TEXT NOT NULL DEFAULT 'UPDATE' CHECK(kind IN ('UPDATE','DELETE')),PRIMARY KEY(scope,mutation),UNIQUE(scope,event))")
                db.execSQL("CREATE TABLE conflict_archives(id INTEGER PRIMARY KEY AUTOINCREMENT,scope TEXT NOT NULL,event TEXT NOT NULL,mutation TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('UPDATE','DELETE')),adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),base TEXT NOT NULL,local TEXT NOT NULL,UNIQUE(scope,mutation))")
                val original = """{"id":"legacy","revision":"opaque","deleted":false,"title":"Original","description":"","location":"","allDay":true,"start":"2030-04-01","end":"2030-04-02"}"""
                db.execSQL("INSERT INTO local_edits VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any>(key, "legacy-edit", "legacy", "legacy", 1,
                    original, original.replace("Original", "Local"), "CONFLICT", "UPDATE"))
                db.execSQL("INSERT INTO conflict_archives(scope,event,mutation,kind,adapter,version,base,local) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(key, "legacy", "legacy-edit", "UPDATE", "legacy", 1, original, original.replace("Original", "Local")))
                db.version = 25; rows(db)
            }
            block(name, before)
        } finally { context.deleteDatabase(name) }
    }
}

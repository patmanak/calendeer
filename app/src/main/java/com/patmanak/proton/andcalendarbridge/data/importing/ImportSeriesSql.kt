package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.sync.*

/** Recurrence membership and dependency queue state on the caller's connection/transaction.
 * No database lifecycle, admission changes, Provider calls or remote authority live here.
 * A deferred master is unknown; it must never become evidence of a deleted series.
 */
internal object ImportSeriesSql {
    private data class SeriesMember(val uid: String, val role: String)
    enum class MasterState {
        INACTIVE, ALL_DAY, TIMED;
        fun accepts(original: RecurrencePoint?): Boolean = when (this) {
            INACTIVE -> false
            ALL_DAY -> original is RecurrencePoint.AllDay
            TIMED -> original is RecurrencePoint.Timed
        }
    }
    private fun seriesMember(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): SeriesMember? =
        db.rawQuery("SELECT uid,role FROM series_members WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value)).use {
            if (it.moveToFirst()) SeriesMember(it.getString(0), it.getString(1)) else null
        }
    fun masterUid(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): String? =
        seriesMember(db, scope, eventId)?.takeIf { it.role == "master" }?.uid

    /** Deferred parent contents cannot establish a usable dependency, even with a cached old copy. */
    fun masterStates(db: SQLiteDatabase, scope: CalendarScope): Map<String, MasterState> =
        db.rawQuery("SELECT s.uid,r.payload,EXISTS(SELECT 1 FROM deferred_events d WHERE d.scope=s.scope AND d.event=s.event) FROM series_members s JOIN records r ON r.scope=s.scope AND r.event=s.event WHERE s.scope=? AND s.role='master'", arrayOf(key(scope))).use { cursor ->
            buildMap {
                val unusable = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val uid = cursor.getString(0)
                    if (cursor.getInt(2) != 0) { unusable.add(uid); continue }
                    val content = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content
                    val recurrence = content?.recurrence
                    val active = recurrence != null && recurrence.isMaster && recurrence.uid == uid
                    if (active) put(uid, if (content!!.time is EventTime.AllDay) MasterState.ALL_DAY else MasterState.TIMED)
                    else if (uid !in this) put(uid, MasterState.INACTIVE)
                }
                // An older inactive association must not turn an unusable new parent into a deletion.
                unusable.forEach { uid -> if (get(uid) == MasterState.INACTIVE) remove(uid) }
            }
        }
    fun suppressInactiveChildren(db: SQLiteDatabase, scope: CalendarScope) {
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.event,r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND s.role='exception'", arrayOf(key(scope))).use { cursor ->
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster && masters[recurrence.uid] == MasterState.INACTIVE)
                    db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?", arrayOf(key(scope), cursor.getString(0)))
            }
        }
    }
    fun updateSeries(db: SQLiteDatabase, scope: CalendarScope, change: RemoteChange) {
        val previous = seriesMember(db, scope, change.eventId)
        val recurrence = (change as? RemoteChange.Upsert)?.content?.recurrence
        if (recurrence != null) {
            val role = if (recurrence.isMaster) "master" else "exception"
            require(previous == null || previous == SeriesMember(recurrence.uid, role)) { "Recurrence identity changed" }
            if (recurrence.isMaster) db.rawQuery("SELECT r.payload FROM series_members s JOIN records r ON r.scope=s.scope AND r.event=s.event WHERE s.scope=? AND s.uid=? AND s.role='master' AND s.event<>?",
                arrayOf(key(scope), recurrence.uid, change.eventId.value)).use { cursor ->
                while (cursor.moveToNext()) {
                    val other = (decodeChange(cursor.getString(0)) as? RemoteChange.Upsert)?.content?.recurrence
                    require(other == null || !other.isMaster || other.uid != recurrence.uid) { "Ambiguous recurrence master" }
                }
            }
            db.insertWithOnConflict("series_members", null, ContentValues().apply {
                put("scope", key(scope)); put("event", change.eventId.value); put("uid", recurrence.uid); put("role", role)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        } else if (change is RemoteChange.Upsert && previous?.role == "exception") {
            db.delete("series_members", "scope=? AND event=?", arrayOf(key(scope), change.eventId.value))
        }
        // Deleted records retain the association. A master becoming simple does too.
    }
    fun requeueSeriesChildren(db: SQLiteDatabase, scope: CalendarScope, uid: String) {
        val state = masterStates(db, scope)[uid] ?: return
        db.rawQuery("SELECT r.event,r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND s.uid=? AND s.role='exception' AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event) AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope), uid)).use { cursor ->
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster)
                    db.update("records", ContentValues().apply { put("pending", if (state != MasterState.INACTIVE) 1 else 0) },
                        "scope=? AND event=? AND pending<>2", arrayOf(key(scope), cursor.getString(0)))
            }
        }
    }
}

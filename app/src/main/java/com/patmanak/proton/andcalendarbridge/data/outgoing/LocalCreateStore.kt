// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Backup-excluded local capture journal, separate from remote dispatch and the import database.
 * Reserving identity permanently consumes the right to attempt a Provider stamp. Existing work is
 * inspect-only; no erase, resume, dispatch, row-ID fallback or automatic intent replacement exists.
 */
class LocalCreateStore(context: Context, name: String = "calendar-local-create-v1.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    private val lock = Mutex()
    @Volatile var admitCapture: (CalendarScope) -> Boolean = { true }

    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE local_creates (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                local_event TEXT NOT NULL, mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                created_at TEXT NOT NULL, marker_protocol INTEGER NOT NULL CHECK(marker_protocol=1),
                content TEXT,
                PRIMARY KEY(account, calendar, generation, local_event),
                UNIQUE(account, calendar, generation, mutation)
            )
        """.trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        error("Explicit local create journal migration required")

    /** Returns true only for the first durable reservation. A lost return can never reopen it. */
    suspend fun reserve(reservation: LocalCreateReservation): Boolean = mutation(reservation.scope) { db ->
        val existing = read(db, reservation.scope, reservation.localId)
        if (existing != null) {
            check(existing.first == reservation) { "Local create reservation is immutable" }
            false
        } else {
            db.insertOrThrow("local_creates", null, ContentValues().apply {
                put("account", reservation.scope.account.value); put("calendar", reservation.scope.calendar.value)
                put("generation", reservation.scope.generation); put("local_event", reservation.localId.value)
                put("mutation", reservation.id.value); put("adapter", reservation.format.id); put("version", reservation.format.version)
                put("created_at", reservation.timestamp.toString()); put("marker_protocol", reservation.markerProtocol)
                putNull("content")
            })
            true
        }
    }
    suspend fun readReservation(scope: CalendarScope, localId: LocalEventId): LocalCreateReservation? =
        guardedRead(scope) { db -> read(db, scope, localId)?.first }
    suspend fun readIntent(scope: CalendarScope, localId: LocalEventId): LocalCreateIntent? =
        guardedRead(scope) { db -> read(db, scope, localId)?.let { (reservation, payload) ->
            payload?.let { LocalCreateIntent(reservation, decodeContent(it, reservation)) }
        } }

    /** Retain only after qualified stamp/inspection. The first exact content is immutable. */
    suspend fun retain(intent: LocalCreateIntent): LocalCreateIntent = mutation(intent.reservation.scope) { db ->
        val reservation = intent.reservation
        val row = read(db, reservation.scope, reservation.localId)
        check(row != null && row.first == reservation) { "Local create requires its exact reservation" }
        val payload = row.second
        if (payload != null) {
            val existing = LocalCreateIntent(reservation, decodeContent(payload, reservation))
            check(existing == intent) { "Local create intent is immutable" }
            existing
        } else {
            check(db.update("local_creates", ContentValues().apply { put("content", encodeContent(intent)) },
                "$IDENTITY AND mutation=? AND adapter=? AND version=? AND created_at=? AND marker_protocol=? AND content IS NULL",
                identity(reservation.scope, reservation.localId) + arrayOf(reservation.id.value, reservation.format.id,
                    reservation.format.version.toString(), reservation.timestamp.toString(), reservation.markerProtocol.toString())) == 1) {
                "Local create reservation changed during capture"
            }
            intent
        }
    }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, localId: LocalEventId): Pair<LocalCreateReservation, String?>? =
        db.query("local_creates", null, IDENTITY, identity(scope, localId), null, null, null).use { cursor ->
            check(cursor.count <= 1) { "Ambiguous local create identity" }
            if (!cursor.moveToFirst()) null else {
                fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                val storedScope = CalendarScope(AccountId(string("account")), CalendarId(string("calendar")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("generation")))
                val reservation = LocalCreateReservation(MutationId(string("mutation")), LocalEventId(string("local_event")),
                    storedScope, AdapterFormat(string("adapter"), cursor.getInt(cursor.getColumnIndexOrThrow("version"))),
                    Instant.parse(string("created_at")), cursor.getInt(cursor.getColumnIndexOrThrow("marker_protocol")))
                check(reservation.scope == scope && reservation.localId == localId) { "Foreign local create identity" }
                val index = cursor.getColumnIndexOrThrow("content")
                reservation to if (cursor.isNull(index)) null else cursor.getString(index)
            }
        }

    private fun encodeContent(intent: LocalCreateIntent): String = JSONObject().apply {
        put("schema", 1); put("reservation", encodeReservation(intent.reservation)); put("knownEmptyDetails", true)
        put("title", intent.content.title); put("description", intent.content.description); put("location", intent.content.location)
        when (val time = intent.content.time) {
            is EventTime.AllDay -> { put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            is EventTime.Timed -> { put("allDay", false); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
        }
    }.toString()
    private fun encodeReservation(reservation: LocalCreateReservation) = JSONObject().apply {
        put("id", reservation.id.value); put("localId", reservation.localId.value)
        put("account", reservation.scope.account.value); put("calendar", reservation.scope.calendar.value); put("generation", reservation.scope.generation)
        put("adapter", reservation.format.id); put("version", reservation.format.version)
        put("timestamp", reservation.timestamp.toString()); put("markerProtocol", reservation.markerProtocol)
    }
    private fun decodeContent(payload: String, reservation: LocalCreateReservation): EventContent = JSONObject(payload).let { json ->
        require(json.getInt("schema") == 1 && json.getBoolean("knownEmptyDetails")) { "Unsupported local create content" }
        val fields = setOf("schema", "reservation", "knownEmptyDetails", "title", "description", "location", "allDay", "start", "end") +
            if (json.getBoolean("allDay")) emptySet() else setOf("zone")
        require(json.keys().asSequence().toSet() == fields) { "Unsupported local create fields" }
        val stored = json.getJSONObject("reservation")
        require(stored.keys().asSequence().toSet() == setOf("id", "localId", "account", "calendar", "generation",
            "adapter", "version", "timestamp", "markerProtocol")) { "Unsupported local create identity fields" }
        val storedReservation = LocalCreateReservation(MutationId(stored.getString("id")), LocalEventId(stored.getString("localId")),
            CalendarScope(AccountId(stored.getString("account")), CalendarId(stored.getString("calendar")), stored.getLong("generation")),
            AdapterFormat(stored.getString("adapter"), stored.getInt("version")), Instant.parse(stored.getString("timestamp")),
            stored.getInt("markerProtocol"))
        check(storedReservation == reservation) { "Foreign local create content" }
        val time = if (json.getBoolean("allDay")) EventTime.AllDay(LocalDate.parse(json.getString("start")), LocalDate.parse(json.getString("end")))
            else EventTime.Timed(Instant.parse(json.getString("start")), Instant.parse(json.getString("end")), ZoneId.of(json.getString("zone")))
        EventContent(json.getString("title"), time, json.getString("description"), json.getString("location"), details = EventDetails())
    }
    private fun identity(scope: CalendarScope, localId: LocalEventId) =
        arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), localId.value)

    private suspend fun <T> guardedRead(scope: CalendarScope, action: (SQLiteDatabase) -> T): T {
        val coroutine = currentCoroutineContext()
        return access { db ->
            coroutine.ensureActive(); check(admitCapture(scope)) { "Inactive local create lease" }
            val result = action(db)
            check(admitCapture(scope)) { "Inactive local create lease" }; coroutine.ensureActive()
            result
        }
    }
    private suspend fun <T> mutation(scope: CalendarScope, action: (SQLiteDatabase) -> T): T {
        val coroutine = currentCoroutineContext()
        return access { db ->
            coroutine.ensureActive(); check(admitCapture(scope)) { "Inactive local create lease" }
            db.beginTransaction()
            try {
                val result = action(db)
                check(admitCapture(scope)) { "Inactive local create lease" }; coroutine.ensureActive()
                db.setTransactionSuccessful()
                result
            } finally { db.endTransaction() }
        }
    }
    private suspend fun <T> access(action: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action(writableDatabase) }
    }
    private companion object { const val IDENTITY = "account=? AND calendar=? AND generation=? AND local_event=?" }
}

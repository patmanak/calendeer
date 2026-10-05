// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
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

/** Private, backup-excluded journal of one unresolved title attempt per remote event across generations.
 * Payload/evidence are opaque protected strings; this store grants no HTTP or Provider capability.
 */
class TitleUpdateAttemptStore(context: Context, name: String = "calendar-title-update-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 1), TitleUpdateAttemptJournal {
    private val lock = Mutex()
    @Volatile var admitDispatch: (CalendarScope) -> Boolean = { false }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=FULL")
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE title_update_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, event TEXT NOT NULL,
                preparation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),
                evidence TEXT,
                PRIMARY KEY(account,calendar,generation,mutation),
                UNIQUE(account,calendar,event)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Explicit title update journal migration required")
    }

    override suspend fun remember(record: TitleUpdateAttemptRecord) {
        val frozen = record.frozen()
        require(frozen.state == TitleUpdateAttemptState.PREPARED && frozen.protectedEvidence == null) {
            "Only prepared title work without evidence can be remembered"
        }
        val preparation = encodePreparation(frozen.preparation)
        access { db -> transaction(db) {
            val existing = read(db, frozen.scope, frozen.id)
            if (existing != null) {
                check(existing.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen) {
                    "Title update attempt is immutable"
                }
            } else {
                check(!hasEvent(db, frozen.scope, frozen.preparation.intent.base.eventId)) {
                    "An unresolved title attempt already owns this event"
                }
                db.insertOrThrow(TABLE, null, ContentValues().apply {
                    put("account", frozen.scope.account.value); put("calendar", frozen.scope.calendar.value)
                    put("generation", frozen.scope.generation); put("mutation", frozen.id.value)
                    put("event", frozen.preparation.intent.base.eventId.value); put("preparation", preparation)
                    put("adapter", frozen.format.id); put("version", frozen.format.version)
                    put("payload", frozen.protectedPayload); put("state", TitleUpdateAttemptState.PREPARED.name)
                })
            }
        } }
    }

    override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? =
        access { db -> read(db, scope, id) }

    /** The complete frozen preparation and ciphertext must still match before durable UNKNOWN.
     * SQLite transactions arbitrate independent helpers; successful commit precedes true/dispatch.
     */
    override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean {
        val frozen = expected.frozen()
        if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val preparation = encodePreparation(frozen.preparation)
        return access { db ->
            currentCoroutineContext().ensureActive()
            if (!admitDispatch(frozen.scope)) false else transaction(db) {
                val current = read(db, frozen.scope, frozen.id)
                val admitted = current == frozen && db.update(TABLE, ContentValues().apply {
                    put("state", TitleUpdateAttemptState.OUTCOME_UNKNOWN.name)
                }, "$IDENTITY AND event=? AND preparation=? AND adapter=? AND version=? AND payload=? AND state=? AND evidence IS NULL",
                    identity(frozen.scope, frozen.id) + arrayOf(frozen.preparation.intent.base.eventId.value,
                        preparation, frozen.format.id, frozen.format.version.toString(), frozen.protectedPayload,
                        TitleUpdateAttemptState.PREPARED.name)) == 1
                check(admitDispatch(frozen.scope)) { "Title update dispatch lease is not current" }
                admitted
            }
        }
    }

    override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) {
        val frozen = expectedUnknown.frozen()
        require(frozen.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) { "Evidence requires unknown title work" }
        // Apply the domain's strict bounded-string validation before storage suspension.
        frozen.copy(protectedEvidence = protectedEvidence)
        require(frozen.protectedEvidence == null || frozen.protectedEvidence == protectedEvidence) {
            "Title update evidence is immutable"
        }
        access { db -> transaction(db) {
            val existing = read(db, frozen.scope, frozen.id)
            check(existing != null && existing.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
                existing.copy(protectedEvidence = null) == frozen.copy(protectedEvidence = null)) {
                "Evidence requires identical unknown title work"
            }
            if (existing.protectedEvidence != null) {
                check(existing.protectedEvidence == protectedEvidence) { "Title update evidence is immutable" }
            } else {
                check(db.update(TABLE, ContentValues().apply { put("evidence", protectedEvidence) },
                    "$IDENTITY AND state=? AND evidence IS NULL",
                    identity(frozen.scope, frozen.id) + TitleUpdateAttemptState.OUTCOME_UNKNOWN.name) == 1) {
                    "Title update evidence was not retained"
                }
            }
        } }
    }

    private fun hasEvent(db: SQLiteDatabase, scope: CalendarScope, event: EventId): Boolean =
        db.query(TABLE, arrayOf("mutation"), "account=? AND calendar=? AND event=?",
            arrayOf(scope.account.value, scope.calendar.value, event.value),
            null, null, null).use { it.moveToFirst() }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? =
        db.query(TABLE, arrayOf("event", "preparation", "adapter", "version", "payload", "state", "evidence"),
            IDENTITY, identity(scope, id), null, null, null).use { row ->
            if (!row.moveToFirst()) null else try {
                val preparation = decodePreparation(row.getString(1))
                require(preparation.intent.scope == scope && preparation.intent.base.eventId.value == row.getString(0))
                TitleUpdateAttemptRecord(id, preparation, AdapterFormat(row.getString(2), version(row, 3)),
                    row.getString(4), TitleUpdateAttemptState.valueOf(row.getString(5)),
                    if (row.isNull(6)) null else row.getString(6)).also {
                    require(it.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN || it.protectedEvidence == null)
                }
            } catch (_: Exception) { throw IllegalStateException("Invalid retained title update work") }
        }

    /** This is a domain snapshot codec, never a Proton wire serializer or a protected-payload decoder. */
    private fun encodePreparation(value: LocalEditPreparation): String = bounded(JSONObject().apply {
        val intent = value.intent
        put("schema", 1); put("id", intent.id.value)
        put("account", intent.scope.account.value); put("calendar", intent.scope.calendar.value); put("generation", intent.scope.generation)
        put("intentAdapter", intent.format.id); put("intentVersion", intent.format.version); put("intentState", intent.state.name)
        put("event", intent.base.eventId.value); put("revision", intent.base.revision.value)
        put("base", contentJson(intent.base.content)); put("newTitle", intent.content.title)
        put("timestamp", value.timestamp.toString()); put("preparationAdapter", value.format.id)
        put("preparationVersion", value.format.version); put("preparationPayload", value.protectedPayload)
    }.toString())

    private fun contentJson(value: EventContent) = JSONObject().apply {
        require(value.details == EventDetails() && value.recurrence == null && value.colorRgb == null)
        put("title", value.title); put("description", value.description); put("location", value.location)
        put("knownEmptyDetails", true)
        when (val time = value.time) {
            is EventTime.AllDay -> { put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            is EventTime.Timed -> { put("allDay", false); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
        }
    }

    private fun decodePreparation(payload: String): LocalEditPreparation {
        val json = JSONObject(bounded(payload))
        require(json.keys().asSequence().toSet() == PREPARATION_FIELDS && integer(json, "schema") == 1L)
        val scope = CalendarScope(AccountId(text(json, "account")), CalendarId(text(json, "calendar")), integer(json, "generation"))
        val base = RemoteChange.Upsert(EventId(text(json, "event")), Revision(text(json, "revision")),
            decodeContent(json.getJSONObject("base")))
        val intent = LocalEditIntent(MutationId(text(json, "id")), scope,
            AdapterFormat(text(json, "intentAdapter"), version(json, "intentVersion")), base,
            base.content.copy(title = text(json, "newTitle")), LocalEditState.valueOf(text(json, "intentState")))
        val timeText = text(json, "timestamp")
        val timestamp = Instant.parse(timeText)
        require(timestamp.toString() == timeText)
        return LocalEditPreparation(intent, timestamp, AdapterFormat(text(json, "preparationAdapter"),
            version(json, "preparationVersion")), text(json, "preparationPayload"))
    }

    private fun decodeContent(json: JSONObject): EventContent {
        require(json.get("allDay") is Boolean && json.get("knownEmptyDetails") == true)
        val allDay = json.getBoolean("allDay")
        require(json.keys().asSequence().toSet() == CONTENT_FIELDS + if (allDay) emptySet() else setOf("zone"))
        val time = if (allDay) EventTime.AllDay(LocalDate.parse(text(json, "start")), LocalDate.parse(text(json, "end")))
            else EventTime.Timed(Instant.parse(text(json, "start")), Instant.parse(text(json, "end")), ZoneId.of(text(json, "zone")))
        return EventContent(text(json, "title"), time, text(json, "description"), text(json, "location"), details = EventDetails())
    }

    private fun text(json: JSONObject, key: String): String = json.get(key).let { require(it is String); it }
    private fun integer(json: JSONObject, key: String): Long = json.get(key).let {
        require(it is Int || it is Long); (it as Number).toLong()
    }
    private fun version(json: JSONObject, key: String): Int = integer(json, key).let {
        require(it in 1..Int.MAX_VALUE.toLong()); it.toInt()
    }
    private fun version(row: Cursor, column: Int): Int {
        require(row.getType(column) == Cursor.FIELD_TYPE_INTEGER)
        return row.getLong(column).let { require(it in 1..Int.MAX_VALUE.toLong()); it.toInt() }
    }
    private fun bounded(value: String): String {
        require(value.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        return value
    }
    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value,
        scope.calendar.value, scope.generation.toString(), id.value)

    private suspend fun <T> access(action: suspend (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { currentCoroutineContext().ensureActive(); action(writableDatabase) }
    }
    private suspend fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        try {
            val result = action()
            currentCoroutineContext().ensureActive()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    override fun toString() = "TitleUpdateAttemptStore(redacted)"
    private companion object {
        const val TABLE = "title_update_attempts"
        const val IDENTITY = "account=? AND calendar=? AND generation=? AND mutation=?"
        val PREPARATION_FIELDS = setOf("schema", "id", "account", "calendar", "generation", "intentAdapter", "intentVersion",
            "intentState", "event", "revision", "base", "newTitle", "timestamp", "preparationAdapter", "preparationVersion", "preparationPayload")
        val CONTENT_FIELDS = setOf("title", "description", "location", "knownEmptyDetails", "allDay", "start", "end")
    }
}

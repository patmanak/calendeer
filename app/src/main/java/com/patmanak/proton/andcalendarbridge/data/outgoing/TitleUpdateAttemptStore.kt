// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.data.serialization.TitleUpdateAcknowledgementCodec
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
    SQLiteOpenHelper(context, name, null, 3), TitleUpdateAttemptJournal, TitleUpdateCompletionJournal {
    private val lock = Mutex()
    override val completionProtocol = 1
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
                evidence TEXT, result TEXT, completion TEXT,
                PRIMARY KEY(account,calendar,generation,mutation)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX title_update_unresolved_event ON $TABLE(account,calendar,event) WHERE completion IS NULL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "Explicit title update journal migration required" }
        // SQLiteOpenHelper owns the migration transaction. Copy opaque bytes without interpreting
        // them; neither an existing verified result nor a state name establishes completion.
        if (oldVersion == 1) db.execSQL("ALTER TABLE $TABLE ADD COLUMN result TEXT")
        db.execSQL("ALTER TABLE $TABLE RENAME TO title_update_attempts_legacy")
        onCreate(db)
        db.execSQL("""INSERT INTO $TABLE(account,calendar,generation,mutation,event,preparation,
            adapter,version,payload,state,evidence,result,completion)
            SELECT account,calendar,generation,mutation,event,preparation,
            adapter,version,payload,state,evidence,result,NULL FROM title_update_attempts_legacy""".trimIndent())
        db.execSQL("DROP TABLE title_update_attempts_legacy")
    }

    override suspend fun remember(record: TitleUpdateAttemptRecord) {
        val frozen = record.frozen()
        require(frozen.state == TitleUpdateAttemptState.PREPARED && frozen.protectedEvidence == null) {
            "Only prepared title work without evidence can be remembered"
        }
        val preparation = encodePreparation(frozen.preparation)
        access { db -> transaction(db) {
            val history = eventHistory(db, frozen.scope, frozen.preparation.intent.base.eventId)
            val existing = read(db, frozen.scope, frozen.id)
            if (existing != null) {
                check(existing.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen) {
                    "Title update attempt is immutable"
                }
            } else {
                check(history.all { it.completed }) {
                    "An unresolved title attempt already owns this event"
                }
                check(history.none { it.localIntentId == frozen.preparation.intent.id ||
                    it.id == frozen.id }) { "A previous title intention cannot be admitted again" }
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

    override suspend fun retainCompletion(completion: TitleUpdateCompletion,
        admit: (CalendarScope) -> Boolean): Boolean {
        val frozen = completion.snapshot()
        val work = frozen.result.record
        val encoded = encodeCompletion(frozen)
        return access { db ->
            if (!admit(work.scope)) false else transaction(db, beforeCommit = {
                check(admit(work.scope)) { "Title completion lease is not current" }
            }) {
                val current = read(db, work.scope, work.id)
                check(current != null && current.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
                    current.copy(protectedEvidence = null) == work) {
                    "Completion requires identical unknown title work"
                }
                val retained = readVerified(db, current)
                check(retained != null && retained == frozen.result) {
                    "Completion requires the first durable verified result"
                }
                eventHistory(db, work.scope, work.preparation.intent.base.eventId)
                val existing = readCompletion(db, current)
                if (existing != null) check(existing == frozen) { "Title completion is immutable" }
                else check(db.update(TABLE, ContentValues().apply { put("completion", encoded) },
                    "$IDENTITY AND state=? AND completion IS NULL AND result=?",
                    identity(work.scope, work.id) + arrayOf(TitleUpdateAttemptState.OUTCOME_UNKNOWN.name,
                        encodeResult(retained))) == 1) { "Title completion was not retained" }
                true
            }
        }
    }

    override suspend fun readCompletion(scope: CalendarScope, id: MutationId): TitleUpdateCompletion? =
        access { db -> transaction(db) { read(db, scope, id)?.let { readCompletion(db, it) } } }

    private fun encodeCompletion(value: TitleUpdateCompletion): String = bounded(JSONObject().apply {
        put("schema", 1)
        put("result", JSONObject(encodeResult(value.result)))
        put("receipt", TitleUpdateAcknowledgementCodec.encode(value.receipt))
    }.toString())

    private fun readCompletion(db: SQLiteDatabase, current: TitleUpdateAttemptRecord): TitleUpdateCompletion? =
        db.query(TABLE, arrayOf("completion"), IDENTITY, identity(current.scope, current.id), null, null, null).use { row ->
            if (!row.moveToFirst() || row.isNull(0)) null else try {
                require(row.getType(0) == Cursor.FIELD_TYPE_STRING && current.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
                val payload = bounded(row.getString(0))
                val json = JSONObject(payload)
                require(json.keys().asSequence().toSet() == setOf("schema", "result", "receipt") && integer(json, "schema") == 1L)
                val result = decodeResult(json.getJSONObject("result").toString(), current)
                require(result == readVerified(db, current))
                val receipt = TitleUpdateAcknowledgementCodec.decode(text(json, "receipt"))
                TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(receipt,
                    TitleUpdateAcknowledgementState.RESTORED)).also { require(encodeCompletion(it) == payload) }
            } catch (_: Exception) { throw IllegalStateException("Invalid retained title completion") }
        }

    override suspend fun retainVerified(result: TitleUpdateVerifiedResult) {
        val frozen = TitleUpdateVerifiedResult.of(result.record,
            TitleUpdateSubmissionObservation.Verified(result.attempt, result.event))
        val encoded = encodeResult(frozen)
        access { db -> transaction(db) {
            val current = read(db, frozen.record.scope, frozen.record.id)
            check(current != null && current.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN &&
                current.copy(protectedEvidence = null) == frozen.record) {
                "A verified result requires identical unknown title work"
            }
            val existing = readVerified(db, current)
            if (existing != null) {
                check(existing == frozen) { "Verified title result is immutable" }
            } else {
                // The transaction holds the database writer lock across the complete comparison.
                // Evidence can attach independently; it is intentionally not part of this CAS.
                check(db.update(TABLE, ContentValues().apply { put("result", encoded) },
                    "$IDENTITY AND state=? AND result IS NULL",
                    identity(current.scope, current.id) + TitleUpdateAttemptState.OUTCOME_UNKNOWN.name) == 1) {
                    "Verified title result was not retained"
                }
            }
        } }
    }

    override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? =
        access { db -> transaction(db) { read(db, scope, id)?.let { readVerified(db, it) } } }

    private fun readVerified(db: SQLiteDatabase, current: TitleUpdateAttemptRecord): TitleUpdateVerifiedResult? =
        db.query(TABLE, arrayOf("result"), IDENTITY, identity(current.scope, current.id), null, null, null).use { row ->
            if (!row.moveToFirst() || row.isNull(0)) null else try {
                require(row.getType(0) == Cursor.FIELD_TYPE_STRING)
                decodeResult(row.getString(0), current).also { require(encodeResult(it) == row.getString(0)) }
            } catch (_: Exception) { throw IllegalStateException("Invalid retained verified title result") }
        }

    private fun encodeResult(value: TitleUpdateVerifiedResult): String = bounded(JSONObject().apply {
        put("schema", 1); put("attempt", attemptJson(value.attempt))
        put("event", JSONObject().apply {
            put("id", value.event.eventId.value); put("revision", value.event.revision.value)
            put("content", contentJson(value.event.content))
        })
    }.toString())

    private fun attemptJson(value: TitleUpdateAttemptIdentity) = JSONObject().apply {
        put("account", value.scope.account.value); put("calendar", value.scope.calendar.value)
        put("generation", value.scope.generation); put("id", value.id.value); put("intent", value.localIntentId.value)
        put("event", value.eventId.value); put("baseRevision", value.baseRevision.value)
        put("adapter", value.format.id); put("version", value.format.version)
        put("preparationAdapter", value.preparationFormat.id); put("preparationVersion", value.preparationFormat.version)
        put("preparationFingerprint", value.preparationFingerprint); put("payloadFingerprint", value.payloadFingerprint)
    }

    private fun decodeResult(payload: String, current: TitleUpdateAttemptRecord): TitleUpdateVerifiedResult {
        val json = JSONObject(bounded(payload))
        require(json.keys().asSequence().toSet() == RESULT_FIELDS && integer(json, "schema") == 1L)
        val attempt = json.getJSONObject("attempt")
        require(attempt.keys().asSequence().toSet() == ATTEMPT_FIELDS)
        val identity = TitleUpdateAttemptIdentity(
            CalendarScope(AccountId(text(attempt, "account")), CalendarId(text(attempt, "calendar")), integer(attempt, "generation")),
            MutationId(text(attempt, "id")), MutationId(text(attempt, "intent")), EventId(text(attempt, "event")),
            Revision(text(attempt, "baseRevision")), AdapterFormat(text(attempt, "adapter"), version(attempt, "version")),
            AdapterFormat(text(attempt, "preparationAdapter"), version(attempt, "preparationVersion")),
            text(attempt, "preparationFingerprint"), text(attempt, "payloadFingerprint"))
        require(identity == TitleUpdateAttemptIdentity.of(current))
        val event = json.getJSONObject("event")
        require(event.keys().asSequence().toSet() == RESULT_EVENT_FIELDS)
        val observed = RemoteChange.Upsert(EventId(text(event, "id")), Revision(text(event, "revision")),
            decodeContent(event.getJSONObject("content")))
        return TitleUpdateVerifiedResult.of(current, TitleUpdateSubmissionObservation.Verified(identity, observed))
    }

    /** The complete frozen preparation and ciphertext must still match before durable UNKNOWN.
     * SQLite transactions arbitrate independent helpers; successful commit precedes true/dispatch.
     */
    override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean {
        val frozen = expected.frozen()
        if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val preparation = encodePreparation(frozen.preparation)
        return access { db ->
            currentCoroutineContext().ensureActive()
            if (!admitDispatch(frozen.scope)) false else transaction(db, beforeCommit = {
                check(admitDispatch(frozen.scope)) { "Title update dispatch lease is not current" }
            }) {
                val history = eventHistory(db, frozen.scope, frozen.preparation.intent.base.eventId)
                val current = read(db, frozen.scope, frozen.id)
                val admitted = current == frozen && history.singleOrNull {
                    it.scope == frozen.scope && it.id == frozen.id && !it.completed
                } != null && history.none { !it.completed &&
                    (it.scope != frozen.scope || it.id != frozen.id) } && db.update(TABLE, ContentValues().apply {
                    put("state", TitleUpdateAttemptState.OUTCOME_UNKNOWN.name)
                }, "$IDENTITY AND event=? AND preparation=? AND adapter=? AND version=? AND payload=? AND state=? AND evidence IS NULL AND completion IS NULL",
                    identity(frozen.scope, frozen.id) + arrayOf(frozen.preparation.intent.base.eventId.value,
                        preparation, frozen.format.id, frozen.format.version.toString(), frozen.protectedPayload,
                        TitleUpdateAttemptState.PREPARED.name)) == 1
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

    private data class History(val scope: CalendarScope, val id: MutationId,
        val localIntentId: MutationId, val completed: Boolean)

    /** A non-null column alone must never release the event hold. Validate every historical
     * proof under the writer transaction, including history in earlier generations. Discard each
     * full work/result/receipt after validation; only admission identities survive the iteration.
     */
    private fun eventHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId): List<History> {
        val history = db.query(TABLE, arrayOf("generation", "mutation"), "account=? AND calendar=? AND event=?",
            arrayOf(scope.account.value, scope.calendar.value, event.value),
            null, null, null).use { rows -> buildList {
                while (rows.moveToNext()) {
                    check(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER && rows.getType(1) == Cursor.FIELD_TYPE_STRING) {
                        "Invalid title attempt history identity"
                    }
                    val previousScope = scope.copy(generation = rows.getLong(0))
                    val record = checkNotNull(read(db, previousScope, MutationId(rows.getString(1))))
                    check(record.preparation.intent.base.eventId == event)
                    val completed = readCompletion(db, record) != null
                    add(History(record.scope, record.id, record.preparation.intent.id, completed))
                }
            } }
        check(history.count { !it.completed } <= 1 &&
            history.map { it.id }.distinct().size == history.size &&
            history.map { it.localIntentId }.distinct().size == history.size) {
            "Title attempt history is inconsistent"
        }
        return history
    }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? =
        db.query(TABLE, arrayOf("event", "preparation", "adapter", "version", "payload", "state", "evidence"),
            IDENTITY, identity(scope, id), null, null, null).use { row ->
            if (!row.moveToFirst()) null else try {
                require((0..6).all { row.getType(it) == if (it == 3) Cursor.FIELD_TYPE_INTEGER
                    else if (it == 6 && row.isNull(it)) Cursor.FIELD_TYPE_NULL else Cursor.FIELD_TYPE_STRING })
                val preparation = decodePreparation(row.getString(1))
                require(encodePreparation(preparation) == row.getString(1))
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
    private suspend fun <T> transaction(db: SQLiteDatabase, beforeCommit: () -> Unit = {}, action: () -> T): T {
        db.beginTransaction()
        try {
            val result = action()
            currentCoroutineContext().ensureActive()
            beforeCommit()
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
        val RESULT_FIELDS = setOf("schema", "attempt", "event")
        val RESULT_EVENT_FIELDS = setOf("id", "revision", "content")
        val ATTEMPT_FIELDS = setOf("account", "calendar", "generation", "id", "intent", "event", "baseRevision",
            "adapter", "version", "preparationAdapter", "preparationVersion", "preparationFingerprint", "payloadFingerprint")
    }
}

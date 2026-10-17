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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Private, backup-excluded shared title/text/time/deletion ownership across generations.
 * Payload/evidence are opaque protected strings; this store grants no HTTP or Provider capability.
 */
class TitleUpdateAttemptStore(context: Context, name: String = "calendar-title-update-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 9), TitleUpdateRecoveryJournal, TitleUpdatePreparationJournal {
    private val lock = Mutex()
    /** Text history shares the same connection and reciprocal ownership transaction. */
    internal val textJournal: TextUpdateRecoveryJournal = SqlTextUpdateAttemptJournal(object : TextAttemptDatabase {
        override suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T =
            withContext(Dispatchers.IO) { lock.withLock { beforeOpen(); action(writableDatabase) } }
    }, ::canTextOwn)
    /** Temporal history shares the same connection and reciprocal ownership transaction. */
    internal val timeJournal: TimeUpdateRecoveryJournal = SqlTimeUpdateAttemptJournal(object : TimeAttemptDatabase {
        override suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T =
            withContext(Dispatchers.IO) { lock.withLock { beforeOpen(); action(writableDatabase) } }
    }, ::canTimeOwn)
    /** Completed DELETE retains its permanent event and request hold. */
    internal val deleteJournal: DeleteRecoveryJournal = SqlDeleteAttemptJournal(object : DeleteAttemptDatabase {
        override suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T =
            withContext(Dispatchers.IO) { lock.withLock { beforeOpen(); action(writableDatabase) } }
    }, ::canDeleteOwn)
    override val completionProtocol = 1
    @Volatile var admitDispatch: (CalendarScope) -> Boolean = { false }

    /** Metadata discovery only. A non-null completion is excluded without qualifying its proof;
     * admission still validates the full event history independently, across generations.
     */
    override suspend fun page(query: TitleUpdateWorkQuery): TitleUpdateWorkPage = access { db -> transaction(db) {
        try {
            // Bound every selected value before CursorWindow allocation, including lookahead.
            // Preserve UTF-8 bytes for strict decoding instead of accepting replacement text.
            val columns = "CASE WHEN typeof(mutation)='text' AND length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN CAST(mutation AS BLOB) END, " +
                "CASE WHEN typeof(event)='text' AND length(CAST(event AS BLOB)) BETWEEN 1 AND 4096 THEN CAST(event AS BLOB) END, " +
                "CASE WHEN typeof(state)='text' AND length(CAST(state AS BLOB)) BETWEEN 1 AND 15 THEN state END"
            val position = if (query.cursor == null) "" else " AND event COLLATE BINARY > ?"
            val args = buildList {
                add(query.scope.account.value); add(query.scope.calendar.value); add(query.scope.generation.toString())
                query.cursor?.let { add(it.afterEvent.value) }; add((query.limit + 1).toString())
            }.toTypedArray()
            val rows = db.rawQuery("SELECT $columns FROM $TABLE WHERE account=? AND calendar=? AND generation=? " +
                "AND completion IS NULL$position ORDER BY event COLLATE BINARY LIMIT ?", args).use { row -> buildList {
                while (row.moveToNext()) {
                    require(row.getType(0) == Cursor.FIELD_TYPE_BLOB && row.getType(1) == Cursor.FIELD_TYPE_BLOB &&
                        row.getType(2) == Cursor.FIELD_TYPE_STRING)
                    fun identity(column: Int) = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(row.getBlob(column))).toString()
                    add(TitleUpdateWorkRef(MutationId(identity(0)), EventId(identity(1)),
                        TitleUpdateAttemptState.valueOf(row.getString(2))))
                }
            } }
            TitleUpdateWorkPage.fromOrderedMetadata(query, rows)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { throw IllegalStateException("Invalid title work metadata") }
    } }

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
        createReservationTable(db)
        TextUpdateAttemptSql.createTable(db)
        TimeUpdateAttemptSql.createTable(db)
        DeleteAttemptSql.createTable(db)
    }

    private fun createReservationTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE $RESERVATIONS (
            account TEXT NOT NULL, calendar TEXT NOT NULL,
            generation INTEGER NOT NULL CHECK(generation >= 0),
            mutation TEXT NOT NULL, event TEXT NOT NULL, preparation TEXT NOT NULL,
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            PRIMARY KEY(account,calendar,generation,mutation)
        )""".trimIndent())
        db.execSQL("CREATE INDEX title_update_preparation_event ON $RESERVATIONS(account,calendar,event)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..8 && newVersion == 9) { "Explicit title update journal migration required" }
        if (oldVersion >= 3) {
            // Existing attempts, protected bytes, results and completions stay untouched.
            if (oldVersion == 3) createReservationTable(db)
            if (oldVersion == 5) TextUpdateAttemptSql.migrateProofs(db)
            else if (oldVersion < 5) TextUpdateAttemptSql.createTable(db)
            if (oldVersion < 7) TimeUpdateAttemptSql.createTable(db)
            if (oldVersion < 8) DeleteAttemptSql.createTable(db)
            else DeleteAttemptSql.migrateProofs(db)
            return
        }
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

    override suspend fun reservePreparation(candidate: TitleUpdatePreparationReservation,
        admit: (CalendarScope) -> Boolean): TitleUpdatePreparationReservation? {
        val frozen = candidate.frozen()
        val operation = currentCoroutineContext()
        return access { db ->
            if (!admit(frozen.scope)) null else transaction(db, beforeCommit = {
                check(admit(frozen.scope)) { "Title preparation reservation lease is not current" }
            }) {
                if (otherFamilyOwns(db, frozen.scope, frozen.eventId, frozen.id) { operation.ensureActive() })
                    return@transaction null
                val history = ownershipHistory(db, frozen.scope, frozen.eventId)
                val owner = history.singleOrNull { !it.completed }
                val canonical = if (owner != null) {
                    if (owner.scope != frozen.scope) return@transaction null
                    val attempt = read(db, owner.scope, owner.id)
                    if (attempt?.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) return@transaction null
                    val retained = readReservation(db, owner.scope, owner.id)
                        ?: TitleUpdatePreparationReservation.of(checkNotNull(attempt))
                    // A proposed UUID is ignored only after complete ownership equality.
                    if (retained.preparation != frozen.preparation || retained.format != frozen.format)
                        return@transaction null
                    retained
                } else {
                    if (history.any { it.id == frozen.id || it.localIntentId == frozen.preparation.intent.id } ||
                        read(db, frozen.scope, frozen.id) != null || readReservation(db, frozen.scope, frozen.id) != null)
                        return@transaction null
                    frozen
                }
                // A proposal may adopt an earlier title UUID. Qualify that identity separately.
                if (otherFamilyOwns(db, canonical.scope, canonical.eventId, canonical.id) { operation.ensureActive() })
                    return@transaction null
                if (readReservation(db, canonical.scope, canonical.id) == null) {
                    // Adoption of legacy PREPARED work keeps its ID and first wire intact.
                    db.insertOrThrow(RESERVATIONS, null, ContentValues().apply {
                        put("account", canonical.scope.account.value); put("calendar", canonical.scope.calendar.value)
                        put("generation", canonical.scope.generation); put("mutation", canonical.id.value)
                        put("event", canonical.eventId.value); put("preparation", encodePreparation(canonical.preparation))
                        put("adapter", canonical.format.id); put("version", canonical.format.version)
                    })
                }
                canonical
            }
        }
    }

    override suspend fun readPreparationReservation(scope: CalendarScope,
        eventId: EventId): TitleUpdatePreparationReservation? = access { db -> transaction(db) {
        val owner = ownershipHistory(db, scope, eventId).singleOrNull { !it.completed }
            ?: return@transaction null
        if (owner.scope != scope) return@transaction null
        readReservation(db, owner.scope, owner.id)
            ?: TitleUpdatePreparationReservation.of(checkNotNull(read(db, owner.scope, owner.id)))
    } }

    override suspend fun remember(record: TitleUpdateAttemptRecord) {
        rememberPrepared(record, { true }, guarded = false)
    }

    override suspend fun remember(record: TitleUpdateAttemptRecord,
        admit: (CalendarScope) -> Boolean): Boolean = rememberPrepared(record, admit, guarded = true)

    private suspend fun rememberPrepared(record: TitleUpdateAttemptRecord,
        admit: (CalendarScope) -> Boolean, guarded: Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (guarded && (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)) return false
        require(frozen.state == TitleUpdateAttemptState.PREPARED && frozen.protectedEvidence == null) {
            "Only prepared title work without evidence can be remembered"
        }
        val preparation = encodePreparation(frozen.preparation)
        val operation = currentCoroutineContext()
        return access { db ->
            if (!admit(frozen.scope)) false else transaction(db, beforeCommit = {
                check(admit(frozen.scope)) { "Title preparation lease is not current" }
            }) {
                val blocked = otherFamilyOwns(db, frozen.scope, frozen.preparation.intent.base.eventId, frozen.id) {
                    operation.ensureActive()
                }
                if (guarded && blocked) return@transaction false
                check(!blocked) { "Title update ownership is held" }
                val completeHistory = ownershipHistory(db, frozen.scope, frozen.preparation.intent.base.eventId)
                val existing = read(db, frozen.scope, frozen.id)
                val reservation = readReservation(db, frozen.scope, frozen.id)
                if (guarded && reservation != null && !reservation.matches(frozen)) return@transaction false
                check(reservation == null || reservation.matches(frozen)) { "Title preparation reservation is immutable" }
                // The exact bare reservation may become its first PREPARED record. All other
                // unresolved ownership and historical identity checks remain unchanged.
                val history = if (existing == null && reservation != null) completeHistory.filterNot {
                    !it.completed && it.scope == frozen.scope && it.id == frozen.id
                } else completeHistory
                if (existing != null) {
                    // Guarded binding cannot use the legacy tolerance for UNKNOWN or release
                    // a history hold. Even exact repetitions qualify the complete event history.
                    if (guarded && (existing != frozen || history.singleOrNull { !it.completed }?.let {
                            it.scope == frozen.scope && it.id == frozen.id && it.localIntentId == frozen.preparation.intent.id
                        } != true)) return@transaction false
                    check(existing.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen) {
                        "Title update attempt is immutable"
                    }
                } else {
                    if (guarded && (history.any { !it.completed } || history.any {
                            it.localIntentId == frozen.preparation.intent.id || it.id == frozen.id
                        })) return@transaction false
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
                true
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

    override suspend fun retainVerified(result: TitleUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean =
        retainVerifiedWithAdmission(result, admit)

    override suspend fun retainVerified(result: TitleUpdateVerifiedResult) {
        // Compatibility for explicit experiments; guarded recovery never falls back here.
        retainVerifiedWithAdmission(result) { true }
    }

    private suspend fun retainVerifiedWithAdmission(result: TitleUpdateVerifiedResult,
        admit: (CalendarScope) -> Boolean): Boolean {
        val frozen = TitleUpdateVerifiedResult.of(result.record,
            TitleUpdateSubmissionObservation.Verified(result.attempt, result.event))
        val encoded = encodeResult(frozen)
        return access { db ->
            // access has acquired the IO mutex. A queued call must observe current admission,
            // never a permission snapshot taken before waiting for that lock.
            if (!admit(frozen.record.scope)) false else transaction(db, beforeCommit = {
                check(admit(frozen.record.scope)) { "Verified title result lease is not current" }
            }) {
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
                true
            }
        }
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
    override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean =
        claimWithAdmission(expected) { admitDispatch(it) }

    /** This invocation's lease is independent of the legacy mutable dispatch permission. */
    override suspend fun claim(expected: TitleUpdateAttemptRecord,
        admit: (CalendarScope) -> Boolean): Boolean = claimWithAdmission(expected, admit)

    private suspend fun claimWithAdmission(expected: TitleUpdateAttemptRecord,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val preparation = encodePreparation(frozen.preparation)
        return access { db ->
            val operationContext = currentCoroutineContext()
            operationContext.ensureActive()
            val initiallyAllowed = admit(frozen.scope)
            operationContext.ensureActive()
            if (!initiallyAllowed) false else transaction(db, beforeCommit = {
                val stillAllowed = admit(frozen.scope)
                operationContext.ensureActive()
                check(stillAllowed) { "Title update dispatch lease is not current" }
            }) {
                if (otherFamilyOwns(db, frozen.scope, frozen.preparation.intent.base.eventId, frozen.id) {
                        operationContext.ensureActive()
                    }) return@transaction false
                val history = ownershipHistory(db, frozen.scope, frozen.preparation.intent.base.eventId)
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

    /** All families are fully qualified before deciding; a hold cannot hide another corrupt history. */
    private fun otherFamilyOwns(db: SQLiteDatabase, scope: CalendarScope, event: EventId, id: MutationId,
        checkCancelled: () -> Unit): Boolean = qualifiedOwnership(checkCancelled) {
        val text = TextUpdateAttemptSql(db, checkCancelled).hasOwner(scope, event, id)
        val time = TimeUpdateAttemptSql(db, checkCancelled).hasOwner(scope, event, id)
        val delete = DeleteAttemptSql(db, checkCancelled).hasOwner(scope, event, id)
        text || time || delete
    }

    private fun canTextOwn(db: SQLiteDatabase, work: TextUpdateAttemptRecord, checkCancelled: () -> Unit): Boolean =
        qualifiedOwnership(checkCancelled) {
            val history = ownershipHistory(db, work.scope, work.eventId)
            val usedIdentity = titleRequestExists(db, work.scope, work.id, checkCancelled)
            val time = TimeUpdateAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            val delete = DeleteAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            history.all { it.completed } && !usedIdentity && !time && !delete
        }

    private fun canTimeOwn(db: SQLiteDatabase, work: TimeUpdateAttemptRecord, checkCancelled: () -> Unit): Boolean =
        qualifiedOwnership(checkCancelled) {
            val history = ownershipHistory(db, work.scope, work.eventId)
            val usedIdentity = titleRequestExists(db, work.scope, work.id, checkCancelled)
            val text = TextUpdateAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            val delete = DeleteAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            history.all { it.completed } && !usedIdentity && !text && !delete
        }

    private fun canDeleteOwn(db: SQLiteDatabase, work: DeleteAttemptRecord, checkCancelled: () -> Unit): Boolean =
        qualifiedOwnership(checkCancelled) {
            val history = ownershipHistory(db, work.scope, work.eventId)
            val usedIdentity = titleRequestExists(db, work.scope, work.id, checkCancelled)
            val text = TextUpdateAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            val time = TimeUpdateAttemptSql(db, checkCancelled).hasOwner(work.scope, work.eventId, work.id)
            history.all { it.completed } && !usedIdentity && !text && !time
        }

    private fun titleRequestExists(db: SQLiteDatabase, scope: CalendarScope, id: MutationId,
        checkCancelled: () -> Unit): Boolean {
        var found = false
        for (table in listOf(TABLE, RESERVATIONS)) {
            val columns = "CASE WHEN typeof(generation)='integer' AND generation>=0 THEN generation END, " +
                "CASE WHEN typeof(event)='text' AND length(CAST(event AS BLOB)) BETWEEN 1 AND 4096 THEN CAST(event AS BLOB) END"
            db.rawQuery("SELECT $columns FROM $table WHERE account=? AND calendar=? AND mutation=?",
                arrayOf(scope.account.value, scope.calendar.value, id.value)).use { rows ->
                while (rows.moveToNext()) {
                    checkCancelled()
                    require(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER && rows.getType(1) == Cursor.FIELD_TYPE_BLOB)
                    val retainedScope = scope.copy(generation = rows.getLong(0))
                    val event = EventId(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(rows.getBlob(1))).toString())
                    // Full history also checks reverse reservation/wire links and every completion.
                    require(ownershipHistory(db, retainedScope, event).any { it.scope == retainedScope && it.id == id })
                    found = true
                }
            }
        }
        return found
    }

    private fun <T> qualifiedOwnership(checkCancelled: () -> Unit, action: () -> T): T {
        try {
            checkCancelled()
            val value = action()
            checkCancelled()
            return value
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { checkCancelled(); throw IllegalStateException("Invalid shared update ownership") }
    }

    /** Reservation and wire tables share one writer transaction and one ownership view.
     * Only fully qualified completion releases a reservation; a bare reservation is a hold.
     * Discard each decoded capsule after qualification rather than keeping payload history.
     */
    private fun ownershipHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId): List<History> {
        val attempts = eventHistory(db, scope, event)
        // The event index is discovery, not proof. A moved reservation index cannot
        // hide a disagreement with an attempt that is still indexed under this event.
        for (attempt in attempts) {
            readReservation(db, attempt.scope, attempt.id)?.let { reservation ->
                check(reservation.matches(checkNotNull(read(db, attempt.scope, attempt.id)))) {
                    "Title wire and reservation disagree"
                }
            }
        }
        val reservations = db.query(RESERVATIONS, arrayOf("generation", "mutation"),
            "account=? AND calendar=? AND event=?", arrayOf(scope.account.value, scope.calendar.value, event.value),
            null, null, null).use { rows -> buildList {
            while (rows.moveToNext()) {
                check(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER && rows.getType(1) == Cursor.FIELD_TYPE_STRING) {
                    "Invalid title reservation history identity"
                }
                val retainedScope = scope.copy(generation = rows.getLong(0))
                val id = MutationId(rows.getString(1))
                val reservation = checkNotNull(readReservation(db, retainedScope, id))
                check(reservation.eventId == event)
                // Also check the reverse link independently of the wire's event index.
                val record = read(db, retainedScope, id)
                val completed = if (record != null) {
                    check(reservation.matches(record)) {
                        "Title reservation and wire disagree"
                    }
                    checkNotNull(attempts.singleOrNull { it.scope == retainedScope && it.id == id }).completed
                } else false
                add(History(retainedScope, id, reservation.preparation.intent.id, completed))
            }
        } }
        val combined = (attempts + reservations).distinct()
        check(combined.count { !it.completed } <= 1 &&
            combined.map { it.id }.distinct().size == combined.size &&
            combined.map { it.localIntentId }.distinct().size == combined.size) {
            "Title preparation ownership is inconsistent"
        }
        return combined
    }

    private fun readReservation(db: SQLiteDatabase, scope: CalendarScope,
        id: MutationId): TitleUpdatePreparationReservation? = db.query(RESERVATIONS,
        arrayOf("event", "preparation", "adapter", "version"), IDENTITY, identity(scope, id), null, null, null).use { row ->
        if (!row.moveToFirst()) null else try {
            require((0..3).all { row.getType(it) == if (it == 3) Cursor.FIELD_TYPE_INTEGER else Cursor.FIELD_TYPE_STRING })
            val preparation = decodePreparation(row.getString(1))
            require(encodePreparation(preparation) == row.getString(1))
            val value = TitleUpdatePreparationReservation(id, preparation, AdapterFormat(row.getString(2), version(row, 3))).frozen()
            require(value.scope == scope && value.eventId.value == row.getString(0))
            value
        } catch (_: Exception) { throw IllegalStateException("Invalid retained title preparation reservation") }
    }

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
        const val RESERVATIONS = "title_update_preparation_reservations"
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

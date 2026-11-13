package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedHandoff
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readProjectionReceipt
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireOrdinaryLocalCapture
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasRawEventRow
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasForeignLocalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readInspectionDeleteOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPreparationOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasLocalMutation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.checkStagingCapacity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.MasterState
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.masterStates
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Local intent, conflict and acknowledgement SQL; the store owns every transaction and lease. */
internal class ImportStoreLocalMutationSql(
    private val conflicts: (SQLiteDatabase) -> ConflictArchiveSql,
) {
    /** Triage a bare captured variant only; frozen capsules and all request formats remain untouched. */
    fun retainUnsupportedBareUpdate(db: SQLiteDatabase, intent: LocalEditIntent): Boolean {
        val scope = intent.scope; val event = intent.base.eventId
        if (intent.state != LocalEditState.PAUSED || !activeProjectionScope(db, scope) ||
            ImportStoreLocalPreparations.readLocalPreparationOwner(db, scope, event) != intent ||
            hasForeignLocalPreparation(db, scope, event, null)) return false
        check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=? AND mutation=? AND kind='UPDATE'", arrayOf(key(scope), event.value, intent.id.value)) == 1)
        conflicts(db).ensure(scope, event)
        return true
    }
    data class ProjectionBase(val change: RemoteChange.Upsert, val representation: ProjectedEventReference?) {
        override fun toString() = "ProjectionBase(redacted)"
    }
    fun requeueDistinctUpdateObservation(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?, acknowledgedPayload: String) {
        val scope = intent.scope
        val event = intent.base.eventId
        val condition = "scope=? AND event=? AND payload<>? AND pending=0 AND NOT EXISTS " +
            "(SELECT 1 FROM deferred_events d WHERE d.scope=records.scope AND d.event=records.event) " +
            "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)"
        val args = arrayOf(key(scope), event.value, acknowledgedPayload)
        val change = stagedQuery(db, "SELECT payload FROM records WHERE $condition", args).use { row ->
            if (row.moveToFirst()) decodeChange(row.getString(0)) else null
        } ?: return
        check(change.eventId == event) { "Invalid retained update observation" }
        if (handoff != null && !supportsPersonalHandoff(change)) return
        val recurrence = (change as? RemoteChange.Upsert)?.content?.recurrence
        if (recurrence != null && !recurrence.isMaster && masterStates(db, scope)[recurrence.uid] == MasterState.INACTIVE) return
        check(db.update("records", ContentValues().apply { put("pending", 1) }, condition, args) == 1) {
            "Retained update observation changed"
        }
    }

    fun ordinaryUpdateAcknowledgement(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?): Boolean {
        val scope = intent.scope
        val event = intent.base.eventId
        if (listOf("captured_create_bases", "captured_create_acknowledgements", "captured_create_handoffs")
            .any { hasRawEventRow(db, it, scope, event) }) return false
        val pending = readProjectionReceipt(db, scope, event) ?: return true
        return redundantUpdateProjection(pending, intent, handoff)
    }

    fun redundantUpdateProjection(pending: ProjectionReceipt, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?): Boolean =
        handoff == null && pending.scope == intent.scope && pending.protocol == 1 &&
            pending.format == intent.format && pending.change == intent.base &&
            pending.previousBase == intent.base && pending.representation == null && pending.previousRepresentation == null

    fun retireRedundantUpdateProjection(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?) {
        val pending = readProjectionReceipt(db, intent.scope, intent.base.eventId) ?: return
        check(redundantUpdateProjection(pending, intent, handoff)) { "Update projection obligation changed" }
        check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? " +
            "AND previous_base=? AND protocol=? AND representation IS NULL AND previous_representation IS NULL",
            arrayOf(key(pending.scope), pending.change.eventId.value, pending.nonce, encodeChange(pending.change),
                pending.format.id, pending.format.version.toString(), encodeChange(checkNotNull(pending.previousBase)),
                pending.protocol.toString())) == 1) { "Update projection obligation changed" }
    }

    fun exactUpdateResultEcho(db: SQLiteDatabase, intent: LocalEditIntent,
        projection: ProjectionReceipt, owner: LocalEditIntent?): Boolean {
        val scope = intent.scope
        val event = intent.base.eventId
        if (owner?.state != LocalEditState.CONFLICT ||
            owner.copy(state = LocalEditState.PAUSED) != intent || !activeProjectionScope(db, scope) ||
            !acknowledgementFormatMatches(db, scope, intent.format) || readBaseline(db, scope, event) != intent.base ||
            hasRawEventRow(db, "deferred_events", scope, event)) return false
        val observed = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        return observed == projection.change
    }

    fun directUpdateAcknowledgementBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): Boolean =
        db.rawQuery("SELECT 1 FROM projection_bases WHERE scope=? AND event=? AND representation IS NULL",
            arrayOf(key(scope), event.value)).use { it.moveToFirst() }

    fun acknowledgementFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean =
        db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            it.moveToFirst() && !it.isNull(0) && decodeCheckpoint(scope, it.getString(0)).format == format
        }

    fun readDeleteAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): DeleteAcknowledgementRecord? = try {
        stagedQuery(db, "SELECT ${archiveText("mutation",4096)},${archiveText("attempt",4096)},${archiveText("payload",1048576)},${archiveText("state",16)} FROM delete_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope),event.value)).use { row ->
            if (!row.moveToFirst()) null else {
                val receipt = DeleteAcknowledgementCodec.decode(archiveString(row,2))
                require(receipt.intent.scope == scope && receipt.intent.base.eventId == event &&
                    receipt.intent.id.value == archiveString(row,0) && receipt.attempt.id.value == archiveString(row,1))
                DeleteAcknowledgementRecord(receipt,DeleteAcknowledgementState.valueOf(archiveString(row,3)))
            }
        }
    } catch (failure: CancellationException) { throw failure }
      catch (failure: SQLiteException) { throw failure }
      catch (_: Exception) { throw IllegalStateException("Invalid retained delete acknowledgement") }

    fun matchingDeleteReservation(db: SQLiteDatabase, scope: CalendarScope, event: EventId): Boolean {
        val record = readDeleteAcknowledgement(db,scope,event) ?: return false
        val owner = readInspectionDeleteOwner(db,scope,event) ?: return false
        return owner.copy(state = LocalEditState.PAUSED) == record.receipt.intent && conflicts(db).currentFormat(scope) == owner.format
    }

    fun completedDeleteEvents(db: SQLiteDatabase, scope: CalendarScope): Set<EventId> {
        val events = stagedQuery(db,"SELECT ${archiveText("event",4096)} FROM delete_acknowledgements WHERE scope=?",arrayOf(key(scope))).use { rows ->
            buildList { while (rows.moveToNext()) add(EventId(archiveString(rows,0))) }
        }
        return events.filterTo(mutableSetOf()) { event ->
            val record = checkNotNull(readDeleteAcknowledgement(db,scope,event))
            val owner = readInspectionDeleteOwner(db,scope,event)
            record.state == DeleteAcknowledgementState.RESTORED && owner == record.receipt.intent
        }
    }

    fun qualifyLocalDelete(db: SQLiteDatabase, intent: LocalDeleteIntent, allowReservedAbsence: Boolean): Boolean {
        val scope = intent.scope; val event = intent.base.eventId
        if (intent.state != LocalEditState.PAUSED || !activeProjectionScope(db,scope)) return false
        // Freeze validates the narrow supported deletion profile without creating new identities.
        try { DeleteSubmissionPolicy.freezeIntent(intent) } catch (_: IllegalArgumentException) { return false }
        if (listOf("captured_create_bases","captured_create_acknowledgements","captured_create_handoffs")
            .any { hasRawEventRow(db,it,scope,event) }) return false
        val reservation = readDeleteAcknowledgement(db,scope,event)
        if (reservation != null && reservation.receipt.intent != intent) return false
        if (hasForeignLocalPreparation(db,scope,event,null) ||
            hasRawEventRow(db,"deferred_events",scope,event)) return false
        val pendingProjection = readProjectionReceipt(db,scope,event)
        if (pendingProjection != null && !redundantDeleteProjection(pendingProjection,intent)) return false
        if (conflicts(db).currentFormat(scope) != intent.format || readInspectionDeleteOwner(db,scope,event) != intent ||
            readProjectionBase(db,scope,event)?.let { it.change != intent.base ||
                it.representation?.representation?.let { value -> value != ProjectionRepresentation.DIRECT_V1 } == true } != false) return false
        val current = stagedQuery(db,"SELECT payload FROM records WHERE scope=? AND event=?",arrayOf(key(scope),event.value)).use {
            if (it.moveToFirst()) decodeChange(it.getString(0)) else null
        }
        return current == intent.base || allowReservedAbsence && current is RemoteChange.Deleted && matchingDeleteReservation(db,scope,event)
    }

    fun redundantDeleteProjection(pending: ProjectionReceipt, intent: LocalDeleteIntent): Boolean =
        pending.scope == intent.scope && pending.protocol == 1 && pending.format == intent.format &&
            pending.change == intent.base && pending.previousBase == intent.base &&
            pending.representation == null && pending.previousRepresentation == null

    fun retireRedundantDeleteProjection(db: SQLiteDatabase, intent: LocalDeleteIntent) {
        val pending = readProjectionReceipt(db,intent.scope,intent.base.eventId) ?: return
        check(redundantDeleteProjection(pending,intent)) { "Deletion projection obligation changed" }
        check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? " +
            "AND previous_base=? AND protocol=? AND representation IS NULL AND previous_representation IS NULL",
            arrayOf(key(pending.scope),pending.change.eventId.value,pending.nonce,encodeChange(pending.change),
                pending.format.id,pending.format.version.toString(),encodeChange(checkNotNull(pending.previousBase)),
                pending.protocol.toString())) == 1) { "Deletion projection obligation changed" }
    }

    fun readProjectionBase(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ProjectionBase? =
        db.rawQuery("SELECT payload,representation FROM projection_bases WHERE scope=? AND event=?",
            arrayOf(key(scope), eventId.value)).use {
            if (!it.moveToFirst()) null else {
                val change = decodeChange(it.getString(0)) as? RemoteChange.Upsert ?: error("Invalid projection baseline")
                check(change.eventId == eventId) { "Projection baseline identity mismatch" }
                ProjectionBase(change, ProjectionRepresentationCodec.decode(change, if (it.isNull(1)) null else it.getString(1)))
            }
        }
    fun readBaseline(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? =
        readProjectionBase(db, scope, eventId)?.change
    fun readLocalEdit(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        db.rawQuery("SELECT mutation,adapter,version,base,local,state FROM local_edits WHERE scope=? AND event=? AND kind='UPDATE'",
            arrayOf(key(scope), eventId.value)).use { if (!it.moveToFirst()) null else LocalEditIntent(
            MutationId(it.getString(0)), scope, AdapterFormat(it.getString(1), it.getInt(2)),
            decodeChange(it.getString(3)) as RemoteChange.Upsert, (decodeChange(it.getString(4)) as RemoteChange.Upsert).content,
            LocalEditState.valueOf(it.getString(5))) }
    fun readLocalDelete(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalDeleteIntent? =
        db.rawQuery("SELECT mutation,adapter,version,base,local,state FROM local_edits WHERE scope=? AND event=? AND kind='DELETE'",
            arrayOf(key(scope), eventId.value)).use {
            if (!it.moveToFirst()) null else {
                val base = decodeChange(it.getString(3)) as RemoteChange.Upsert
                val local = decodeChange(it.getString(4)) as RemoteChange.Upsert
                check(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision) { "Local deletion identity mismatch" }
                LocalDeleteIntent(MutationId(it.getString(0)), scope, AdapterFormat(it.getString(1), it.getInt(2)),
                    base, local.content, LocalEditState.valueOf(it.getString(5)))
            }
        }
    fun isAvailable(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT available FROM calendars WHERE scope=?", arrayOf(key(scope)),
    ).use { it.moveToFirst() && it.getInt(0) != 0 }
    fun retainedLocalChanges(db: SQLiteDatabase, query: RetainedLocalChangeQuery): RetainedLocalChangePage = run {
        try {
            // Bound strings before loading a CursorWindow, including the look-ahead row.
            val columns = "CASE WHEN typeof(event)='text' AND length(CAST(event AS BLOB)) BETWEEN 1 AND 4096 THEN CAST(event AS BLOB) END, " +
                "CASE WHEN typeof(mutation)='text' AND length(CAST(mutation AS BLOB)) BETWEEN 1 AND 4096 THEN CAST(mutation AS BLOB) END, " +
                "CASE WHEN typeof(kind)='text' AND length(CAST(kind AS BLOB)) <= 6 THEN kind END, " +
                "CASE WHEN typeof(state)='text' AND length(CAST(state AS BLOB)) <= 8 THEN state END"
            val position = if (query.cursor == null) "" else " AND event COLLATE BINARY > ?"
            val completed = completedDeleteEvents(db,query.scope)
            val args = buildList {
                add(key(query.scope)); query.cursor?.let { add(it.afterEvent.value) }
                add((query.limit.toLong() + 1 + completed.size).toString())
            }.toTypedArray()
            val rows = db.rawQuery("SELECT $columns FROM local_edits WHERE scope=?$position " +
                "ORDER BY event COLLATE BINARY LIMIT ?", args).use { row ->
                buildList {
                    while (row.moveToNext()) {
                        require((0..3).none { row.isNull(it) })
                        fun identity(column: Int) = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(row.getBlob(column))).toString()
                        val event = EventId(identity(0))
                        if (event in completed) continue
                        add(RetainedLocalChangeRef(event, MutationId(identity(1)),
                            MutationKind.valueOf(row.getString(2)), LocalEditState.valueOf(row.getString(3))))
                        if (size == query.limit + 1) break
                    }
                }
            }
            RetainedLocalChangePage.fromOrderedMetadata(query, rows)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: SQLiteException) { throw failure }
          catch (_: Exception) { throw IllegalStateException("Invalid retained change metadata") }
    }

    fun inspectLocalEdit(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId, mutationId: MutationId): LocalEditReview? = run {
        val update = db.rawQuery("SELECT kind FROM local_edits WHERE scope=? AND event=?",
            arrayOf(key(scope), eventId.value)).use { it.moveToFirst() && it.getString(0) == "UPDATE" }
        if (!update) return@run null
        val owner = readPreparationOwner(db, scope, eventId) ?: return@run null
        if (owner.id != mutationId) return@run null
        val imported = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(scope), eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0)).format
        }
        val retained = readLocalEditPreparation(db, scope, eventId)
        LocalEditReview(owner, readBaseline(db, scope, eventId), imported, format,
            hasRawEventRow(db, "deferred_events", scope, eventId),
            retained?.let { RetainedLocalTitleInfo(it.format, it.timestamp) })
    }

    fun inspectLocalDelete(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId, mutationId: MutationId): LocalDeleteReview? = run {
        val exact = db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND event=? AND mutation=? AND kind='DELETE'",
            arrayOf(key(scope), eventId.value, mutationId.value)).use { it.moveToFirst() }
        if (!exact) return@run null
        check(!hasForeignLocalPreparation(db, scope, eventId, null)) { "Deletion has incompatible preparation" }
        val owner = readInspectionDeleteOwner(db, scope, eventId) ?: return@run null
        check(owner.id == mutationId)
        val imported = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(scope), eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0)).format
        }
        LocalDeleteReview(owner, readBaseline(db, scope, eventId), imported, format,
            hasRawEventRow(db, "deferred_events", scope, eventId))
    }

    fun prepareDeleteAcknowledgement(db: SQLiteDatabase, record: DeleteAttemptRecord): DeleteAcknowledgementReservation? = run {
        val frozen = record.frozen(); val identity = DeleteAttemptIdentity.of(frozen)
        val existing = readDeleteAcknowledgement(db,frozen.scope,frozen.eventId)
        if (existing != null) return@run if (existing.receipt.attempt == identity && existing.receipt.intent == frozen.intent &&
            qualifyLocalDelete(db,frozen.intent,true))
            DeleteAcknowledgementReservation(existing,false) else null
        if (frozen.state != DeleteAttemptState.PREPARED || !qualifyLocalDelete(db,frozen.intent,false)) return@run null
        val receipt = DeleteAcknowledgementReceipt(identity,frozen.intent,UUID.randomUUID().toString())
        db.insertOrThrow("delete_acknowledgements",null,ContentValues().apply {
            put("scope",key(frozen.scope)); put("event",frozen.eventId.value); put("mutation",frozen.intent.id.value)
            put("attempt",frozen.id.value); put("payload",DeleteAcknowledgementCodec.encode(receipt)); put("state","PENDING")
        })
        checkStagingCapacity(db)
        DeleteAcknowledgementReservation(DeleteAcknowledgementRecord(receipt,DeleteAcknowledgementState.PENDING),true)
    }

    fun restoreDeleteAcknowledgement(db: SQLiteDatabase, witness: AppliedDeleteAcknowledgement): Boolean = run {
        val receipt = witness.receipt.snapshot(); val scope = receipt.intent.scope; val event = receipt.intent.base.eventId
        val retained = readDeleteAcknowledgement(db,scope,event) ?: return@run false
        if (retained.receipt != receipt || !qualifyLocalDelete(db,receipt.intent,true)) return@run false
        if (retained.state == DeleteAcknowledgementState.RESTORED) return@run true
        retireRedundantDeleteProjection(db,receipt.intent)
        db.update("records",ContentValues().apply { put("pending",0) },"scope=? AND event=?",arrayOf(key(scope),event.value))
        db.update("delete_acknowledgements",ContentValues().apply { put("state","RESTORED") },
            "scope=? AND event=? AND mutation=? AND attempt=? AND payload=? AND state='PENDING'",
            arrayOf(key(scope),event.value,receipt.intent.id.value,receipt.attempt.id.value,DeleteAcknowledgementCodec.encode(receipt))) == 1
    }

    fun conflictLocalDelete(db: SQLiteDatabase, intent: LocalDeleteIntent, observed: RemoteChange.Upsert): Boolean = run {
        val scope = intent.scope; val event = intent.base.eventId
        val owner = readInspectionDeleteOwner(db,scope,event) ?: return@run false
        if (owner.copy(state = LocalEditState.PAUSED) != intent || observed.eventId != event || observed == intent.base ||
            conflicts(db).currentFormat(scope) != intent.format) return@run false
        val archive = checkNotNull(conflicts(db).ensure(scope,event,true))
        conflicts(db).append(archive,ConflictObservationKind.VERIFIED,ConflictObservationOrigin.RECEIVED,
            intent.format,observed.revision,null,encodeChange(observed))
        db.update("local_edits",ContentValues().apply { put("state","CONFLICT") },"scope=? AND event=? AND mutation=? AND kind='DELETE'",
            arrayOf(key(scope),event.value,intent.id.value)) == 1
    }

    fun conflictAcknowledgedDelete(db: SQLiteDatabase, intent: LocalDeleteIntent): Boolean = run {
        val scope = intent.scope; val event = intent.base.eventId
        val retained = readDeleteAcknowledgement(db,scope,event) ?: return@run false
        val owner = readInspectionDeleteOwner(db,scope,event) ?: return@run false
        if (retained.receipt.intent != intent || owner.copy(state=LocalEditState.PAUSED) != intent) return@run false
        conflicts(db).ensure(scope,event,true)
        db.update("local_edits",ContentValues().apply { put("state","CONFLICT") },"scope=? AND event=? AND mutation=? AND kind='DELETE'",
            arrayOf(key(scope),event.value,intent.id.value)) == 1
    }

    fun captureLocalEdit(db: SQLiteDatabase, intent: LocalEditIntent): LocalEditIntent = run {
        require(intent.state == LocalEditState.PAUSED) { "Caller cannot declare conflict state" }
        check(isAvailable(db, intent.scope) && db.rawQuery("SELECT enabled FROM calendars WHERE scope=?",
            arrayOf(key(intent.scope))).use { it.moveToFirst() && it.getInt(0) != 0 }) { "Inactive local capture scope" }
        requireOrdinaryLocalCapture(db, intent.scope, intent.base.eventId)
        val previous = readLocalEdit(db, intent.scope, intent.base.eventId)
        if (previous != null) {
            require(previous.copy(state = LocalEditState.PAUSED) == intent) { "Local edit is immutable" }
            return@run previous
        }
        require(!hasLocalMutation(db, intent.scope, intent.base.eventId)) { "Local mutation kind collision" }
        require(readBaseline(db, intent.scope, intent.base.eventId) == intent.base) { "Stale projection baseline" }
        val hasDeferred = db.rawQuery("SELECT 1 FROM deferred_events WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { it.moveToFirst() }
        val remote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val captured = intent.copy(state = if (hasDeferred || remote != intent.base) LocalEditState.CONFLICT else LocalEditState.PAUSED)
        db.insertOrThrow("local_edits", null, ContentValues().apply {
            put("kind", "UPDATE")
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version); put("base", encodeChange(intent.base))
            put("local", encodeChange(intent.base.copy(content = intent.content))); put("state", captured.state.name)
        })
        db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value))
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        captured
    }

    fun captureLocalDelete(db: SQLiteDatabase, intent: LocalDeleteIntent): LocalDeleteIntent = run {
        require(intent.state == LocalEditState.PAUSED) { "Caller cannot declare conflict state" }
        check(activeProjectionScope(db, intent.scope)) { "Inactive local capture scope" }
        requireOrdinaryLocalCapture(db, intent.scope, intent.base.eventId)
        val previous = readLocalDelete(db, intent.scope, intent.base.eventId)
        if (previous != null) {
            require(previous.copy(state = LocalEditState.PAUSED) == intent) { "Local deletion is immutable" }
            return@run previous
        }
        require(!hasLocalMutation(db, intent.scope, intent.base.eventId)) { "Local mutation kind collision" }
        require(readBaseline(db, intent.scope, intent.base.eventId) == intent.base) { "Stale projection baseline" }
        val hasDeferred = db.rawQuery("SELECT 1 FROM deferred_events WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { it.moveToFirst() }
        val remote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val captured = intent.copy(state = if (hasDeferred || remote != intent.base) LocalEditState.CONFLICT else LocalEditState.PAUSED)
        db.insertOrThrow("local_edits", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value); put("kind", "DELETE")
            put("adapter", intent.format.id); put("version", intent.format.version); put("base", encodeChange(intent.base))
            put("local", encodeChange(intent.base.copy(content = intent.content))); put("state", captured.state.name)
        })
        db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value))
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        captured
    }

    fun textAcknowledgementSource(db: SQLiteDatabase, receipt: TextUpdateAcknowledgementReceipt): Boolean {
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        if (hasForeignLocalPreparation(db, scope, event, "local_text_edit_preparations")) return false
        val frame = receipt.handoff ?: return ordinaryUpdateAcknowledgement(db, receipt.intent, receipt.handoff)
        if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, receipt.intent.format) ||
            hasRawEventRow(db, "projection_receipts", scope, event)) return false
        // This reader validates the full immutable origin/PROVEN acknowledgement and current base.
        // The ordinary handoff clean guard cannot be reused while this exact local intent is held.
        val current = readCapturedHandoff(db, scope, event) ?: return false
        return current.state == CapturedCreateHandoffState.RELEASED && current.waiting && current.frame == frame
    }

    fun timeAcknowledgementSource(db: SQLiteDatabase, receipt: TimeUpdateAcknowledgementReceipt): Boolean {
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        if (hasForeignLocalPreparation(db, scope, event, "local_time_edit_preparations")) return false
        val frame = receipt.handoff ?: return ordinaryUpdateAcknowledgement(db, receipt.intent, receipt.handoff)
        if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, receipt.intent.format) ||
            hasRawEventRow(db, "projection_receipts", scope, event)) return false
        // This reader validates the full immutable origin/PROVEN acknowledgement and current base.
        // The ordinary handoff clean guard cannot be reused while this exact local intent is held.
        val current = readCapturedHandoff(db, scope, event) ?: return false
        return current.state == CapturedCreateHandoffState.RELEASED && current.waiting && current.frame == frame
    }

    fun personalAcknowledgementSource(db: SQLiteDatabase, receipt: PersonalUpdateAcknowledgementReceipt): Boolean {
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        if (hasForeignLocalPreparation(db, scope, event, "personal_edit_preparations")) return false
        val frame = receipt.handoff ?: return ordinaryUpdateAcknowledgement(db, receipt.intent, receipt.handoff)
        if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, receipt.intent.format) ||
            hasRawEventRow(db, "projection_receipts", scope, event)) return false
        // This reader validates the full immutable origin/PROVEN acknowledgement and current base.
        // The ordinary handoff clean guard cannot be reused while this exact local intent is held.
        val current = readCapturedHandoff(db, scope, event) ?: return false
        return current.state == CapturedCreateHandoffState.RELEASED && current.waiting && current.frame == frame
    }

    fun titleAcknowledgementSource(db: SQLiteDatabase, receipt: TitleUpdateAcknowledgementReceipt): Boolean {
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        if (hasForeignLocalPreparation(db, scope, event, "local_edit_preparations")) return false
        val frame = receipt.handoff ?: return ordinaryUpdateAcknowledgement(db, receipt.intent, receipt.handoff)
        if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, receipt.intent.format) ||
            hasRawEventRow(db, "projection_receipts", scope, event)) return false
        // This reader validates the full immutable origin/PROVEN acknowledgement and current base.
        // The ordinary handoff clean guard cannot be reused while this exact local intent is held.
        val current = readCapturedHandoff(db, scope, event) ?: return false
        return current.state == CapturedCreateHandoffState.RELEASED && current.waiting && current.frame == frame
    }

    fun acknowledge(db: SQLiteDatabase, intent: ProjectionIntent): Int = run {
        requireOrdinaryLocalCapture(db, intent.scope, intent.change.eventId)
        val outstanding = readProjectionReceipt(db, intent.scope, intent.change.eventId)
        require(outstanding?.representation == null && outstanding?.previousRepresentation == null) { "Represented projection requires tracked acknowledgement" }
        require(readProjectionBase(db, intent.scope, intent.change.eventId)?.representation?.representation != ProjectionRepresentation.FINITE_DAILY_V1) {
            "Represented baseline requires tracked projection"
        }
        val consumed = db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1 AND NOT EXISTS " +
                "(SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)",
            arrayOf(key(intent.scope), intent.change.eventId.value, encodeChange(intent.change)))
        if (consumed != 0) when (intent.change) {
            is RemoteChange.Upsert -> db.insertWithOnConflict("projection_bases", null, ContentValues().apply {
                put("scope", key(intent.scope)); put("event", intent.change.eventId.value); put("payload", encodeChange(intent.change))
                putNull("representation")
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            is RemoteChange.Deleted -> db.delete("projection_bases", "scope=? AND event=?", arrayOf(key(intent.scope), intent.change.eventId.value))
        }
        consumed
    }

}

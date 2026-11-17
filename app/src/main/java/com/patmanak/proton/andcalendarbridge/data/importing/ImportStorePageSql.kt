package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodePageCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodePageCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.MasterState
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.masterStates
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.masterUid
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.requeueSeriesChildren
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.updateSeries
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSeriesSql.suppressInactiveChildren
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readReceived
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.writeCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.ensureDeltaState
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readReconciliationFormat
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readDeltaCursor
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readDeltaReceived
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.encodeNotice
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.decodeNotice
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.encodeReceivedEvent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.decodeReceivedEvent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasLocalMutation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.localMutationBase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.deferred
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.pending
import com.patmanak.proton.andcalendarbridge.sync.*
import org.json.JSONObject

/** SQL operations only; ImportStore owns the mutex, lease, transaction and commit boundaries. */
internal class ImportStorePageTransaction(
    private val db: SQLiteDatabase,
    private val scope: CalendarScope,
    private val conflicts: (SQLiteDatabase) -> ConflictArchiveSql,
    private val matchingDeleteReservation: (SQLiteDatabase, CalendarScope, EventId) -> Boolean,
) : DeltaSyncTransaction {
    override var coverage: CoverageLedger
        get() = readCoverage(db, scope)
        set(value) = writeCoverage(db, scope, value)
    override val reconciliationFormat: AdapterFormat?
        get() = readReconciliationFormat(db, scope)
    override fun retainReconciliation(receipt: ReceivedChangePage) {
        require(receipt.scope == scope)
        val format = receipt.after.format
        require(reconciliationFormat == null || reconciliationFormat == format)
        require(deltaCursor == null || deltaCursor?.format == format)
        require(deltaReceived == null || deltaReceived?.after?.format == format)
        db.insertOrThrow("delta_reconciliation", null, ContentValues().apply {
            put("scope", key(scope)); put("adapter", format.id); put("version", format.version)
            put("after_cursor", receipt.after.value); put("payload", receipt.protectedPayload)
        })
    }
    override var deltaCursor: OpaqueCursor?
        get() = readDeltaCursor(db, scope)
        set(value) {
            val previous = readDeltaCursor(db, scope)
            require(value == null || previous == null || value.format == previous.format)
            require(value == null || reconciliationFormat == null || value.format == reconciliationFormat)
            val pending = readDeltaReceived(db, scope)
            require(pending == null || value != null && value.format == pending.after.format)
            ensureDeltaState(db, scope)
            db.update("delta_state", ContentValues().apply {
                if (value == null) { putNull("cursor_adapter"); putNull("cursor_version"); putNull("cursor_value") }
                else { put("cursor_adapter", value.format.id); put("cursor_version", value.format.version); put("cursor_value", value.value) }
            }, "scope=?", arrayOf(key(scope)))
        }
    override var deltaReceived: ReceivedChangePage?
        get() = readDeltaReceived(db, scope)
        set(value) {
            if (value != null) {
                require(value.scope == scope)
                require(reconciliationFormat == null || value.after.format == reconciliationFormat)
                val current = readDeltaCursor(db, scope)
                require(current == null || current == value.after)
                val previous = readDeltaReceived(db, scope)
                check(previous == null || previous == value) { "A received change page is still pending" }
            }
            ensureDeltaState(db, scope)
            db.update("delta_state", ContentValues().apply {
                if (value == null) { putNull("received_adapter"); putNull("received_version"); putNull("received_after"); putNull("received_payload") }
                else { put("received_adapter", value.after.format.id); put("received_version", value.after.format.version); put("received_after", value.after.value); put("received_payload", value.protectedPayload) }
            }, "scope=?", arrayOf(key(scope)))
        }
    override var deltaMore: Boolean
        get() = db.rawQuery("SELECT more FROM delta_state WHERE scope=?", arrayOf(key(scope))).use { it.moveToFirst() && it.getInt(0) != 0 }
        set(value) {
            ensureDeltaState(db, scope)
            db.update("delta_state", ContentValues().apply { put("more", if (value) 1 else 0) }, "scope=?", arrayOf(key(scope)))
        }
    override fun deltaJobs(limit: Int, blocked: Boolean): List<CalendarChangeJob> {
        require(limit in 1..20)
        // Only context/content notices lack an event ID. Refresh their keys/settings
        // before selecting a bounded group of individual event obligations.
        return stagedQuery(db, "SELECT notice,received,blocked FROM delta_jobs WHERE scope=? AND blocked=? ORDER BY CASE WHEN event IS NULL THEN 0 ELSE 1 END,rowid LIMIT $limit",
            arrayOf(key(scope), if (blocked) "1" else "0")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CalendarChangeJob(decodeNotice(cursor.getString(0)),
                if (cursor.isNull(1)) null else decodeReceivedEvent(scope, cursor.getString(1)), cursor.getInt(2) != 0)) }
        }
    }
    override fun putDeltaJob(job: CalendarChangeJob) {
        job.received?.let {
            require(job.notice.kind == CalendarNoticeKind.EVENT_CHANGED && it.scope == scope && it.eventId == job.notice.eventId)
            require(checkpoint == null || it.format == checkpoint?.format)
        }
        val values = ContentValues().apply {
            put("scope", key(scope)); put("notice_id", job.notice.id); put("notice", encodeNotice(job.notice))
            job.notice.eventId?.let { put("event", it.value) } ?: putNull("event")
            job.received?.let { put("received", encodeReceivedEvent(it)) } ?: putNull("received")
            put("blocked", if (job.blocked) 1 else 0)
        }
        if (db.update("delta_jobs", values, "scope=? AND notice_id=?", arrayOf(key(scope), job.notice.id)) == 0)
            db.insertOrThrow("delta_jobs", null, values)
    }
    override fun removeDeltaJob(expected: CalendarChangeJob): Boolean = db.delete("delta_jobs",
        "scope=? AND notice_id=? AND notice=? AND received IS ? AND blocked=?", arrayOf(key(scope), expected.notice.id,
            encodeNotice(expected.notice), expected.received?.let(::encodeReceivedEvent), if (expected.blocked) "1" else "0")) != 0
    override fun clearBlockedEvent(eventId: EventId) {
        db.delete("delta_jobs", "scope=? AND event=? AND blocked=1", arrayOf(key(scope), eventId.value))
    }
    override fun retryBlockedJobs() {
        db.update("delta_jobs", ContentValues().apply { put("blocked", 0) }, "scope=? AND blocked=1", arrayOf(key(scope)))
    }
    override var received: ReceivedCalendarPage?
        get() = readReceived(db, scope)
        set(value) {
            if (value == null) {
                db.delete("inbox", "scope=?", arrayOf(key(scope)))
            } else {
                require(value.request.checkpoint.scope == scope && value.request.limit in 1..100)
                val previous = readReceived(db, scope)
                check(previous == null || previous == value) { "A received page is still pending" }
                if (previous == null) db.insertOrThrow("inbox", null, ContentValues().apply {
                    put("scope", key(scope)); put("request_checkpoint", encodeCheckpoint(value.request.checkpoint))
                    put("request_limit", value.request.limit); put("payload", value.protectedPayload)
                    value.coverage?.let { put("coverage", encodePageCoverage(it)) } ?: putNull("coverage")
                })
            }
        }
    override fun defer(event: DeferredCalendarEvent) {
        val archive = conflicts(db).ensure(scope, event.eventId, force = true)
        db.insertWithOnConflict("deferred_events", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.eventId.value); put("revision", event.revision.value)
            put("adapter", event.format.id); put("version", event.format.version); put("reason", event.reason.name)
            put("payload", event.protectedPayload); put("retry_pending", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not retain deferred event" } }
        db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=?", arrayOf(key(scope), event.eventId.value))
        archive?.let { conflicts(db).append(it, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.RECEIVED,
            event.format, event.revision, event.reason, event.protectedPayload) }
    }
    override fun clearDeferred(eventId: EventId) {
        val removed = db.delete("deferred_events", "scope=? AND event=?", arrayOf(key(scope), eventId.value))
        if (removed != 0) masterUid(db, scope, eventId)?.let {
            requeueSeriesChildren(db, scope, it)
        }
    }
    override fun deferred(eventId: EventId): DeferredCalendarEvent? = stagedQuery(db,
        "SELECT revision,adapter,version,reason,payload FROM deferred_events WHERE scope=? AND event=?",
        arrayOf(key(scope), eventId.value),
    ).use {
        if (!it.moveToFirst()) null else DeferredCalendarEvent(eventId, Revision(it.getString(0)),
            AdapterFormat(it.getString(1), it.getInt(2)), DeferredEventReason.valueOf(it.getString(3)), it.getString(4))
    }
    override var checkpoint: PageCheckpoint?
        get() = db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
            check(it.moveToFirst()); if (it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0))
        }
        set(value) {
            require(value == null || value.scope == scope)
            db.update("calendars", ContentValues().apply {
                if (value == null) putNull("checkpoint") else put("checkpoint", encodeCheckpoint(value))
            }, "scope=?", arrayOf(key(scope)))
        }
    override val access = CalendarAccess()
    override fun remote(eventId: EventId): RemoteChange? = db.query("records", arrayOf("payload"),
        "scope=? AND event=?", arrayOf(key(scope), eventId.value), null, null, null).use {
        if (it.moveToFirst()) decodeChange(it.getString(0)) else null
    }
    override fun putRemote(change: RemoteChange) {
        val ownerBase = localMutationBase(db, scope, change.eventId)
        val deletionEcho = change is RemoteChange.Deleted && matchingDeleteReservation(db,scope,change.eventId)
        val archive = conflicts(db).ensure(scope, change.eventId, force = ownerBase != null && change != ownerBase && !deletionEcho)
        updateSeries(db, scope, change)
        db.insertWithOnConflict("records", null, ContentValues().apply {
            put("scope", key(scope)); put("event", change.eventId.value)
            put("payload", encodeChange(change)); put("pending", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        localMutationBase(db, scope, change.eventId)?.let { base ->
            if (change != base && !deletionEcho) db.update("local_edits", ContentValues().apply {
                put("state", LocalEditState.CONFLICT.name)
            }, "scope=? AND event=?", arrayOf(key(scope), change.eventId.value))
        }
        archive?.let { conflicts(db).append(it, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.RECEIVED,
            conflicts(db).currentFormat(scope), change.revision, null, encodeChange(change)) }
        masterUid(db, scope, change.eventId)?.let {
            requeueSeriesChildren(db, scope, it)
        }
    }
    override fun hasPendingMutation(eventId: EventId) = hasLocalMutation(db, scope, eventId)
    override fun putProjection(intent: ProjectionIntent) {
        require(intent.scope == scope && remote(intent.change.eventId) == intent.change)
        val recurrence = (intent.change as? RemoteChange.Upsert)?.content?.recurrence
        val inactiveParent = recurrence != null && !recurrence.isMaster && masterStates(db, scope)[recurrence.uid] == MasterState.INACTIVE
        db.update("records", ContentValues().apply { put("pending", if (inactiveParent || hasPendingMutation(intent.change.eventId)) 0 else 1) },
            "scope=? AND event=?", arrayOf(key(scope), intent.change.eventId.value))
    }
    override fun removeProjection(eventId: EventId) {
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=?", arrayOf(key(scope), eventId.value))
    }
    override fun mutation(id: MutationId): Mutation? = null
    override fun putMutation(mutation: Mutation): Unit = error("Outgoing changes are not enabled")
}

internal object ImportStorePageState {
    fun countDeferred(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM deferred_events WHERE scope=?", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) }
    fun countPending(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM records r WHERE scope=? AND pending IN (1,2) AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) }
    fun countUnverified(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM deferred_events WHERE scope=? AND reason=?",
        arrayOf(key(scope), DeferredEventReason.UNVERIFIED_CONTENT.name),
    ).use { check(it.moveToFirst()); it.getInt(0) }

    fun readReceived(db: SQLiteDatabase, scope: CalendarScope): ReceivedCalendarPage? = stagedQuery(db,
        "SELECT request_checkpoint,request_limit,payload,coverage FROM inbox WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst()) null else ReceivedCalendarPage(PageRequest(decodeCheckpoint(scope, it.getString(0)), it.getInt(1)), it.getString(2),
            if (it.isNull(3)) null else decodePageCoverage(it.getString(3)))
    }

    fun readCoverage(db: SQLiteDatabase, scope: CalendarScope): CoverageLedger = db.rawQuery(
        "SELECT payload FROM coverage_state WHERE scope=?", arrayOf(key(scope)),
    ).use { if (it.moveToFirst()) decodeCoverage(it.getString(0)) else CoverageLedger() }

    fun writeCoverage(db: SQLiteDatabase, scope: CalendarScope, value: CoverageLedger) {
        db.insertWithOnConflict("coverage_state", null, ContentValues().apply {
            put("scope", key(scope)); put("payload", encodeCoverage(value))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not retain coverage" } }
    }

    fun ensureDeltaState(db: SQLiteDatabase, scope: CalendarScope) {
        db.insertWithOnConflict("delta_state", null, ContentValues().apply { put("scope", key(scope)) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun hasReconciliation(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM delta_reconciliation WHERE scope=?)", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) != 0 }
    fun readReconciliationFormat(db: SQLiteDatabase, scope: CalendarScope): AdapterFormat? = db.rawQuery(
        "SELECT adapter,version FROM delta_reconciliation WHERE scope=? ORDER BY id LIMIT 1", arrayOf(key(scope)),
    ).use { if (it.moveToFirst()) AdapterFormat(it.getString(0), it.getInt(1)) else null }
    fun readDeltaCursor(db: SQLiteDatabase, scope: CalendarScope): OpaqueCursor? = db.rawQuery(
        "SELECT cursor_adapter,cursor_version,cursor_value FROM delta_state WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst() || it.isNull(2)) null else OpaqueCursor(AdapterFormat(it.getString(0), it.getInt(1)), it.getString(2))
    }
    fun readDeltaReceived(db: SQLiteDatabase, scope: CalendarScope): ReceivedChangePage? = stagedQuery(db,
        "SELECT received_adapter,received_version,received_after,received_payload FROM delta_state WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst() || it.isNull(3)) null else ReceivedChangePage(scope,
            OpaqueCursor(AdapterFormat(it.getString(0), it.getInt(1)), it.getString(2)), it.getString(3))
    }
    fun encodeNotice(value: CalendarChangeNotice) = JSONObject().apply {
        put("id", value.id); put("kind", value.kind.name)
        value.eventId?.let { put("event", it.value) }; value.revision?.let { put("revision", it.value) }
    }.toString()
    fun decodeNotice(payload: String): CalendarChangeNotice = JSONObject(payload).let {
        CalendarChangeNotice(it.getString("id"), CalendarNoticeKind.valueOf(it.getString("kind")),
            if (it.isNull("event")) null else EventId(it.getString("event")),
            if (it.isNull("revision")) null else Revision(it.getString("revision")))
    }
    fun encodeReceivedEvent(value: ReceivedCalendarEvent) = JSONObject().apply {
        put("event", value.eventId.value); put("revision", value.revision.value)
        put("adapter", value.format.id); put("version", value.format.version); put("payload", value.protectedPayload)
    }.toString()
    fun decodeReceivedEvent(scope: CalendarScope, payload: String): ReceivedCalendarEvent = JSONObject(payload).let {
        ReceivedCalendarEvent(scope, EventId(it.getString("event")), Revision(it.getString("revision")),
            AdapterFormat(it.getString("adapter"), it.getInt("version")), it.getString("payload"))
    }

    fun checkStagingCapacity(db: SQLiteDatabase) {
        // Bound active obligations. Completed immutable proofs and RESTORED deletion barriers
        // stay on disk for replay/identity protection but cannot exhaust this working budget.
        val bytes = db.rawQuery("SELECT COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+COALESCE(length(CAST(coverage AS BLOB)),0)) FROM inbox),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM coverage_state),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM deferred_events),0) + COALESCE((SELECT SUM(length(CAST(received_payload AS BLOB))) FROM delta_state),0) + COALESCE((SELECT SUM(length(CAST(notice AS BLOB))+COALESCE(length(CAST(received AS BLOB)),0)) FROM delta_jobs),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM delta_reconciliation),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))) FROM local_edit_preparations),0)" +
            " + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))+16384) FROM local_text_edit_preparations),0)" +
            " + COALESCE((SELECT SUM(length(CAST(receipt AS BLOB))+16384) FROM text_update_acknowledgements WHERE state='PENDING'),0)" +
            " + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))+16384) FROM local_time_edit_preparations),0)" +
            " + COALESCE((SELECT SUM(length(CAST(receipt AS BLOB))+16384) FROM time_update_acknowledgements WHERE state='PENDING'),0)" +
            " + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))+16384) FROM personal_edit_preparations),0)" +
            " + COALESCE((SELECT SUM(length(CAST(receipt AS BLOB))+16384) FROM personal_update_acknowledgements WHERE state='PENDING'),0)" +
            " + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+16384) FROM delete_acknowledgements WHERE state='PENDING'),0)" +
            " + COALESCE((SELECT SUM(length(CAST(scope AS BLOB))+length(CAST(event AS BLOB))+length(CAST(mutation AS BLOB))+length(CAST(adapter AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))+64) FROM conflict_archives),0)" +
            " + COALESCE((SELECT SUM(COALESCE(length(CAST(payload AS BLOB)),0)+COALESCE(length(CAST(adapter AS BLOB)),0)+COALESCE(length(CAST(revision AS BLOB)),0)+128) FROM conflict_observations),0)", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        if (bytes > ImportStore.STAGING_BUDGET_BYTES) throw ImportCapacityException()
    }

    fun requireKnown(db: SQLiteDatabase, scope: CalendarScope) {
        db.rawQuery("SELECT 1 FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            check(it.moveToFirst()) { "Unknown import scope" }
        }
    }
    fun hasLocalMutation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): Boolean = db.rawQuery(
        "SELECT 1 FROM local_edits WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value),
    ).use { it.moveToFirst() }
    fun localMutationBase(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = db.rawQuery(
        "SELECT base FROM local_edits WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value),
    ).use {
        if (!it.moveToFirst()) null else (decodeChange(it.getString(0)) as RemoteChange.Upsert).also { base ->
            check(base.eventId == eventId) { "Local mutation identity mismatch" }
        }
    }

    fun requeueSupported(db: SQLiteDatabase, scope: CalendarScope) {
        db.update("records",
        ContentValues().apply { put("pending", 1) },
        "scope=? AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=records.scope AND d.event=records.event) " +
            "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)", arrayOf(key(scope)))
        suppressInactiveChildren(db, scope)
    }
    fun deferred(db: SQLiteDatabase, scope: CalendarScope, limit: Int = 4, afterEventId: EventId? = null): List<DeferredCalendarEvent> = run {
        require(limit in 1..4); requireKnown(db, scope)
        val condition = if (afterEventId == null) "scope=?" else "scope=? AND event>?"
        val args = if (afterEventId == null) arrayOf(key(scope)) else arrayOf(key(scope), afterEventId.value)
        stagedQuery(db, "SELECT event,revision,adapter,version,reason,payload FROM deferred_events WHERE $condition ORDER BY event LIMIT $limit", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeferredCalendarEvent(EventId(cursor.getString(0)), Revision(cursor.getString(1)),
                    AdapterFormat(cursor.getString(2), cursor.getInt(3)), DeferredEventReason.valueOf(cursor.getString(4)), cursor.getString(5)))
            }
        }
    }

    fun deferredCount(db: SQLiteDatabase, scope: CalendarScope): Int = run {
        requireKnown(db, scope); countDeferred(db, scope)
    }

    fun prepareDeferredRetry(db: SQLiteDatabase, scope: CalendarScope): Int = run {
        db.update("deferred_events", ContentValues().apply { put("retry_pending", 1) }, "scope=?", arrayOf(key(scope)))
    }

    fun pendingDeferredRetries(db: SQLiteDatabase, scope: CalendarScope, limit: Int = 4): List<DeferredCalendarEvent> = run {
        require(limit in 1..4); requireKnown(db, scope)
        stagedQuery(db, "SELECT event,revision,adapter,version,reason,payload FROM deferred_events WHERE scope=? AND retry_pending=1 ORDER BY event LIMIT $limit", arrayOf(key(scope))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeferredCalendarEvent(EventId(cursor.getString(0)), Revision(cursor.getString(1)),
                    AdapterFormat(cursor.getString(2), cursor.getInt(3)), DeferredEventReason.valueOf(cursor.getString(4)), cursor.getString(5)))
            }
        }
    }

    fun acknowledgeDeferredRetry(db: SQLiteDatabase, scope: CalendarScope, event: DeferredCalendarEvent): Int = run {
        db.update("deferred_events", ContentValues().apply { put("retry_pending", 0) },
            "scope=? AND event=? AND revision=? AND adapter=? AND version=? AND reason=? AND payload=? AND retry_pending=1",
            arrayOf(key(scope), event.eventId.value, event.revision.value, event.format.id, event.format.version.toString(), event.reason.name, event.protectedPayload))
    }

    fun mappingRescanPending(db: SQLiteDatabase, scope: CalendarScope): Boolean = run {
        requireKnown(db, scope)
        db.query("mapping_profiles", arrayOf("rescan_pending"), "scope=?", arrayOf(key(scope)),
            null, null, null).use { it.moveToFirst() && it.getInt(0) != 0 }
    }

    fun complete(db: SQLiteDatabase, scope: CalendarScope) = run {
        db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
            it.moveToFirst() && !it.isNull(0) && decodeCheckpoint(scope, it.getString(0)).traversalComplete
        }
    }

    fun projectionBacklog(db: SQLiteDatabase, scope: CalendarScope): ImportStore.ProjectionBacklog {
        requireKnown(db, scope)
        return db.rawQuery("SELECT COUNT(*), COUNT(CASE WHEN pending=2 THEN 1 END) " +
            "FROM records WHERE scope=? AND pending IN (1,2)", arrayOf(key(scope))).use {
            check(it.moveToFirst())
            ImportStore.ProjectionBacklog(pending = it.getInt(0), locallyHeld = it.getInt(1))
        }
    }

    fun pending(db: SQLiteDatabase, scope: CalendarScope, limit: Int = 100, excludedEvents: Set<EventId> = emptySet()): List<ProjectionIntent> = run {
        require(limit in 1..100); requireKnown(db, scope)
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.payload,r.event FROM records r LEFT JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event) ORDER BY CASE WHEN s.role='master' THEN 0 ELSE 1 END,r.rowid", arrayOf(key(scope))).use { cursor ->
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    if (EventId(cursor.getString(1)) in excludedEvents) continue
                    val change = decodeChange(cursor.getString(0))
                    val recurrence = (change as? RemoteChange.Upsert)?.content?.recurrence
                    if (recurrence == null || recurrence.isMaster || masters[recurrence.uid]?.accepts(recurrence.original) == true)
                        add(ProjectionIntent(scope, change))
                }
            }
        }
    }

    fun blockedSeriesCount(db: SQLiteDatabase, scope: CalendarScope): Int = run {
        requireKnown(db, scope)
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND s.role='exception' AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope))).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(0)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster) {
                    val parent = masters[recurrence.uid]
                    if (parent != MasterState.INACTIVE && parent?.accepts(recurrence.original) != true) count++
                }
            }
            count
        }
    }

    fun holdLocalProjection(db: SQLiteDatabase, intent: ProjectionIntent): Boolean = run {
        db.update("records", ContentValues().apply { put("pending", 2) },
            "scope=? AND event=? AND payload=? AND pending IN (0,1) AND NOT EXISTS " +
                "(SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)",
            arrayOf(key(intent.scope), intent.change.eventId.value, encodeChange(intent.change))) == 1
    }

    fun deletedSeriesUid(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): String? = run {
        requireKnown(db, scope)
        masterUid(db, scope, eventId)
    }

    fun projectedEventIds(db: SQLiteDatabase, scope: CalendarScope, afterEvent: EventId? = null, limit: Int = 100,
        admitTransaction: (CalendarScope) -> Boolean): List<EventId> = run {
        require(limit in 1..100)
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val ids = db.query("projection_bases", arrayOf("event"), "scope=? AND event>?",
            arrayOf(key(scope), afterEvent?.value ?: ""), null, null, "event ASC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(EventId(cursor.getString(0))) }
        }
        check(admitTransaction(scope)) { "Inactive import lease" }
        ids
    }

}

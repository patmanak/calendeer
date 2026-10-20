package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.requirePreparationSnapshotBudget
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveVersion
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.DateTimeException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONException

/** Synchronous conflict evidence on the caller's existing transaction. This collaborator
 * cannot open/commit a database, suspend, change admission or send/acknowledge an event. */
internal class ConflictArchiveSql(private val db: SQLiteDatabase, private val checkCapacity: () -> Unit) {
    /** Read-only evidence pages do not check global staging capacity or decode preparation capsules. */
    fun archives(query: ConflictArchiveQuery): ConflictArchivePage = archiveRead {
        val refs = db.rawQuery("SELECT id,${archiveText("event", 4096)},${archiveText("mutation", 4096)},${archiveText("kind", 8)} " +
            "FROM conflict_archives WHERE scope=? AND id>? ORDER BY id LIMIT ?",
            arrayOf(key(query.scope), (query.cursor?.afterId ?: 0).toString(), (query.limit + 1).toString())).use { rows ->
            buildList { while (rows.moveToNext()) add(archiveRef(query.scope, rows)) }
        }
        val visible = refs.take(query.limit)
        ConflictArchivePage(query.scope, visible, if (refs.size > query.limit) ConflictArchiveCursor(query.scope, visible.last().id) else null)
    }
    fun archive(ref: ConflictArchiveRef): ConflictArchiveDetail? = archiveRead { readConflictArchive(ref) }
    fun observations(query: ConflictObservationQuery): ConflictObservationPage = archiveRead {
        check(readConflictArchive(query.ref) != null) { "Conflict archive is absent" }
        // Select bounded metadata first. At most one payload enters a CursorWindow at a time.
        val ids = db.rawQuery("SELECT id FROM conflict_observations WHERE archive_id=? AND id>? ORDER BY id LIMIT ?",
            arrayOf(query.ref.id.toString(), (query.cursor?.afterId ?: 0).toString(), (query.limit + 1).toString())).use { rows ->
            buildList { while (rows.moveToNext()) { check(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(rows.getLong(0)) } }
        }
        val entries = ids.take(query.limit).map { readConflictObservation(query.ref, it) }
        ConflictObservationPage(query.ref, entries, if (ids.size > query.limit) ConflictObservationCursor(query.ref, entries.last().id) else null)
    }
    private inline fun <T> archiveRead(block: () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (capacity: ImportCapacityException) { throw capacity }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid conflict archive evidence") }

    private fun archiveRef(scope: CalendarScope, row: Cursor): ConflictArchiveRef {
        check(row.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "Invalid conflict identity" }
        return ConflictArchiveRef(scope, row.getLong(0), EventId(archiveString(row, 1)), MutationId(archiveString(row, 2)),
            MutationKind.valueOf(archiveString(row, 3)))
    }
    private fun readConflictArchive(ref: ConflictArchiveRef): ConflictArchiveDetail? = stagedQuery(db,
        "SELECT id,${archiveText("event", 4096)},${archiveText("mutation", 4096)},${archiveText("kind", 8)}," +
            "${archiveText("adapter", 4096)},${archiveVersion()},${archiveText("base")},${archiveText("local")} FROM conflict_archives WHERE scope=? AND id=?",
        arrayOf(key(ref.scope), ref.id.toString())).use { row ->
        if (!row.moveToFirst()) null else {
            if (archiveRef(ref.scope, row) != ref) return@use null
            check(row.getType(5) == Cursor.FIELD_TYPE_INTEGER && row.getLong(5) in 1..Int.MAX_VALUE) { "Invalid conflict format" }
            val format = AdapterFormat(archiveString(row, 4), row.getInt(5))
            val base = decodeChange(archiveString(row, 6)) as? RemoteChange.Upsert ?: error("Invalid conflict base")
            val local = decodeChange(archiveString(row, 7)) as? RemoteChange.Upsert ?: error("Invalid conflict capture")
            check(base.eventId == ref.eventId && local.eventId == ref.eventId && local.revision == base.revision) { "Invalid conflict binding" }
            validateConflictCapture(ref, format, base, local.content)
            ConflictArchiveDetail(ref, format, base, local.content)
        }
    }
    private fun validateConflictCapture(ref: ConflictArchiveRef, format: AdapterFormat, base: RemoteChange.Upsert, local: EventContent) {
        if (ref.kind == MutationKind.UPDATE) LocalEditIntent(ref.mutationId, ref.scope, format, base, local, LocalEditState.CONFLICT)
        else LocalDeleteIntent(ref.mutationId, ref.scope, format, base, local, LocalEditState.CONFLICT)
    }
    private fun readConflictObservation(ref: ConflictArchiveRef, id: Long): ConflictObservation = stagedQuery(db,
        "SELECT ${archiveText("kind", 16)},${archiveText("origin", 16)},${archiveText("observed_at", 64)}," +
            "${archiveText("adapter", 4096)},${archiveVersion()},${archiveText("revision", 4096)},${archiveText("reason", 64)},${archiveText("payload")}," +
            "observed_at IS NULL,adapter IS NULL,version IS NULL,revision IS NULL,reason IS NULL,payload IS NULL " +
            "FROM conflict_observations WHERE archive_id=? AND id=?", arrayOf(ref.id.toString(), id.toString())).use { row ->
        check(row.moveToFirst()) { "Missing conflict observation" }
        fun optional(column: Int, absence: Int) = if (row.getInt(absence) == 1) null else archiveString(row, column)
        val kind = ConflictObservationKind.valueOf(archiveString(row, 0))
        val origin = ConflictObservationOrigin.valueOf(archiveString(row, 1))
        val observed = optional(2, 8)?.let(Instant::parse)
        val adapter = optional(3, 9)
        val format = if (adapter == null) { check(row.getInt(10) == 1); null } else {
            check(row.getType(4) == Cursor.FIELD_TYPE_INTEGER && row.getLong(4) in 1..Int.MAX_VALUE)
            AdapterFormat(adapter, row.getInt(4))
        }
        val revision = optional(5, 11)?.let(::Revision)
        val reason = optional(6, 12)?.let(DeferredEventReason::valueOf)
        val payload = optional(7, 13)
        val change = when (kind) {
            ConflictObservationKind.VERIFIED -> decodeChange(checkNotNull(payload)).also {
                check(it.eventId == ref.eventId && it.revision == revision && reason == null)
            }
            ConflictObservationKind.DEFERRED -> {
                DeferredCalendarEvent(ref.eventId, checkNotNull(revision), checkNotNull(format), checkNotNull(reason), checkNotNull(payload)); null
            }
            ConflictObservationKind.UNOBSERVED -> { check(payload == null && revision == null && reason == null); null }
            ConflictObservationKind.UNREADABLE -> { check(payload != null && revision == null && reason == null); null }
        }
        ConflictObservation(id, kind, origin, observed, format, change, reason)
    }
    /** Called before replacing cache/deferred evidence. B/L is copied once and never updated. */
    fun ensure(scope: CalendarScope, event: EventId, force: Boolean = false): Long? = archiveRead {
        val owner = stagedQuery(db, "SELECT ${archiveText("mutation", 4096)},${archiveText("kind", 8)},${archiveText("adapter", 4096)},${archiveVersion()}," +
            "${archiveText("base")},${archiveText("local")},${archiveText("state", 8)} FROM local_edits WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else {
                val state = LocalEditState.valueOf(archiveString(row, 6))
                if (!force && state != LocalEditState.CONFLICT) return@use null
                check(row.getType(3) == Cursor.FIELD_TYPE_INTEGER && row.getLong(3) in 1..Int.MAX_VALUE)
                val ref = ConflictArchiveRef(scope, 1, event, MutationId(archiveString(row, 0)), MutationKind.valueOf(archiveString(row, 1)))
                val format = AdapterFormat(archiveString(row, 2), row.getInt(3))
                val base = decodeChange(archiveString(row, 4)) as? RemoteChange.Upsert ?: error("Invalid conflict base")
                val local = decodeChange(archiveString(row, 5)) as? RemoteChange.Upsert ?: error("Invalid conflict capture")
                check(base.eventId == event && local.eventId == event && local.revision == base.revision)
                validateConflictCapture(ref, format, base, local.content)
                ConflictArchiveDetail(ref, format, base, local.content)
            }
        } ?: return@archiveRead null
        val existing = db.rawQuery("SELECT id FROM conflict_archives WHERE scope=? AND mutation=?", arrayOf(key(scope), owner.ref.mutationId.value))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
        if (existing != null) {
            val retained = checkNotNull(readConflictArchive(owner.ref.copy(id = existing)))
            check(retained.format == owner.format && retained.base == owner.base && retained.local == owner.local) { "Conflict archive is immutable" }
            return@archiveRead existing
        }
        val base = encodeChange(owner.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(owner.base.copy(content = owner.local)).also(::requirePreparationSnapshotBudget)
        val id = db.insertOrThrow("conflict_archives", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("mutation", owner.ref.mutationId.value); put("kind", owner.ref.kind.name)
            put("adapter", owner.format.id); put("version", owner.format.version); put("base", base); put("local", local)
        })
        var observed = false
        stagedQuery(db, "SELECT ${archiveText("payload")} FROM records WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { row ->
            if (row.moveToFirst()) {
                // Bounds/storage type/UTF-8 failures still abort. Only malformed supported cache content
                // becomes a display marker; its exact raw text stays private and is never a revision.
                val raw = archiveString(row, 0)
                val change = decodeConflictCachedChange(raw, event)
                append(id,
                    if (change == null) ConflictObservationKind.UNREADABLE else ConflictObservationKind.VERIFIED,
                    ConflictObservationOrigin.CACHED, currentFormat(scope), change?.revision, null,
                    if (change == null) raw else encodeChange(change)); observed = true
            }
        }
        stagedQuery(db, "SELECT ${archiveText("revision", 4096)},${archiveText("adapter", 4096)},${archiveVersion()},${archiveText("reason", 64)},${archiveText("payload")} " +
            "FROM deferred_events WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { row ->
            if (row.moveToFirst()) {
                check(row.getType(2) == Cursor.FIELD_TYPE_INTEGER && row.getLong(2) in 1..Int.MAX_VALUE)
                val deferred = DeferredCalendarEvent(event, Revision(archiveString(row, 0)), AdapterFormat(archiveString(row, 1), row.getInt(2)),
                    DeferredEventReason.valueOf(archiveString(row, 3)), archiveString(row, 4))
                append(id, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.CACHED,
                    deferred.format, deferred.revision, deferred.reason, deferred.protectedPayload); observed = true
            }
        }
        if (!observed) append(id, ConflictObservationKind.UNOBSERVED, ConflictObservationOrigin.CACHED, null, null, null, null)
        checkCapacity()
        id
    }
    private fun decodeConflictCachedChange(raw: String, event: EventId): RemoteChange? = try {
        decodeChange(raw).also { require(it.eventId == event) { "Invalid cached event binding" } }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (capacity: ImportCapacityException) { throw capacity }
      catch (failure: SQLiteException) { throw failure }
      catch (_: JSONException) { null }
      catch (_: DateTimeException) { null }
      catch (_: IllegalArgumentException) { null }
      catch (_: IllegalStateException) { null }
    fun currentFormat(scope: CalendarScope): AdapterFormat? = db.rawQuery(
        "SELECT ${archiveText("checkpoint", 65536)},checkpoint IS NULL FROM calendars WHERE scope=?", arrayOf(key(scope))).use { row ->
        check(row.moveToFirst()); if (row.getInt(1) == 1) null else decodeCheckpoint(scope, archiveString(row, 0)).format
    }
    fun append(archive: Long, kind: ConflictObservationKind, origin: ConflictObservationOrigin,
        format: AdapterFormat?, revision: Revision?, reason: DeferredEventReason?, payload: String?) {
        listOfNotNull(format?.id, revision?.value).forEach { value ->
            check(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() } &&
                Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) { "Invalid conflict observation identity" }
        }
        payload?.let(::requirePreparationSnapshotBudget)
        // Compare all evidence bytes, never just the opaque remote revision. Collection time is not identity.
        // Android rawQuery binds selection arguments as strings and cannot bind null.
        // These column names are fixed locally; only non-null evidence becomes an argument.
        val arguments = mutableListOf(archive.toString(), kind.name, origin.name)
        val evidence = listOf("adapter" to format?.id, "version" to format?.version?.toString(),
            "revision" to revision?.value, "reason" to reason?.name, "payload" to payload)
        val predicates = evidence.joinToString(" AND ") { (column, value) ->
            if (value == null) "$column IS NULL" else { arguments.add(value); "$column=?" }
        }
        val exists = db.rawQuery("SELECT 1 FROM conflict_observations WHERE archive_id=? AND kind=? AND origin=? AND $predicates LIMIT 1",
            arguments.toTypedArray()).use { it.moveToFirst() }
        if (exists) return
        db.insertOrThrow("conflict_observations", null, ContentValues().apply {
            put("archive_id", archive); put("kind", kind.name); put("origin", origin.name)
            if (origin == ConflictObservationOrigin.RECEIVED) put("observed_at", Instant.now().toString()) else putNull("observed_at")
            put("adapter", format?.id); if (format == null) putNull("version") else put("version", format.version)
            put("revision", revision?.value); put("reason", reason?.name); put("payload", payload)
        })
        checkCapacity()
    }

}

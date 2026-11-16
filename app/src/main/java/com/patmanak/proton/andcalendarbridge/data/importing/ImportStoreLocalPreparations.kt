package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.requirePreparationSnapshotBudget
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** Family-specific prepared snapshots; transaction, cancellation and lease ownership stay with the store. */
internal object ImportStoreLocalPreparations {
    fun localTextPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent,
        readBaseline: (SQLiteDatabase, CalendarScope, EventId) -> RemoteChange.Upsert?): LocalTextPreparationAdmission {
        if (supportedLocalTextIntent(intent) == null) return LocalTextPreparationAdmission.UNQUALIFIED
        val target = intent.scope
        if (!activeProjectionScope(db, target)) return LocalTextPreparationAdmission.INACTIVE
        if (hasForeignLocalPreparation(db, target, intent.base.eventId, "local_text_edit_preparations"))
            return LocalTextPreparationAdmission.UNQUALIFIED
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(target))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(target, it.getString(0)).format
        }
        if (format != intent.format) return LocalTextPreparationAdmission.UNQUALIFIED
        val owner = readLocalPreparationOwner(db, target, intent.base.eventId) ?: return LocalTextPreparationAdmission.UNQUALIFIED
        if (owner != intent) return if (owner.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            LocalTextPreparationAdmission.CONFLICT else LocalTextPreparationAdmission.UNQUALIFIED
        if (owner.state == LocalEditState.CONFLICT) return LocalTextPreparationAdmission.CONFLICT
        if (readBaseline(db, target, intent.base.eventId) != intent.base) return LocalTextPreparationAdmission.UNQUALIFIED
        val remote = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(target), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        if (remote != intent.base || hasRawEventRow(db, "deferred_events", target, intent.base.eventId))
            return LocalTextPreparationAdmission.CONFLICT
        // A cache hit is historical evidence, never a fresh crypto/access qualification.
        if (readLocalTextEditPreparation(db, target, intent.base.eventId) != null) return LocalTextPreparationAdmission.HISTORICAL
        return LocalTextPreparationAdmission.ELIGIBLE
    }
    fun insertLocalTextPreparation(db: SQLiteDatabase, value: LocalTextEditPreparation, base: String, local: String,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        val intent = value.intent
        db.insertOrThrow("local_text_edit_preparations", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version)
            put("base", base); put("local", local); put("state", intent.state.name)
            put("timestamp", value.timestamp.toString()); put("prep_adapter", value.format.id)
            put("prep_version", value.format.version); put("payload", value.protectedPayload)
        })
        checkStagingCapacity(db)
    }
    fun sameTextPreparation(a: LocalTextEditPreparation, b: LocalTextEditPreparation) =
        a.intent == b.intent && a.timestamp == b.timestamp && a.format == b.format && a.protectedPayload == b.protectedPayload

    fun supportedLocalTextIntent(intent: LocalEditIntent): LocalEditIntent? = try {
        LocalTextEditPreparation.freezeIntent(intent).takeIf {
            it.base.content.details == EventDetails() && it.base.content.colorRgb == null &&
                !TextUpdateContentPolicy.sameSubmittedContent(it.base.content, it.content)
        }
    } catch (_: IllegalArgumentException) { null }

    fun readLocalTextEditPreparation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalTextEditPreparation? =
        try {
            stagedQuery(db, "SELECT ${archiveText("mutation", 4096)},${archiveText("adapter", 4096)},version," +
                "${archiveText("base")},${archiveText("local")},${archiveText("state", 16)},${archiveText("timestamp", 40)}," +
                "${archiveText("prep_adapter", 4096)},prep_version,${archiveText("payload")} FROM local_text_edit_preparations WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    val baseJson = archiveString(row, 3).also(::requirePreparationSnapshotBudget)
                    val localJson = archiveString(row, 4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    val frozen = LocalEditIntent(MutationId(archiveString(row, 0)), scope,
                        AdapterFormat(archiveString(row, 1), preparationVersion(row, 2)), base, local.content,
                        LocalEditState.valueOf(archiveString(row, 5)))
                    val timestamp = Instant.parse(archiveString(row, 6))
                    require(timestamp.toString() == archiveString(row, 6)) { "Noncanonical local preparation time" }
                    val preparation = LocalTextEditPreparation(frozen, timestamp,
                        AdapterFormat(archiveString(row, 7), preparationVersion(row, 8)), archiveString(row, 9))
                    require(supportedLocalTextIntent(preparation.intent) != null)
                    val owner = readLocalPreparationOwner(db, scope, eventId)
                    require(owner?.copy(state = LocalEditState.PAUSED) == frozen) { "Local preparation owner mismatch" }
                    preparation
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid retained text preparation") }
    fun localTimePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent,
        readBaseline: (SQLiteDatabase, CalendarScope, EventId) -> RemoteChange.Upsert?): LocalTimePreparationAdmission {
        if (supportedLocalTimeIntent(intent) == null) return LocalTimePreparationAdmission.UNQUALIFIED
        val target = intent.scope
        if (!activeProjectionScope(db, target)) return LocalTimePreparationAdmission.INACTIVE
        if (hasForeignLocalPreparation(db, target, intent.base.eventId, "local_time_edit_preparations"))
            return LocalTimePreparationAdmission.UNQUALIFIED
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(target))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(target, it.getString(0)).format
        }
        if (format != intent.format) return LocalTimePreparationAdmission.UNQUALIFIED
        val owner = readLocalPreparationOwner(db, target, intent.base.eventId) ?: return LocalTimePreparationAdmission.UNQUALIFIED
        if (owner != intent) return if (owner.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            LocalTimePreparationAdmission.CONFLICT else LocalTimePreparationAdmission.UNQUALIFIED
        if (owner.state == LocalEditState.CONFLICT) return LocalTimePreparationAdmission.CONFLICT
        if (readBaseline(db, target, intent.base.eventId) != intent.base) return LocalTimePreparationAdmission.UNQUALIFIED
        val remote = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(target), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        if (remote != intent.base || hasRawEventRow(db, "deferred_events", target, intent.base.eventId))
            return LocalTimePreparationAdmission.CONFLICT
        // A cache hit is historical evidence, never a fresh crypto/access qualification.
        if (readLocalTimeEditPreparation(db, target, intent.base.eventId) != null) return LocalTimePreparationAdmission.HISTORICAL
        return LocalTimePreparationAdmission.ELIGIBLE
    }
    fun insertLocalTimePreparation(db: SQLiteDatabase, value: LocalTimeEditPreparation, base: String, local: String,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        val intent = value.intent
        db.insertOrThrow("local_time_edit_preparations", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version)
            put("base", base); put("local", local); put("state", intent.state.name)
            put("timestamp", value.timestamp.toString()); put("prep_adapter", value.format.id)
            put("prep_version", value.format.version); put("payload", value.protectedPayload)
        })
        checkStagingCapacity(db)
    }
    fun sameTimePreparation(a: LocalTimeEditPreparation, b: LocalTimeEditPreparation) =
        a.intent == b.intent && a.timestamp == b.timestamp && a.format == b.format && a.protectedPayload == b.protectedPayload

    fun supportedLocalTimeIntent(intent: LocalEditIntent): LocalEditIntent? = try {
        LocalTimeEditCandidate(intent).intent.takeIf {
            it.base.content.details == EventDetails() && it.base.content.colorRgb == null &&
                !TimeUpdateContentPolicy.sameSubmittedContent(it.base.content, it.content)
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: java.nio.charset.CharacterCodingException) { null }

    fun readLocalTimeEditPreparation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalTimeEditPreparation? =
        try {
            stagedQuery(db, "SELECT ${archiveText("mutation", 4096)},${archiveText("adapter", 4096)},version," +
                "${archiveText("base")},${archiveText("local")},${archiveText("state", 16)},${archiveText("timestamp", 40)}," +
                "${archiveText("prep_adapter", 4096)},prep_version,${archiveText("payload")} FROM local_time_edit_preparations WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    val baseJson = archiveString(row, 3).also(::requirePreparationSnapshotBudget)
                    val localJson = archiveString(row, 4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    val frozen = LocalEditIntent(MutationId(archiveString(row, 0)), scope,
                        AdapterFormat(archiveString(row, 1), preparationVersion(row, 2)), base, local.content,
                        LocalEditState.valueOf(archiveString(row, 5)))
                    val timestamp = Instant.parse(archiveString(row, 6))
                    require(timestamp.toString() == archiveString(row, 6)) { "Noncanonical local preparation time" }
                    val preparation = LocalTimeEditPreparation(frozen, timestamp,
                        AdapterFormat(archiveString(row, 7), preparationVersion(row, 8)), archiveString(row, 9))
                    require(supportedLocalTimeIntent(preparation.intent) != null)
                    val owner = readLocalPreparationOwner(db, scope, eventId)
                    require(owner?.copy(state = LocalEditState.PAUSED) == frozen) { "Local preparation owner mismatch" }
                    preparation
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid retained time preparation") }
    fun personalPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent,
        readBaseline: (SQLiteDatabase, CalendarScope, EventId) -> RemoteChange.Upsert?): PersonalPreparationAdmission {
        if (supportedPersonalIntent(intent) == null) return PersonalPreparationAdmission.UNQUALIFIED
        val target = intent.scope
        if (!activeProjectionScope(db, target)) return PersonalPreparationAdmission.INACTIVE
        if (hasForeignLocalPreparation(db, target, intent.base.eventId, "personal_edit_preparations"))
            return PersonalPreparationAdmission.UNQUALIFIED
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(target))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(target, it.getString(0)).format
        }
        if (format != intent.format) return PersonalPreparationAdmission.UNQUALIFIED
        val owner = readLocalPreparationOwner(db, target, intent.base.eventId) ?: return PersonalPreparationAdmission.UNQUALIFIED
        if (owner != intent) return if (owner.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            PersonalPreparationAdmission.CONFLICT else PersonalPreparationAdmission.UNQUALIFIED
        if (owner.state == LocalEditState.CONFLICT) return PersonalPreparationAdmission.CONFLICT
        if (readBaseline(db, target, intent.base.eventId) != intent.base) return PersonalPreparationAdmission.UNQUALIFIED
        val remote = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(target), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        if (remote != intent.base || hasRawEventRow(db, "deferred_events", target, intent.base.eventId))
            return PersonalPreparationAdmission.CONFLICT
        // A cache hit is historical evidence, never a fresh crypto/access qualification.
        if (readPersonalEditPreparation(db, target, intent.base.eventId) != null) return PersonalPreparationAdmission.HISTORICAL
        return PersonalPreparationAdmission.ELIGIBLE
    }
    fun insertPersonalPreparation(db: SQLiteDatabase, value: PersonalEditPreparation, base: String, local: String,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        val intent = value.intent
        db.insertOrThrow("personal_edit_preparations", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version)
            put("base", base); put("local", local); put("state", intent.state.name)
            put("timestamp", value.timestamp.toString()); put("prep_adapter", value.format.id)
            put("prep_version", value.format.version); put("payload", value.protectedPayload)
        })
        checkStagingCapacity(db)
    }
    fun samePersonalPreparation(a: PersonalEditPreparation, b: PersonalEditPreparation) =
        a.intent == b.intent && a.timestamp == b.timestamp && a.format == b.format && a.protectedPayload == b.protectedPayload

    fun supportedPersonalIntent(intent: LocalEditIntent): LocalEditIntent? = try {
        (if (intent.seriesProfile != null) SeriesEditCandidate(intent).intent else PersonalEditCandidate(intent).intent).takeIf {
            !PersonalUpdateContentPolicy.sameSubmittedContent(it.base.content, it.content)
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: java.nio.charset.CharacterCodingException) { null }

    fun readPersonalEditPreparation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): PersonalEditPreparation? =
        try {
            stagedQuery(db, "SELECT ${archiveText("mutation", 4096)},${archiveText("adapter", 4096)},version," +
                "${archiveText("base")},${archiveText("local")},${archiveText("state", 16)},${archiveText("timestamp", 40)}," +
                "${archiveText("prep_adapter", 4096)},prep_version,${archiveText("payload")} FROM personal_edit_preparations WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    val baseJson = archiveString(row, 3).also(::requirePreparationSnapshotBudget)
                    val localJson = archiveString(row, 4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    val owner = requireNotNull(readLocalPreparationOwner(db, scope, eventId))
                    val frozen = LocalEditIntent(MutationId(archiveString(row, 0)), scope,
                        AdapterFormat(archiveString(row, 1), preparationVersion(row, 2)), base, local.content,
                        LocalEditState.valueOf(archiveString(row, 5)), owner.seriesProfile)
                    val timestamp = Instant.parse(archiveString(row, 6))
                    require(timestamp.toString() == archiveString(row, 6)) { "Noncanonical local preparation time" }
                    val format = AdapterFormat(archiveString(row, 7), preparationVersion(row, 8))
                    val preparation = if (owner.seriesProfile != null) {
                        require(format.version == 3)
                        PersonalEditPreparation.forSeries(frozen, timestamp, format, archiveString(row, 9))
                    } else PersonalEditPreparation(frozen, timestamp, format, archiveString(row, 9))
                    require(supportedPersonalIntent(preparation.intent) != null)
                    require(owner?.copy(state = LocalEditState.PAUSED) == frozen) { "Local preparation owner mismatch" }
                    preparation
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid retained personal preparation") }
    fun localTitlePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent,
        readBaseline: (SQLiteDatabase, CalendarScope, EventId) -> RemoteChange.Upsert?): LocalTitlePreparationAdmission {
        val target = intent.scope
        if (!activeProjectionScope(db, target)) return LocalTitlePreparationAdmission.INACTIVE
        if (hasForeignLocalPreparation(db, target, intent.base.eventId, "local_edit_preparations")) return LocalTitlePreparationAdmission.UNQUALIFIED
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(target))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(target, it.getString(0)).format
        }
        if (format != intent.format) return LocalTitlePreparationAdmission.UNQUALIFIED
        val owner = readPreparationOwner(db, target, intent.base.eventId) ?: return LocalTitlePreparationAdmission.UNQUALIFIED
        if (owner != intent) return if (owner.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            LocalTitlePreparationAdmission.CONFLICT else LocalTitlePreparationAdmission.UNQUALIFIED
        if (owner.state == LocalEditState.CONFLICT) return LocalTitlePreparationAdmission.CONFLICT
        if (readBaseline(db, target, intent.base.eventId) != intent.base) return LocalTitlePreparationAdmission.UNQUALIFIED
        val remote = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(target), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        if (remote != intent.base || hasRawEventRow(db, "deferred_events", target, intent.base.eventId))
            return LocalTitlePreparationAdmission.CONFLICT
        // A cache hit is historical evidence, never a fresh crypto/access qualification.
        if (readLocalEditPreparation(db, target, intent.base.eventId) != null) return LocalTitlePreparationAdmission.HISTORICAL
        return LocalTitlePreparationAdmission.ELIGIBLE
    }
    fun insertLocalTitlePreparation(db: SQLiteDatabase, value: LocalEditPreparation, base: String, local: String,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        val intent = value.intent
        db.insertOrThrow("local_edit_preparations", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version)
            put("base", base); put("local", local); put("state", intent.state.name)
            put("timestamp", value.timestamp.toString()); put("prep_adapter", value.format.id)
            put("prep_version", value.format.version); put("payload", value.protectedPayload)
        })
        checkStagingCapacity(db)
    }
    fun readLocalEditPreparation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        try {
            stagedQuery(db, "SELECT mutation,adapter,version,base,local,state,timestamp,prep_adapter,prep_version,payload FROM local_edit_preparations WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    val baseJson = row.getString(3).also(::requirePreparationSnapshotBudget)
                    val localJson = row.getString(4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    val frozen = LocalEditIntent(MutationId(row.getString(0)), scope,
                        AdapterFormat(row.getString(1), preparationVersion(row, 2)), base, local.content,
                        LocalEditState.valueOf(row.getString(5)))
                    val timestamp = Instant.parse(row.getString(6))
                    require(timestamp.toString() == row.getString(6)) { "Noncanonical local preparation time" }
                    val preparation = LocalEditPreparation(frozen, timestamp,
                        AdapterFormat(row.getString(7), preparationVersion(row, 8)), row.getString(9))
                    val owner = readPreparationOwner(db, scope, eventId)
                    require(owner?.copy(state = LocalEditState.PAUSED) == frozen) { "Local preparation owner mismatch" }
                    preparation
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid retained edit preparation") }
    fun hasForeignLocalPreparation(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        ownTable: String?): Boolean = listOf("local_edit_preparations", "local_text_edit_preparations", "local_time_edit_preparations", "personal_edit_preparations")
        .any { it != ownTable && hasRawEventRow(db, it, scope, event) }

    fun readLocalPreparationOwner(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        try {
            stagedQuery(db, "SELECT ${archiveText("mutation", 4096)},${archiveText("adapter", 4096)},version," +
                "${archiveText("base")},${archiveText("local")},${archiveText("state", 16)},${archiveText("kind", 16)},edit_profile FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    require(archiveString(row, 6) == "UPDATE")
                    val baseJson = archiveString(row, 3).also(::requirePreparationSnapshotBudget)
                    val localJson = archiveString(row, 4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    LocalEditIntent(MutationId(archiveString(row, 0)), scope, AdapterFormat(archiveString(row, 1), preparationVersion(row, 2)),
                        base, local.content, LocalEditState.valueOf(archiveString(row, 5)), readLocalEditProfile(row, 7, base, local))
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid local text preparation owner") }

    private fun preparationVersion(cursor: Cursor, column: Int): Int {
        require(cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(column) in 1..Int.MAX_VALUE.toLong())
        return cursor.getInt(column)
    }
    fun readPreparationOwner(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        try {
            stagedQuery(db, "SELECT mutation,adapter,version,base,local,state,kind,edit_profile FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    require(row.getString(6) == "UPDATE")
                    val baseJson = row.getString(3).also(::requirePreparationSnapshotBudget)
                    val localJson = row.getString(4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    LocalEditIntent(MutationId(row.getString(0)), scope, AdapterFormat(row.getString(1), preparationVersion(row, 2)),
                        base, local.content, LocalEditState.valueOf(row.getString(5)), readLocalEditProfile(row, 7, base, local))
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid local preparation owner") }

    fun hasRawEventRow(db: SQLiteDatabase, table: String, scope: CalendarScope, event: EventId): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { it.moveToFirst() }
    fun activeProjectionScope(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT 1 FROM calendars WHERE scope=? AND enabled=1 AND available=1", arrayOf(key(scope)),
    ).use { it.moveToFirst() }
    fun readInspectionDeleteOwner(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalDeleteIntent? =
        try {
            stagedQuery(db, "SELECT mutation,adapter,version,base,local,state,kind FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    require(row.getString(6) == "DELETE")
                    val base = decodeChange(row.getString(3).also(::requirePreparationSnapshotBudget)) as RemoteChange.Upsert
                    val local = decodeChange(row.getString(4).also(::requirePreparationSnapshotBudget)) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    LocalDeleteIntent(MutationId(row.getString(0)), scope, AdapterFormat(row.getString(1), preparationVersion(row, 2)),
                        base, local.content, LocalEditState.valueOf(row.getString(5)))
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid local deletion owner") }

    fun editAcknowledgementOwner(db: SQLiteDatabase, scope: CalendarScope, event: EventId): LocalEditIntent? {
        val update = db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND event=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value)).use { it.moveToFirst() }
        return if (update) readLocalPreparationOwner(db, scope, event) else null
    }

    fun titleAcknowledgementOwner(db: SQLiteDatabase, scope: CalendarScope, event: EventId): LocalEditIntent? {
        val update = db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND event=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value)).use { it.moveToFirst() }
        return if (update) readPreparationOwner(db, scope, event) else null
    }

}

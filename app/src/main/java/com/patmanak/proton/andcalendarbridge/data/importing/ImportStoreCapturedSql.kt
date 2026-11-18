package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasRawEventRow
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasLocalMutation
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateContentCodec
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/** Captured CREATE provenance and handoff SQL; the store retains admission and transaction ownership. */
internal object ImportStoreCapturedSql {
    fun requireCapturedAcknowledgementSource(db: SQLiteDatabase, baseline: CapturedCreateBaseline) {
        val scope = baseline.scope
        val event = baseline.change.eventId
        check(activeProjectionScope(db, scope) && capturedFormatMatches(db, scope, baseline.format) &&
            readCapturedBase(db, scope, event) == baseline) { "Captured acknowledgement requires identical sent reference" }
        check(!hasLocalMutation(db, scope, event) && !hasRawEventRow(db, "projection_receipts", scope, event)) {
            "Captured acknowledgement cannot bypass unresolved local work"
        }
    }

    fun readCapturedAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId):
        CapturedCreateAcknowledgementRecord? = db.rawQuery(
            "SELECT nonce,protocol,fingerprint,state FROM captured_create_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) return@use null
            val baseline = checkNotNull(readCapturedBase(db, scope, event)) { "Captured acknowledgement source is unresolved" }
            val receipt: CapturedCreateAcknowledgementReceipt
            val state: CapturedCreateAcknowledgementState
            try {
                check(row.getString(1) == "1") { "Captured acknowledgement protocol is malformed" }
                receipt = CapturedCreateAcknowledgementReceipt(baseline, row.getString(0), row.getInt(1))
                state = CapturedCreateAcknowledgementState.valueOf(row.getString(3))
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("Captured acknowledgement evidence is malformed")
            }
            check(row.getString(2) == receipt.fingerprint()) { "Captured acknowledgement source or fingerprint changed" }
            CapturedCreateAcknowledgementRecord(receipt, state)
        }

    fun requireCapturedAcknowledgementResolved(db: SQLiteDatabase, scope: CalendarScope, event: EventId):
        CapturedCreateAcknowledgementRecord? {
        val record = readCapturedAcknowledgement(db, scope, event)
        check(record == null || record.state == CapturedCreateAcknowledgementState.PROVEN) {
            "Captured acknowledgement outcome is unresolved"
        }
        return record
    }

    fun requireOrdinaryLocalCapture(db: SQLiteDatabase, scope: CalendarScope, event: EventId) {
        check(!hasRawEventRow(db, "captured_create_bases", scope, event) &&
            !hasRawEventRow(db, "captured_create_acknowledgements", scope, event) &&
            !hasRawEventRow(db, "captured_create_handoffs", scope, event)) {
            "Captured work requires its exact specialized local snapshot"
        }
    }

    fun hasCapturedBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId) =
        hasRawEventRow(db, "captured_create_bases", scope, event)

    fun capturedFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean =
        db.rawQuery("SELECT checkpoint,account,calendar,generation FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            try { it.moveToFirst() && !it.isNull(0) && it.getString(1) == scope.account.value &&
                it.getString(2) == scope.calendar.value && it.getLong(3) == scope.generation &&
                decodeCheckpoint(scope, it.getString(0)).format == format }
            catch (_: IllegalArgumentException) { false }
            catch (_: JSONException) { false }
        }

    fun readCapturedBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateBaseline? {
        if (hasRawEventRow(db, "captured_create_handoffs", scope, event)) return null
        if (!activeProjectionScope(db, scope)) return null
        return db.query("captured_create_bases", arrayOf("account", "calendar", "generation", "attempt", "adapter", "version",
            "payload_fingerprint", "local_id", "capture_id", "capture_adapter", "capture_version", "timestamp",
            "content_fingerprint", "fingerprint_version", "verified_revision", "nonce", "protocol", "payload"),
            "scope=? AND event=?", arrayOf(key(scope), event.value), null, null, null).use { row ->
            if (!row.moveToFirst()) null else {
                val baseline = decodeCapturedBase(row, scope, event) ?: return@use null
                if (!capturedFormatMatches(db, scope, baseline.format)) return@use null
                db.rawQuery("SELECT payload,representation FROM projection_bases WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { base ->
                    if (!base.moveToFirst() || !base.isNull(1) || base.getString(0) != encodeChange(baseline.change)) null else baseline
                }
            }
        }
    }

    fun decodeCapturedBase(row: Cursor, scope: CalendarScope, event: EventId): CapturedCreateBaseline? = try {
        val storedScope = CalendarScope(AccountId(row.getString(0)), CalendarId(row.getString(1)), row.getLong(2))
        if (storedScope != scope) null else {
            val attempt = CreateAttemptIdentity(scope, MutationId(row.getString(3)),
                AdapterFormat(row.getString(4), row.getInt(5)), row.getString(6))
            val link = CapturedCreateDispatchIdentity(attempt, LocalEventId(row.getString(7)), MutationId(row.getString(8)),
                AdapterFormat(row.getString(9), row.getInt(10)), Instant.ofEpochSecond(row.getLong(11)), row.getString(12), row.getInt(13))
            val binding = CapturedCreateBinding(link, event, Revision(row.getString(14)))
            val receipt = CapturedCreateAttachmentReceipt(binding, row.getString(15), row.getInt(16))
            val payload = row.getString(17)
            if (link.fingerprintVersion == 3) {
                val reservation = LocalCreateReservation(link.captureId, link.localId, scope, link.captureFormat, link.timestamp)
                val intent = LocalCreateContentCodec.decodeIntent(payload, reservation)
                val occurrence = requireNotNull(intent.occurrence)
                if (LocalCreateContentCodec.encodeContent(intent) != payload) null else
                    CapturedCreateBaseline(receipt, RemoteChange.Upsert(event, binding.verifiedRevision, occurrence.content), intent)
            } else {
                val change = decodeChange(payload) as? RemoteChange.Upsert
                if (change == null || change.eventId != event || payload != encodeChange(change)) null else CapturedCreateBaseline(receipt, change)
            }
        }
    } catch (_: IllegalArgumentException) { null }
    catch (_: DateTimeException) { null }
    catch (_: JSONException) { null }
    fun requireHandoffNoLocalWork(db: SQLiteDatabase, scope: CalendarScope, event: EventId) {
        check(!hasLocalMutation(db, scope, event) && !hasRawEventRow(db, "projection_receipts", scope, event)) {
            "Handoff cannot bypass unresolved local work"
        }
    }
    fun requireHandoffFrame(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): CapturedCreateHandoffRecord {
        val record = checkNotNull(readCapturedHandoff(db, frame.scope, frame.eventId)) { "Handoff source is absent" }
        check(record.frame == frame) { "Handoff current reference changed" }; return record
    }
    fun readHandoffPending(db: SQLiteDatabase, scope: CalendarScope, event: EventId): ProjectionIntent? =
        db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=? AND pending=1", arrayOf(key(scope), event.value)).use {
            if (!it.moveToFirst()) null else {
                val change = decodeChange(it.getString(0))
                check(change.eventId == event && encodeChange(change) == it.getString(0)) { "Handoff pending evidence is malformed" }
                ProjectionIntent(scope, change)
            }
        }
    fun readHandoffProjectionReceipt(db: SQLiteDatabase, scope: CalendarScope, event: EventId): ProjectionReceipt? {
        val receipt = readProjectionReceipt(db, scope, event) ?: return null
        check(receipt.representation == null && receipt.previousRepresentation == null) { "Represented handoff is unavailable" }
        db.rawQuery("SELECT nonce,payload,adapter,version,previous_base,protocol FROM projection_receipts WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use {
            check(it.moveToFirst() && it.getString(0) == receipt.nonce && it.getString(1) == encodeChange(receipt.change) &&
                it.getString(2) == receipt.format.id && it.getString(3) == receipt.format.version.toString() &&
                !it.isNull(4) && it.getString(4) == receipt.previousBase?.let(::encodeChange) && it.getString(5) == "1") {
                "Handoff prepared proof is malformed"
            }
        }
        return receipt
    }

    fun readCapturedHistoricalBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateBaseline? =
        db.query("captured_create_bases", arrayOf("account", "calendar", "generation", "attempt", "adapter", "version",
            "payload_fingerprint", "local_id", "capture_id", "capture_adapter", "capture_version", "timestamp",
            "content_fingerprint", "fingerprint_version", "verified_revision", "nonce", "protocol", "payload"),
            "scope=? AND event=?", arrayOf(key(scope), event.value), null, null, null).use {
            if (!it.moveToFirst()) null else checkNotNull(decodeCapturedBase(it, scope, event)) { "Handoff origin is malformed" }.also { baseline ->
                val identity = baseline.receipt.binding.identity
                check(it.getString(2) == scope.generation.toString() && it.getString(5) == identity.attempt.format.version.toString() &&
                    it.getString(10) == identity.captureFormat.version.toString() && it.getString(11) == identity.timestamp.epochSecond.toString() &&
                    it.getString(13) == identity.fingerprintVersion.toString() && it.getString(16) == baseline.receipt.protocol.toString()) {
                    "Handoff origin numeric evidence is malformed"
                }
            }
        }

    fun readCapturedHandoff(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateHandoffRecord? =
        db.rawQuery("SELECT nonce,protocol,ack_nonce,ack_fingerprint,fingerprint,state,waiting,last_projection " +
            "FROM captured_create_handoffs WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) return@use null
            check(activeProjectionScope(db, scope)) { "Inactive handoff scope" }
            val origin = checkNotNull(readCapturedHistoricalBase(db, scope, event)) { "Handoff origin is absent" }
            check(capturedFormatMatches(db, scope, origin.format)) { "Incompatible handoff format" }
            val acknowledgement = db.rawQuery("SELECT nonce,protocol,fingerprint,state FROM captured_create_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { ack ->
                check(ack.moveToFirst() && ack.getString(1) == "1" && ack.getString(3) == "PROVEN") { "Handoff acknowledgement is unresolved" }
                CapturedCreateAcknowledgementReceipt(origin, ack.getString(0), ack.getInt(1)).also {
                    check(ack.getString(2) == it.fingerprint()) { "Handoff acknowledgement source changed" }
                }
            }
            check(row.getString(1) == "1" && row.getString(2) == acknowledgement.nonce && row.getString(3) == acknowledgement.fingerprint()) {
                "Handoff source is malformed"
            }
            val receipt = CapturedCreateHandoffReceipt(acknowledgement, row.getString(0), row.getInt(1))
            check(row.getString(4) == receipt.fingerprint() && row.getString(6) in listOf("0", "1")) { "Handoff proof is malformed" }
            val base = db.rawQuery("SELECT payload,representation FROM projection_bases WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use {
                check(it.moveToFirst()) { "Handoff current reference is absent" }
                check(it.isNull(1)) { "Represented handoff reference is unavailable" }
                val change = decodeChange(it.getString(0)) as? RemoteChange.Upsert
                check(change != null && change.eventId == event && encodeChange(change) == it.getString(0)) { "Handoff current reference is malformed" }
                change
            }
            val last = if (row.isNull(7)) null else decodeHandoffProjection(scope, row.getString(7))
            val master = origin.occurrenceIntent?.occurrence?.master?.let { anchor ->
                db.rawQuery("SELECT payload,representation FROM projection_bases WHERE scope=? AND event=?",
                    arrayOf(key(scope), anchor.eventId.value)).use {
                    check(it.moveToFirst()) { "Missing occurrence master baseline" }
                    val current = decodeChange(it.getString(0)) as? RemoteChange.Upsert
                    check(current != null && compatibleOccurrenceMaster(anchor, current) && encodeChange(current) == it.getString(0))
                    val reference = ProjectionRepresentationCodec.decode(current, it.getString(1))
                    check(reference == null || reference.representation == ProjectionRepresentation.DIRECT_V1)
                    current
                }
            }
            CapturedCreateHandoffRecord(CapturedCreateHandoffFrame(receipt, base, last, master),
                CapturedCreateHandoffState.valueOf(row.getString(5)), row.getString(6) == "1")
        }

    fun encodeHandoffProjection(receipt: ProjectionReceipt): String = JSONObject().apply {
        check(receipt.representation == null && receipt.previousRepresentation == null) { "Represented handoff is unavailable" }
        put("adapter", receipt.format.id); put("version", receipt.format.version); put("nonce", receipt.nonce)
        put("protocol", receipt.protocol); put("change", encodeChange(receipt.change))
        put("previous", receipt.previousBase?.let(::encodeChange) ?: JSONObject.NULL)
    }.toString()
    fun decodeHandoffProjection(scope: CalendarScope, payload: String): ProjectionReceipt {
        val json = JSONObject(payload)
        check(json.length() == 6) { "Handoff projection proof is malformed" }
        val receipt = ProjectionReceipt(scope, AdapterFormat(json.getString("adapter"), json.getInt("version")),
            decodeChange(json.getString("change")) as RemoteChange.Upsert, json.getString("nonce"),
            if (json.isNull("previous")) null else decodeChange(json.getString("previous")) as RemoteChange.Upsert, json.getInt("protocol"))
        check(encodeHandoffProjection(receipt) == payload) { "Handoff projection proof is not canonical" }; return receipt
    }

    fun readProjectionReceipt(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ProjectionReceipt? = db.rawQuery(
        "SELECT nonce,payload,adapter,version,previous_base,protocol,representation,previous_representation FROM projection_receipts WHERE scope=? AND event=?",
        arrayOf(key(scope), eventId.value),
    ).use {
        if (!it.moveToFirst()) null else {
            val change = decodeChange(it.getString(1)) as RemoteChange.Upsert
            val previous = if (it.isNull(4)) null else decodeChange(it.getString(4)) as RemoteChange.Upsert
            check(previous != null || it.isNull(7)) { "Stored previous representation has no source" }
            ProjectionReceipt(scope, AdapterFormat(it.getString(2), it.getInt(3)), change, it.getString(0), previous, it.getInt(5),
                ProjectionRepresentationCodec.decode(change, if (it.isNull(6)) null else it.getString(6)),
                previous?.let { source -> ProjectionRepresentationCodec.decode(source, if (it.isNull(7)) null else it.getString(7)) }).snapshot().also { receipt ->
            check(receipt.change.eventId == eventId) { "Projection receipt identity mismatch" }
            }
        }
    }
    fun retainCapturedLocalChange(db: SQLiteDatabase, snapshot: CapturedCreateCurrent, mutation: MutationId,
        conflicts: (SQLiteDatabase) -> ConflictArchiveSql): LocalEditState? = run {
        val baseline = snapshot.baseline
        val link = baseline.receipt.binding.identity
        val scope = baseline.scope
        val event = baseline.change.eventId
        val acknowledgement = requireCapturedAcknowledgementResolved(db, scope, event)
        check(acknowledgement?.receipt == snapshot.acknowledgement) {
            "Captured current acknowledgement source changed"
        }
        require(mutation != link.captureId && mutation != link.attempt.id && mutation.value != link.localId.value) {
            "Local change requires a separate mutation identity"
        }
        check(activeProjectionScope(db, scope) && capturedFormatMatches(db, scope, baseline.format) &&
            readCapturedBase(db, scope, event) == baseline) { "Current local change requires identical captured baseline" }
        check(!hasRawEventRow(db, "projection_receipts", scope, event)) { "Ordinary projection proof is unresolved" }
        if (snapshot.deleted) LocalDeleteIntent(mutation, scope, baseline.format, baseline.change, snapshot.content)
        else if (!snapshot.unchanged) LocalEditIntent(mutation, scope, baseline.format, baseline.change, snapshot.content)
        val kind = if (snapshot.deleted) "DELETE" else "UPDATE"
        val basePayload = encodeChange(baseline.change)
        val localPayload = encodeChange(baseline.change.copy(content = snapshot.content))
        val previous = db.rawQuery("SELECT mutation,adapter,version,base,local,state,kind FROM local_edits WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else {
                check(!snapshot.unchanged && row.getString(0) == mutation.value && row.getString(1) == baseline.format.id &&
                    row.getInt(2) == baseline.format.version && row.getString(3) == basePayload &&
                    row.getString(4) == localPayload && row.getString(6) == kind) { "Captured local intention is immutable" }
                LocalEditState.valueOf(row.getString(5))
            }
        }
        if (previous == null) {
            check(!db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND mutation=?", arrayOf(key(scope), mutation.value))
                .use { it.moveToFirst() }) { "Local mutation identity already belongs to other work" }
            if (snapshot.unchanged) return@run null
        }
        val hasDeferred = hasRawEventRow(db, "deferred_events", scope, event)
        val exactRemote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row -> row.moveToFirst() && row.getString(0) == basePayload }
        val state = if (previous == LocalEditState.CONFLICT || hasDeferred || !exactRemote) LocalEditState.CONFLICT else LocalEditState.PAUSED
        if (previous != null) {
            if (previous != state) check(db.update("local_edits", ContentValues().apply { put("state", state.name) },
                "scope=? AND event=? AND mutation=? AND state=?", arrayOf(key(scope), event.value, mutation.value,
                    previous.name)) == 1) { "Captured local conflict state was not retained" }
        } else {
            db.insertOrThrow("local_edits", null, ContentValues().apply {
                put("scope", key(scope)); put("mutation", mutation.value); put("event", event.value)
                put("adapter", baseline.format.id); put("version", baseline.format.version)
                put("base", basePayload); put("local", localPayload); put("state", state.name); put("kind", kind)
            })
        }
        conflicts(db).ensure(scope, event)
        state
    }

    fun retainCapturedHandoffLocalChange(db: SQLiteDatabase, snapshot: CapturedHandoffCurrent, mutation: MutationId,
        conflicts: (SQLiteDatabase) -> ConflictArchiveSql, series: Boolean = false): LocalEditState? = run {
        val frame = snapshot.frame; val scope = frame.scope; val event = frame.eventId
        check(requireHandoffFrame(db, frame).state == CapturedCreateHandoffState.RELEASED) { "Handoff is pending" }
        check(!hasRawEventRow(db, "projection_receipts", scope, event)) { "Handoff projection proof is unresolved" }
        val link = frame.receipt.acknowledgement.baseline.receipt.binding.identity
        require(mutation != link.captureId && mutation != link.attempt.id && mutation.value != link.localId.value) {
            "Local change requires a separate mutation identity"
        }
        if (series) {
            check(activeProjectionScope(db, scope)) { "Inactive series capture scope" }
            require(link.fingerprintVersion == 2 && !snapshot.deleted && !snapshot.unchanged)
            SeriesEditCandidate(LocalEditIntent(mutation, scope, frame.format, frame.base, snapshot.content,
                seriesProfile = SeriesEditProfile.of(frame.base.content, snapshot.content)))
        } else if (snapshot.deleted) LocalDeleteIntent(mutation, scope, frame.format, frame.base, snapshot.content)
        else if (!snapshot.unchanged) LocalEditIntent(mutation, scope, frame.format, frame.base, snapshot.content)
        val kind = if (snapshot.deleted) "DELETE" else "UPDATE"
        val base = encodeChange(frame.base); val local = encodeChange(frame.base.copy(content = snapshot.content))
        val previous = db.rawQuery("SELECT mutation,adapter,version,base,local,state,kind,edit_profile FROM local_edits WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else {
                check(!snapshot.unchanged && row.getString(0) == mutation.value && row.getString(1) == frame.format.id &&
                    row.getString(2) == frame.format.version.toString() && row.getString(3) == base && row.getString(4) == local &&
                    row.getString(6) == kind && row.getInt(7) == (if (series) 2 else 1)) { "Handoff local intention is immutable" }
                LocalEditState.valueOf(row.getString(5))
            }
        }
        if (previous == null) {
            check(!db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND mutation=?", arrayOf(key(scope), mutation.value)).use { it.moveToFirst() }) {
                "Local mutation identity already belongs to other work"
            }
            if (snapshot.unchanged) return@run null
        }
        val exactRemote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?", arrayOf(key(scope), event.value))
            .use { it.moveToFirst() && it.getString(0) == base }
        val state = if (previous == LocalEditState.CONFLICT || !exactRemote || hasRawEventRow(db, "deferred_events", scope, event))
            LocalEditState.CONFLICT else LocalEditState.PAUSED
        if (previous != null) {
            if (state != previous) check(db.update("local_edits", ContentValues().apply { put("state", state.name) },
                "scope=? AND event=? AND mutation=? AND state=?", arrayOf(key(scope), event.value, mutation.value, previous.name)) == 1)
        } else db.insertOrThrow("local_edits", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("mutation", mutation.value)
            put("adapter", frame.format.id); put("version", frame.format.version); put("base", base)
            put("local", local); put("state", state.name); put("kind", kind); put("edit_profile", if (series) 2 else 1)
        })
        check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 1) },
            "scope=? AND event=? AND nonce=?", arrayOf(key(scope), event.value, frame.receipt.nonce)) == 1)
        conflicts(db).ensure(scope, event)
        state
    }

    fun reserveCapturedHandoff(db: SQLiteDatabase, receipt: CapturedCreateHandoffReceipt): CapturedCreateHandoffRecord = run {
        val existing = readCapturedHandoff(db, receipt.scope, receipt.eventId)
        if (existing != null) {
            check(existing.frame.receipt.acknowledgement == receipt.acknowledgement) { "Handoff source is immutable" }
            if (existing.state == CapturedCreateHandoffState.PENDING) requireHandoffNoLocalWork(db, receipt.scope, receipt.eventId)
            return@run existing
        }
        requireCapturedAcknowledgementSource(db, receipt.acknowledgement.baseline)
        check(readCapturedAcknowledgement(db, receipt.scope, receipt.eventId) ==
            CapturedCreateAcknowledgementRecord(receipt.acknowledgement, CapturedCreateAcknowledgementState.PROVEN)) {
            "Handoff requires exact proven acknowledgement"
        }
        db.insertOrThrow("captured_create_handoffs", null, ContentValues().apply {
            put("scope", key(receipt.scope)); put("event", receipt.eventId.value); put("nonce", receipt.nonce)
            put("protocol", receipt.protocol); put("ack_nonce", receipt.acknowledgement.nonce)
            put("ack_fingerprint", receipt.acknowledgement.fingerprint()); put("fingerprint", receipt.fingerprint())
            put("state", CapturedCreateHandoffState.PENDING.name); put("waiting", 0); putNull("last_projection")
        })
        checkNotNull(readCapturedHandoff(db, receipt.scope, receipt.eventId))
    }

    fun retainCapturedHandoff(db: SQLiteDatabase, receipt: CapturedCreateHandoffReceipt,
        witness: CapturedCreateCleanWitness): CapturedCreateHandoffRecord = run {
        check(witness.receipt == receipt.acknowledgement) { "Handoff clean source differs" }
        val existing = requireHandoffFrame(db, CapturedCreateHandoffFrame(receipt, receipt.acknowledgement.baseline.change))
        requireHandoffNoLocalWork(db, receipt.scope, receipt.eventId)
        if (existing.state == CapturedCreateHandoffState.PENDING) {
            check(db.update("captured_create_handoffs", ContentValues().apply { put("state", "RELEASED") },
                "scope=? AND event=? AND nonce=? AND state='PENDING'", arrayOf(key(receipt.scope), receipt.eventId.value, receipt.nonce)) == 1)
        }
        checkNotNull(readCapturedHandoff(db, receipt.scope, receipt.eventId))
    }

    fun prepareCapturedHandoffProjection(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame,
        intent: ProjectionIntent): CapturedHandoffProjection = run {
        val record = requireHandoffFrame(db, frame)
        check(record.state == CapturedCreateHandoffState.RELEASED && !record.waiting) { "Handoff cannot project" }
        check(intent.scope == frame.scope && intent.change.eventId == frame.eventId && supportsCapturedHandoffContent(frame.receipt.acknowledgement.baseline, intent.change)) {
            "Unsupported handoff projection"
        }
        check(!hasLocalMutation(db, frame.scope, frame.eventId) && !hasRawEventRow(db, "deferred_events", frame.scope, frame.eventId) &&
            readHandoffPending(db, frame.scope, frame.eventId) == intent) { "Stale or blocked handoff projection" }
        val existing = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        if (existing != null) {
            val plan = CapturedHandoffProjection(frame, existing)
            check(plan.receipt.change == intent.change) { "Unresolved handoff projection" }; return@run plan
        }
        val receipt = ProjectionReceipt(frame.scope, frame.format, intent.change as RemoteChange.Upsert,
            UUID.randomUUID().toString(), frame.base)
        val plan = CapturedHandoffProjection(frame, receipt)
        db.insertOrThrow("projection_receipts", null, ContentValues().apply {
            put("scope", key(frame.scope)); put("event", frame.eventId.value); put("nonce", receipt.nonce)
            put("payload", encodeChange(receipt.change)); put("adapter", frame.format.id); put("version", frame.format.version)
            put("previous_base", encodeChange(frame.base)); put("protocol", receipt.protocol)
        })
        plan
    }

    fun capturedHandoffCanProject(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): Boolean = run {
        val record = requireHandoffFrame(db, frame)
        val outstanding = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        outstanding?.let { CapturedHandoffProjection(frame, it) }
        val pending = readHandoffPending(db, frame.scope, frame.eventId)
        record.state == CapturedCreateHandoffState.RELEASED && !record.waiting &&
            !hasLocalMutation(db, frame.scope, frame.eventId) && !hasRawEventRow(db, "deferred_events", frame.scope, frame.eventId) &&
            (pending != null || outstanding == null) && pending?.change?.let { supportsCapturedHandoffContent(frame.receipt.acknowledgement.baseline, it) } != false
    }

    fun waitCapturedHandoff(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): Boolean = run {
        val record = requireHandoffFrame(db, frame)
        if (record.state != CapturedCreateHandoffState.RELEASED) return@run false
        if (!record.waiting) check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 1) },
            "scope=? AND event=? AND nonce=? AND waiting=0", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
        true
    }

    fun resumeCapturedHandoff(db: SQLiteDatabase, witness: CapturedHandoffCleanWitness): Boolean = run {
        val frame = witness.frame; val record = requireHandoffFrame(db, frame)
        if (record.state != CapturedCreateHandoffState.RELEASED) return@run false
        requireHandoffNoLocalWork(db, frame.scope, frame.eventId)
        if (record.waiting) check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 0) },
            "scope=? AND event=? AND nonce=? AND waiting=1", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
        true
    }

}

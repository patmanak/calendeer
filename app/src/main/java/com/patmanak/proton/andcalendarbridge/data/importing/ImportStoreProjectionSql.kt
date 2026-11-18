package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireCapturedAcknowledgementSource
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireOrdinaryLocalCapture
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.hasCapturedBase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.capturedFormatMatches
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedBase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireHandoffFrame
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readHandoffPending
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readHandoffProjectionReceipt
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedHandoff
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.encodeHandoffProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readProjectionReceipt
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requireKnown
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasLocalMutation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasRawEventRow
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateContentCodec

/** Projection SQL only; ImportStore retains IO, mutex, cancellation and transaction ownership. */
internal class ImportStoreProjectionSql(
    private val localMutations: ImportStoreLocalMutationSql,
    private val admitTransaction: (CalendarScope) -> Boolean,
) {
    fun stabilizedProjection(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }
        requireKnown(db, scope)
        db.rawQuery("SELECT r.payload FROM records r WHERE r.scope=? AND r.event=? AND r.pending=0 AND NOT EXISTS " +
            "(SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event) AND NOT EXISTS " +
            "(SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)",
            arrayOf(key(scope), eventId.value)).use { cursor ->
            val snapshot = if (cursor.moveToFirst()) decodeChange(cursor.getString(0)) as? RemoteChange.Upsert else null
            check(admitTransaction(scope)) { "Inactive import lease" }
            snapshot
        }
    }

    fun projectedBaseline(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val baseline = localMutations.readBaseline(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        baseline
    }

    fun projectedReference(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ProjectedEventReference? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val base = localMutations.readProjectionBase(db, scope, eventId)
        val reference = base?.let { it.representation ?: ProjectedEventReference.direct(it.change) }
        check(admitTransaction(scope)) { "Inactive import lease" }
        reference
    }

    fun requireDirectProjection(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): Unit = run {
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransactionNonExclusive()
        try {
            requireKnown(db, scope)
            check(activeProjectionScope(db, scope)) { "Inactive projection scope" }
            val base = localMutations.readProjectionBase(db, scope, eventId)
            val receipt = readProjectionReceipt(db, scope, eventId)
            check(listOf(base?.representation, receipt?.representation, receipt?.previousRepresentation).all {
                it == null || it.representation == ProjectionRepresentation.DIRECT_V1
            }) { "Represented projection requires dedicated admission" }
            check(admitTransaction(scope)) { "Inactive import lease" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun occurrenceFamilyCurrent(db: SQLiteDatabase, baseline: CapturedCreateBaseline): Boolean {
        val master = baseline.occurrenceIntent?.occurrence?.master ?: return true
        val scope = baseline.scope
        val child = baseline.change
        if (stabilizedProjection(db, scope, master.eventId) != master || localMutations.readBaseline(db, scope, master.eventId) != master ||
            hasRawEventRow(db, "projection_receipts", scope, master.eventId) || hasLocalMutation(db, scope, child.eventId) ||
            hasRawEventRow(db, "projection_receipts", scope, child.eventId) || hasRawEventRow(db, "deferred_events", scope, child.eventId)) return false
        return db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?", arrayOf(key(scope), child.eventId.value)).use {
            !it.moveToFirst() || decodeChange(it.getString(0)) == child
        }
    }

    fun retainCapturedBaseline(db: SQLiteDatabase, baseline: CapturedCreateBaseline): Boolean = run {

        val event = baseline.change.eventId
        if (hasRawEventRow(db, "captured_create_handoffs", baseline.scope, event)) return@run false
        if (!activeProjectionScope(db, baseline.scope) || !occurrenceFamilyCurrent(db, baseline) ||
            !capturedFormatMatches(db, baseline.scope, baseline.format)) return@run false
        if (hasCapturedBase(db, baseline.scope, event)) {
            if (readCapturedBase(db, baseline.scope, event) != baseline) return@run false
        } else {
            if (hasRawEventRow(db, "projection_bases", baseline.scope, event) ||
                hasLocalMutation(db, baseline.scope, event) ||
                hasRawEventRow(db, "projection_receipts", baseline.scope, event)) return@run false
            val receipt = baseline.receipt
            val link = receipt.binding.identity
            if (db.rawQuery("SELECT 1 FROM captured_create_bases WHERE " +
                "(scope=? AND (attempt=? OR local_id=? OR capture_id=?)) OR nonce=? LIMIT 1",
                arrayOf(key(baseline.scope), link.attempt.id.value, link.localId.value, link.captureId.value,
                    receipt.nonce)).use { it.moveToFirst() }) return@run false
            db.insertOrThrow("captured_create_bases", null, ContentValues().apply {
                put("scope", key(baseline.scope)); put("event", event.value)
                put("account", baseline.scope.account.value); put("calendar", baseline.scope.calendar.value)
                put("generation", baseline.scope.generation); put("attempt", link.attempt.id.value)
                put("adapter", link.attempt.format.id); put("version", link.attempt.format.version)
                put("payload_fingerprint", link.attempt.payloadFingerprint); put("local_id", link.localId.value)
                put("capture_id", link.captureId.value); put("capture_adapter", link.captureFormat.id)
                put("capture_version", link.captureFormat.version); put("timestamp", link.timestamp.epochSecond)
                put("content_fingerprint", link.contentFingerprint); put("fingerprint_version", link.fingerprintVersion)
                put("verified_revision", receipt.binding.verifiedRevision.value); put("nonce", receipt.nonce)
                put("protocol", receipt.protocol)
                put("payload", baseline.occurrenceIntent?.let(LocalCreateContentCodec::encodeContent) ?: encodeChange(baseline.change))
            })
            db.insertOrThrow("projection_bases", null, ContentValues().apply {
                put("scope", key(baseline.scope)); put("event", event.value); put("payload", encodeChange(baseline.change))
            })
        }
        // Full serialized content is compared; an opaque revision alone cannot consume work.
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1 AND NOT EXISTS " +
                "(SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event) AND NOT EXISTS " +
                "(SELECT 1 FROM deferred_events d WHERE d.scope=records.scope AND d.event=records.event) AND NOT EXISTS " +
                "(SELECT 1 FROM projection_receipts p WHERE p.scope=records.scope AND p.event=records.event)",
            arrayOf(key(baseline.scope), event.value, encodeChange(baseline.change)))
        true
    }

    fun reserveCapturedAcknowledgement(db: SQLiteDatabase, proposed: CapturedCreateAcknowledgementReceipt): CapturedCreateAcknowledgementReservation = run {
        check(occurrenceFamilyCurrent(db, proposed.baseline)) { "Occurrence family changed before ACK reservation" }
        requireCapturedAcknowledgementSource(db, proposed.baseline)
        val existing = readCapturedAcknowledgement(db, proposed.baseline.scope, proposed.baseline.change.eventId)
        if (existing != null) {
            check(existing.receipt.baseline == proposed.baseline) { "Captured acknowledgement source is immutable" }
            return@run CapturedCreateAcknowledgementReservation(existing, fresh = false)
        }
        db.insertOrThrow("captured_create_acknowledgements", null, ContentValues().apply {
            put("scope", key(proposed.baseline.scope)); put("event", proposed.baseline.change.eventId.value)
            put("nonce", proposed.nonce); put("protocol", proposed.protocol)
            put("fingerprint", proposed.fingerprint()); put("state", CapturedCreateAcknowledgementState.UNKNOWN.name)
        })
        CapturedCreateAcknowledgementReservation(CapturedCreateAcknowledgementRecord(proposed,
            CapturedCreateAcknowledgementState.UNKNOWN), fresh = true)
    }

    fun retainCapturedAcknowledgement(db: SQLiteDatabase, witness: AppliedCapturedCreateAcknowledgement): CapturedCreateAcknowledgementRecord = run {
        val receipt = witness.receipt
        check(occurrenceFamilyCurrent(db, receipt.baseline)) { "Occurrence family changed before ACK proof" }
        requireCapturedAcknowledgementSource(db, receipt.baseline)
        val existing = checkNotNull(readCapturedAcknowledgement(db, receipt.baseline.scope, receipt.baseline.change.eventId)) {
            "Captured acknowledgement requires its durable reservation"
        }
        check(existing.receipt == receipt) { "Captured acknowledgement proof is immutable" }
        if (existing.state == CapturedCreateAcknowledgementState.UNKNOWN) {
            check(db.update("captured_create_acknowledgements", ContentValues().apply {
                put("state", CapturedCreateAcknowledgementState.PROVEN.name)
            }, "scope=? AND event=? AND nonce=? AND protocol=? AND fingerprint=? AND state=?",
                arrayOf(key(receipt.baseline.scope), receipt.baseline.change.eventId.value, receipt.nonce,
                    receipt.protocol.toString(), receipt.fingerprint(), CapturedCreateAcknowledgementState.UNKNOWN.name)) == 1) {
                "Captured acknowledgement proof was not retained"
            }
        }
        existing.copy(state = CapturedCreateAcknowledgementState.PROVEN)
    }

    fun capturedProvenanceEvents(db: SQLiteDatabase, scope: CalendarScope): Set<EventId> = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val events = db.rawQuery("SELECT event FROM captured_create_bases WHERE scope=? UNION " +
            "SELECT event FROM captured_create_acknowledgements WHERE scope=? UNION " +
            "SELECT event FROM captured_create_handoffs WHERE scope=?", arrayOf(key(scope), key(scope), key(scope))).use {
            buildSet { while (it.moveToNext()) add(EventId(it.getString(0))) }
        }
        check(admitTransaction(scope)) { "Inactive import lease" }; events
    }

    fun restoreCapturedHandoffProjection(db: SQLiteDatabase, witness: AppliedCapturedHandoffProjection): Boolean = run {
        val plan = witness.plan; val frame = plan.frame; val receipt = plan.receipt
        val record = readCapturedHandoff(db, frame.scope, frame.eventId) ?: return@run false
        if (record.state != CapturedCreateHandoffState.RELEASED || record.frame.receipt != frame.receipt ||
            hasLocalMutation(db, frame.scope, frame.eventId)) return@run false
        val outstanding = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        if (record.frame == CapturedCreateHandoffFrame(frame.receipt, receipt.change, receipt, frame.occurrenceMaster)) {
            return@run outstanding == null
        }
        if (record.frame != frame || outstanding != receipt) return@run false
        check(db.update("projection_bases", ContentValues().apply { put("payload", encodeChange(receipt.change)); putNull("representation") },
            "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(frame.scope), frame.eventId.value, encodeChange(frame.base))) == 1)
        check(db.update("captured_create_handoffs", ContentValues().apply { put("last_projection", encodeHandoffProjection(receipt)) },
            "scope=? AND event=? AND nonce=?", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
        db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=? AND payload=? AND pending=1",
            arrayOf(key(frame.scope), frame.eventId.value, encodeChange(receipt.change)))
        check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=? AND representation IS NULL AND previous_representation IS NULL",
            arrayOf(key(frame.scope), frame.eventId.value, receipt.nonce, encodeChange(receipt.change), receipt.format.id,
                receipt.format.version.toString(), encodeChange(frame.base), receipt.protocol.toString())) == 1)
        true
    }

    fun prepareProjection(db: SQLiteDatabase, candidate: ProjectionReceipt): ProjectionReceipt = run {

        requireOrdinaryLocalCapture(db, candidate.scope, candidate.change.eventId)
        check(activeProjectionScope(db, candidate.scope)) { "Inactive projection scope" }
        require(projectionFormatMatches(db, candidate.scope, candidate.format)) { "Incompatible projection format" }
        val change = candidate.change
        val pending = db.rawQuery("SELECT 1 FROM records r WHERE r.scope=? AND r.event=? AND r.payload=? AND r.pending=1 " +
            "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event) " +
            "AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event)",
            arrayOf(key(candidate.scope), change.eventId.value, encodeChange(change))).use { it.moveToFirst() }
        require(pending) { "Stale or blocked projection intent" }
        val existing = readProjectionReceipt(db, candidate.scope, change.eventId)
        if (existing != null) {
            require(existing.change == change && existing.format == candidate.format && existing.representation == candidate.representation) { "Unresolved projection receipt" }
            return@run existing
        }
        val previous = localMutations.readProjectionBase(db, candidate.scope, change.eventId)
        val receipt = candidate.copy(previousBase = previous?.change, previousRepresentation = previous?.representation).snapshot()
        db.insertOrThrow("projection_receipts", null, ContentValues().apply {
            put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("nonce", receipt.nonce)
            put("payload", encodeChange(receipt.change)); put("adapter", receipt.format.id); put("version", receipt.format.version)
            receipt.previousBase?.let { put("previous_base", encodeChange(it)) } ?: putNull("previous_base")
            put("protocol", receipt.protocol)
            put("representation", ProjectionRepresentationCodec.encode(receipt.representation))
            put("previous_representation", ProjectionRepresentationCodec.encode(receipt.previousRepresentation))
        })
        receipt
    }

    fun restoreProjection(db: SQLiteDatabase, receipt: ProjectionReceipt): Boolean = run {
        requireOrdinaryLocalCapture(db, receipt.scope, receipt.change.eventId)
        if (readProjectionReceipt(db, receipt.scope, receipt.change.eventId) != receipt) return@run false
        if (!activeProjectionScope(db, receipt.scope) || !projectionFormatMatches(db, receipt.scope, receipt.format) ||
            hasLocalMutation(db, receipt.scope, receipt.change.eventId)) return@run false
        val baseline = localMutations.readProjectionBase(db, receipt.scope, receipt.change.eventId)
        val previousMatches = baseline?.change == receipt.previousBase && baseline?.representation == receipt.previousRepresentation
        val appliedMatches = baseline?.change == receipt.change && baseline?.representation == receipt.representation
        if (!previousMatches && !appliedMatches) return@run false
        db.insertWithOnConflict("projection_bases", null, ContentValues().apply {
            put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("payload", encodeChange(receipt.change))
            put("representation", ProjectionRepresentationCodec.encode(receipt.representation))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending IN (1,2)",
            arrayOf(key(receipt.scope), receipt.change.eventId.value, encodeChange(receipt.change)))
        check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=? AND representation IS ? AND previous_representation IS ?",
            arrayOf(key(receipt.scope), receipt.change.eventId.value, receipt.nonce, encodeChange(receipt.change),
                receipt.format.id, receipt.format.version.toString(), receipt.previousBase?.let(::encodeChange), receipt.protocol.toString(),
                ProjectionRepresentationCodec.encode(receipt.representation), ProjectionRepresentationCodec.encode(receipt.previousRepresentation))) == 1)
        true
    }

    fun capturedBaseline(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateBaseline? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val baseline = readCapturedBase(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }
        baseline
    }

    fun capturedAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateAcknowledgementRecord? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val record = readCapturedAcknowledgement(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }
        record
    }

    fun capturedCleanSource(db: SQLiteDatabase, receipt: CapturedCreateAcknowledgementReceipt): Boolean = run {
        requireCapturedAcknowledgementSource(db, receipt.baseline)
        val record = readCapturedAcknowledgement(db, receipt.baseline.scope, receipt.baseline.change.eventId)
        record != null && record.receipt == receipt && record.state == CapturedCreateAcknowledgementState.PROVEN
    }

    fun capturedHandoff(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateHandoffRecord? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val record = readCapturedHandoff(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }; record
    }

    fun capturedHandoffPending(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): ProjectionIntent? = run {
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; requireKnown(db, frame.scope)
        requireHandoffFrame(db, frame)
        val pending = readHandoffPending(db, frame.scope, frame.eventId)
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; pending
    }

    fun capturedHandoffProjection(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): CapturedHandoffProjection? = run {
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; requireKnown(db, frame.scope)
        check(requireHandoffFrame(db, frame).state == CapturedCreateHandoffState.RELEASED) { "Handoff is pending" }
        val receipt = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        val plan = receipt?.let { CapturedHandoffProjection(frame, it) }
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; plan
    }

    fun projectionReceipt(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ProjectionReceipt? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val receipt = readProjectionReceipt(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        receipt
    }

    fun localEdit(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val edit = localMutations.readLocalEdit(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        edit
    }

    fun localDelete(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalDeleteIntent? = run {
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val deletion = localMutations.readLocalDelete(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        deletion
    }

    private fun projectionFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean = db.rawQuery(
        "SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope)),
    ).use { it.moveToFirst() && (it.isNull(0) || decodeCheckpoint(scope, it.getString(0)).format == format) }
}

package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.encodeHandoffProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.checkStagingCapacity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.titleAcknowledgementOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.editAcknowledgementOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTextPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTextEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTimePreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTimeEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.samePersonalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPersonalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTextUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTextAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTimeUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTimeAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readPersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readPersonalUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedPersonalAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodePersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTitleAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.archiveTitleUpdateAcknowledgement
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.sync.*

/** UPDATE acknowledgement SQL only; snapshots, cancellation, admission and transactions stay in the store. */
internal class ImportStoreUpdateSql(
    private val localMutations: ImportStoreLocalMutationSql,
    private val localTextPreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTextPreparationAdmission,
    private val localTimePreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTimePreparationAdmission,
    private val personalPreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> PersonalPreparationAdmission,
    private val localTitlePreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTitlePreparationAdmission,
) {
    fun prepareTextUpdateAcknowledgement(db: SQLiteDatabase, frozen: TextUpdateVerifiedResult,
        proposed: TextUpdateAcknowledgementReceipt, encoded: String): TextUpdateAcknowledgementReservation? = run {
        val scope = proposed.intent.scope
        val event = proposed.intent.base.eventId
        val historical = readTextUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
        val existing = if (historical == null) readTextUpdateAcknowledgement(db, scope, event) else null
        val exact = if (historical != null) readTextUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
            else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
        if (exact != null) {
            check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                "Text acknowledgement receipt is immutable"
            }
            return@run TextUpdateAcknowledgementReservation(exact, fresh = false)
        }
        check(existing == null || existing.state == TextUpdateAcknowledgementState.RESTORED) {
            "Pending text acknowledgement receipt is immutable"
        }
        requireUnusedTextAcknowledgementIdentity(db, proposed)
        if (!localMutations.textAcknowledgementSource(db, proposed) ||
            !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run null
        val owner = editAcknowledgementOwner(db, scope, event)
        val historicalAdmission = owner == proposed.intent &&
            localTextPreparationAdmission(db, proposed.intent) == LocalTextPreparationAdmission.HISTORICAL
        if (!historicalAdmission && !localMutations.exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
            return@run null
        val preparation = readLocalTextEditPreparation(db, scope, event) ?: return@run null
        if (!sameTextPreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
            return@run null
        if (existing != null) {
            archiveTextUpdateAcknowledgement(db, existing)
            check(db.delete("text_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                    encodeTextUpdateAcknowledgement(existing.receipt))) == 1) { "Text acknowledgement slot changed" }
        }
        check(!db.rawQuery("SELECT 1 FROM text_update_acknowledgement_history WHERE nonce=?",
            arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Text acknowledgement nonce is already retained" }
        db.insertOrThrow("text_update_acknowledgements", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
            put("receipt", encoded); put("state", TextUpdateAcknowledgementState.PENDING.name)
        })
        checkStagingCapacity(db)
        TextUpdateAcknowledgementReservation(TextUpdateAcknowledgementRecord(proposed,
            TextUpdateAcknowledgementState.PENDING), fresh = true)
    }

    fun restoreTextUpdateAcknowledgement(db: SQLiteDatabase, receipt: TextUpdateAcknowledgementReceipt,
        intent: LocalEditIntent, scope: CalendarScope, event: EventId): Boolean = run {
        val retained = readTextUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@run false
        if (retained.receipt != receipt) return@run false
        if (retained.state == TextUpdateAcknowledgementState.RESTORED) return@run true
        if (!activeProjectionScope(db, scope) || !localMutations.acknowledgementFormatMatches(db, scope, intent.format) ||
            !localMutations.textAcknowledgementSource(db, receipt) || !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run false
        val owner = editAcknowledgementOwner(db, scope, event) ?: return@run false
        if (owner.copy(state = LocalEditState.PAUSED) != intent) return@run false
        val preparation = readLocalTextEditPreparation(db, scope, event) ?: return@run false
        if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@run false
        if (localMutations.readBaseline(db, scope, event) != intent.base) return@run false
        // The native ACK is now proven. Retire only a still-redundant ordinary
        // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
        if (receipt.handoff == null) localMutations.retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
        val oldBase = encodeChange(intent.base)
        val newBase = encodeChange(receipt.projection.change)
        val local = encodeChange(intent.base.copy(content = intent.content))
        check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
            "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
        receipt.handoff?.let { frame ->
            check(db.update("captured_create_handoffs", ContentValues().apply {
                put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
            }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                    frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                    frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                "Transferred text acknowledgement source changed"
            }
        }
        check(db.delete("local_text_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                preparation.format.version.toString(), preparation.protectedPayload)) == 1)
        check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                oldBase, local, owner.state.name)) == 1)
        // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
        db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
            "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
        localMutations.requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
        check(db.update("text_update_acknowledgements", ContentValues().apply {
            put("state", TextUpdateAcknowledgementState.RESTORED.name)
        }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
            arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTextUpdateAcknowledgement(receipt),
                TextUpdateAcknowledgementState.PENDING.name)) == 1)
        archiveTextUpdateAcknowledgement(db, TextUpdateAcknowledgementRecord(receipt,
            TextUpdateAcknowledgementState.RESTORED))
        true
    }

    fun prepareTimeUpdateAcknowledgement(db: SQLiteDatabase, frozen: TimeUpdateVerifiedResult,
        proposed: TimeUpdateAcknowledgementReceipt, encoded: String): TimeUpdateAcknowledgementReservation? = run {
        val scope = proposed.intent.scope
        val event = proposed.intent.base.eventId
        val historical = readTimeUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
        val existing = if (historical == null) readTimeUpdateAcknowledgement(db, scope, event) else null
        val exact = if (historical != null) readTimeUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
            else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
        if (exact != null) {
            check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                "Time acknowledgement receipt is immutable"
            }
            return@run TimeUpdateAcknowledgementReservation(exact, fresh = false)
        }
        check(existing == null || existing.state == TimeUpdateAcknowledgementState.RESTORED) {
            "Pending text acknowledgement receipt is immutable"
        }
        requireUnusedTimeAcknowledgementIdentity(db, proposed)
        if (!localMutations.timeAcknowledgementSource(db, proposed) ||
            !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run null
        val owner = editAcknowledgementOwner(db, scope, event)
        val historicalAdmission = owner == proposed.intent &&
            localTimePreparationAdmission(db, proposed.intent) == LocalTimePreparationAdmission.HISTORICAL
        if (!historicalAdmission && !localMutations.exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
            return@run null
        val preparation = readLocalTimeEditPreparation(db, scope, event) ?: return@run null
        if (!sameTimePreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
            return@run null
        if (existing != null) {
            archiveTimeUpdateAcknowledgement(db, existing)
            check(db.delete("time_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                    encodeTimeUpdateAcknowledgement(existing.receipt))) == 1) { "Time acknowledgement slot changed" }
        }
        check(!db.rawQuery("SELECT 1 FROM time_update_acknowledgement_history WHERE nonce=?",
            arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Time acknowledgement nonce is already retained" }
        db.insertOrThrow("time_update_acknowledgements", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
            put("receipt", encoded); put("state", TimeUpdateAcknowledgementState.PENDING.name)
        })
        checkStagingCapacity(db)
        TimeUpdateAcknowledgementReservation(TimeUpdateAcknowledgementRecord(proposed,
            TimeUpdateAcknowledgementState.PENDING), fresh = true)
    }

    fun restoreTimeUpdateAcknowledgement(db: SQLiteDatabase, receipt: TimeUpdateAcknowledgementReceipt,
        intent: LocalEditIntent, scope: CalendarScope, event: EventId): Boolean = run {
        val retained = readTimeUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@run false
        if (retained.receipt != receipt) return@run false
        if (retained.state == TimeUpdateAcknowledgementState.RESTORED) return@run true
        if (!activeProjectionScope(db, scope) || !localMutations.acknowledgementFormatMatches(db, scope, intent.format) ||
            !localMutations.timeAcknowledgementSource(db, receipt) || !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run false
        val owner = editAcknowledgementOwner(db, scope, event) ?: return@run false
        if (owner.copy(state = LocalEditState.PAUSED) != intent) return@run false
        val preparation = readLocalTimeEditPreparation(db, scope, event) ?: return@run false
        if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@run false
        if (localMutations.readBaseline(db, scope, event) != intent.base) return@run false
        // The native ACK is now proven. Retire only a still-redundant ordinary
        // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
        if (receipt.handoff == null) localMutations.retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
        val oldBase = encodeChange(intent.base)
        val newBase = encodeChange(receipt.projection.change)
        val local = encodeChange(intent.base.copy(content = intent.content))
        check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
            "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
        receipt.handoff?.let { frame ->
            check(db.update("captured_create_handoffs", ContentValues().apply {
                put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
            }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                    frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                    frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                "Transferred time acknowledgement source changed"
            }
        }
        check(db.delete("local_time_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                preparation.format.version.toString(), preparation.protectedPayload)) == 1)
        check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                oldBase, local, owner.state.name)) == 1)
        // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
        db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
            "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
        localMutations.requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
        check(db.update("time_update_acknowledgements", ContentValues().apply {
            put("state", TimeUpdateAcknowledgementState.RESTORED.name)
        }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
            arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTimeUpdateAcknowledgement(receipt),
                TimeUpdateAcknowledgementState.PENDING.name)) == 1)
        archiveTimeUpdateAcknowledgement(db, TimeUpdateAcknowledgementRecord(receipt,
            TimeUpdateAcknowledgementState.RESTORED))
        true
    }

    fun preparePersonalUpdateAcknowledgement(db: SQLiteDatabase, frozen: PersonalUpdateVerifiedResult,
        proposed: PersonalUpdateAcknowledgementReceipt, encoded: String): PersonalUpdateAcknowledgementReservation? = run {
        val scope = proposed.intent.scope
        val event = proposed.intent.base.eventId
        val historical = readPersonalUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
        val existing = if (historical == null) readPersonalUpdateAcknowledgement(db, scope, event) else null
        val exact = if (historical != null) readPersonalUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
            else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
        if (exact != null) {
            check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                "Personal acknowledgement receipt is immutable"
            }
            return@run PersonalUpdateAcknowledgementReservation(exact, fresh = false)
        }
        check(existing == null || existing.state == PersonalUpdateAcknowledgementState.RESTORED) {
            "Pending personal acknowledgement receipt is immutable"
        }
        requireUnusedPersonalAcknowledgementIdentity(db, proposed)
        if (!localMutations.personalAcknowledgementSource(db, proposed) ||
            !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run null
        val owner = editAcknowledgementOwner(db, scope, event)
        val historicalAdmission = owner == proposed.intent &&
            personalPreparationAdmission(db, proposed.intent) == PersonalPreparationAdmission.HISTORICAL
        if (!historicalAdmission && !localMutations.exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
            return@run null
        val preparation = readPersonalEditPreparation(db, scope, event) ?: return@run null
        if (!samePersonalPreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
            return@run null
        if (existing != null) {
            archivePersonalUpdateAcknowledgement(db, existing)
            check(db.delete("personal_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                    encodePersonalUpdateAcknowledgement(existing.receipt))) == 1) { "Personal acknowledgement slot changed" }
        }
        check(!db.rawQuery("SELECT 1 FROM personal_update_acknowledgement_history WHERE nonce=?",
            arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Personal acknowledgement nonce is already retained" }
        db.insertOrThrow("personal_update_acknowledgements", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
            put("receipt", encoded); put("state", PersonalUpdateAcknowledgementState.PENDING.name)
        })
        checkStagingCapacity(db)
        PersonalUpdateAcknowledgementReservation(PersonalUpdateAcknowledgementRecord(proposed,
            PersonalUpdateAcknowledgementState.PENDING), fresh = true)
    }

    fun restorePersonalUpdateAcknowledgement(db: SQLiteDatabase, receipt: PersonalUpdateAcknowledgementReceipt,
        intent: LocalEditIntent, scope: CalendarScope, event: EventId): Boolean = run {
        val retained = readPersonalUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@run false
        if (retained.receipt != receipt) return@run false
        if (retained.state == PersonalUpdateAcknowledgementState.RESTORED) return@run true
        if (!activeProjectionScope(db, scope) || !localMutations.acknowledgementFormatMatches(db, scope, intent.format) ||
            !localMutations.personalAcknowledgementSource(db, receipt) || !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run false
        val owner = editAcknowledgementOwner(db, scope, event) ?: return@run false
        if (owner.copy(state = LocalEditState.PAUSED) != intent) return@run false
        val preparation = readPersonalEditPreparation(db, scope, event) ?: return@run false
        if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@run false
        if (localMutations.readBaseline(db, scope, event) != intent.base) return@run false
        // The native ACK is now proven. Retire only a still-redundant ordinary
        // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
        if (receipt.handoff == null) localMutations.retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
        val oldBase = encodeChange(intent.base)
        val newBase = encodeChange(receipt.projection.change)
        val local = encodeChange(intent.base.copy(content = intent.content))
        check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
            "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
        receipt.handoff?.let { frame ->
            check(db.update("captured_create_handoffs", ContentValues().apply {
                put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
            }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                    frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                    frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                "Transferred personal acknowledgement source changed"
            }
        }
        check(db.delete("personal_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                preparation.format.version.toString(), preparation.protectedPayload)) == 1)
        check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                oldBase, local, owner.state.name)) == 1)
        // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
        db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
            "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
        localMutations.requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
        check(db.update("personal_update_acknowledgements", ContentValues().apply {
            put("state", PersonalUpdateAcknowledgementState.RESTORED.name)
        }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
            arrayOf(key(scope), event.value, receipt.projection.nonce, encodePersonalUpdateAcknowledgement(receipt),
                PersonalUpdateAcknowledgementState.PENDING.name)) == 1)
        archivePersonalUpdateAcknowledgement(db, PersonalUpdateAcknowledgementRecord(receipt,
            PersonalUpdateAcknowledgementState.RESTORED))
        true
    }

    fun prepareTitleUpdateAcknowledgement(db: SQLiteDatabase, frozen: TitleUpdateVerifiedResult,
        proposed: TitleUpdateAcknowledgementReceipt, encoded: String): TitleUpdateAcknowledgementReservation? = run {
        val scope = proposed.intent.scope
        val event = proposed.intent.base.eventId
        val historical = readTitleUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
        val existing = if (historical == null) readTitleUpdateAcknowledgement(db, scope, event) else null
        val exact = if (historical != null) readTitleUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
            else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
        if (exact != null) {
            check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                "Title acknowledgement receipt is immutable"
            }
            return@run TitleUpdateAcknowledgementReservation(exact, fresh = false)
        }
        check(existing == null || existing.state == TitleUpdateAcknowledgementState.RESTORED) {
            "Pending title acknowledgement receipt is immutable"
        }
        requireUnusedTitleAcknowledgementIdentity(db, proposed)
        if (!localMutations.titleAcknowledgementSource(db, proposed) ||
            !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run null
        val owner = titleAcknowledgementOwner(db, scope, event)
        val historicalAdmission = owner == proposed.intent &&
            localTitlePreparationAdmission(db, proposed.intent) == LocalTitlePreparationAdmission.HISTORICAL
        if (!historicalAdmission && !localMutations.exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
            return@run null
        val preparation = readLocalEditPreparation(db, scope, event) ?: return@run null
        if (preparation != frozen.record.preparation || !proposed.attempt.matchesPreparation(preparation))
            return@run null
        if (existing != null) {
            archiveTitleUpdateAcknowledgement(db, existing)
            check(db.delete("title_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                    encodeTitleUpdateAcknowledgement(existing.receipt))) == 1) { "Title acknowledgement slot changed" }
        }
        check(!db.rawQuery("SELECT 1 FROM title_update_acknowledgement_history WHERE nonce=?",
            arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Title acknowledgement nonce is already retained" }
        db.insertOrThrow("title_update_acknowledgements", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
            put("receipt", encoded); put("state", TitleUpdateAcknowledgementState.PENDING.name)
        })
        TitleUpdateAcknowledgementReservation(TitleUpdateAcknowledgementRecord(proposed,
            TitleUpdateAcknowledgementState.PENDING), fresh = true)
    }

    fun restoreTitleUpdateAcknowledgement(db: SQLiteDatabase, receipt: TitleUpdateAcknowledgementReceipt,
        intent: LocalEditIntent, scope: CalendarScope, event: EventId): Boolean = run {
        val retained = readTitleUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@run false
        if (retained.receipt != receipt) return@run false
        if (retained.state == TitleUpdateAcknowledgementState.RESTORED) return@run true
        if (!activeProjectionScope(db, scope) || !localMutations.acknowledgementFormatMatches(db, scope, intent.format) ||
            !localMutations.titleAcknowledgementSource(db, receipt) || !localMutations.directUpdateAcknowledgementBase(db, scope, event))
            return@run false
        val owner = titleAcknowledgementOwner(db, scope, event) ?: return@run false
        if (owner.copy(state = LocalEditState.PAUSED) != intent) return@run false
        val preparation = readLocalEditPreparation(db, scope, event) ?: return@run false
        if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@run false
        if (localMutations.readBaseline(db, scope, event) != intent.base) return@run false
        // The native ACK is now proven. Retire only a still-redundant ordinary
        // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
        if (receipt.handoff == null) localMutations.retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
        val oldBase = encodeChange(intent.base)
        val newBase = encodeChange(receipt.projection.change)
        val local = encodeChange(intent.base.copy(content = intent.content))
        check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
            "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
        receipt.handoff?.let { frame ->
            check(db.update("captured_create_handoffs", ContentValues().apply {
                put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
            }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                    frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                    frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                "Transferred title acknowledgement source changed"
            }
        }
        check(db.delete("local_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                preparation.format.version.toString(), preparation.protectedPayload)) == 1)
        check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
            "AND base=? AND local=? AND state=? AND kind='UPDATE'",
            arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                oldBase, local, owner.state.name)) == 1)
        // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
        db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
            "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
        localMutations.requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
        check(db.update("title_update_acknowledgements", ContentValues().apply {
            put("state", TitleUpdateAcknowledgementState.RESTORED.name)
        }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
            arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTitleUpdateAcknowledgement(receipt),
                TitleUpdateAcknowledgementState.PENDING.name)) == 1)
        archiveTitleUpdateAcknowledgement(db, TitleUpdateAcknowledgementRecord(receipt,
            TitleUpdateAcknowledgementState.RESTORED))
        true
    }

    private fun archiveTextUpdateAcknowledgement(db: SQLiteDatabase, record: TextUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archiveTextUpdateAcknowledgement(db, record, ::checkStagingCapacity)

    private fun archiveTimeUpdateAcknowledgement(db: SQLiteDatabase, record: TimeUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archiveTimeUpdateAcknowledgement(db, record, ::checkStagingCapacity)

    private fun archivePersonalUpdateAcknowledgement(db: SQLiteDatabase, record: PersonalUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archivePersonalUpdateAcknowledgement(db, record, ::checkStagingCapacity)

}

package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.requirePreparationSnapshotBudget
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TitleUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Family-specific immutable UPDATE receipts. Callers retain transaction and admission ownership. */
internal object ImportStoreUpdateAcknowledgements {
    fun readTextUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TextUpdateAcknowledgementRecord? {
        if (attempt == null) return readCurrentTextUpdateAcknowledgement(db, scope, event)
        val historical = readTextUpdateAcknowledgementHistory(db, scope, event, attempt)
        if (historical != null) {
            // A later slot is unrelated to historical recovery. Only qualify it if it claims
            // the archived identity/nonce; never decode its mutable handoff or projection frame.
            val sameIdentity = stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")} FROM text_update_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) false else archiveString(row, 0) == historical.receipt.projection.nonce || try {
                    requirePreparationSnapshotBudget(archiveString(row, 1))
                    JSONObject(archiveString(row, 1)).getJSONObject("attempt").get("id") == attempt.value
                } catch (failure: CancellationException) { throw failure }
                catch (failure: SQLiteException) { throw failure }
                catch (_: Exception) { false }
            }
            if (sameIdentity) check(readCurrentTextUpdateAcknowledgement(db, scope, event) == historical) {
                "Text acknowledgement history diverged"
            }
            return historical
        }
        return readCurrentTextUpdateAcknowledgement(db, scope, event)?.takeIf { it.receipt.attempt.id == attempt }
    }

    private fun readCurrentTextUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): TextUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")},${archiveText("state", 16)} FROM text_update_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else try {
                val receipt = decodeTextUpdateAcknowledgement(archiveString(row, 1))
                require(receipt.intent.scope == scope && receipt.intent.base.eventId == event && receipt.projection.nonce == archiveString(row, 0))
                TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.valueOf(archiveString(row, 2)))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: SQLiteException) { throw failure }
            catch (_: Exception) { throw IllegalStateException("Invalid retained text acknowledgement") }
        }

    fun readTextUpdateAcknowledgementHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId): TextUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM text_update_acknowledgement_history WHERE scope=? AND event=? AND attempt=?",
            arrayOf(key(scope), event.value, attempt.value)).use { row ->
            if (!row.moveToFirst()) null else decodeTextUpdateAcknowledgementHistory(scope, event,
                archiveString(row, 0), archiveString(row, 1), archiveString(row, 2))
        }

    private fun decodeTextUpdateAcknowledgementHistory(scope: CalendarScope, event: EventId,
        attempt: String, nonce: String, payload: String): TextUpdateAcknowledgementRecord = try {
        val receipt = decodeTextUpdateAcknowledgement(payload)
        require(receipt.intent.scope == scope && receipt.intent.base.eventId == event &&
            receipt.attempt.id.value == attempt && receipt.projection.nonce == nonce)
        TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: SQLiteException) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Invalid text acknowledgement history") }

    fun archiveTextUpdateAcknowledgement(db: SQLiteDatabase, record: TextUpdateAcknowledgementRecord,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        check(record.state == TextUpdateAcknowledgementState.RESTORED)
        val receipt = record.receipt
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        val existing = readTextUpdateAcknowledgementHistory(db, scope, event, receipt.attempt.id)
        if (existing != null) {
            check(existing == record) { "Text acknowledgement history is immutable" }
            return
        }
        db.insertOrThrow("text_update_acknowledgement_history", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("attempt", receipt.attempt.id.value)
            put("nonce", receipt.projection.nonce); put("receipt", encodeTextUpdateAcknowledgement(receipt))
        })
        checkStagingCapacity(db)
    }

    /** An immutable attempt or local intent cannot be rebound to a new cycle/event in this scope. */
    fun requireUnusedTextAcknowledgementIdentity(db: SQLiteDatabase, proposed: TextUpdateAcknowledgementReceipt) {
        val scope = proposed.intent.scope
        fun unused(retained: TextUpdateAcknowledgementReceipt) {
            check(retained.attempt.id != proposed.attempt.id && retained.intent.id != proposed.intent.id) {
                "Text acknowledgement identity is already retained"
            }
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)} FROM text_update_acknowledgements WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(checkNotNull(readCurrentTextUpdateAcknowledgement(db, scope, EventId(archiveString(rows, 0)))).receipt)
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)},${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM text_update_acknowledgement_history WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(decodeTextUpdateAcknowledgementHistory(scope, EventId(archiveString(rows, 0)),
                archiveString(rows, 1), archiveString(rows, 2), archiveString(rows, 3)).receipt)
        }
    }

    fun encodeTextUpdateAcknowledgement(value: TextUpdateAcknowledgementReceipt): String =
        TextUpdateAcknowledgementCodec.encode(value)

    private fun decodeTextUpdateAcknowledgement(payload: String): TextUpdateAcknowledgementReceipt =
        TextUpdateAcknowledgementCodec.decode(payload)

    fun readTimeUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TimeUpdateAcknowledgementRecord? {
        if (attempt == null) return readCurrentTimeUpdateAcknowledgement(db, scope, event)
        val historical = readTimeUpdateAcknowledgementHistory(db, scope, event, attempt)
        if (historical != null) {
            // A later slot is unrelated to historical recovery. Only qualify it if it claims
            // the archived identity/nonce; never decode its mutable handoff or projection frame.
            val sameIdentity = stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")} FROM time_update_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) false else archiveString(row, 0) == historical.receipt.projection.nonce || try {
                    requirePreparationSnapshotBudget(archiveString(row, 1))
                    JSONObject(archiveString(row, 1)).getJSONObject("attempt").get("id") == attempt.value
                } catch (failure: CancellationException) { throw failure }
                catch (failure: SQLiteException) { throw failure }
                catch (_: Exception) { false }
            }
            if (sameIdentity) check(readCurrentTimeUpdateAcknowledgement(db, scope, event) == historical) {
                "Time acknowledgement history diverged"
            }
            return historical
        }
        return readCurrentTimeUpdateAcknowledgement(db, scope, event)?.takeIf { it.receipt.attempt.id == attempt }
    }

    private fun readCurrentTimeUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): TimeUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")},${archiveText("state", 16)} FROM time_update_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else try {
                val receipt = decodeTimeUpdateAcknowledgement(archiveString(row, 1))
                require(receipt.intent.scope == scope && receipt.intent.base.eventId == event && receipt.projection.nonce == archiveString(row, 0))
                TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.valueOf(archiveString(row, 2)))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: SQLiteException) { throw failure }
            catch (_: Exception) { throw IllegalStateException("Invalid retained time acknowledgement") }
        }

    fun readTimeUpdateAcknowledgementHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId): TimeUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM time_update_acknowledgement_history WHERE scope=? AND event=? AND attempt=?",
            arrayOf(key(scope), event.value, attempt.value)).use { row ->
            if (!row.moveToFirst()) null else decodeTimeUpdateAcknowledgementHistory(scope, event,
                archiveString(row, 0), archiveString(row, 1), archiveString(row, 2))
        }

    private fun decodeTimeUpdateAcknowledgementHistory(scope: CalendarScope, event: EventId,
        attempt: String, nonce: String, payload: String): TimeUpdateAcknowledgementRecord = try {
        val receipt = decodeTimeUpdateAcknowledgement(payload)
        require(receipt.intent.scope == scope && receipt.intent.base.eventId == event &&
            receipt.attempt.id.value == attempt && receipt.projection.nonce == nonce)
        TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: SQLiteException) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Invalid time acknowledgement history") }

    fun archiveTimeUpdateAcknowledgement(db: SQLiteDatabase, record: TimeUpdateAcknowledgementRecord,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        check(record.state == TimeUpdateAcknowledgementState.RESTORED)
        val receipt = record.receipt
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        val existing = readTimeUpdateAcknowledgementHistory(db, scope, event, receipt.attempt.id)
        if (existing != null) {
            check(existing == record) { "Time acknowledgement history is immutable" }
            return
        }
        db.insertOrThrow("time_update_acknowledgement_history", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("attempt", receipt.attempt.id.value)
            put("nonce", receipt.projection.nonce); put("receipt", encodeTimeUpdateAcknowledgement(receipt))
        })
        checkStagingCapacity(db)
    }

    /** An immutable attempt or local intent cannot be rebound to a new cycle/event in this scope. */
    fun requireUnusedTimeAcknowledgementIdentity(db: SQLiteDatabase, proposed: TimeUpdateAcknowledgementReceipt) {
        val scope = proposed.intent.scope
        fun unused(retained: TimeUpdateAcknowledgementReceipt) {
            check(retained.attempt.id != proposed.attempt.id && retained.intent.id != proposed.intent.id) {
                "Time acknowledgement identity is already retained"
            }
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)} FROM time_update_acknowledgements WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(checkNotNull(readCurrentTimeUpdateAcknowledgement(db, scope, EventId(archiveString(rows, 0)))).receipt)
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)},${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM time_update_acknowledgement_history WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(decodeTimeUpdateAcknowledgementHistory(scope, EventId(archiveString(rows, 0)),
                archiveString(rows, 1), archiveString(rows, 2), archiveString(rows, 3)).receipt)
        }
    }

    fun encodeTimeUpdateAcknowledgement(value: TimeUpdateAcknowledgementReceipt): String =
        TimeUpdateAcknowledgementCodec.encode(value)

    private fun decodeTimeUpdateAcknowledgement(payload: String): TimeUpdateAcknowledgementReceipt =
        TimeUpdateAcknowledgementCodec.decode(payload)

    fun readPersonalUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): PersonalUpdateAcknowledgementRecord? {
        if (attempt == null) return readCurrentPersonalUpdateAcknowledgement(db, scope, event)
        val historical = readPersonalUpdateAcknowledgementHistory(db, scope, event, attempt)
        if (historical != null) {
            // A later slot is unrelated to historical recovery. Only qualify it if it claims
            // the archived identity/nonce; never decode its mutable handoff or projection frame.
            val sameIdentity = stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")} FROM personal_update_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) false else archiveString(row, 0) == historical.receipt.projection.nonce || try {
                    requirePreparationSnapshotBudget(archiveString(row, 1))
                    JSONObject(archiveString(row, 1)).getJSONObject("attempt").get("id") == attempt.value
                } catch (failure: CancellationException) { throw failure }
                catch (failure: SQLiteException) { throw failure }
                catch (_: Exception) { false }
            }
            if (sameIdentity) check(readCurrentPersonalUpdateAcknowledgement(db, scope, event) == historical) {
                "Personal acknowledgement history diverged"
            }
            return historical
        }
        return readCurrentPersonalUpdateAcknowledgement(db, scope, event)?.takeIf { it.receipt.attempt.id == attempt }
    }

    private fun readCurrentPersonalUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): PersonalUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("nonce", 128)},${archiveText("receipt")},${archiveText("state", 16)} FROM personal_update_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else try {
                val receipt = decodePersonalUpdateAcknowledgement(archiveString(row, 1))
                require(receipt.intent.scope == scope && receipt.intent.base.eventId == event && receipt.projection.nonce == archiveString(row, 0))
                PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.valueOf(archiveString(row, 2)))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: SQLiteException) { throw failure }
            catch (_: Exception) { throw IllegalStateException("Invalid retained personal acknowledgement") }
        }

    fun readPersonalUpdateAcknowledgementHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId): PersonalUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT ${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM personal_update_acknowledgement_history WHERE scope=? AND event=? AND attempt=?",
            arrayOf(key(scope), event.value, attempt.value)).use { row ->
            if (!row.moveToFirst()) null else decodePersonalUpdateAcknowledgementHistory(scope, event,
                archiveString(row, 0), archiveString(row, 1), archiveString(row, 2))
        }

    private fun decodePersonalUpdateAcknowledgementHistory(scope: CalendarScope, event: EventId,
        attempt: String, nonce: String, payload: String): PersonalUpdateAcknowledgementRecord = try {
        val receipt = decodePersonalUpdateAcknowledgement(payload)
        require(receipt.intent.scope == scope && receipt.intent.base.eventId == event &&
            receipt.attempt.id.value == attempt && receipt.projection.nonce == nonce)
        PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: SQLiteException) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Invalid personal acknowledgement history") }

    fun archivePersonalUpdateAcknowledgement(db: SQLiteDatabase, record: PersonalUpdateAcknowledgementRecord,
        checkStagingCapacity: (SQLiteDatabase) -> Unit) {
        check(record.state == PersonalUpdateAcknowledgementState.RESTORED)
        val receipt = record.receipt
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        val existing = readPersonalUpdateAcknowledgementHistory(db, scope, event, receipt.attempt.id)
        if (existing != null) {
            check(existing == record) { "Personal acknowledgement history is immutable" }
            return
        }
        db.insertOrThrow("personal_update_acknowledgement_history", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("attempt", receipt.attempt.id.value)
            put("nonce", receipt.projection.nonce); put("receipt", encodePersonalUpdateAcknowledgement(receipt))
        })
        checkStagingCapacity(db)
    }

    /** An immutable attempt or local intent cannot be rebound to a new cycle/event in this scope. */
    fun requireUnusedPersonalAcknowledgementIdentity(db: SQLiteDatabase, proposed: PersonalUpdateAcknowledgementReceipt) {
        val scope = proposed.intent.scope
        fun unused(retained: PersonalUpdateAcknowledgementReceipt) {
            check(retained.attempt.id != proposed.attempt.id && retained.intent.id != proposed.intent.id) {
                "Personal acknowledgement identity is already retained"
            }
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)} FROM personal_update_acknowledgements WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(checkNotNull(readCurrentPersonalUpdateAcknowledgement(db, scope, EventId(archiveString(rows, 0)))).receipt)
        }
        stagedQuery(db, "SELECT ${archiveText("event", 4096)},${archiveText("attempt", 4096)},${archiveText("nonce", 128)},${archiveText("receipt")} FROM personal_update_acknowledgement_history WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(decodePersonalUpdateAcknowledgementHistory(scope, EventId(archiveString(rows, 0)),
                archiveString(rows, 1), archiveString(rows, 2), archiveString(rows, 3)).receipt)
        }
    }

    fun encodePersonalUpdateAcknowledgement(value: PersonalUpdateAcknowledgementReceipt): String =
        PersonalUpdateAcknowledgementCodec.encode(value)

    private fun decodePersonalUpdateAcknowledgement(payload: String): PersonalUpdateAcknowledgementReceipt =
        PersonalUpdateAcknowledgementCodec.decode(payload)

    fun readTitleUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TitleUpdateAcknowledgementRecord? {
        if (attempt == null) return readCurrentTitleUpdateAcknowledgement(db, scope, event)
        val historical = readTitleUpdateAcknowledgementHistory(db, scope, event, attempt)
        if (historical != null) {
            // A later slot is unrelated to historical recovery. Only qualify it if it claims
            // the archived identity/nonce; never decode its mutable handoff or projection frame.
            val sameIdentity = stagedQuery(db, "SELECT nonce,receipt FROM title_update_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) false else row.getString(0) == historical.receipt.projection.nonce || try {
                    requirePreparationSnapshotBudget(row.getString(1))
                    JSONObject(row.getString(1)).getJSONObject("attempt").get("id") == attempt.value
                } catch (failure: CancellationException) { throw failure }
                catch (failure: SQLiteException) { throw failure }
                catch (_: Exception) { false }
            }
            if (sameIdentity) check(readCurrentTitleUpdateAcknowledgement(db, scope, event) == historical) {
                "Title acknowledgement history diverged"
            }
            return historical
        }
        return readCurrentTitleUpdateAcknowledgement(db, scope, event)?.takeIf { it.receipt.attempt.id == attempt }
    }

    private fun readCurrentTitleUpdateAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): TitleUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT nonce,receipt,state FROM title_update_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) null else try {
                val receipt = decodeTitleUpdateAcknowledgement(row.getString(1))
                require(receipt.intent.scope == scope && receipt.intent.base.eventId == event && receipt.projection.nonce == row.getString(0))
                TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.valueOf(row.getString(2)))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: SQLiteException) { throw failure }
            catch (_: Exception) { throw IllegalStateException("Invalid retained title acknowledgement") }
        }

    fun readTitleUpdateAcknowledgementHistory(db: SQLiteDatabase, scope: CalendarScope, event: EventId,
        attempt: MutationId): TitleUpdateAcknowledgementRecord? =
        stagedQuery(db, "SELECT attempt,nonce,receipt FROM title_update_acknowledgement_history WHERE scope=? AND event=? AND attempt=?",
            arrayOf(key(scope), event.value, attempt.value)).use { row ->
            if (!row.moveToFirst()) null else decodeTitleUpdateAcknowledgementHistory(scope, event,
                row.getString(0), row.getString(1), row.getString(2))
        }

    private fun decodeTitleUpdateAcknowledgementHistory(scope: CalendarScope, event: EventId,
        attempt: String, nonce: String, payload: String): TitleUpdateAcknowledgementRecord = try {
        val receipt = decodeTitleUpdateAcknowledgement(payload)
        require(receipt.intent.scope == scope && receipt.intent.base.eventId == event &&
            receipt.attempt.id.value == attempt && receipt.projection.nonce == nonce)
        TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED)
    } catch (failure: CancellationException) { throw failure }
    catch (failure: SQLiteException) { throw failure }
    catch (_: Exception) { throw IllegalStateException("Invalid title acknowledgement history") }

    fun archiveTitleUpdateAcknowledgement(db: SQLiteDatabase, record: TitleUpdateAcknowledgementRecord) {
        check(record.state == TitleUpdateAcknowledgementState.RESTORED)
        val receipt = record.receipt
        val scope = receipt.intent.scope
        val event = receipt.intent.base.eventId
        val existing = readTitleUpdateAcknowledgementHistory(db, scope, event, receipt.attempt.id)
        if (existing != null) {
            check(existing == record) { "Title acknowledgement history is immutable" }
            return
        }
        db.insertOrThrow("title_update_acknowledgement_history", null, ContentValues().apply {
            put("scope", key(scope)); put("event", event.value); put("attempt", receipt.attempt.id.value)
            put("nonce", receipt.projection.nonce); put("receipt", encodeTitleUpdateAcknowledgement(receipt))
        })
    }

    /** An immutable attempt or local intent cannot be rebound to a new cycle/event in this scope. */
    fun requireUnusedTitleAcknowledgementIdentity(db: SQLiteDatabase, proposed: TitleUpdateAcknowledgementReceipt) {
        val scope = proposed.intent.scope
        fun unused(retained: TitleUpdateAcknowledgementReceipt) {
            check(retained.attempt.id != proposed.attempt.id && retained.intent.id != proposed.intent.id) {
                "Title acknowledgement identity is already retained"
            }
        }
        stagedQuery(db, "SELECT event FROM title_update_acknowledgements WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(checkNotNull(readCurrentTitleUpdateAcknowledgement(db, scope, EventId(rows.getString(0)))).receipt)
        }
        stagedQuery(db, "SELECT event,attempt,nonce,receipt FROM title_update_acknowledgement_history WHERE scope=?", arrayOf(key(scope))).use { rows ->
            while (rows.moveToNext()) unused(decodeTitleUpdateAcknowledgementHistory(scope, EventId(rows.getString(0)),
                rows.getString(1), rows.getString(2), rows.getString(3)).receipt)
        }
    }

    fun encodeTitleUpdateAcknowledgement(value: TitleUpdateAcknowledgementReceipt): String =
        TitleUpdateAcknowledgementCodec.encode(value)

    private fun decodeTitleUpdateAcknowledgement(payload: String): TitleUpdateAcknowledgementReceipt =
        TitleUpdateAcknowledgementCodec.decode(payload)

}

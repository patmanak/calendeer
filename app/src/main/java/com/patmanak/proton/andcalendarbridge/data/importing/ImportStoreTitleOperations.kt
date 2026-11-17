// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.requirePreparationSnapshotBudget
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.UUID

/** The store retains IO, mutex, cancellation and transaction ownership. */
internal interface ImportStoreMutations {
    suspend fun <T> mutate(scope: CalendarScope, action: (SQLiteDatabase) -> T): T
}

/** Historical title preparations and acknowledgements; no fresh writer or new SQL policy. */
internal class ImportStoreTitleOperations(
    private val mutations: ImportStoreMutations,
    private val preparation: ImportStorePreparationSql,
    private val updates: ImportStoreUpdateSql,
    private val localTitlePreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTitlePreparationAdmission,
) {
    /** Freeze an exact captured owner and sealed fragments together, without dispatch admission.
     * Existing evidence is immutable even after its current owner becomes conflicted or paused.
     */
    suspend fun retainLocalEditPreparation(value: LocalEditPreparation): Boolean {
        // Snapshot caller-owned empty lists and serialized observations before the first suspension.
        val frozen = value.copy(intent = LocalEditPreparation.freezeIntent(value.intent))
        val intent = frozen.intent
        val base = encodeChange(intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(intent.base.copy(content = intent.content)).also(::requirePreparationSnapshotBudget)
        return mutations.mutate(intent.scope) { db ->
        preparation.retainLocalEditPreparation(db, frozen, intent, base, local)
        }
    }
    /** SQL observation only; does not inspect Android or refresh remote access. */
    suspend fun assessLocalTitlePreparation(intent: LocalEditIntent): LocalTitlePreparationAdmission =
        mutations.mutate(intent.scope) { db -> localTitlePreparationAdmission(db, intent) }

    /** First admission rechecks the owner, checkpoint, baseline and remote in the insertion transaction. */
    suspend fun retainCurrentLocalTitlePreparation(value: LocalEditPreparation): LocalTitlePreparationAdmission {
        val frozen = value.copy(intent = LocalEditPreparation.freezeIntent(value.intent))
        val base = encodeChange(frozen.intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(frozen.intent.base.copy(content = frozen.intent.content)).also(::requirePreparationSnapshotBudget)
        return mutations.mutate(frozen.intent.scope) { db ->
        preparation.retainCurrentLocalTitlePreparation(db, frozen, base, local)
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTitlePreparation(intent: LocalEditIntent): LocalTitlePreparationAdmission =
        mutations.mutate(intent.scope) { db ->
        preparation.conflictLocalTitlePreparation(db, intent)
    }

    /** Reserve the immutable title receipt before the one fresh native metadata write.
     * The coordinator owns verified-result admission; this transaction qualifies only its SQL source.
     */
    suspend fun prepareTitleUpdateAcknowledgement(result: TitleUpdateVerifiedResult,
        handoff: CapturedCreateHandoffFrame? = null): TitleUpdateAcknowledgementReservation? {
        val frozen = TitleUpdateVerifiedResult.of(result.record,
            TitleUpdateSubmissionObservation.Verified(result.attempt, result.event))
        val proposed = TitleUpdateAcknowledgementReceipt.of(frozen, UUID.randomUUID().toString(), handoff)
        val encoded = encodeTitleUpdateAcknowledgement(proposed)
        return mutations.mutate(proposed.intent.scope) { db ->
            updates.prepareTitleUpdateAcknowledgement(db, frozen, proposed, encoded)
        }
    }

    /** Historical leased inspection, including inactive scopes; never permission for a native write. */
    suspend fun titleUpdateAcknowledgement(scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TitleUpdateAcknowledgementRecord? =
        mutations.mutate(scope) { db -> readTitleUpdateAcknowledgement(db, scope, event, attempt) }

    /** Close only the exact historically acknowledged owner. New remote/deferred work survives.
     * A RESTORED replay is read-only and cannot consume a subsequent intent or preparation.
     */
    suspend fun restoreTitleUpdateAcknowledgement(witness: AppliedTitleUpdateAcknowledgement): Boolean {
        val receipt = witness.receipt.snapshot()
        val intent = receipt.intent
        val scope = intent.scope
        val event = intent.base.eventId
        return mutations.mutate(scope) { db ->
            updates.restoreTitleUpdateAcknowledgement(db, receipt, intent, scope, event)
        }
    }

    /** Historical inspection only: a frozen PAUSED snapshot never resets the current conflict state. */
    suspend fun localEditPreparation(scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        mutations.mutate(scope) { db -> readLocalEditPreparation(db, scope, eventId) }

}

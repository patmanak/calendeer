package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException

/** Local closure of an already verified title update under the coordinator's existing mutex.
 * The caller supplies the original run epoch. Fresh ACK reservations permit one native write;
 * retained receipts only inspect, and completion only retains exact durable historical proof. */
internal class LocalTitleCompletion(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val currentFormat: () -> AdapterFormat,
    private val active: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
) {
    private suspend fun available(): Boolean {
        if (!active() || !currentAccount()) return false
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        return selected?.enabled == true && selected.available && active()
    }

    suspend fun acknowledge(eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal): TitleUpdateAcknowledgementResult {
        return try {
            if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            if (projection.titleUpdateAcknowledgementProtocol != 1)
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            val result = journal.readVerified(target, attempt)
                ?: return TitleUpdateAcknowledgementResult.UNRESOLVED
            if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
            val intent = result.record.preparation.intent
            if (result.record.scope != target || result.record.id != attempt || result.event.eventId != eventId ||
                intent.format != currentFormat() || store.transaction(target) { checkpoint?.format } != intent.format)
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            val existing = store.titleUpdateAcknowledgement(target, eventId, attempt)
            if (existing != null && !existing.receipt.matches(result))
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            if (existing?.state == TitleUpdateAcknowledgementState.RESTORED)
                return TitleUpdateAcknowledgementResult.ACKNOWLEDGED
            val reservation = if (existing != null) TitleUpdateAcknowledgementReservation(existing, false) else {
                val transfer = store.capturedHandoff(target, eventId)
                val frame = transfer?.frame
                val content = if (frame == null) {
                    projection.captureOwnedSimpleEdit(target, intent.base)?.content
                } else {
                    if (transfer.state != CapturedCreateHandoffState.RELEASED || !transfer.waiting ||
                        frame.base != intent.base || frame.format != intent.format)
                        return TitleUpdateAcknowledgementResult.UNRESOLVED
                    val observed = projection.captureCapturedHandoffCurrent(frame) as? CapturedHandoffCurrentObservation.Supported
                    observed?.snapshot?.takeIf { it.frame == frame && !it.deleted }?.content
                } ?: return TitleUpdateAcknowledgementResult.UNRESOLVED
                if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
                if (content != intent.content || journal.readVerified(target, attempt) != result)
                    return TitleUpdateAcknowledgementResult.UNRESOLVED
                store.prepareTitleUpdateAcknowledgement(result, frame)
                    ?: return TitleUpdateAcknowledgementResult.UNRESOLVED
            }
            val retained = reservation.record
            if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
            if (!retained.receipt.matches(result) || store.titleUpdateAcknowledgement(target, eventId, attempt) != retained ||
                journal.readVerified(target, attempt) != result)
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            if (reservation.fresh) {
                val owner = store.localEdit(target, eventId)
                // The SQL receipt already qualified this owner before the native write.
                // An echo or later observation may change only its conflict marker;
                // identity and all snapshots must stay exact.
                val echoedOwner = owner?.state == LocalEditState.CONFLICT &&
                    owner.copy(state = LocalEditState.PAUSED) == intent
                if ((owner != intent && !echoedOwner) ||
                    store.localEditPreparation(target, eventId) != result.record.preparation)
                    return TitleUpdateAcknowledgementResult.UNRESOLVED
            }
            if (reservation.fresh && retained.receipt.handoff?.let { frame ->
                    store.capturedHandoff(target, eventId) != CapturedCreateHandoffRecord(frame,
                        CapturedCreateHandoffState.RELEASED, waiting = true)
                } == true) return TitleUpdateAcknowledgementResult.UNRESOLVED
            // The final journal/source reads can suspend. Recheck selection and account after
            // them as well as the synchronous epoch/mode/permission lease in the Provider.
            if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
            // No fallback from historical inspection to another native write. The first receipt
            // stays pending even when an interruption happened before the batch actually executed.
            val witness = if (reservation.fresh) projection.acknowledgeTitleUpdate(retained.receipt)
                else projection.inspectTitleUpdateAcknowledgement(retained.receipt)
            if (!available()) return TitleUpdateAcknowledgementResult.INACTIVE
            if (witness?.receipt?.snapshot() != retained.receipt || journal.readVerified(target, attempt) != result ||
                store.titleUpdateAcknowledgement(target, eventId, attempt) != retained)
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            if (!store.restoreTitleUpdateAcknowledgement(witness))
                return TitleUpdateAcknowledgementResult.UNRESOLVED
            TitleUpdateAcknowledgementResult.ACKNOWLEDGED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (!active()) TitleUpdateAcknowledgementResult.INACTIVE else TitleUpdateAcknowledgementResult.UNRESOLVED
          } finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    suspend fun complete(eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal): TitleUpdateCompletionResult {
        return try {
            if (!available()) return TitleUpdateCompletionResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            if (journal.completionProtocol != 1) return TitleUpdateCompletionResult.UNRESOLVED
            val result = journal.readVerified(target, attempt) ?: return TitleUpdateCompletionResult.UNRESOLVED
            if (!available()) return TitleUpdateCompletionResult.INACTIVE
            val intent = result.record.preparation.intent
            if (result.record.scope != target || result.record.id != attempt || result.event.eventId != eventId ||
                intent.format != currentFormat() || store.transaction(target) { checkpoint?.format } != intent.format)
                return TitleUpdateCompletionResult.UNRESOLVED
            val acknowledgement = store.titleUpdateAcknowledgement(target, eventId, attempt)
                ?: return TitleUpdateCompletionResult.UNRESOLVED
            if (acknowledgement.state != TitleUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result))
                return TitleUpdateCompletionResult.UNRESOLVED
            val completion = TitleUpdateCompletion.of(result, acknowledgement)
            val retained = journal.readCompletion(target, attempt)
            if (retained != null && retained != completion) return TitleUpdateCompletionResult.UNRESOLVED
            if (journal.readVerified(target, attempt) != result ||
                store.titleUpdateAcknowledgement(target, eventId, attempt) != acknowledgement)
                return TitleUpdateCompletionResult.UNRESOLVED
            // Repeat the selected-calendar/account check after the historical reads suspend.
            if (!available()) return TitleUpdateCompletionResult.INACTIVE
            if (retained == null && !journal.retainCompletion(completion) { it == target && active() })
                return TitleUpdateCompletionResult.UNRESOLVED
            if (!available()) return TitleUpdateCompletionResult.INACTIVE
            if (journal.readCompletion(target, attempt) != completion)
                return TitleUpdateCompletionResult.UNRESOLVED
            if (!available()) return TitleUpdateCompletionResult.INACTIVE
            TitleUpdateCompletionResult.COMPLETED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (!active()) TitleUpdateCompletionResult.INACTIVE else TitleUpdateCompletionResult.UNRESOLVED
          } finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }
}

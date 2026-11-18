package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Preparation must recheck the live source, account and destination and open the frozen request, without submission. */
internal interface GuestDeliveryTransport {
    suspend fun prepareMail(row: GuestJournalRecord): GuestMailTransmission?
    suspend fun prepareCalendar(row: GuestJournalRecord): GuestCalendarTransmission?
    /** Returns protected evidence only after exact authenticated readback of the frozen guest change. */
    suspend fun inspectCalendar(row: GuestJournalRecord): String?
}

internal fun interface GuestMailTransmission {
    suspend fun send(active: () -> Boolean): Long?
}

internal fun interface GuestCalendarTransmission {
    suspend fun send(active: () -> Boolean)
}

/** Executes at most one leg. A lost Mail response blocks Calendar, a lost Calendar response permits only inspection. */
internal class GuestDeliveryWorker(private val journal: GuestDeliveryJournal, private val transport: GuestDeliveryTransport) {
    suspend fun step(scope: CalendarScope, id: MutationId, admitWrite: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean) {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        val row = journal.read(scope, id, admitEvidence) ?: return
        val revoked = AtomicBoolean(false)
        fun active(): Boolean {
            if (!operation.isActive || !admitWrite(scope)) revoked.set(true)
            return !revoked.get()
        }
        val writeLease = { requested: CalendarScope -> requested == scope && active() }
        when (row.progress.phase) {
            GuestDeliveryPhase.MAIL_READY -> {
                if (!active()) return
                val mail = observe { transport.prepareMail(row) } ?: return
                if (!journal.claimMail(row, writeLease)) return
                operation.ensureActive()
                if (!active()) return
                val time = observe { mail.send(::active) } ?: return
                operation.ensureActive()
                val unknown = row.advance(row.progress.claimMail()) ?: return
                journal.mailAccepted(unknown, time, admitEvidence)
            }
            GuestDeliveryPhase.CALENDAR_READY -> {
                if (!active()) return
                val calendar = observe { transport.prepareCalendar(row) } ?: return
                if (!journal.claimCalendar(row, writeLease)) return
                operation.ensureActive()
                if (active()) observe { calendar.send(::active) }
            }
            GuestDeliveryPhase.CALENDAR_UNKNOWN -> {
                if (!admitEvidence(scope)) return
                val proof = observe { transport.inspectCalendar(row) } ?: return
                operation.ensureActive()
                journal.calendarVerified(row, proof, admitEvidence)
            }
            GuestDeliveryPhase.MAIL_UNKNOWN, GuestDeliveryPhase.BOTH_PROVEN -> Unit
        }
    }

    private suspend fun <T> observe(block: suspend () -> T?): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
}

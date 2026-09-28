package com.patmanak.proton.andcalendarbridge.sync

data class DeltaProgress(val needsNetwork: Boolean, val workRemaining: Boolean, val blocked: Boolean,
    val processed: Int = 0, val historyGap: Boolean = false)

/** Calendar deltas are durable obligations, not a snapshot from which absence implies deletion. */
class DeltaImportEngine(private val store: SyncStore, private val remote: CalendarDeltaRemote,
    private val serial: AccountSerialExecutor) {

    /** Capture before any baseline read. Existing obligations and cursors survive refreshes. */
    suspend fun prepare(scope: CalendarScope, allowNetwork: Boolean, fresh: Boolean): Boolean = serial.run(scope.account) {
        validateReconciliation(scope)
        val cursor = tx(scope) { deltaCursor }
        if (cursor == null) {
            if (!allowNetwork) return@run false
            val latest = remote.latestChanges(scope)
            validateCursor(latest)
            tx(scope) {
                check(deltaCursor == null)
                deltaCursor = latest
                deltaMore = true
                // A migrated baseline predates this cursor and cannot be used as its initial scan.
                checkpoint = null
                received = null
                coverage = coverage.restart()
            }
        } else validateCursor(cursor)
        if (fresh && allowNetwork) tx(scope) { retryBlockedJobs(); deltaMore = true }
        true
    }

    suspend fun run(scope: CalendarScope, allowNetwork: Boolean): DeltaProgress = serial.run(scope.account) {
        val historyGap = validateReconciliation(scope)
        val baseline = tx(scope) { checkpoint to received }
        baseline.first?.let {
            if (it.scope != scope || it.format != remote.format || it.cursor?.format?.let { f -> f != remote.format } == true)
                fail("Incompatible baseline checkpoint")
        }
        if (baseline.first?.traversalComplete != true) return@run DeltaProgress(allowNetwork.not(), true, false, historyGap = historyGap)
        if (baseline.second != null) fail("Unapplied baseline receipt")
        var pages = 0
        var jobs = 0
        while (jobs < 20 && pages < 4) {
            val job = tx(scope) { deltaJobs(1).firstOrNull() }
            if (job != null) {
                when (job.notice.kind) {
                    CalendarNoticeKind.CONTENT_CHANGED -> {
                        if (!allowNetwork) return@run progress(scope, true, pages + jobs)
                        remote.refreshContext(scope)
                        tx(scope) {
                            if (!removeDeltaJob(job)) fail("Changed content obligation")
                            checkpoint = null
                            coverage = coverage.restart()
                            deltaMore = true
                        }
                        // Settings can change inherited content without an event revision.
                        // Retain the feed position and other obligations across the bounded rescan.
                        return@run DeltaProgress(false, true, false, pages + jobs + 1, historyGap)
                    }
                    CalendarNoticeKind.CONTEXT_CHANGED -> {
                        if (!allowNetwork) return@run progress(scope, true, pages + jobs)
                        remote.refreshContext(scope)
                        tx(scope) { if (!removeDeltaJob(job)) fail("Changed context obligation") }
                    }
                    CalendarNoticeKind.EVENT_DELETED -> tx(scope) {
                        if (!removeDeltaJob(job)) fail("Changed deletion obligation")
                        apply(scope, RemoteChange.Deleted(job.notice.eventId!!, job.notice.revision!!))
                        clearDeferred(job.notice.eventId)
                        clearBlockedEvent(job.notice.eventId)
                    }
                    CalendarNoticeKind.EVENT_CHANGED -> {
                        if (job.received == null && !allowNetwork) return@run progress(scope, true, pages + jobs)
                        val saved = try {
                            job.received ?: remote.receiveEvent(scope, job.notice.eventId!!).also { receipt ->
                                validateEvent(scope, job, receipt)
                                tx(scope) {
                                    if (deltaJobs(1).firstOrNull() != job) fail("Changed acquisition obligation")
                                    putDeltaJob(job.copy(received = receipt))
                                }
                            }
                        } catch (failure: RemoteImportException) {
                            if (failure.reason != RemoteImportFailure.NOT_FOUND) throw failure
                            tx(scope) {
                                if (deltaJobs(1).firstOrNull() != job) fail("Changed missing-event obligation")
                                putDeltaJob(job.copy(blocked = true))
                            }
                            jobs++
                            continue
                        }
                        validateEvent(scope, job, saved)
                        val decoded = remote.decodeEvent(saved)
                        val resultId = decoded.change?.eventId ?: decoded.deferred!!.eventId
                        val revision = decoded.change?.revision ?: decoded.deferred!!.revision
                        if (resultId != saved.eventId || revision != saved.revision ||
                            decoded.deferred?.format?.let { it != remote.format } == true) fail("Changed decoded event identity")
                        tx(scope) {
                            if (!removeDeltaJob(job.copy(received = saved))) fail("Changed decode obligation")
                            decoded.change?.let { apply(scope, it); clearDeferred(it.eventId) }
                            decoded.deferred?.let { defer(it); removeProjection(it.eventId) }
                            clearBlockedEvent(saved.eventId)
                        }
                    }
                }
                jobs++
                continue
            }
            val (cursor, receipt, more) = tx(scope) { Triple(deltaCursor, deltaReceived, deltaMore) }
            if (cursor == null) return@run progress(scope, true, pages + jobs)
            validateCursor(cursor)
            if (receipt == null && !more) return@run progress(scope, false, pages + jobs)
            if (receipt == null && !allowNetwork) return@run progress(scope, true, pages + jobs)
            val saved = receipt ?: remote.receiveChanges(scope, cursor).also {
                validateReceipt(scope, cursor, it)
                tx(scope) {
                    if (deltaCursor != cursor || deltaReceived != null) fail("Changed delta acquisition")
                    deltaReceived = it
                }
            }
            validateReceipt(scope, cursor, saved)
            val page = remote.decodeChanges(saved)
            val events = page.notices.mapNotNull { it.eventId }
            if (page.scope != scope || page.after != cursor || events.distinct().size != events.size ||
                page.notices.map { it.id }.distinct().size != page.notices.size ||
                page.notices.size > 10000) fail("Invalid delta envelope")
            validateCursor(page.next)
            if (page.more && page.next == cursor) fail("Delta cursor did not advance")
            if (page.reconciliationRequired) {
                // A reset establishes a history gap, not authoritative event actions. Reanchor
                // before a bounded rescan; archive its evidence and retain all known local data.
                if (page.next == cursor) fail("Reset cursor did not advance")
                tx(scope) {
                    if (deltaCursor != cursor || deltaReceived != saved) fail("Changed reset evidence")
                    retainReconciliation(saved)
                    deltaCursor = page.next
                    deltaMore = true
                    deltaReceived = null
                    checkpoint = null
                    coverage = coverage.restart()
                }
                // The baseline barrier was checked at entry; never process further work here.
                return@run DeltaProgress(!allowNetwork, true, false, pages + jobs + 1, historyGap = true)
            }
            tx(scope) {
                if (deltaCursor != cursor || deltaReceived != saved) fail("Changed delta decoding")
                page.notices.sortedBy { if (it.eventId == null) 0 else 1 }.forEach {
                    it.eventId?.let(::clearBlockedEvent)
                    putDeltaJob(CalendarChangeJob(it))
                }
                deltaCursor = page.next
                deltaMore = page.more
                deltaReceived = null
            }
            pages++
        }
        progress(scope, false, pages + jobs)
    }

    private suspend fun progress(scope: CalendarScope, needsNetwork: Boolean, processed: Int) = tx(scope) {
        DeltaProgress(needsNetwork, deltaMore || deltaReceived != null || deltaJobs(1).isNotEmpty(),
            deltaJobs(1, true).isNotEmpty(), processed, reconciliationFormat != null)
    }
    private suspend fun validateReconciliation(scope: CalendarScope): Boolean {
        val format = tx(scope) { reconciliationFormat }
        if (format != null && format != remote.deltaFormat) fail("History gap requires adapter migration")
        return format != null
    }
    private fun DeltaSyncTransaction.apply(scope: CalendarScope, change: RemoteChange) {
        val previous = remote(change.eventId)
        if (previous?.revision == change.revision && previous != change) fail("Reused remote revision")
        if (previous != change) putRemote(change)
        if (hasPendingMutation(change.eventId)) removeProjection(change.eventId)
        else if (previous != change || deferred(change.eventId) != null) putProjection(ProjectionIntent(scope, change))
    }
    private fun validateCursor(cursor: OpaqueCursor) {
        if (cursor.format != remote.deltaFormat || cursor.value.isBlank()) fail("Incompatible delta cursor")
    }
    private fun validateReceipt(scope: CalendarScope, cursor: OpaqueCursor, receipt: ReceivedChangePage) {
        if (receipt.scope != scope || receipt.after != cursor) fail("Mismatched delta receipt")
    }
    private fun validateEvent(scope: CalendarScope, job: CalendarChangeJob, receipt: ReceivedCalendarEvent) {
        if (receipt.scope != scope || receipt.eventId != job.notice.eventId || receipt.format != remote.format) fail("Mismatched event receipt")
    }
    private suspend fun <T> tx(scope: CalendarScope, action: DeltaSyncTransaction.() -> T): T = store.transaction(scope) {
        (this as? DeltaSyncTransaction ?: fail("Durable delta store required")).action()
    }
    private fun fail(message: String): Nothing = throw SyncContractException(message)
}

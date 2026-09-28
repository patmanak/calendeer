package com.patmanak.proton.andcalendarbridge.sync

/** Bounded receipt -> local decoding -> transactional application, under the shared account owner. */
class StagedImportEngine(
    private val store: SyncStore,
    private val remote: StagedCalendarRemote,
    private val serial: AccountSerialExecutor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(scope: CalendarScope, allowNetwork: Boolean, maxPages: Int = 4, pageSize: Int = 100): ImportProgress {
        require(maxPages in 1..4 && pageSize in 1..100)
        return serial.run(scope.account) {
            var committed = 0
            while (committed < maxPages) {
                val (current, saved, epoch) = transaction(scope) {
                    if (coverage.active?.format?.let { it != remote.format } == true)
                        fail("Coverage requires adapter migration")
                    Triple(checkpoint ?: PageCheckpoint(scope, remote.format), received, coverage.epoch)
                }
                validateCheckpoint(scope, current)
                if (current.traversalComplete) {
                    check(saved == null) { "Completed import has an unapplied receipt" }
                    return@run ImportProgress(committed, true)
                }
                if (saved == null && !allowNetwork) return@run ImportProgress(committed, false)
                val expectedRequest = PageRequest(current, saved?.request?.limit ?: pageSize)
                val receipt = saved ?: remote.receive(expectedRequest).also { acquired ->
                    validateReceipt(expectedRequest, acquired)
                    transaction(scope) {
                        val actual = checkpoint ?: PageCheckpoint(scope, remote.format)
                        if (actual != current || received != null || coverage.epoch != epoch) fail("Import receipt changed during acquisition")
                        coverage = CoverageTracker.received(coverage, expectedRequest, acquired.coverage, now())
                        received = acquired
                    }
                }
                validateReceipt(expectedRequest, receipt)
                val decoded = remote.decode(receipt)
                validateDecoded(receipt, decoded)
                transaction(scope) {
                    val actual = checkpoint ?: PageCheckpoint(scope, remote.format)
                    if (actual != current || received != receipt || coverage.epoch != epoch) fail("Import changed during local decoding")
                    coverage = CoverageTracker.applied(coverage, decoded.coverage, decoded.page.traversalComplete, now())
                    for (change in decoded.page.changes) {
                        // A periodic window scan is weaker evidence than a consumed tombstone.
                        if (remote(change.eventId) is RemoteChange.Deleted && change is RemoteChange.Upsert) continue
                        applyChange(scope, change)
                        clearDeferred(change.eventId)
                    }
                    for (issue in decoded.deferred) {
                        if (remote(issue.eventId) is RemoteChange.Deleted) continue
                        defer(issue)
                        // Keep an already visible old copy, but never apply an older queued version.
                        removeProjection(issue.eventId)
                    }
                    checkpoint = PageCheckpoint(scope, remote.format, decoded.page.nextCursor, decoded.page.traversalComplete)
                    received = null
                }
                committed++
                if (decoded.page.traversalComplete) return@run ImportProgress(committed, true)
            }
            ImportProgress(committed, false)
        }
    }

    /** Bounded caller-selected retry; scope and exact retained version protect against stale results. */
    suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): Boolean = serial.run(scope.account) {
        if (event.format != remote.format) fail("Deferred event requires adapter migration")
        if (!transaction(scope) { deferred(event.eventId) == event }) return@run false
        val result = remote.retryDeferred(scope, event) ?: return@run false
        if (result.eventId != event.eventId) fail("Deferred decode changed event identity")
        transaction(scope) {
            if (deferred(event.eventId) != event) false else {
                applyChange(scope, result)
                clearDeferred(event.eventId)
                true
            }
        }
    }

    private fun StagedSyncTransaction.applyChange(scope: CalendarScope, change: RemoteChange) {
        val previous = remote(change.eventId)
        if (previous?.revision == change.revision && previous != change) fail("Revision was reused for different content")
        if (previous != change) putRemote(change)
        if (hasPendingMutation(change.eventId)) removeProjection(change.eventId)
        else if (previous != change || deferred(change.eventId) != null) putProjection(ProjectionIntent(scope, change))
    }

    private suspend fun <T> transaction(scope: CalendarScope, action: StagedSyncTransaction.() -> T): T =
        store.transaction(scope) {
            val staged = this as? StagedSyncTransaction ?: fail("Durable staged import store required")
            staged.action()
        }

    private fun validateCheckpoint(scope: CalendarScope, value: PageCheckpoint) {
        if (value.scope != scope || value.format != remote.format ||
            value.cursor?.format?.let { it != remote.format } == true) fail("Incompatible staged checkpoint")
    }

    private fun validateReceipt(expected: PageRequest, receipt: ReceivedCalendarPage) {
        if (receipt.request != expected || expected.limit !in 1..100) fail("Mismatched import receipt")
    }

    private fun validateDecoded(receipt: ReceivedCalendarPage, decoded: DecodedCalendarPage) {
        val current = receipt.request.checkpoint
        val page = decoded.page
        if (receipt.coverage != decoded.coverage) fail("Decoded coverage does not match received evidence")
        if (page.scope != current.scope || page.format != remote.format ||
            page.nextCursor?.format?.let { it != remote.format } == true) fail("Decoded page scope or format mismatch")
        if (page.traversalComplete != (page.nextCursor == null) ||
            !page.traversalComplete && page.nextCursor == current.cursor) fail("Invalid staged page continuation")
        val ids = page.changes.map { it.eventId } + decoded.deferred.map { it.eventId }
        if (ids.size > receipt.request.limit || ids.distinct().size != ids.size ||
            decoded.deferred.any { it.format != remote.format }) fail("Invalid decoded record envelope")
    }

    private fun fail(message: String): Nothing = throw SyncContractException(message)
}

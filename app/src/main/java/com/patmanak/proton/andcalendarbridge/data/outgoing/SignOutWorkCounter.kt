// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A fresh metadata sample from existing journals, never an inventory of uncaptured Android edits. */
internal class SignOutWorkCounter(
    private val currentAccount: suspend () -> AccountId?,
    private val epoch: () -> Long,
    private val authenticated: () -> Boolean,
    private val selections: suspend (AccountId) -> List<ImportStore.Selection>,
    private val creates: suspend (CalendarScope) -> Int,
) {
    suspend fun readCount(): Int? = try {
        currentCoroutineContext().ensureActive()
        val generation = epoch()
        fun active() = authenticated() && epoch() == generation
        if (!active()) null else {
            val account = currentAccount()
            if (account == null || !active()) null else {
                val rows = selections(account)
                if (!active() || rows.any { it.scope.account != account } || rows.map { it.scope }.distinct().size != rows.size) null else {
                    var total = 0L
                    for (row in rows) {
                        currentCoroutineContext().ensureActive()
                        if (!active()) return null
                        val pending = creates(row.scope)
                        if (!active() || pending < 0) return null
                        total += row.retainedLocalChangeCount.toLong() + pending
                        if (total > Int.MAX_VALUE) return null
                    }
                    val latest = currentAccount()
                    currentCoroutineContext().ensureActive()
                    if (active() && latest == account) total.toInt() else null
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
}

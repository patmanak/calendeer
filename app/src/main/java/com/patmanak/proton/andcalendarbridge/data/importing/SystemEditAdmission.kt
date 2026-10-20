package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import java.util.concurrent.atomic.AtomicBoolean

/** Local worker admission only; neither method consults the authenticated adapter.
 * Live readers belong to the coordinator. Each capture freezes a new invocation's
 * original epoch/account; a rejected callback remains closed after restoration. */
internal class SystemEditAdmission(
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val currentAccount: () -> AccountId?,
    private val authenticated: () -> Boolean,
    private val hasPermissions: () -> Boolean,
    private val createsEnabled: (CalendarScope) -> Boolean,
    private val selected: (CalendarScope) -> Boolean,
) {
    fun captureRun(target: CalendarScope, consent: Boolean, sendingEnabled: (CalendarScope) -> Boolean): (CalendarScope) -> Boolean {
        val expected = generation()
        val policy = policyRevision()
        val account = currentAccount()
        val initiallyAuthenticated = authenticated()
        val closed = AtomicBoolean(false)
        return { requested ->
            val allowed = !closed.get() && requested == target && initiallyAuthenticated && authenticated() &&
                generation() == expected && policyRevision() == policy &&
                account == target.account && currentAccount() == account &&
                hasPermissions() && createsEnabled(target) && (!consent || sendingEnabled(target)) &&
                selected(target) &&
                generation() == expected && policyRevision() == policy
            if (!allowed) closed.set(true)
            allowed && !closed.get()
        }
    }

    fun captureEvidence(target: CalendarScope): (CalendarScope) -> Boolean {
        val expected = generation()
        val account = currentAccount()
        val initiallyAuthenticated = authenticated()
        val closed = AtomicBoolean(false)
        return { requested ->
            val allowed = !closed.get() && requested == target && initiallyAuthenticated && authenticated() &&
                account == target.account && generation() == expected && currentAccount() == account
            if (!allowed) closed.set(true)
            allowed && !closed.get()
        }
    }
}

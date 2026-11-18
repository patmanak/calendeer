package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class GuestDeliveryProgressTest {
    private val owner = PersonalUpdateAttemptIdentity(CalendarScope(AccountId("fixture-account"), CalendarId("calendar"), 2),
        MutationId("whole"), MutationId("local"), EventId("event"), Revision("base"), AdapterFormat("guest", 1),
        AdapterFormat("guest-preparation", 1), "1".repeat(64), "2".repeat(64))
    private val binding = GuestDeliveryBinding.of(owner, "3".repeat(64), "4".repeat(64))
    private val readback = "5".repeat(64)

    @Test fun eachRestartPreservesUnknownAndOnlyAdvancesOnItsOwnEvidence() {
        fun restart(value: GuestDeliveryProgress) = requireNotNull(GuestDeliveryProgress.restore(binding, value.encode()))
        val ready = restart(GuestDeliveryProgress.prepared(binding))
        assertNull(ready.claimCalendar())
        val uncertain = restart(requireNotNull(ready.claimMail()))
        assertNull(uncertain.claimMail())
        assertNull(uncertain.claimCalendar())
        assertNull(uncertain.calendarVerified(binding, readback))
        val mail = restart(requireNotNull(uncertain.mailAccepted(binding, 1234)))
        assertNull(mail.claimMail())
        assertEquals(1234L, mail.deliveryTime)
        val calendar = restart(requireNotNull(mail.claimCalendar()))
        assertNull(calendar.claimCalendar())
        assertNull(calendar.mailAccepted(binding, 1235))
        val complete = restart(requireNotNull(calendar.calendarVerified(binding, readback)))
        assertEquals(GuestDeliveryPhase.BOTH_PROVEN, complete.phase)
        assertEquals(readback, complete.calendarProofHash)
        assertEquals(1234L, complete.deliveryTime)
        assertNull(complete.claimMail())
    }

    @Test fun capsuleAndLateResponsesMustMatchFrozenOwnershipAndBothRequests() {
        val uncertain = requireNotNull(GuestDeliveryProgress.prepared(binding).claimMail())
        val bytes = uncertain.encode()
        val changed = listOf(
            GuestDeliveryBinding.of(owner.copy(scope = owner.scope.copy(generation = 3)), "3".repeat(64), "4".repeat(64)),
            GuestDeliveryBinding.of(owner.copy(baseRevision = Revision("new")), "3".repeat(64), "4".repeat(64)),
            GuestDeliveryBinding.of(owner, "9".repeat(64), "4".repeat(64)),
            GuestDeliveryBinding.of(owner, "3".repeat(64), "9".repeat(64)))
        for (other in changed) {
            assertNull(GuestDeliveryProgress.restore(other, bytes))
            assertNull(uncertain.mailAccepted(other, 1234))
        }
        assertNull(GuestDeliveryProgress.restore(binding, bytes.copyOf(bytes.size - 1)))
        assertNull(GuestDeliveryProgress.restore(binding, bytes + 0))
        assertNull(GuestDeliveryProgress.restore(binding, bytes.clone().apply { this[0] = 9 }))
        val formerReady = GuestDeliveryProgress.prepared(binding).encode()
        val changedPhase = bytes.clone()
        val firstChange = bytes.indices.first { bytes[it] != formerReady[it] }
        changedPhase[firstChange] = formerReady[firstChange]
        assertNull(GuestDeliveryProgress.restore(binding, changedPhase))
        assertNull(uncertain.mailAccepted(binding, 0))
    }
}

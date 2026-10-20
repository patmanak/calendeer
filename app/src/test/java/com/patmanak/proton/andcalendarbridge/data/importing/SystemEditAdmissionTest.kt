package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import org.junit.Assert.*
import org.junit.Test

class SystemEditAdmissionTest {
    private class Fixture {
        val target = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
        var generation = 1L
        var policy = 1L
        var account: AccountId? = target.account
        var authenticated = true
        var permitted = true
        var creates = true
        var selected = true
        var text = true
        var time = true
        var onSelection: () -> Unit = {}
        val admissions = SystemEditAdmission(
            generation = { generation }, policyRevision = { policy }, currentAccount = { account },
            authenticated = { authenticated }, hasPermissions = { permitted }, createsEnabled = { creates },
            selected = { onSelection(); selected },
        )
        fun run() = admissions.captureRun(target, true) { text }
    }

    @Test fun workerEpochIsCapturedBeforeItsFirstUse() {
        val f = Fixture()
        val old = f.run()
        f.generation++
        f.policy++
        assertFalse(old(f.target))
        assertTrue(f.run()(f.target))
    }

    @Test fun observingPermissionLossPermanentlyClosesThatInvocation() {
        val f = Fixture()
        val old = f.run()
        assertTrue(old(f.target))
        f.permitted = false
        assertFalse(old(f.target))
        f.permitted = true
        assertFalse(old(f.target))
        assertTrue(f.run()(f.target))
    }

    @Test fun wrongCalendarClosesTheCallbackEvenWhenTheOriginalScopeReturns() {
        val f = Fixture()
        val old = f.run()
        assertFalse(old(f.target.copy(calendar = CalendarId("other-calendar"))))
        assertFalse(old(f.target))
        assertTrue(f.run()(f.target))
    }

    @Test fun textAndTimeConsentStayIndependentWhileProofStillRequiresCreateAccess() {
        val f = Fixture()
        val text = f.run()
        val time = f.admissions.captureRun(f.target, true) { f.time }
        val proof = f.admissions.captureRun(f.target, false) { error("Proof must not read sending consent") }
        f.text = false
        assertFalse(text(f.target))
        assertTrue(time(f.target))
        f.time = false
        assertFalse(time(f.target))
        assertTrue(proof(f.target))
        f.creates = false
        assertFalse(proof(f.target))
        f.creates = true
        assertFalse(proof(f.target))
    }

    @Test fun epochChangeDuringSelectionCannotAdmitWork() {
        val f = Fixture()
        val old = f.run()
        f.onSelection = { f.policy++ }
        assertFalse(old(f.target))
        f.onSelection = {}
        assertFalse(old(f.target))
        assertTrue(f.run()(f.target))
    }

    @Test fun factualEvidenceSurvivesEditingRevocationButNeverObservedLogout() {
        val f = Fixture()
        val evidence = f.admissions.captureEvidence(f.target)
        f.policy++
        f.permitted = false
        f.creates = false
        f.selected = false
        f.text = false
        assertTrue(evidence(f.target))
        f.authenticated = false
        assertFalse(evidence(f.target))
        f.authenticated = true
        assertFalse(evidence(f.target))
        assertTrue(f.admissions.captureEvidence(f.target)(f.target))
    }

    @Test fun evidenceCannotBorrowAuthenticationEstablishedAfterCapture() {
        val f = Fixture()
        f.authenticated = false
        val evidence = f.admissions.captureEvidence(f.target)
        f.authenticated = true
        assertFalse(evidence(f.target))
        assertTrue(f.admissions.captureEvidence(f.target)(f.target))
    }

    @Test fun evidenceCannotFollowAnAccountReplacementAndReturn() {
        val f = Fixture()
        val evidence = f.admissions.captureEvidence(f.target)
        f.account = AccountId("replacement-account")
        assertFalse(evidence(f.target))
        f.account = f.target.account
        assertFalse(evidence(f.target))
        assertTrue(f.admissions.captureEvidence(f.target)(f.target))
    }
}

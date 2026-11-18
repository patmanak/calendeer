package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.protonauth.ProtonGuestDirectoryProbe.Status
import me.proton.core.key.domain.entity.key.*
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestDirectoryKindTest {
    private val email = "guest@example.test"
    private val key = PublicAddressKey(email, 3, PublicKey("fixture", true, true, true, true))
    private val direct = PublicAddressKeyData(listOf(key))
    private val empty = PublicAddressInfo(email, PublicAddressKeyData(emptyList()), warnings = emptyList(),
        protonMx = false, isProton = 0)

    @Test fun usesMailRecipientTypeAndObservedKeySourcesIndependently() {
        assertEquals(Status.INTERNAL_KEY_OBSERVED, guestDirectoryKind(empty.copy(address = direct), Recipient.Internal, true))
        assertEquals(Status.INTERNAL_NO_ADDRESS_KEY, guestDirectoryKind(empty, Recipient.Internal, false))
        assertEquals(Status.INTERNAL_CATCH_ALL, guestDirectoryKind(empty.copy(catchAll = direct), Recipient.Internal, true))
        assertEquals(Status.EXTERNAL_WITHOUT_DIRECTORY_KEY, guestDirectoryKind(empty.copy(isProton = 1), Recipient.External, false))
        val wkd = PublicAddressKeyData(listOf(key.copy(source = PublicAddressKeySource.Wkd)))
        assertEquals(Status.EXTERNAL_WITH_KEY, guestDirectoryKind(empty.copy(unverified = wkd), Recipient.External, true))
    }

    @Test fun failuresAndConflictsCannotBeReportedAsExternalWithNoKey() {
        assertEquals(Status.RECIPIENT_TYPE_UNAVAILABLE, guestDirectoryKind(empty, null, false))
        assertEquals(Status.UNSUPPORTED_KEY_SET, guestDirectoryKind(empty.copy(warnings = listOf("fixture")), Recipient.External, false))
        assertEquals(Status.DIRECTORY_DISAGREEMENT, guestDirectoryKind(empty.copy(address = direct), Recipient.External, false))
    }
}

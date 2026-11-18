package com.patmanak.proton.andcalendarbridge.protonauth

import me.proton.core.key.domain.entity.key.*
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class ProtonGuestMailRecipientTest {
    private val email = "guest@example.test"
    private val key = PublicAddressKey(email, 3, PublicKey("synthetic-public-key", true, true, true, true))
    private val address = PublicAddress(email, Recipient.Internal.value, null, listOf(key), null, null)
    private val defaults = GuestContactPreferences.DEFAULTS
    private fun resolve(candidate: PublicAddress? = address, prefs: GuestContactPreferences = defaults,
        mime: String? = "text/plain") = internalGuestMailRecipient(email, candidate, prefs, mime)

    @Test fun resolvesExactPrimaryInternalRecipientFromKnownDefaults() {
        val selected = requireNotNull(resolve())
        assertEquals(email, selected.address)
        assertEquals(key.publicKey, selected.key)
        assertFalse(selected.toString().contains(email))
        assertFalse(selected.toString().contains(key.publicKey.key))
    }

    @Test fun unqualifiedContactIdentityOrKeyCannotFallBackToMail() {
        assertNull(resolve(null))
        assertNull(resolve(address.copy(email = "other@example.test")))
        assertNull(resolve(address.copy(recipientType = Recipient.External.value)))
        assertNull(resolve(address.copy(keys = emptyList())))
        assertNull(resolve(address.copy(keys = listOf(key, key))))
        for (invalid in listOf(key.copy(email = "other@example.test"), key.copy(flags = 1), key.copy(flags = 2),
            key.copy(flags = 3 or KeyFlags.EmailNoEncrypt), key.copy(flags = 3 or KeyFlags.EmailNoSign),
            key.copy(source = PublicAddressKeySource.Wkd),
            key.copy(publicKey = key.publicKey.copy(isActive = false)), key.copy(publicKey = key.publicKey.copy(canEncrypt = false)))) {
            assertNull(resolve(address.copy(keys = listOf(invalid))))
        }
        assertNull(resolve(prefs = GuestContactPreferences.UNRESOLVED))
        assertNull(resolve(prefs = GuestContactPreferences.CUSTOM))
        assertNull(resolve(mime = null))
    }
}

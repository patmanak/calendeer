package com.patmanak.proton.andcalendarbridge.protonauth

import me.proton.core.key.domain.entity.key.*

internal enum class GuestContactPreferences { UNRESOLVED, DEFAULTS, CUSTOM }

internal class InternalGuestMailRecipient internal constructor(val address: String, val key: PublicKey) {
    override fun toString() = "InternalGuestMailRecipient(redacted)"
}

/** The caller must obtain current settings, verified public address and contact preference for this mailbox. */
@Suppress("DEPRECATION")
internal fun internalGuestMailRecipient(email: String, address: PublicAddress?,
    preferences: GuestContactPreferences, draftMimeType: String?): InternalGuestMailRecipient? {
    if (preferences != GuestContactPreferences.DEFAULTS || draftMimeType !in setOf("text/plain", "text/html") ||
        address == null || address.email != email || address.recipient != Recipient.Internal) return null
    val candidate = address.keys.singleOrNull { it.publicKey.isPrimary } ?: return null
    val key = candidate.publicKey
    if (candidate.email != email || candidate.source?.let { it != PublicAddressKeySource.Internal } == true ||
        !candidate.canEncryptEmail() || !candidate.canVerifyEmail() || !key.isActive || !key.canEncrypt ||
        !key.canVerify || key.key.isBlank()) return null
    return InternalGuestMailRecipient(email, key)
}

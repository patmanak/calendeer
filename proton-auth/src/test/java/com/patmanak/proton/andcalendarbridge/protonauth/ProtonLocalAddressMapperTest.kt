package com.patmanak.proton.andcalendarbridge.protonauth

import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.user.data.entity.AddressEntity
import me.proton.core.user.data.entity.AddressKeyEntity
import me.proton.core.user.data.entity.AddressWithKeys
import me.proton.core.user.domain.entity.AddressId
import org.junit.Assert.*
import org.junit.Test

class ProtonLocalAddressMapperTest {
    @Test fun pinnedMapperRestoresProtectedPassphraseWithoutPromotingLockedOrInactiveKeys() {
        val addressId = AddressId("synthetic-address")
        val passphrase = EncryptedByteArray(byteArrayOf(9, 8, 7))
        val key = AddressKeyEntity(addressId = addressId, keyId = KeyId("synthetic-key"), version = 1,
            privateKey = "synthetic-armored-key", isPrimary = true, isUnlockable = true, flags = 3,
            passphrase = passphrase, token = null, signature = null, fingerprint = "synthetic",
            fingerprints = emptyList(), activation = null, active = true)
        val entity = AddressEntity(userId = UserId("synthetic-user"), addressId = addressId,
            email = "synthetic@example.invalid", displayName = null, signature = null, domainId = null,
            canSend = true, canReceive = true, enabled = true, type = null, order = 0, signedKeyList = null)
        fun mapped(candidate: AddressKeyEntity) = ProtonLocalAddressMapper.map(AddressWithKeys(entity, listOf(candidate))).keys.single().privateKey
        assertEquals(passphrase, mapped(key).passphrase)
        assertTrue(mapped(key).isActive)
        assertFalse(mapped(key.copy(passphrase = null)).isActive)
        assertFalse(mapped(key.copy(isUnlockable = false)).isActive)
        assertFalse(mapped(key.copy(active = false)).isActive)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.user.domain.entity.*
import org.junit.Assert.*
import org.junit.Test

class ProtonCreateContextTest {
    private val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    private val metadata = json("""{"ID":"synthetic-calendar","Type":0}""")
    private val bootstrap = json("""{"Passphrase":{"ID":"pass","CalendarID":"synthetic-calendar","Flags":1,
      "MemberPassphrases":[{"MemberID":"member","Passphrase":"protected","Signature":"signature"}]},
      "Members":[{"ID":"member","CalendarID":"synthetic-calendar","AddressID":"address","Flags":1,"Permissions":16}],
      "Keys":[{"ID":"primary","CalendarID":"synthetic-calendar","PassphraseID":"pass","Flags":3,"PrivateKey":"encrypted"},
      {"ID":"secondary","CalendarID":"synthetic-calendar","PassphraseID":"old-pass","Flags":1,"PrivateKey":"old-encrypted"}]}""")
    private val addressId = AddressId("address")
    private val key = PrivateKey("protected-private", isPrimary = true, passphrase = EncryptedByteArray(byteArrayOf(1)))
    private val address = UserAddress(UserId(scope.account.value), addressId, "synthetic@example.invalid",
        canSend = true, canReceive = true, enabled = true, order = 0,
        keys = listOf(UserAddressKey(addressId, 1, 3, active = true, keyId = KeyId("signer"), privateKey = key)), signedKeyList = null)

    @Test fun writableSharedMemberSelectsPrimaryAndRetainsSecondaryReadKeys() {
        val selected = ProtonCreateContextPolicy.select(scope, metadata, bootstrap, listOf(address))
        assertEquals("primary", selected.calendarKey.string("ID"))
        assertTrue(selected.signingKey === key)
        assertFalse(selected.toString().contains("member"))
    }

    @Test fun readOnlyAdminDisabledAndUnknownFlagsNeverInferWritePermission() {
        for (permission in listOf(0, 2, 4, 32, -1, 256)) rejected(CreatePreparationFailure.PERMISSION) {
            select(member("Permissions", JsonPrimitive(permission)))
        }
        for (flags in listOf(0, 2, 4, 8, 16, 32, 64, 129)) rejected(CreatePreparationFailure.PERMISSION) {
            select(member("Flags", JsonPrimitive(flags)))
        }
        rejected(CreatePreparationFailure.PERMISSION) { select(addresses = listOf(address.copy(enabled = false))) }
        rejected(CreatePreparationFailure.PERMISSION) { select(addresses = listOf(address.copy(canSend = false))) }
    }

    @Test fun foreignMissingAndAmbiguousOwnedMembersOrPrimaryKeysAreRefused() {
        rejected(CreatePreparationFailure.CONTEXT) { select(meta = metadata + ("Type" to JsonPrimitive(1))) }
        rejected(CreatePreparationFailure.CONTEXT) { select(meta = metadata + ("ID" to JsonPrimitive("other"))) }
        rejected(CreatePreparationFailure.CONTEXT) { select(addresses = listOf(address.copy(userId = UserId("other")))) }
        rejected(CreatePreparationFailure.CONTEXT) { select(member("AddressID", JsonNull)) }
        rejected(CreatePreparationFailure.CONTEXT) { select(member("CalendarID", JsonPrimitive("other"))) }
        val duplicate = JsonObject(bootstrap + ("Members" to JsonArray(listOf(bootstrap.array("Members").single(), bootstrap.array("Members").single()))))
        rejected(CreatePreparationFailure.CONTEXT) { select(duplicate) }
        val alternate = JsonObject((bootstrap.array("Members").single() as JsonObject) + ("ID" to JsonPrimitive("other-member")))
        rejected(CreatePreparationFailure.CONTEXT) { select(JsonObject(bootstrap + ("Members" to JsonArray(listOf(bootstrap.array("Members").single(), alternate))))) }
        val keys = bootstrap.array("Keys").map { it as JsonObject }
        fun withKeys(values: List<JsonObject>) = JsonObject(bootstrap + ("Keys" to JsonArray(values)))
        rejected(CreatePreparationFailure.LOCAL_KEYS) {
            select(withKeys(keys.map { JsonObject(it + ("Flags" to JsonPrimitive(1))) }))
        }
        rejected(CreatePreparationFailure.LOCAL_KEYS) {
            select(withKeys(keys.map { JsonObject(it + ("Flags" to JsonPrimitive(3))) }))
        }
        rejected(CreatePreparationFailure.CONTEXT) {
            select(withKeys(listOf(JsonObject(keys[0] + ("PassphraseID" to JsonPrimitive("other"))), keys[1])))
        }
    }

    @Test fun localSigningKeyMustBeUniquePrimaryActiveAndUsable() {
        rejected(CreatePreparationFailure.LOCAL_KEYS) { select(addresses = listOf(address.copy(keys = emptyList()))) }
        rejected(CreatePreparationFailure.LOCAL_KEYS) { select(addresses = listOf(address.copy(keys = address.keys + address.keys))) }
        rejected(CreatePreparationFailure.LOCAL_KEYS) {
            select(addresses = listOf(address.copy(keys = listOf(address.keys.single().copy(privateKey = key.copy(isActive = false))))))
        }
        rejected(CreatePreparationFailure.LOCAL_KEYS) {
            select(addresses = listOf(address.copy(keys = listOf(address.keys.single().copy(privateKey = key.copy(isPrimary = false))))))
        }
    }

    private fun member(field: String, value: JsonElement) = JsonObject(bootstrap + ("Members" to JsonArray(listOf(
        JsonObject((bootstrap.array("Members").single() as JsonObject) + (field to value))))))
    private fun select(value: JsonObject = bootstrap, addresses: List<UserAddress> = listOf(address), meta: Map<String, JsonElement> = metadata) =
        ProtonCreateContextPolicy.select(scope, JsonObject(meta), value, addresses)
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun rejected(reason: CreatePreparationFailure, action: () -> Any) {
        try { action(); fail("Invalid context must not select keys") }
        catch (failure: CreatePreparationException) { assertEquals(reason, failure.reason); assertNull(failure.cause) }
    }
}

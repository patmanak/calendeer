package com.patmanak.proton.andcalendarbridge.protonauth

import java.lang.reflect.Proxy
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import org.junit.Assert.*
import org.junit.Test

class ProtonEventPayloadProtectorTest {
    @Test fun keystoreFallbackNeverReceivesPlaintextAndAvailabilityIsRecheckedOnEveryOperation() {
        var available = true
        var encryptCalls = 0
        var decryptCalls = 0
        val core = Proxy.newProxyInstance(KeyStoreCrypto::class.java.classLoader, arrayOf(KeyStoreCrypto::class.java)) { _, method, _ ->
            when (method.name) {
                "isUsingKeyStore" -> available
                "encrypt" -> { encryptCalls++; "synthetic-protected" }
                "decrypt" -> { decryptCalls++; "synthetic-plain" }
                else -> error("Unexpected method")
            }
        } as KeyStoreCrypto
        val protector = ProtonEventPayloadProtector(core)
        assertEquals("synthetic-protected", protector.seal("synthetic-plain"))
        assertEquals("synthetic-plain", protector.open("synthetic-protected"))
        available = false
        for (operation in listOf<() -> String>({ protector.seal("synthetic-plain") }, { protector.open("synthetic-protected") })) {
            try { operation(); fail("Core plaintext fallback must not run") }
            catch (_: GateCProtectedStorageUnavailable) { }
        }
        assertEquals(1, encryptCalls)
        assertEquals(1, decryptCalls)
    }
}

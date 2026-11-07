// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.publicKey
import me.proton.core.key.domain.signText
import me.proton.core.key.domain.verifyText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against main sources only, including the externally signed release APK.
 * Fresh synthetic material; no session, service, existing calendar or debug probe. */
@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeDeviceTest {
    @Test fun nativePgpAndAndroidKeystoreExecuteFromTheInstalledArtifact() = runBlocking {
        val crypto = AndroidCryptoContext()
        val pgp = crypto.pgpCrypto
        val secret = pgp.generateNewToken()
        val clear = "Synthetic release runtime round trip".toByteArray(Charsets.UTF_8)
        try {
            val armored = pgp.generateNewPrivateKey("release-fixture", "example.invalid", secret)
            val key = PrivateKey(armored, true, passphrase = PlainByteArray(secret).encrypt(crypto.keyStoreCrypto))
            val signature = key.signText(crypto, "Synthetic release signature")
            assertTrue(key.publicKey(crypto).verifyText(crypto, "Synthetic release signature", signature))
            assertFalse(key.publicKey(crypto).verifyText(crypto, "Changed release signature", signature))
            pgp.generateNewSessionKey().use { session ->
                val encrypted = pgp.encryptData(clear, session)
                val reopened = pgp.decryptData(encrypted, session)
                try { assertArrayEquals(clear, reopened) } finally { reopened.fill(0); encrypted.fill(0) }
            }
        } finally { secret.fill(0); clear.fill(0) }
    }
}

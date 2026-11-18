package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonGuestDirectoryProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestDirectoryDeviceTest {
    @Test fun inspectMailSettingsAndEnrolledGuestKeyWithoutMailOrContacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guestDirectory") == "true")
        val runner = InstrumentationRegistry.getInstrumentation()
        val app = runner.targetContext.applicationContext as BridgeApplication
        val fixture = app.filesDir.resolve("guest-directory-recipient")
        require(fixture.isFile && fixture.length() in 3..256)
        val recipient = fixture.readText().trimEnd('\n', '\r')
        require(fixture.delete())
        withTimeout(60_000) { app.authentication.state.first { it.destination == AuthenticationDestination.AUTHENTICATED } }
        val preferences = args.getString("guestPreferences") == "true"
        val result = ProtonGuestDirectoryProbe.inspect(app, args.getString("createAccountHash").orEmpty(), recipient, preferences)
        runner.sendStatus(0, Bundle().apply { putString("guest_directory", result.name) })
        assertTrue(result !in setOf(ProtonGuestDirectoryProbe.Status.ENROLLMENT_UNAVAILABLE,
            ProtonGuestDirectoryProbe.Status.SETTINGS_UNAVAILABLE, ProtonGuestDirectoryProbe.Status.KEYS_UNAVAILABLE,
            ProtonGuestDirectoryProbe.Status.RECIPIENT_TYPE_UNAVAILABLE, ProtonGuestDirectoryProbe.Status.CANONICAL_UNAVAILABLE,
            ProtonGuestDirectoryProbe.Status.CONTACT_PREFERENCES_UNAVAILABLE))
    }
}

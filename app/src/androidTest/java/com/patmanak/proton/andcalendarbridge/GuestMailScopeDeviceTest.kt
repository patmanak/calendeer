package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonGuestScopeProbe
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestMailScopeDeviceTest {
    @Test fun inspectEnrolledCachedMailScopeWithoutNetwork() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guestMailScope") == "true")
        val runner = InstrumentationRegistry.getInstrumentation()
        val result = ProtonGuestScopeProbe.inspect(runner.targetContext, args.getString("createAccountHash").orEmpty())
        runner.sendStatus(0, Bundle().apply { putString("guest_mail_scope", result.name) })
        assertNotEquals(ProtonGuestScopeProbe.Result.UNAVAILABLE, result)
    }
}

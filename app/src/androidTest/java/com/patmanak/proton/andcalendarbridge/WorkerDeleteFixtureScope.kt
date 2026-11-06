// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import java.security.MessageDigest
import org.junit.Assert.assertTrue

/** Optional private enrollment for a different controlled, fully audited calendar.
 * This selector never grants account access, consent or a quiescence exemption.
 */
internal object WorkerDeleteFixtureScope {
    fun witness(args: Bundle): String = args.getString("deleteWorkerCalendarHash").orEmpty().also {
        assertTrue("An alternate calendar requires an explicit SHA-256 witness", it.isEmpty() || it.matches(Regex("[a-f0-9]{64}")))
    }

    fun matches(selection: ImportStore.Selection, witness: String): Boolean = selection.enabled && selection.available &&
        if (witness.isEmpty()) selection.summary.title == "Calendeer QA Automatic Worker"
        else MessageDigest.getInstance("SHA-256").digest(selection.scope.calendar.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) } == witness
}

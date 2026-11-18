// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*

object ProtonScopedOccurrenceProbe {
    fun submission(context: Context, intent: LocalCreateIntent, attempt: MutationId, accountHash: String): CalendarCreateSubmissionPort =
        ProtonAuthentication.shared(context).occurrenceQualification.forAttempt(intent, attempt, accountHash)
}

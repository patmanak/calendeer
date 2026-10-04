// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.experiments

import com.patmanak.proton.andcalendarbridge.sync.EventRecurrence
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.FiniteDailyRecurrenceProjection

/** Debug fixture entry point. The extracted pure policy has no runtime import caller. */
internal object FiniteDailyRecurrenceExperiment {
    fun plan(time: EventTime, recurrence: EventRecurrence): FiniteDailyRecurrenceProjection.Schedule =
        FiniteDailyRecurrenceProjection.plan(time, recurrence)
}

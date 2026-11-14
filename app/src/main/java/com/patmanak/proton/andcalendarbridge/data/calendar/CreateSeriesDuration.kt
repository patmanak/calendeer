// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import java.time.Duration

/** Bounded native duration profile; the raw value remains in the Provider assertion. */
internal fun seriesCreateEnd(startMillis: Long, raw: String, allDay: Boolean): Long? = try {
    val shape = if (allDay) "P[0-9]+D" else "PT(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+S)?"
    if (raw.length !in 1..40 || !raw.matches(Regex(shape))) null else {
        val duration = Duration.parse(raw)
        if (duration.nano != 0 || duration.seconds !in 1L..31_622_400L) null
        else Math.addExact(startMillis, duration.toMillis())
    }
} catch (_: java.time.DateTimeException) { null }
  catch (_: ArithmeticException) { null }

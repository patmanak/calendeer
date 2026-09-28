package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.transientStorageFailure
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure

/** Retry environmental failures, never permissions, ownership conflicts or corrupt schemas. */
internal fun retryableImportFailure(failure: Exception): Boolean = when {
    transientStorageFailure(failure) -> true
    failure is CalendarProjectionException -> failure.category == CalendarProjectionFailure.PROVIDER_UNAVAILABLE ||
        failure.category == CalendarProjectionFailure.CALENDAR_MISSING
    else -> false
}

package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.sync.EventId

enum class SystemCalendarChangeKind { NEW, IMPORTED, CAPTURED, UNSUPPORTED }
data class SystemCalendarChange(val row: Long, val kind: SystemCalendarChangeKind,
    val event: EventId? = null, val deleted: Boolean = false, val nativeKey: String? = null) {
    override fun toString() = "SystemCalendarChange(REDACTED)"
}
data class SystemCalendarChangePage(val changes: List<SystemCalendarChange>, val afterRow: Long, val more: Boolean) {
    override fun toString() = "SystemCalendarChangePage(REDACTED)"
}

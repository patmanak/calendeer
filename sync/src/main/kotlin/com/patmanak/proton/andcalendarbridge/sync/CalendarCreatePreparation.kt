package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Stable correlation and frozen timestamp must come from a future durable outgoing intent. */
data class CalendarCreateDraft(val scope: CalendarScope, val correlation: MutationId,
    val timestamp: Instant, val content: EventContent) {
    override fun toString() = "CalendarCreateDraft(redacted)"
}

/** Opaque protected preparation, not admission to dispatch or a remote acknowledgement. */
class PreparedCalendarCreate(val scope: CalendarScope, val format: AdapterFormat, val protectedPayload: String) {
    override fun toString() = "PreparedCalendarCreate(version=${format.version}, redacted)"
}

enum class CreatePreparationFailure { AUTHENTICATION, PERMISSION, CONTEXT, LOCAL_KEYS, UNSUPPORTED, STORAGE, NETWORK, RETRY }
class CreatePreparationException(val reason: CreatePreparationFailure) :
    IllegalStateException("Calendar create preparation: ${reason.name}")

/**
 * Preparation may refresh identity/calendar context using reads. It MUST NOT create events, change
 * account attributes, set up keys or advertise writable capability. Validate scope/session throughout;
 * keep keys, protocol identifiers and serialization inside the adapter. A prepared blob cannot be
 * dispatched without durable admission, fresh rights/context validation and outcome reconciliation.
 */
fun interface CalendarCreatePreparationPort {
    suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate
}

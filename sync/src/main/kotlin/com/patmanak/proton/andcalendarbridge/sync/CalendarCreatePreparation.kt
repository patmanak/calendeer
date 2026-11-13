package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Stable correlation and frozen timestamp must come from a future durable outgoing intent. */
data class CalendarCreateDraft(val scope: CalendarScope, val correlation: MutationId,
    val timestamp: Instant, val content: EventContent) {
    /** Own lists before the first suspension, including unsupported fields kept for validation. */
    fun frozen(): CalendarCreateDraft = copy(content = content.copy(details = content.details?.let {
        it.copy(reminders = java.util.Collections.unmodifiableList(it.reminders.toList()),
            attendees = java.util.Collections.unmodifiableList(it.attendees.toList()))
    }))
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
    /** Local declaration, stable during each operation, with no storage/network/crypto work. Null refuses captured known-empty
     * preparation. A changed format requires explicit reconciliation of cached protected payloads.
     * This capability never grants submission, personal notification support, or writable mode.
     */
    val localCreateFormat: AdapterFormat? get() = null
    val personalLocalCreateFormat: AdapterFormat? get() = null
    fun capturedPreparationFormat(content: EventContent): AdapterFormat? =
        if (content.details == EventDetails() && content.colorRgb == null) localCreateFormat else personalLocalCreateFormat
    suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate
}

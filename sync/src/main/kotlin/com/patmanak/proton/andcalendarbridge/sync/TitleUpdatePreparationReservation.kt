// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Durable identity before wire preparation, never permission to prepare or dispatch.
 * A restart may repeat read-only preparation with this identity while no wire exists;
 * the first durable wire remains immutable. The complete capsule and formats are frozen.
 */
data class TitleUpdatePreparationReservation(val id: MutationId,
    val preparation: LocalEditPreparation, val format: AdapterFormat) {
    val scope: CalendarScope get() = preparation.intent.scope
    val eventId: EventId get() = preparation.intent.base.eventId
    init {
        val intent = LocalEditPreparation.freezeIntent(preparation.intent)
        require(intent.base.content.details == EventDetails() && intent.base.content.colorRgb == null)
        listOf(id.value, format.id).forEach {
            require(it.isNotBlank() && it.length <= LocalEditPreparation.MAX_CONTEXT_BYTES)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(it)).remaining() <=
                LocalEditPreparation.MAX_CONTEXT_BYTES)
        }
    }
    fun frozen() = copy(preparation = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent)))
    /** Structural binding only, including UNKNOWN history; not an access or retry decision. */
    fun matches(record: TitleUpdateAttemptRecord): Boolean = frozen() == of(record)
    override fun toString() = "TitleUpdatePreparationReservation(redacted)"
    companion object {
        fun of(record: TitleUpdateAttemptRecord) = TitleUpdatePreparationReservation(record.id, record.preparation, record.format).frozen()
    }
}

/** Local identity recovery only. Unsupported journals deny without legacy fallback.
 * reserve returns the first canonical identity for an identical capsule/format, even if
 * the caller proposed another ID. Bare reservations hold the event across generations.
 * UNKNOWN is never returned as preparation admission. Every repetition checks the lease
 * through commit and full history. Reading grants no preparation or transport authority.
 */
interface TitleUpdatePreparationJournal : TitleUpdateAttemptJournal {
    suspend fun reservePreparation(candidate: TitleUpdatePreparationReservation,
        admit: (CalendarScope) -> Boolean): TitleUpdatePreparationReservation? = null
    suspend fun readPreparationReservation(scope: CalendarScope, eventId: EventId): TitleUpdatePreparationReservation? = null
}

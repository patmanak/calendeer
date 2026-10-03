// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneOffset

/** Frozen offline preparation for one retained title edit, never dispatch admission.
 * The journal binds the immutable intent; the opaque adapter payload remains independently sealed.
 * A PAUSED snapshot here is historical and does not replace the current intent's conflict state.
 */
data class LocalEditPreparation(val intent: LocalEditIntent, val timestamp: Instant,
    val format: AdapterFormat, val protectedPayload: String) {
    init {
        freezeIntent(intent)
        require(timestamp.nano == 0 && timestamp.atOffset(ZoneOffset.UTC).year in 1970..9999)
        bounded(format.id, MAX_CONTEXT_BYTES)
        require(protectedPayload.isNotBlank())
        bounded(protectedPayload, MAX_PAYLOAD_BYTES)
    }
    override fun toString() = "LocalEditPreparation(REDACTED)"
    companion object {
        const val MAX_PAYLOAD_BYTES = 4_194_304
        const val MAX_CONTEXT_BYTES = 4_096
        private fun bounded(value: String, limit: Int) {
            require(value.length <= limit)
            val size = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            require(size <= limit)
        }
        /** Snapshot the admitted empty-detail profile before adapter/storage suspension. */
        fun freezeIntent(intent: LocalEditIntent): LocalEditIntent {
            listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
                intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach {
                bounded(it, MAX_CONTEXT_BYTES)
            }
            require(intent.state == LocalEditState.PAUSED)
            require(intent.base.content.recurrence == null &&
                (intent.base.content.details == null || intent.base.content.details == EventDetails()))
            require(intent.content == intent.base.content.copy(title = intent.content.title))
            fun frozen(content: EventContent) = content.copy(details = content.details?.let { EventDetails() })
            return intent.copy(base = intent.base.copy(content = frozen(intent.base.content)), content = frozen(intent.content))
        }
    }
}

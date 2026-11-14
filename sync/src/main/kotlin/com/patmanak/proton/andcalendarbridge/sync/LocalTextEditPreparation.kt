// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Frozen offline text preparation only; never dispatch authority or an expanded title record.
 * The retained PAUSED observation does not replace the current local owner's state.
 */
class LocalTextEditPreparation(intent: LocalEditIntent, val timestamp: Instant,
    val format: AdapterFormat, val protectedPayload: String) {
    val intent: LocalEditIntent = freezeIntent(intent)
    init {
        require(timestamp.nano == 0 && timestamp >= Instant.EPOCH && timestamp <= LAST_TIMESTAMP)
        bounded(format.id, MAX_CONTEXT_BYTES)
        require(protectedPayload.isNotBlank())
        bounded(protectedPayload, MAX_PAYLOAD_BYTES)
    }
    override fun toString() = "LocalTextEditPreparation(REDACTED)"

    companion object {
        const val MAX_CONTEXT_BYTES = 4_096
        const val MAX_TEXT_BYTES = 65_536
        const val MAX_PAYLOAD_BYTES = 4_194_304

        private val LAST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z")

        private fun bounded(value: String, limit: Int) {
            require(value.length <= limit)
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            require(bytes <= limit)
        }

        private fun text(value: String) {
            // Keep the original bytes in the snapshot: normalization is only for the control check.
            bounded(value, MAX_TEXT_BYTES)
            require(value.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
        }

        /** Snapshot the supported text-only observation before adapter or storage suspension.
         * Empty details receive new immutable lists; legacy unknown details stay null.
         * A raw CRLF/LF difference remains an intent even if the adapter later prepares a no-op.
         */
        fun freezeIntent(intent: LocalEditIntent): LocalEditIntent {
            require(intent.seriesProfile == null)
            listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
                intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach {
                bounded(it, MAX_CONTEXT_BYTES)
            }
            require(intent.state == LocalEditState.PAUSED)
            val original = intent.base.content
            val replacement = intent.content
            require(original.recurrence == null && (original.details == null || original.details == EventDetails()))
            require(replacement == original.copy(title = replacement.title,
                description = replacement.description, location = replacement.location))
            listOf(original.title, original.description, original.location,
                replacement.title, replacement.description, replacement.location).forEach(::text)
            fun frozen(content: EventContent) = content.copy(details = content.details?.let { EventDetails() })
            return intent.copy(base = intent.base.copy(content = frozen(original)), content = frozen(replacement))
        }
    }
}

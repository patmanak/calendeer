// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.ICalVersion
import biweekly.component.VEvent
import biweekly.io.text.ICalWriter
import java.io.StringWriter
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal enum class ProtonSharedTextProperty { SUMMARY, DESCRIPTION, LOCATION }
internal data class ProtonSharedTextChange(val expected: String?, val replacement: String?) {
    override fun toString() = "ProtonSharedTextChange(REDACTED)"
}
internal class ProtonTextPatchException : IllegalStateException("Shared text patch unavailable: UNSUPPORTED")

/**
 * Offline plaintext experiment only. Not an authenticated envelope or an UPDATE preparation.
 * No keys, signatures, persistence, transport or writable capability.
 * Biweekly handles typed identity validation and new text serialization; untouched input is raw.
 * Framing, identity and selected text checks do not certify other properties as safe to upload.
 */
internal object ProtonSharedTextPatcher {
    /**
     * Explicit local metadata policy for a restricted plaintext layout, not a general Proton edit.
     * Retain CREATED and SEQUENCE; set both DTSTAMP values and clear LAST-MODIFIED.
     * Original signatures cannot be reused on modified shared parts; this is no uploadable envelope.
     * A no-op validates its binding and explicit time but preserves all original metadata unchanged.
     */
    fun patchAt(original: ProtonEventTextParts, changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>,
                expectedUid: String, timestamp: Instant): ProtonEventTextParts {
        try {
            val selected = selection(changes)
            require(timestamp.nano == 0 && timestamp.atOffset(ZoneOffset.UTC).year in 1970..9999)
            val patched = patch(original, selected, expectedUid)
            if (patched === original) return original

            val clear = metadata(patched.clearShared, clear = true)
            val secret = metadata(patched.encryptedShared, clear = false)
            val oldStamp = metadataDate(clear.getValue("DTSTAMP"))
            require(oldStamp == metadataDate(secret.getValue("DTSTAMP")))
            val created = metadataDate(clear.getValue("CREATED"))
            val lastModified = metadataDate(clear.getValue("LAST-MODIFIED"))
            require(created <= oldStamp && created <= lastModified)
            require(timestamp >= oldStamp && timestamp >= lastModified)
            val sequence = value(clear.getValue("SEQUENCE"))
            require(sequence.matches(Regex("[0-9]+")))
            // Leading zeros and folding are retained verbatim, even for an otherwise long value.
            val sequenceNumber = checkNotNull(sequence.trimStart('0').ifEmpty { "0" }.toLongOrNull())
            require(sequenceNumber in 0L..Int.MAX_VALUE.toLong())

            val stamp = STAMP.format(timestamp.atOffset(ZoneOffset.UTC))
            val nextClear = replaceDates(patched.clearShared,
                listOf(clear.getValue("DTSTAMP"), clear.getValue("LAST-MODIFIED")), stamp)
            val nextSecret = replaceDates(patched.encryptedShared, listOf(secret.getValue("DTSTAMP")), stamp)
            budget(nextClear, nextSecret)
            val checkedClear = inspect(nextClear, hasTitle = false, selected.keys)
            val checkedSecret = inspect(nextSecret, hasTitle = true, selected.keys)
            require(checkedClear.uid == expectedUid && checkedSecret.uid == expectedUid)
            require(checkedClear.stamp == stamp && checkedSecret.stamp == stamp)
            require(selected.all { (field, change) -> checkedSecret.values[field] == change.replacement })
            return ProtonEventTextParts(nextClear, nextSecret)
        } catch (_: Exception) {
            throw ProtonTextPatchException()
        }
    }

    fun patch(original: ProtonEventTextParts, changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>,
        expectedUid: String? = null): ProtonEventTextParts {
        try {
            val selected = selection(changes)
            budget(original.clearShared, original.encryptedShared)
            val clear = inspect(original.clearShared, hasTitle = false, selected.keys)
            val secret = inspect(original.encryptedShared, hasTitle = true, selected.keys)
            require(clear.uid == secret.uid && clear.stamp == secret.stamp)
            require(expectedUid == null || clear.uid == expectedUid)
            require(selected.all { (field, change) -> secret.values[field] == change.expected })
            val changed = selected.filterValues { it.expected != it.replacement }
            if (changed.isEmpty()) return original

            val edits = mutableListOf<Edit>()
            val additions = StringBuilder()
            // Enum order fixes insertion order independently of caller Map iteration order.
            for (field in ProtonSharedTextProperty.entries) {
                val change = changed[field] ?: continue
                val replacement = change.replacement?.let { serialize(field, it) }.orEmpty()
                val span = secret.spans[field]
                if (span == null) additions.append(replacement)
                else edits += Edit(span.start, span.end, replacement)
            }
            if (additions.isNotEmpty()) edits += Edit(secret.eventEnd, secret.eventEnd, additions.toString())
            var patched = original.encryptedShared
            for (edit in edits.sortedByDescending { it.start })
                patched = patched.substring(0, edit.start) + edit.replacement + patched.substring(edit.end)
            budget(original.clearShared, patched)
            val checked = inspect(patched, hasTitle = true, selected.keys)
            require(checked.uid == secret.uid && checked.stamp == secret.stamp)
            require(selected.all { (field, change) -> checked.values[field] == change.replacement })
            return ProtonEventTextParts(original.clearShared, patched)
        } catch (_: Exception) {
            // Parser errors and input values must never reach diagnostics or exception causes.
            throw ProtonTextPatchException()
        }
    }

    private fun selection(changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>): Map<ProtonSharedTextProperty, ProtonSharedTextChange> {
        val selected = changes.toMap()
        require(selected.isNotEmpty())
        return selected.mapValues { (field, change) ->
            if (field == ProtonSharedTextProperty.SUMMARY) require(change.expected != null && change.replacement != null)
            ProtonSharedTextChange(change.expected?.let(::text), change.replacement?.let(::text))
        }
    }

    /** Serialize a newly constructed property only, never an original component. */
    private fun serialize(field: ProtonSharedTextProperty, next: String): String {
        val output = StringWriter()
        ICalWriter(output, ICalVersion.V2_0).use { writer ->
            // Vinnie's width counts UTF-16 units. Retain the title path's conservative Unicode
            // width and enforce the actual UTF-8 octet limit, including whitespace exceptions.
            writer.vObjectWriter.foldedLineWriter.lineLength = if (next.any { it.code > 127 }) 24 else 75
            writer.write(ICalendar().apply { addEvent(VEvent().apply {
                when (field) {
                    ProtonSharedTextProperty.SUMMARY -> setSummary(next)
                    ProtonSharedTextProperty.DESCRIPTION -> setDescription(next)
                    ProtonSharedTextProperty.LOCATION -> setLocation(next)
                }
            }) })
        }
        val generated = output.toString()
        require(generated.split("\r\n").all { bytes(it) <= 75 })
        val block = blocks(generated).single { property(it.logical).first == field.name }
        require(property(block.logical).second == field.name)
        return generated.substring(block.start, block.end)
    }

    private data class Block(val start: Int, val end: Int, val logical: String, val folded: Boolean = false) {
        override fun toString() = "Block(REDACTED)"
    }
    private data class Edit(val start: Int, val end: Int, val replacement: String) {
        override fun toString() = "Edit(REDACTED)"
    }
    private data class Inspected(val uid: String, val stamp: String, val values: Map<ProtonSharedTextProperty, String?>,
        val spans: Map<ProtonSharedTextProperty, Block>, val eventEnd: Int) {
        override fun toString() = "Inspected(REDACTED)"
    }

    /** The caller has already validated framing; inspect only root/event metadata eligibility. */
    private fun metadata(text: String, clear: Boolean): Map<String, Block> {
        val stack = mutableListOf<String>()
        val selected = mutableMapOf<String, Block>()
        val required = if (clear) setOf("DTSTAMP", "CREATED", "LAST-MODIFIED", "SEQUENCE") else setOf("DTSTAMP")
        for (block in blocks(text)) {
            val (name, header) = property(block.logical)
            if (name == "BEGIN") { stack += value(block).uppercase(Locale.ROOT); continue }
            if (name == "END") { stack.removeAt(stack.lastIndex); continue }
            if (stack == listOf("VCALENDAR")) require(name != "METHOD")
            if (stack != listOf("VCALENDAR", "VEVENT")) continue
            require(name !in NON_SIMPLE_MARKERS)
            if (!clear) require(name !in setOf("CREATED", "LAST-MODIFIED", "SEQUENCE"))
            if (name in required) {
                require(header.equals(name, ignoreCase = true) && selected.put(name, block) == null)
            }
        }
        require(selected.keys == required)
        return selected
    }

    private fun value(block: Block): String = block.logical.substring(property(block.logical).second.length + 1)

    private fun metadataDate(block: Block): Instant {
        val date = value(block)
        require(date.matches(Regex("[0-9]{8}T[0-9]{6}Z")))
        return LocalDateTime.parse(date, STAMP).also { require(it.year in 1970..9999) }.toInstant(ZoneOffset.UTC)
    }

    private fun replaceDates(text: String, selected: List<Block>, stamp: String): String {
        var result = text
        for (block in selected.sortedByDescending { it.start }) {
            val header = property(block.logical).second
            result = result.substring(0, block.start) + header + ":" + stamp + "\r\n" + result.substring(block.end)
        }
        return result
    }

    /** Offsets and framing only: opaque values and components are never mapped or rewritten. */
    private fun blocks(text: String): List<Block> {
        require(text.endsWith("\r\n") && !text.startsWith('\uFEFF'))
        val result = mutableListOf<Block>()
        var offset = 0
        var lines = 0
        var start = 0
        var blockEnd = 0
        var folded = false
        val logical = StringBuilder()
        fun flush() {
            if (logical.isNotEmpty()) result += Block(start, blockEnd, logical.toString(), folded)
        }
        while (offset < text.length) {
            require(++lines <= MAX_LINES)
            val end = text.indexOf("\r\n", offset)
            require(end >= offset)
            val line = text.substring(offset, end)
            require(line.isNotEmpty() && line.none { it.isISOControl() && it != '\t' })
            if (line.first() == ' ' || line.first() == '\t') {
                require(logical.isNotEmpty())
                logical.append(line.substring(1))
                folded = true
            } else {
                flush()
                logical.setLength(0)
                logical.append(line)
                start = offset
                folded = false
            }
            blockEnd = end + 2
            offset = end + 2
        }
        flush()
        return result
    }

    /** Locate the first value delimiter outside quoted parameters, keeping their bytes opaque. */
    private fun property(line: String): Pair<String, String> {
        var quoted = false
        var delimiter = -1
        for (index in line.indices) {
            if (line[index] == '"') quoted = !quoted
            if (line[index] == ':' && !quoted) { delimiter = index; break }
        }
        require(delimiter > 0)
        val header = line.substring(0, delimiter)
        val name = header.substringBefore(';')
        require(name.matches(Regex("[A-Za-z0-9-]+")))
        return name.uppercase(Locale.ROOT) to header
    }

    private fun inspect(text: String, hasTitle: Boolean, selected: Set<ProtonSharedTextProperty>): Inspected {
        val stack = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val stamps = mutableListOf<String>()
        val summaries = mutableListOf<Block>()
        val spans = mutableMapOf<ProtonSharedTextProperty, Block>()
        var eventEnd = -1
        var calendars = 0
        var events = 0
        var versions = 0
        for (block in blocks(text)) {
            val (name, header) = property(block.logical)
            val value = block.logical.substring(header.length + 1)
            if (name == "BEGIN" || name == "END") {
                require(header.equals(name, ignoreCase = true) && !block.folded && value.matches(Regex("[A-Za-z0-9-]+")))
                val component = value.uppercase(Locale.ROOT)
                if (name == "BEGIN") {
                    if (component == "VCALENDAR") require(stack.isEmpty() && ++calendars == 1)
                    else require(stack.isNotEmpty())
                    if (component == "VEVENT") require(stack == listOf("VCALENDAR") && ++events == 1)
                    stack += component
                    require(stack.size <= MAX_DEPTH)
                } else {
                    require(stack.lastOrNull() == component)
                    if (stack == listOf("VCALENDAR", "VEVENT")) eventEnd = block.start
                    stack.removeAt(stack.lastIndex)
                }
                continue
            }
            require(stack.isNotEmpty())
            if (stack == listOf("VCALENDAR") && name == "VERSION") {
                require(header.equals(name, ignoreCase = true) && value == "2.0" && ++versions == 1)
            }
            if (stack == listOf("VCALENDAR", "VEVENT")) {
                when (name) {
                    "UID" -> { require(header.equals(name, ignoreCase = true)); ids += value }
                    "DTSTAMP" -> { require(header.equals(name, ignoreCase = true)); stamps += value }
                    "SUMMARY" -> { require(hasTitle && header.equals(name, ignoreCase = true)); summaries += block }
                }
                val field = selected.singleOrNull { it.name == name }
                if (field != null) {
                    require(hasTitle && header.equals(name, ignoreCase = true) && spans.put(field, block) == null)
                }
            }
        }
        require(stack.isEmpty() && calendars == 1 && events == 1 && versions == 1)
        require(ids.size == 1 && ids.single().isNotBlank() && ids.single().length <= 1024 && stamps.size == 1)
        require(summaries.size == if (hasTitle) 1 else 0)
        val stamp = stamps.single()
        require(stamp.matches(Regex("[0-9]{8}T[0-9]{6}Z")))
        val instant = LocalDateTime.parse(stamp, STAMP).also { require(it.year in 1970..9999) }.toInstant(ZoneOffset.UTC)
        val parsed = Biweekly.parse(text).all()
        require(parsed.size == 1 && parsed.single().events.size == 1)
        val event = parsed.single().events.single()
        require(event.uid?.value == ids.single() && event.dateTimeStamp?.value?.toInstant() == instant)
        require((event.summary != null) == hasTitle)
        val values = selected.associateWith { field ->
            val decoded = when (field) {
                ProtonSharedTextProperty.SUMMARY -> event.summary?.value
                ProtonSharedTextProperty.DESCRIPTION -> event.description?.value
                ProtonSharedTextProperty.LOCATION -> event.location?.value
            }
            // Presence is checked separately: an explicit empty property is not absence.
            require((field in spans) == (decoded != null))
            decoded?.replace("\r\n", "\n")
        }
        require(eventEnd >= 0)
        return Inspected(ids.single(), stamp, values, spans, eventEnd)
    }

    private fun text(value: String): String {
        val normalized = value.replace("\r\n", "\n")
        require(normalized.none { it.isISOControl() && it != '\n' && it != '\t' })
        require(bytes(normalized) <= MAX_TEXT_BYTES)
        return normalized
    }

    private fun bytes(value: String): Int {
        require(value.length <= MAX_BYTES)
        return Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
    }

    private fun budget(clear: String, secret: String) = require(bytes(clear).toLong() + bytes(secret) <= MAX_BYTES)
    private const val MAX_BYTES = 1_048_576
    private const val MAX_TEXT_BYTES = 65_536
    private const val MAX_LINES = 20_000
    private const val MAX_DEPTH = 16
    private val NON_SIMPLE_MARKERS = setOf("RRULE", "EXRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "ATTENDEE", "ORGANIZER", "STATUS", "METHOD")
    private val STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT)
}

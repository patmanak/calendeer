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

internal class ProtonTitlePatchException : IllegalStateException("Shared title patch unavailable: UNSUPPORTED")

/**
 * Offline plaintext experiment only. Not an authenticated envelope or an UPDATE preparation.
 * No keys, signatures, persistence, transport or writable capability.
 * Biweekly handles typed identity validation and new text serialization; untouched input is raw.
 * Framing, identity and title checks do not certify other properties as valid or safe to upload.
 */
internal object ProtonSharedTitlePatcher {
    /**
     * Explicit local metadata policy for a restricted plaintext layout, not a general Proton edit.
     * Retain CREATED and SEQUENCE; set both DTSTAMP values and clear LAST-MODIFIED.
     * Original signatures cannot be reused on modified shared parts; this is no uploadable envelope.
     * A no-op validates its binding and explicit time but preserves all original metadata unchanged.
     */
    fun patchAt(original: ProtonEventTextParts, expectedTitle: String, newTitle: String,
                expectedUid: String, timestamp: Instant): ProtonEventTextParts {
        try {
            require(timestamp.nano == 0 && timestamp.atOffset(ZoneOffset.UTC).year in 1970..9999)
            val patched = patch(original, expectedTitle, newTitle, expectedUid)
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
            val checkedClear = inspect(nextClear, hasTitle = false)
            val checkedSecret = inspect(nextSecret, hasTitle = true)
            require(checkedClear.uid == expectedUid && checkedSecret.uid == expectedUid)
            require(checkedClear.stamp == stamp && checkedSecret.stamp == stamp)
            require(checkedSecret.title == title(newTitle))
            return ProtonEventTextParts(nextClear, nextSecret)
        } catch (_: Exception) {
            throw ProtonTitlePatchException()
        }
    }

    fun patch(original: ProtonEventTextParts, expectedTitle: String, newTitle: String, expectedUid: String? = null): ProtonEventTextParts {
        try {
            budget(original.clearShared, original.encryptedShared)
            val expected = title(expectedTitle)
            val next = title(newTitle)
            val clear = inspect(original.clearShared, hasTitle = false)
            val secret = inspect(original.encryptedShared, hasTitle = true)
            require(clear.uid == secret.uid && clear.stamp == secret.stamp)
            require(expectedUid == null || clear.uid == expectedUid)
            require(secret.title == expected)
            if (next == expected) return original

            // Serialize only a synthetic SUMMARY block, never the original event or other parts.
            val output = StringWriter()
            ICalWriter(output, ICalVersion.V2_0).use { writer ->
                // Vinnie's default width counts UTF-16 units, not UTF-8 octets. Use a conservative
                // Unicode width and verify actual bytes; its whitespace exceptions can exceed it.
                writer.vObjectWriter.foldedLineWriter.lineLength = if (next.any { it.code > 127 }) 24 else 75
                writer.write(ICalendar().apply { addEvent(VEvent().apply { setSummary(next) }) })
            }
            val generated = output.toString()
            require(generated.split("\r\n").all { bytes(it) <= 75 })
            val summary = blocks(generated).single { property(it.logical).first == "SUMMARY" }
            require(property(summary.logical).second == "SUMMARY")
            val span = checkNotNull(secret.summary)
            val patched = original.encryptedShared.substring(0, span.start) +
                generated.substring(summary.start, summary.end) + original.encryptedShared.substring(span.end)
            budget(original.clearShared, patched)
            val checked = inspect(patched, hasTitle = true)
            require(checked.uid == secret.uid && checked.stamp == secret.stamp && checked.title == next)
            return ProtonEventTextParts(original.clearShared, patched)
        } catch (_: Exception) {
            // Parser errors and input values must never reach diagnostics or exception causes.
            throw ProtonTitlePatchException()
        }
    }

    private data class Block(val start: Int, val end: Int, val logical: String, val folded: Boolean = false)
    private data class Inspected(val uid: String, val stamp: String, val title: String?, val summary: Block?)

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

    private fun inspect(text: String, hasTitle: Boolean): Inspected {
        val stack = mutableListOf<String>()
        val ids = mutableListOf<String>()
        val stamps = mutableListOf<String>()
        val summaries = mutableListOf<Block>()
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
                    stack.removeAt(stack.lastIndex)
                }
                continue
            }
            require(stack.isNotEmpty())
            if (stack == listOf("VCALENDAR") && name == "VERSION") {
                require(header.equals(name, ignoreCase = true) && value == "2.0" && ++versions == 1)
            }
            if (stack == listOf("VCALENDAR", "VEVENT")) when (name) {
                "UID" -> { require(header.equals(name, ignoreCase = true)); ids += value }
                "DTSTAMP" -> { require(header.equals(name, ignoreCase = true)); stamps += value }
                "SUMMARY" -> { require(hasTitle && header.equals(name, ignoreCase = true)); summaries += block }
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
        return Inspected(ids.single(), stamp, event.summary?.value?.replace("\r\n", "\n"), summaries.singleOrNull())
    }

    private fun title(value: String): String {
        val normalized = value.replace("\r\n", "\n")
        require(normalized.none { it.isISOControl() && it != '\n' && it != '\t' })
        require(bytes(normalized) <= MAX_TITLE_BYTES)
        return normalized
    }

    private fun bytes(value: String): Int {
        require(value.length <= MAX_BYTES)
        return Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
    }

    private fun budget(clear: String, secret: String) = require(bytes(clear).toLong() + bytes(secret) <= MAX_BYTES)
    private const val MAX_BYTES = 1_048_576
    private const val MAX_TITLE_BYTES = 65_536
    private const val MAX_LINES = 20_000
    private const val MAX_DEPTH = 16
    private val NON_SIMPLE_MARKERS = setOf("RRULE", "EXRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "ATTENDEE", "ORGANIZER", "STATUS", "METHOD")
    private val STAMP = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT)
}

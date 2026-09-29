// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Proton Calendar ICalUtilsImpl/EditCreateEventUseCase; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.component.VTimezone
import biweekly.io.TimezoneAssignment
import biweekly.util.DateTimeComponents
import biweekly.util.ICalDate
import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.key.domain.encryptSessionKey
import me.proton.core.key.domain.signText
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PublicKey

internal enum class ProtonEventPreparationFailure { UNSUPPORTED_CONTENT, KEYS, CRYPTO }
internal class ProtonEventPreparationException(val reason: ProtonEventPreparationFailure) :
    IllegalStateException("Event preparation unavailable: ${reason.name}")

/** Ephemeral plaintext, not a persistable receipt. Never include its fields in diagnostics. */
internal class ProtonEventTextParts(val clearShared: String, val encryptedShared: String) {
    override fun toString() = "ProtonEventTextParts(redacted)"
}

/** CREATE only. Never rebuild an UPDATE from the lossy display model. No clock, network or key access. */
internal object ProtonSimpleEventSerializer {
    fun serialize(uid: String, timestamp: Instant, content: EventContent): ProtonEventTextParts {
        fun unsupported(): Nothing = throw ProtonEventPreparationException(ProtonEventPreparationFailure.UNSUPPORTED_CONTENT)
        try {
            if (!uid.matches(Regex("[A-Za-z0-9][A-Za-z0-9@._+-]{0,254}")) || timestamp.nano != 0 ||
                timestamp.atOffset(ZoneOffset.UTC).year !in MIN_YEAR..9999 || content.recurrence != null ||
                content.details != null || content.colorRgb != null) unsupported()
            val text = listOf(content.title, content.description, content.location)
                .map { it.replace("\r\n", "\n") }
            if (text.any { it.length > MAX_BYTES || '\u0000' in it ||
                    it.any { c -> c.isISOControl() && c != '\n' && c != '\t' } } ||
                text.sumOf { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(it)).remaining().toLong() } > MAX_BYTES) unsupported()
            val clear = VEvent().apply {
                setUid(uid); setDateTimeStamp(Date.from(timestamp)); setCreated(Date.from(timestamp))
                setLastModified(Date.from(timestamp)); setSequence(0)
            }
            val secret = VEvent().apply {
                setUid(uid); setDateTimeStamp(Date.from(timestamp))
                setSummary(text[0]); setDescription(text[1]); setLocation(text[2])
            }
            fun wrap(event: VEvent) = ICalendar().apply {
                setProductId("-//Calendeer//Calendar Bridge//EN"); addEvent(event)
            }
            val clearCalendar = wrap(clear)
            when (val time = content.time) {
                is EventTime.AllDay -> {
                    if (time.start.year !in MIN_YEAR..9999 || time.endExclusive.year !in MIN_YEAR..9999) unsupported()
                    fun date(value: LocalDate) = ICalDate(DateTimeComponents(value.year, value.monthValue, value.dayOfMonth), false)
                    clear.setDateStart(date(time.start), false); clear.setDateEnd(date(time.endExclusive), false)
                }
                is EventTime.Timed -> {
                    if (time.start.nano != 0 || time.end.nano != 0) unsupported()
                    val localStart = time.start.atZone(time.zone).toLocalDateTime()
                    val localEnd = time.end.atZone(time.zone).toLocalDateTime()
                    if (localStart.year !in MIN_YEAR..9999 || localEnd.year !in MIN_YEAR..9999 ||
                        time.zone.rules.getValidOffsets(localStart).size != 1 ||
                        time.zone.rules.getValidOffsets(localEnd).size != 1) unsupported()
                    clear.setDateStart(Date.from(time.start)); clear.setDateEnd(Date.from(time.end))
                    if (time.zone != ZoneOffset.UTC) {
                        // The official serializer uses Olson TZIDs with no embedded VTIMEZONE.
                        // Fixed offset names are a separate unsupported capability in this slice.
                        if (time.zone.id !in java.time.ZoneId.getAvailableZoneIds()) unsupported()
                        val javaZone = TimeZone.getTimeZone(time.zone)
                        if (listOf(time.start, time.end).any {
                                javaZone.getOffset(it.toEpochMilli()) != time.zone.rules.getOffset(it).totalSeconds * 1000
                            }) unsupported()
                        val zone = TimezoneAssignment(javaZone, VTimezone(time.zone.id))
                        clearCalendar.timezoneInfo.setTimezone(clear.dateStart, zone)
                        clearCalendar.timezoneInfo.setTimezone(clear.dateEnd, zone)
                        clearCalendar.timezoneInfo.timezones.clear()
                    }
                }
            }
            val parts = ProtonEventTextParts(Biweekly.write(clearCalendar).go(), Biweekly.write(wrap(secret)).go())
            if (parts.clearShared.toByteArray(Charsets.UTF_8).size.toLong() +
                parts.encryptedShared.toByteArray(Charsets.UTF_8).size > MAX_BYTES) unsupported()
            return parts
        } catch (failure: ProtonEventPreparationException) { throw failure }
          catch (_: Exception) { unsupported() }
    }
    private const val MAX_BYTES = 1_048_576L
    // Historical calendars/timezones are not qualified for this preparation slice.
    private const val MIN_YEAR = 1970
}

/** Request fragment, not a server acknowledgement or an idempotency guarantee. */
internal class ProtonPreparedCreateEvent(val event: JsonObject) {
    val format = AdapterFormat("proton-calendar-simple-create", 1)
    override fun toString() = "ProtonPreparedCreateEvent(version=${format.version}, redacted)"
}

/** Callers must select the primary active member-address signing key and active calendar key. */
internal class ProtonSimpleEventEncryptor(private val crypto: CryptoContext) {
    fun prepare(uid: String, timestamp: Instant, content: EventContent,
        memberSigningKey: PrivateKey, calendarEncryptionKey: PublicKey): ProtonPreparedCreateEvent {
        val parts = ProtonSimpleEventSerializer.serialize(uid, timestamp, content)
        if (!memberSigningKey.isActive || !memberSigningKey.isPrimary || !memberSigningKey.canVerify ||
            !calendarEncryptionKey.isActive || !calendarEncryptionKey.isPrimary || !calendarEncryptionKey.canEncrypt)
            throw ProtonEventPreparationException(ProtonEventPreparationFailure.KEYS)
        try {
            return crypto.pgpCrypto.generateNewSessionKey().use { sessionKey ->
                val secretBytes = parts.encryptedShared.toByteArray(Charsets.UTF_8)
                val encrypted = try { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(secretBytes, sessionKey)) }
                    finally { secretBytes.fill(0) }
                fun part(type: Int, data: String, original: String) = buildJsonObject {
                    put("Type", type); put("Data", data); put("Signature", memberSigningKey.signText(crypto, original))
                    // As in the official CREATE path, the service resolves Author from the member.
                    put("Author", "")
                }
                ProtonPreparedCreateEvent(buildJsonObject {
                    put("Permissions", 1); put("IsOrganizer", 1)
                    put("SharedKeyPacket", Base64.getEncoder().encodeToString(calendarEncryptionKey.encryptSessionKey(crypto, sessionKey)))
                    put("SharedEventContent", JsonArray(listOf(part(2, parts.clearShared, parts.clearShared),
                        part(3, encrypted, parts.encryptedShared))))
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { throw ProtonEventPreparationException(ProtonEventPreparationFailure.CRYPTO) }
    }
}

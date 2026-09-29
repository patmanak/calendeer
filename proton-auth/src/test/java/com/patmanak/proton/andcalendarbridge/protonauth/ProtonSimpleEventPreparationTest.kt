// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.lang.reflect.Proxy
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PublicKey
import org.junit.Assert.*
import org.junit.Test

class ProtonSimpleEventPreparationTest {
    private val stamp = Instant.parse("2026-09-29T10:00:00Z")
    private val uid = "synthetic-stable@calendeer.invalid"
    private val timed = EventTime.Timed(Instant.parse("2027-03-28T00:30:00Z"),
        Instant.parse("2027-03-28T01:30:00Z"), ZoneId.of("Europe/Paris"))

    @Test fun sharedPartitionProtectsTextAndRetainsInstantsAcrossDst() {
        val content = EventContent("Titre été;\\,\nBEGIN:VEVENT", timed,
            "Description\n" + "é".repeat(100), "Lieu, café")
        val parts = ProtonSimpleEventSerializer.serialize(uid, stamp, content)
        val clear = Biweekly.parse(parts.clearShared).first().events.single()
        val secret = Biweekly.parse(parts.encryptedShared).first().events.single()
        assertNull(clear.summary); assertNull(clear.description); assertNull(clear.location)
        assertNull(secret.dateStart); assertNull(secret.dateEnd)
        assertEquals(uid, clear.uid.value); assertEquals(uid, secret.uid.value)
        assertEquals(stamp, clear.dateTimeStamp.value.toInstant())
        assertEquals(stamp, clear.created.value.toInstant())
        assertEquals(stamp, clear.lastModified.value.toInstant())
        assertEquals(0, clear.sequence.value.toInt())
        assertTrue(parts.clearShared.contains("TZID=Europe/Paris"))
        assertTrue(parts.clearShared.contains("DTSTART;TZID=Europe/Paris:20270328T013000"))
        assertTrue(parts.clearShared.contains("DTEND;TZID=Europe/Paris:20270328T033000"))
        assertTrue(parts.clearShared.contains("DTSTAMP:20260929T100000Z"))
        assertFalse(parts.clearShared.contains("BEGIN:VTIMEZONE"))
        // RFC text newlines are decoded as CRLF by biweekly; compare their logical text.
        val restored = ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared))
        assertEquals(content, restored.copy(title = restored.title.replace("\r\n", "\n"),
            description = restored.description.replace("\r\n", "\n")))
        assertEquals(parts.clearShared, ProtonSimpleEventSerializer.serialize(uid, stamp, content).clearShared)
        assertEquals(parts.encryptedShared, ProtonSimpleEventSerializer.serialize(uid, stamp, content).encryptedShared)
        assertFalse(parts.toString().contains("Titre"))
    }

    @Test fun allDayDatesRemainExclusiveAndIndependentOfDefaultTimezone() {
        val content = EventContent("All day", EventTime.AllDay(LocalDate.parse("2027-03-28"), LocalDate.parse("2027-03-30")))
        val old = java.util.TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Honolulu", "Pacific/Kiritimati")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                val parts = ProtonSimpleEventSerializer.serialize(uid, stamp, content)
                assertTrue(parts.clearShared.contains("DTSTART;VALUE=DATE:20270328"))
                assertTrue(parts.clearShared.contains("DTEND;VALUE=DATE:20270330"))
                assertEquals(content, ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared)))
            }
        } finally { java.util.TimeZone.setDefault(old) }
    }

    @Test fun utcTimesAndEmptyDisplayFieldsArePreserved() {
        val content = EventContent("", timed.copy(zone = ZoneOffset.UTC))
        val parts = ProtonSimpleEventSerializer.serialize(uid, stamp, content)
        assertTrue(parts.clearShared.contains("DTSTART:20270328T003000Z"))
        assertEquals(content, ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared)))
        val namedUtc = content.copy(time = (content.time as EventTime.Timed).copy(zone = ZoneId.of("UTC")))
        val named = ProtonSimpleEventSerializer.serialize(uid, stamp, namedUtc)
        assertEquals(namedUtc, ProtonICalendar.parse(listOf(named.clearShared, named.encryptedShared)))
        val lf = content.copy(description = "line1\nline2")
        assertEquals(ProtonSimpleEventSerializer.serialize(uid, stamp, lf).encryptedShared,
            ProtonSimpleEventSerializer.serialize(uid, stamp, lf.copy(description = "line1\r\nline2")).encryptedShared)
    }

    @Test fun unsupportedFieldsAreRefusedRatherThanDiscarded() {
        val simple = EventContent("Synthetic", timed)
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, simple.copy(colorRgb = 0xAABBCC)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, simple.copy(recurrence = EventRecurrence(uid, "FREQ=DAILY"))) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, simple.copy(details = EventDetails(listOf(EventReminder(15))))) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, simple.copy(details = EventDetails(organizer = EventPerson("host@example.invalid")))) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, simple.copy(details = EventDetails())) }
    }

    @Test fun precisionAmbiguousLocalTimesAndUnsafeIdentitiesCannotSilentlyChange() {
        refused { ProtonSimpleEventSerializer.serialize("bad\r\nUID:injected", stamp, EventContent("Synthetic", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp.plusNanos(1), EventContent("Synthetic", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Synthetic", timed.copy(start = timed.start.plusNanos(1)))) }
        val repeated = timed.copy(start = Instant.parse("2027-10-31T01:30:00Z"), end = Instant.parse("2027-10-31T02:30:00Z"))
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Synthetic", repeated)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Synthetic\u0000", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, Instant.MAX, EventContent("Synthetic", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Synthetic\rbare", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("x".repeat(1_048_577), timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("before\uD800after", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("before\uDC00after", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, Instant.parse("1582-10-10T10:00:00Z"), EventContent("Synthetic", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Historical", EventTime.AllDay(
            LocalDate.parse("1582-10-10"), LocalDate.parse("1582-10-11")))) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Historical", EventTime.Timed(
            Instant.parse("1500-01-01T10:00:00Z"), Instant.parse("1500-01-01T11:00:00Z"), ZoneOffset.UTC))) }
        refused { ProtonSimpleEventSerializer.serialize(uid, Instant.parse("1969-12-31T23:59:59Z"), EventContent("Synthetic", timed)) }
        refused { ProtonSimpleEventSerializer.serialize(uid, stamp, EventContent("Historical", EventTime.AllDay(
            LocalDate.parse("1969-12-31"), LocalDate.parse("1970-01-01")))) }
        val unicode = EventContent("Deer 🦌", timed)
        val valid = ProtonSimpleEventSerializer.serialize(uid, stamp, unicode)
        assertEquals(unicode, ProtonICalendar.parse(listOf(valid.clearShared, valid.encryptedShared)))
    }

    @Test fun inactiveKeysAreRejectedBeforeCryptoAndCryptoFailuresStaySanitized() {
        var cryptoCalls = 0
        val crypto = Proxy.newProxyInstance(CryptoContext::class.java.classLoader, arrayOf(CryptoContext::class.java)) { _, _, _ ->
            cryptoCalls++
            error("synthetic private failure detail")
        } as CryptoContext
        val encryptor = ProtonSimpleEventEncryptor(crypto)
        for ((signingActive, calendarActive) in listOf(false to true, true to false)) {
            try {
                encryptor.prepare(uid, stamp, EventContent("Synthetic", timed),
                    PrivateKey("synthetic-private-key", isPrimary = true, isActive = signingActive, passphrase = EncryptedByteArray(byteArrayOf(1))),
                    PublicKey("synthetic-public-key", isPrimary = true, isActive = calendarActive, canEncrypt = true, canVerify = true))
                fail("Inactive key must block preparation")
            } catch (failure: ProtonEventPreparationException) {
                assertEquals(ProtonEventPreparationFailure.KEYS, failure.reason)
            }
        }
        for (calendarKey in listOf(
            PublicKey("synthetic-public-key", isPrimary = false, isActive = true, canEncrypt = true, canVerify = true),
            PublicKey("synthetic-public-key", isPrimary = true, isActive = true, canEncrypt = false, canVerify = true),
        )) {
            try {
                encryptor.prepare(uid, stamp, EventContent("Synthetic", timed),
                    PrivateKey("synthetic-private-key", isPrimary = true, passphrase = EncryptedByteArray(byteArrayOf(1))), calendarKey)
                fail("Unsuitable calendar key must block preparation")
            } catch (failure: ProtonEventPreparationException) {
                assertEquals(ProtonEventPreparationFailure.KEYS, failure.reason)
            }
        }
        assertEquals(0, cryptoCalls)
        try {
            encryptor.prepare(uid, stamp, EventContent("Synthetic", timed),
                PrivateKey("synthetic-private-key", true, passphrase = EncryptedByteArray(byteArrayOf(1))),
                PublicKey("synthetic-public-key", isPrimary = true, isActive = true, canEncrypt = true, canVerify = true))
            fail("Crypto failure must not produce a payload")
        } catch (failure: ProtonEventPreparationException) {
            assertEquals(ProtonEventPreparationFailure.CRYPTO, failure.reason)
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
        assertEquals(1, cryptoCalls)
    }

    private fun refused(action: () -> Unit) {
        try { action(); fail("Unsupported draft must not produce a payload") }
        catch (failure: ProtonEventPreparationException) {
            assertEquals(ProtonEventPreparationFailure.UNSUPPORTED_CONTENT, failure.reason)
            assertFalse(failure.message.orEmpty().contains(uid))
        }
    }
}

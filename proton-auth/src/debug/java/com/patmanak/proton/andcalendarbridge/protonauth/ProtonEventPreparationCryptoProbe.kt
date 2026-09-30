// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.user.domain.entity.*

/** Synthetic keys only. A fabricated server read shape does not establish live CREATE compatibility. */
object ProtonEventPreparationCryptoProbe {
    suspend fun run(): String = withContext(Dispatchers.IO) {
        var stage = "GENERATE"
        try {
            val crypto = AndroidCryptoContext()
            val pgp = crypto.pgpCrypto
            val addressSecret = pgp.generateNewToken()
            var calendarSecret = ByteArray(0)
            try {
                calendarSecret = pgp.generateNewToken()
                val addressArmored = pgp.generateNewPrivateKey("synthetic", "example.invalid", addressSecret)
                val calendarArmored = pgp.generateNewPrivateKey("calendar", "example.invalid", calendarSecret)
                val addressKey = PrivateKey(addressArmored, true, passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                val calendarKey = PrivateKey(calendarArmored, true, passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
                val user = UserId("synthetic-user")
                val addressId = AddressId("synthetic-address")
                val email = "synthetic@example.invalid"
                val address = UserAddress(user, addressId, email, canSend = true, canReceive = true, enabled = true,
                    order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                        keyId = KeyId("synthetic-key"), privateKey = addressKey)), signedKeyList = null)
                val passphraseText = String(calendarSecret, Charsets.UTF_8)
                val memberPacket = address.useKeys(crypto) { buildJsonObject {
                    put("MemberID", "synthetic-member"); put("Passphrase", encryptText(passphraseText))
                    put("Signature", signText(passphraseText))
                } }
                val bootstrap = buildJsonObject {
                    putJsonObject("Passphrase") {
                        put("ID", "synthetic-passphrase"); put("CalendarID", "synthetic-calendar"); put("Flags", 1)
                        put("MemberPassphrases", JsonArray(listOf(memberPacket)))
                    }
                    putJsonArray("Members") { add(buildJsonObject {
                        put("ID", "synthetic-member"); put("CalendarID", "synthetic-calendar")
                        put("AddressID", addressId.id); put("Email", email)
                        put("Flags", 1); put("Permissions", 16)
                    }) }
                    putJsonArray("Keys") { add(buildJsonObject {
                        put("ID", "synthetic-calendar-key"); put("CalendarID", "synthetic-calendar")
                        put("Flags", 3); put("PassphraseID", "synthetic-passphrase"); put("PrivateKey", calendarArmored)
                    }) }
                    putJsonObject("CalendarSettings") {
                        put("CalendarID", "synthetic-calendar")
                        put("DefaultPartDayNotifications", JsonArray(emptyList()))
                        put("DefaultFullDayNotifications", JsonArray(emptyList()))
                    }
                }
                val encryptor = ProtonSimpleEventEncryptor(crypto)
                val resolver = ProtonCreateContextResolver(crypto)
                val scope = CalendarScope(AccountId(user.id), CalendarId("synthetic-calendar"), 1)
                val metadata = buildJsonObject { put("ID", scope.calendar.value); put("Type", 0) }
                val decoder = ProtonEventDecryptor(crypto) { listOf(address) }
                val stamp = Instant.parse("2026-09-29T10:00:00Z")
                val times = listOf<EventTime>(EventTime.Timed(Instant.parse("2027-03-28T00:30:00Z"),
                    Instant.parse("2027-03-28T01:30:00Z"), ZoneId.of("Europe/Paris")),
                    EventTime.AllDay(LocalDate.parse("2027-03-28"), LocalDate.parse("2027-03-30")))
                for (time in times) {
                    val content = EventContent("Création; café, \\" + "é".repeat(100), time,
                        "Description\nLigne suivante", "Lieu de test")
                    stage = "PREPARE"
                    val direct = encryptor.prepare("synthetic-stable@calendeer.invalid", stamp, content,
                        addressKey, calendarKey.publicKey(crypto))
                    check(direct.format == AdapterFormat("proton-calendar-simple-create", 1))
                    val draft = CalendarCreateDraft(scope, MutationId("synthetic-correlation"), stamp, content)
                    val prepared = resolver.prepare(scope, metadata, bootstrap, listOf(address),
                        "synthetic-stable@calendeer.invalid", draft)
                    check(prepared.format == AdapterFormat("proton-calendar-simple-create", 1))
                    check(!prepared.toString().contains(content.title))
                    val requestParts = prepared.event.array("SharedEventContent").map { it as JsonObject }
                    check(requestParts.map { it.number("Type") } == listOf(2L, 3L))
                    check(requestParts.all { it.string("Author").isEmpty() })
                    check(!requestParts[0].string("Data").contains("SUMMARY:"))
                    check(!requestParts[1].string("Data").contains(content.title))
                    check(prepared.event.keys == setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent"))
                    // Synthetic service normalization: author resolution, response field names and identity.
                    // No request was sent and this is not an observed server response.
                    val row = buildJsonObject {
                        put("ID", "synthetic-event"); put("CalendarID", "synthetic-calendar"); put("ModifyTime", 1)
                        put("UID", "synthetic-stable@calendeer.invalid")
                        put("SharedKeyPacket", prepared.event.getValue("SharedKeyPacket"))
                        put("SharedEvents", JsonArray(requestParts.map { JsonObject(it + ("Author" to JsonPrimitive(email))) }))
                        put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
                        put("Notifications", JsonNull)
                    }
                    stage = "ROUND_TRIP"
                    val actual = decoder.decrypt(user, "synthetic-calendar", bootstrap, listOf(row)).single().content
                    check(actual.copy(description = actual.description.replace("\r\n", "\n"), details = null) == content)
                    check(actual.details == EventDetails())
                    stage = "SIGNED_UID_BINDING"
                    val session = CatalogSession(user, SessionId("synthetic-session"))
                    check(decoder.decodeCreated(session, "synthetic-calendar", bootstrap, row,
                        "synthetic-stable@calendeer.invalid") != null)
                    val foreign = "foreign@calendeer.invalid"
                    val substituted = JsonObject(row + ("UID" to JsonPrimitive(foreign)))
                    check(decoder.decodeCreated(session, "synthetic-calendar", bootstrap, substituted, foreign) == null)
                    for (index in requestParts.indices) {
                        stage = if (index == 0) "CLEAR_SIGNATURE" else "ENCRYPTED_SIGNATURE"
                        val parts = row.array("SharedEvents").map { it as JsonObject }.toMutableList()
                        parts[index] = JsonObject(parts[index] + ("Signature" to JsonPrimitive(addressKey.signText(crypto, "different"))))
                        val tampered = decoder.decodePage(user, "synthetic-calendar", bootstrap,
                            listOf(JsonObject(row + ("SharedEvents" to JsonArray(parts)))))
                        check(tampered.changes.isEmpty() && tampered.deferred.single().reason == DeferredEventReason.UNVERIFIED_CONTENT)
                    }
                }
                val draft = CalendarCreateDraft(scope, MutationId("synthetic-correlation"), stamp,
                    EventContent("Synthetic", times.last()))
                stage = "PASSPHRASE_SIGNATURE"
                val tamperedPacket = JsonObject(memberPacket + ("Signature" to JsonPrimitive(addressKey.signText(crypto, "different"))))
                val passphrase = JsonObject(bootstrap.obj("Passphrase") + ("MemberPassphrases" to JsonArray(listOf(tamperedPacket))))
                refused { resolver.prepare(scope, metadata, JsonObject(bootstrap + ("Passphrase" to passphrase)), listOf(address), "synthetic@invalid", draft) }
                stage = "PRIMARY_NO_FALLBACK"
                val primary = bootstrap.array("Keys").single() as JsonObject
                val wrongPrimary = JsonObject(primary + ("PrivateKey" to JsonPrimitive(addressArmored)))
                val secondary = JsonObject(primary + mapOf("ID" to JsonPrimitive("synthetic-secondary"), "Flags" to JsonPrimitive(1)))
                refused { resolver.prepare(scope, metadata, JsonObject(bootstrap + ("Keys" to JsonArray(listOf(wrongPrimary, secondary)))),
                    listOf(address), "synthetic@invalid", draft) }
                "RESULT=SUCCESS"
            } finally { addressSecret.fill(0); calendarSecret.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "RESULT=FAILURE STAGE=$stage" }
    }
    /** Real preparation adapter and protection, with controlled context reads and fabricated server shape.
     * Explicit raw notification emptiness is checked independently of the decoded display model.
     */
    suspend fun runKnownEmpty(): String = withContext(Dispatchers.IO) {
        var stage = "GENERATE"
        try {
            val crypto = AndroidCryptoContext()
            val pgp = crypto.pgpCrypto
            val addressSecret = pgp.generateNewToken()
            var calendarSecret = ByteArray(0)
            try {
                calendarSecret = pgp.generateNewToken()
                val addressArmored = pgp.generateNewPrivateKey("synthetic", "example.invalid", addressSecret)
                val calendarArmored = pgp.generateNewPrivateKey("calendar", "example.invalid", calendarSecret)
                val addressKey = PrivateKey(addressArmored, true,
                    passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                val user = UserId("synthetic-known-empty-user")
                val addressId = AddressId("synthetic-known-empty-address")
                val email = "synthetic-known-empty@example.invalid"
                val address = UserAddress(user, addressId, email, canSend = true, canReceive = true, enabled = true,
                    order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                        keyId = KeyId("synthetic-known-empty-key"), privateKey = addressKey)), signedKeyList = null)
                val passphraseText = String(calendarSecret, Charsets.UTF_8)
                val memberPacket = address.useKeys(crypto) { buildJsonObject {
                    put("MemberID", "synthetic-known-empty-member"); put("Passphrase", encryptText(passphraseText))
                    put("Signature", signText(passphraseText))
                } }
                val calendar = "synthetic-known-empty-calendar"
                val bootstrap = buildJsonObject {
                    putJsonObject("Passphrase") {
                        put("ID", "synthetic-known-empty-passphrase"); put("CalendarID", calendar); put("Flags", 1)
                        put("MemberPassphrases", JsonArray(listOf(memberPacket)))
                    }
                    putJsonArray("Members") { add(buildJsonObject {
                        put("ID", "synthetic-known-empty-member"); put("CalendarID", calendar)
                        put("AddressID", addressId.id); put("Email", email); put("Flags", 1); put("Permissions", 16)
                    }) }
                    putJsonArray("Keys") { add(buildJsonObject {
                        put("ID", "synthetic-known-empty-calendar-key"); put("CalendarID", calendar)
                        put("Flags", 3); put("PassphraseID", "synthetic-known-empty-passphrase"); put("PrivateKey", calendarArmored)
                    }) }
                    putJsonObject("CalendarSettings") {
                        put("CalendarID", calendar)
                        putJsonArray("DefaultPartDayNotifications") { add(buildJsonObject {
                            put("Type", 1); put("Trigger", "-PT15M")
                        }) }
                        putJsonArray("DefaultFullDayNotifications") { add(buildJsonObject {
                            put("Type", 1); put("Trigger", "-P1D")
                        }) }
                    }
                }
                val scope = CalendarScope(AccountId(user.id), CalendarId(calendar), 1)
                val session = CatalogSession(user, SessionId("synthetic-known-empty-session"))
                val expectedSession = session
                val metadata = buildJsonObject { put("ID", calendar); put("Type", 0) }
                val resolver = ProtonCreateContextResolver(crypto)
                val protector = ProtonEventPayloadProtector(crypto.keyStoreCrypto)
                var refreshes = 0
                var calendarReads = 0
                var bootstrapReads = 0
                var addressReads = 0
                var pageReads = 0
                val adapter = ProtonCreatePreparationAdapter(
                    sessions = { session },
                    context = EventContextRefresher { check(it == session); refreshes++ },
                    metadata = CreateCalendarMetadataRemote { actualSession, actualCalendar ->
                        check(actualSession == session && actualCalendar == calendar); calendarReads++; metadata
                    },
                    events = object : EventImportRemote {
                        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                            check(session == expectedSession)
                            check(calendar == scope.calendar.value); bootstrapReads++; return bootstrap
                        }
                        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage {
                            pageReads++; error("Synthetic preparation must not fetch event pages")
                        }
                    },
                    addresses = LocalEventAddresses { check(it == user); addressReads++; listOf(address) },
                    prepare = CreateContextPreparation { actualScope, actualMetadata, actualBootstrap, local, uid, draft ->
                        resolver.prepare(actualScope, actualMetadata, actualBootstrap, local, uid, draft)
                    },
                    protector = protector)
                val expectedFormat = AdapterFormat("proton-calendar-local-create-preparation", 2)
                check(adapter.localCreateFormat == expectedFormat)
                val decoder = ProtonEventDecryptor(crypto) { listOf(address) }
                val stamp = Instant.parse("2026-09-29T10:00:00Z")
                val times = listOf<EventTime>(EventTime.Timed(Instant.parse("2027-03-28T00:30:00Z"),
                    Instant.parse("2027-03-28T01:30:00Z"), ZoneId.of("Europe/Paris")),
                    EventTime.AllDay(LocalDate.parse("2027-03-28"), LocalDate.parse("2027-03-30")))
                for ((index, time) in times.withIndex()) {
                    val content = EventContent("Known-empty creation; café, \\" + "é".repeat(100), time,
                        "Description\nLigne suivante", "Lieu de test", details = EventDetails())
                    val draft = CalendarCreateDraft(scope, MutationId("synthetic-known-empty-$index"), stamp, content)
                    stage = "PREPARE"
                    val prepared = adapter.prepareCreate(draft)
                    check(prepared.scope == scope && prepared.format == expectedFormat)
                    check(!prepared.protectedPayload.contains(content.title))
                    stage = "KEYSTORE_REOPEN"
                    val reopened = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
                    val envelope = Json.parseToJsonElement(reopened.open(prepared.protectedPayload)) as JsonObject
                    check(envelope.number("version") == 2L && envelope.getValue("knownEmptyDetails").jsonPrimitive.boolean)
                    check(envelope.string("notificationPolicy") == "EXPLICIT_EMPTY")
                    check(listOf("request", "context", "expected").none { it in envelope })
                    check(envelope.string("account") == scope.account.value && envelope.string("calendar") == calendar)
                    check(envelope.number("generation") == scope.generation && envelope.string("correlation") == draft.correlation.value)
                    val uid = envelope.string("uid")
                    check(uid == adapter.correlationUid(draft) && envelope.string("timestamp") == stamp.toString())
                    val event = envelope.obj("event")
                    stage = "EXPLICIT_NOTIFICATION_OVERRIDE"
                    val notifications = event.getValue("Notifications")
                    check(notifications is JsonArray && notifications.isEmpty())
                    val shared = event.array("SharedEventContent").map { it as JsonObject }
                    check(shared.map { it.number("Type") } == listOf(2L, 3L))
                    check(shared.all { it.string("Author").isEmpty() })
                    // Author and response field normalization are synthetic. Notifications are copied
                    // from the reopened protected preparation, never manufactured for the decoder.
                    val row = buildJsonObject {
                        put("ID", "synthetic-known-empty-event-$index"); put("CalendarID", calendar); put("ModifyTime", 1)
                        put("UID", uid); put("SharedKeyPacket", event.getValue("SharedKeyPacket"))
                        put("SharedEvents", JsonArray(shared.map { JsonObject(it + ("Author" to JsonPrimitive(email))) }))
                        put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
                        put("Notifications", notifications)
                    }
                    check(row["Notifications"] is JsonArray && (row.getValue("Notifications") as JsonArray).isEmpty())
                    stage = "KNOWN_EMPTY_ROUND_TRIP"
                    val actual = decoder.decrypt(user, calendar, bootstrap, listOf(row)).single().content
                    check(actual.copy(description = actual.description.replace("\r\n", "\n")) == content)
                    stage = "STRICT_READBACK"
                    var readRow = row
                    var readBootstrapCalls = 0
                    var uidReads = 0
                    val readback = ProtonCreatedEventReadback(CatalogSessionSource { session }, object : CreateReadbackRemote {
                        override suspend fun byUid(actual: CatalogSession, actualCalendar: String, expectedUid: String, page: Int): List<JsonObject> {
                            check(actual == session && actualCalendar == calendar && expectedUid == uid && page == 0)
                            uidReads++; return listOf(row)
                        }
                        override suspend fun event(actual: CatalogSession, actualCalendar: String, id: String): JsonObject {
                            check(actual == session && actualCalendar == calendar && id == row.string("ID")); return readRow
                        }
                    }, object : EventImportRemote {
                        override suspend fun bootstrap(actual: CatalogSession, actualCalendar: String): JsonObject {
                            check(actual == session && actualCalendar == calendar); readBootstrapCalls++; return bootstrap
                        }
                        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage =
                            error("Readback must not scan event windows")
                    }, decoder::decodeCreated)
                    val strict = CreatedEventReadbackPolicy.KNOWN_EMPTY_V2
                    val observed = readback.inspect(session, scope, uid, content, strict)?.content
                        ?: error("Known-empty readback was not verified")
                    check(observed.copy(description = observed.description.replace("\r\n", "\n")) == content)
                    check(uidReads == 1 && readBootstrapCalls == 1)
                    check(readback.inspect(session, scope, uid, content.copy(title = "Changed expectation"), strict) == null)
                    val changedTime = when (time) {
                        is EventTime.AllDay -> time.copy(start = time.start.plusDays(1), endExclusive = time.endExclusive.plusDays(1))
                        is EventTime.Timed -> time.copy(start = time.start.plusSeconds(60), end = time.end.plusSeconds(60))
                    }
                    check(readback.inspect(session, scope, uid, content.copy(time = changedTime), strict) == null)
                    stage = "DEFAULT_NOTIFICATION_COUNTERPROOF"
                    val inherited = EventDetails(listOf(EventReminder(if (time is EventTime.AllDay) 1440 else 15)))
                    for (variant in listOf(JsonObject(row - "Notifications"), JsonObject(row + ("Notifications" to JsonNull)))) {
                        check(decoder.decrypt(user, calendar, bootstrap, listOf(variant)).single().content.details == inherited)
                    }
                    val emptyDefaults = JsonObject(bootstrap + ("CalendarSettings" to buildJsonObject {
                        put("CalendarID", calendar); put("DefaultPartDayNotifications", JsonArray(emptyList()))
                        put("DefaultFullDayNotifications", JsonArray(emptyList()))
                    }))
                    for (variant in listOf(JsonObject(row - "Notifications"), JsonObject(row + ("Notifications" to JsonNull)))) {
                        check(decoder.decrypt(user, calendar, emptyDefaults, listOf(variant)).single().content.details == EventDetails())
                        check(decoder.decodeCreated(session, calendar, emptyDefaults, variant, uid, strict) == null)
                    }
                    // EMAIL notifications are filtered from the Android display model. Decoded empty
                    // details alone therefore cannot prove an explicitly empty raw notification array.
                    val emailOnly = JsonArray(listOf(buildJsonObject { put("Type", 0); put("Trigger", "-PT15M") }))
                    val emailRow = JsonObject(row + ("Notifications" to emailOnly))
                    check((emailRow.getValue("Notifications") as JsonArray).isNotEmpty())
                    check(decoder.decrypt(user, calendar, bootstrap, listOf(emailRow)).single().content.details == EventDetails())
                    stage = "STRICT_PERSONAL_REFUSAL"
                    val invalidOverrides = listOf(JsonObject(row - "Notifications"), JsonObject(row + ("Notifications" to JsonNull)),
                        emailRow, JsonObject(row + ("Notifications" to JsonPrimitive("[]"))),
                        JsonObject(row + ("Notifications" to JsonArray(listOf(buildJsonObject { put("Type", 1); put("Trigger", "-PT15M") })))))
                    val noKeyDecoder = ProtonEventDecryptor(crypto) { error("Invalid override must be refused before local keys") }
                    val beforeBootstrap = readBootstrapCalls
                    for (variant in invalidOverrides) {
                        readRow = variant
                        check(readback.inspect(session, scope, uid, content, strict) == null)
                        check(noKeyDecoder.decodeCreated(session, calendar, buildJsonObject {}, variant, uid, strict) == null)
                    }
                    check(readBootstrapCalls == beforeBootstrap)
                    readRow = row
                    stage = "SIGNED_UID_BINDING"
                    check(decoder.decodeCreated(session, calendar, bootstrap, row, uid, strict) != null)
                    val foreign = "foreign-known-empty@calendeer.invalid"
                    check(decoder.decodeCreated(session, calendar, bootstrap,
                        JsonObject(row + ("UID" to JsonPrimitive(foreign))), foreign, strict) == null)
                    for (partIndex in shared.indices) {
                        stage = if (partIndex == 0) "CLEAR_SIGNATURE" else "ENCRYPTED_SIGNATURE"
                        val parts = row.array("SharedEvents").map { it as JsonObject }.toMutableList()
                        parts[partIndex] = JsonObject(parts[partIndex] +
                            ("Signature" to JsonPrimitive(addressKey.signText(crypto, "different"))))
                        val tampered = decoder.decodePage(user, calendar, bootstrap,
                            listOf(JsonObject(row + ("SharedEvents" to JsonArray(parts)))))
                        check(tampered.changes.isEmpty() && tampered.deferred.single().reason == DeferredEventReason.UNVERIFIED_CONTENT)
                        readRow = JsonObject(row + ("SharedEvents" to JsonArray(parts)))
                        check(readback.inspect(session, scope, uid, content, strict) == null)
                    }
                }
                stage = "READ_ONLY_CONTEXT"
                check(refreshes == 2 && calendarReads == 2 && bootstrapReads == 2 && addressReads == 2 && pageReads == 0)
                "RESULT=SUCCESS"
            } finally { addressSecret.fill(0); calendarSecret.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "RESULT=FAILURE STAGE=$stage" }
    }
    private fun refused(action: () -> Any) {
        try { action(); error("Invalid crypto context accepted") }
        catch (failure: CreatePreparationException) { check(failure.reason == CreatePreparationFailure.LOCAL_KEYS); check(failure.cause == null) }
    }
}

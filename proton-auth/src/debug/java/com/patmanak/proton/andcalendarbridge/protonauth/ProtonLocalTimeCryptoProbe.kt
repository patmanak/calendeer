// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.user.domain.entity.UserAddress

/** Synthetic Core verification and local protected recovery only; no account/network/Provider IO. */
internal object ProtonLocalTimeCryptoProbe {
    suspend fun run(crypto: AndroidCryptoContext, calendarSecret: ByteArray, calendarArmored: String,
        signer: PrivateKey, address: UserAddress, scope: CalendarScope, session: CatalogSession,
        bootstrap: JsonObject, originalRow: JsonObject, originalClear: String, originalSecret: String,
        textSignatures: Boolean): String {
        var stage = "TIME_FIXTURE"
        try {
            val timestamp = Instant.parse("2026-10-03T09:30:00Z")
            val uid = originalRow.string("UID")
            val metadata = buildJsonObject { put("ID", scope.calendar.value); put("Type", 0) }
            val decoder = ProtonEventDecryptor(crypto) { check(it == session.userId); listOf(address) }
            val calendarKey = PrivateKey(calendarArmored, true,
                passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val publicKey = signer.publicKey(crypto)
            fun sign(text: String) = if (textSignatures) signer.signText(crypto, text)
                else signer.signData(crypto, text.toByteArray(Charsets.UTF_8))
            fun <T> withKey(action: (me.proton.core.crypto.common.pgp.SessionKey) -> T): T =
                KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)), PublicKeyRing(emptyList())).use { keys ->
                    val key = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket")))
                        ?: error("Synthetic packet unavailable")
                    key.use(action)
                }
            fun decrypt(part: JsonObject): String = withKey { key ->
                val bytes = crypto.pgpCrypto.decryptData(Base64.getDecoder().decode(part.string("Data")), key)
                try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
            }
            val originalParts = originalRow.array("SharedEvents").map { it as JsonObject }
            fun row(clear: String, secret: String): JsonObject {
                val bytes = secret.toByteArray(Charsets.UTF_8)
                val cipher = try { withKey { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes, it)) } }
                    finally { bytes.fill(0) }
                return JsonObject(originalRow + ("SharedEvents" to JsonArray(listOf(
                    JsonObject(originalParts[0] + mapOf("Data" to JsonPrimitive(clear), "Signature" to JsonPrimitive(sign(clear)))),
                    JsonObject(originalParts[1] + mapOf("Data" to JsonPrimitive(cipher), "Signature" to JsonPrimitive(sign(secret))))))))
            }
            val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\n"
            check(originalSecret.contains(alarm))
            val secret = originalSecret.replace(alarm, "")
            val candidate = row(originalClear, secret)
            suspend fun base(input: JsonObject) = decoder.decodePage(session.userId, scope.calendar.value,
                bootstrap, listOf(input), uid).also { check(it.deferred.isEmpty()) }.changes.single()
            val baseline = base(candidate)
            val time = baseline.content.time as EventTime.Timed
            fun intent(source: RemoteChange.Upsert = baseline, next: EventTime = time.copy(
                start = time.start.plusSeconds(3600), end = time.end.plusSeconds(7200))) =
                LocalEditIntent(MutationId("synthetic-time-edit"), scope, EVENT_FORMAT, source, source.content.copy(time = next))
            val originalIntent = intent()
            fun protector() = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
            suspend fun prepare(edit: LocalEditIntent = originalIntent, input: JsonObject = candidate,
                engine: ProtonEventDecryptor = decoder, storage: EventPayloadProtector = protector()) =
                engine.prepareAndSealLocalTimeEdit(session, edit, uid, timestamp, metadata, bootstrap, input, storage)
            fun verify(record: LocalTimeEditPreparation, input: JsonObject, beforeClear: String,
                beforeSecret: String, start: String, end: String) {
                val opened = ProtonTimePreparationCodec(protector()).openLocal(record)
                check(opened.changed && opened.originalRow == input)
                check(opened.originalRow.string("SharedKeyPacket") == originalRow.string("SharedKeyPacket"))
                val clear = opened.clearSharedPart.string("Data")
                val renewedSecret = decrypt(opened.encryptedSharedPart)
                val expectedClear = beforeClear.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z")
                    .replace("LAST-MODIFIED:20261003T090000Z", "LAST-MODIFIED:20261003T093000Z")
                    .replace("SEQUENCE:0007", "SEQUENCE:8").replace(start, end)
                check(clear == expectedClear)
                check(renewedSecret == beforeSecret.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z"))
                check(publicKey.verifyText(crypto, clear, opened.clearSharedPart.string("Signature")))
                check(publicKey.verifyText(crypto, renewedSecret, opened.encryptedSharedPart.string("Signature")))
                val beforeParts = input.array("SharedEvents").map { it as JsonObject }
                for ((before, after) in listOf(beforeParts[0] to opened.clearSharedPart, beforeParts[1] to opened.encryptedSharedPart))
                    check(JsonObject(before - setOf("Data", "Signature")) == JsonObject(after - setOf("Data", "Signature")))
                val secondOpen = ProtonTimePreparationCodec(protector()).openLocal(record)
                check(secondOpen.clearSharedPart == opened.clearSharedPart && secondOpen.encryptedSharedPart == opened.encryptedSharedPart)
            }
            stage = "TIME_TIMED_SIGNATURES"
            val prepared = prepare()
            check(prepared.intent == originalIntent && prepared.format == LOCAL_TIME_PREPARATION_FORMAT)
            verify(prepared, candidate, originalClear, secret,
                "DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z",
                "DTSTART:20261003T110000Z\r\nDTEND:20261003T130000Z")

            stage = "TIME_AUTHENTICATED_COMPOSITION"
            val reads = mutableListOf<String>()
            val authenticated = ProtonLocalTimePreparationAdapter({ session }, { error("No unrestricted refresh") },
                CreateCalendarMetadataRemote { observed, calendar ->
                    check(observed == session && calendar == scope.calendar.value); reads += "metadata"; metadata
                }, object : EventImportRemote {
                    override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage =
                        error("No page reads")
                    override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                        reads += "bootstrap"; return bootstrap
                    }
                }, LocalTitleEventRemote { observed, target, id ->
                    check(observed == session && target == scope && id == baseline.eventId); reads += "event"; candidate
                }, LocalEventAddresses { check(it == session.userId); reads += "addresses"; listOf(address) },
                localTimeCryptoPreparation(crypto, protector()),
                LocalTitleContextRefresh { observed, checkpoint -> check(observed == session); checkpoint(); reads += "refresh"; checkpoint() })
            val authenticatedRecord = checkNotNull(authenticated.prepareLocalTime(originalIntent, timestamp) { it == scope })
            check(reads == listOf("refresh", "metadata", "bootstrap", "event", "addresses"))
            check(authenticatedRecord.intent == originalIntent)
            verify(authenticatedRecord, candidate, originalClear, secret,
                "DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z",
                "DTSTART:20261003T110000Z\r\nDTEND:20261003T130000Z")

            stage = "TIME_PROTECTED_REQUEST_AND_READBACK"
            // Construct a different synthetic fixture for the closed wire profile. The broader
            // preservation fixture above deliberately contains unknown JSON metadata, which is
            // refused by sending eligibility. No real source row is trimmed for production.
            val wireSource = JsonObject(candidate - "OpaqueMetadata" + buildJsonObject {
                put("SharedEvents", JsonArray(partsForWire(candidate)))
                put("CreateTime", 1); put("ModifyTime", 2); put("Permissions", 3); put("IsOrganizer", 1)
                put("IsPersonalSingleEdit", false); put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond)
                put("FullDay", 0); put("StartTimezone", "UTC"); put("EndTimezone", "UTC")
                put("Attendees", JsonArray(emptyList())); put("Exdates", JsonArray(emptyList()))
            })
            val wireIntent = intent(base(wireSource))
            val wirePreparation = prepare(wireIntent, wireSource)
            val wireStorage = protector()
            val codec = ProtonTimeUpdateRequestCodec(wireStorage)
            val selected = ProtonCreateContextPolicy.select(scope, metadata, bootstrap, listOf(address))
            val wireContext = ProtonSimpleEventUpdate.context(selected, metadata, bootstrap)
            val request = codec.seal(MutationId("synthetic-time-request"), wirePreparation, wireContext)
            val opened = codec.open(request)
            check(opened.request.string("MemberID") == wireContext.string("member"))
            val targetTime = wireIntent.content.time as EventTime.Timed
            val readback = JsonObject(wireSource + buildJsonObject {
                put("SharedEvents", JsonArray(listOf(opened.fragments.clearSharedPart, opened.fragments.encryptedSharedPart)))
                put("ModifyTime", 3); put("StartTime", targetTime.start.epochSecond); put("EndTime", targetTime.end.epochSecond)
            })
            var verifications = 0
            val reader = ProtonTimeUpdateReadbackVerifier(codec, CreateEventVerifier { observedSession, calendar, observedBootstrap, row, expectedUid, policy ->
                verifications++
                decoder.decodeCreated(observedSession, calendar, observedBootstrap, row, expectedUid, policy)
            })
            val verified = checkNotNull(reader.verify(session, request, wireContext, bootstrap, readback))
            check(verified.content == wireIntent.content && verified.revision == readback.revision(bootstrap))
            for ((name, wrong) in listOf("StartTime" to JsonPrimitive(targetTime.start.epochSecond + 1),
                "EndTime" to JsonPrimitive(targetTime.end.epochSecond + 1), "FullDay" to JsonPrimitive(1),
                "StartTimezone" to JsonPrimitive("Europe/Paris"), "EndTimezone" to JsonPrimitive("Europe/Paris")))
                check(reader.verify(session, request, wireContext, bootstrap, JsonObject(readback + (name to wrong))) == null)
            check(verifications == 1)

            stage = "TIME_ALL_DAY_SIGNATURES"
            val dates = "DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261005"
            val dayClear = originalClear.replace("DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z", dates)
            val dayRow = row(dayClear, secret)
            val dayBase = base(dayRow)
            val day = dayBase.content.time as EventTime.AllDay
            val dayIntent = intent(dayBase, day.copy(start = day.start.plusDays(1), endExclusive = day.endExclusive.plusDays(2)))
            verify(prepare(dayIntent, dayRow), dayRow, dayClear, secret, dates,
                "DTSTART;VALUE=DATE:20261004\r\nDTEND;VALUE=DATE:20261007")

            stage = "TIME_ORIGINAL_REFUSALS"
            suspend fun refused(edit: LocalEditIntent = originalIntent, input: JsonObject = candidate) {
                var seals = 0
                val storage = object : EventPayloadProtector {
                    override fun seal(plain: String): String { seals++; error("Must not seal") }
                    override fun open(protected: String): String = error("Must not open")
                }
                var rejected = false
                try { prepare(edit, input, storage = storage) }
                catch (failure: RemoteImportException) { rejected = failure.reason == RemoteImportFailure.UNSUPPORTED && failure.cause == null }
                check(rejected && seals == 0)
            }
            refused(originalIntent.copy(base = baseline.copy(content = baseline.content.copy(description = "Stale notes")),
                content = originalIntent.content.copy(description = "Stale notes")))
            refused(originalIntent.copy(content = originalIntent.content.copy(title = "Combined edit")))
            refused(originalIntent.copy(format = AdapterFormat("foreign-observation", 1)))
            refused(input = row(originalClear.replace("DTSTART:20261003T100000Z", "DTSTART:20261003T100001Z"), secret))
            val parts = candidate.array("SharedEvents").map { it as JsonObject }
            val broken = JsonObject(parts[0] + ("Signature" to parts[1].getValue("Signature")))
            refused(input = JsonObject(candidate + ("SharedEvents" to JsonArray(listOf(broken, parts[1])))))
            refused(input = JsonObject(candidate + ("CalendarEvents" to JsonArray(listOf(parts[0])))))

            stage = "TIME_CANCELLATION"
            for (kind in listOf("CALLBACK_RETURN", "CALLBACK_FAILURE", "STORAGE_FAILURE")) supervisorScope {
                var cancelled = false; var returned = false; var ordinary = false; var seals = 0
                val child = async {
                    val context = currentCoroutineContext()
                    val engine = ProtonEventDecryptor(crypto) {
                        if (kind != "STORAGE_FAILURE") {
                            context.cancel()
                            if (kind == "CALLBACK_FAILURE") throw java.io.IOException("Synthetic callback failure")
                        }
                        listOf(address)
                    }
                    val storage = object : EventPayloadProtector {
                        override fun seal(plain: String): String {
                            seals++
                            context.cancel()
                            throw java.io.IOException("Synthetic storage failure")
                        }
                        override fun open(protected: String) = error("No storage read")
                    }
                    try { prepare(engine = engine, storage = storage); returned = true }
                    catch (_: CancellationException) { cancelled = true }
                    catch (_: Exception) { ordinary = true }
                }
                try { child.await() } catch (_: CancellationException) { }
                check(cancelled && !returned && !ordinary && seals == if (kind == "STORAGE_FAILURE") 1 else 0)
            }
            return "RESULT=SUCCESS"
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return "RESULT=FAILURE STAGE=$stage" }
    }

    private fun partsForWire(row: JsonObject) = row.array("SharedEvents").map {
        JsonObject((it as JsonObject) - "UnknownPartMetadata")
    }
}

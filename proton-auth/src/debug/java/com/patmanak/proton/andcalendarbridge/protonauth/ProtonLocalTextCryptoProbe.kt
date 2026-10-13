// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/** Original synthetic fixture extension; reuses Core primitives as in ProtonSharedTitleCryptoProbe.
 * No real account, request, persistence journal, Provider mutation or upload qualification.
 */
internal object ProtonLocalTextCryptoProbe {
    suspend fun run(crypto: AndroidCryptoContext, calendarSecret: ByteArray, calendarArmored: String,
        addressKey: PrivateKey, address: UserAddress, scope: CalendarScope, session: CatalogSession,
        bootstrap: JsonObject, originalRow: JsonObject, originalClear: String, originalSecret: String,
        textSignatures: Boolean): String {
        var stage = "LOCAL_TEXT_FIXTURE"
        try {
            val timestamp = Instant.parse("2026-10-03T09:30:00Z")
            val uid = originalRow.string("UID")
            val metadata = buildJsonObject { put("ID", scope.calendar.value); put("Type", 0) }
            val decoder = ProtonEventDecryptor(crypto) { check(it == session.userId); listOf(address) }
            val calendarKey = PrivateKey(calendarArmored, true,
                passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val publicKey = addressKey.publicKey(crypto)
            fun decrypt(part: JsonObject): String =
                KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)), PublicKeyRing(emptyList())).use { keys ->
                    val key = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket")))
                        ?: error("Synthetic packet unavailable")
                    val bytes = key.use { keys.decryptDataOrNull(Base64.getDecoder().decode(part.string("Data")), it)
                        ?: error("Synthetic ciphertext unavailable") }
                    try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                }
            fun sign(plain: String) = if (textSignatures) addressKey.signText(crypto, plain)
                else addressKey.signData(crypto, plain.toByteArray(Charsets.UTF_8))
            fun row(clear: String = originalClear, secret: String): JsonObject {
                val parts = originalRow.array("SharedEvents").map { it as JsonObject }
                val ciphertext = KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)),
                    PublicKeyRing(emptyList())).use { keys ->
                    val retained = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket")))
                        ?: error("Synthetic packet unavailable")
                    retained.use { key ->
                        val bytes = secret.toByteArray(Charsets.UTF_8)
                        try { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes, key)) }
                        finally { bytes.fill(0) }
                    }
                }
                val clearPart = JsonObject(parts[0] + mapOf("Data" to JsonPrimitive(clear),
                    "Signature" to JsonPrimitive(sign(clear))))
                val secretPart = JsonObject(parts[1] + mapOf("Data" to JsonPrimitive(ciphertext),
                    "Signature" to JsonPrimitive(sign(secret))))
                return JsonObject(originalRow + ("SharedEvents" to JsonArray(listOf(clearPart, secretPart))))
            }
            // Isolate this fixture from historical VALARM data; current Notifications govern decoded reminders.
            val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\n"
            check(originalSecret.contains(alarm))
            val secret = originalSecret.replace(alarm, "")
            val candidate = row(secret = secret)
            suspend fun base(source: JsonObject): RemoteChange.Upsert {
                val decoded = decoder.decodePage(session.userId, scope.calendar.value, bootstrap, listOf(source), uid)
                check(decoded.deferred.isEmpty() && decoded.changes.size == 1)
                return decoded.changes.single()
            }
            val source = base(candidate)
            check(source.content.details == EventDetails() && source.content.recurrence == null)
            check(source.content.description.replace("\r\n", "\n") == "Opaque\ntext" && source.content.location == "")
            fun intent(observation: RemoteChange.Upsert = source,
                content: EventContent = observation.content.copy(title = "Local; forêt 🦌",
                    description = "Notes, \\ é\n" + "界🦌".repeat(8), location = "Salle; été\\\n2"),
                id: String = "synthetic-local-text-edit") =
                LocalEditIntent(MutationId(id), scope, EVENT_FORMAT, observation, content)
            val originalIntent = intent()
            fun protector() = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
            fun open(record: LocalTextEditPreparation) = ProtonTextPreparationCodec(protector()).openLocal(record)
            fun selected(record: LocalTextEditPreparation): JsonObject =
                Json.parseToJsonElement(protector().open(record.protectedPayload)).jsonObject.obj("binding").obj("changes")
            suspend fun prepare(edit: LocalEditIntent = originalIntent, input: JsonObject = candidate,
                context: JsonObject = bootstrap, catalog: JsonObject = metadata, engine: ProtonEventDecryptor = decoder,
                storage: EventPayloadProtector = protector()) =
                engine.prepareAndSealLocalTextEdit(session, edit, uid, timestamp, catalog, context, input, storage)
            fun clock(text: String) = text.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z")
                .replace("LAST-MODIFIED:20261003T090000Z", "LAST-MODIFIED:20261003T093000Z")
            val textBlocks = Regex("(?m)^(?:SUMMARY|DESCRIPTION|LOCATION):[^\\r\\n]*(?:\\r\\n[ \\t][^\\r\\n]*)*\\r\\n")
            fun verify(record: LocalTextEditPreparation, input: JsonObject, clear: String, secret: String,
                expected: EventContent, changed: Boolean = true): ProtonTimestampedSharedText {
                val result = open(record)
                val parts = input.array("SharedEvents").map { it as JsonObject }
                check(record.format == LOCAL_TEXT_PREPARATION_FORMAT && record.timestamp == timestamp)
                check(result.changed == changed && result.originalRow == input)
                check(result.originalRow.string("SharedKeyPacket") == originalRow.string("SharedKeyPacket"))
                val clearAfter = result.clearSharedPart.string("Data")
                val secretAfter = decrypt(result.encryptedSharedPart)
                for ((before, after) in listOf(parts[0] to result.clearSharedPart, parts[1] to result.encryptedSharedPart))
                    check(before.filterKeys { it != "Data" && it != "Signature" } ==
                        after.filterKeys { it != "Data" && it != "Signature" })
                for ((plain, part) in listOf(clear to parts[0], secret to parts[1])) {
                    check(if (textSignatures) publicKey.verifyText(crypto, plain, part.string("Signature"))
                        else publicKey.verifyData(crypto, plain.toByteArray(Charsets.UTF_8), part.string("Signature")))
                }
                if (changed) {
                    check(clearAfter == clock(clear))
                    check(secretAfter.replace(textBlocks, "") == clock(secret).replace(textBlocks, ""))
                    check(publicKey.verifyText(crypto, clearAfter, result.clearSharedPart.string("Signature")))
                    check(publicKey.verifyText(crypto, secretAfter, result.encryptedSharedPart.string("Signature")))
                } else {
                    check(result.clearSharedPart == parts[0] && result.encryptedSharedPart == parts[1])
                    check(clearAfter == clear && secretAfter == secret)
                }
                val parsed = biweekly.Biweekly.parse(secretAfter).first().events.single()
                fun logical(value: String?) = value.orEmpty().replace("\r\n", "\n")
                check(logical(parsed.summary?.value) == logical(expected.title) &&
                    logical(parsed.description?.value) == logical(expected.description) &&
                    logical(parsed.location?.value) == logical(expected.location))
                check(!record.toString().contains(uid) && !result.toString().contains(uid))
                return result
            }
            suspend fun refused(edit: LocalEditIntent = originalIntent, input: JsonObject = candidate,
                context: JsonObject = bootstrap, catalog: JsonObject = metadata) {
                var seals = 0
                val guarded = object : EventPayloadProtector {
                    override fun seal(plain: String): String { seals++; error("Rejected preparation must not seal") }
                    override fun open(protected: String): String = error("No storage read")
                }
                var rejected = false
                try { prepare(edit, input, context, catalog, storage = guarded) }
                catch (failure: RemoteImportException) {
                    rejected = failure.reason == RemoteImportFailure.UNSUPPORTED && failure.cause == null
                }
                check(rejected && seals == 0)
            }

            stage = "LOCAL_TEXT_COMBINED"
            val record = prepare()
            check(record.intent == originalIntent)
            val prepared = verify(record, candidate, originalClear, secret, originalIntent.content)
            val choices = selected(record)
            check(choices.keys == setOf("SUMMARY", "DESCRIPTION", "LOCATION"))
            check(choices.obj("LOCATION")["expected"] == JsonNull)
            // Reopening supplies neither external UID nor decoder/keys.
            val reopened = open(record)
            check(reopened.originalRow == prepared.originalRow && reopened.clearSharedPart == prepared.clearSharedPart &&
                reopened.encryptedSharedPart == prepared.encryptedSharedPart)
            check(reopened.originalRow !== reopened.originalRow)

            stage = "LOCAL_TEXT_OPTIONAL_PRESENCE"
            for (presentEmpty in listOf(false, true)) {
                val plain = if (presentEmpty) secret.replace("END:VEVENT\r\n", "LOCATION:\r\nEND:VEVENT\r\n") else secret
                val input = row(secret = plain)
                val observed = base(input)
                val addedIntent = intent(observed, observed.content.copy(location = "New place"),
                    "synthetic-local-text-add-" + (if (presentEmpty) "empty" else "absent"))
                val added = prepare(addedIntent, input)
                verify(added, input, originalClear, plain, addedIntent.content)
                val change = selected(added).obj("LOCATION")
                check(change["expected"] == (if (presentEmpty) JsonPrimitive("") else JsonNull))
                val unchangedIntent = intent(observed, observed.content.copy(title = "Only title"),
                    "synthetic-local-text-keep")
                val unchanged = prepare(unchangedIntent, input)
                val result = verify(unchanged, input, originalClear, plain, unchangedIntent.content)
                check(selected(unchanged).keys == setOf("SUMMARY"))
                check(decrypt(result.encryptedSharedPart).contains("LOCATION:\r\n") == presentEmpty)
            }

            stage = "LOCAL_TEXT_REMOVAL"
            val removalIntent = intent(content = source.content.copy(description = ""))
            val removed = prepare(removalIntent)
            val removal = verify(removed, candidate, originalClear, secret, removalIntent.content)
            check(selected(removed).obj("DESCRIPTION")["replacement"] == JsonNull)
            check(!decrypt(removal.encryptedSharedPart).contains("DESCRIPTION:"))
            check(decrypt(removal.encryptedSharedPart) == clock(secret.replace("DESCRIPTION:Opaque\\ntext\r\n", "")))

            stage = "LOCAL_TEXT_ALL_DAY"
            val allDayClear = originalClear.replace("DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z\r\n",
                "DTSTART;VALUE=DATE:20261024\r\nDTEND;VALUE=DATE:20261027\r\n")
            val allDayRow = row(allDayClear, secret)
            val allDayBase = base(allDayRow)
            check(allDayBase.content.time == EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27")))
            val allDayIntent = intent(allDayBase, allDayBase.content.copy(description = "All-day notes"))
            val allDayRecord = prepare(allDayIntent, allDayRow)
            verify(allDayRecord, allDayRow, allDayClear, secret, allDayIntent.content)
            check(allDayRecord.intent.base.content.time == allDayRecord.intent.content.time)

            stage = "LOCAL_TEXT_NORMALIZED_NO_OP"
            val oldDescription = source.content.description
            check(oldDescription.contains('\n'))
            val alternateLines = if (oldDescription.contains("\r\n")) oldDescription.replace("\r\n", "\n")
                else oldDescription.replace("\n", "\r\n")
            val noOpIntent = intent(content = source.content.copy(description = alternateLines))
            val noOp = prepare(noOpIntent)
            check(noOp.intent == noOpIntent && noOp.intent.content.description != noOp.intent.base.content.description)
            verify(noOp, candidate, originalClear, secret, noOpIntent.content, changed = false)

            stage = "LOCAL_TEXT_FULL_BASE_REFUSALS"
            fun changedBase(next: EventContent) = intent(source.copy(content = next),
                next.copy(title = originalIntent.content.title, description = originalIntent.content.description,
                    location = originalIntent.content.location))
            val timed = source.content.time as EventTime.Timed
            for ((tag, next) in listOf(
                "TITLE" to source.content.copy(title = "Wrong original"),
                "DESC" to source.content.copy(description = "Wrong original"),
                "LOCATION" to source.content.copy(location = "Wrong original"),
                "COLOR" to source.content.copy(colorRgb = 0x123456),
                "DETAILS" to source.content.copy(details = null),
                "NANOS" to source.content.copy(time = timed.copy(start = timed.start.plusNanos(1))),
                "END" to source.content.copy(time = timed.copy(end = timed.end.plusSeconds(1))),
                "ZONE" to source.content.copy(time = timed.copy(zone = ZoneId.of("Europe/Paris"))),
                "ALLDAY" to source.content.copy(time = allDayBase.content.time))) {
                stage = "LOCAL_TEXT_REFUSE_" + tag
                refused(changedBase(next))
            }
            stage = "LOCAL_TEXT_REFUSE_REV"
            refused(originalIntent.copy(base = source.copy(revision = Revision("wrong-revision"))))
            stage = "LOCAL_TEXT_REFUSE_ID"
            refused(originalIntent.copy(base = source.copy(eventId = EventId("wrong-event"))))
            stage = "LOCAL_TEXT_REFUSE_FORMAT"
            refused(originalIntent.copy(format = EVENT_FORMAT.copy(version = EVENT_FORMAT.version + 1)))
            stage = "LOCAL_TEXT_REFUSE_ACCOUNT"
            refused(originalIntent.copy(scope = scope.copy(account = AccountId("wrong-account"))))
            stage = "LOCAL_TEXT_REFUSE_METADATA"
            refused(catalog = JsonObject(metadata + ("Type" to JsonPrimitive(1))))
            stage = "LOCAL_TEXT_REFUSE_SETTINGS"
            val settings = bootstrap.obj("CalendarSettings")
            refused(context = JsonObject(bootstrap + ("CalendarSettings" to
                JsonObject(settings + ("CalendarID" to JsonPrimitive("wrong-calendar"))))))
            // Equal visible title and correct candidate revision cannot conceal altered signed dates.
            stage = "LOCAL_TEXT_REFUSE_SIGNED_DATE"
            val differentDates = row(originalClear.replace("DTEND:20261003T110000Z", "DTEND:20261003T120000Z"), secret)
            refused(originalIntent.copy(base = source.copy(revision = differentDates.revision(bootstrap))), differentDates)
            stage = "LOCAL_TEXT_REFUSE_NOTIFICATIONS"
            val unsupported = JsonObject(candidate + ("Notifications" to JsonPrimitive("unsupported")))
            refused(originalIntent.copy(base = source.copy(revision = unsupported.revision(bootstrap))), unsupported)
            stage = "LOCAL_TEXT_LEGACY_ALARM"
            // A historical VALARM with explicit Notifications=[] decodes to the same supported content.
            // Expecting refusal for that legacy component alone was incorrect; its row revision still differs.
            val legacy = base(originalRow)
            check(legacy.content == source.content && legacy.eventId == source.eventId)
            check(legacy.revision == originalRow.revision(bootstrap))
            stage = "LOCAL_TEXT_CURRENT_REMINDER"
            val withReminder = JsonObject(candidate + ("Notifications" to buildJsonArray {
                add(buildJsonObject { put("Type", 1); put("Trigger", "-PT15M") })
            }))
            val reminderBase = base(withReminder)
            check(reminderBase.content.details?.reminders == listOf(EventReminder(15)))
            check(reminderBase.content == source.content.copy(details = EventDetails(reminders = listOf(EventReminder(15)))))
            // The retained intent stays bound to known-empty details, so the real current reminder must refuse.
            refused(originalIntent.copy(base = source.copy(revision = withReminder.revision(bootstrap))), withReminder)

            stage = "LOCAL_TEXT_RECORD_REBINDING"
            fun recordRefused(other: LocalTextEditPreparation) {
                var rejected = false
                try { open(other) }
                catch (failure: ProtonTextPreparationException) {
                    rejected = failure.reason == ProtonTextPreparationFailure.UNSUPPORTED && failure.cause == null
                }
                check(rejected)
            }
            for (edit in listOf(originalIntent.copy(id = MutationId("different-mutation")),
                originalIntent.copy(scope = scope.copy(generation = scope.generation + 1)),
                originalIntent.copy(content = originalIntent.content.copy(location = "Other target"))))
                recordRefused(LocalTextEditPreparation(edit, timestamp, record.format, record.protectedPayload))
            recordRefused(LocalTextEditPreparation(record.intent, timestamp.plusSeconds(1), record.format, record.protectedPayload))
            recordRefused(LocalTextEditPreparation(record.intent, timestamp, TEXT_PREPARATION_FORMAT, record.protectedPayload))
            recordRefused(LocalTextEditPreparation(record.intent, timestamp, record.format, allDayRecord.protectedPayload))

            stage = "LOCAL_TEXT_FROZEN_CALLBACK"
            val reminders = mutableListOf<EventReminder>()
            val details = EventDetails(reminders = reminders)
            val mutableIntent = originalIntent.copy(base = source.copy(content = source.content.copy(details = details)),
                content = originalIntent.content.copy(details = details))
            val mutableRow = candidate.toMutableMap()
            val mutableMetadata = metadata.toMutableMap()
            val mutableSettings = bootstrap.obj("CalendarSettings").toMutableMap()
            var callbacks = 0
            val changing = ProtonEventDecryptor(crypto) {
                check(it == session.userId); callbacks++
                yield()
                reminders += EventReminder(10)
                mutableRow["ModifyTime"] = JsonPrimitive(99)
                mutableMetadata["Type"] = JsonPrimitive(1)
                mutableSettings["CalendarID"] = JsonPrimitive("changed-after-suspension")
                listOf(address)
            }
            val frozen = prepare(mutableIntent, JsonObject(mutableRow),
                JsonObject(bootstrap + ("CalendarSettings" to JsonObject(mutableSettings))),
                JsonObject(mutableMetadata), changing)
            check(callbacks == 1 && frozen.intent == originalIntent)
            verify(frozen, candidate, originalClear, secret, originalIntent.content)

            stage = "LOCAL_TEXT_CANCELLED_CLASSIFICATION"
            val failures = mutableListOf<String>()
            for (kind in listOf("CALLBACK_RETURN", "CALLBACK_FAILURE", "STORAGE_FAILURE")) {
                val correct = supervisorScope {
                    var returned = 0; var cancellations = 0; var ordinaryFailures = 0; var reads = 0; var seals = 0
                    val child = async {
                        val childContext = currentCoroutineContext()
                        val cancelling = ProtonEventDecryptor(crypto) {
                            reads++
                            if (kind != "STORAGE_FAILURE") {
                                currentCoroutineContext().cancel(CancellationException("Synthetic local text cancellation"))
                                if (kind == "CALLBACK_FAILURE") throw java.io.IOException("Synthetic address failure")
                            }
                            listOf(address)
                        }
                        val delegate = protector()
                        val storage = object : EventPayloadProtector {
                            override fun seal(plain: String): String {
                                seals++
                                if (kind == "STORAGE_FAILURE") {
                                    childContext.cancel(CancellationException("Synthetic local text storage cancellation"))
                                    throw java.io.IOException("Synthetic storage failure")
                                }
                                return delegate.seal(plain)
                            }
                            override fun open(protected: String) = delegate.open(protected)
                        }
                        try { prepare(engine = cancelling, storage = storage); returned++ }
                        catch (_: CancellationException) { cancellations++ }
                        catch (_: Exception) { ordinaryFailures++ }
                    }
                    var awaitCancelled = false
                    try { child.await() } catch (_: CancellationException) { awaitCancelled = true }
                    awaitCancelled && cancellations == 1 && returned == 0 && ordinaryFailures == 0 &&
                        reads == 1 && seals == (if (kind == "STORAGE_FAILURE") 1 else 0)
                }
                if (!correct) failures += kind
            }
            if (failures.isNotEmpty()) stage = "LOCAL_TEXT_CANCELLED_" + failures.joinToString("_")
            check(failures.isEmpty())
            return "RESULT=SUCCESS"
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return "RESULT=FAILURE STAGE=$stage" }
    }
}

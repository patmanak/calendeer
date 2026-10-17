// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Proton Calendar CacheCalendarPassphraseUseCase/TransformEventUseCase; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.user.domain.entity.UserAddress

/** Implemented by the protected local address DAO, never by UserManager's cache/network store. */
internal fun interface LocalEventAddresses {
    suspend fun get(user: UserId): List<UserAddress>
}

/** Keys and decrypted packets live only within one page. No calendar/account setup is invoked. */
internal class ProtonEventDecryptor(private val crypto: CryptoContext, private val localAddresses: LocalEventAddresses) : EventImportDecoder {
    override suspend fun decode(session: CatalogSession, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>) =
        decodePage(session.userId, calendar, bootstrap, rows)

    /** Legacy native probe path must fail if any content was deferred. */
    suspend fun decrypt(user: UserId, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>): List<RemoteChange.Upsert> {
        val result = decodePage(user, calendar, bootstrap, rows)
        if (result.deferred.isNotEmpty()) unsupportedEvents()
        return result.changes
    }
    suspend fun decodeCreated(session: CatalogSession, calendar: String, bootstrap: JsonObject, row: JsonObject,
        uid: String, policy: CreatedEventReadbackPolicy = CreatedEventReadbackPolicy.SHARED_ONLY_V1): RemoteChange.Upsert? {
        if (!policy.accepts(row) || row.string("UID") != uid || row.array("SharedEvents").map { (it as JsonObject).number("Type") }.sorted() != listOf(2L, 3L) ||
            row.array("CalendarEvents").isNotEmpty() || row.array("AttendeesEvents").isNotEmpty()) return null
        return decodePage(session.userId, calendar, bootstrap, listOf(row), uid).changes.singleOrNull()
    }
    suspend fun decodePage(user: UserId, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>, expectedUid: String? = null): EventDecodeResult {
        return withCalendarKeys(user, calendar, bootstrap) { calendarKeys, addresses ->
            decodeRows(calendar, bootstrap, rows, expectedUid, calendarKeys, addresses)
        }
    }

    /** Offline shared-signature verification only. Expected bindings come from the caller's read.
     * No active-session/access proof, network, signing, persistence or update payload construction.
     */
    suspend fun openSharedTitle(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String,
        bootstrap: JsonObject, row: JsonObject): ProtonSharedTitleBasis {
        return withSharedTitle(session, scope, expectedEventId, expectedRevision, expectedUid, expectedTitle,
            bootstrap, row) { basis, _, _, _ -> basis }
    }

    /** Offline crypto fragment, never an UPDATE payload or proof of event edit eligibility.
     * Reuses the existing shared session key; only the encrypted part's Data/Signature change.
     * Calendar WRITE selection is local evidence, not current session/server authorization.
     */
    suspend fun prepareSharedTitle(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String, newTitle: String,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject): ProtonReencryptedSharedTitle {
        val prepared = prepareSharedTitleFragments(session, scope, expectedEventId, expectedRevision,
            expectedUid, expectedTitle, newTitle, metadata, bootstrap, row, timestamp = null)
        return ProtonReencryptedSharedTitle(prepared.originalRow, prepared.encryptedSharedPart, prepared.changed)
    }

    /** Offline timestamp policy and crypto only; no clock, current rights proof or upload.
     * Requires explicitly empty personal/attendee partitions, even for a no-op.
     * Re-signs only parts whose actual plaintext changes; original JSON remains separate.
     * A no-op does not certify metadata eligibility for a subsequent changed title.
     */
    suspend fun prepareSharedTitleAt(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String, newTitle: String,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, timestamp: Instant): ProtonTimestampedSharedTitle =
        prepareSharedTitleFragments(session, scope, expectedEventId, expectedRevision, expectedUid,
            expectedTitle, newTitle, metadata, bootstrap, row, timestamp)

    /** Recoverable protected format only; no database commit, automatic resumption or dispatch.
     * Original verification and crypto precede sealing. Local sealing is not a remote read receipt
     * or current session/access proof; no bootstrap or private key material is retained.
     */
    suspend fun prepareAndSealTitleAt(session: CatalogSession, binding: ProtonTitlePreparationBinding,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject,
        protector: EventPayloadProtector): ProtonProtectedTitlePreparation {
        val prepared = prepareSharedTitleAt(session, binding.scope, binding.eventId, binding.revision,
            binding.uid, binding.expectedTitle, binding.newTitle, metadata, bootstrap, row, binding.timestamp)
        return ProtonTitlePreparationCodec(protector).seal(binding, prepared)
    }

    /** Offline selected shared texts only, with mandatory timestamp policy and no dispatch authority.
     * Authentication of the original SUMMARY remains mandatory even when it is not selected.
     */
    suspend fun prepareSharedTextAt(session: CatalogSession, binding: ProtonTextPreparationBinding,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject): ProtonTimestampedSharedText {
        currentCoroutineContext().ensureActive()
        val changes = binding.changes
        val titleChange = changes[ProtonSharedTextProperty.SUMMARY]
        if (titleChange != null && (titleChange.expected == null ||
            titleChange.expected.replace("\r\n", "\n") != binding.expectedTitle.replace("\r\n", "\n"))) unsupportedEvents()
        return prepareSharedTextFragments(session, binding.scope, binding.eventId, binding.revision,
            binding.uid, binding.expectedTitle, metadata, bootstrap, row, requireEmptyPersonal = true) { basis ->
            ProtonSharedTextPatcher.patchAt(basis.originalPlaintext, changes, binding.uid, binding.timestamp)
        }
    }

    /** Distinct protected history only; sealing grants no access, journal or sending capability. */
    suspend fun prepareAndSealTextAt(session: CatalogSession, binding: ProtonTextPreparationBinding,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject,
        protector: EventPayloadProtector): ProtonProtectedTextPreparation {
        try {
            val prepared = prepareSharedTextAt(session, binding, metadata, bootstrap, row)
            currentCoroutineContext().ensureActive()
            val record = ProtonTextPreparationCodec(protector).seal(binding, prepared)
            currentCoroutineContext().ensureActive()
            return record
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            // A synchronous protector can revoke this job and then throw an ordinary storage error.
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    /** Bind the protected title profile to a project-owned immutable local intent.
     * Only title changes are admitted here, against the complete supported imported base.
     * Observed metadata equality does not prove current rights, opaque content or remote writes.
     */
    suspend fun prepareAndSealLocalTitleEdit(session: CatalogSession, intent: LocalEditIntent,
        uid: String, timestamp: Instant, metadata: JsonObject, bootstrap: JsonObject, row: JsonObject,
        protector: EventPayloadProtector): LocalEditPreparation {
        val frozen = try { LocalEditPreparation.freezeIntent(intent) }
            catch (_: IllegalArgumentException) { unsupportedEvents() }
            catch (_: java.nio.charset.CharacterCodingException) { unsupportedEvents() }
        if (frozen.format != EVENT_FORMAT) unsupportedEvents()
        val prepared = prepareSharedTitleFragments(session, frozen.scope, frozen.base.eventId, frozen.base.revision,
            uid, frozen.base.content.title, frozen.content.title, metadata, bootstrap, row, timestamp,
            expectedBase = frozen.base)
        return ProtonTitlePreparationCodec(protector).sealLocal(frozen, uid, timestamp, prepared)
    }

    /** Offline text preparation bound to the full imported base and frozen local intent.
     * Optional property absence is observed from authenticated plaintext, never inferred from
     * an empty domain string. This distinct local format grants no persistence or upload authority.
     */
    suspend fun prepareAndSealLocalTextEdit(session: CatalogSession, intent: LocalEditIntent,
        uid: String, timestamp: Instant, metadata: JsonObject, bootstrap: JsonObject, row: JsonObject,
        protector: EventPayloadProtector): LocalTextEditPreparation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val frozen = try { LocalTextEditPreparation.freezeIntent(intent) }
            catch (_: IllegalArgumentException) { context.ensureActive(); unsupportedEvents() }
            catch (_: java.nio.charset.CharacterCodingException) { context.ensureActive(); unsupportedEvents() }
        if (frozen.format != EVENT_FORMAT) unsupportedEvents()
        val base = frozen.base.content
        val replacement = frozen.content
        fun contentValue(content: EventContent, field: ProtonSharedTextProperty): String = when (field) {
            ProtonSharedTextProperty.SUMMARY -> content.title
            ProtonSharedTextProperty.DESCRIPTION -> content.description
            ProtonSharedTextProperty.LOCATION -> content.location
        }
        // Select raw differences; normalization is only for comparing decoded signed values.
        val selected = ProtonSharedTextProperty.entries.filterTo(linkedSetOf()) {
            contentValue(base, it) != contentValue(replacement, it)
        }
        var binding: ProtonTextPreparationBinding? = null
        val prepared = prepareSharedTextFragments(session, frozen.scope, frozen.base.eventId, frozen.base.revision,
            uid, base.title, metadata, bootstrap, row, requireEmptyPersonal = true, expectedBase = frozen.base) { basis ->
            context.ensureActive()
            val observed = ProtonSharedTextPatcher.observe(basis.originalPlaintext, selected, uid)
            val changes = selected.associateWith { field ->
                val expected = observed.getValue(field)
                val old = contentValue(base, field).replace("\r\n", "\n")
                if (expected?.replace("\r\n", "\n").orEmpty() != old ||
                    (field == ProtonSharedTextProperty.SUMMARY && expected == null)) unsupportedEvents()
                val next = contentValue(replacement, field)
                ProtonSharedTextChange(expected,
                    if (field != ProtonSharedTextProperty.SUMMARY && next.isEmpty()) null else next)
            }
            binding = ProtonTextPreparationBinding(frozen.scope, frozen.base.eventId, frozen.base.revision,
                uid, base.title, changes, timestamp)
            ProtonSharedTextPatcher.patchAt(basis.originalPlaintext, changes, uid, timestamp)
        }
        context.ensureActive()
        val record = try {
            ProtonTextPreparationCodec(protector).sealLocal(frozen, binding ?: unsupportedEvents(), prepared)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { context.ensureActive(); throw failure }
        context.ensureActive()
        return record
    }

    /** Full-base temporal crypto, deliberately separate from historical text records. */
    suspend fun prepareAndSealLocalTimeEdit(session: CatalogSession, intent: LocalEditIntent,
        uid: String, timestamp: Instant, metadata: JsonObject, bootstrap: JsonObject, row: JsonObject,
        protector: EventPayloadProtector): LocalTimeEditPreparation {
        val context = currentCoroutineContext()
        context.ensureActive()
        val frozen = try { LocalTimeEditCandidate(intent).intent }
            catch (_: IllegalArgumentException) { context.ensureActive(); unsupportedEvents() }
            catch (_: java.nio.charset.CharacterCodingException) { context.ensureActive(); unsupportedEvents() }
        if (frozen.format != EVENT_FORMAT) unsupportedEvents()
        // Reuse original signature verification, full-base decode, writer selection and the
        // original shared session key. Only the plaintext policy and protected format differ.
        val prepared = prepareSharedTextFragments(session, frozen.scope, frozen.base.eventId, frozen.base.revision,
            uid, frozen.base.content.title, metadata, bootstrap, row, requireEmptyPersonal = true,
            expectedBase = frozen.base) { basis ->
            ProtonSharedTextPatcher.patchTimeAt(basis.originalPlaintext, uid, frozen.base.content.time,
                frozen.content.time, timestamp)
        }
        context.ensureActive()
        val record = try {
            ProtonTimePreparationCodec(protector).sealLocal(frozen, uid, timestamp,
                ProtonTimestampedSharedTime(prepared.originalRow, prepared.clearSharedPart,
                    prepared.encryptedSharedPart, prepared.changed))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { context.ensureActive(); throw failure }
        context.ensureActive()
        return record
    }

    private suspend fun prepareSharedTitleFragments(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String, newTitle: String,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, timestamp: Instant?,
        expectedBase: RemoteChange.Upsert? = null): ProtonTimestampedSharedTitle {
        val prepared = prepareSharedTextFragments(session, scope, expectedEventId, expectedRevision,
            expectedUid, expectedTitle, metadata, bootstrap, row, timestamp != null, expectedBase) { basis ->
            if (timestamp == null) basis.patchTitle(newTitle).patchedPlaintext
            else ProtonSharedTitlePatcher.patchAt(basis.originalPlaintext, expectedTitle, newTitle, expectedUid, timestamp)
        }
        return ProtonTimestampedSharedTitle(prepared.originalRow, prepared.clearSharedPart,
            prepared.encryptedSharedPart, prepared.changed)
    }

    private suspend fun prepareSharedTextFragments(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, requireEmptyPersonal: Boolean,
        expectedBase: RemoteChange.Upsert? = null,
        patch: (ProtonSharedTitleBasis) -> ProtonEventTextParts): ProtonTimestampedSharedText {
        try {
            val context = currentCoroutineContext()
            context.ensureActive()
            val frozenMetadata = snapshot(metadata)
            return withSharedTitle(session, scope, expectedEventId, expectedRevision, expectedUid, expectedTitle,
                bootstrap, row) { basis, keys, addresses, frozenBootstrap ->
                context.ensureActive()
                val original = basis.originalRow
                // Personal partitions can contain their own DTSTAMP; never silently retain stale dates.
                if (requireEmptyPersonal && (original.array("CalendarEvents").isNotEmpty() ||
                    original.array("AttendeesEvents").isNotEmpty())) unsupportedEvents()
                // Decode the same frozen observation with the already unlocked keys and addresses.
                // Compare before patching/signing/sealing; never normalize away an intent mismatch.
                if (expectedBase != null) {
                    val decoded = decodeRows(scope.calendar.value, frozenBootstrap, listOf(original),
                        expectedUid, keys, addresses)
                    if (decoded.deferred.isNotEmpty() || decoded.changes.singleOrNull() != expectedBase)
                        unsupportedEvents()
                }
                val selected = ProtonCreateContextPolicy.select(scope, frozenMetadata, frozenBootstrap, addresses)
                val parts = original.array("SharedEvents").map { it as JsonObject }
                val clearPart = parts.single { it.number("Type") == 2L }
                val secretPart = parts.single { it.number("Type") == 3L }
                // Preserve the legacy encrypted-author check, including its no-op contract.
                if (!selected.address.email.equals(secretPart.string("Author"), ignoreCase = true)) unsupportedEvents()
                context.ensureActive()
                val patched = patch(basis)
                context.ensureActive()
                val clearChanged = patched.clearShared != basis.originalPlaintext.clearShared
                val secretChanged = patched.encryptedShared != basis.originalPlaintext.encryptedShared
                if (!clearChanged && !secretChanged)
                    return@withSharedTitle ProtonTimestampedSharedText(original, clearPart, secretPart, changed = false)
                // A trusted original author need not be the selected writer if its part is unchanged.
                if (clearChanged && !selected.address.email.equals(clearPart.string("Author"), ignoreCase = true)) unsupportedEvents()
                GateCProtectedStorageGuard.requireAvailable(crypto.keyStoreCrypto)
                if (selected.signingKey.unlockOrNull(crypto)?.use { true } != true) missingLocalKeys()
                context.ensureActive()
                val replacementClear = if (!clearChanged) clearPart else JsonObject(clearPart + mapOf(
                    "Data" to JsonPrimitive(patched.clearShared),
                    "Signature" to JsonPrimitive(selected.signingKey.signText(crypto, patched.clearShared))))
                val replacementSecret = if (!secretChanged) secretPart else {
                    context.ensureActive()
                    val sessionKey = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(original.string("SharedKeyPacket")))
                        ?: missingLocalKeys()
                    val encrypted = sessionKey.use { key ->
                        val bytes = patched.encryptedShared.toByteArray(Charsets.UTF_8)
                        try { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes, key)) }
                        finally { bytes.fill(0) }
                    }
                    JsonObject(secretPart + mapOf("Data" to JsonPrimitive(encrypted),
                        "Signature" to JsonPrimitive(selected.signingKey.signText(crypto, patched.encryptedShared))))
                }
                context.ensureActive()
                ProtonTimestampedSharedText(original, replacementClear, replacementSecret, changed = true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: RemoteImportException) {
            // Key callbacks can cancel and fail before returning to the normal activity check.
            currentCoroutineContext().ensureActive()
            throw failure
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            unsupportedEvents()
        }
    }

    private suspend fun <T> withSharedTitle(session: CatalogSession, scope: CalendarScope, expectedEventId: EventId,
        expectedRevision: Revision, expectedUid: String, expectedTitle: String,
        bootstrap: JsonObject, row: JsonObject,
        action: (ProtonSharedTitleBasis, KeyHolderContext, List<UserAddress>, JsonObject) -> T): T {
        try {
            if (scope.account.value != session.userId.id) unsupportedEvents()
            // Snapshot before the first suspension, including settings used in the revision.
            val frozenRow = snapshot(row)
            val frozenBootstrap = snapshot(bootstrap)
            if (frozenBootstrap.obj("CalendarSettings").string("CalendarID") != scope.calendar.value) unsupportedEvents()
            if (frozenRow.string("CalendarID") != scope.calendar.value || frozenRow.string("ID") != expectedEventId.value ||
                frozenRow.string("UID") != expectedUid || frozenRow.revision(frozenBootstrap) != expectedRevision ||
                frozenRow.optionalString("AddressKeyPacket") != null) unsupportedEvents()
            val parts = frozenRow.array("SharedEvents").map { it as? JsonObject ?: unsupportedEvents() }
            if (parts.map { it.number("Type") }.sorted() != listOf(2L, 3L)) unsupportedEvents()
            // Preserve all opaque metadata/personal/attendee parts; do not interpret or certify them.
            return withCalendarKeys(session.userId, scope.calendar.value, frozenBootstrap) { keys, addresses ->
                if (addresses.any { it.userId != session.userId }) unsupportedEvents()
                val clearPart = parts.single { it.number("Type") == 2L }
                val secretPart = parts.single { it.number("Type") == 3L }
                val clear = clearPart.string("Data")
                val secret = keys.decryptPart(frozenRow.string("SharedKeyPacket"), secretPart.string("Data"))
                val budget = EventTextBudget()
                budget.consume(clear)
                budget.consume(secret)
                if (!verify(clearPart, clear, addresses) || !verify(secretPart, secret, addresses)) unsupportedEvents()
                val plain = ProtonEventTextParts(clear, secret)
                // Bind both signed iCalendar UIDs, not merely the unsigned row UID.
                ProtonSharedTitlePatcher.patch(plain, expectedTitle, expectedTitle, expectedUid)
                val basis = ProtonSharedTitleBasis(scope, expectedEventId, expectedRevision, expectedUid, expectedTitle,
                    frozenRow.toString(), plain)
                action(basis, keys, addresses, frozenBootstrap)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: RemoteImportException) { throw failure }
          catch (_: Exception) { unsupportedEvents() }
    }

    private fun snapshot(value: JsonObject): JsonObject {
        val text = value.toString()
        if (text.length > 4_194_304 || text.toByteArray(Charsets.UTF_8).size > 4_194_304) unsupportedEvents()
        return Json.parseToJsonElement(text) as? JsonObject ?: unsupportedEvents()
    }

    private suspend fun <T> withCalendarKeys(user: UserId, calendar: String, bootstrap: JsonObject,
        action: (KeyHolderContext, List<UserAddress>) -> T): T {
        val addresses = localAddresses.get(user)
        currentCoroutineContext().ensureActive()
        if (addresses.isEmpty() || addresses.none { address -> address.keys.any { it.privateKey.isActive } }) missingLocalKeys()
        return try {
        val passphrase = bootstrap.obj("Passphrase")
        if (passphrase.string("CalendarID") != calendar || passphrase.number("Flags") != 1L) unsupportedEvents()
        val members = bootstrap.array("Members").map { it as? JsonObject ?: unsupportedEvents() }
        if (members.any { it.string("CalendarID") != calendar || it.string("ID").isBlank() } ||
            members.map { it.string("ID") }.distinct().size != members.size) unsupportedEvents()
        val memberPassphrases = passphrase.array("MemberPassphrases").map { it as? JsonObject ?: unsupportedEvents() }
        if (memberPassphrases.any { it.string("MemberID").isBlank() } ||
            memberPassphrases.map { it.string("MemberID") }.distinct().size != memberPassphrases.size) unsupportedEvents()
        val secret = members.firstNotNullOfOrNull { member ->
            val address = addresses.firstOrNull { it.addressId.id == member.optionalString("AddressID") } ?: return@firstNotNullOfOrNull null
            val packet = memberPassphrases.firstOrNull { it.string("MemberID") == member.string("ID") } ?: return@firstNotNullOfOrNull null
            address.useKeys(crypto) {
                val plain = decryptTextOrNull(packet.string("Passphrase")) ?: return@useKeys null
                if (publicKeyRing.keys.any { it.isActive && crypto.pgpCrypto.verifyText(plain, packet.string("Signature"), it.key) }) plain else null
            }
        } ?: missingLocalKeys()
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val encryptedSecret = try { PlainByteArray(secretBytes).encrypt(crypto.keyStoreCrypto) } finally { secretBytes.fill(0) }
        val keys = bootstrap.array("Keys").map { it as? JsonObject ?: unsupportedEvents() }.filter {
            if (it.string("CalendarID") != calendar) unsupportedEvents()
            it.number("Flags") and 1L != 0L
        }.map {
            if (it.string("PassphraseID") != passphrase.string("ID")) unsupportedEvents()
            PrivateKey(it.string("PrivateKey"), it.number("Flags") == 3L, passphrase = encryptedSecret)
        }
        if (keys.isEmpty()) missingLocalKeys()
        if (keys.none { it.unlockOrNull(crypto)?.use { true } == true }) missingLocalKeys()
        KeyHolderContext(crypto, PrivateKeyRing(crypto, keys), PublicKeyRing(emptyList())).use { calendarKeys ->
            action(calendarKeys, addresses)
        }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: RemoteImportException) { throw failure }
          catch (_: Exception) { unsupportedEvents() }
    }

    private fun decodeRows(calendar: String, bootstrap: JsonObject, rows: List<JsonObject>, expectedUid: String?,
        calendarKeys: KeyHolderContext, addresses: List<UserAddress>): EventDecodeResult {
        val pageBudget = EventDecodeBudget()
        val changes = mutableListOf<RemoteChange.Upsert>()
        val deferred = mutableListOf<DeferredEventContent>()
        rows.forEach { row ->
            // Identity is validated outside the per-event recovery boundary.
            if (row.string("CalendarID") != calendar || row.string("ID").isBlank()) unsupportedEvents()
            val eventId = EventId(row.string("ID"))
            val revision = row.revision(bootstrap)
            val eventBudget = EventTextBudget()
            try {
            val texts = mutableListOf<String>()
            for (field in listOf("SharedEvents", "CalendarEvents", "AttendeesEvents")) {
                for (element in row.array(field)) {
                    val part = element as? JsonObject ?: unsupportedEvents()
                    val type = part.number("Type")
                    if (type !in 0..3) unsupportedEvents()
                    val plain = if (type and 1L == 0L) part.string("Data") else {
                        if (field != "CalendarEvents" && row.optionalString("AddressKeyPacket") != null) {
                            val address = addresses.firstOrNull { it.addressId.id == row.optionalString("AddressID") } ?: unsupportedEvents()
                            address.useKeys(crypto) { decryptPart(row.string("AddressKeyPacket"), part.string("Data")) }
                        } else calendarKeys.decryptPart(row.optionalString(if (field == "CalendarEvents") "CalendarKeyPacket" else "SharedKeyPacket")
                            ?: unsupportedEvents(), part.string("Data"))
                    }
                    eventBudget.consume(plain)
                    if (type and 2L != 0L && !verify(part, plain, addresses)) throw UnverifiedEventContent()
                    texts.add(plain)
                }
            }
            val content = ProtonICalendar.parse(texts, row, bootstrap, expectedUid).copy(colorRgb = row.eventColorRgb())
            pageBudget.accept(eventBudget)
            changes.add(RemoteChange.Upsert(eventId, revision, content))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: UnverifiedEventContent) {
                deferred.add(DeferredEventContent(eventId, revision, DeferredEventReason.UNVERIFIED_CONTENT))
            } catch (failure: RemoteImportException) {
                if (failure.reason != RemoteImportFailure.UNSUPPORTED) throw failure
                deferred.add(DeferredEventContent(eventId, revision, DeferredEventReason.UNSUPPORTED_CONTENT))
            }
        }
        return EventDecodeResult(changes, deferred)
    }
    private fun missingLocalKeys(): Nothing = throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
    private fun KeyHolderContext.decryptPart(packet: String, data: String): String {
        return try {
            val key = decryptSessionKeyOrNull(Base64.getDecoder().decode(packet)) ?: unsupportedEvents()
            val bytes = key.use { decryptDataOrNull(Base64.getDecoder().decode(data), it) } ?: unsupportedEvents()
            try {
                if (bytes.size > 1_048_576) unsupportedEvents()
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { unsupportedEvents() }
    }
    private fun verify(part: JsonObject, plain: String, addresses: List<UserAddress>): Boolean {
        // External author pinning is intentionally unsupported until its contact/key trust path is qualified.
        val address = addresses.firstOrNull { it.email.equals(part.string("Author"), ignoreCase = true) } ?: return false
        val signature = part.optionalString("Signature") ?: return false
        return address.useKeys(crypto) {
            publicKeyRing.keys.any {
                // Core signText canonicalizes text (including line-end whitespace). Keep binary
                // signatures compatible, then use Proton's matching text verifier on the same key.
                it.verifyData(crypto, plain.toByteArray(Charsets.UTF_8), signature) ||
                    it.verifyText(crypto, plain, signature)
            }
        }
    }
}

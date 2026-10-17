// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Supplied temporal observations only; exact signed parts AND API dates must match.
 * This grants no authentication, dispatch, persistence or native acknowledgement authority.
 */
internal class ProtonTimeUpdateReadbackVerifier(
    private val codec: ProtonTimeUpdateRequestCodec,
    private val verifier: CreateEventVerifier,
) {
    suspend fun verify(session: CatalogSession, record: ProtonProtectedTimeUpdateRequest,
        currentContext: JsonObject, bootstrap: JsonObject, row: JsonObject): RemoteChange.Upsert? = try {
        currentCoroutineContext().ensureActive()
        // No caller-owned JSON or mutable detail lists survive the first codec suspension.
        val frozen = ProtonProtectedTimeUpdateRequest(record.id, record.preparation, record.format, record.protectedPayload)
        val contextSnapshot = snapshot(currentContext)
        val bootstrapSnapshot = snapshot(bootstrap)
        val rowSnapshot = snapshot(row)
        currentCoroutineContext().ensureActive()
        val preparation = frozen.preparation
        val intent = preparation.intent
        if (session.userId.id != intent.scope.account.value) null else {
            // Codec errors describe invalid/unavailable retained history and keep their closed categories.
            val request = codec.open(frozen)
            currentCoroutineContext().ensureActive()
            val fragments = request.fragments
            if (contextSnapshot != request.context ||
                ProtonSimpleEventUpdate.hash(bootstrapSnapshot.toString()) != request.context.string("bootstrap") ||
                rowSnapshot.string("ID") != intent.base.eventId.value ||
                rowSnapshot.string("CalendarID") != intent.scope.calendar.value ||
                !ProtonTimeEventUpdate.matchesReadback(fragments.originalRow, fragments.clearSharedPart,
                    fragments.encryptedSharedPart, rowSnapshot, intent.base.content.time, intent.content.time) ||
                !CreatedEventReadbackPolicy.KNOWN_EMPTY_V2.acceptsExpected(intent.content)) null else {
                val expectedRevision = rowSnapshot.revision(bootstrapSnapshot)
                if (expectedRevision == intent.base.revision) null else {
                    // A supplied decoder receives independent copies, never the comparison snapshots.
                    val decoded = try {
                        verifier.verify(session, intent.scope.calendar.value, snapshot(bootstrapSnapshot), snapshot(rowSnapshot),
                            fragments.originalRow.string("UID"), CreatedEventReadbackPolicy.KNOWN_EMPTY_V2)
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
                    currentCoroutineContext().ensureActive()
                    if (decoded == null || decoded.eventId != intent.base.eventId || decoded.revision != expectedRevision ||
                        !CreatedEventReadbackPolicy.KNOWN_EMPTY_V2.acceptsExpected(decoded.content) ||
                        canonicalText(decoded.content) != canonicalText(intent.content)) null
                    else decoded.copy(content = decoded.content.copy(details = EventDetails()))
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: ProtonTimeUpdateRequestException) { currentCoroutineContext().ensureActive(); throw failure }
      catch (_: Exception) { currentCoroutineContext().ensureActive(); null }

    private fun snapshot(value: JsonObject): JsonObject {
        val encoded = value.toString()
        // Bound before parsing, and before encoding obviously oversized character sequences.
        require(encoded.length <= LocalTimeEditPreparation.MAX_PAYLOAD_BYTES)
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(encoded))
        require(bytes.remaining() <= LocalTimeEditPreparation.MAX_PAYLOAD_BYTES)
        return Json.parseToJsonElement(encoded) as JsonObject
    }

    // The raw retained intent and returned decoded value remain untouched. No zone/Unicode/space normalization.
    private fun canonicalText(content: EventContent) = content.copy(title = content.title.replace("\r\n", "\n"),
        description = content.description.replace("\r\n", "\n"), location = content.location.replace("\r\n", "\n"))
}

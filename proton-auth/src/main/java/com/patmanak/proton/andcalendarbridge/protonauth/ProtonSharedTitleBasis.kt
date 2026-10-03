// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Ephemeral original shared plaintext verified by the local decryptor. Not a trusted read receipt.
 * Scope/revision/row metadata are caller bindings, not authenticated by the shared signatures.
 * Unknown row fields are retained structurally; their semantics/signatures are not qualified.
 * This internal container must only be instantiated after verification in openSharedTitle.
 */
internal class ProtonSharedTitleBasis internal constructor(
    val scope: CalendarScope,
    val eventId: EventId,
    val revision: Revision,
    private val uid: String,
    private val title: String,
    private val originalJson: String,
    val originalPlaintext: ProtonEventTextParts
) {
    // A new deep copy prevents callers from modifying the retained original via backing maps.
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject

    fun patchTitle(newTitle: String): ProtonSharedTitleDraft = ProtonSharedTitleDraft(originalJson,
        ProtonSharedTitlePatcher.patch(originalPlaintext, title, newTitle, uid))

    override fun toString() = "ProtonSharedTitleBasis(redacted)"
}

/** Original signatures apply only to originalRow, NEVER to patchedPlaintext.
 * Deliberately exposes no request assembly, re-encryption or signing operation.
 * No metadata timestamp/sequence changes, rights or concurrent-write guarantees.
 */
internal class ProtonSharedTitleDraft internal constructor(
    private val originalJson: String,
    val patchedPlaintext: ProtonEventTextParts
) {
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    override fun toString() = "ProtonSharedTitleDraft(redacted)"
}

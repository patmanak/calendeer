// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Opt-in qualification only; neither account names nor raw response content are returned. */
object ProtonCreateSubmissionProbe {
    suspend fun matchesAccount(context: Context, account: AccountId, expectedIdentityHash: String): Boolean =
        ProtonAuthentication.matchesIdentityHash(context, account, expectedIdentityHash)

    fun responseSummary(record: CreateAttemptRecord): String {
      return try {
        val sealed = record.protectedEvidence ?: return "EVIDENCE_ABSENT"
        val protector = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
        val evidence = Json.parseToJsonElement(protector.open(sealed)).jsonObject
        if (evidence["complete"]?.jsonPrimitive?.booleanOrNull != true) "TRANSPORT_INCOMPLETE"
        else {
            val body = Json.parseToJsonElement(evidence.string("body")).jsonObject
            val topCode = body["Code"]?.jsonPrimitive?.intOrNull ?: -1
            val items = body["Responses"] as? JsonArray
            val itemCode = (items?.singleOrNull() as? JsonObject)?.get("Response")?.jsonObject
                ?.get("Code")?.jsonPrimitive?.intOrNull ?: -1
            "TOP_CODE=$topCode ITEM_CODE=$itemCode"
        }
      } catch (_: Exception) { "EVIDENCE_UNREADABLE" }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import org.json.JSONObject
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Immutable receipt metadata only; no request or fabricated remote deletion. */
internal object DeleteAcknowledgementCodec {
    private const val MAX_BYTES = 1024 * 1024
    private fun budget(value: String) {
        require(value.length <= MAX_BYTES)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= MAX_BYTES)
    }
    fun encode(value: DeleteAcknowledgementReceipt): String = sanitized {
        val r = value.snapshot(); val a = r.attempt
        JSONObject().apply {
            put("kind", "delete-acknowledgement"); put("schema", 1)
            put("attempt", JSONObject().apply {
                put("account", a.scope.account.value); put("calendar", a.scope.calendar.value); put("generation", a.scope.generation)
                put("id", a.id.value); put("intent", a.localIntentId.value); put("event", a.eventId.value)
                put("revision", a.baseRevision.value); put("adapter", a.format.id); put("version", a.format.version)
                put("intentFingerprint", a.intentFingerprint); put("payloadFingerprint", a.payloadFingerprint)
            })
            put("adapter", r.intent.format.id); put("version", r.intent.format.version)
            put("base", JSONObject(TimeUpdateAcknowledgementCodec.encodeChange(r.intent.base)))
            put("nonce", r.nonce)
        }.toString().also(::budget)
    }
    fun decode(payload: String): DeleteAcknowledgementReceipt = sanitized {
        budget(payload); val j = JSONObject(payload)
        fun text(o: JSONObject, k: String): String = o.get(k).let { require(it is String); it }
        fun number(o: JSONObject, k: String): Long = o.get(k).let { require(it is Int || it is Long); (it as Number).toLong() }
        fun version(o: JSONObject, k: String): Int = number(o,k).let { require(it in 1..Int.MAX_VALUE.toLong()); it.toInt() }
        require(j.get("kind") == "delete-acknowledgement" && number(j,"schema") == 1L)
        val a = j.getJSONObject("attempt")
        val s = CalendarScope(AccountId(text(a,"account")), CalendarId(text(a,"calendar")), number(a,"generation"))
        val identity = DeleteAttemptIdentity(s, MutationId(text(a,"id")), MutationId(text(a,"intent")),
            EventId(text(a,"event")), Revision(text(a,"revision")), AdapterFormat(text(a,"adapter"),version(a,"version")),
            text(a,"intentFingerprint"),text(a,"payloadFingerprint"))
        val base = TimeUpdateAcknowledgementCodec.decodeChange(j.getJSONObject("base").toString()) as RemoteChange.Upsert
        val intent = LocalDeleteIntent(identity.localIntentId,s,AdapterFormat(text(j,"adapter"),version(j,"version")),base,base.content)
        val receipt = DeleteAcknowledgementReceipt(identity,intent,text(j,"nonce"))
        require(encode(receipt) == payload)
        receipt
    }
    private inline fun <T> sanitized(block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid delete acknowledgement receipt") }
}

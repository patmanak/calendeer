// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Synthetic HTTP boundary: actual admission, single-use body and crypto; no socket or service. */
internal class ProtonSeriesCreateDispatchProbe(initial: CreateAttemptRecord, private val lostResponse: Boolean) : CreateAttemptJournal {
    var stored = initial
        private set
    var sends = 0
        private set
    var visible = false
        private set
    private val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "calendeer.invalid" && request.method == "PUT")
        check(request.body!!.isOneShot())
        Buffer().use { buffer ->
            request.body!!.writeTo(buffer)
            val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            check(root.array("Events").size == 1)
            check(root.array("Events").single().jsonObject.obj("Event").array("SharedEventContent").size == 2)
        }
        sends++
        check(sends == 1)
        if (lostResponse) throw IOException("Synthetic response loss")
        visible = true
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
            .body("{}".toResponseBody("application/json".toMediaType())).build()
    }.build()

    fun expose() { visible = true }
    fun send(request: JsonObject, callerClaimed: Boolean = false): String {
        check(callerClaimed || stored.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val attempt = ProtonSingleDispatchAttempt("https://calendeer.invalid/calendar/v1/synthetic/events/sync".toHttpUrl(), request.toString())
        return client.newCall(attempt.request()).execute().use { it.body!!.string() }
    }
    suspend fun qualify(adapter: CalendarCreateSubmissionPort, draft: CalendarCreateDraft) {
        val before = stored
        val first = adapter.submitCaptured(before, draft, this)
        check(sends == 1 && stored.state == CreateAttemptState.OUTCOME_UNKNOWN)
        check(if (lostResponse) first == CreateSubmissionObservation.Unknown else first is CreateSubmissionObservation.Verified)
        visible = true
        // Deliberately replay the stale PREPARED snapshot: durable UNKNOWN must win.
        repeat(2) { check(adapter.submitCaptured(before, draft, this) is CreateSubmissionObservation.Verified) }
        check(sends == 1)
    }
    override suspend fun remember(record: CreateAttemptRecord) { check(record == stored) }
    override suspend fun read(scope: CalendarScope, id: MutationId) = stored.takeIf { it.scope == scope && it.id == id }
    override suspend fun claim(expected: CreateAttemptRecord): Boolean {
        if (stored != expected || stored.state != CreateAttemptState.PREPARED) return false
        stored = stored.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        return true
    }
    override suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String) {
        check(stored == expectedUnknown)
        stored = stored.copy(protectedEvidence = protectedEvidence)
    }
}

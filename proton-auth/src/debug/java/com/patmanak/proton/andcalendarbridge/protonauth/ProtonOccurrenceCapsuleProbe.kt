// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Synthetic Keystore E3 only; no account, network, signing key or service data. */
object ProtonOccurrenceCapsuleProbe {
    fun verify(): Boolean {
        val scope = CalendarScope(AccountId("capsule-fixture"), CalendarId("calendar"), 0)
        val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Synthetic master",
            EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-02")), details = EventDetails(),
            recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
        val content = master.content.copy(title = "Synthetic child", recurrence = null)
        val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"),
            LocalEventId("11111111-2222-3333-4444-555555555555"), scope, AdapterFormat("fixture", 1),
            Instant.parse("2030-04-01T12:00:00Z")), content, occurrence = OccurrenceEditCandidate(master,
                RecurrencePoint.AllDay(LocalDate.parse("2030-04-02")), content))
        fun json(value: String) = Json.parseToJsonElement(value).jsonObject
        val source = json("""{"ID":"master","CalendarID":"calendar","UID":"master@calendeer.invalid","Future":{"preserved":true}}""")
        val request = json("""{"MemberID":"member","IsImport":0,"Events":[{"Overwrite":0,"Event":{"SharedKeyPacket":"synthetic","SharedEventContent":[{"Type":2,"Data":"synthetic"}],"Notifications":null,"Color":null,"IsPersonalSingleEdit":true}}]}""")
        val protector = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
        val id = MutationId("attempt")
        val codec = ProtonOccurrenceSubmissionCodec(protector)
        val sealed = codec.seal(id, intent, json("""{"member":"member"}"""), source, request, JsonNull)
        check(!sealed.contains("Synthetic child") && sealed != protector.open(sealed))
        val unknown = CreateAttemptRecord(id, scope, OCCURRENCE_CREATE_SUBMISSION_FORMAT, sealed,
            CreateAttemptState.OUTCOME_UNKNOWN, "synthetic-evidence")
        val reopened = ProtonOccurrenceSubmissionCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
            .open(unknown, intent)
        check(reopened.intent == intent && reopened.source == source && reopened.request == request)
        check(unknown.state == CreateAttemptState.OUTCOME_UNKNOWN && unknown.protectedEvidence == "synthetic-evidence")
        val foreign = intent.copy(reservation = intent.reservation.copy(id = MutationId("foreign")))
        check(runCatching { codec.open(unknown, foreign) }.isFailure)
        check(runCatching { ProtonCreateSubmissionCodec.open(unknown, protector) }.isFailure)
        val changed = unknown.copy(protectedPayload = sealed.dropLast(4) + "xxxx")
        check(runCatching { codec.open(changed, intent) }.isFailure)
        return true
    }
}

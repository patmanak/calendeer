package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import org.json.JSONArray

/** The three protected strings are produced by the authenticated adapter's existing local storage protector. */
internal class GuestJournalRecord private constructor(
    val owner: PersonalUpdateAttemptIdentity, val protectedContext: String, val protectedMail: String,
    val protectedCalendar: String, val binding: GuestDeliveryBinding, val progress: GuestDeliveryProgress,
    val protectedProof: String?,
) {
    fun advance(step: GuestDeliveryProgress?, proof: String? = null): GuestJournalRecord? {
        if (step == null || proof?.let(::hash) != step.calendarProofHash) return null
        val bound = GuestDeliveryProgress.restore(binding, step.encode()) ?: return null
        return GuestJournalRecord(owner, protectedContext, protectedMail, protectedCalendar, binding, bound, proof)
    }

    fun same(other: GuestJournalRecord) = owner == other.owner && protectedContext == other.protectedContext &&
        protectedMail == other.protectedMail && protectedCalendar == other.protectedCalendar &&
        progress.encode().contentEquals(other.progress.encode()) && protectedProof == other.protectedProof

    fun encode(): ByteArray = JSONArray().apply {
        put(1); put(owner.scope.account.value); put(owner.scope.calendar.value); put(owner.scope.generation)
        put(owner.id.value); put(owner.localIntentId.value); put(owner.eventId.value); put(owner.baseRevision.value)
        put(protectedContext); put(protectedMail); put(protectedCalendar); put(progress.encode().toString(Charsets.US_ASCII))
        if (protectedProof != null) put(protectedProof)
    }.toString().encodeToByteArray(throwOnInvalidSequence = true).also { require(it.size <= MAX_BYTES) }

    override fun toString() = "GuestJournalRecord(redacted)"

    companion object {
        const val MAX_BYTES = 16_777_216
        fun hash(value: String): String {
            require(value.isNotBlank() && value.length <= 2_097_152)
            val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            require(bytes.size <= 2_097_152)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        }

        fun prepared(scope: CalendarScope, id: MutationId, local: MutationId, event: EventId, base: Revision,
            protectedContext: String, protectedMail: String, protectedCalendar: String): GuestJournalRecord {
            val mail = hash(protectedMail)
            val calendar = hash(protectedCalendar)
            val owner = PersonalUpdateAttemptIdentity(scope, id, local, event, base, AdapterFormat("guest-delivery", 1),
                AdapterFormat("guest-preparation", 1), hash(protectedContext), hash(mail + calendar))
            val binding = GuestDeliveryBinding.of(owner, mail, calendar)
            return GuestJournalRecord(owner, protectedContext, protectedMail, protectedCalendar, binding,
                GuestDeliveryProgress.prepared(binding), null)
        }

        fun decode(bytes: ByteArray): GuestJournalRecord {
            require(bytes.size in 1..MAX_BYTES)
            val row = JSONArray(bytes.decodeToString(throwOnInvalidSequence = true))
            require(row.length() in 12..13 && row.get(0) == 1)
            fun text(i: Int) = row.get(i) as String
            val gen = row.get(3)
            require(gen is Long || gen is Int)
            val scope = CalendarScope(AccountId(text(1)), CalendarId(text(2)), (gen as Number).toLong())
            val start = prepared(scope, MutationId(text(4)), MutationId(text(5)), EventId(text(6)), Revision(text(7)),
                text(8), text(9), text(10))
            val progress = requireNotNull(GuestDeliveryProgress.restore(start.binding, text(11).toByteArray(Charsets.US_ASCII)))
            val proof = if (row.length() == 13) text(12) else null
            require(proof?.let(::hash) == progress.calendarProofHash)
            return requireNotNull(start.advance(progress, proof)).also { require(it.encode().contentEquals(bytes)) }
        }
    }
}

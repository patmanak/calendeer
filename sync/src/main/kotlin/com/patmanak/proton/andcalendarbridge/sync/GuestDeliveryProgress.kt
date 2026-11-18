package com.patmanak.proton.andcalendarbridge.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

private val guestDigest = Regex("[a-f0-9]{64}")

private fun guestHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

/** Exact historical identity shared by the two claims. Never constructed from a Provider row ID. */
class GuestDeliveryBinding private constructor(internal val hash: String) {
    companion object {
        fun of(owner: PersonalUpdateAttemptIdentity, mailRequestHash: String, calendarRequestHash: String): GuestDeliveryBinding {
            require(guestDigest.matches(mailRequestHash) && guestDigest.matches(calendarRequestHash))
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                for (field in listOf("calendeer-guest-requests-v1", owner.scope.account.value, owner.scope.calendar.value,
                    owner.scope.generation.toString(), owner.id.value, owner.localIntentId.value, owner.eventId.value,
                    owner.baseRevision.value, owner.format.id, owner.format.version.toString(), owner.preparationFormat.id,
                    owner.preparationFormat.version.toString(), owner.preparationFingerprint, owner.payloadFingerprint,
                    mailRequestHash, calendarRequestHash)) {
                    val utf8 = field.toByteArray(Charsets.UTF_8)
                    output.writeInt(utf8.size)
                    output.write(utf8)
                }
            }
            return GuestDeliveryBinding(guestHash(bytes.toByteArray()))
        }
    }
    override fun toString() = "GuestDeliveryBinding(redacted)"
}

enum class GuestDeliveryPhase(internal val tag: String) {
    MAIL_READY("1"), MAIL_UNKNOWN("2"), CALENDAR_READY("3"), CALENDAR_UNKNOWN("4"), BOTH_PROVEN("5")
}

/**
 * Inner progress record for a versioned update capsule. Persistence must compare-and-swap the full prior record
 * and owner atomically before a send. An in-memory transition alone grants no authority to send or acknowledge.
 */
class GuestDeliveryProgress private constructor(
    private val binding: GuestDeliveryBinding,
    val phase: GuestDeliveryPhase,
    val deliveryTime: Long? = null,
    val calendarProofHash: String? = null,
) {
    fun claimMail() = if (phase == GuestDeliveryPhase.MAIL_READY)
        GuestDeliveryProgress(binding, GuestDeliveryPhase.MAIL_UNKNOWN) else null

    fun mailAccepted(responseOwner: GuestDeliveryBinding, time: Long) =
        if (phase == GuestDeliveryPhase.MAIL_UNKNOWN && binding.hash == responseOwner.hash && time in 1..Int.MAX_VALUE.toLong())
            GuestDeliveryProgress(binding, GuestDeliveryPhase.CALENDAR_READY, time) else null

    fun claimCalendar() = if (phase == GuestDeliveryPhase.CALENDAR_READY)
        GuestDeliveryProgress(binding, GuestDeliveryPhase.CALENDAR_UNKNOWN, deliveryTime) else null

    fun calendarVerified(responseOwner: GuestDeliveryBinding, proofHash: String) =
        if (phase == GuestDeliveryPhase.CALENDAR_UNKNOWN && binding.hash == responseOwner.hash && guestDigest.matches(proofHash))
            GuestDeliveryProgress(binding, GuestDeliveryPhase.BOTH_PROVEN, deliveryTime, proofHash) else null

    fun encode(): ByteArray {
        val core = listOf("1", binding.hash, phase.tag, deliveryTime?.toString() ?: "-", calendarProofHash ?: "-")
            .joinToString("|")
        return (core + "|" + guestHash(core.toByteArray(Charsets.US_ASCII))).toByteArray(Charsets.US_ASCII)
    }

    companion object {
        fun prepared(binding: GuestDeliveryBinding) = GuestDeliveryProgress(binding, GuestDeliveryPhase.MAIL_READY)

        /** The checksum detects corruption. The enclosing authenticated capsule and SQL CAS prevent replay. */
        fun restore(binding: GuestDeliveryBinding, bytes: ByteArray): GuestDeliveryProgress? {
            if (bytes.size !in 1..256) return null
            val fields = bytes.toString(Charsets.US_ASCII).split('|')
            if (fields.size != 6 || fields[0] != "1" || fields[1] != binding.hash) return null
            val phase = GuestDeliveryPhase.entries.singleOrNull { it.tag == fields[2] } ?: return null
            val time = fields[3].toLongOrNull()
            val needsMailReceipt = phase in setOf(GuestDeliveryPhase.CALENDAR_READY,
                GuestDeliveryPhase.CALENDAR_UNKNOWN, GuestDeliveryPhase.BOTH_PROVEN)
            if (needsMailReceipt && (time == null || time !in 1..Int.MAX_VALUE.toLong())) return null
            if (!needsMailReceipt && fields[3] != "-") return null
            val proof = fields[4].takeUnless { it == "-" }
            if (phase == GuestDeliveryPhase.BOTH_PROVEN && (proof == null || !guestDigest.matches(proof))) return null
            if (phase != GuestDeliveryPhase.BOTH_PROVEN && proof != null) return null
            val restored = GuestDeliveryProgress(binding, phase, time, proof)
            return restored.takeIf { it.encode().contentEquals(bytes) }
        }
    }
    override fun toString() = "GuestDeliveryProgress(redacted)"
}

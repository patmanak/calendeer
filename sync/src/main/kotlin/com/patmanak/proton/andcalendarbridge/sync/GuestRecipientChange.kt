package com.patmanak.proton.andcalendarbridge.sync

sealed interface GuestRecipientChange {
    data class Request(val guest: EventAttendee) : GuestRecipientChange
    data class Cancel(val guest: EventAttendee) : GuestRecipientChange
}

/**
 * Resolves one organizer-side recipient from complete, same-event snapshots.
 * Authentication of the base, account ownership, and authority to send are caller responsibilities.
 * A null result means there is no supported guest-only change.
 */
fun guestRecipientChange(
    authenticatedOrganizer: EventPerson,
    base: EventDetails,
    edited: EventDetails,
): GuestRecipientChange? {
    if (base.organizer != authenticatedOrganizer || edited.organizer != authenticatedOrganizer ||
        base.reminders != edited.reminders || !supportedGuestMailbox(authenticatedOrganizer.email)) return null

    val before = base.attendees.uniqueRecipients() ?: return null
    val after = edited.attendees.uniqueRecipients() ?: return null
    val addresses = before.keys + after.keys
    if (addresses.any { it.equals(authenticatedOrganizer.email, ignoreCase = true) || !supportedGuestMailbox(it) })
        return null
    if ((before.keys intersect after.keys).any { before[it] != after[it] }) return null

    val address = addresses.singleOrNull { it !in before || it !in after } ?: return null
    val added = after[address]
    val guest = added ?: before.getValue(address)
    if (guest.role !in setOf(AttendanceRole.REQUIRED, AttendanceRole.OPTIONAL)) return null
    if (added == null) return GuestRecipientChange.Cancel(guest)
    return if (guest.status == AttendanceStatus.NEEDS_ACTION) GuestRecipientChange.Request(guest) else null
}

private fun List<EventAttendee>.uniqueRecipients(): Map<String, EventAttendee>? {
    val snapshot = toList()
    if (snapshot.size > 100) return null
    val unique = snapshot.distinctBy { it.person.email.lowercase(java.util.Locale.ROOT) }
    return if (unique.size == snapshot.size) snapshot.associateBy { it.person.email } else null
}

// Deliberately bounded to ordinary ASCII mailboxes. Preserve the exact address used by the source.
private val guestLocalPart = Regex("[A-Za-z0-9_+%-]+(?:[.][A-Za-z0-9_+%-]+)*")
private val guestDomainLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

private fun supportedGuestMailbox(address: String): Boolean {
    val local = address.substringBefore('@')
    val domain = address.substringAfter('@')
    return address.length <= 254 && local.length <= 64 && local.matches(guestLocalPart) &&
        '.' in domain && domain.split('.').all { it.matches(guestDomainLabel) }
}

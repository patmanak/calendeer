# Organizer guest changes

Source inspection on 2026-11-18. V05 requires both a changed guest list on
Proton web and an invitation or cancellation received by the guest. Android
Calendar release/2.34.1 at `dbc20fa485ca9a3e02988ec8f430145fc9e0b1af` and
the published Proton Core 36.8.0 Mail sources establish the following contract.
No service delivery or third-party integration was tested in this study.

## Two independent service operations

| Change on an existing organizer event | Mail recipient and attachment |
| --- | --- |
| Add a guest | Resolve send preferences for the added address. Upgrade the event if needed, obtain its shared ID and decrypt the shared session key with the calendar keys. Build a METHOD:REQUEST invitation from the edited event with X-PM-SHARED-EVENT-ID and X-PM-SESSION-KEY. The latter contains secret key material. |
| Remove a guest | Address the removed guest using the original event. METHOD:CANCEL carries the shared ID and a refreshed DTSTAMP. The shared session key and event STATUS are omitted. |

For a guest-only edit the Android client sends these messages before saving the
event change. It blocks on an error adding a guest, but the caller can discard
the returned cancellation error on removal. Calendeer must retain both outcomes.
The calendar write uses `PUT calendar/v1/{calendarId}/events/sync`; its schema includes
AttendeesEventContent, Attendees, AddedProtonAttendees and RemovedAttendeeAddresses.
The client signs the attendee fragment, encrypts it under the shared session key,
preserves or derives attendee tokens and wraps the shared session key to each
eligible added Proton guest's public address key. The per-attendee participation
route performs a different operation.
[Save orchestration][save] · [Mail attachment preparation][email] ·
[ICS construction][ics] · [Calendar mutation][edit] · [Calendar wire schema][api]

The official client consults public address keys, contacts and Mail settings
for each recipient. It constructs attachment and body packets and generates
recipient-specific packages before submission. Core exposes
`POST mail/v4/messages/send/direct`; the Android request includes an encrypted
email message, packages and attachment keys with contact autosave disabled.
Its receipt contains DeliveryTime. The response schema supplies neither a
message identifier nor a recipient mailbox receipt.
[Preferences][prefs] · [Package assembly][direct] · [Submission][repository] · [Core sources][core]

## Reuse and current capability

- Calendeer already resolves biweekly 0.6.8 and Core key, crypto, network and
  account modules at 36.8.0. Its session coordinator retains service-issued
  scopes at sign-in, second-factor completion and refresh. The production
  missing-scope listener returns failure. A test fixture
  returns `full` and `mail`; that fixture establishes no grant on a real account.
  Neither a scope name alone nor a successful calendar sync establishes the
  ability to read preferences/keys or send Mail. No minimum set of scope strings
  for direct send was established. [Authentication](AUTH_INTEGRATION.md)
- Core's `mail-message-data:36.8.0` is published (GPLv3, with GPLv3-or-later
  source headers). Candidate for the wire adapter once admitted under the
  existing dependency verification procedure. Its source archive SHA-256 is
  `3cf4d341df07f3f1b32e485ac23c5ebf4a2e54adf4a741609784720586a1c2e1`.
  It is absent from the current adapter's declared dependencies. The upstream
  Android package builder is application code using Core 36.2.0 and its own
  models and preferences resolver. The adapter below implements its internal
  default-preference branch using the already admitted 36.8.0 primitives.
  [Core sources][core] · [Published dependency manifest][pom] · [Android catalog][catalog] · [Android license][license]
- The offline recipient contract covers one existing, authenticated organizer,
  one added or removed participant on a simple event, an unchanged organizer
  and unchanged retained participants. The caller must first authenticate the
  complete guest list and organizer authority, exclude organizer aliases and
  duplicate recipient identities, verify native identity and
  check for other edits. Imported display projections can omit information.
  Replacements, bulk diffs and ambiguous identity changes remain unsupported.
  The contract performs no dispatch and grants no delivery capability.

## Offline attachment preparation

The adapter prepares one `invite.ics` from an authenticated, already merged
organizer event and an exact guest-only edit. Its bounded profile accepts UTC
times and DATE intervals. It requires an explicit UID, organizer, sequence,
DTSTAMP, DTSTART/DTEND and matching participants. Unknown fields, embedded
timezones, delegation, recurrence and stale invitation extensions hold the edit.
An equal start/end omits DTEND. Source alarms and attendee nicknames are removed.
The refreshed DTSTAMP uses the frozen whole-second preparation time.

A new participant gets RSVP, NEEDS-ACTION, the selected participation level and
the Proton participant token derived from UID and the resolved canonical address.
The original guest and sequence remain in CANCEL. REQUEST requires a 32-byte
shared session key. Its resulting ICS contains that key in plaintext pending
Mail package encryption. Close the holder and wipe any byte copy after use.
Source identity and remote revision, recipient aliases, key material and canonical
lookup results must be qualified by the calling adapter before transport.
[ICS transformations][ics] · [New participant fields][form] · [Canonical address lookup][picker]

The debug-only grant probe opens the existing authentication database read-only,
matches a single enrolled account and its current ready/authenticated session
and reports only the presence of the literal `mail` grant. This observation
does not establish recipient preferences, sender key usability or delivery.

## Internal recipient package

The published Core 36.8.0 `mail-message-domain` helper performs delivery as
part of its call, ignores send preferences and selects a clear package for an
external address. Its use would bypass preference handling and durable request
identity. Its sources SHA-256 is
`a048c89e7c6366de56965bc02b620d8503afc2af8cbf5b87047d93bb6345151f`.
[Core Mail domain sources][domain]

The offline package preparation covers one Proton recipient with confirmed
default contact preferences, fetched Mail settings, and an exact internal
public address with one usable primary key. Unknown or custom contact settings,
ambiguous keys, obsolete/compromised or Mail-disabled keys, external addresses
and expired/revoked keys hold the edit. Qualification of settings, contacts,
canonical addresses and trusted key lookup on the enrolled account remains a
prerequisite for calling this code in production.

Using Core crypto, the sender encrypts the attachment and signs its original
bytes. The sender attachment comprises the sender key packet, encrypted data
packet and detached signature. The body carries an embedded signature. The
recipient package (type 1) reuses the encrypted body data packet, wraps the
body and attachment session keys to the selected guest, and marks the recipient
signature flag. The direct request has one recipient, empty CC/BCC, empty
top-level AttachmentKeys and contact autosave disabled. The prepared JSON
includes routing metadata and requires sealed journal storage. An offline
synthetic test opens the package with a separate recipient key and verifies
both signatures. This preparation creates no durable intent or HTTP request.
[Sender packaging][direct] · [Recipient package][package] · [Mail wire types][core]

## Submission, recovery and acceptance

Extend the existing update journal before enabling writes. Freeze the source
revision, organizer identity, exact recipient, method, UID, sequence and sealed
request identities before their respective sends. Stage offline and recheck
account, calendar access, edit consent, usable organizer address/keys and
recipient preferences on admission. Treat an event upgrade as a separate
remote mutation if required. Missing Mail access must preserve the local edit.

Track Mail and Calendar evidence separately. A lost response after Mail dispatch
leaves Mail UNKNOWN and inspect-only, including across restart. Do not resend
or treat an updated calendar row as proof of delivery. A known Mail submission
followed by an uncertain Calendar write requires Calendar inspection while
preserving the Mail result. Without a correlated receipt, leave uncertainty
visible. Never record ICS, shared keys or recipient addresses in logs.

The present `SqlPersonalUpdateAttemptJournal.claim` persists one uncertain
submission and `retainEvidence` binds one response to that identity. The
existing personal content policy also requires an empty participant list.
An organizer attempt therefore needs an explicit version of the same journal
family, with a guest-aware source/receipt codec and two independently claimed
operations. Historical formats and uncertain attempts keep their original
recovery path. Proposed transitions for one already upgraded event:

| Durable state before executing the action | Action after restart |
| --- | --- |
| Both requests frozen, neither claimed | Recheck access, source revision, identity and preferences. Atomically claim Mail before its single send. |
| Mail claimed without correlated response | Inspect only. Calendar remains unclaimed. A matching current guest list cannot clear Mail uncertainty. |
| Mail response retained, Calendar unclaimed | Recheck full source and admission. Atomically claim only Calendar. Preserve the Mail receipt if the source changed. |
| Calendar claimed without proven readback | Inspect the exact event against its identity and signed guest changes. Never submit either operation again. |
| Both responses/readback proven | Perform the native/SQL acknowledgement against the original local capture, preserving newer edits. |

Each claim and receipt must compare the previous durable state and both frozen
request identities in one transaction. Freezing must include the intended mail
recipient and attachment method as well as the calendar request. A retry after a
process kill can send only an unclaimed leg. A recognized rejection remains a
visible partial operation if its peer already succeeded.

Qualification requires a controlled organizer account with an enabled sending
address and a separately authorized, inspectable guest mailbox. Use a synthetic
simple event. Check that adding the guest produces one matching REQUEST and
removing the same guest produces one matching CANCEL in that mailbox, with the
same UID, correct organizer, intended recipient and expected web guest list at
each stage. Cover transport loss independently on each operation using injected
failures. Test any supported external-recipient packaging separately before
claiming interoperability.

[save]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt
[email]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/SendEmailUseCase.kt
[ics]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt
[api]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt
[edit]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCase.kt
[prefs]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/ObtainSendPreferencesUseCase.kt
[direct]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/SendEmailDirect.kt
[repository]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/data/api/EmailMessageRepositoryImpl.kt
[core]: https://repo.maven.apache.org/maven2/me/proton/core/mail-message-data/36.8.0/mail-message-data-36.8.0-sources.jar
[pom]: https://repo.maven.apache.org/maven2/me/proton/core/mail-message-data/36.8.0/mail-message-data-36.8.0.pom
[catalog]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/gradle/libs.versions.toml
[license]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/LICENSE
[form]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/presentation/calendar/viewModel/EventViewModel.kt
[picker]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/presentation/calendar/fragment/EventFormAttendeesFragment.kt
[package]: https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/GenerateEmailPackageUseCase.kt
[domain]: https://repo.maven.apache.org/maven2/me/proton/core/mail-message-domain/36.8.0/mail-message-domain-36.8.0-sources.jar

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
  models and preferences resolver. No upstream code was copied or linked.
  [Core sources][core] · [Published dependency manifest][pom] · [Android catalog][catalog] · [Android license][license]
- The offline recipient contract covers one existing, authenticated organizer,
  one added or removed participant on a simple event, an unchanged organizer
  and unchanged retained participants. The caller must first authenticate the
  complete guest list and organizer authority, exclude organizer aliases and
  duplicate recipient identities, verify native identity and
  check for other edits. Imported display projections can omit information.
  Replacements, bulk diffs and ambiguous identity changes remain unsupported.
  The contract performs no dispatch and grants no delivery capability.

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

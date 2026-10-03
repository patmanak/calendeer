# Proton authentication adapter provenance

## Offline shared-title preservation experiment

`ProtonSharedTitlePatcher.kt` is an original, internal plaintext experiment using
the existing Biweekly 0.6.8 dependency, also used by the inspected Proton Android
Calendar source. It does not copy the Android split/edit use case. The
[conditional-write audit](../docs/research/CONDITIONAL_WRITES.md) explains why that
field-by-field split is insufficient for general preservation.

Biweekly validates UID/DTSTAMP/SUMMARY and serializes only the new SUMMARY block.
A bounded local framing scanner locates the original span; all other source spans
stay raw rather than passing through a full-document writer. Quoted parameters,
duplicates and nested components remain untouched. Event SUMMARY parameters are
refused rather than silently dropped or retained with a contradictory ALTREP.
The experiment validates framing and selected fields, not every RFC property.
Unsupported untouched properties can be preserved without becoming safe to upload.

The pinned transitive Vinnie 2.0.2 writer counts UTF-16 units when folding, not
UTF-8 octets. The experiment configures its existing writer with a conservative
Unicode width, then independently checks generated lines against 75 octets.
Vinnie's whitespace exceptions can exceed that width; those results are refused.
No original line is refolded. A failing Unicode boundary test established the
default-width limitation before the configuration and byte check were added.

Input/output remain ephemeral `ProtonEventTextParts` with redacted diagnostics.
There is no authentication, envelope/signature/key-packet handling, timestamp
update, persistence, transport, production caller or mutation capability. This is
not a complete UPDATE serializer or arbitrary JSON round-trip. Existing Biweekly
license notices apply; no dependency or copied-upstream code was added.

`ProtonEventDecryptor.openSharedTitle` extends this experiment with the existing
Core key-bootstrap and binary/text signature verification paths described below.
The bootstrap helper is shared with page decoding; page recovery and budget
semantics are unchanged. New local containers in `ProtonSharedTitleBasis.kt`
keep the original JSON and patched plaintext separate. Both signed UIDs are
matched to the expected observation before admitting a title basis. Complete JSON
values are copied before local key access and preserved structurally, rather than
being rebuilt from the display model. This is original project integration, not
an imported Proton edit/save implementation. No new dependency or copied source
was added. Existing adaptation notices and licenses remain applicable.

Only the two shared parts are signature-verified/decrypted. Other JSON fields and
personal/attendee partitions remain opaque, unqualified originals. Expected scope,
IDs/revision, session validity and rights are not authenticated by those signatures.
The internal experiment does not provide a sealed read receipt, re-encryption,
replacement signatures, UPDATE payload, timestamp update or concurrency guarantee.

The separate `prepareSharedTitle` experiment now reuses the existing Core
`decryptSessionKeyOrNull`, `encryptData` and address-key `signText` primitives.
It keeps the original shared session key and key packet instead of invoking the
CREATE encryptor's new-key generation or lossy display-model serializer. It
reuses the local `ProtonCreateContextPolicy` selection checks for a unique owned
personal-calendar WRITE member/primary signer, then requires the encrypted-part
author to match that selected local address. This selection does not establish
event-level rights or a live session. Shared opening and preparation use the
same frozen inputs and one local address read.

`ProtonReencryptedSharedTitle.kt` retains the original row separately from a
response-shaped replacement encrypted shared part. Only Data and Signature
are replaced; unknown part metadata is copied without semantic qualification.
No-op changes retain ciphertext/signature exactly. No new dependency or upstream
code was copied. This is local primitive integration, not a qualified Proton
UPDATE builder; no wire request, timestamp/sequence change or server guarantee
is supplied. Existing crypto adaptation/library notices still apply.

`ProtonSharedTitlePatcher.patchAt` adds an original local metadata policy using
the same framing scanner and Java time parsing/formatting. It rewrites only the
two DTSTAMP spans and clear LAST-MODIFIED after the title patch, keeping CREATED,
SEQUENCE and unrelated raw spans intact. The policy requires a specific plaintext
layout; it does not copy an upstream save use case or claim universal Proton
timestamp semantics. The [source audit](../docs/research/TITLE_EDIT_METADATA.md)
records pinned Android/Web differences and why SEQUENCE is not a conflict token.
No new dependency or upstream code was copied. The method itself only returns
plaintext; its offline `prepareSharedTitleAt` caller now reuses the existing Core
session-key decryption, encryption and text-signing primitives. It requires
explicitly empty personal/attendee partitions, compares each plaintext separately
and replaces only Data/Signature in parts that change. The selected primary author
must match each part being re-signed; an unchanged clear part retains its original
verified author and signature. The encrypted author check also remains in no-ops,
as in the legacy path. Original session keys, key packets, JSON and opaque part
metadata remain separate from the replacement fragments.

`ProtonTimestampedSharedTitle.kt` freezes all three values independently and
redacts diagnostics. This is local primitive integration, not a copied save use
case, complete event envelope or accepted timestamp policy for all Proton clients.
No new dependency or upstream code was copied. There is no production caller,
persistence, transport, current rights proof or concurrent-write guarantee.

`ProtonTitlePreparationCodec.kt` adds an original, internal protected format for
recovering the separate timestamped fragments with their exact caller bindings.
`ProtonEventDecryptor.prepareAndSealTitleAt` connects the actual shared verifier,
patcher and Core crypto to the existing `EventPayloadProtector`; production
protection uses `ProtonEventPayloadProtector` and its Keystore availability guard.
The format has its own kind/version, strict envelope keys, bounded UTF-8 and
duplicate-key preflight using the existing `UniqueJsonKeys` scanner. No new
library or upstream code was copied, and no cryptography was invented.

The codec validates structural preservation, not the signatures themselves. Core
text canonicalization means different raw spans can legitimately share signature
bytes; changed Data does not require unequal Signature bytes. Unchanged Data must
retain its complete original part. The opaque original revision is bound to the
expected observation, without recalculating it or retaining bootstrap/private
keys. Reopening local protected data supplies no current session/access proof,
remote acknowledgement or mutation guarantee. The codec performs no SQL writes;
the app's ImportStore separately owns the immutable preparation journal. There is
no automatic resumption, complete event/request assembly or UPDATE transport.

The same original codec now also supports a distinct local-intent preparation
format. Its protected envelope includes the complete typed historical local
intent and title binding, so `openLocal` needs only the retained journal record
and recovers UID from protected storage after matching the expected intention.
It reuses the existing strict envelope/fragment checks and Core Keystore
protector. The generic format remains unchanged and is not converted or
interpreted by this opener. No library or upstream source was added. This pins
the supplied local observation, not equality of every base field with remote
plaintext, current account/access, worker recovery or UPDATE eligibility.

## Experimental single CREATE submission and inspection

`ProtonCreateSubmissionAdapter.kt` and `ProtonCreateSubmissionCodec.kt` adapt
the GPL v3 Calendar request/response protocol from Android Calendar
`83bbd6a15b0aaa135e424fb5ac195c0a52929b4f` and WebClients
`914d7520eff52aafed9a1adfe19859143bdf1dc9`:

- [Web request and response interfaces](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/Api.ts#L169)
  place `MemberID`, `IsImport` and `Events` at the root. A simple CREATE wraps
  an identifier-free `Event` with `Overwrite=0`; it sends no `Action` field.
- [Web Calendar endpoints](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/api/calendars.ts#L296)
  define the sync PUT and calendar-scoped individual event GET.
- [Android sync DTOs](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt#L521)
  define `IsImport`, the event wrapper, `SharedEventContent`, and the single-operation response.
- [Web UID lookup](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/applications/calendar/src/app/containers/calendar/getAllEventsByUID.ts#L7)
  and [pagination helper](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/api/helpers/paginatedFetch.ts)
  establish scoped UID queries with page zero and page size 100.

Local restrictions freeze a separate versioned, scoped, protected submission
receipt. Fresh authenticated preparation must retain its complete draft, format,
UID, member, rights and key fingerprints before durable UNKNOWN admission. The one-shot permit is created
once outside Core's callback. The bounded raw response or a transport-incomplete
marker is Keystore-sealed and retained without acknowledging or reopening the attempt.
Cancellation or process loss before evidence storage can leave no response evidence;
the prior UNKNOWN admission still survives and inspection remains read-only.
Inspection never writes. It refuses ambiguous/foreign UID results and incomplete
pagination (at most 100 pages), individually reads the unique event, verifies both
shared-part signatures and each signed UID, and compares supported content and time.
Submission v1 keeps the legacy unknown-personal-data interpretation. Submission
v2 additionally binds `EXPLICIT_EMPTY`, known-empty expected details and a raw
`Notifications: []` event override. The stored format selects the strict readback
profile; there is no fallback or conversion from either local preparation format.
UTC aliases and logical newlines are normalized. A missing event, service error,
lost response or failed verification remains unknown and never authorizes resending.

These adaptations retain the module's [GPL version 3 text](LICENSE) and Proton
contributors' copyright. Local implementation is copyright 2026 Patmanak. No new
production dependency is introduced. This experimental port is not connected to
Provider edit capture or background outgoing work. A verified observation now carries
the exact scoped/versioned preparation identity, including a ciphertext fingerprint.
The application can persist its immutable remote association and independently
revalidate the current imported Android projection. That local association is
project-owned behavior, not an upstream protocol or a general outgoing ACK; it
does not establish server UID idempotency or conditional writes.

## Offline simple CREATE preparation

`ProtonSimpleEventPreparation.kt` adapts the bounded simple-event partition and
encryption assembly from Proton Android Calendar at
`83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`, under GPL version 3:

- [ICalUtilsImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt#L301-L431)
  splits UID/date metadata into a signed clear shared part and display text into
  a signed encrypted shared part. Its timezone assignments retain Olson TZIDs
  without embedding VTIMEZONE. `printToString` delegates to Biweekly.
- [EditCreateEventUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCase.kt)
  signs with the member-address private key, encrypts display text with a fresh
  session key, and encrypts that key for the primary active calendar public key.
  The CREATE path supplies an empty author for the service to resolve.
- [Event.EventPart](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/model/Event.kt#L600-L658)
  defines Type/Data/Signature/Author.
- [SyncEvent](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt#L642-L705)
  defines the request fragment names, including `SharedEventContent` rather than
  the read response's `SharedEvents`.
- [EditCreateEventUseCaseTest](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/test/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCaseTest.kt)
  supplies the partition/all-day/timezone expectations. Its encryption test is
  an unfinished TODO; local native evidence is required instead.

Copyright remains with the respective Proton contributors for adapted material;
the local implementation is copyright 2026 Patmanak. The module's existing
[GPL version 3 text](LICENSE) accompanies this adaptation. Biweekly 0.6.8 is reused
for escaping, folding and date serialization. Core 36.8.0 supplies `signText`,
session-key generation, `encryptData` and public-key `encryptSessionKey`; the
inspected upstream client uses Core 36.2.0, so version compatibility is tested
rather than assumed.

Local changes restrict preparation to CREATE with no recurrence, non-empty
details/reminders/participants or color override. UID and timestamp are supplied
by the caller; no identity or clock is generated inside serialization. UTF-8 is
strict, LF/CRLF logical text is normalized, precision is bounded to seconds and
dates to 1970–9999. Ambiguous local timestamps, fixed-offset non-UTC zones and
inconsistent Java timezone offsets are refused. No Calendar/Attendees part is
invented. Optional future fields must be qualified before adding them; never
rebuild UPDATE from this display-only model.

The encryptor checks primary/active/capability metadata on already selected keys;
the caller must establish their calendar, member and permission context. It has
no transport, capture or outbox wiring. The prepared fragment has its own
format/version and redacted
diagnostics; it is not a persistable, scoped intent or server acknowledgement.
`ProtonEventPreparationCryptoProbe` uses synthetic keys and an explicitly
fabricated service read shape to qualify signing/decryption and altered-signature
rejection. It does not demonstrate live CREATE acceptance, UID idempotency,
conditional writes or unknown-field preservation during UPDATE.

### Captured local CREATE preparation

The local known-empty-details extension reuses the same pinned partition mapping,
Biweekly serialization and Core cryptographic/Keystore primitives. It adds no
upstream source copy or dependency. Shared content without VALARM does not establish
empty personal notifications: missing notification data can inherit calendar
defaults. The explicit override follows these same pinned sources:

- [Android CREATE construction](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCase.kt#L376)
  sets nullable notifications directly in the `SyncEvent` inside the CREATE container.
- [Android SyncEvent DTO](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt#L696)
  places `Notifications` alongside the shared parts; its comment identifies migration
  from historical `PersonalEventContent`.
- [Web blob interface](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/Api.ts#L117)
  independently places nullable `Notifications` on the create/update event blob.

The separate local preparation v2 seals `knownEmptyDetails=true`, policy
`EXPLICIT_EMPTY` and an empty `Notifications` array on its event fragment. No new
crypto part or extra personal-update request is introduced. Unexpected preexisting
personal data is refused. Historical v1 blobs retain `EXPLICIT_EMPTY_REQUIRED`;
they are not migrated or overwritten. Neither format contains a submission request
or can be opened/dispatched by the submission codec. Submission preparation
rejects non-empty details profiles before session/context reads; exactly empty
details use the separate experimental submission v2 format.
JVM format tests establish these restrictions. Synthetic Core/Keystore fixture
code is separate from physical execution and live acceptance. Future readback
must qualify the raw empty array: mapped Android reminders omit EMAIL notifications
and cannot alone prove a personal override.

`ProtonCreatedEventReadback.kt` factors the existing bounded UID/individual GET and
shared-content verification into a read-only helper. Its internal versioned profiles
preserve legacy shared-only inspection and separately require explicit-empty personal
data without discarding known-empty details during comparison. The existing submission
codec selects the profile from its submission format; local preparations remain non-dispatchable.
`ProtonCreatedEventResponse.kt` reuses the project's bounded UTF-8 reader and unique-key
scanner to reject ambiguous GET responses before JSON normalization. These are local
verification restrictions, with no additional upstream copy or dependency. Shared
signatures cover their iCalendar parts, not the `Notifications` sibling. Native
synthetic readback and a retained live legacy read qualify different paths. A separate
retained timed submission v2 now passes authenticated raw-empty readback and owned
Android projection after the text-verification correction below, without resubmission.
This does not qualify all-day service behavior or immediate readback inside the
initial submission call.

### Text-signature verification compatibility

The published Core 36.8.0 source artifacts
([key-domain](https://repo.maven.apache.org/maven2/me/proton/core/key-domain/36.8.0/key-domain-36.8.0-sources.jar),
[crypto-common](https://repo.maven.apache.org/maven2/me/proton/core/crypto-common/36.8.0/crypto-common-36.8.0-sources.jar)
and [crypto-android](https://repo.maven.apache.org/maven2/me/proton/core/crypto-android/36.8.0/crypto-android-36.8.0-sources.jar))
define `signText` and `verifyText` with `trimTrailingSpaces=true`, including spaces
and tabs at each physical line end. `GOpenPGPCrypto` applies that text normalization;
`verifyData` handles the supplied bytes. A folded iCalendar property can end a line
with whitespace while retaining it in the encrypted plaintext. Binary verification
alone therefore refuses some text produced by our existing Core `signText` call.

The decoder retains binary verification compatibility and uses Core's text
verification on the same locally trusted author key through the official
`PublicKey` wrappers, retaining active/verifying-key and verification-time checks.
It does not trim the parsed
event, accept unsigned parts, change author resolution or relax signed UID/content
comparison. A native synthetic fixture reproduces the original refusal with a
whitespace-ending fold and exercises timed/all-day recovery and altered-signature
rejection. This is a local compatibility change to the pinned Calendar adaptation,
not a copied cryptographic primitive or a dependency upgrade.

## Authenticated CREATE context and response preparation

`ProtonCreateContext.kt` adapts member permission/key selection and signed
passphrase verification from the same Android Calendar commit, under GPL v3:

- [MemberEntity](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/entity/MemberEntity.kt)
  defines active-member flags and the explicit WRITE permission bit (16).
- [CalendarKeyEntity](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/entity/CalendarKeyEntity.kt)
  identifies the primary active key (flags 3) and its passphrase association.
- [CacheCalendarPassphraseUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/CacheCalendarPassphraseUseCase.kt)
  decrypts the selected member packet and verifies the passphrase signature
  against that address's active keys.
- [EditCreateEventUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCase.kt)
  supplies the address signer and primary calendar encryption key.

Local restrictions admit a type-0 calendar, exactly one owned member/address,
fully active member flags, an enabled sending address and unambiguous active
primary keys. Shared type-0 calendars with WRITE are not rejected solely for
being shared. Missing address IDs, maintenance flags or ambiguous ownership
remain unsupported context; this is stricter than the upstream clients.
The selected primary calendar key must unlock; no secondary fallback is used.
Core 36.8.0 supplies local address mapping, key unlock, passphrase signature
verification, PGP preparation and Keystore protection. The project-owned port
uses explicit context reads and returns a protected format/version; it neither
sets up keys nor uploads events. Its deterministic scoped UID is correlation,
not an idempotency guarantee. A future dispatcher must revalidate context and
establish durable admission rather than dispatch this fragment directly.

`ProtonCreateResponseParser.kt` adapts the single-operation response shape from
[CalendarsApiImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt)
and WebClients commit `914d7520eff52aafed9a1adfe19859143bdf1dc9`,
[calendar API interfaces](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/Api.ts).
The adaptation retains raw candidate event fields, requires one response at
index zero, checks event scope/UID and refuses duplicate JSON keys or excessive
nesting. Even a success candidate requires authenticated refetch and verified
content before acknowledgement. Individual errors keep their numeric code only
and remain uncertain outcomes; no error message or resend permission escapes.
The same GPL-3.0-only notices and accompanying [license](LICENSE) apply;
upstream copyright remains with Proton contributors, local adaptation with
Patmanak (2026).

Native synthetic fixtures qualify signed passphrase verification, primary-key
selection without secondary fallback, and signing/decryption. An opt-in live
fixture qualifies real context reads and sealed local preparations, not remote
CREATE acceptance. The published Core 36.8.0 and OkHttp 4.12.0 call paths can
redispatch or follow up requests despite the read-side no-connection-retry flag.
An outgoing transport must establish a single dispatch before any live CREATE.
The guards below address these re-entries; the experimental submission port above
uses them without enabling writable Android mirrors.

### Single-dispatch transport guards

`ProtonSingleDispatch.kt` is a local guard around the published OkHttp/Core
transport, rather than a copied HTTP/authentication stack. The production factory
adds guards to its supplied client; Core 36.8.0 copies that client for its primary
and alternative backends and preserves request tags while preparing headers.
The existing certificate, header, cookie and read transport configuration remains
owned by Core. The guards themselves do not invoke endpoints.

The guard requires the same scoped URL, PUT method, body object and attempt tag
at application entry and network exchange. Atomic guards also prevent reuse by
competing calls. The body declares `isOneShot`, refuses a second write and clears
its byte buffer after writing. The attempt must be constructed once outside the
Core invocation callback, after durable admission; reconstructing attempts in a
retry callback is not supported. Unmarked canonical CREATE endpoints fail closed.
These safeguards do not provide server-side idempotency or event acknowledgement.

Dependency behavior was checked in the published Core 36.8.0 and OkHttp 4.12.0
artifacts and is exercised through a real local-server test. Core's token error
handler can redispatch the same callback after refresh. OkHttp's
RetryAndFollowUpInterceptor can follow a 503 despite no connection retry; its
one-shot-body check blocks that path. Retrofit 2.9.0's Content-Type overriding
wrapper loses the one-shot property, so the guard refuses a wrapped/replaced body.
The MIME type belongs to the body, not a future endpoint's Content-Type annotation.
Library upgrades require requalification. MockWebServer 4.12.0 is a unit-test-only
Apache-2.0 dependency; no new production networking library is introduced.

## Read-only reminders and participant details

`ProtonEventDetails` adapts the current personal notification selection and
participant status mapping from Proton Android Calendar at
`83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`, under GPL version 3:

- `app/src/main/java/me/proton/android/calendar/data/entity/NotificationEntity.kt`
  defines Type 0 email, Type 1 display and RFC5545 duration triggers relative to start.
- `domain/model/Event.kt` in that same Java package tree, `alarms`, distinguishes
  nullable defaults from an explicit empty override. `data/entity/CalendarSettingsEntity.kt`
  supplies separate timed and all-day defaults from the scoped bootstrap.
- `domain/usecase/TransformEventUseCase.kt` decrypts shared/calendar/attendee parts,
  marks API notifications as migrated and overlays clear participation status by token.
- `common/utils/ICalUtilsImpl.kt`, `generateXPmToken`, and
  `common/utils/ProtonUtilsImpl.kt`, `canonicalizeProtonEmail`, define the fallback
  participant identifier. SHA-1 here is an identifier compatibility operation,
  never authentication or signature verification.

The nullable selection and token status precedence were cross-checked against
WebClients `914d7520eff52aafed9a1adfe19859143bdf1dc9`,
`packages/shared/lib/calendar/alarms/notificationsToModel.ts` and
`packages/shared/lib/calendar/attendees.ts` (GPL-3.0-only).
These adaptations retain the module's GPL-3.0-only notices and accompanying LICENSE.
Copyright in upstream behavior/code belongs to Proton contributors; local adaptation
is copyright 2026 Patmanak. No upstream application database or private data is copied.

Local changes enforce bounded exact-minute display reminders, ignore email delivery
for local projection, reject partial or ambiguous participation data, and restrict
participants to representable mail addresses/roles/statuses. API Notifications always
take precedence over historical ICS VALARM components. The parser-only overload keeps
details unknown; production decoding supplies event metadata and the sealed bootstrap.
No invitation, reply or email operation is introduced. Existing external-author
signature trust restrictions remain in force. Unit and native crypto fixtures are
synthetic local tests, not evidence of complete server/OEM compatibility.

The authentication/session and hosted-verification integration adapts Contako,
copyright 2026 Patmanak, GPL-3.0-or-later, from the owner-provided source checkout
at commit `4a1d2e4f6626a2a1e1b194542bd175ac019013e0`. No stored session, credential,
account data or generated build output was imported. Preserve these notices and
[the upstream license](LICENSE).

## Source records

Paths are relative to Contako. SHA-256 identifies exact source bytes; Git blob
identifies the normalized file at the pinned commit. Authentication portions of
ProtonGateCRuntime and ProtonGateways were extracted; contact adapters were omitted.

| Source path | Git blob | SHA-256 |
| --- | --- | --- |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonAuthenticationFlowAdapter.kt` | `a23bd02e717f857339cd86cb3b0c9817776bbe87` | `7606212d7fc90cf0e503dbe5d2cb788c8e3fbd63e3538a094d43be4973693c3a` |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonCoreSessionCoordinator.kt` | `a5fe437be8d83dd914cfb15f54eb9ff5311f138e` | `1d11073934cff4a1e0531e33e9dfdd072170e04f94616118c0144605de0df9c5` |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonGateCDatabase.kt` | `b072cbc59289b9e16eecd82114c0ba6a36edc3f3` | `bbdf9c95282af9580e132ae21b9af431c58711d08986218a0edd9ec599a95eac` |
| `app/src/main/java/com/patmanak/contako/data/proton/GateCProtectedStorageGuard.kt` | `d178a35306191b7284e028d06377f3a50db18b26` | `340fd905774dcf61adb4f5713fe2b12d32d0f83ca588213f01ecd0c96cbeeb9d` |
| `app/src/main/java/com/patmanak/contako/data/proton/PendingLoginPassword.kt` | `a8e265ae34d2ce96e967bf7ff1cb569cbddeeba5` | `97ddb02fe081429f9cb8ab67c22b1a694dc4ecc117fe37472879172fe3b02134` |
| `app/src/main/java/com/patmanak/contako/data/proton/GateCInteractiveHumanVerification.kt` | `49c7a402822dfc5af8b24c1a0beef6e22f9b05b1` | `5b457d869ee33f56b0a8261e88629d03464d1d515b0f2ed17c51e606841de5d2` |
| `app/src/main/java/com/patmanak/contako/data/proton/OriginBoundVerificationBridge.kt` | `b6b4c0d886a0b2e81791ddae58d43da79e90d0a1` | `83185f6d16bcd33e72e61c81a7cc11d1e005de3c88a5644eb91d6767573c22f3` |
| `app/src/main/java/com/patmanak/contako/data/proton/ContakoApiClient.kt` | `4667a571a2890406798e420d611da6a431a46575` | `7c1d4408dcc5373cea028b8abb6813f410ecb666b0ff0e5c86c8f4b768179929` |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonGateCRuntime.kt` | `fedc97d3c812ca09e139be0f4962cad856f88380` | `3cf5e13453cb8289492e6056884aeafa2df3162aaaaca83a9a1055af57c5f3a5` |
| `app/src/main/java/com/patmanak/contako/data/gateway/ProtonGateways.kt` | `08cd7257afae7db4a7a8d4e540fd3d24c214108f` | `d6105b22dc40a359b65e6a2b4f5e2501a879ef08bcabb74167d3ede4dc15fbb9` |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonGateCNetwork.kt` | `4fcb7df25724f94a04cda93821740c4f7d6da18f` | `7989b42d7b83e017160ac7f349a88a5f01152c67750eb16e7d51297a114051ee` |
| `app/src/test/java/com/patmanak/contako/data/proton/ProtonGateCAdaptersTest.kt` | `33a590614e8a5ae8aaea280f2a4fae533cdb8d02` | `b3f537cd93f4bf83cccaf3a10de8891a34353d41349407ffba6cd1e60df3cf31` |
| `app/src/test/java/com/patmanak/contako/data/proton/GateCInteractiveHumanVerificationTest.kt` | `abf29a9f3702aec2803611316fda87faf3e5fff3` | `361df93d918433cdd44fd953d79cf4467a700a0950947227e4c16cf13992ab6d` |

## Local adaptations

- Dedicated Android library, package `com.patmanak.proton.andcalendarbridge.protonauth`.
  The project-owned AuthenticationFlowPort is the application integration boundary.
  Proton DTOs and crypto objects remain inside the module.
- Product.Calendar is used consistently for account, auth and user repositories;
  API base `https://calendar-api.proton.me/` matches official android-calendar
  commit `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`. Client identifies honestly as
  `Other_0.1.0+andcalendarbridge` and `CalendarBridge/0.1.0 (Android)`; server acceptance
  requires test-account qualification independently from unit tests.
- Authentication graph excludes contact adapters. Key setup, recovery, account
  security changes and password changes are not exposed. Existing account keys
  unlock through maintained Core primitives, which store derived passphrases locally.
- Database renamed `proton-auth-v1.db`. Keystore availability is required before
  opening it. Core encrypts tokens/derived passphrases; account metadata and public
  or already-encrypted key records also require app-private storage and no backup.
- Lazy composition and all port operations run on Dispatchers.IO. Shared Mutex
  serializes operations/cleanup; a slow cancelled operation cannot repersist a
  session after cleanup returns. Controller lifetime is independent of view rotation.
- Ready maps to Authenticated, which does not establish Calendar bootstrap/sync.
  HumanVerificationHost exposes only generation metadata and WebView lifetime APIs.
  The hosted message bridge accepts only the verifier origin and main-frame messages.
- OperationSecret.close clears buffers even during suspended consumption. Pending
  login password retention is memory-only, session-bound and expires after five
  minutes. Passwords/codes are not persisted; some Core APIs require temporary strings.
- Cleanup failures are surfaced to the controller gate. Persisted incomplete 2FA
  sessions are revoked best-effort before local removal. No raw server messages,
  credentials, tokens or exception details are shown or logged.
- Upstream adapter and verification tests are retained with package changes;
  local regressions cover cleanup failure and incomplete-session restoration.

## Dependency and maintenance boundary

Proton Core 36.8.0 supplies SRP, server-proof validation, login, second factor,
token refresh, existing user-key unlock and protected persistence; Gradle enforces
one release train. Native me.proton.crypto:android-golib is pinned to 2.10.0-2.
Room 2.8.4 supplies the local schema and AndroidX WebKit 1.17.0 the origin-bound
verification message bridge. These published artifacts are not an official
supported third-party Calendar SDK.

Copied integration code does not update automatically. Compare these source
records with upstream security/session/challenge changes before release and
retain relevant tests/notices. Fake tests do not establish live SRP, CAPTCHA,
account scopes, device Keystore or Calendar crypto compatibility. Security-key-only
login and Calendar-specific bootstrap/synchronization remain separate boundaries.

## Calendar catalog protocol adaptation

`ProtonCalendarCatalog.kt` adapts the query, minimal field shape and own-member
display selection from Proton WebClients at commit
`914d7520eff52aafed9a1adfe19859143bdf1dc9`:

- [Calendar model](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/calendar/calendars/index.ts)
  fetches `CalendarWithOwnMembers[]` using the complete, unpaged query with flags.
- [Calendar API query](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/api/calendars.ts)
  and [query enums](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/Api.ts)
  define `GET calendar/v1?OrderBy=0&ReturnFlags=1`.
- [Calendar interface](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/Calendar.ts)
  and [member interface](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/interfaces/calendar/CalendarMember.ts)
  define the nested metadata. Name and color are on members, not assumed on calendars.
- [Visual calendar mapping](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/calendar/calendar.ts)
  and [own-member selection](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/calendar/members.ts)
  select the first own member for display metadata.

The catalog adapter and its protocol fixtures carry `GPL-3.0-only`, consistent
with the pinned WebClients GPL-3.0 declaration; this does not extend that material
to later license versions. Copyright belongs to the respective Proton contributors;
the local adapter implementation is copyright 2026 Patmanak. The existing
[GPL version 3 text](LICENSE) accompanies the adaptation. The broader Contako
authentication extraction retains its original GPL-3.0-or-later notices.

The Kotlin implementation uses Core's existing authenticated ApiProvider and the
same account/session coordinator, database, IO dispatcher and serialization mutex
as login. Catalog access is admitted only after successful identity/key unlock and
rechecks the user/session after the response. Logout closes admission before cleanup.
No additional login, calendar creation, default setup, bootstrap or event request
is performed. Ordinary Core session refresh may occur within authenticated transport.

Local response bounds are one MiB and 1,024 calendars, with strict UTF-8, unique IDs,
nonempty own-member lists and matching member CalendarID. Exceeding a bound or
encountering an unrepresentable row fails the entire result; nothing is silently
truncated. These are application safeguards, not claimed Proton server limits.
Unknown fields are ignored only because this slice never edits remote objects.
Unsupported color syntax yields an unspecified color while retaining the calendar.
Catalog metadata is transient; this adapter does not persist names or raw responses.

The source's complete web query deliberately supplies no Page/PageSize. The
Android client's default first-page call is not used as evidence of completeness,
and no short-page/cursor semantics are invented. Event pagination is separate work.
Metadata presence establishes neither Calendar key readiness nor write permission.

## Read-only event protocol and cryptography adaptation

Per-event color uses the optional `Color` field in the pinned Android Calendar
`EventResponse`/`EventEntity`, carried by `TransformEventUseCase` into
[Event](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/model/Event.kt).
It is a direct RGB hex override, not an iCalendar property or palette index.
Missing/null inherits the calendar color; an explicit value remains independent
even when it happens to equal the current calendar color. Unsupported color syntax
is retained as deferred content instead of guessing. The domain stores optional RGB and the
Provider writes opaque `EVENT_COLOR`, clearing both it and `EVENT_COLOR_KEY` when
the override is removed. See [Android color semantics](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns#EVENT_COLOR).

The `event-map2:` revision prefix distinguishes the color-aware mapping from
previous rows whose encrypted-payload hash already included Color but whose
projected content omitted it. Cursor format is unchanged. Existing rows, pending
work and provider identities are retained; refresh updates them in place. Missing
color remains omitted from stored JSON to preserve legacy acknowledgement matching.

`ProtonEventImport.kt`, `ProtonEventDecryptor.kt` and `ProtonICalendar.kt` adapt
Proton Android Calendar at commit `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`:

- [Calendar API and DTOs](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt):
  authenticated bootstrap and event GETs, packet fields, explicit `More` pagination.
- [FetchEventsUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/FetchEventsUseCase.kt)
  and [Constants](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/Constants.kt):
  four query types and 42-day request chunks. The chunk size is an upstream UI
  choice; it is not claimed to be a server limit.
- [CacheCalendarPassphraseUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/CacheCalendarPassphraseUseCase.kt):
  member/address selection, passphrase decryption and signature verification.
- [TransformEventUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/TransformEventUseCase.kt):
  shared/calendar/attendee packet selection, address-key versus calendar-key
  decryption and detached signature verification.
- [ICalUtilsImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt):
  Biweekly parsing, split-property merge, date-only raw components and legacy
  equal-boundary all-day normalization.
- [Dependency versions](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/gradle/libs.versions.toml):
  `net.sf.biweekly:biweekly:0.6.8` is reused as the iCalendar parser.

Biweekly is copyright 2013-2024 Michael Angstadt under BSD-2-Clause. Its
[license](licenses/biweekly.license) and embedded
[Commons Codec notice](licenses/commons-codec.license) and
[Google RFC 2445 notice](licenses/google-rfc-2445.license) are retained from the
published 0.6.8 source artifact. Biweekly's `TimezoneInfo` owns parsed timezone
assignments; parser warning 37 denotes successful Olson timezone resolution.
Other parser warnings and unresolved/floating start or end dates are refused.

These adaptations retain `GPL-3.0-only` under the pinned Android Calendar GPL
version 3 license, accompanied by [the license text](LICENSE). Copyright belongs
to the respective Proton contributors; local changes are copyright 2026 Patmanak.
Core 36.8.0 performs all PGP operations; no cryptographic primitive is reimplemented.
Calendar key material and decrypted packet buffers are scoped to a page. The
adapter does not store decrypted calendar passphrases or invoke key/account setup.
Core APIs require some transient immutable strings that cannot be explicitly erased.
The first page qualifies bootstrap and a calendar-key unlock even when there are
no events. Later empty pages need no bootstrap request. Decoded packets and per-event UTF-8 text are limited to one MiB;
accepted event text is limited to eight MiB per page; Core's native PGP operation allocates
before the post-decryption check, so this is not a pre-allocation decompression bound.

Local changes: project-owned read-only port; common authenticated runtime and
logout mutex; closed failures; one-MiB response bound; frozen 90-days-past through
365-days-future inclusive window; scoped/versioned cursor; requested page size at
most 100; `ModifyTime` plus encrypted-payload SHA-256 revision. Pagination is an
observation of a changing server, not a stable snapshot. Missing events never
produce deletions. The caller must repeat bounded reconciliations.

Only title, start/end, description and location are projected. Alarms, participants
and additional properties are not represented by this first read-only model.
Recurrence and exceptions, cancelled events, floating or unrepresentable dates,
missing calendar bootstrap/key material block the page. Identifiable unsupported
event contents and unverified event signatures are retained as sealed retry evidence;
supported records on the same page can advance together with that evidence. External-author contact/key pinning is not implemented;
only signatures verifiable with the signed-in user's own address keys are accepted.
Synthetic parser/cursor/session tests do not establish native crypto or server
compatibility; those require separate physical-device qualification.

### Durable receipt and offline mapping

The staged import boundary separates page/bootstrap reception from decryption.
`ProtonEventReceipt.kt` wraps the received JSON and bootstrap with scope, request,
frozen window, adapter version and receipt version. Proton Core 36.8.0
`KeyStoreCrypto.encrypt/decrypt` protects that envelope; the existing protected
storage guard rejects Core's plaintext compatibility fallback on every operation.
No local cipher or key derivation is introduced. Sealed receipts are limited to
four MiB, in addition to the one-MiB HTTP response bound. Plaintext is transient
inside the adapter; the import repository receives only sealed retry evidence.

The production decoder receives only an address source backed by Proton Core
`AddressWithKeysDao.getByUserId` and its `toUserAddress` mapper. The published
`me.proton.core:user-data:36.8.0` artifact was inspected: `getAddresses(refresh=false)`
uses `ProtonStore.get`, while the DAO/mapping reads existing protected passphrases
directly. The decoder therefore has no `UserManager`, transport, or cache-miss
network path. Missing local keys or invalid bootstrap blocks mapping and retains
the receipt. Network receipt does not establish successful key unlock or projection.

`ProtonLocalAddressMapper.java` is a narrow Java bridge to the pinned artifact's
Kotlin-internal but JVM-public `AddressMapperKt.toUserAddress(AddressWithKeys)`.
This deliberately reuses the complete official mapping, including key flags and
protected passphrases, instead of copying it. It is not a stable public SDK contract;
Core upgrades require compilation and native/local-key requalification.

The receipt's frozen first-page window survives restart and date changes. Scope,
request, version, row identity, calendar identity and duplicates are validated
before isolating content errors. Deferred records retain the original protected
row/bootstrap for offline retry after a mapper update; detached-signature failures
are classified separately from unsupported content. Page commit/retention and
budget policy belong to the application store. The legacy fetch API still rejects
partial results because it cannot acknowledge durable retry evidence.

The debug native probe exercises Core Keystore and PGP with synthetic keys,
rebuilds its crypto/adapter objects after receipt, forbids network access, and
checks supported/unsupported/supported rows, a large unsupported record followed
by valid content, signature failures and missing keys. Unsupported records have
independent decode budgets; exceeding the accepted-page budget blocks the page
with a capacity failure rather than misclassifying remaining supported records.
This does not qualify key rotation or every shared-calendar author trust path.

### Incremental-feed protocol preparation

`ProtonChangeFeed.kt` adapts the protocol fields and endpoints documented by the
pinned Android Calendar
[ServerEventsApiImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/ServerEventsApiImpl.kt)
and [CalendarsApiImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt),
plus WebClients
[events API](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/api/events.ts).
The adaptation remains `GPL-3.0-only`; it does not copy or install a Core event
manager. Core source at commit `1b87f94ebfdfaf5e67145e8668efc52dbb931e0b` provides
additional behavior evidence for fetching a latest cursor before a baseline and
handling nonzero Refresh. That source commit is not identified as the published
36.8.0 artifact. Source inspection and isolated protocol tests do not establish
working incremental deletion synchronization against the live service.

`ProtonCalendarDeltaAdapter.kt` connects the calendar-specific feed to the production
shared runtime. Baseline window cursors remain unchanged. Delta cursors use a
separate scoped `proton-calendar-delta` format, and received feed envelopes are
sealed with Core Keystore under a separate receipt kind/version. Decoding a feed
is local and revalidates the original scope/cursor. Nonzero Refresh emits a
reconciliation requirement without inferred deletions. Explicit CalendarEvents
tombstones carry stable, scoped provenance; changed IDs require a full event GET.
Context changes precede event notices. Bootstrap/key refresh is an explicit
network operation using existing Core user/address GETs followed by an empty-page
local key-unlock qualification. It does not set up keys or mutate account attributes.

Single-event GET responses are sealed with the existing scoped event/bootstrap
receipt format. Their local decoder returns either a verified upsert or retained
deferred evidence. HTTP 404 is an unresolved missing read, never a tombstone.
All added operations share the authentication/logout mutex and session checks.
The global feed remains unwired; current catalog/access reconciliation is owned
by the application coordinator. Fake transport tests establish ordering, scope,
restart, reset and failure behavior, not live feed availability or account access.

Subscription context notifications are checked against the pinned
[CalendarSubscriptionEntity](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/entity/CalendarSubscriptionEntity.kt):
their nested payload has `CalendarID`, without an `ID` field. Its calendar must
match the requested scope. The outer notification ID remains opaque provenance
for a context refresh; it does not become an event identity or authorize deletion.

### Read-only recurrence mapping references

The recurrence mapper extends the existing Biweekly 0.6.8 integration, using its
typed properties and `RecurrenceRuleScribe.writeText` for RFC serialization. It
does not copy Proton's UI occurrence expander. The mapping follows these paths
from Android Calendar commit `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`:

- [HandleSaveUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt#L313):
  a single edit retains UID, receives a distinct event identity, drops the rule
  and exclusions, and stores the original occurrence slot in RECURRENCE-ID.
- [HandleDeleteUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleDeleteUseCase.kt#L89):
  occurrence removal updates the master's EXDATE; removing an existing single
  edit also deletes that remote record.
- [EventUtilsImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/EventUtilsImpl.kt#L396)
  and [ICalUtilsImpl](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt#L571):
  preserve date-only exception components and distinguish the original slot from
  a moved event's current start. A moved occurrence need not add EXDATE to its master.
- WebClients commit `914d7520eff52aafed9a1adfe19859143bdf1dc9`,
  [createSingleCancelledRecurrence](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/applications/calendar/src/app/containers/calendar/recurrence/createSingleCancelledRecurrence.ts):
  an occurrence can remain a record with RECURRENCE-ID and STATUS:CANCELLED;
  this status is not a remote deletion notice.

The same GPL-3.0-only adaptation notices and existing Biweekly licenses apply.
RDATE parsing reuses Biweekly; the inspected Proton occurrence expander is not
evidence for Android Provider compatibility. Unsupported recurrence combinations
remain deferred, and parser tests do not establish live recurrence behavior.

The initial Android projection excludes every rule containing `BYSETPOS`,
`BYWEEKNO` or `BYYEARDAY`, including valid RFC rules. Android's recurrence
processor does not provide the complete semantics needed by this mapping;
Biweekly acceptance alone is insufficient. These records keep their sealed
deferred evidence instead of being projected with selectors silently ignored.
Yearly rules with `BYDAY` or `BYMONTHDAY` also require an explicit `BYMONTH` in
this slice: the native processor's month-based day selection does not establish
correct year-wide semantics. Rules outside that boundary remain deferred.

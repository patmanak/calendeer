# Single-occurrence edits: source contract and admission

Study date: 2026-11-17. This is L9d source evidence, not an activated capability
or proof of service compatibility. The [recurrence plan](RECURRENCE_WRITES.md)
and its concurrency limits still apply. No upstream application code is added by
this study. Sources were read, not executed.

## Pinned official implementation

WebClients commit `c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672` is the reproducible
baseline below; these files were fetched again at that exact revision. This does
not claim that revision is today's HEAD. Android Calendar remains pinned at
`dbc20fa485ca9a3e02988ec8f430145fc9e0b1af` (`release/2.34.1`). Both repositories
carry GPLv3 licenses: [Web](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/LICENSE)
and [Android](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/LICENSE).
Any later port must retain notices and record the exact adaptation in
[adapter provenance](../../proton-auth/PROVENANCE.md).

- [createSingleRecurrence](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/recurrence/createSingleRecurrence.ts)
  keeps the UID, removes RRULE/EXDATE, and sets RECURRENCE-ID to the original slot.
  [toExdate](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/exdate.ts)
  uses the master's DATE/DATE-TIME kind and timezone. A moved child's new DTSTART
  does not replace that original identity; changing its kind does not change the
  RECURRENCE-ID kind. Floating or ambiguous original slots must not be guessed.
- [getCurrentVevent](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/eventActions/recurringHelper.ts)
  constructs the old generated occurrence at its original start/end and removes
  RRULE, EXDATE and RECURRENCE-ID before comparison. Its all-day end correction
  belongs to Web's inclusive local-end representation, not Android's exclusive end.
- The SINGLE branch of [getSaveRecurringEventActions](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/eventActions/getSaveRecurringEventActions.ts)
  creates a new remote event for a generated slot, and updates the child ID for
  an existing detached exception. When the master lacks SEQUENCE, it prepends a
  master UPDATE setting SEQUENCE=0; its comment explicitly says the API otherwise
  complains. Ordered operations are not proof of atomic server commit. Android's
  [HandleSaveUseCase](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt)
  also calls `handleOriginalEventNullSequence` before creating a single edit.
- [withVeventSequence](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/eventActions/sequence.ts)
  preserves the old sequence when kind, dates and rule are preserved/subset, and
  otherwise increments it. The generated comparison already has no rule, so a
  title/reminder/color-only child can retain the master's sequence; a moved child
  increments it. An existing child's comparison sequence is at least the master's.
  SEQUENCE is not a modification timestamp or a conditional-write token.
- The CREATE's `IsPersonalSingleEdit` is the inverse of
  [getHasUpdatedInviteData](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/mailIntegration/invite.ts).
  This compares dates, rule and normalized shared text/conference properties;
  reminder/color-only changes differ from title/time changes. The flag is not a
  synonym for a personal calendar. Existing exceptions preserve/update their flag
  through the official UPDATE path.

## Signed, encrypted and personal data

[getSyncMultipleEventsPayload](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/getSyncMultipleEventsPayload.ts)
builds `calendar/v1/{calendarID}/events/sync`, carrying MemberID and indexed
operations. A child CREATE uses `Overwrite: 0`, fresh creation keys and a new
remote event ID. It does not link by the master's remote ID or reuse the master's
SharedEventID. The signed UID and RECURRENCE-ID establish the recurrence link.
No source establishes duplicate-free retries after a lost response.

[getCreationKeys](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/crypto/keys/helpers.ts)
and [createCalendarEvent](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/serialize.ts)
select the calendar public key and address signing key. A new child gets a fresh
shared session key and SharedKeyPacket; an UPDATE reuses that child's keys.
Do not reuse a master's session key just because the UID matches.

The [field partition](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/constants.ts)
puts UID, DTSTAMP, DTSTART/DTEND, RECURRENCE-ID and SEQUENCE in the shared signed
part. Summary/description/location belong to shared encrypted signed content.
Calendar status/transparency and exclusions can be in calendar signed content;
calendar comments require encrypted calendar content and its key packet.
[getVeventParts](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/veventHelper.ts)
retains unclassified properties in the encrypted shared part. Preserve qualified
source parts and refuse unsupported data rather than rebuild only visible fields.

[formatData](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/calendar/formatData.ts)
emits Notifications and Color as personal JSON siblings, outside shared ICS.
Default notifications become null; explicit overrides remain lists. Although
`getVeventParts` exposes a personal signed representation, this sync serializer
does not emit that personal part. Existing default-email preservation still applies.

## Existing Android import and required safeguards

`ProtonICalendar` decodes a detached RECURRENCE-ID into `EventRecurrence.original`;
the original kind is independent of the moved DTSTART kind. Import stages the
master before its exceptions. `NativeIncomingProjection` resolves the scoped
series UID hash to an owned master and atomically asserts its geometry/receipt.
`NativeEventValues` writes ORIGINAL_INSTANCE_TIME and ORIGINAL_ALL_DAY; the
incoming writer sets ORIGINAL_ID and ORIGINAL_SYNC_ID to the native master.
A child has its own hashed remote `_SYNC_ID`; a master uses its scoped series UID
hash, while SYNC_DATA4 retains its distinct hashed remote event identity.

L9c accepts only a complete qualified remote family containing the frozen master,
and its native reader asserts no children by ORIGINAL_ID or ORIGINAL_SYNC_ID.
Those guards must remain effective if an exception appears after capture and
before preparation/claim. Color reprojection must include clean master and child
rows, preserve dirty/foreign rows and canonical colors, and remain bounded and
restartable. Display pagination is not evidence of complete remote membership.

## Initial L9d subset and gates

Retain the existing finite DAILY master rule, rights and timezone restrictions.
Initially admit only the first detached child of a master whose complete remote
and native family has no existing exceptions. Updating an existing detached child
remains unsupported in this first profile. Admit one exact original slot, no
guests, no cancellation, no guessed parent or duplicate slot. Target edits are
title, time (including a moved occurrence),
reminders and Proton-palette color. The master must be unchanged. Initially require
an existing valid master SEQUENCE; the source-required compound initialization
for sequence-less masters stays unsupported until separately qualified. This is
an explicit additional gate, not a reinterpretation of saved profiles.

Use a dedicated versioned native/preparation profile through the existing intent,
attempt and proof stores. Existing capsule v4, fingerprint v2 and profiles 1–3
stay frozen. An uncertain child CREATE/UPDATE is inspect-only and never replayed.
Before activation, prove exact family capture, requalification before claim,
crypto with controlled HTTP, independent readback, native/SQL ACK and interrupted
recovery in E3. Then qualify one authorized live test occurrence. E4 clients remain
grouped separately; source knowledge alone does not enable a worker route.

## Inactive native observation

`captureOwnedOccurrenceEdit` is a separate assertion-only observation on the
existing projection port. `calendeer-native-occurrence` version 1 snapshots an
ordinary imported clean master and its first unstamped dirty child. Parent
identity/revision/geometry, exact original slot, complete native family, reminders,
color-key evidence and absence of participants/extended properties are asserted
in one Provider batch. The candidate owns bounded immutable domain data; Android
row locators stay outside it. No identity is stamped and neither row is cleaned.

Transferred CREATE provenance, additional exceptions, unsupported fields and an
unwitnessed absent child color under a colored master remain held. A dirty child
can outlive display-mode changes and parent reprojection. This native profile
does not prove a signed parent SEQUENCE or complete remote membership. It has no
durable request, preparation or worker route; those need independently versioned
extensions of the existing stores and the remaining adapter/recovery gates.

## Inactive durable local capture

The explicit `captureLocalOccurrence` entry point extends the existing capture
journal. Local snapshot schema 4 wraps an unchanged historical flat-content
encoding with the exact bounded master and original slot. The journal commits
that context together with its one-shot reservation before any native stamp.
A scoped unique index reserves the master/UID/slot across local identities.
Existing reservations and payloads receive no inferred occurrence context.

Only a fresh reservation may stamp the child with a `local-occurrence-v1` identity
in the same native assertion batch. The master and DIRTY flags stay unchanged.
After interruption, recovery looks up that exact marker and frozen context;
it never reuses a row hint, restamps a consumed reservation or adopts newer edits.
Caller cancellation is checked before the batch and after its return. A lost
reply can leave a committed marker; recovery inspects it rather than writing again.

This is not request capsule v4 or dispatch fingerprint v2: those historical
contracts remain frozen. Existing preparation, submission and fingerprint paths
reject occurrence captures. No worker route, network preparation or ACK is enabled.

## Inactive occurrence request identity

An explicit dispatch fingerprint v3 binds the frozen master event ID, revision,
UID, canonical rule and personal content, original DATE or DATE-TIME slot, and
flat child content. It nests the unchanged historical content fingerprints;
versions 1 and 2 keep their byte contracts. Matching v3 requires the complete
retained intent and its capture metadata. Bare-content matching and historical
factories remain closed to occurrences.

Protected CREATE capsule v5 likewise requires that independently retained intent
on inspection. It binds the attempt and capture identities to v3, preserves the
whole source JSON, frozen request, key context and notification defaults, and
accepts exactly one child CREATE envelope. Inspection does not authenticate the
master's signed SEQUENCE, qualify rights or authorize dispatch. Those checks
belong to preparation and pre-claim qualification. Historical capsule readers
still reject v5. Inspecting UNKNOWN work neither changes nor resends it.

The existing dispatch and import tables accept fingerprint v3 through explicit
transactional migrations; prior payloads, evidence and acknowledgement states
are copied unchanged. No occurrence preparation, worker, readback or native/SQL
acknowledgement route is activated by this storage contract.

## Inactive source-preserving cryptographic preparation

The explicit decryptor entry point verifies the captured master's shared
signatures and exact full decoded base, then applies the existing closed series
wire profile and writer/key policy. Its bounded raw patcher preserves admitted
opaque properties, keeps the master's UID, removes RRULE and adds RECURRENCE-ID
using the original slot and master timezone. A moved child or kind transition
does not change that original identity. A signed existing SEQUENCE is mandatory;
unchanged generated geometry keeps it, while changed geometry increments it.

Maintained Proton primitives generate a fresh shared session key for the child,
wrap it to the selected calendar key and sign both shared parts. A qualified
signed calendar status/transparency part is verified, timestamped and signed
again. Existing metadata policy preserves unchanged null inheritance or explicit
notifications; changed reminders retain default emails, and colors require the
Proton palette. IsPersonalSingleEdit compares generated shared content with the
replacement, independently of personal reminder/color changes.
Plain-text titles use the upstream trim behavior. Changed titles containing
HTML/entity-sensitive characters remain unsupported until the browser's
sanitization semantics are qualified; unchanged rich titles can keep personal
reminder/color edits. Their retained source is never sanitized or discarded.

The result is an inactive v5 capsule. This operation has no HTTP, persistence,
claim or sending path. Its caller must still obtain fresh authenticated context,
qualify the complete remote/native family and recheck admission before dispatch.
Independent family readback and native/SQL acknowledgement remain separate gates.

## Inactive submission qualification

An explicit occurrence reservation carries the complete retained intent through
the existing submission store and a context-bound draft. Historical constructors,
preparers, codecs and the original transport-permit constructor keep their strict
refusals. Production assembly does not install the new adapter capability.

Staging and final claim compare the original durable capture context, stamped
native child and complete native family, projected/current master and absence of
local or deferred master work. Current account, mapping, permission, editable
consent and lease guards remain live across awaits and the independent claim
transaction, including after its process-local right has been consumed.

Authenticated preparation reuses the bounded complete UID-family verifier before
and after crypto. Submission repeats those reads and compares source, key context
and notification defaults, then sends the retained request through an explicit
v5 single-use permit. Its validation view preserves the actual null notifications,
emails and signed calendar part in the original transport bytes. The request is
never replaced by its freshly generated comparison payload. These reads provide
no atomic server lock against a concurrent external edit.

Claim commits UNKNOWN before any transport call. Responses are protected evidence;
both success and lost replies remain UNKNOWN at this stage. Replaying that work
does not prepare, read fresh remote context or send again. A reservation with no
retained payload cannot regenerate one; a retained unclaimed payload may use the
existing explicit qualified resume policy. Occurrence staging checks editable
consent before reserving this right and during qualification and retention.

## Inactive independent occurrence readback

The explicit captured inspector can now open an UNKNOWN v5 attempt with its
retained intent. A bounded authenticated UID query must return exactly two
distinct IDs, including the frozen master. The inspector fetches both events,
checks the original master source and signed base, then decrypts and verifies
the child against the retained attempt. Its original slot, generated signed
packets, personal flag, notifications (including null inheritance or email),
color and current decoded content must match. The GET response may supply
authors for the signatures. The inspector checks membership again and returns
the child's observed ID and revision. A protected success response can constrain
that ID. Missing responses still require the full independent proof.

These reads do not provide an atomic server snapshot. Incomplete or conflicting
observations remain UNKNOWN. Inspection has no journal or send capability.
The production assembly keeps the occurrence adapter disabled.

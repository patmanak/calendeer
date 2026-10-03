# Conditional Proton Calendar writes

Inspection date: 2026-10-03. The client-source audit below is not a server
contract or live conditional-write qualification. No account/device operation,
upstream build or protocol mutation was performed for that source audit.
A subsequent debug-only read-only inspection is described separately below.

## Decision

Keep UPDATE and DELETE submission unavailable in the current adapter. The
inspected official clients provide useful mutation builders and cryptography,
but do not establish an atomic server comparison against Calendeer's captured
base. A fresh GET followed by a PUT leaves a race with another writer. Neither
UID nor iCalendar SEQUENCE resolves it. Do not represent these endpoints as an
implementation of a conditional-write contract.

This is **absence of a demonstrated mechanism in the inspected client paths**,
not a claim that Proton's server cannot support one. A documented, supported
precondition or an authoritative server implementation is still needed before
enabling outgoing edits with the current conflict guarantees.

## Resolved public references

| Repository/ref | Resolved commit | Inspection scope |
| --- | --- | --- |
| `ProtonMail/android-calendar`, default `release/2.29.0-332` | `61f983b58d0abfb39d1672e8ee6a730fea094486` | Branch inventory only; default is older than the release inspected below. |
| `ProtonMail/android-calendar`, `release/2.34.0` | `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f` | API, save/delete use cases, event model and iCalendar partitioning. Latest numbered release branch returned by the branch inventory. |
| `ProtonMail/WebClients`, `main` | `40cc08c12c8fbac7f66521186c7297ddf3e17861` | API types/routes, mutation builder, action submission, sequence and event serialization. |
| `ProtonMail/WebClients`, `release/proton-calendar@5.0.136.0` | `7a4c5cd8fe55baaf6643ee79622d6424a98df138` | Latest numbered Calendar release among matching release refs. Six mutation/serialization boundary files listed below were byte-identical to inspected `main`. |

The recursive trees inspected for Android 2.34.0 and Web `main` were complete
(`truncated=false`, respectively 1,059 and 24,686 entries). This does not imply
that every source file or every Web product release was inspected.

The six release comparison files were `packages/shared/lib/api/calendars.ts`,
`packages/shared/lib/interfaces/calendar/Api.ts`,
`applications/calendar/src/app/containers/calendar/getSyncMultipleEventsPayload.ts`,
`applications/calendar/src/app/containers/calendar/eventActions/sequence.ts`,
`packages/shared/lib/calendar/serialize.ts` and
`packages/shared/lib/calendar/veventHelper.ts`.

## Wire operations and metadata

Android's `CalendarsApiService.syncEvents` uses PUT on
`calendar/v1/{calendarId}/events/sync`. Its update container has `ID` and `Event`;
its delete container has `ID` and `DeletionReason`. `Overwrite` is on the create
container. `SyncEvent` includes encrypted contents, attendee metadata,
notifications and color, but no expected revision or expected ModifyTime. The
service method declares no conditional HTTP header.
[Android API and DTOs](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt#L107)

Web's `syncMultipleEvents` route and `getSyncMultipleEventsPayload` produce the
same shapes for same-calendar update and delete, with `MemberID` at the request
level. UPDATE uses newly serialized content; DELETE sends no old content or
version. The builder emits `Overwrite: 0` for creation. Its linked-event/calendar
move branch is a distinct operation, not a conditional same-calendar update.
[Route](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/api/calendars.ts#L296),
[request types](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/interfaces/calendar/Api.ts#L169),
[payload builder](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/containers/calendar/getSyncMultipleEventsPayload.ts#L180)

| Value | Observed role | Conditional-write implication |
| --- | --- | --- |
| `ID` | Identifies the calendar event to update/delete. | Identity alone does not qualify its current contents. |
| `UID` | iCalendar identity; also used for linked events and recurrence-related retrieval. | No demonstrated compare-and-swap or idempotency guarantee. |
| `SEQUENCE` | Scheduling/recurrence changes; may remain unchanged for a title change. | It is not a version that changes on every edit, nor a demonstrated server precondition. |
| `Overwrite` | CREATE/import choice; Android enables it for import creation. | Do not add it to UPDATE/DELETE as an invented conflict mechanism. |
| `ModifyTime` | Returned event metadata. | Not supplied as an expected value by the inspected update/delete builders. |

Android `HandleSaveUseCase.handleSequence` compares status, time and recurrence
properties and still carries a conflict-resolution TODO. Web
`withVeventSequence` preserves the prior sequence when date/time and recurrence
conditions allow. This explains why an incremented scheduling sequence cannot
stand in for an opaque immutable base.
[Android sequence logic](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt#L980),
[Web sequence logic](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/containers/calendar/eventActions/sequence.ts#L19),
[returned metadata](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/interfaces/calendar/Event.ts#L69)

## Preservation and reuse limits

Web's form conversion retains remaining VEVENT properties through `rest`.
`getVeventParts` places properties outside its known partition keys into the
shared encrypted part. Its serializer reuses available shared/calendar session
keys and avoids resending existing key packets on a same-calendar update.
These are useful patterns for an adapter-owned opaque envelope; they do not
prove a byte-preserving round-trip of arbitrary components, duplicate properties,
parameters or future JSON fields.
[Form read](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/components/eventModal/eventForm/propertiesToModel.ts#L55),
[form write](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/components/eventModal/eventForm/modelToProperties.ts#L59),
[partitioning](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/calendar/veventHelper.ts#L296),
[serialization](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/calendar/serialize.ts#L45)

Android's `splitICalendarIntoParts` reconstructs explicit fields and has an
unfinished catch-all-properties path. Its merge function can retain raw
properties, but that does not make the later split preserve every unknown field.
Copying the Android editing use case wholesale would therefore not meet the
project's general opaque-preservation requirement.
[Android partition/merge](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt#L301)

Reuse candidates remain behind the Proton boundary: Android uses Biweekly,
Proton Core/session APIs and its repository/crypto abstractions; Web uses its
VCAL model, key helpers and `@protontech/crypto`. Both pinned repositories declare
GPLv3 licensing. Preserve attribution/notices and check the actual extraction's
dependencies and tests before adoption. Nothing was copied or built here.
[Android license](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/LICENSE),
[Web license](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/LICENSE)

## Responses and failure semantics

The protocol returns indexed per-operation responses. Web's `handleSyncActions`
checks each response for `Error` or a non-success `Code`; Android's
`EditCreateEventUseCase` persists successful returned events separately from
reported operation errors. These paths do not establish all-or-nothing batch
atomicity, stale-base rejection, or safety of retrying an ambiguous PUT.
[Web submission](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/containers/calendar/InteractiveCalendarView.tsx#L1511),
[Android submission](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/EditCreateEventUseCase.kt#L404)

Android's delete use case treats `DOES_NOT_EXIST` specially and otherwise
processes indexed results; transport/API errors remain a separate path. No
dedicated expected-base conflict interpretation was found in the inspected
submission/use-case paths. Do not infer a guaranteed non-mutation from an HTTP
error, missing event, parser failure or cancellation. Readback can confirm an
observed state; it cannot retroactively prevent a concurrent edit being lost.
[Android delete handling](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleDeleteUseCase.kt#L240)

## Smallest safe next experiments

1. With fabricated sealed fixtures, round-trip an unchanged opaque envelope and
   a title-only patch through an extracted serializer. Include unknown properties,
   parameters, duplicate properties, subcomponents and untouched personal/attendee
   parts. Refuse shapes that cannot be preserved; never use a simple CREATE
   serializer as a general UPDATE serializer.
2. In a local transport fixture, change the simulated remote state after the
   preflight read and before submission. Require the current adapter capability
   gate to refuse UPDATE/DELETE before any write admission or HTTP mutation.
   This verifies Calendeer's protection, not Proton server behavior.
3. If read-only service inspection is separately authorized, inspect a synthetic
   event's GET response headers and version metadata without writing. An ETag or
   revision, if present, is only a candidate: it still needs an authoritative
   mutation precondition contract. Do not send a speculative PUT to discover it.

Live conditional UPDATE/DELETE, backend atomicity, arbitrary opaque round-trips
and extracted-upstream buildability remain **NOT RUN / UNESTABLISHED**.

## Enforced local transport boundary

The current `ProtonSingleDispatchAttempt` now accepts only the existing single
CREATE shape on the shared v1 sync endpoint. It validates before constructing
the one-shot body or entering Core/HTTP. The exact root is `MemberID`, `IsImport`
and `Events`; the array contains one `Overwrite: 0` / `Event` operation. Member
identity must be a nonblank string and import/overwrite must be numeric zero.
The event admits the existing shared-only fields or the same fields with explicit
empty `Notifications`. Identity/update/delete/move containers, mixed operations,
overwrite/import and extra fields cannot acquire this permit. Duplicate JSON names,
including escaped equivalents, are refused at every level.

The supported fields retain their established types: numeric `Permissions` and
`IsOrganizer` equal to one, a nonblank string key packet and array shared content.
The discriminator does not validate fragment signatures or acquire scope/rights;
those remain responsibilities of preparation, protected codec and submission
admission. Original payload bytes are preserved. A length precheck and 4 MiB
UTF-8 transport ceiling accommodate the existing bounded plaintext's encryption,
base64 and JSON overhead; this is a local limit, not a service guarantee.

A loopback test reads an original event, advances the simulated remote state,
then tries an UPDATE through this CREATE permit. Before the guard, the PUT reached
the fixture and overwrote its newer value; afterward, only the GET reaches it.
This proves a local rejection, not Proton stale-base rejection. Supported CREATE
and Core's single-dispatch/re-entry behavior retain separate regression checks.

The submission adapter can already have committed `OUTCOME_UNKNOWN` before
`remote.send` constructs its transport permit. A local refusal does not roll back
that journal, produce `NotApplied` or admit another attempt. The existing codec
qualifies the supported payload before durable admission. This additional transport
fence does not enable UPDATE/DELETE or resolve their conditional-write dependency.

## Debug read-only metadata inspection

`ProtonEventVersionProbe` exists only in the debug source set. It performs the
existing authenticated event GET through Proton Core, under the authentication
runtime lock, after exact account/hash and session checks. A session change
before dispatch prevents the request; a change after it discards the profile.
It neither prepares nor submits a mutation and adds no conditional headers.

The response uses the existing 1 MiB strict UTF-8/envelope parser and checks the
event/calendar identities. Both success and error bodies are closed on refusal,
parse errors and cancellation. Public results contain only header cardinality
and emptiness, and numeric-field categories. No raw tag/date, timestamp, event
content, stable identity or credential is returned or persisted. `SINGLE` does
not validate HTTP syntax; combined header values are not parsed as separate tags.
Numeric classification accepts bounded non-negative lexical integers only.
The existing event parser additionally requires a valid `ModifyTime`, so a live
response with a missing/null/invalid modification field is refused rather than
returned as a profile. The pure classifier tests also cover those categories.

An authorized retained synthetic-event inspection observed:

| Item | Observation on that response |
| --- | --- |
| ETag | Absent |
| Last-Modified | Absent |
| ModifyTime | Non-negative integer |
| CreateTime | Non-negative integer |

This is one successful GET, not a guarantee about every event, server response
or endpoint. Before/after checks retained the same decoded remote event,
projected SQL baseline, local UPDATE/DELETE intentions, uncertain CREATE attempt,
calendar selection/access and Provider ownership/revision/dirty/deleted witness.
They do not compare every database table or every Provider field. No event or
account attribute was deliberately mutated; the synthetic fixture was retained.

These timestamps do not demonstrate an atomic comparison at the mutation
endpoint. HTTP's conditional-request rules describe server evaluation of a
precondition, not mere header presence on a prior GET; no supported Proton
precondition for the batch sync endpoint has been established here.
[HTTP conditional requests](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1)
UPDATE/DELETE remain unavailable; live conditional submissions remain NOT RUN.

# Temporal UPDATE implementation slice

Confirmed target: date/time editing through the Android calendar client, with
replaceable Proton integration, durable offline work and no bridge event editor.
This document defines the initial bounded profile; it does not enable sending.

The immutable candidate, plaintext patcher, protected cryptographic preparation,
authenticated preparation port and offline request/readback rules are implemented.
Guarded submission, durable attempt ownership and typed native acknowledgement
are implemented as separate capabilities. Import-coordinator orchestration,
worker consent/UI and live service qualification remain unfinished. The installed
application has no new time-edit option and performs no automatic temporal sending.

## Initial profile

- Ordinary nonrecurring events with known-empty details and no custom color.
- Time/date fields only; title, description, location and other mapped fields stay exact.
- Keep the same timed/all-day kind; timed events retain the exact zone ID.
- Whole-second timed bounds and all-day dates within 1970–9999; positive duration
  and exclusive all-day end. Unsupported/ambiguous local offsets are refused.
- Unknown source properties remain raw and untouched. Floating dates, embedded
  timezone components, DURATION, recurrence, guests and alarms are outside this profile.
- Existing title/text formats, consent, request identities and wire profiles remain unchanged.

Kind/zone transitions and combined time/text changes need subsequent explicit
profiles. They are held locally until qualified, not partially uploaded.

## Lossless plaintext boundary

Observe both original shared partitions with the existing bounded parser and
UID/DTSTAMP binding checks. DTSTART and DTEND belong only to the clear shared
VEVENT. Require their decoded value to equal the complete expected time; reject
missing, duplicate or inconsistent properties and unsafe parameters.

Serialize only new DTSTART/DTEND properties using the existing Proton-adapted
Biweekly CREATE serializer. Splice those properties into the original bytes;
never rebuild an UPDATE from display content. On a real change, update both
DTSTAMP values and clear LAST-MODIFIED, retain CREATED/UID, and increment the
clear SEQUENCE with a checked integer bound. A no-op returns the exact original.

Both changed partitions require new signatures and the encrypted partition keeps
its original session key. Plaintext patching alone provides neither authenticity
nor submission authority. A separate protected preparation must bind the full
original/replacement, identity, timestamp and adapter format.

## Cryptographic preparation and authenticated access

`CalendarLocalTimePreparationPort` is a separate project-owned capability exposed
through `ProtonAuthentication.localTimePreparations`. Its runtime uses the existing
session/address refresh, exact event read and local calendar/member key selection.
It freezes the entire candidate before suspension, verifies the signed original,
decodes and compares the complete imported base before patching, then renews only
changed signatures/ciphertext using the original shared session key. Both changed
parts must belong to the selected signing address. Personal partitions are refused.

`LocalTimeEditPreparation` and `ProtonTimePreparationCodec` have a distinct protected
kind/format binding full scope/generation, local identity, original/replacement,
UID, timestamp and original/replacement fragments. Guarded access closes permanently
after denial; the serialized facade shares the authentication/logout mutex and
checks current session, result and format before returning. Cancellation propagates
even if a callback cancels and then throws an ordinary IO/storage error.
Structural reopening supplies no fresh cryptographic or authorization proof.

## Offline request and readback profile

`ProtonTimeUpdateRequestCodec` binds a distinct request identity, full preparation
identity/capsule hash, qualified context and literal request. It reuses the existing
closed simple-event wire builder without widening text/title protected formats.
This request profile currently accepts **timed events only**: all-day cryptographic
preparation is tested, but the API date convention remains unqualified.

`ProtonTimeEventUpdate` binds original StartTime/EndTime/StartTimezone/EndTimezone/
FullDay to the original time and requires all five readback values to match the
replacement exactly. All other fields, signed parts, optional-field presence and
established server-clock rules retain the existing strict comparison. UTC instants
use the observed API spelling `UTC`; other zone IDs must remain exact.
`ProtonTimeUpdateReadbackVerifier` then requires real signature/decryption verification
and complete expected mapped contents with a new computed revision.

Supplied synthetic observations do not establish service acceptance or metadata
recalculation. `IsBreakingChange=0` remains an explicit hypothesis for this restricted
no-attendee profile, to qualify before transport activation. No local codec or
successful reopen is a journal claim, a dispatch permit or a native acknowledgement.

## Guarded delivery capabilities

`TimeUpdateAttemptRecord`, identities, results, cursors, receipts and completion
are disjoint from text/title contracts. Request identities use temporal domain
separators and bind full raw original/replacement, scope/generation, timestamp,
capsule and wire format. Verified readback retains its actual text bytes; CRLF/LF
equivalence never changes the retained raw intent or exact temporal values.

`ProtonTimeUpdateSubmissionAdapter` and the serialized authentication facade expose
guarded preparation/submission/inspection through the existing transport. Submission
requalifies the complete original, source, session and selected context; it requires
the exact first durable wire and commits UNKNOWN before the sole transport call.
Response evidence has an independent scoped lease. UNKNOWN inspection never sends.
The shared facade mutex/lease mechanics retain distinct typed family ports.

The shared update database adds a separate temporal table in schema 7. One writer
transaction arbitrates title reservations/attempts, text and time across generations,
including historical request-ID reuse. Temporal capsules/results/completion use
separate tags. Existing schemas 1–6 migrate without reinterpreting historical
capsules; corrupt history cannot certify a free event or request identity.

`CalendarProjection` exposes default-deny time ACK/inspection and the Android
implementation reuses the guarded native CAS with a typed time receipt. It asserts
the complete raw dirty edit and complete CREATE handoff provenance when applicable.
ACK retains desired DTSTART/DTEND, clears DIRTY and attaches the verified revision/
nonce only on an exact current row. Inspection and historical replay preserve newer
edits. These primitives are not yet composed into the time worker/coordinator flow.

## Delivery and qualification

1. Immutable project-owned candidate and lossless plaintext patcher; focused JVM
   cases for movement/duration, all-day exclusivity, DST, raw preservation,
   original binding, invalid layouts, no-op and sequence overflow.
2. Protected preparation and real Core signature/session-key tests, without transport
   (implemented, including authenticated adapter composition).
3. Separate guarded request/readback with current context and full temporal API
   metadata validation. Qualify server recalculation of StartTime/EndTime,
   StartTimezone/EndTimezone and FullDay, plus the breaking-change policy.
4. Durable ownership/UNKNOWN inspection, native acknowledgement and worker/UI
   integration with explicit consent, then real editor/service/offline/restart journeys.

Until the later stages qualify, production date/time sending remains disabled.
A GET followed by PUT is still non-atomic; no timestamp or plaintext equality
supplies a remote write lock.

## Verification

Candidate/patcher, protected preparation, authenticated access and request/readback
tests first failed against refusing implementations. All `:sync:test` (418 cases),
`:proton-auth:testDebugUnitTest` (785 cases) and `:app:testDebugUnitTest` (116 cases)
passed without failures or skips; app/test APK builds passed. Existing title/text
tests retain their unchanged-date/sequence policy. Independent source reviews found
no functional defect in the preparation or offline request/readback slices.

Six physical native cases passed: two temporal cases and four existing local/shared
text crypto cases. The temporal fixture checks binary/text original signatures,
timed/all-day preparation, renewed signatures, original-session-key decryption,
opaque span/JSON preservation, protected reopening, full-base refusals, cancellation,
actual authenticated adapter composition and supplied timed readback through the
real decoder. Altering any temporal API field is rejected before decoding.
These fixtures use synthetic keys/events, without account, service, journal or
Provider contents. No temporal service write, worker/native acknowledgement or
installation/update preservation journey is qualified by these results.

The delivery continuation established RED for domain validation, submission/facade,
shared SQL ownership and the native ACK entrypoint, then passed the suites above.
Sixty-six further physical isolated cases passed: 32 journal/arbitration/migration
cases (including existing title/text histories and schemas 1–6) and 34 Provider ACK
cases. They qualify exact raw intent, desired timed/all-day dates retained on the
same row, verified text normalization, concurrent/newer edits, guarded batches,
interrupted replies, transferred CREATE provenance, receipt history and cancellation.
Isolated databases/Provider fixtures were cleaned. Independent source review found
no material defect in domain/journal/submission boundaries. These are controlled
dependency/SQL/Provider tests, without temporal service or automatic worker delivery.

## Source evidence

The pinned Android source at `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`
(`ICalUtilsImpl`, `EditCreateEventUseCase`, calendar API DTOs), and Web source at
`40cc08c12c8fbac7f66521186c7297ddf3e17861`
(`applications/calendar/src/app/containers/calendar/eventActions/{sequence,dtstamp}.ts`) establish shared
partition placement and sequence policy. Existing adapted serializer attribution
is in [Proton provenance](../proton-auth/PROVENANCE.md). Request/readback behavior
still needs its own qualification; upstream source alone is not service evidence.

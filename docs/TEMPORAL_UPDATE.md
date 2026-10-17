# Temporal UPDATE implementation slice

Confirmed target: date/time editing through the Android calendar client, with
replaceable Proton integration, durable offline work and no bridge event editor.
This document defines the initial bounded profile; it does not enable sending.

The immutable domain candidate and plaintext patcher are implemented and covered
by JVM tests. Cryptographic preparation, transport and native delivery below
remain unfinished. The installed application has no new time-edit option.

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

## Delivery and qualification

1. Immutable project-owned candidate and lossless plaintext patcher; focused JVM
   cases for movement/duration, all-day exclusivity, DST, raw preservation,
   original binding, invalid layouts, no-op and sequence overflow.
2. Protected preparation and real Core signature/session-key tests, without transport.
3. Separate guarded request/readback with current context and full temporal API
   metadata validation. Qualify server recalculation of StartTime/EndTime,
   StartTimezone/EndTimezone and FullDay, plus the breaking-change policy.
4. Durable ownership/UNKNOWN inspection, native acknowledgement and worker/UI
   integration with explicit consent, then real editor/service/offline/restart journeys.

Until the later stages qualify, production date/time sending remains disabled.
A GET followed by PUT is still non-atomic; no timestamp or plaintext equality
supplies a remote write lock.

## Verification

The new candidate and patcher tests first failed against refusing implementations.
All tests in `:sync:test` (375 cases) and `:proton-auth:testDebugUnitTest`
(688 cases) then passed without failures or skips. Sixteen new cases cover
movement/duration, exclusive all-day end, 23/25-hour DST spans, ambiguous offsets,
exact no-op identity, original UID/time/stamp binding, raw span preservation,
folding, metadata/sequence bounds and incompatible structures. Existing title/text
tests run in the same suites and retain their unchanged-date/sequence policy.
Independent source review found no functional defect in this offline slice.
No phone, service write, signature renewal, worker or installation/update journey
was executed for this slice; those are not implied by the JVM results.

## Source evidence

The pinned Android source at `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f`
(`ICalUtilsImpl`, `EditCreateEventUseCase`, calendar API DTOs), and Web source at
`40cc08c12c8fbac7f66521186c7297ddf3e17861`
(`applications/calendar/src/app/containers/calendar/eventActions/{sequence,dtstamp}.ts`) establish shared
partition placement and sequence policy. Existing adapted serializer attribution
is in [Proton provenance](../proton-auth/PROVENANCE.md). Request/readback behavior
still needs its own qualification; upstream source alone is not service evidence.

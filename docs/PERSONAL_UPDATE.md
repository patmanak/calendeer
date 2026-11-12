# Personal event edits, format 2

The personal-edit family combines supported title, text and date changes.
It handles a combined edit in one protected request: title, description/location,
and dates may change together. Supported date changes include timed/all-day
transitions and changes of named time zone. All-day end dates remain exclusive.
The calendar must be Editable, with current account access, selection and calendar
permissions. The effective mode supplies the internal per-field admission gates
until the legacy families are retired; those gates are not separate user options.

Known personal display reminders and an explicit event color can accompany the
edit only when unchanged. Recurrence, organizers, attendees, invitations, unknown
metadata, changes to reminders/color and ambiguous DST endpoints remain held.
This is preservation, not a new reminder or color editor. Calendeer never delivers
reminder notifications; the system calendar continues to own that behavior.

## Wire and local state

`PersonalEditCandidate` freezes the full raw observation, including ordered
reminders and color. Preparation and request formats are version 2, with distinct
identities, tags and fingerprints. Fresh eligible observations, including simple
title/text/date edits, use personal-v2. Production title/text/time paths only finish
already retained capsules and inspect/acknowledge their historical requests. Their
formats remain strict and are never reinterpreted as personal edits. A retained
legacy capsule or unresolved request blocks personal adoption; its intent and
wire keep their original identity. Removing these compatibility paths is pending.

Proton Core supplies encryption, signing, session-key handling and protected local
storage. The existing pinned Proton-derived scanner/serializer is extended to
patch the complete change once, with one sequence increment and preservation of
unrelated ICS fields. The adapter requires explicit supported personal metadata,
verifies signed readback and requalifies membership before accepting a result.
No new cryptographic primitive or upstream library is introduced.

The shared attempt database adds a personal table in schema 10; every mutation
family checks the others' unresolved ownership across generations. Import schema
23 adds separate preparation, pending ACK and permanent ACK-history tables. Both
migrations are additive and retain old capsules and proofs byte for byte.

## Android and recovery

Personal qualification and acknowledgement use the personal native reader even
for a title-only edit; they never borrow the legacy reader's weaker provenance.
The Provider snapshot proves exact ownership, base revision, dirty status, dates,
text and color. Reminder count, IDs, offsets and ALERT method are asserted in the
same Provider batch as the event row. Attendees and extended properties must be
absent. Neither capture nor acknowledgement replaces reminder rows. A changed
child ID with the same offset/count is still a failed comparison.

For personal all-day capture, the start zone remains canonical `UTC`. An explicit
fixed-zero-offset end-zone alias such as `Z`, `Etc/UTC` or `GMT` is accepted when
an editor retains it during conversion. Midnight and exclusive-end checks still
apply. The raw end-zone token stays in the conditional native assertion and is
preserved by acknowledgement. Missing, malformed, seasonal or nonzero-offset
end zones are refused. Timed, legacy and transferred-CREATE readers keep their
existing stricter rules.

The worker uses separate discovery, inspection and recovery cursor namespaces.
Consent is checked for every changed field, including after asynchronous waits.
An uncertain request is inspection-only. Retained verified facts may finish local
acknowledgement with field sending disabled, under current general access; this
never authorizes a new request. A reserved receipt first inspects the historical
native marker, preserving a later dirty edit. The separate optional
`personalUpdateAcknowledgementRecoveryProtocol == 1` capability can finish a
pending receipt interrupted before the native write: the coordinator requalifies
the exact SQL owner, preparation, handoff, current native content, durable result
and unchanged receipt before calling it. The native implementation inspects again
before the existing conditional Provider batch. It keeps the original nonce and
never inserts, sends a request or replaces a changed local snapshot. Missing or
unknown recovery capabilities remain inspection-only. Legacy families retain
their original contracts.

A fresh GET followed by PUT is not atomic server compare-and-swap. Trustworthy
comparable edit timestamps remain unavailable, so processing or capture time is
not used to pick a winner. Unsupported or conflicting variants remain retained.

Host suites, synthetic native crypto, SQLite/Provider fixtures, real service and
system-editor journeys are separate evidence. See [validation](VALIDATION.md)
for measured results; adding the family does not qualify Samsung/One UI.

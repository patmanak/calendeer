# Personal event edits, format 2

The personal-edit family extends the independent title, text and date options.
It handles a combined edit in one protected request: title, description/location,
and dates may change together. Supported date changes include timed/all-day
transitions and changes of named time zone. All-day end dates remain exclusive.
Each changed field requires its own enabled calendar option. CREATE mode and
current account, selection and calendar permissions remain prerequisites.

Known personal display reminders and an explicit event color can accompany the
edit only when unchanged. Recurrence, organizers, attendees, invitations, unknown
metadata, changes to reminders/color and ambiguous DST endpoints remain held.
This is preservation, not a new reminder or color editor. Calendeer never delivers
reminder notifications; the system calendar continues to own that behavior.

## Wire and local state

`PersonalEditCandidate` freezes the full raw observation, including ordered
reminders and color. Preparation and request formats are version 2, with distinct
identities, tags and fingerprints. Existing title/text/time formats remain strict
and their persisted requests are never reinterpreted as personal edits. Eligible
legacy observations continue through their existing families.

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

The Provider snapshot proves exact ownership, base revision, dirty status, dates,
text and color. Reminder count, IDs, offsets and ALERT method are asserted in the
same Provider batch as the event row. Attendees and extended properties must be
absent. Neither capture nor acknowledgement replaces reminder rows. A changed
child ID with the same offset/count is still a failed comparison.

The worker uses separate discovery, inspection and recovery cursor namespaces.
Consent is checked for every changed field, including after asynchronous waits.
An uncertain request is inspection-only. Retained verified facts may finish local
acknowledgement with field sending disabled, under current general access; this
never authorizes a new request. A reserved historical receipt only inspects the
native marker, preserving a later dirty edit.

A fresh GET followed by PUT is not atomic server compare-and-swap. Trustworthy
comparable edit timestamps remain unavailable, so processing or capture time is
not used to pick a winner. Unsupported or conflicting variants remain retained.

Host suites, synthetic native crypto, SQLite/Provider fixtures, real service and
system-editor journeys are separate evidence. See [validation](VALIDATION.md)
for measured results; adding the family does not qualify Samsung/One UI.

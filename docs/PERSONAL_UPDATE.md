# Personal event edits, format 2

The personal-edit family combines supported title, text and date changes.
It handles a combined edit in one protected request: title, description/location,
and dates may change together. Supported date changes include timed/all-day
transitions and changes of named time zone. All-day end dates remain exclusive.
The calendar must be Editable, with current account access, selection and calendar
permissions. The effective mode supplies the internal per-field admission gates
until the legacy families are retired; those gates are not separate user options.

Imported and transferred events support changes to display reminders and event color,
including removal and calendar-color fallback. Before/at/after-start reminders use
explicit minute offsets. Proton email reminders remain unchanged. Recurrence,
organizers, attendees, invitations, unknown metadata and ambiguous DST endpoints
remain held. Initial CREATE captures supported reminders/color using distinct
personal-v3 preparation and submission formats. The historical known-empty
formats remain strict and inspectable; uncertain requests are never regenerated
or resent. Shared signatures/encryption use the existing Proton primitives;
personal API siblings and the complete readback are checked separately.
The calendar client provides the editor. Calendeer never delivers
reminder notifications; the system calendar continues to own that behavior.

## Wire and local state

`PersonalEditCandidate` freezes the full raw observation, including ordered
reminders and color. Preparation and request formats are version 2, with distinct
identities, tags and fingerprints. Fresh eligible observations, including simple
title/text/date edits, use personal-v2. Legacy writers are retired; retained work
is visibly unsupported, with only historical inspection/acknowledgement. Their
formats remain strict and are never reinterpreted as personal edits. A retained
legacy capsule or unresolved request blocks personal adoption; its intent and
wire keep their original identity.

Metadata editing retains the existing v2 encoding: both complete snapshots and API
siblings were already frozen. Unchanged fields preserve their raw JSON, including
email ordering, trigger spelling and color case. Changed reminders replace only
device notifications, retaining every email entry. Exact readback checks the full
new metadata. A fixture captured before this extension checks the original capsule,
request bytes and fingerprints; UNKNOWN remains inspect-only.

`Notifications: null` preserves calendar-default inheritance when reminders are
unchanged. A reminder change freezes the initial timed/all-day calendar defaults
in an optional protected `notificationDefaults` snapshot, separate from the
unchanged original row. Offline reconstruction keeps those default email entries
and writes explicit phone alerts. This snapshot is required only for the new
null-plus-changed-reminders profile; historical capsule bytes remain unchanged.
Readback checks both forms. A timed/all-day transition retaining null is held
before sending when destination defaults would change the intended phone alerts.

Web-created simple events may also carry one signed calendar partition containing
UID/DTSTAMP and supported TRANSP/STATUS values. An optional `calendarSignedProfile`
with sub-profile version 1 retains its renewed part inside the existing capsule;
historical empty-partition capsules gain no fields. Preparation verifies its
signature, selected author and shared UID/DTSTAMP, preserves unrelated bytes,
renews DTSTAMP consistently and signs it with Proton Core. The request includes
`CalendarEventContent`; readback checks the exact retained part and its signature.
Encrypted calendar parts, recurrence, cancelled status, unknown properties and
additional partitions remain held. UNKNOWN reconstructs only its retained request.
Proton web fragments may omit the final CRLF; the scanner preserves that ending
without accepting LF-only separators. Personal editing also retains absence of
optional CREATED/LAST-MODIFIED dates, validating chronology and uniqueness when
present. It advances DTSTAMP/SEQUENCE without inventing missing dates. Historical
title/text/time metadata profiles retain their stricter date requirements.

Transferred events retain their original captured creation receipt. Only the
current handoff reference accepts known personal metadata. Frames, observations
and projection plans own immutable snapshots. Personal ACK preserves reminder
rows; incoming projection changes them only with exact parent/child assertions
in the same batch. Historical markers recover lost responses without consuming
later edits. Existing encodings and provenance fingerprints stay unchanged.

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
absent. Neither capture nor acknowledgement replaces reminder rows. Palette keys,
email/SMS Provider reminder methods, duplicate offsets and unknown metadata are
held. An existing receipt cannot consume later reminder/color changes. A changed
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

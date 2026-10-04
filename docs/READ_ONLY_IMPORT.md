# Initial read-only import

Calendar selection is opt-in and persisted per account/calendar. Selected calendars
are mirrored in Android's Calendar Provider using a bridge-owned account type.
The authenticator owns the local account but does not expose or store Proton
credentials in Android AccountManager. Existing calendars from other accounts are
never import targets. The mirror advertises read-only access; there is no upload path.

## Supported contents and coverage

The adapter imports supported events: title, description, location, timed
start/end with a supported timezone, and all-day dates with an exclusive end.
An event's custom Proton color is preserved as an Android event color. Missing
color inherits the calendar color; removing an override clears the old Android
value on refresh. Existing imported rows update in place. Event colors are stored
offline, while old cached rows acquire newly supported colors at the next fetch.
Floating timestamps, cancelled standalone events and unverified
external-author signatures are retained as individually deferred records when
their envelopes have valid identities. Supported records on the same page proceed.
Malformed page identities and unusable key context still block the page. The UI
distinguishes unsupported content from content requiring verification, and reports
a partial import while either remains.

Personal DISPLAY reminders use the event's current Proton notification settings.
An absent or null override inherits the calendar's timed or all-day defaults; an
explicit empty list disables them. Historical shared VALARM components are not
treated as personal preferences. Up to five distinct alerts with exact signed
minute offsets within one year are supported, except Android's reserved -1 value.
EMAIL reminders remain Proton's responsibility. Unknown defaults or unsupported
triggers defer the event instead of silently removing an alert.

The system calendar application delivers local reminders. Calendar Bridge does
not generate a second notification or request notification permission itself.
All-day offsets use the Provider's local-day interpretation; DST transitions and
OEM alarm scheduling require separate notification qualification.

Verified event content can expose an organizer and up to 100 mailto participants,
with supported roles and response statuses. Current API responses are matched to
decrypted participants through Proton tokens. Partial response lists, ambiguous
identities, delegation and unsupported roles remain deferred. The bridge sends
no invitation or response and grants no organizer rights. External authors still
require a separately integrated trust chain; fetching an arbitrary public key is
not sufficient. Such events remain marked as requiring verification.

Event updates atomically replace native reminders and participants with their
parent row. Replays retain the event identity without accumulating child rows.
Calendar-default changes force a bounded rescan even when event modification
times are unchanged; the change cursor and remaining obligations survive it.

Recurring masters use native Android rules, duration, additional dates and excluded
dates. The supported rule frequencies are daily, weekly, monthly and yearly, with
validated RFC fields. Single-occurrence moves and cancellations retain the original
slot separately from their new start; UID and calendar scope link them to the master.
Android expands occurrences when a calendar application queries a date range.
The bridge does not eagerly materialize an infinite series.

EXRULE, RDATE periods, RANGE exceptions, floating/unresolved dates, BYSETPOS,
BYWEEKNO and BYYEARDAY remain deferred. Yearly BYDAY/BYMONTHDAY require BYMONTH.
These limits prevent accepted RFC syntax from being silently misinterpreted by
the native provider. Unsupported signatures remain subject to the existing trust
boundary. A duplicate active master or changed remote recurrence identity blocks
application rather than guessing a new relationship.

An exception received before its usable master stays pending while subsequent
pages continue. The same applies while the master is deferred or its original
date type does not yet match a newly received exception. A completed traversal
with unresolved series dependencies reports waiting for the original series,
without a new success timestamp or an immediate retry loop. No unqualified UID
lookup or inferred remote deletion is used to resolve missing masters.
Deleting a known master removes its owned native exceptions atomically. Durable
associations prevent cached or late children from reappearing after that deletion.
A child tombstone alone does not delete its master. Converting a master to a simple
event also clears its native children, preserving their cached remote evidence.

The provisional rolling query horizon is 90 days before today through 365 days
after today. New traversals load today plus/minus 30 days first, then the remaining
past and future intervals. The adapter freezes dates, timezone and traversal order
in its continuation, uses chunks of at most 42 days and four event query types,
and follows the server's explicit continuation flag. Each engine pass commits at most four
pages of at most 100 events. Successful continuation schedules another bounded
pass; backoff is reserved for failures. Download and projection have separate
durable completion states.
The priority layout has twelve chunks (48 requests when all pages are empty).
Existing ten-field cursors and sealed receipts keep their legacy order and exact
encoding through completion; new cursors carry an explicit `near-first-v1` marker.
This preserves acquired data across upgrades, including a first-page receipt with
no request cursor. A compatible completed observation and a drained change feed
can now avoid repeating already observed event-window requests.
Received pages and bootstrap material are sealed using Proton's Android Keystore
protection and committed before decryption. One unapplied receipt per calendar
keeps receipt progress separate from application progress. Local decode reads
protected address keys directly from Proton's local DAO, without a network fallback.
Missing local keys retain the receipt and produce an explicit blocked state.

A durable coverage register records each traversal's frozen query intervals,
received/applied page counts and their latest receipt/application times. A period
is received only after all query types and pages have arrived; it is processed
only after their local transactions commit. Unsupported events can remain after
processing. The UI therefore separates received/processed periods, deferred events,
pending Android updates and persistent change-history gaps. An empty projection
queue is not proof that every remote event is supported or projected.

Coverage alone does not prove an atomic snapshot, a deletion or change-feed
continuity. New scans, initial feed anchoring and feed resets advance
a local traversal epoch together with the checkpoint. Late results cannot join
another epoch. One current traversal and the last finished observation are retained;
interrupted observations are not an unbounded history. Old stored receipts retain
unknown coverage, and a migrated mid-period traversal cannot claim its unobserved
prefix. The next full traversal can establish new observations.

After a fully processed scan, a compact observation retains its contiguous queried
dates. If the feed was caught up, no unsupported/blocked work or history gap is
known, and the timezone and adapter format match, a fresh import queries only
newly entered future dates. The same date range needs only a change-feed check.
New intervals are split into at most 42 days and frozen in a scoped continuation;
every interval still requires all four query types. An unchanged event can therefore
enter the rolling horizon without producing a change notification.

Admission atomically retains the new plan and the required subsequent feed check.
A reset discovered after extension invalidates reusable observations and starts
a full scan before success can be reported. Unknown coverage, changed timezone,
backward expansion, disjoint horizons, unresolved work or history gaps use the
full scan path. Loss of calendar access invalidates reuse without discarding data.
Existing Android records are still requeued for local repair; the optimization
reduces event-window downloads, not every local write or metadata request.

A completed traversal is an observation of the queried windows, not an atomic
server snapshot. Later imports extend or repeat the traversal and reconcile known records.
Calendar change polling captures an opaque anchor before the initial traversal.
Sealed change pages commit their next cursor together with durable per-event
obligations. Changed events are fetched individually and decoded locally; only
an explicit authoritative deletion notice can remove a bridge-owned Android row.
Absence, HTTP 404, permission failure and leaving a date window never delete a
local event. Unresolved per-event reads remain visible as incomplete work and are
retried on fresh refreshes. New authoritative notices can resolve them.

Each pass processes at most four change pages or twenty event obligations.
Key-context invalidations precede event processing. Network retries recheck the
complete catalog and local key context; this currently costs additional metadata
requests on continuation batches. The global account feed remains unconnected.
A missing calendar in a successful complete catalog is marked unavailable, retaining
its selection, cached data and Android mirror. Catalog failures do not imply absence.

A validated advancing feed reset archives its sealed evidence and resumes through
a bounded window rescan followed by newer changes. Its individual notices are not
applied. A persistent history-gap warning preserves the distinction from complete
reconciliation: a fresh scan cannot establish which older events were deleted.
Non-advancing resets and unclassified cursor errors remain blocked. Periodic scans retain
consumed tombstones and cannot resurrect them. Editing requires subsequent integration.

## Persistence and offline work

The private SQLite database commits decoded data, deferred encrypted evidence,
projection intent, applied checkpoint and receipt removal together. Failed
transactions roll back. Schema 7 migration preserves existing selections, checkpoints,
receipts, event rows and change-history evidence without inventing past coverage,
and retains durable scoped series associations. Optional versioned recurrence and
event-detail data leave the legacy simple-event encoding unchanged. A separate
content-mapping profile invalidates reusable coverage on upgrade and durably
requires a full rescan after older work finishes. Completion cannot be reported
while that migration remains pending. Legacy sealed single-event receipts retain
their original acknowledgement contract before the new mapping is fetched.
Projection acknowledgements
match the expected record, so an old acknowledgement cannot consume a newer edit.
Provider identities and atomic assertion/write batches make clean replay idempotent
after interruption between the provider write and acknowledgement. Simple upserts
and supported recurrence masters/exceptions also retain a preparation and write its nonce
with the event; exact marker inspection
can restore the projected base after a lost ACK while preserving subsequent dirty
or deleted flags. Exceptions retain their original slot separately when display
changes between timed and all-day dates. Newer pending remote evidence is not consumed by an older receipt.
See [local capture](LOCAL_EDIT_CAPTURE.md) for the subset and refusal rules.
Rebuilding a missing mirror requeues cached records. A fresh traversal reprojects
known supported clean records; dirty/deleted local rows remain protected and can
block the import. A newer deferred version prevents an older queued version from
being reprojected.

Explicit refresh arms a durable local retry of retained events before scheduling
network work. Batches of four retries resume independently of connectivity, and
compare the exact retained evidence before acknowledgement. Unsupported results
do not continuously requeue themselves. Local projection remains bounded to 100
records per pass. A completed traversal is reported complete or partial only after
its pending local work has drained; the completion time survives a split drain.

Sealed receipts are limited to four MiB of encoded characters each; combined
receipt/deferred/delta evidence storage, including notice and coverage metadata,
has a 32 MiB UTF-8 admission budget. Each versioned coverage payload is limited
to 256 KiB and each plan to 128 disjoint periods in one timezone. This is not
a limit on the physical SQLite file or supported-event cache. Decoded content is
limited independently to one MiB UTF-8 per event and eight MiB accepted per page.
A rejected event does not consume another event's budget. Capacity failures retain
the page and stop advancement instead of labelling later valid records unsupported.
Native PGP allocation occurs before the post-decryption size check; this is not
a proven pre-allocation decompression limit.

WorkManager schedules execution; SQLite owns the queue and selection. Network
workers require connectivity; local projection workers do not. Startup rediscovers
pending selected work. One process coordinator serializes all import triggers.
Account identity and a run admission token are checked before accepting results;
sign-out invalidates in-flight admission and cancels scheduled work. Account-scoped
records cannot be reused by a different account. No destructive purge occurs on
pause or sign-out: already projected data remains available in Android.

Transient failures back off and retain pending work. Server retry delays are stored
before another network attempt. Permission/authentication/unsupported-content
blocks remain visible; finishing a local projection does not erase the network
failure. The periodic refresh target is six hours, subject to Android scheduling
and OEM restrictions, with a manual trigger available.

A temporary Provider outage or disappearing mirror requests a worker retry rather
than reporting completion with pending projections. Local retries require no
network and preserve any existing network failure. Transient SQLite open, lock,
I/O or full-storage exceptions escaping the worker request backoff without relying
on another successful database write. Rejected Provider batches, identity conflicts
and corrupt schemas are not silently retried as temporary outages. Cancellation
propagates, and the UI leaves its busy state even if diagnostic refresh also fails.
The same transient storage failures during startup selection restoration, catalog
persistence or an explicit refresh also schedule durable local and network work.
This preserves previously admitted work even when the selection list cannot be
read at the time of the trigger. The calendar list may briefly appear empty while
the store is unavailable; it is refreshed after a successful worker run.

## Access and privacy

Incoming event replacement/deletion now asserts that existing rows are clean and
non-deleted, including recurrence children removed with a master. An unexpected
dirty or locally deleted row is preserved and its import remains blocked, rather
than overwriting data. Explicit internal simple UPDATE capture can retain the
local variant with its original projected base and latest remote evidence, but
does not enable editing or send an upload. Unsupported edits and user-facing
resolution remain future work; see [local capture](LOCAL_EDIT_CAPTURE.md).

Enabling projection requires both Android calendar permissions. The UI explains
why Android write access is necessary for a read-only Proton mirror, and offers
an explicit request and application-settings shortcut. Permission checks run on
foreground return and at provider operations. Refusal does not block login or
settings, and revocation retains pending work.

Projected event content is readable by other applications granted Android calendar
access. The private import database also holds decrypted supported fields in
app-private storage; app data is excluded from backup. The Android mirror is not
an end-to-end encrypted Proton store. This consequence is displayed before access
is requested. No event content, keys, tokens or stable private identifiers belong
in diagnostics or source control.

Calendeer posts no notifications. It copies supported reminder data into Android;
the system calendar application delivers reminders according to its own settings.
Synchronization progress and errors are available inside Calendeer only.

See [validation](VALIDATION.md) for isolated fixtures and opt-in live checks,
[permission policy](PERMISSIONS.md), and [adapter provenance](../proton-auth/PROVENANCE.md).

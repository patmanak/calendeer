# Internal local UPDATE and DELETE capture

The `ImportCoordinator.captureLocalEdit` entry point retains one local
change to an already imported simple event. The opt-in system creation controller
now discovers and retains these changes automatically; it does not send or
acknowledge an UPDATE. Read-only remains the default calendar mode. `captureLocalDelete` separately
retains an explicit local deletion of an already imported simple event; it does
not submit or acknowledge that deletion.

## Supported snapshot

The first subset covers title, description, location and time: timed events with
an explicit consistent timezone and second precision, or UTC all-day dates with
an exclusive end. Event color must retain its imported value. Recurrences,
exceptions, reminders, participants, organizer fields, custom application data,
extended properties and unsupported flags cannot become partial edits.

The current import's known empty `EventDetails()` profile is preserved exactly.
Legacy null details stay unknown; capture never upgrades them to known empty or
claims that unknown remote fields can safely be rewritten. A future write adapter
must preserve and qualify the full remote event separately.

The UPDATE reader requires one bridge-owned calendar and one event with the
exact scoped remote identity and base revision marker, `DIRTY=1` and `DELETED=0`.
An assertion-only Provider batch checks the complete observed row, ownership and
absence of reminder, attendee and extended-property rows. The reader neither
changes the event nor clears its dirty flag. Missing, ambiguous, unsupported or
concurrently changed rows remain untouched.

## Personal editing permission profile

`PERSONAL_OWNER_V1` is a local Calendar Provider profile for non-recurring events
whose remote details are explicitly `EventDetails()`. Projection sets
`ORGANIZER` to the owning local calendar's opaque account label and
`IS_ORGANIZER=1`, with `HAS_ATTENDEE_DATA=0` and all guest flags zero.
This local permission label is not an email or remote organizer identity;
it creates no attendee, reminder or invitation and never enters the domain or
Proton request. Unknown
details, invitations and richer events retain their existing permission mapping.
Calendar access remains READ unless the experimental calendar mode is enabled.

AOSP's editor requires calendar write access and either local organizer identity
or `guestsCanModify`. It derives organizer identity by comparing the event
organizer with the calendar owner, so setting `IS_ORGANIZER` alone is insufficient.
Some event-detail screens also require organizer identity to show the edit button;
`GUESTS_CAN_MODIFY=1` alone did not expose editing in the qualified client.
Its existing-event save retains `HAS_ATTENDEE_DATA` and does not set organizer
or guest flags. [Pinned editor source](https://android.googlesource.com/platform/packages/apps/Calendar.git/+/7b051c6e9ef7922f08d9ebf664ac3087d5fa0552/src/com/android/calendar/event/EditEventHelper.java).

Ordinary capture and capture after CREATE handoff recognize this exact profile,
alongside the previous zero-flag profile. They still assert the full row and
absence of children atomically, leave DIRTY unchanged and refuse other flags or
unsupported data. Raw CREATE admission retains its separate measured profiles.
Incoming projection applies the profile only when the row is eligible for that
projection; it never repairs a dirty row or discards a retained intention.

Existing clean rows gain the profile on their next projection; this change does
not migrate every already settled row. Provider metadata and direct editor
launch alone cannot qualify every client. Edits adding participants remain
unsupported and must not be stripped or sent as a partial title change. Outgoing UPDATE/DELETE remain
unimplemented.

Physical qualification with the /e/OS calendar client opened the projected event's
detail screen, used its Edit button, saved a title and captured it after application
restart on the same row with no participant or reminder. Provider fixtures also
qualify the CREATE-handoff path and dirty-row protection. This does not establish
Samsung client behavior, migration of settled rows or Proton UPDATE compatibility.

## Durable state and imports

Import schema 10 separates four facts:

- `records`: latest supported remote content or authoritative tombstone;
- `projection_bases`: last upsert successfully acknowledged after projection;
- `local_edits`: immutable scoped ID, adapter format/version, original base and
  captured supported content, with kind `UPDATE` or `DELETE` and state `PAUSED`
  or `CONFLICT`;
- `projection_receipts`: an immutable preparation for a supported upsert,
  with the exact payload, adapter format, previous base and a random protocol nonce.

Acknowledgement compares the exact still-pending payload and absence of a local
intent in one transaction before advancing the base. A stale acknowledgement
cannot advance it. A pending remote deletion retains the old base until deletion
is acknowledged, then removes it. Migration uses settled, non-deferred legacy
upserts only as baseline candidates; the live Provider marker still must qualify
them. An incompatible adapter/generation needs explicit migration or reconciliation.

Capture checks current account, generation, permissions, selected availability and
compatible checkpoint format. It compares the retained base exactly, inserts the
intent and suppresses projection atomically. If the latest remote record already
differs or deferred evidence exists, the intent starts in `CONFLICT`. Later imports
continue to retain remote data and checkpoint progress while suppressing projection
of that event. Divergence or deferred evidence makes conflict persistent, even if
the original remote version returns. Requeues, mirror repair and series-child
requeues cannot reopen a retained intent for projection.

Identical retained work is idempotent. A different ID or changed content for that
event is refused; there is no coalescing, automatic conflict resolution or reopening.
Both states block upload. Generic outgoing mutation storage remains unavailable.
Lease loss, cancellation and database failures before commit roll back the entire
capture or import/conflict transaction.

## Local deletion snapshots

DELETE capture requires a present, uniquely owned tombstone with `DIRTY=1` and
`DELETED=1`. It uses the same supported-field and assertion-only snapshot checks
as UPDATE. Physically missing rows, unsupported child data, foreign identities
and changed revisions do not become inferred deletion requests. Content can be
unchanged; edits made before deletion are preserved as a separate `EventContent`
snapshot against the original projected base.
Only fields still observable in the tombstone are captured. The ordinary
[AOSP deletion path](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/master/src/com/android/providers/calendar/CalendarProvider2.java)
can remove reminder and extended-property rows while retaining the event. Their
absence afterwards does not prove they were absent before deletion, and this
snapshot is not a complete undo image. Provider behavior still needs device
qualification.

The shared journal's unique event and mutation IDs prevent UPDATE and DELETE
from replacing one another. Capture refuses a different kind rather than silently
coalescing it. Replaying the same retained ID returns the first immutable intention;
it does not prove the Provider is still in that state. The DELETE kind is separate
from an imported `RemoteChange.Deleted`: a remote tombstone causes a persistent
conflict and never automatically acknowledges the local deletion.

Every import, receipt restoration, acknowledgement, count and requeue barrier
checks all local kinds. A retained DELETE keeps its projected base and suppresses
incoming projection while newer remote or deferred evidence is retained. Schema
10 migration tags existing intentions as UPDATE without changing their payloads,
states, IDs or receipts; it does not invent prior local deletions.

Incoming Provider writes also assert clean, non-deleted event rows before replacing
or deleting them, including recurrence children removed with a master. This
conservatively preserves even an uncaptured or unsupported local edit. Its pending
import remains blocked rather than discarding data; user-facing resolution is future
work, including the final policy for unexpected edits in a read-only mirror.

## Interrupted projection

Before writing a supported upsert, the owner persists a receipt. The
Provider writes its versioned nonce in `SYNC_DATA3` in the same atomic batch as
the event, organizer metadata and reminder/attendee replacements. Supported simple
imports include up to five distinct reminders, an organizer and up to 100
participants with distinct email identities. Moved/cancelled recurrence exceptions
also qualify when changing between timed and all-day display. The original slot
retains the master's date type; timed originals and displays retain second precision.
Already supported recurring masters also qualify, including
rule-based and additional-date-only sets with exclusions. Mutable date lists are
revalidated for bounds, date type and whole-second precision before preparation.
Receipt contents and previous-base collection data are
snapshotted before crossing SQL or Provider suspension boundaries.
Preparation alone does not prove that the write happened. Untracked
projections clear this marker; migration creates no receipts for old rows.

After an interrupted acknowledgement, an assertion-only Provider inspection
requires unique scoped ownership, the exact event identity, revision and nonce.
For a master, both its remote-event identity and its separate series identity must
resolve uniquely to the same owned row, with both assertions in the proof batch.
Current rules and linked exceptions are not substituted for historical evidence.
Proof/restoration does not rewrite linked exceptions. Ordinary master changes
still requeue those children for separate projection through the existing dependency path.
The inspection qualifies a historical write even if the row is now dirty or locally deleted;
it never changes content or clears either flag. In one private transaction, the
qualified receipt restores its projected base only if the previous base still
matches, consumes only the exact pending payload, and removes the receipt. Newer
remote content or a tombstone stays pending and separate from that recovered base.
Explicit capture attempts this recovery before reading the Android edit.
Changed Android reminders, organizer fields and participant rows are preserved by
historical inspection and restoration; this does not qualify their editing,
invitation/response dispatch or upload. Those edits remain outside the
simple local-edit capture profile. Recurrence editing remains unavailable.
Existing receipts retain protocol 1 semantics. Downgrading a receipt beyond an
older binary's event profile
is not qualified; the older profile refuses it conservatively.

A missing, changed or ambiguous marker cannot qualify recovery. A preparation
that was never applied can still permit capture against a distinct older revision.
If different prepared content reuses that revision, capture refuses to guess its
base. An unresolved receipt for different pending content blocks projection
instead of being replaced or replayed as stale data. Adapter, generation, lease
and permission gates apply to preparation and recovery as well as normal import.

## Retained change metadata index

The explicit internal `ImportCoordinator.retainedLocalChanges` entry point
discovers already retained UPDATE/DELETE intentions without reading captured
contents or protected preparations. A page contains only the exact event/mutation
references, kind and retained PAUSED/CONFLICT state. Discovery does not validate
the captured content, adapter compatibility or current Provider/server state;
route each reference through its separate exact historical inspection.

Each query binds the account/calendar/generation, accepts 1–50 entries and uses
a protocol-1 position in the local index. SQLite BINARY event ordering and a
strict lower boundary avoid repeating the previous page's boundary. The first
page has no lower boundary. One extra row determines continuation, and every
selected row, including that extra row, must have valid metadata. SQL checks
TEXT storage classes and byte bounds before loading identities; the domain
requires valid UTF-8 identities of at most 4096 bytes without control characters.
Malformed metadata refuses the whole page without consuming anything or exposing
its value/cause. Corruption beyond the selected rows remains unknown until read.

This is a current historical index, not a frozen multi-page snapshot. State can
advance between pages, and an insertion behind the cursor requires restarting
from the first page. A terminal page only means that query saw no continuation;
it does not prove there are no later changes. Cursors cannot cross scopes,
generations or protocol versions. They are in-memory local positions, unrelated
to Proton's import/change cursors.

Every page repeats account, generation, selection-policy and permission gates
before/after SQL. Known paused/unavailable calendars remain historically visible;
session or permission loss discards the result. Cancellation propagates and the
owner closes its leases. Returned lists are frozen and diagnostics are redacted.
No schema, UI/worker caller, automatic capture, resolution or dispatch is added.

## Historical DELETE inspection

Explicit internal `ImportCoordinator.inspectLocalDelete` reads the exact retained
scope/event/mutation with kind DELETE. One private SQL transaction observes its
strictly validated owner, projected reference, imported record, checkpoint format
and deferred marker, without changing any table. An incompatible retained UPDATE
preparation causes refusal based on row presence; its protected payload is not read.
Known paused/unavailable calendars remain inspectable with the current account and
calendar permissions. Generation and selection policy are frozen, and session/
permission checks repeat before returning. Cancellation propagates and leases close.

UPDATE and DELETE share the same imported-history comparison: unknown/incompatible
formats precede deferred evidence, then absence, equality, change and an explicit
remote tombstone. Absence never implies deletion. A remote tombstone does not
acknowledge the local DELETE or remove its projected base; recorded conflict stays
separate even if import returns to that base. Supported edits observed before the
local deletion remain part of the original immutable content snapshot.

The result is historical SQL evidence, not current Provider/server state or a
complete undo image. It contains no preparation payload or dispatch authority.
There is no UI/worker caller, resolution, import release or service request.

## Recovery limits

Provider snapshot and private database commit are not one atomic transaction.
An edit made after snapshot qualification remains dirty and protected in Android;
it does not silently replace the immutable first capture. Automatic observation,
additional local edits, automatic CREATE capture, conflict controls and conditional
Proton UPDATE/DELETE remain separate work.
Explicit internal [local CREATE capture](LOCAL_CREATE_CAPTURE.md) has its own
one-shot identity reservation and permanently paused journal, without dispatch.

Nonce-backed recovery covers simple upserts, supported masters and the exception
subset above with absent, empty or supported event details. Provider DELETE projections and
legacy rows without receipts retain their conservative
recovery limits. Neither cache presence, a revision alone nor a numeric row ID
invents an ACK. The two stores still do not share a transaction, and controlled
reopening tests do not establish recovery from physical power loss.
Parent identity and original-date type are asserted with an exception write. The
existing parent rule lookup occurs before the batch; this does not qualify every
concurrent parent-rule change. Historical proof is not a present series snapshot.

See [validation](VALIDATION.md) for the distinct domain, SQLite, controlled-owner
and real Provider fixtures. They do not establish Samsung editing UI behavior or
general Proton UPDATE acceptance. The separate
[internal title submission experiment](TITLE_UPDATE_SUBMISSION.md) does not
acknowledge or consume these captured native intentions.

# Internal local UPDATE capture

The internal `ImportCoordinator.captureLocalEdit` entry point retains one local
change to an already imported simple event. It does not enable editable calendars,
observe Provider changes automatically, send a mutation, or acknowledge an Android
edit. The production mirror remains read-only.

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

The Android reader requires one bridge-owned calendar and one event with the
exact scoped remote identity and base revision marker, `DIRTY=1` and `DELETED=0`.
An assertion-only Provider batch checks the complete observed row, ownership and
absence of reminder, attendee and extended-property rows. The reader neither
changes the event nor clears its dirty flag. Missing, ambiguous, unsupported or
concurrently changed rows remain untouched.

## Durable state and imports

Import schema 8 separates three facts:

- `records`: latest supported remote content or authoritative tombstone;
- `projection_bases`: last upsert successfully acknowledged after projection;
- `local_edits`: immutable scoped ID, adapter format/version, original base and
  captured supported content, with state `PAUSED` or `CONFLICT`.

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

Incoming Provider writes also assert clean, non-deleted event rows before replacing
or deleting them, including recurrence children removed with a master. This
conservatively preserves even an uncaptured or unsupported local edit. Its pending
import remains blocked rather than discarding data; user-facing resolution is future
work, including the final policy for unexpected edits in a read-only mirror.

## Recovery limits

Provider snapshot and private database commit are not one atomic transaction.
An edit made after snapshot qualification remains dirty and protected in Android;
it does not silently replace the immutable first capture. Automatic observation,
additional local edits, local CREATE/DELETE capture, conflict controls and conditional
Proton UPDATE/DELETE remain separate work.

A crash after successful Provider projection but before private acknowledgement
can leave a newer Android marker without its matching durable baseline. Capture
refuses that mismatch and preserves the local row. Recovery of this gap needs
separate qualification; neither cache presence nor a numeric row ID invents an ACK.

See [validation](VALIDATION.md) for the distinct domain, SQLite, controlled-owner
and real Provider fixtures. They do not establish Samsung editing UI behavior or
live Proton UPDATE acceptance.

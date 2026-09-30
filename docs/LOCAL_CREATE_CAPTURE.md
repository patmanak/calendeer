# Internal simple local CREATE capture

`ImportCoordinator.captureLocalCreate` explicitly retains a simple new Android
event offline. The application owns its capture journal, separate from the import
database and experimental remote CREATE admission journal. No observer, editable
calendar mode, submission or remote acknowledgement is enabled. A separate explicit
read-only preparation entry point can retain a protected, non-dispatchable payload.

## Identity and supported content

A canonical random local UUID identifies the candidate independently of a remote
event ID, mutation ID or Android row locator. The caller supplies the UUID and
mutation ID for an explicit capture; automatic discovery remains future work.
Scope includes account, calendar and generation. Adapter format/version and marker
protocol are retained with a frozen second-precision timestamp.

Initial capture requires one bridge-owned calendar and the selected present row,
`DIRTY=1`, `DELETED=0`, and null `_SYNC_ID` and `SYNC_DATA1` through `SYNC_DATA10`.
Imported rows and rows carrying foreign synchronization metadata cannot become
new creations. Title, description, location and supported time are retained.
Timed events require an explicit consistent timezone and second precision;
all-day dates use UTC with an exclusive end. Color overrides, recurrence,
organizer/participant fields, reminders, extended properties, custom app data and
unsupported flags are refused. This newly created content has an explicitly
known-empty details profile; no unknown remote fields are inferred to be empty.
Proton preparation uses a dedicated known-empty format. This is distinct from
acceptance by the experimental submission adapter.

## One-shot claim and first committed intention

The backup-excluded local journal first commits an immutable reservation.
Scoped UUID and mutation ID are independently unique. Only the invocation that
inserted it may attempt an identity stamp. Reserving does not capture content,
authorize upload or establish that a Provider operation happened.

The Provider batch asserts the complete observed row, calendar ownership, absence
of unsupported children and absence of the target marker throughout that calendar,
including dirty and deleted rows. It then changes only `SYNC_DATA5` to a scoped,
versioned digest of the local identity. It preserves dirty/deleted flags and does
not assign a remote identity or revision. No other event content is written.

After a qualified stamp or inspection, the journal commits the first supported
content snapshot. Its state is implicitly and permanently paused. Neither the
reservation nor captured content can be replaced, erased or automatically resumed.
The timestamp is frozen at reservation; retrying with a later timestamp retains
the original value. Replaying retained work returns that first intention without
claiming the Provider still contains it.

Current authentication/account, selection availability, permissions and compatible
checkpoint format gate capture. The import owner serializes capture with imports
and explicit experimental writes. Lease and cancellation checks also apply at the
journal's durable commit. Read-only imports have no remote identity with which to
replace a new local row; no remote association is inferred from matching content.

## Interrupted capture

Every existing reservation without captured content permits marker inspection
only. Its original or supplied Android row ID is ignored. The unique marker must
still identify a supported, dirty, non-deleted row in the owned scoped calendar.
Missing, duplicated, foreign, deleted or unsupported evidence remains unqualified.
Inspection never stamps, rewrites content, clears flags or falls back to a row ID.

An interruption after reservation but before stamping sacrifices that identity's
automatic availability, even if stamping never started. A purged row cannot cause
the same identity to be rebound when Android recycles its numeric ID. After stamping
but before the first content commit, inspection can retain currently observable
content; it cannot reconstruct a lost, uncommitted snapshot. Later committed work
remains immutable even if the row changes or disappears.

Provider stamping and journal capture do not share a transaction. The inspection
and later content commit also remain separate. Selection and checkpoint checks
in the import database are separate from the capture journal's commit; the owner
mutex serializes production operations, but does not provide cross-store atomicity
against an external helper changing import state. Controlled reopen fixtures do not
establish physical power-loss recovery or simultaneous Android processes. Explicit
resolution of unused reservations, subsequent edits/deletions, incoming remote
association, user-facing capture and outgoing reconciliation remain separate work.

See [validation](VALIDATION.md) for domain, SQLite, controlled-owner and Provider
checks. Source/JVM evidence does not qualify a system calendar application's UI or
the Proton service.

## Protected read-only preparation

`prepareLocalCreate` requires the exact committed intention, current account,
permissions, available selection and compatible import checkpoint. The preparation
port declares its known-empty format locally, without network or cryptographic
work. Missing capability is unsupported; a changed format cannot silently reuse
or replace an old protected payload.

Marker inspection must still qualify the intention's exact current content. A
fresh preparation receives the frozen mutation ID, timestamp and known-empty
content. It may refresh account/calendar context using reads and use Proton crypto
and Keystore protection. After that await, the owner rechecks admission, selection,
checkpoint, declared/response format, scope and marker/content before committing.
No Provider content or flags are written. A failure preserves the original intention.

Journal v2 adds a nullable protected preparation with its own adapter format/version.
The additive v1 migration preserves every reservation and captured payload without
inventing preparations. The first preparation is immutable and must match the
exact committed intention. Incomplete preparation triplets are refused. It remains
permanently paused and never enters the experimental dispatch journal.

A cached preparation still requires current admission and marker/content checks,
but never calls the preparer or regenerates its encrypted payload. It can therefore
be reused offline with a locally retained session. A changed/deleted event, ambiguous
marker or incompatible format leaves the historical payload retained and unqualified.
This is a point-in-time qualification, without atomicity across the Provider,
import state and journal or a guarantee about a later edit.

The dedicated `proton-calendar-local-create-preparation` v2 format seals
`knownEmptyDetails=true`, the policy `EXPLICIT_EMPTY`, and `Notifications: []`
inside its event fragment. Personal notifications are a JSON sibling of the
shared parts; they do not require a new signed/encrypted iCalendar part. The
shared partition and cryptography remain unchanged. Unexpected non-empty, null
or malformed personal data from the preparer is refused before sealing rather
than silently replaced. An already explicitly empty list is compatible.

Historical preparation v1 retains its `EXPLICIT_EMPTY_REQUIRED` obligation and
original protected payload. A v2 adapter cannot reuse or replace it automatically;
it remains retained and unqualified. The journal schema is still v2. This format
change is distinct from a database migration and requires future explicit resolution.

Shared iCalendar text without `VALARM` does not disable calendar-default reminders.
The new fragment prepares the override but does not claim that a server accepted
it. The envelope contains no submission request. Submission v1 refuses both local
preparation versions before opening them, and its preparation entry point still
refuses any non-null details before session/network/crypto work. Legacy unknown-
profile preparation remains unchanged. Before future dispatch qualification,
authenticated readback must have `Notifications` present, non-null and a genuinely
empty JSON array in addition to signature, UID and content checks. Empty mapped
Android reminders alone are insufficient: defaults may be empty and personal
EMAIL notifications are intentionally absent from that Android model.

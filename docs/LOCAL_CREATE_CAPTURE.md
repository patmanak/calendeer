# Internal simple local CREATE capture

`ImportCoordinator.captureLocalCreate` explicitly retains a simple new Android
event offline. The application owns its capture journal, separate from the import
database and experimental remote CREATE admission journal. No observer, editable
calendar mode, preparation, submission or remote acknowledgement is enabled.

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
Acceptance by the existing Proton preparation adapter is a separate capability.

## One-shot claim and first committed intention

The backup-excluded local journal v1 first commits an immutable reservation.
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

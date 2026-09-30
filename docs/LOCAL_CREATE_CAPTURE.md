# Internal simple local CREATE capture

`ImportCoordinator.captureLocalCreate` explicitly retains a simple new Android
event offline. The application owns its capture journal, separate from the import
database and experimental remote CREATE admission journal. Capture performs no
dispatch or remote acknowledgement; no observer or editable calendar mode is enabled.
A separate explicit
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
it remains retained and unqualified. These preparation columns were introduced in
journal schema v2 and are preserved by schema v3. This adapter format change is
distinct from a database migration and requires future explicit resolution.

Shared iCalendar text without `VALARM` does not disable calendar-default reminders.
The new fragment prepares the override but does not claim that a server accepted
it. The envelope contains no submission request. Both experimental submission
profiles refuse the two local preparation versions before opening them. Exactly
empty details can be separately prepared as submission v2; this neither promotes
a captured intention nor converts its historical protected fragment. Legacy unknown-
profile preparation remains unchanged. For explicit-empty dispatch qualification,
authenticated readback must have `Notifications` present, non-null and a genuinely
empty JSON array in addition to signature, UID and content checks. Empty mapped
Android reminders alone are insufficient: defaults may be empty and personal
EMAIL notifications are intentionally absent from that Android model.

## Separate captured submission staging

`ImportCoordinator.stageLocalCreateSubmission` explicitly links a captured
intention to a separately prepared opaque submission. It performs no dispatch,
remote acknowledgement, import association or Provider write. No observer,
worker or UI calls it automatically. It returns only a closed qualification result.
The adapter's local `knownEmptySubmissionFormat` declaration is required; Proton
declares submission v2. Another adapter may declare its own compatible format
without introducing protocol types into the owner or captured domain models.

A canonical attempt UUID is distinct from the captured mutation and local event
identities. The draft uses that new correlation with the exact captured content
and original second-precision timestamp. The local journal reserves this link
before calling `prepareSubmission`, using a separate table in schema v3. The link
is unique per captured identity and per scoped attempt; aliases with other
captured identities are refused. The additive migrations from v1 and v2 preserve
raw captured content and any historical preparation without inventing submission
work. Protected local preparations v1/v2 are never opened, promoted or converted.

Only the inserting invocation receives a volatile ticket bound to that SQLite
helper and reservation. It is consumed once before payload retention, including
when a transaction fails or is cancelled. The first opaque payload is immutable
and remains distinct from `CreateAttemptRecord`. A reopened reservation without
payload returns `UNRESOLVED`; it never yields another ticket, calls the adapter
again or adopts a new attempt identity. Resolution of such gaps is future work.
A completed payload can be reused without new context reads or crypto through
the preparation port, after qualification of current local session and evidence.

Both fresh staging and reuse require the exact capture, current account/session,
permissions, available enabled selection, compatible import checkpoint and stable
declared submission format. Unique marker inspection must still match the frozen
content. These gates are rechecked after preparation and Provider inspection
before retaining or returning work. Changed, missing, deleted, ambiguous or
unsupported Provider data leaves historical work retained and unqualified. The
owner serializes this operation with import and account lifecycle operations.
Provider observation, import state and the local journal are separate operations;
this does not establish atomicity against another process or an external editor.

The staging operation does not insert anything into the dispatch journal or
qualify an existing admission. Freshly completed staging can retain a volatile
dispatch lease in the same owner, as described below. Cached payloads and
interrupted reservations never grant that lease.

## Explicit captured dispatch

`ImportCoordinator.submitLocalCreate` is a separate internal entry point. It
requires the exact captured intention and separately sealed submission, with a
fresh lease from successful staging in this owner. The lease binds the full
captured dispatch identity to the current session generation and access-policy
revision. Account changes, observed permission loss or calendar disabling
invalidate it, even if access returns. Reopening an owner cannot recover it from
the cache. Failed or cancelled durable admission consumes it permanently.
This conservative policy sacrifices automatic availability; explicit resolution
of interrupted or invalidated work remains future work.

Dispatch journal schema v5 atomically retains the immutable PREPARED attempt
and its captured link. The link includes capture/local identities, capture and
submission formats, frozen timestamp, ciphertext identity and a versioned
content fingerprint. The fingerprint uses length-prefixed strict UTF-8 fields
and exact time/zone representations, without text normalization. It is a private
equality witness, not encryption. Additive migrations preserve older attempts,
evidence, associations and pauses without inventing captured links. Existing
generic work is never adopted, even if its payload matches. Generic staging,
dispatch and association refuse linked work, including a corrupt link.

Before admission, the owner rechecks current local permissions, enabled available
selection, import format, immutable capture/submission and a unique owned Provider
marker with exactly matching supported content. The guarded journal repeats these
checks after the adapter's fresh account, rights and key reads. This last guard
does not call authentication APIs while the adapter holds its authentication mutex.
The lease is consumed before the independent SQLite claim; that claim commits
OUTCOME_UNKNOWN before one request can be admitted. Linked PREPARED work inherits
the existing permanent restart/session/access pause policy.

The captured port fails closed unless its sealed request is known-empty and its
entire draft equals the expected frozen draft. Proton submission v2 then uses
the existing one-shot transport and authenticated readback. After signatures,
UID, raw empty notifications and supported content have qualified, the adapter
returns the expected domain representation while retaining the observed remote
ID and revision. Only readback has the existing CRLF/LF and UTC-alias normalization;
sealed-draft comparison and the captured fingerprint remain exact. Historical
local preparation envelopes and legacy unknown-details submissions are refused.

Existing UNKNOWN work permits explicit captured inspection only. It never
prepares, claims or sends again, even if the original row changed or disappeared.
Current access and the complete immutable journal link are still required. A
verified observation does not acknowledge the Android row, clear dirty/deleted
flags, bind a remote identity or inject a projection. No observer, worker or UI
invokes this path; writable mirrors remain disabled.

Provider observation, import state and dispatch admission are separate operations.
An external editor can change a row after its last observation; this is not a
cross-store transaction or a guarantee of the latest content at send time.
Duplicate-free association with the original row and conflict handling after
admission must precede a live captured-upload journey or editable-mode rollout.

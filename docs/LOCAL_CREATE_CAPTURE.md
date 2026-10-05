# Internal simple local CREATE capture

`ImportCoordinator.captureLocalCreate` explicitly retains a simple new Android
event offline. The application owns its capture journal, separate from the import
database and experimental remote CREATE admission journal. Capture itself performs no
dispatch or remote acknowledgement. The opt-in system creation controller below
orchestrates the separate qualified submission path.
A separate explicit
read-only preparation entry point can retain a protected, non-dispatchable payload.

## Identity and supported content

A canonical random local UUID identifies the candidate independently of a remote
event ID, mutation ID or Android row locator. The caller supplies the UUID and
mutation ID for an explicit capture; automatic discovery reserves these identities
durably before invoking that same boundary.
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

An additional measured native-editor profile accepts the bridge's own opaque
local organizer label, with the exact self-organizer/default guest flags and no
participants, reminders or extended properties. This local label is not an
external organizer or invitation. A missing end timezone uses the explicit start
timezone only in this profile; an explicitly different end timezone is refused.
Capture and acknowledgement assert and preserve the original native fields.
The [AOSP editor helper](https://android.googlesource.com/platform/packages/apps/Calendar.git/+/7b051c6e9ef7922f08d9ebf664ac3087d5fa0552/src/com/android/calendar/event/EditEventHelper.java)
sets attendee-data capability on new events and writes the start timezone without
an end timezone. The rest of this profile comes from physical editor observation.
This narrow admission does not loosen generic imported-event capture.

## Opt-in system creation

Each accessible imported calendar has an experimental creation switch, disabled
by default. It enables Android's editor access for that mirror; event entry stays
in the system calendar client. Disabling the mode, pausing the import or losing
calendar access closes editing on the existing mirror. Retained mirrors are also
closed after sign-out or a cold restart without the owning authenticated account.
Closing access never creates a calendar or deletes event data.

An event-table observer schedules durable local and network WorkManager jobs.
Local discovery reads synchronization metadata, consumes at most 25 candidates
per scope and persists its continuation. A separate backup-excluded journal
reserves the local identity, capture identity, attempt and timestamp before stamp.
Local work captures without networking. Network work prepares the exact payload,
qualifies dispatch, verifies Proton readback and completes attachment, baseline,
reconciliation, acknowledgement and handoff on the original Android row.
The network path reuses the existing Proton adapter and official cryptography.
The app adds no event editor, event reminder or synchronization notification.

A restart may requalify the same captured PREPARED submission under the current
mode, account, permissions, calendar access and unchanged native content. It never
regenerates the payload or identities. Once the journal admits a send, its outcome
is UNKNOWN until inspection verifies it; automatic processing cannot admit another
send. Disable/re-enable invalidates the earlier dispatch lease, including at the
final journal claim. Unsupported content and subsequent edits stay native and
dirty; pending work remains visible even with the creation switch disabled.

Imported changes resolve opaque Provider hashes through canonical acknowledged
event IDs, reading metadata in pages of 100 without decoding payloads. Unknown or
ambiguous keys cannot produce an intention. This resolution uses bounded memory
but can traverse all projected IDs for a native page; a persistent reverse index
is still needed for large catalogs. The native query is not a guaranteed database
row limit, even though cursor consumption and job processing are paginated.

UPDATE and DELETE captures remain held locally without upload. Automatic CREATE
supports only the qualified simple profiles above. Per-event colors, recurrence,
participants, reminders and interactive conflict/uncertainty resolution remain
outside this experimental capability. General bidirectional release is not yet
qualified, and additional system clients/OEMs require their own editor journeys.

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
verified observation from this dispatch path does not acknowledge the Android row,
clear dirty/deleted flags, bind a remote identity or inject a projection. No observer, worker or UI
invokes this path; writable mirrors remain disabled.

Provider observation, import state and dispatch admission are separate operations.
An external editor can change a row after its last observation; this is not a
cross-store transaction or a guarantee of the latest content at send time.
Duplicate-free association with the original row and conflict handling after
admission must precede a live captured-upload journey or editable-mode rollout.

## Durable incoming projection hold

An admitted captured attempt can have reached the server before its remote event
ID is known. Matching content or an Android row ID cannot identify that result.
The import owner therefore reads the durable dispatch journal before projection
and recovery: captured UNKNOWN work without a qualified binding holds all event
projections in that scope. A valid PREPARED attempt has never admitted a request
and does not hold import. Detectable damaged parent/link/proof rows fail closed;
an unreadable journal never grants projection permission.

`bindLocalCreateObservation` explicitly inspects the exact UNKNOWN submission,
qualifies the whole frozen draft and observation, and retains its first remote
ID and verified revision. It rechecks current account/session, permissions,
selection, import format and immutable capture/submission after the reads.
It does not inspect or acknowledge the original Provider row. A changed, deleted
or missing row still needs reconciliation; its absence cannot justify recreating
it through ordinary import. An already retained exact binding can resume with
no submission port and no network or crypto preparation.

Dispatch journal v6 adds an immutable captured binding with a full snapshot of
the capture/submission link. The proof must still match the current parent and
link; valid-looking identity drift cannot silently rebind it. Scoped event
uniqueness covers captured and generic associations in both directions. Additive
migration preserves earlier work, evidence, pauses and associations without
inventing bindings. Raw captured links or proofs, including orphaned proofs,
remain excluded from the generic paths.

Once every UNKNOWN capture in the scope has an exact remote binding with the
current import format, only their remote event IDs remain held. A changed adapter
format keeps the entire scope held until explicit reconciliation; opaque event
IDs cannot be reused as if they belonged to a replacement adapter. Incoming updates
and deletions for those IDs stay
pending; the binding neither injects creation content into the import cache nor
clears its queue, receipts or acknowledged baselines. Other events can project.
Runnable work is filtered before the batch limit, so a held prefix cannot starve
later events. Reads, page receipt and offline processing may continue. Held work
alone does not request a WorkManager continuation or backoff loop. The local
CREATE_PENDING state explains why Android updates are retained.

The owner checks the hold before projection receipt recovery, including recovery
used for local-edit capture, and before preparation/application/acknowledgement.
These checks share its process mutex with explicit captured writes. There is no
transaction across the import journal, dispatch journal and Provider, and external
process races remain unqualified. The hold currently has no automatic release.
Baseline acknowledgement and conflict resolution are still required before a
live captured upload or release of held projections.

## Original-row metadata attachment

`attachLocalCreateBinding` is an explicit internal operation using a retained
qualified binding and the exact frozen submission. It prepares a durable receipt
before calling the Android Provider, checks current account/session, selection,
permissions, checkpoint and adapter format, and rechecks them after the call.
It uses no submission port, network request or crypto preparation.

Dispatch journal v7 adds the first immutable attachment nonce and a complete
snapshot of the binding. Replay reuses that nonce. Additive migration preserves
earlier work and does not invent attachment receipts. Damaged or orphaned receipt
rows keep the scope held and remain excluded from generic paths.

The Provider finds the original through its unique scoped local marker, never
through a historical row locator. An atomic batch asserts calendar ownership,
local-marker uniqueness and absence of a competing remote identity before
assigning only the remote ID, owner/revision metadata and attachment receipt.
It keeps the local marker, content, children, dirty and deleted flags. A later
title change or reminder therefore stays intact. Initial tombstones, missing or
ambiguous markers, foreign metadata and already projected remote identities are
left unresolved; no row is inserted, merged or deleted to force association.

Receipt inspection reasserts the exact local/remote identity, owner, revision and
full binding/nonce marker. The marker uses strict UTF-8 encoding to refuse invalid
identity text rather than collapsing distinct inputs. Inspection is a historical
metadata-write proof: a later edit or tombstone can preserve it without proving
current content or consuming the local change. An uncertain Provider response
resumes by inspecting the same durable receipt before any repeated write.

Successful attachment does not acknowledge the Android row, publish an imported
baseline, consume pending work or release the event hold. No worker, observer or
UI automatically invokes it. Reconciling the verified remote baseline with the
current local intention and acknowledging the exact received work remains the
next prerequisite for release and an editable mirror. The mutex and Provider
batch do not form a transaction across journals or simultaneous processes.

## Verified sent baseline retention

`retainLocalCreateBaseline` uses the frozen submission, retained binding and
attachment receipt to retain the first verified sent content. It only inspects
the historical Provider receipt: it does not prepare, attach, fetch or submit.
Current access, selection, checkpoint, format and complete source identities are
requalified around that inspection and the local commit.

Import journal v11 retains immutable capture/attachment provenance together with
the sent baseline. A single transaction creates both and acknowledges only an
exact whole incoming cache payload. It never invents a remote cache row if none
has arrived. A different revision or content, an incoming deletion, deferred work
or a later local mutation cannot be consumed by that comparison. Opaque revisions
provide no ordering, and equal revisions do not prove equal content.

An initial generic baseline, unresolved projection receipt or local mutation
blocks adoption, even if a generic baseline happens to match. Replay must retain
the exact provenance and current baseline; it cannot replace a damaged proof or
rewind a baseline that has advanced. A stored provenance read is historical data,
not authentication or permission to release projection. The owner compares it
against the authoritative captured journals and the qualified Provider receipt.

The original dirty/deleted flags, current content and child rows remain untouched.
The durable captured hold remains in force. Preserving and resolving the current
local intention, then qualifying original-row acknowledgement and hold release,
are still required before an editable mirror or live captured-upload rollout.

## Current local intention after attachment

`reconcileLocalCreate` is a separate explicit offline operation. It reconstructs
the sent reference from the frozen submission and retained binding/receipt, then
requires identical import provenance. A dedicated Provider snapshot asserts the
complete attachment metadata, unique local and remote identities, ownership,
dirty/deleted flags, supported fields and absence of child data in one batch.
It changes no Android data. A present supported tombstone preserves any edits
made before deletion; a missing row never establishes deletion. Reminders,
recurrence, custom colors and other unsupported fields remain intact and block
this operation rather than becoming a partial edit.

A supported change retains one immutable UPDATE or DELETE intention against the
sent reference in the existing local journal. Its transaction preserves incoming
records and raw pending flags, baselines and receipts. Different or missing
cached remote evidence, or deferred work, conservatively marks the intention as
conflicted. A conflict is never cleared by ordinary replay. Subsequent import
can suppress projection through the existing local-intention guard while keeping
the remote payload and conflict; suppression does not acknowledge Android or
resolve the competing versions.

Replay still inspects the current original row. A second edit, a change of kind,
a reverted edit or a different mutation identity cannot replace or hide the first
intention. A fresh snapshot after the local commit can detect a concurrent edit;
the historical intention remains retained and the current row stays dirty.
`UNCHANGED` describes local content observed at that instant, with no retained
local intention. It creates no durable clean proof and cannot authorize a later
acknowledgement. Access and full source identities are requalified around these
operations. This reconciliation does not acknowledge the original row or release
incoming projection. There is no observer, upload or writable UI.

## Conditional original-row acknowledgement

`acknowledgeLocalCreate` is an explicit offline operation for an unchanged
original. It reconstructs the authoritative sent reference and requalifies the
account, selection, permissions, formats and full source journals. An old
`UNCHANGED` result never authorizes this operation.

Import schema v12 adds an immutable acknowledgement reservation. Its transaction
requires exact captured provenance and absence of raw local intentions and
ordinary projection receipts. Only the invocation that inserts its first
`UNKNOWN` reservation can attempt a Provider write. Competing proposals retain
the first nonce. Every local capture entry point checks this durable fence before
new capture or replay; corrupt or unresolved evidence blocks admission.

The Provider requalifies the complete current row, unique local and remote
identities, ownership, supported content, flags and absence of children in one
atomic batch. Only content identical to the sent reference with no tombstone
can be cleaned. The batch changes only `DIRTY` and a dedicated acknowledgement
marker, preserving the local and attachment markers. Unsupported data and edits
made before the batch remain untouched.

Reopening, cancellation, a lost response or failed private commit only permits
inspection of the same nonce. An exact scoped marker proves the historical batch
even after a new edit, reminder or tombstone; inspection never cleans those newer
changes. Retaining this proof promotes the reservation to `PROVEN` without
consuming incoming records, deferred evidence, pending flags or the captured hold.
Replay of an already proven acknowledgement still inspects Android and preserves
any later local intention. `ACKNOWLEDGED` means historical proof, not currently
clean content or a current remote version.

After proven acknowledgement, reconciliation accepts only that exact marker and
can retain a later supported UPDATE/DELETE against the original sent reference.
The snapshot carries its acknowledgement provenance, which must match the durable
record in the capture transaction; a pre-acknowledgement snapshot cannot be reused
after promotion to proven state.
Missing, ambiguous or corrupted markers cannot be adopted from a private proven
state. An interruption after reservation but before the Provider batch can leave
`UNKNOWN` permanently blocked without a marker: no automatic retry or new nonce
is allowed. Explicit resolution remains future work. The private transaction and
Provider batch are separate; real power loss and simultaneous processes are not
qualified. Incoming hold release and writable UI remain separate stages.

## Current clean original qualification

`qualifyAcknowledgedLocalCreate` is an explicit offline, assertion-only operation.
It reconstructs the complete submission, dispatch binding, attachment and original
sent reference, and requires the exact durable `PROVEN` acknowledgement. The SQL
source check rejects raw local intentions or ordinary projection receipts, including
malformed records. Account, enabled selection, permissions, adapter formats and
full source identities are checked around the Provider observation. Nothing is
acknowledged, submitted, advanced or durably released by this operation.

`inspectAcknowledgedCreateClean` asserts the exact original content, clean and
non-deleted flags, full scoped markers, unique local/remote identities and absence
of unsupported fields or child rows in one Provider batch containing only assertions.
Even content changed while `DIRTY` remains zero is unqualified. Historical ACK
inspection remains independent and can still establish history after later edits.
`CLEAN` and its witness describe one observation; a subsequent edit is allowed and
the next check must refuse it. This result never grants a future projection lease.

Generic local UPDATE/DELETE captures reject any raw captured baseline or ACK before
replay, including proven, orphaned or damaged provenance. Only the specialized
snapshot can retain a captured local intention. Generic Provider projections and
local captures similarly refuse local, attachment or ACK markers; null-marker
assertions in their batches protect concurrent attachment. Recurrence parents and
descendants obey the same barrier. The owner's captured hold continues to exclude
ordinary import preparation, receipt recovery and acknowledgement. Transferring
that hold requires a separate durable reference and specialized projection path.

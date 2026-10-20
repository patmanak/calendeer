# Architecture and delivery boundaries

## Decisions for the initial implementation

Calendar Bridge is an Android synchronization utility, with no event editor.
The build uses Kotlin, Java 17, Gradle 9.4.1 and AGP 9.2.1; Android modules use
AGP's built-in Kotlin support and Compose for the small configuration UI.
These pinned versions match the inspected Contako integration baseline; they
are not a claim that Calendar's dependencies have already been qualified.

The initial compatibility baseline is Android 12/API 31, target/compile API 36.
The development application identity is `com.patmanak.proton.andcalendarbridge`.
The application ID is selected for this project; the Android compatibility
baseline remains provisional. No release has been published.

## Modules

- `sync`: plain Kotlin/JVM contracts and orchestration. Account/calendar-scoped
  identifiers, access policy, page checkpoints, queued changes and recovery
  behavior have no Android or Proton SDK dependencies.
- `app`: Android configuration UI, language/theme preferences and eventual
  Calendar Provider, durable storage and background-execution adapters.
- `auth`: extracted application-facing login contract and state management from
  Contako, kept separate from contact synchronization and Proton SDK types.
- `proton-auth`: Android Proton Core binding, SRP login, protected account/session
  persistence, user-key unlock and origin-bound hosted human verification.
  It implements the `auth` and metadata catalog ports; an authenticated identity does not imply Calendar
  keys, membership or synchronization readiness.

Domain contracts own behavior. The future Proton adapter owns SDK types, opaque
payloads/revisions, keys and versioned continuation state. The Android adapter
owns provider rows and permissions. Neither representation is the domain model.
Prefer reusing proven upstream behavior to reimplementing protocol or cryptography.

## Authentication lifecycle

The application owns one authentication controller and one serialized Proton port.
Activity recreation replaces the presentation without restarting login or signing
out. Secrets in input fields are never saved as activity state and are cleared on
submission, backgrounding and disposal. A pending second-factor password is held
only in the adapter's bounded memory lifetime; incomplete sessions cannot become
authenticated by restoration alone.

Proton Core handles SRP and server-proof validation. Session tokens and derived
passphrases use its Android Keystore-backed protection, with an explicit gate
rejecting a plaintext fallback. The app excludes its data from backup. Network and
storage work run off the UI thread; the configuration UI never receives tokens,
private keys, hosted-challenge URLs or verification solutions.

Hosted verification is presented only while Proton requests it. The maintained
WebView bridge enforces its HTTPS origin and frame policy; unknown or unsupported
verification paths fail closed. Account recovery, security-key-only login and
account security changes are outside this authentication slice. User-facing
connection state distinguishes authenticated identity from calendar availability.

## Calendar metadata discovery

The application-owned `CalendarCatalogController` starts after authentication and
survives activity recreation. Its project-owned port returns a complete metadata
snapshot or a closed failure, never a successful partial result. The UI shows names
and colors, provides manual refresh and distinguishes loading, errors and empty
results. Transient failures retain the previous in-memory snapshot; sign-out clears
it and invalidates delayed responses. Retry delays use a monotonic clock.

The Proton implementation shares the authentication graph, session and mutex.
It checks session identity before and after the read. The upstream WebClients
complete-list query includes own-member metadata without event/key bootstrap.
The response is bounded to one MiB and 1,024 calendars; exceeding either safeguard
fails explicitly. This endpoint uses no invented pagination contract. Event
pagination remains separate. Protocol provenance is in the adapter module.

The import store persists discovered metadata and per-calendar opt-in selection.
The standalone catalog refresh view remains memory-backed; the import controls
restore the last known metadata offline. Names/colors do not prove write permissions.
See [read-only import](READ_ONLY_IMPORT.md) for the connected durable pipeline,
provider identities, scheduling and current supported fields.

`ImportSchema` contains the import database's SQL definitions and explicit version
migrations. `SQLiteOpenHelper` still owns migration transactions; evidence backfill
uses the store's existing capacity check. Runtime admission, locks and commit
checks remain in `ImportStore`. Text, time and deletion owner operations reuse the
coordinator's scoped access guard with their own sending-consent predicate; typed
source checks and completion policies remain separate.

`SystemEditAdmission` shares the identical text/time worker admission predicates
and factual-evidence admission. Its live local readers capture the original epoch,
account and authentication at each invocation, before any worker suspension. A
rejected callback stays closed. Proof recovery requires CREATE access without
reading edit-sending consent; factual evidence survives editing-policy loss but
cannot follow logout or account replacement. Deletion retains its additional
consent latch over the shared proof admission.

`EditSubmissionLease` shares the lifetime and irreversible callback gates of one
text/time submission. The coordinator still captures the original operation and
account-evidence admission before validation and locking; format checks remain
live. Source rejection closes sending without discarding factual response evidence.
Evidence rejection has its own latch, and both channels close when the invocation
ends. The lease adds no session lookup, lock, SQL/native write or submission port;
typed wire validation, submission and durable result checks remain in each gateway.

Text/time source qualification shares three private coordinator functions for
native contents, the surrounding SQL/source rechecks and fresh-ACK role checks.
Every suspended read still uses the caller's original `ScopedEditOwnerAccess`;
ordinary sources cannot adopt a transferred CREATE or bypass incoming holds.
Operation profiles, raw/canonical text comparison, date constraints, preparations,
verified results and acknowledgement journals remain typed. The shared checks do
not write the Provider, claim work or renew access; historical receipts retain
their inspection-only path.

`LocalChangeCapture` retains imported-event UPDATE/DELETE snapshots, sharing
selection/session checks and admission setup/cleanup. The coordinator retains the
mutex, original epoch and distinct initial gates, plus incoming hold/baseline
qualification. Native snapshots and owner checks stay typed. `beforeNewOwner`
still runs between the qualified native snapshot and first SQL retention, with
access rechecked afterward; retained owners do not trigger that notification.
Capture never writes/acknowledges native rows or submits an outgoing request.

`LocalChangeHistory` groups retained-change listing, local edit/delete inspection
and conflict-archive reads. The coordinator retains the mutex and original
generation/policy capture; the reader shares account/access checks and admission
setup/cleanup. Known paused or unavailable selections remain readable. The typed
results and archive-specific cancellation/error rules stay distinct; reading
history cannot acknowledge native rows or renew sending authority.

`CapturedCreateRestoration` handles remote identity binding, original-row attachment,
baseline retention, acknowledgement, clean inspection and later local-change
reconciliation. `ImportCoordinator` still owns the mutex, lifecycle initialization
and original generation/policy capture. The collaborator reuses those fixed checks
around suspensions, reads the current adapter format and clears each operation's
admission callbacks. It cannot submit CREATE or release the incoming hold; only a
fresh ACK reservation permits a native acknowledgement write.

`LocalTitleOwnerQualification` isolates the exact current title owner, retained
preparation and native-source checks shared by wire preparation, retention and
submission. The coordinator retains the mutex, captures generation/policy and
supplies the original admission predicates. Fresh account observation remains
separate from the cached account check used inside guarded submission, avoiding
authentication lock re-entry. Both source paths share the released-handoff match
and title-only profile; source provenance and pending projection checks retain
their original order. This collaborator never claims, sends or acknowledges work.

`LocalTitleCompletion` owns title acknowledgement and durable completion only.
The coordinator retains the recovery loop and mutex, passing the original
generation/policy into this collaborator. Both phases share the same availability
check; historical proof recovery does not acquire title-sending consent or renew
a revoked operation's epoch.

`LocalCreateStaging` groups local CREATE capture and protected preparation,
including durable submission staging. The coordinator retains the mutex, initial
account/permission checks, lifecycle initialization and dispatch-lease registry.
The three phases share admission installation/cleanup without merging their
qualification rules. Only newly retained staging notifies the coordinator to record
the original epoch's send lease; historical reads never do so.

`CapturedCreateHandoff` groups transfer of a proven captured CREATE to incoming
synchronization and reconciliation of subsequent local changes. Both phases share
availability and admission setup/cleanup. The coordinator retains the mutex,
original epoch and distinct consent predicates; source qualification stays shared
with other owner paths. Historical projection recovery only inspects native proof.
An exact waiting-to-settled echo can schedule one offline continuation; neither
phase submits CREATE or grants a new sending lease.

The import run's suspending diagnostic finalization has a private coordinator
method. It preserves the original failure over a secondary refresh failure,
always clears `busy`, and schedules local follow-up only after a successful
authenticated network run. Cleanup remains under the existing mutex.
The run delegates each calendar pass to a private method containing its existing
staging, projection, progress and error handling. An inactive pass still stops the
entire run; retry keeps precedence over continuation across calendars. This keeps
the calendar suspensions outside the run's diagnostic `finally` state machine.

## Synchronization invariants

Incoming pages are committed with their checkpoint and projection intents in a
single local transaction. Android projection is replayable, with stable remote
identity and durable mappings; completion is separate from download progress.
Page absence cannot prove deletion. Overlapping/replayed pages cannot erase
local pending changes. Pagination stops at a bounded per-run page budget.

Outgoing edits are durable before dispatch. Effective write access requires local
editable mode, current remote permission, a supported operation and an explicit
qualified capability on the current adapter. That technical ceiling defaults to
empty and is checked before uncertain-outcome admission; it does not replace
event-specific rights, payload preservation or remote conditional-write checks.
Restoring capabilities cannot requeue blocked work or resend uncertain work. Conflicts,
lost permissions and uncertain upload outcomes remain explicit blocked states;
never blindly replay a create with a lost acknowledgement. A storage generation
and adapter state version prevent accidental reuse across incompatible adapters.

The scheduler is not the queue. Foreground/manual/background triggers share a
serialized engine per account; durable state survives process death. Local-only
processing is distinct from network work. WorkManager and provider registration
will be integrated only with a persistent production repository and usable adapter.

## Versioned recurrence projection model

The pure [projection reference](PROJECTION_REPRESENTATION.md) separates canonical
event data from a direct or bounded finite-date view. Its creation factories own
immutable snapshots. Schema 15 connects explicitly supplied references to receipt
preparation and the applied SQL baseline; complete-reference recovery retains
saved dates after acknowledgement without recalculating with a newer timezone
database. Ordinary imports still prepare historical direct projections, and the
ordinary Android mapper refuses finite metadata. A separate first-insertion port
uses saved finite dates only with atomically absent event/series/child identities;
its historical inspection permits exact SQL recovery without repeating the write.
Finite native markers protect these rows from ordinary writes. Replacement and
automatic finite import remain unavailable.
Receipt protocol 1, canonical event encoding and production recurrence admission
remain unchanged.

## Experimental CREATE association

The experimental simple CREATE path keeps three separate facts: durable admission
(`OUTCOME_UNKNOWN`, never automatically readmitted), an immutable authenticated
remote event/revision observation, and a replaceable historical Android locator.
The association journal is separate from the import database. It validates exact
scoped work and sealed-payload identity transactionally before recording proof.
It cannot acknowledge or erase a pending Provider edit.

Association runs under the import coordinator's existing mutex and current
account/generation leases. The remote observation does not replace the latest
imported content: only a stabilized cached upsert, without pending projection or
deferred work, supplies the revision to check in Android. Provider inspection
requires one owned, clean, non-deleted matching row and rechecks calendar ownership;
persisted numeric row IDs alone never qualify it. A locator is a momentary witness,
not an atomic cross-database guarantee, and must be revalidated on later use.
Missing imports schedule ordinary read-only recovery. A retained remote binding
allows local reassociation without invoking the CREATE port. User-facing capture,
general outgoing queue and complete conflict resolution remain separate delivery work.

The application-owned journal also participates in experimental CREATE lifecycle.
Startup closes its dispatch gate and permanently pauses old PREPARED work before
any explicit staging/submission. The shared import owner serializes those commands
with calendar selection and catalog persistence. It checks the real current account
before and after preparation, not just the authenticated UI flag. Session/policy
changes immediately invalidate local admission; complete catalog disappearance,
calendar disabling and observed permission loss record a pause before later writes.
Failed pause persistence retains the obligation in memory; restart conservatively
pauses all old unadmitted work. SQLite claim checks the exact permanent pause in the
same transaction, including between independent helpers. A pause records its first
cause, not current access, and has no automatic reset on reactivation.

The Proton submission adapter continues to own fresh session/rights/key validation
under its authentication mutex. Its journal claim callback checks only local app
leases, avoiding re-entry into that non-reentrant mutex. Evidence storage for an
already UNKNOWN attempt is independent of the dispatch gate: a late response is
retained even after app admission closes. Inspection with current access never
readmits UNKNOWN work. These safeguards do not provide edit capture, cross-store
atomicity, general outgoing resolution or a new user-facing logout policy.

## Internal simple local capture

Explicit simple local CREATE capture uses a separate backup-excluded journal.
An immutable scoped UUID reservation commits before a single Provider identity
stamp; every restart is marker-only inspection. The first committed supported
snapshot stays paused and cannot authorize the experimental submission journal.
Journal v2 can separately retain a protected read-only preparation against that
exact snapshot. The owner checks current marker/content before and after preparation,
and requires a declared adapter format for cache compatibility. Proton's dedicated
known-empty preparation v2 carries an explicit empty personal-notification JSON
override; older v1 preparations retain their unresolved obligation without automatic
replacement. Neither format nor the journal grants submission admission.
Android row IDs are transient selectors for the first claim only and never durable
domain identities. See [local creation capture](LOCAL_CREATE_CAPTURE.md) for the
protocol and interrupted-claim availability limits.

The import database retains the latest remote record, last acknowledged Provider
upsert and immutable local UPDATE intent separately. Capture and import conflict
changes commit with projection suppression under the same SQLite transaction.
The shared import owner admits explicit capture only with current account/scope,
permissions, enabled availability and compatible checkpoint format. An assertion-only
Provider reader qualifies a supported dirty snapshot without acknowledging it.
The retained intent blocks both direct projection and replay/requeue paths; changed
or deferred remote evidence makes conflict persistent. The generic outgoing port
remains closed. UPDATE and DELETE share unique event/mutation admission in the
private journal but retain separate typed intentions. DELETE requires an observed
dirty tombstone, preserves its supported content and projected base, and never
interprets a missing row or remote deletion notice as local acknowledgement.
See [local capture](LOCAL_EDIT_CAPTURE.md) for supported fields,
legacy-profile treatment and recovery limits. Supported simple upserts persist
an immutable preparation before writing a nonce with the event in an atomic
Provider batch. Inspection of that exact scoped marker can recover a lost private
ACK without overwriting a later local edit. Baseline comparison, exact pending
payload consumption and receipt removal share one private transaction; newer
remote evidence remains separate. Legacy and complex projections have no such
proof, and Provider and private storage still do not share a transaction.

Explicit `inspectLocalEdit` reads an exact captured UPDATE owner, acknowledged
projection base, imported record, checkpoint format, deferred marker and retained
title metadata in one SQL transaction. It leaves every journal and projection
table unchanged. The coordinator freezes generation/policy and rechecks session
and permissions before returning; known paused/unavailable calendars can retain
historical inspection. This is local history, not an atomic observation across
SQL, Provider and server. Format uncertainty and deferred evidence take precedence
over comparison; missing records never imply deletion. Recorded conflict stays
distinct even if the imported record returns to the captured base. Retained title
metadata conveys no protected payload, current qualification or upload authority.
There is no UI/worker caller or automatic resolution.

Separate `inspectLocalDelete` applies the same imported-history policy to an exact
DELETE owner with strict snapshot/version validation. It refuses incompatible
UPDATE preparation presence without loading its payload. Neither a remote tombstone
nor an imported return to the base consumes the intention, clears conflict or
supplies a complete undo image. The same local transaction and session gates apply.

## Delivery slices and acceptance

1. Buildable configuration app: eight languages/system mode and three persistent
   appearance modes, reusing Contako source where appropriate; explicit connection
   unavailability until authentication integration is usable. No fake login or
   sample personal events in the production application.
2. Pure synchronization engine: deterministic adapter contracts, read-only/write
   gating, bounded page import, interrupted/replayed work and preserved local
   intent. JVM tests establish these policies, not Proton interoperability.
3. Login extraction: application-owned contract/state machine and focused tests;
   dependencies, session storage and Calendar bootstrap then qualified as a
   separate connected slice. Do not expose a credential form with no backend.
4. Durable Android storage + Calendar Provider projection + background scheduling:
   restart/race tests, explicit schema, stable identities and device qualification.
5. Live Proton import, then bidirectional creation/updates/deletions with preserved
   unknown fields, conflict and lost-acknowledgement handling. Device/account
   acceptance precedes any claim of working synchronization.

No slice's fake/stub adapter qualifies later live-service behavior. Modules may
compile before all slices are complete; expose limitations accurately in the UI
and [implementation status](STATUS.md).

## Validation and provenance

See [validation](VALIDATION.md) for repeatable commands and acceptance boundaries,
and [Contako source reuse](research/CONTAKO_REUSE.md) for provenance. Personal
operator choices, execution transcripts and temporary planning live outside Git.
Public documents contain reusable behavior and current implementation limits.

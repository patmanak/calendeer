# Internal title UPDATE experiment

The release target remains bidirectional synchronization through Android calendar
clients. This internal experiment qualifies a narrower service boundary: submitting
one prepared title change for a simple personal event. A separate internal path
conditionally acknowledges ordinary imported and transferred CREATE events after their result
is retained. Production workers now recover already durable results offline;
new UPDATE preparation, submission and inspection remain unavailable to workers
and UI controls.

## Supported work and ownership

`CalendarTitleUpdateSubmissionPort` accepts an immutable `LocalEditPreparation`
with its original full base, scope, formats and timestamp. Both versions must have
known-empty event details, no color and no recurrence; only the title may differ.
Description, location, dates and timezone remain identical. Personal partitions,
attendees, notifications and the full original GET context must satisfy the
adapter's closed profile. Unknown or incompatible data refuses preparation.

The Proton adapter reuses the existing title patcher, protected original rows,
session-key reuse and Proton Core signatures/encryption. The separate versioned
submission envelope binds the retained preparation, attempt, member, author and
calendar/key context. Its request follows Proton's same-calendar builder:
`PUT calendar/v1/{calendar}/events/sync`, with one `Events[{ID,Event}]` operation.
Proton JSON and cryptographic objects stay inside `proton-auth`.
See [source attribution](../proton-auth/PROVENANCE.md#experimental-title-only-update-submission).

## Durable admission and recovery

`TitleUpdateAttemptStore` retains an exact immutable preparation and protected
wire envelope. Preparing work grants no right to send it. Dispatch admission is
disabled by default and must be supplied by an explicit caller with current local
account, calendar policy and permission checks.

Before claim, the adapter repeats authenticated preparation of the same intent
and timestamp. It compares the complete original event and current session,
rights and key context. Newly randomized ciphertext does not replace the frozen
wire payload. The journal then compares the complete record in a SQLite transaction
and commits `OUTCOME_UNKNOWN` with `synchronous=FULL` before transport can start.
A failed commit cannot admit a request.

One unresolved attempt owns the remote account/calendar/event across generations.
Changing the UUID, base or generation cannot bypass it. An UNKNOWN caller remains
inspection-only; a forged evidence-bearing PREPARED caller cannot authorize an
request. There is no automatic reset, deletion, resolution or release of the attempt,
including after a verified readback.

The UPDATE transport has a distinct permit from CREATE, a one-shot body and
application/network guards. They refuse payload/tag confusion, re-entry,
redirects and additional exchanges. Cancellation, authentication/network errors,
lost responses or failure to retain evidence never authorize another PUT.
The first available response body is sealed as evidence before interpretation.

UNKNOWN inspection also works when no response was retained, because the original
event ID is known. It repeats current authenticated context checks, reads that
exact event, compares the submitted signed parts and preserved metadata, and
verifies the decrypted complete expected content. The observed server edit-clock
extension has a separate bounded rule: field presence must stay identical,
integers must be nonnegative, and a changed `LastEditTime` must advance monotonically
and equal the current `ModifyTime`. Other metadata remains exactly equal. This
rule describes the measured experimental profile, not a documented server CAS
or a general permission to ignore timestamps. A matching display title or
success response alone is insufficient. The adapter's inspection does not alter
the journal.

## Guarded dispatch and durable claim

The internal `submit(record, journal, admit)` overload is distinct from the explicit
legacy experiment. It accepts only an exact PREPARED record without response evidence
in the current wire format. Unsupported ports deny by default, without calling the
legacy submit method. An UNKNOWN caller or an already UNKNOWN stored record remains
held; neither starts preparation, transport nor implicit inspection. Recovery uses
the separate guarded inspection path.

The adapter freezes the record and narrows the synchronous lease to its exact scope
and wire format. Once denied, that invocation cannot resume when access returns.
Session and lease checks surround protected capsule opening, fresh preparation,
context qualification, claim, transport eligibility and authenticated readback.
Fresh preparation uses a separate guarded overload with no legacy fallback. Its
Proton implementation checks admission and the expected session between the Core
user and address refresh calls, and around calendar metadata, bootstrap,
original-event read, local addresses and cryptographic preparation. These
checkpoints cover application-controlled stages; they do not
intercept every internal Core exchange or cancel an already running stage merely
because the callback subsequently becomes false.

`claim(expected, admit)` is denied by unsupported journals. The SQLite implementation
uses the callback of this invocation directly, without temporarily replacing the
legacy mutable dispatch permission. It rechecks admission after acquiring the IO
lock and immediately before commit, with cancellation checks even when callbacks
return false. The complete PREPARED record and merged event ownership history must
still qualify. Failed admission, corruption or commit failure cannot return a permit.
The guarded admission helper calls only this overload and rechecks access after
claim. A revocation or cancellation after commit leaves durable UNKNOWN with no
transport and never restores PREPARED.

After a transport exchange, the first received response body may be retained as
immutable evidence even if admission or the session has changed. This historical
write grants no readback or retry authority. Cancellation or evidence-storage
failure can leave UNKNOWN without evidence; explicit inspection remains the recovery
path. Further journal reads and service inspection require the same original lease
and session. A failed claim, including a lost race, never triggers implicit inspection.

The authentication facade freezes work before dispatcher/mutex waits, requires its
authenticated runtime and forwards only the guarded overload. It restricts the lease
to the original scope/current format and rejects verification unless every attempt
identity field and the complete expected event content match. Cancellation propagates
after awaited results, including null or Unknown.

The additional `submit(record, journal, admit, qualifySource)` overload also requires
a suspending current-owner observation. Unsupported ports deny without using the
three-argument or legacy overload. Source refusal closes synchronous admission for
that invocation. The adapter checks the source around journal reads, fresh preparation
and context qualification, before claim and after its commit. The final order before
transport is session check, local source qualification, synchronous admission and
cancellation checks, then send. No further awaited session lookup separates that
local observation from transport. After response evidence retention, source changes
prevent further inspection or a verified return without erasing the first evidence.
During guarded readback, the source is checked before and after inspection; the
existing session/access checkpoints within inspection are unchanged.

`ImportCoordinator.submitLocalTitleAttempt` is internal and requires explicit network
allowance. It accepts only an exact durable PREPARED wire without response evidence.
Under its mutex it retains one generation/policy epoch, observes the account before
calling the port, and supplies a private local-only qualification callback. This
callback reads the selected calendar, exact SQL edit, retained capsule, baseline/cache,
holds, native dirty contents and any transferred CREATE provenance. It uses the cached
account witness and never calls `currentAccount`, re-enters the authentication facade
or reacquires the coordinator mutex. The existing preparation/retention paths keep
their fresh account observations. Transferred events still require the full original
CREATE chain and released waiting frame; they cannot fall back to ordinary capture.

Only complete, attempt-bound verification can be returned, after exact durable UNKNOWN
and evidence readback and final local qualification. This operation does not store a
verified result, acknowledge Android, complete the attempt or activate the worker.
Independent SQLite databases, the Provider and the session have no distributed commit;
these are repeated observations, not an atomic native edit precondition. A change after
the final observation can still race transport. Preparing work does not supply dispatch
admission. Fresh GET followed by PUT remains an optimistic controlled-writer experiment,
without an atomic server precondition. A new physical native capture-to-PUT journey and
production orchestration remain separate work.

## Stable preparation identity

`TitleUpdatePreparationJournal` reserves an attempt identity before wire
preparation. The complete retained preparation capsule and current wire format
are immutable. Identical concurrent proposals receive the first durable identity,
even if they proposed different IDs. A compatible legacy PREPARED record owns
that identity when adopted; its first protected wire is preserved.

The schema-four migration adds an empty reservation table. Version-three attempts,
protected bytes, results and completions are untouched; earlier versions retain
their existing migration rules. Reservation, both retention overloads and dispatch
claim share a fully qualified ownership view under the writer transaction. A bare
reservation holds the event across generations. An exact matching reservation
can receive its first PREPARED wire, but another identity or capsule cannot bypass
it. Both cross-references are checked by scope and identity, independently of the
event discovery indexes. Corruption or disagreement between reservation and attempt
remains blocking.
Only the corresponding full, valid completion releases logical ownership for a
new cycle; the reservation and original UNKNOWN work stay in history.

Reservation checks its per-call lease after lock acquisition and immediately
before commit, including repetitions. Reading by exact scope and event can recover
the identity after reopening, but grants no preparation or dispatch authority.
UNKNOWN references are readable history and cannot be reserved again. Without a
wire, a future caller may repeat read-only preparation with the same identity;
this does not promise one cryptographic execution. Once a wire exists, the caller
must read it instead of preparing again. A competing different wire is rejected,
and the caller must explicitly read the winner rather than report its own wire
as retained. An internal helper composes this journal with guarded preparation;
the internal coordinator supplies current SQL/Android qualification. Production
UPDATE remains separate.

## Guarded preparation and retained-wire recovery

The guarded `prepareSubmission(reservation, admit)` overload denies unsupported
ports by default, without invoking unrestricted preparation. Proton freezes the
reserved identity and complete capsule before dispatcher, mutex or session waits.
Admission precedes opening historical protected fragments, then the expected
session and lease are checked around context qualification and wire sealing.
The shared runtime checkpoints cover refreshed user/address reads, calendar
metadata, bootstrap and local address resolution. These application-controlled
checks do not intercept every internal Core exchange or cancel an already started
read when a policy callback subsequently becomes false.

The authentication facade restricts the runtime callback to the reserved scope
and current format. It rejects incompatible returned work and rechecks access
after the runtime returns, even when it returns null. Revocation denies the
preparation; authentication, network, retry and storage errors remain typed,
including explicit legacy experiments. Cancellation always propagates.

`TitleUpdatePreparationRecovery` first reserves the canonical identity and reads
its exact durable record. An existing PREPARED wire is returned without preparation,
and UNKNOWN never becomes preparation admission. With a bare reservation,
`allowNetwork` defaults to false. Explicit eligibility may call only guarded
preparation, validate the complete reservation binding and retain through the
guarded journal. A successful retention requires exact wire readback. After a
lost race, explicit readback may return the other helper's immutable first wire;
the losing generated wire is never reported as retained. Corruption or persistence
exceptions propagate instead of becoming historical success. Access and cancellation
are rechecked after every awaited result, including null and the final readback.

This helper performs no claim, PUT, remote inspection or native acknowledgement.
Historical wire replay does not refresh remote rights. The caller supplies
an epoch-bound lease and qualifies the current SQL/Android owner around preparation;
the pure helper cannot inspect those sources. Its explicit asynchronous `checkAccess`
overload checks additional observations before and after suspended work, including
null results and the final durable read. This check supplements the synchronous
scope/format/epoch lease passed to storage and preparation. The existing overload
remains available for callers that supply their own qualification.

### Current native edit qualification

`ImportCoordinator.prepareLocalTitleAttempt` composes this helper with the same
private owner qualification used by `retainLocalTitleAttempt`. The complete
retained SQL intent, baseline, capsule and native dirty contents must still match
the candidate. Imported events use their owned simple snapshot. Transferred CREATE
events require the original capture/dispatch/attachment provenance, a released
waiting handoff frame and the matching specialized native observation. Missing or
changed provenance never falls back to ordinary capture.

One session/policy epoch covers the invocation. Account, selection, permission,
calendar editing mode and adapter formats are checked alongside the complete
source around reservation, wire reads, guarded preparation, retention and durable
readback. The first observed refusal remains binding for the invocation. A restored
permission or a later source cannot substitute a new owner. Cancellation propagates;
typed preparation failures become bounded outcomes without rewriting the local
intent or marking its SQL owner conflicted merely from a port exception.

The returned `Prepared` contains only the canonical durable record, which may use
the first reservation's ID rather than the caller's proposed ID. Network preparation
defaults to disabled: a bare reservation remains unresolved with no
preparation call. UNKNOWN stays held. If the source changes during preparation,
no returned wire is retained; if it changes during commit or readback, retained
history remains but the invocation cannot report `Prepared`.

Provider observations, SQL ownership and the wire journal have no distributed
transaction. These surrounding checks qualify measured observations, not an atomic
snapshot or authority to dispatch. The API makes no claim, PUT, service inspection
or native acknowledgement, and remains disconnected from the production worker.

## Local binding of prepared work

`ImportCoordinator.retainLocalTitleAttempt` binds an already prepared wire record
to the exact retained Android edit and preparation capsule. It does not prepare
wire, claim dispatch, inspect the service or acknowledge Android. Only a PREPARED
record without response evidence is eligible, with the adapter's current wire
format and the current import checkpoint. Mode, account, selection, permission
and one session/policy epoch remain required through durable readback.

The SQL owner, complete capsule, projected baseline, cached remote contents and
deferred state must qualify. The current owned native dirty snapshot must still
equal the complete prepared contents. A transferred CREATE additionally requires
its released waiting frame, complete original journal provenance and an exact
supported, non-deleted native snapshot. Missing provenance or a blocked scope
cannot fall back to ordinary imported-event handling.

The guarded `remember(record, admit)` overload denies by default and never falls
back to unrestricted retention. The store checks its lease after waiting for
access and immediately before commit, including exact repetitions. Full event
history is revalidated across generations. UNKNOWN, another unresolved owner,
substituted work and reused historical identities cannot create or replace a
PREPARED record. Corrupt history remains held. Explicit legacy experiments keep
their original idempotent retention behavior without resetting UNKNOWN.

The coordinator requires exact durable record readback and requalifies both SQL
ownership and the current native snapshot before reporting retention or qualified
historical replay. A later conflict, edit or access loss may leave the committed
wire intact while refusing current qualification. SQL stores and Calendar Provider
have no distributed atomic commit. Retaining opaque wire does not prove its
current cryptographic validity, refresh remote rights or authorize a future send;
submission still needs its own fresh checks. The production worker does not call
this internal binding path.

## Retained verified results

`TitleUpdateVerifiedResult` binds the complete expected contents and observed
revision to the exact UNKNOWN attempt, original preparation and protected wire
payload. It accepts only the submission adapter's full-content verified
observation. Response evidence is retained independently and is the sole field
excluded from the result's attempt comparison.

The journal retains the first result in a `synchronous=FULL` transaction against
the entire identical UNKNOWN record. Exact repetitions are idempotent. Another
revision or substituted work cannot replace it. Its version-two migration adds
result storage without changing original attempts, preparation or evidence.
Decoded results are validated again against their owning record.

`ImportCoordinator.submitAndRetainLocalTitleAttempt` adds result retention to the
owner-qualified submission path. It shares the same private core, mutex and original
generation/policy epoch with the observation-only entry point; it does not release
that ownership and acquire another lease between submission and storage. Only an
exact complete Verified observation backed by the identical durable UNKNOWN wire
and response evidence can reach result retention.

After the submission facade returns, fresh account observations are safe again.
The helper requalifies the account, selected calendar, SQL owner, capsule and native
contents before guarded `retainVerified(result, lease)`, after its return even when
false, and after reading the retained proof. It returns only a complete equal result,
including the observed revision. Refusal, storage failure or substituted/null
readback returns no qualified proof; there is no fallback to an older result or
unrestricted retention. Cancellation propagates even if a dependency returns false
or null. The invocation's lease expires on exit.

A committed proof remains historical if access or the source changes before the
qualified return. The first immutable result is preserved if another observation
wins a race; a different revision cannot replace it. Unsupported guarded result
storage can leave an already submitted UNKNOWN attempt with response evidence and
no result, requiring the separate inspection-only recovery path. Neither this helper
nor the observation-only entry point resubmits UNKNOWN, acknowledges Android,
completes the attempt or activates a worker. Independent SQL/Provider observations
still have no distributed commit or atomic server precondition.

`TitleUpdateResultRecovery` first reads retained history without network access.
If no proof exists, it only inspects the UNKNOWN attempt and returns a verified
result after durable retention. Failure or cancellation propagates; there is no
prepare, claim or submit step. A retained result is a historical observation,
not current server state, server authorship, native ACK admission or permission
to send again. The event's unresolved-attempt hold remains in place.

## Native title acknowledgement

`ImportCoordinator.acknowledgeLocalTitle` consumes an already retained result;
it cannot prepare, submit or inspect a service request. Current account, calendar
selection, import format, permissions and experimental editing policy must qualify
the call, including after suspended source reads. Represented events are refused.
An ordinary event has no CREATE provenance. A transferred CREATE requires its
explicit complete frame; it cannot fall back to ordinary acknowledgement.

Before writing Android, `ImportStore` reserves one immutable receipt linking the
attempt identity, complete original intent, old/new baselines and nonce. The
preparation fingerprint checks every original field, timestamp and protected
payload without copying a second sealed blob into the receipt. The additive
version-sixteen import migration preserves existing work.

Only the fresh reservation may call the native write. One atomic Calendar Provider
batch asserts the unique owned calendar and both event identities, complete
submitted contents, old revision, `DIRTY=1`, `DELETED=0`, supported markers
and absent children. It changes only `DIRTY`, revision marker `SYNC_DATA2` and
receipt marker `SYNC_DATA3`. A later edit, tombstone, child or metadata change
before the batch prevents acknowledgement and stays intact.

Qualified native proof restores SQL atomically: advance the exact old baseline,
close only the original intent and preparation, advance only an exact old cached
record to the result, and archive the receipt as RESTORED. Other remote records
and deferred observations survive. If a poll changed the same original intent
to CONFLICT after the native commit, proof may close that exact historical
owner; it never resets a conflict or substitutes another owner.

Newer cached observations retain their existing projection state. If their
projection was held by the original local intent, a later projection trigger must
requeue them; this acknowledgement does not schedule that trigger.

After interruption, an existing receipt permits marker inspection only. That
historical inspection ignores and preserves subsequent native content, dirty
flags, tombstones and children. SQL restoration can then complete without another
native write. If the first batch never committed, the receipt stays pending;
there is no automatic rearming. A RESTORED replay never consumes the next intent.
The network attempt remains UNKNOWN. Its per-event hold remains until a separate
durable completion binds the first verified result to the exact RESTORED receipt.

For a transferred CREATE, the receipt also freezes the full original creation,
attachment, acknowledgement and handoff reference. The existing import database
uses unchanged ordinary and extended receipt encodings. The extended
encoding is self-contained even after the current baseline advances. Reserving a
fresh receipt requires the exact RELEASED frame with its retained local change,
`waiting=1`, and no unresolved incoming projection receipt.

The Provider asserts original CREATE markers and the old projection marker together
with the complete dirty snapshot. It still changes only `DIRTY/SYNC_DATA2/SYNC_DATA3`.
SQL restoration additionally advances `last_projection` to the verified result and
clears the original frame's waiting flag, atomically with baseline advancement and
exact owner consumption. Original CREATE evidence is never rewritten. The native
proof establishes the creation and projection markers; the handoff nonce and
RELEASED state remain separately qualified by the SQL owner.

## Durable completion and subsequent title cycles

The internal completion coordinator reads the actual verified-result journal and
attempt-scoped RESTORED import receipt. It checks the selected calendar, current
account, permissions, policy epoch and adapter format before retaining closure.
It makes no Provider write or service request. An interruption between restoring
the receipt and committing completion leaves the event blocked until this
historical read-only recovery succeeds.

Import schema seventeen archives complete RESTORED receipts atomically with SQL
restoration. A later qualified cycle may replace the current event slot only
after preserving its exact previous receipt. Old replays resolve their original
attempt and cannot consume the next local owner or its preparation. Legacy
version-sixteen receipts remain byte-identical and are archived when reused;
PENDING receipts cannot be replaced.

Attempt-journal schema three adds immutable completion without changing UNKNOWN,
the protected request, preparation, evidence or first result. Migration never
infers closure from an old result. An unresolved attempt blocks the same event
across generations. A valid closure permits a distinct new intention and attempt;
claiming the old attempt still fails. Admission validates historical closures,
including their complete CREATE handoff frames, and corruption blocks new work.
These histories currently retain every completed cycle; retention policy and
production scheduling remain separate work.

## Retained incoming observations after acknowledgement

The first SQL acknowledgement restoration requeues only the acknowledged event's
distinct, supported cached observation in the same transaction that advances the
baseline, consumes the exact local owner/preparation and archives the RESTORED
receipt. It preserves the cached payload and opaque revision; distinctness does
not establish revision order. An old-base cache advances to the verified result,
an exact-result echo remains settled, and an absent cache is never synthesized.
No unrelated event or scope is requeued. An archived RESTORED replay is read-only.

Deferred evidence is neither rearmed nor discarded. Ordinary recurrence exceptions
retain the existing missing/incompatible-parent gate; children of inactive parents
remain suppressed. Ordinary incoming deletions use the existing guarded import
path. Transferred CREATE events preserve their narrower profile: unsupported
deletions or recurrence are not rearmed by title acknowledgement and never fall
back to ordinary projection. Previously pending distinct work keeps its existing state.

Requeueing grants no permission to overwrite Android. The existing local incoming
run separately qualifies the row and its provenance; a later DIRTY edit stays
intact and can be captured as a conflicting intention. A SQL failure after
requeueing rolls back the queue, baseline, owner, handoff frame and receipt together.
Native acknowledgement recovery remains inspection-only. This change activates
neither outgoing UPDATE nor a new scheduler.

## Offline observation and work discovery

The system observer reuses the existing durable UPDATE owner instead of deriving
every edit's identity from the event ID. Its immutable content and preparation
remain unchanged while retained, including after restart or conflict detection.
After acknowledgement consumes that owner, a later observed edit receives a new
UUID. Imported events and transferred CREATE events follow this rule; transferred
events still require their complete creation evidence and current handoff frame.
Automatic observation requires the experimental system-editing mode and the
existing session, selection, adapter and permission gates. Explicit capture calls
keep their caller-supplied identity contract.

`TitleUpdateWorkJournal` exposes current unresolved attempt metadata in pages of
at most fifty entries. Its cursor binds account, calendar, generation and protocol;
SQLite BINARY event ordering and one-row lookahead determine continuation. Reads
select bounded identities and state only, without loading protected requests or
decoding proof histories. Non-null completions are excluded without qualifying
their contents. Corrupt included metadata rejects the page with a sanitized error.

The index survives process restart and does not depend on `local_edits`, which
acknowledgement may already have consumed before completion is durable. It is
neither a snapshot nor a dispatch permit. New insertions behind a cursor require
a fresh first page. Old-generation work requires querying its original scope;
the index does not rebind it to the current generation. Recovery must separately
read full attempts and proofs and recheck access and admission. No production
UPDATE scheduler is activated by these prerequisites.

## Bounded offline completion recovery

`TitleUpdateOfflineRecoveryJournal` supplies metadata discovery, complete attempt
reads and the existing result/completion history. The internal coordinator handles
one page of at most fifty entries sequentially under its synchronization mutex.
It captures one session generation and editing-policy revision for the entire
invocation. Account, selection, permissions and editing mode are rechecked across
suspending reads; acknowledgement and completion reuse that original epoch.

Discovery alone permits no action. The coordinator validates each full attempt's
scope, event and attempt identity and import format, then requires its already
durable, exactly bound verified result. PREPARED work, incompatible import formats
and missing proofs remain held. Invalid full records are counted as failed and
retained; a later entry can still proceed. Existing acknowledgement and completion
guards remain authoritative for any unresolved provenance or native-state conflict.
No inspection, preparation, claim, submission or new result retention occurs.

A RESTORED receipt without a committed completion can finish after reopening from
its historical proof. This replay does not write native metadata again and preserves
a later dirty edit. Progress counts describe only this invocation, not global sync
completion. Held/failed entries and insertions behind a cursor require a fresh
first-page traversal. Access invalidation returns no continuation, and cancellation
propagates. The production worker now invokes this offline path through
`SystemTitleRecoveryController`, using the production title journal rather than
isolated experiment journals. The guarded inspection path below supplies separate
transport and durable-result admission boundaries and remains disconnected.

### Production scheduling

The existing local and network import workers share the system-creation owner.
For each current selected editable calendar, offline title recovery runs before
native change discovery under the same owner epoch. A native scan or CREATE failure
cannot prevent recovery of an existing durable proof. No title service port is
provided to the recovery controller, and the journal's dispatch gate stays closed.

Each controller invocation processes at most twenty-five entries per calendar scope. The local cursor is
committed before continuation is reported and binds the full account/calendar/
generation scope and protocol through a framed digest. It survives reopening,
and persists exhaustion through worker retries and continuations. An exhausted
calendar restarts only on a fresh ordinary trigger, not because another calendar,
import or CREATE still has pages. Explicit mode changes and access revocation
discard the old position.
Current metadata order is owned by SQLite; opaque IDs are never sorted in the JVM.
Only a valid continuation requests another worker pass. Held/failed entries alone
do not trigger immediate retries, and a malformed page or cursor remains visible
as a bounded failure. Cursor persistence failure grants no continuation. Later
ordinary triggers can start a fresh first-page traversal, including insertions
behind an earlier cursor. Cancellation propagates across every suspended boundary.

This activates completion recovery only. New title attempts are not generated
by this controller, UNKNOWN work without proof is not inspected or resent, and
bare preparation reservations remain intact. Existing session, selection, mode,
permission, import-format, exact-owner and native-state guards still decide whether
acknowledgement or completion is permitted. Experimental journals are not adopted
by production, and automatic title submission remains a separate implementation.

## Guarded inspection and result retention

`TitleUpdateGuardedResultRecovery` requires a synchronous access callback bound to
the original account/calendar/generation and session/policy epoch. Default denial
performs no reads or transport. A durable historical result needs no network;
without one, inspection additionally requires `allowNetwork=true` and a compatible
wire format. PREPARED work remains held. Full record and result bindings are checked
around suspensions. There is no preparation, claim, submission or native operation.

The guarded `inspect(record, admit)` and `retainVerified(result, admit)` overloads
deny by default, without falling back to the unrestricted experimental methods.
The serialized Proton facade freezes the record before dispatcher/mutex waits and
rechecks access after those waits and the runtime return. The adapter checks access,
the expected session and cancellation before opening protected data and around
context qualification, exact-event GET, bootstrap and verification. Qualification
passes its checkpoint between refreshed user/address reads, calendar metadata,
bootstrap and local address resolution. These checks cover application-controlled
stages; they do not intercept every internal Core exchange or cancel an already
started read merely because a policy callback later becomes false.

Result storage checks admission after acquiring its lock and immediately before
commit, including an identical repetition. Revocation during that transaction rolls
back the result write without changing UNKNOWN work, preparation or response evidence.
The first durable result remains immutable under concurrent helpers. Recovery returns
a newly observed result only after successful guarded commit and exact journal
readback. Revocation after commit can suppress the return while preserving historical
proof for a later invocation. Cancellation and persistence failures propagate.
The caller must not readmit an old callback after a policy off/on cycle. These
internal guards do not activate a production UPDATE worker or advertise server CAS.

## Inspection and completion in one bounded page

`inspectAndRecoverLocalTitles` shares the offline page coordinator's mutex and
acknowledgement/completion helpers. One invocation captures one session generation
and policy revision, then processes at most fifty metadata entries sequentially.
`TitleUpdateRecoveryJournal` combines discovery, full attempts and durable history;
`TitleUpdateRecoveryProgress` uses the existing bounded page accounting.

Each full UNKNOWN attempt must match its discovered scope/event/attempt and the
current import format. Already durable proofs proceed without service inspection.
For missing proofs, the guarded recovery path additionally requires explicit
network eligibility and a compatible wire format. Network eligibility defaults
to false and never authorizes preparation, claim, submission or response-evidence
replacement. An unknown observation or unsupported guarded port keeps work held.
Malformed records and thrown persistence failures are retained and counted as
failed; later entries can proceed. Cancellation propagates.

The original synchronous scope/epoch lease accompanies inspection and result
commit. An additional suspending access checkpoint checks current account and
selected-calendar availability around history reads, inspection, guarded retention
and durable readback. The result must still match the initially qualified complete
attempt before native acknowledgement; only independent response evidence is
excluded from this comparison. Permissions and observed account changes invalidate
the policy epoch even without a CREATE journal. Restoration of access requires a
new invocation; an inactive page exposes no continuation.

The production owner invalidates policy synchronously before deselection or loss
of calendar availability can wait for its mutex. The synchronous commit lease
relies on those invalidations. A direct out-of-owner SQL selection change is not
an atomic transaction with the separate result journal; surrounding access reads
prevent qualified success and native acknowledgement after detected revocation.
Historical results and pending acknowledgement receipts survive interrupted
restoration. Reopening can finish using the same receipt without another inspection
or native write, preserving a later dirty edit. This runner remains internal and
is not called by production workers or UI controls.

## Concurrency and integration limits

The fresh GET and subsequent PUT are successive observations, **not an atomic
server precondition**. A concurrent Proton change between them may be overwritten.
Readback proves current observed contents, not authorship or immutable remote
history. This adapter does not advertise general conflict-safe write capability.

There is no production title-submission worker or UPDATE UI toggle. The existing system
observer continues to retain edits/deletions locally. The internal owner binding
now qualifies captured work through guarded submission. Before activation, UPDATE
must be connected to production scheduling and the complete service and native
journey must be qualified.
The internal completion path permits successive locally qualified title cycles;
it does not activate production UPDATE or establish repeated service submissions.
Conflict policy, further editable fields, recurrence, invitations and DELETE
remain separate work.

## Repeatable verification

- `TitleUpdateSubmissionTest` checks commit-before-send, cancellation, transport
  failure, exact identities and observation limits with a controlled journal.
- `TitleUpdateAttemptStoreDeviceTest` uses isolated SQLite fixtures with
  `providerFixture=true`. It exercises concurrent handles, reopening, immutable
  evidence, generation isolation and an actual deferred constraint failure at
  commit. These fixtures make no Provider or service requests.
- `TitleUpdateVerifiedResultTest` checks exact full-content binding, immutable
  snapshots, inspect-only recovery, offline historical reads and failure before
  durable retention.
- `TitleUpdateGuardedResultRecoveryTest` checks explicit network eligibility,
  historical offline recovery, default-denied overloads, immutable epoch admission,
  revocation across reads/commit, cancellation and exact durable readback.
  `ProtonGuardedTitleInspectionTest` uses synthetic adapter/facade fixtures for
  context-stage/session/access changes, mutex waits and cancellation. It qualifies
  control flow, not Proton signatures or server acceptance.
  The owner access-check overload also exercises denial and cancellation across
  asynchronous account/selection observations without replacing the commit lease.
- `TitleUpdateResultStoreDeviceTest` checks result persistence, migration,
  immutable first proof, concurrent handles, corruption and transaction failures
  in isolated SQLite fixtures. No Provider or service request is involved.
  Guarded retention fixtures additionally observe a real uncommitted SQL write
  before revoking admission, verify rollback and reopening, and qualify idempotent
  replay, cancellation and independent-helper races under the per-call lease.
- `TitleUpdatePreparationReservationTest` checks complete identity bindings,
  strict bounds, redacted diagnostics and default-denied unsupported journals.
  `TitleUpdatePreparationReservationStoreDeviceTest` checks independent writers,
  canonical identity after reopening, legacy adoption, bare and UNKNOWN holds,
  first-wire immutability, completion qualification, corrupt history and migrations
  from all earlier journal versions. Revocation and cancellation after an observable
  uncommitted reservation must roll back; idempotent replay rechecks admission.
  These fixtures use isolated SQLite and synthetic capsules, without service or
  native operations, and do not qualify network preparation or production wiring.
- `TitleUpdatePreparationRecoveryTest` checks asynchronous access refusal and cancellation,
  offline wire reuse, canonical IDs,
  explicit network eligibility, unsupported guarded ports, exact successful readback,
  concurrent winners, UNKNOWN transitions, revocation and cancellation after null.
  `TitleUpdatePreparationRecoveryDeviceTest` combines the helper and real isolated
  SQLite for reopening, failed wire commits, independent writers and UNKNOWN races.
  `ProtonGuardedTitlePreparationTest` and its facade fixtures check context-stage
  guards, session replacement, sealing, freezing across waits, scoped callbacks,
  typed errors and cancellation. These use controlled ports/protected capsules;
  they do not establish service acceptance, native owner composition or a new PUT.
- `TitleUpdateGuardedAdmissionTest` checks default denial, scoped claim, committed
  UNKNOWN before send, cancellation and preservation after revocation or transport
  failure. `TitleUpdateGuardedClaimStoreDeviceTest` checks the same admission boundary
  on isolated SQLite: writer races, commit failure, rollback, complete ownership,
  corruption, reopening and independence from legacy mutable admission.
  Guarded Proton submission/facade fixtures check stage revocation, session changes,
  unsupported fresh preparation, UNKNOWN refusal, retained response evidence,
  cancellation and complete verified bindings. Guarded fresh-preparation fixtures
  check each controlled context/crypto stage. These controlled ports and synthetic
  capsules do not establish service acceptance, native owner dispatch or production activation.
- `ProtonOwnerQualifiedTitleSubmissionTest` and its facade suite check the suspending
  local-source contract, final post-session qualification, monotone refusal,
  cancellation, evidence preservation and no overload fallback. These use controlled
  ports and synthetic protected fragments. `TitleUpdateSubmissionCoordinatorDeviceTest`
  uses isolated real SQL journals/owners and controlled native snapshots to check exact
  wire binding, offline refusal, changed ownership, durable UNKNOWN after claim,
  CREATE provenance corruption and absence of authentication re-entry. It grants no
  physical Provider or new service PUT qualification.
- `TitleUpdateSubmissionResultCoordinatorDeviceTest` checks real isolated SQL result
  retention after controlled submission: exact reopening, unsupported guarded
  storage, rollback and deferred commit failure, post-commit revocation, independent
  owner/native changes, revision races, substituted readback and cancellation. It
  checks that the observation-only API still stores no result and that completed
  invocations close their leases. Native snapshots and transport are controlled;
  this suite does not establish physical Provider or new Proton PUT compatibility.
- `TitleUpdatePreparationCoordinatorDeviceTest` composes the guarded helper with
  real isolated SQL owners/journals and controlled native snapshots. It checks
  canonical identity recovery, offline reuse, conflicts, capsule substitutions,
  access revocation, changes during preparation/commit/readback, typed failures
  and cancellation. The guarded title scenarios in `CapturedHandoffIntegrationDeviceTest`
  additionally use the real Calendar Provider and original CREATE provenance,
  checking unchanged rows, offline replay, lost provenance and later native edits.
  Their preparation ports are synthetic; they establish local owner composition,
  not Proton service acceptance, dispatch or production worker activation.
- `TitleUpdateGuardedPreparationStoreDeviceTest` qualifies immutable guarded
  PREPARED retention, default denial, complete history across generations,
  UNKNOWN holds, corruption and rollback after observable writes or cancellation.
  `TitleUpdateAttemptRetentionCoordinatorDeviceTest` checks exact owner/capsule
  bindings, native edits, durable replay, unsupported journals, access invalidation
  and cancellation, including account lookups that swallow cancellation.
  Real Provider/SQLite integration additionally covers ordinary and transferred
  ownership, reopening, unchanged native rows and missing CREATE provenance.
  Service preparation, dispatch and inspection are forbidden in these fixtures;
  synthetic wire does not establish Proton compatibility.
- `TitleUpdateAcknowledgementTest` checks complete receipt/preparation bindings.
  The Provider, Store and Coordinator device fixtures exercise atomic metadata
  changes, concurrent local/remote edits, lost replies, interrupted restoration,
  access revocation and idempotent history. Integration fixtures combine the real
  Provider, both SQLite stores and coordinator with synthetic result evidence;
  they do not establish Proton acceptance or a service-to-native UPDATE journey.
  These fixtures also qualify event-scoped requeueing, rollback after that queue
  update, reopening, incoming same-row offline projection and historical replay.
  A later native dirty edit remains protected during the incoming drain.
  The integration fixture also starts from a real native capture without seeded
  wire, UNKNOWN state or result, then composes guarded preparation and controlled
  submission with result retention and offline acknowledgement/completion. Timed
  and all-day imported and transferred CREATE profiles preserve every native
  column except the three acknowledgement metadata fields. Reconstructing all
  four SQL stores and the coordinator within the same process qualifies durable
  recovery of restoration and completion gaps, immutable CREATE provenance and
  preservation of a later captured owner. Real native changes before claim deny
  dispatch; changes after the controlled exchange preserve UNKNOWN/evidence and
  deny result retention. This does not establish process-death recovery, Proton
  cryptography, a new service PUT or production worker activation.
- `TitleUpdateCompletionTest`, `TitleUpdateCompletionJournalDeviceTest` and
  `TitleUpdateAcknowledgementHistoryDeviceTest` check closure bindings, migration,
  immutable history, default-denied admission, corruption and rollback. Real
  Provider/SQLite composition checks two successive ordinary and transferred
  title cycles, lost completion commits and old replay during a newer reservation.
  Their remote results are synthetic; they make no UPDATE service request.
  Offline-page coordinator fixtures additionally check mixed corrupt/held/ready
  entries, access changes across reads, cancellation and unchanged dispatch capability.
  Real Provider/SQLite integration covers one-page completion and reopening after
  an aborted completion commit for imported and transferred CREATE events, preserving
  later dirty edits and making no second native acknowledgement write.
  Inspection-page fixtures additionally qualify guarded proof retention followed
  by acknowledgement/completion, network-ineligible holds, failed commits, access
  invalidation and bounded mixed pages. Real Provider/SQLite fixtures reopen a
  proof-to-acknowledgement restoration gap without another synthetic service
  inspection or native write, for imported and transferred CREATE events.
- `TitleUpdateWorkPageTest` and `TitleUpdateWorkPageDeviceTest` check bounded
  metadata pages, lookahead, strict identities, scope isolation, binary ordering,
  reopening and insertions behind a cursor. `SystemCreatePipelineDeviceTest`
  combines the real observer, Provider and SQLite stores for successive imported
  and transferred title cycles with synthetic remote results. Capture coordinator
  fixtures verify immutable preparations, conflict retention and observer gates.
- `CapturedHandoffTitleAcknowledgementProviderDeviceTest` and
  `CapturedHandoffTitleAcknowledgementStoreDeviceTest` qualify explicit CREATE
  provenance, original and advanced frames, receipt reopening, conditional
  metadata acknowledgement and interrupted transactional restoration. Coordinator
  fixtures check source changes during suspended reads; integration fixtures use
  the real Provider and SQLite stores and preserve a later transferred dirty edit.
- `ProtonTitleUpdateSubmissionTest`, `ProtonTitleUpdateSubmissionDispatchTest`
  and `ProtonTitleUpdateSubmissionCoreTest` exercise strict serialization,
  fresh qualification, readback refusal, transport permits and the real Core/
  Retrofit stack against loopback servers with synthetic sessions.
- `CalendarTitleUpdateShapeLiveDeviceTest` explicitly prepares a retained
  synthetic CREATE fixture without sending UPDATE. Diagnostics contain schema
  names/types only, and the preparation is stored separately from production work.
- `CalendarTitleUpdateSubmissionLiveDeviceTest` requires explicit opt-in and an
  authorized fixture/account identity. It retains the exact attempt before one
  controlled title submission. `titleUpdateInspectOnly=true` permits restart
  inspection without admission; reruns of UNKNOWN work cannot send again.
- `CalendarTitleUpdateResultLiveDeviceTest` requires explicit opt-in and the same
  retained fixture. It reads the previously verified result after restart with
  a port that rejects inspection and submission. This checks the recovery path's
  offline behavior; it does not claim that all other application activity is offline.
- `CalendarTitleRecoveryLiveDeviceTest` requires `calendarTitleRecoveryLive=true`
  and the exact previously enrolled fixture/account. It reconstructs the original
  UNKNOWN work, including protected response evidence, in a separate journal with
  no result. It seeds an isolated imported owner and captures the real native title
  edit, requiring the complete captured intent to equal the historical preparation.
  Only native namespace keys are translated to a separate Provider generation;
  the service's scope, preparation and request remain unchanged. A real guarded
  Proton inspection must supply full verified contents before durable result
  storage, metadata-only same-row acknowledgement and completion. Reopened
  databases then read the durable history and explicitly replay acknowledgement
  and completion while preserving a later dirty edit. Preparation, submission
  and unrestricted inspection are forbidden throughout this recovery test.
  The production owner first reconciles the existing native access level according
  to the retained mode, without changing preferences or the session. Subsequent
  production snapshots, original journals, calendars and the native account are
  checked; only newly created isolated calendars/databases are cleaned up. Those
  snapshots do not prove absence of unrelated background activity between reads.
- `CalendarTitleGuardedJourneyLiveDeviceTest` requires
  `calendarTitleGuardedJourney=true`, an enrolled account hash and a new immutable
  fixture token. It creates a new synthetic timed event as its remote base, then
  captures a real title edit in a separate native namespace. Actual Proton capsule
  and guarded wire preparation precede owner-qualified submission and exact durable
  result retention. The `submit` phase stops before native acknowledgement. After
  an explicit process stop, `recover` reads the same proof without a title or CREATE
  service port, acknowledges only native metadata and completes the attempt.
  A later dirty edit remains capturable against the new baseline; old recovery
  never consumes that owner or sends again. `titleGuardedDropMarkers=true` removes
  descriptive phase markers to verify that durable proof and exact owners govern
  recovery. Event, isolated native calendar and journals remain for inspection.
  This qualifies an ordinary imported timed profile across ordered process
  restarts, not transferred CREATE handoff, arbitrary transport interruption,
  Samsung editing or automatic UPDATE. Application startup can independently
  perform network activity; the offline guarantee applies to the recovery path.

Live tests must never run indiscriminately or replace an unresolved fixture with
a new identity. Local tests do not establish service acceptance or Samsung UI
behavior. The experiment retains synthetic examples for inspection and never
changes account attributes.

One retained simple timed event qualified the service path: the single operation
received a success response, and full authenticated inspection passed after
process restart. Reopened submission inspected the same UNKNOWN attempt without
dispatch admission; its preparation and first protected response stayed intact.
This evidence covers that title-only profile, not automatic native reconciliation
or concurrent writers.

The same UNKNOWN fixture subsequently qualified durable result retention by
inspection only, then historical recovery after process restart with all remote
port methods forbidden. The original preparation and first response evidence
remained intact. Isolated physical SQLite fixtures qualified the result journal's
migration, immutable first result, concurrent helpers and failed-commit behavior.

The guarded recovery fixture also passed with a real authenticated Proton readback
followed by real Provider acknowledgement and SQL completion on the isolated native
row. Only DIRTY, revision and receipt metadata changed. Reopening both databases
and explicitly replaying historical acknowledgement/completion left every field
of a later dirty title edit intact, with no additional service-port inspection or
native write. This qualifies recovery of the historical service attempt into a
reconstructed ordinary imported owner. It does not qualify acknowledgement of the
original transferred CREATE row, another title submission from that native capture,
process-death recovery of this composed journey, Samsung editing or automatic UPDATE.

# Internal title UPDATE experiment

The release target remains bidirectional synchronization through Android calendar
clients. This internal experiment qualifies a narrower service boundary: submitting
one prepared title change for a simple personal event. A separate internal path
conditionally acknowledges ordinary imported and transferred CREATE events after their result
is retained. UPDATE remains unavailable to production workers and UI controls.

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

## Concurrency and integration limits

The fresh GET and subsequent PUT are successive observations, **not an atomic
server precondition**. A concurrent Proton change between them may be overwritten.
Readback proves current observed contents, not authorship or immutable remote
history. This adapter does not advertise general conflict-safe write capability.

There is no production worker or UI toggle for UPDATE. The existing system
observer continues to retain edits/deletions locally. Before activation, a
separate owner must bind captured native work to this journal, connect UPDATE
to production scheduling and qualify the complete service and native journey.
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
- `TitleUpdateResultStoreDeviceTest` checks result persistence, migration,
  immutable first proof, concurrent handles, corruption and transaction failures
  in isolated SQLite fixtures. No Provider or service request is involved.
- `TitleUpdateAcknowledgementTest` checks complete receipt/preparation bindings.
  The Provider, Store and Coordinator device fixtures exercise atomic metadata
  changes, concurrent local/remote edits, lost replies, interrupted restoration,
  access revocation and idempotent history. Integration fixtures combine the real
  Provider, both SQLite stores and coordinator with synthetic result evidence;
  they do not establish Proton acceptance or a service-to-native UPDATE journey.
- `TitleUpdateCompletionTest`, `TitleUpdateCompletionJournalDeviceTest` and
  `TitleUpdateAcknowledgementHistoryDeviceTest` check closure bindings, migration,
  immutable history, default-denied admission, corruption and rollback. Real
  Provider/SQLite composition checks two successive ordinary and transferred
  title cycles, lost completion commits and old replay during a newer reservation.
  Their remote results are synthetic; they make no UPDATE service request.
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

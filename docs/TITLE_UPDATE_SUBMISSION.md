# Internal title UPDATE experiment

The release target remains bidirectional synchronization through Android calendar
clients. This internal experiment qualifies a narrower service boundary: submitting
one prepared title change for a simple personal event. It does not activate UPDATE
in the application, acknowledge Android edits or release pending local work.

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

## Concurrency and integration limits

The fresh GET and subsequent PUT are successive observations, **not an atomic
server precondition**. A concurrent Proton change between them may be overwritten.
Readback proves current observed contents, not authorship or immutable remote
history. This adapter does not advertise general conflict-safe write capability.

There is no production worker, UI toggle or Provider acknowledgement for UPDATE.
The existing system observer continues to retain edits/deletions locally. Before
activation, a separate owner must bind captured native work to this journal,
acknowledge only the exact unchanged native
snapshot, preserve a later DIRTY edit and recover the cross-store transition.
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

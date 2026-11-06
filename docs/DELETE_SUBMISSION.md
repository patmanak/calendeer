# Guarded deletion submission

## Capability and supported scope

The typed deletion port has separate worker orchestration, default-off experimental
per-calendar consent, durable proofs and native acknowledgement. Ordinary timed
service/editor/production-worker and restarted replay journeys are qualified;
it does not advertise conditional DELETE in `RemoteCalendarAdapter`. Local
tombstone capture is described in
[local edit capture](LOCAL_EDIT_CAPTURE.md).

The first submission profile accepts an ordinary nonrecurring event with known-empty
mapped details and no custom event color. The retained tombstone content must equal
the complete projected base. Prior edits, transferred creation origins, reminders,
invitations and richer events remain held. The adapter decrypts and verifies the
current remote event using the existing Proton Core event verifier; mapped display
text alone is not a revision or signature check.

## Durable request and interruption rules

Each immutable request binds the account, calendar, generation, local deletion ID,
base event/revision, complete raw content, adapter formats and protected literal
request. Its deterministic local identity is not a server idempotency key.

The shared SQLite journal arbitrates title, text, time and deletion owners across
generations. It preserves the first prepared wire, commits `OUTCOME_UNKNOWN` before
transport, and retains the first available protected HTTP response independently of
continued write consent. Unknown work is never reset, replaced or automatically
resent. Corrupt or foreign history fails closed. Shared journal schema 9 adds
authenticated-absence results and immutable completion proofs without releasing
the event or request identity, including across generations.

A separate DELETE permit authorizes one exchange of the precise single-event
operation. CREATE and UPDATE permits cannot authorize it. Mixed tags, body/URL
rebinding, retries, redirects and interceptor reentry are refused. Cancellation
remains cancellation and cannot reopen a consumed request.

## Conflicts and observed absence

Before claim, a freshly authenticated exact-event read must match the retained
base and current membership/context. A verified different event is returned as a
conflict with its full mapped variant; no deletion is sent. An UNKNOWN request is
inspected without submission. An unchanged event still present is unresolved;
a changed verified event is a conflict.

Indexed success code 1000 alone is insufficient. Verification requires a newly
qualified exact-event lookup reporting structured Proton code 2501 for the bound
calendar and event, with membership/context rechecked afterward. A generic HTTP
404, missing list entry, failed scan, lost access, malformed reply, cancellation or
network error is not deletion evidence. Absence establishes current convergence,
not causal attribution, immutable remote history or a server lock. This scoped
2501 interpretation is qualified on controlled disposable ordinary timed and
all-day events.
The bounded parser
accepts only the exact authenticated event route, status 200/400/404/422 and the
expected structured error fields. Optional `Details` must be an empty object;
unknown keys, nonempty details and malformed or duplicate fields remain unresolved.

Core can convert a structured API error before Retrofit returns its response. A
separate single-use GET witness captures only the exact route and authenticated
session's response before that conversion. It preserves the original JSON, limits
both wire and gzip-expanded bodies, and leaves Core's stream untouched. Reentry,
foreign/ambiguous headers, malformed encodings and repeated consumption invalidate
the witness. An exception's parsed code alone cannot donate absence evidence.

The fresh GET and sync PUT are not atomic. An edit arriving between them can still
race with deletion; see [conditional writes](research/CONDITIONAL_WRITES.md).
Without trustworthy comparable modification timestamps, preserving observed
variants takes precedence over inventing last-modification-wins ordering.

## Worker, acknowledgement and incoming barrier

Deletion has independent default-off per-calendar consent. Disabling general
editing clears it. The worker captures original account/generation/policy leases,
uses separate bounded owner/inspection/offline cursors and never renews an expired
lease. Factual response retention survives sending-consent loss, but not logout or
account replacement. Local source admission runs under the import lock before the
authenticated adapter; callbacks never re-enter authentication or the import lock.

Import schema 22 reserves one immutable full-binding native receipt before the
network claim. Reservation is neither remote absence nor acknowledgement authority.
Only a durable verified result can authorize native acknowledgement. A matching
remote deletion echo may converge without creating a conflict; any different
verified upsert or deferred variant remains preserved as a visible conflict.

A dirty deletion can interrupt an incoming no-change projection. Its receipt may
coexist only when both its change and previous base equal the entire deletion
base, its import format/protocol match and both representations are historical
direct mode. The receipt stays intact through preparation and reservation. Only
verified native acknowledgement can retire this exact obligation by a full-tuple
SQL comparison in the same transaction as restoration. Changed or malformed
receipts remain held; a rollback retains both obligations.

Native acknowledgement conditionally changes only DIRTY and its exact receipt
marker on the unique, unchanged, ordinary deleted row. DELETED remains set, with
the original content and base revision. Android's Provider also clears its
[`MUTATORS` history](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarProvider2.java#4154)
when the sync adapter resets DIRTY; the bridge does not rewrite
event content to perform acknowledgement. The same atomic batch asserts the retained
post-write row: a Provider that purges it cannot qualify this protocol. A missing
row alone is never success. Historical inspection of the exact marker recovers a
lost reply without rewriting newer content, flags or children. Before the first
write, the reserved receipt can be retried only against the complete original
native source. SQL restoration and journal completion are idempotent.

The original local owner and baseline remain a permanent incoming barrier after
completion. Finished paused deletions disappear from actionable counts; conflicts
remain visible. Pending projection queues, requeues and old retained receipts
cannot recreate the event. A later native restoration or edit is preserved and
reported as conflict. Equal-valued remote recreation cannot be distinguished from
stale replay and stays held; no ordering is invented from opaque revisions.

## Qualification

Disposable ordinary timed, single-day and three-day events pass actual `foundation.e.calendar` deletion,
production worker RUN and fresh-process REPLAY with one immutable request and
exact restoration of the five target modes. A separate service journey passes
restarted inspection without another send, deferred ACK, new-process local
acknowledgement and replay. Physical isolated tests cover native conflicts,
lost replies, source/policy loss, SQL rollback and interrupted incoming projection.
All-day worker fixtures freeze the civil-date profile before creation, verify UTC
midnight and exclusive end bounds before editor deletion and after acknowledgement,
and retain the exact history through active fresh-process replay. Bounded test
admission accepts previous completed deletions only with full journal/result/
completion, exact SQL/native receipts and unchanged clean deleted rows and children;
unresolved unrelated work is never exempted. Production admission is unchanged.
A real simple-event preflight conflict also preserves the full authenticated
variant after an official-editor title save changed the event zone from `Z` to
`Europe/Paris` without changing its instants. The wire stays PREPARED, the local
owner becomes CONFLICT, and no DELETE claim, result, completion or native ACK is
allowed. Local evidence review and fresh-process offline replay preserve the
complete dossier and request; the modified service event remains available.
An additional disposable timed event passes actual editor deletion while offline,
production local-worker capture and fresh-process offline replay. Both offline
phases retain the same PAUSED owner, complete base/content and dirty deleted native
row, with no DELETE attempt or ACK. After reconnection, production RUN completes
the original retained owner's deletion and native/SQL acknowledgement. Active
fresh-process replay preserves the one attempt and exact request/result/completion
hashes. The five original modes are restored after every active phase. The fixture
requires no INTERNET-capable network throughout its offline phases; normal
constrained network work may remain queued. This qualifies disconnection before submission and
application-process restart, not interruption during/after claim, device reboot
or power loss.
A separate isolated real-service journey injects cancellation at evidence
persistence after the actual transport attempt and durable claim. It retains the
exact UNKNOWN request without a response capsule, result or native ACK. Repeated
new-process local recovery holds it without HTTP or resubmission, preserving the
first process witness. Restarted authenticated inspection then proves absence;
only its durable result permits subsequent new-process native/SQL ACK and the
permanent incoming barrier. Final fresh-process replay preserves the same request,
result and completion without a new send or native write. The checkpoint itself
is not proof of remote success.
This qualifies the evidence-persistence boundary, not a measured network loss or
production-worker interruption during transport. Device reboot, power loss,
every production-worker conflict path, atomic remote preconditions and Samsung/
One UI remain outside the measured scope.
Existing retained synthetic events must not be used as disposable fixtures.

The opt-in `NETWORK_INTERRUPTION` journey additionally measures a real socket
failure after disabling Wi-Fi at the authenticated request-body start. Its debug
listener binds the exact frozen wire, URL, session, tag/body and call; release
has no interruption listener. The fixture also cancels the first evidence
persistence, then runs `UNKNOWN_REPLAY` in two new processes while actually
offline. After network restoration, `INSPECT` may retain a present original event
as UNKNOWN with no new send, proof or native ACK. This measured outcome and another
local replay preserve the original request, pending receipt and dirty tombstone.
If authenticated inspection instead proves absence, only the normal separately
restarted ACK/REPLAY path may complete. That alternative is not implied by this
present-event result. The protected operator must restore the original Wi-Fi,
mobile-data and airplane settings in `finally`, including on failure; the fixture
requires a no-SIM, Wi-Fi-only target and changes only Wi-Fi. Partial body upload,
server acceptance followed by response loss, reboot and power loss remain separate
qualification cases. The production-worker radio-loss profile is described below.

The opt-in `deleteWorkerNetwork=true` profile extends the ordinary production
worker fixture for timed events. PREPARE, actual system-editor deletion and
offline capture/replay precede the one permitted NETWORK_RUN. A read-only
authenticated preparation preview binds the debug listener to the deterministic
raw DELETE wire; its protected capsule is never journalled, claimed or submitted.
The normal worker independently prepares, claims and attempts its own request.
Full calendar quiescence and exact retained-owner admission precede activation.

PASS: actual Wi-Fi loss at that authenticated request-body start cancels the
production transport. The first and settled observations agree on one matched
call/body start. This is distinct from the isolated natural socket-error result
above. No cancellation, response, exception, capsule or journal write is injected
by this fixture. The real journal keeps its original UNKNOWN request with no
response capsule, no verified result/completion and the original PENDING native
receipt; the dirty deleted row and complete owner/base remain unchanged.

The debug listener distinguishes natural socket failure from cancellation after
the completed radio cut, excluding pre-cancelled calls, timeout/interruption,
dispatch denial and foreign/repeated calls. The worker profile freezes the actual
evidence shape (`NONE` or `NO_RESPONSE`) and full record hash without repairing
or replacing either. WorkManager can stop a worker when a constraint disappears;
see [Android's constraint lifecycle](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).
The observed cancellation does not independently establish its stop reason.

PASS: two fresh-process offline worker activations retain the exact record,
evidence shape, original owner/base, native digest and pending receipt. Repetition
does not replace the first process witness. After network restoration, normal
workers are activated and a separate authenticated read-only diagnostic confirms
the original event PRESENT with unchanged qualified context. No new attempt,
result, completion or native write is created; another offline process preserves
that held state. The diagnostic does not prove that a worker GET was observed.
The alternative authenticated-absence completion path remains outside this
measured PRESENT outcome. Partial upload, server-accepted response loss,
process kill, reboot/power loss and OEM qualification remain open.

Detailed measured evidence is recorded in [validation](VALIDATION.md); passing fake
boundaries alone does not qualify the service, Provider, editor or WorkManager.

The opt-in isolated `PROCESS_INTERRUPTION` profile uses a fresh disposable event
and distinct native generation. All selected production calendars must have all
five effective write modes disabled and idle local write control before CREATE,
SEND and the process-stop checkpoint. An optional separately owned general-only
setting pause uses the ordinary production setter, durable before-write ownership
and explicit restoration; it never enables an editing submode or changes an
uncertain request. The system-editor utility qualifies only the frozen isolated
row and makes no direct Provider writes.

After the real adapter claims and returns a complete bound response, the fixture
opens its genuine protected capsule locally. Only REPORTED_SUCCESS,
REPORTED_FAILURE or UNCLASSIFIED can reach this checkpoint; NONE, NO_RESPONSE and
BINDING_INVALID cannot. It retains the original UNKNOWN/native hashes, receipt nonce,
response-capsule hash, closed response category and PID atomically, then kills its
actual process without delegating response persistence. No response body or capsule
is retained. READY alone is not death evidence: the protected host must observe
the terminated runner and absent target process before any recovery force-stop.

PASS: the measured response is UNCLASSIFIED, with reported-success explicitly
false. Two different restarted local processes retain the original UNKNOWN with
no capsule, result, completion or ACK and do not compose HTTP ports. Fresh
authenticated inspection then proves absence before separately restarted ACK
and replay. Request/result/completion history remains immutable, the exact
native tombstone is acknowledged and an old incoming upsert cannot recreate it.
The complete existing production DELETE history remains byte-identical, and
original effective settings are restored. This qualifies loss of a response's
durable evidence through real process death, not a reported-success envelope,
server attribution, network response loss, production-worker death or reboot.

The separate opt-in `deleteWorkerProcess=true` profile runs ordinary timed
deletion through normal production WorkManager scheduling. It requires the
offline capture/replay profile and excludes the radio-loss profile. A private
calendar-hash witness may select a different controlled calendar; it grants no
account access, consent or exemption from full SQL/native quiescence. Existing
uncertain work in another calendar remains untouched. The default selector keeps
the original dedicated QA calendar.

`PROCESS_RUN` makes only a genuine read-only preparation preview to enroll the
existing debug request listener; the preview is never claimed, journalled or
submitted. The production worker independently prepares, claims and sends. At
its authenticated request-body-start callback, local checks require one matching
call/body start, the complete original UNKNOWN with no response capsule/result/
completion, its exact PENDING receipt and native tombstone, compare-owned target
consent and disabled writes in other selected calendars. Exactly one network
WorkManager job must be RUNNING. The checkpoint atomically stores opaque hashes,
receipt and process/work witnesses, then kills the real application process.
It does not change a radio, inject cancellation or substitute transport/journals.

The protected host must observe terminated instrumentation and disappearance of
the original PID before any operator restart. Android may already have restarted
the app with another PID; this is recorded separately, never treated as failure
or assumed from READY alone. The host disconnects the enrolled Wi-Fi-only target
before controlled recovery. `PROCESS_OFFLINE_REPLAY` activates ordinary local
workers, preserves the exact request and first recovery witness and restores the
five target modes. `PROCESS_RECONNECT` uses normal workers; a separate read-only
diagnostic may establish PRESENT but never proves that a worker GET was observed.
PRESENT retains UNKNOWN without ACK; ABSENT still requires actual production
completion before success. Offline-only `PROCESS_RESTORE` restores compare-owned
settings after an interrupted test without dispatch or a recovery claim.

This checkpoint qualifies process death at authenticated body start. It cannot
establish partial upload, server acceptance, response loss or death later in
submission, proof retention or native acknowledgement.

Two further isolated real-service profiles cover local persistence gaps without
changing production WorkManager composition. `PROOF_PROCESS_INTERRUPTION`
delegates the real `retainVerified` commit before stopping the process, while the
fully bound native receipt remains PENDING and the row remains dirty/deleted.
The checkpoint does not pretend that SEND returned. A new-process local ACK uses
the retained authenticated-absence proof, then a separate replay checks immutable
request/result/completion hashes and the permanent incoming barrier.

`ACK_PROCESS_INTERRUPTION` completes ordinary submission with ACK deferred, then
stops after the actual Provider acknowledgement returns its exact receipt and
historical marker, before SQLite restoration. The native row is clean/deleted
while its SQL receipt is still PENDING and completion absent. `ACK_RECOVER` must
inspect that historical marker and finish SQL/completion with zero new native
writes. It cannot rewind the row, replace the nonce or recreate a request.

Both profiles use distinct disposable events, private journals and frozen native
generations. Before claiming or stopping, bounded waiting allows read-only
production maintenance to settle; the same account/permission/five-mode guard
still follows. Full native and durable bindings are reread after a checkpoint
wait. The protected host independently verifies process death. These are real
Proton/coordinator/SQLite/Provider boundaries, not production-worker, reboot or
power-loss qualification.

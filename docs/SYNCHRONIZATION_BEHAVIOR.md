# Synchronization behavior and capability gates

Status: proposed extension of the development baseline. The bridge-only scope,
bidirectional goal and independent per-calendar modes are confirmed requirements.
The policies below refine provisional choices; they are not implemented features
unless identified in [current status](STATUS.md). Execution plans remain private.

## Capability boundary

| Capability | Current behavior | Required behavior before advertising support |
| --- | --- | --- |
| Simple events | Read-only import of title, description, location, timed/all-day dates and optional event color. | Preserve identity, timezone/date semantics and color inheritance on refresh and restart. |
| Remote changes and deletion | Durable calendar feed obligations, individual refetch, explicit tombstones and retained unavailable mirrors; simple live mutation journey qualified. Advancing resets restart acquisition with a persistent history gap; absence never deletes. | Complete historical reconciliation and live reset/revocation qualification. |
| Coverage | Near-date priority, frozen traversal and persistent interval progress. Compatible fully processed observations plus a drained feed allow future-edge extension; uncertain states fall back to full scans. Legacy coverage stays unknown. | Broader live-volume qualification and per-interval projection/freshness attribution. No claim of an atomic snapshot. |
| Unsupported events | Safely identified events are deferred individually, with encrypted evidence and visible partial status. | Extend supported mappings and preserve the distinction from malformed envelopes or unavailable keys, which still block their page. |
| Recurrences | Native read-only masters, additional/excluded dates and single moved/cancelled exceptions; durable series dependencies and explicit limits. | Broader rule/OEM qualification and recurrence writes. See the supported subset in READ_ONLY_IMPORT.md. |
| Reminders and participants | Bounded personal DISPLAY reminders and verified read-only participants; durable replacement and settings refresh. | External-author trust integration, real invitation journeys and broad local notification qualification; no implicit invitation replies. |
| Local writes | No production capture, outgoing persistence or Proton dispatch. | Durable capture, supported-field preservation, reconciliation, effective rights and operation-specific service qualification. |

The first writable scope is existing calendars owned by the connected account,
with simple events and qualified fields. Calendar-container creation/deletion,
cross-calendar moves, recurrence writes, invitations and attendee replies are
separate capabilities. An Android application may still let a user attempt an
unsupported edit: retain it as blocked, explain why it was not uploaded and do
not silently discard fields or report remote success. Calendar-level permissions
cannot be assumed to restrict every field or operation in every client.

## Incoming observations

The implemented pipeline durably stages received pages and bootstrap material
under Proton Keystore protection before local decryption/mapping. Receipt and
application progress are distinct. Receipt alone does not mean a page is applied.
Already acquired data can resume with locally available keys; missing keys remain
an explicit blocked dependency. Staging storage is bounded and keys remain in
protected adapter storage. See [current import limits](READ_ONLY_IMPORT.md).

Event payloads, deletion evidence, retry records and the corresponding cursor
advance commit atomically. Projection remains a separate replayable stage.
An unsupported record may be skipped for processing only after its identity and
durable retry information are safe to retain; it remains a visible coverage gap.
Do not retain raw plaintext in diagnostics. The adapter owns protected opaque
payloads or a versioned refetch reference with an explicit recovery strategy.

A complete baseline and incremental cursor need a qualified handoff that catches
changes during loading. An expired cursor requests scoped reconciliation, not an
empty result. Missing rows, failed requests, partial catalogs, changed permissions
and departure from the loaded horizon are never deletion evidence. If recovery
cannot establish a remote deletion, retain the local row as stale/uncertain.
Whole-calendar removal or loss of membership pauses that scope and displays its
stale status; it is not an instruction to erase cached or pending user content.

Implemented preloading order: observe today ±30 days first, then fill the existing
90-days-past/365-days-future horizon in bounded windows. Per-page progress survives
restart through the cursor and received-page storage. A separate epoch-scoped register
persists interval receipt and local application, including known-prefix and terminal
evidence. Processing may leave deferred records; projection and unsupported counts
remain calendar-wide, with no per-interval projection claim. A fresh traversal retains
the last finished observation but discards interrupted descriptive progress.
Keep the existing four-page/100-event budgets until measurement changes them. Avoid eager
expansion of infinite recurrence rules. Extending coverage and evicting data are
different operations; automatic eviction is outside the initial policy.
The rolling planner queries newly entered intervals even when no delta arrives: an
unchanged event beyond the previous horizon would otherwise never be discovered.
Its compact observation and explicit query plan are durable. Reset, access loss,
unknown prefixes and incompatible zones/formats prevent unsafe reuse. Feed checking
remains a completion barrier after extension. Calendar-wide projection and deferred
counts remain separate from this record of queried dates.

## Durable Android changes

The provider and private database do not share a transaction. Capture a stable
local identity, base remote revision/snapshot, changed fields and a local edit
generation before scheduling an upload. Reconcile provider dirty/deleted state
on restart and periodically; an observer only accelerates discovery. Qualify the
target provider's tombstone and sync-adapter behavior before enabling deletion.
The [Android provider documentation](https://developer.android.com/identity/providers/calendar-provider)
and [event columns](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns)
are integration references, not evidence of tested client behavior.

Acknowledging capture or projection must compare the exact observed generation
or snapshot. If another Android edit occurred, preserve its dirty state and new
intent. Incoming projection must capture/protect pending edits before writing,
including a change made while the bridge process was absent. A successful upload
does not consume a newer local edit or establish a provider mapping by itself.

Multiple unsent edits may be coalesced while retaining the original base and
newest user state. A never-dispatched create followed by delete can cancel locally.
Once dispatch was admitted, preserve operation identity and reconcile its outcome
before coalescing, cancelling or issuing a dependent operation.

## Remote mutation lifecycle

1. Persist intent and its adapter format before scheduling work.
2. Check mode, calendar membership rights, event rights/organizer role, usable
   keys, supported operation/fields and conflict state at
   admission. Persist uncertain outcome before any possibly effective request.
3. Dispatch through the Proton adapter using official crypto/serialization reuse.
   Update a preserved full remote representation; `EventContent` alone is not
   enough to construct a safe replacement payload.
4. Store acknowledgement or a typed blocked/uncertain result. Reconcile remote
   identity, contents and Android mapping before retiring that intent.
5. Keep newer local generations pending. Recover each step after process death.

Treat every operation's response independently; transport success alone does not
prove all operations succeeded. The initial implementation should send one intent
per request. Future batching must preserve individual outcomes and cannot assume
atomicity across operations or calendars.

The local content hash currently used as a revision is not a server-side write
precondition. A read followed by a write cannot prevent an intervening remote
change. UPDATE/DELETE remain disabled until an atomic condition or a separately
reviewed conflict strategy with explicitly accepted guarantees is qualified.
CREATE also needs a qualified way to identify an accepted request after response
loss. A client UID is only a correlation candidate until its service semantics
are proven; do not assume uniqueness or retry idempotency.

On an ambiguous result, retain the intent as outcome unknown and reconcile with
bounded retries. If evidence cannot resolve it, show a blocked operation; do not
repeat a potentially accepted create. A definitive rejection is distinct from
an unknown outcome. Throttling follows server delay; authentication/key/rights
errors require recovery, not an unbounded immediate retry loop.

## Conflict and transition policy (proposed)

| Situation | Safe default and acceptance condition |
| --- | --- |
| Offline local edit plus remote edit | Preserve base, local and remote variants; block upload. No last-write-wins or automatic merge in the first writable slice. |
| Remote delete plus local edit | Keep the local draft; do not resurrect the event. A separate explicit decision may create a new event after CREATE qualification. |
| Local delete plus remote edit | Preserve the remote variant and deletion intent; do not delete the newer remote version. |
| Lost create/update/delete acknowledgement | Resolve via qualified remote evidence or retain unknown outcome; no blind retry and no early success. |
| New local edit during upload | Finish reconciliation of the admitted operation and retain the newer edit against its appropriate base. |
| Switch to read-only/pause or lose rights | Block undispatched work; reconcile admitted work. Re-enabling does not automatically replay suspended edits. |
| Auth expiry or key rotation | Retain intent; restore/rebootstrap the same identity before continuing. Never reuse another account's keys or cursor. |
| Logout or calendar access loss | Stop admission; preserve pending work and identify stale cached copies. No silent purge or cross-account replay. |
| Unsupported edit or missing unknown fields | Keep the user's draft blocked, keep the adapter's original representation, and explain the unsupported capability. |
| Storage full/process death | No progress acknowledgement without durable commit; replay only the recorded stage. |

Resolution belongs in a small synchronization-issue screen, not a new event
editor. Proposed actions: retain the Proton variant and abandon the captured
local intent; or explicitly submit the retained local variant against a freshly
qualified base. Both require the supported operation's concurrency guarantees.
Changed remote state requires resolution again. No resolution action may erase
a newer Android edit. Sensitive comparisons remain on-device and out of logs.

Before writable mode ships, the issue screen must also handle retained operations
after logout/mode changes and allow an explicit local removal/retention decision.
Logout stops authentication and dispatch immediately; it need not wait for the
network. Cached-copy retention is visible to the user. Exact resolution and purge
UX remain product decisions. Unsupported capabilities remain unavailable.

## Verification gates

- Pure contracts: two adapter implementations share the same accepted behavior
  tests; migration of opaque state/pending work never silently resets data.
- Storage/provider integration: crash injection at every acknowledgement boundary,
  stale-generation races, dirty/tombstone capture and replay without duplicates.
- Proton qualification: synthetic owned test events prove round trip, unknown-field
  preservation, concurrent changes, rejection and accepted-but-unobserved outcomes.
  Fault injection must cover post-acceptance response loss, not only pre-send loss.
- Device journeys: create/edit/delete in the actual calendar app, offline restart,
  permissions revoked during work, background scheduling and mode changes.
  Provider assertions do not establish Samsung Calendar UI compatibility.

See [acceptance](ACCEPTANCE.md) for stable scenario IDs and
[specification](SPECIFICATION.md) for confirmed requirements and open scope.

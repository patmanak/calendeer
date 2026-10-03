# Behavioral acceptance and delivery gates

This is a reusable acceptance plan, not an execution record. See
[specification](SPECIFICATION.md) for confirmed requirements and provisional
policies. JVM/fake evidence must not be presented as Android or Proton evidence.

## Configuration foundation (CAL-02, CAL-10, CAL-11)

- Fresh installation defaults to system language/theme and contains no invented
  account or event. Connection unavailability is explicit before integration.
- Each of en/fr/de/es/it/nl/pl/pt is selectable offline. Its choice persists across
  process restart. System selection clears the override and follows Android's
  supported locale order; unsupported languages fall back to English.
- Light and dark override system changes. System mode follows the current
  system theme; settings and connection screens remain readable and accessible.
- Changing language or theme neither mutates account/calendar data nor changes
  the theme/language of an external calendar app. Recreation does not lose sync intent.

## Authentication (CAL-03, CAL-04, CAL-09)

- State transitions cover initial restore, credentials, code, separate mailbox
  password/key unlock, typed rejection, cancellation and retry delay.
- A rejected/duplicate/stale submission cannot retain mutable secret input or
  restore a previously cancelled session. Saved UI state contains no passwords.
- Invalid credentials and an unsupported challenge remain errors; they cannot
  become an authenticated account or a ready Calendar session.
- Successful account authentication is distinct from Calendar bootstrap. Do not
  expose calendar operations until the selected adapter establishes the required
  access/keys. Verify expiry, refresh, account isolation and revoked-session recovery.
- Preserve the restricted human-verification origin/frame boundary when integrating
  the actual Contako/Proton flow. No permissive fallback for missing WebView support.
- The configuration screen retains the controller through activity recreation,
  clears input fields on backgrounding and prevents capture of secret-bearing
  surfaces. A cleanup failure keeps new submissions blocked until retry succeeds.
- Require Android Keystore-backed session protection; absence of a usable key must
  not open a plaintext fallback store. Account backup remains disabled.
- Local sign-out clears only this bridge's authentication state; it must not alter
  credentials, account security settings or another client's session. Calendar
  cache/queued-change retention needs its separate policy before sync is connected.

## Synchronization engine (CAL-03, CAL-05, CAL-06, CAL-07, CAL-08)

- Import a known multipage fixture with overlaps and replayed pages. Final remote
  identities are unique; pending local changes and unrelated calendars remain intact.
- A page transaction either persists all records/checkpoint/projection intents or
  none. Termination at each boundary resumes safely using the stored checkpoint.
- Page/event budgets cap each run. Detect invalid scope/adapter version and
  non-progressing cursors; do not spin forever or advance on a malformed page.
- Concurrent triggers for one account serialize; different accounts cannot share
  opaque state. Checkpoint invalidation cannot silently discard pending operations.
- Read-only mode, revoked rights, unsupported data or unresolved conflict prevent
  dispatch. The guards apply in the engine, regardless of UI editability.
- An adapter with no declared mutation capabilities blocks every queued mutation
  even when cached access grants all operations. CREATE-only capability cannot
  admit UPDATE/DELETE against a matching cached base. Capability withdrawal blocks
  admission; restoration never requeues blocked or uncertain work.
- Persist an uncertain outcome before sending a mutation. If the response is lost,
  a later trigger does not duplicate the write; require reconciliation.
- Incomplete pages/coverage never imply remote deletion. Explicit remote deletion
  and local projection cleanup are distinct from cache eviction.

## Android storage and provider integration

- Apply [CAL-12 permission behavior](PERMISSIONS.md): recheck at launch/foreground
  return and before each provider operation, contextual requests, refusal and
  revocation without losing queued work. Calendar permission prompts remain absent
  while only authentication and preferences are available.

- A durable production store implements the transaction contract across process
  death, with schema/version migrations and protected storage policy.
- Provider writes preserve remote-to-local mappings, account ownership and dirty
  change capture. A crash after applying a provider write replays without duplicates
  or echo uploads. Read-only flags are checked in target calendar applications.
- Single timed event, all-day event and a DST-boundary fixture retain their
  semantics. Recurrence is qualified separately, including a master outside the
  cached horizon and a modified/deleted occurrence inside it.
- External offline create/edit/delete survives process restart, reconnects and
  reconciles under the selected policy. UI state never claims remote success early.
- Permission loss, account loss and mode changes retain pending work and stop
  unauthorized writes. Tests must include changes occurring during an in-flight request.

## Extended synchronization scenarios

These gates refine CAL-01/03/05/06/07/08/12 and RES-01 through RES-10;
they are not an execution record. See the proposed
[behavior and capability boundaries](SYNCHRONIZATION_BEHAVIOR.md).

| ID | Observable acceptance |
| --- | --- |
| SYNC-01 | Given supported/incompatible/supported records in one page, safe per-record isolation preserves both supported records and a durable visible retry gap. An untrusted envelope still blocks cursor advancement. |
| SYNC-02 | Given changes during baseline loading, the baseline/delta handoff observes them; an expired cursor recovers without interpreting missing records as deletion. |
| SYNC-03 | Given an authoritative remote deletion, only its owned local projection is removed; lost calendar access retains cached/pending content with a stale/blocked status. |
| SYNC-04 | Given interrupted preloading, observed intervals and gaps survive restart and near-term data becomes usable before the full horizon. Completion never hides unsupported content. |
| SYNC-05 | Given a local edit while capture/upload acknowledgement is pending, the older acknowledgement cannot clear the newer dirty state or intent; incoming projection cannot overwrite either pending variant. |
| SYNC-06 | Given a create accepted remotely with its response suppressed, recovery resolves its stable identity without duplicate creation, or explicitly blocks unknown outcome without replay. |
| SYNC-07 | Given a remote change after preflight read but before UPDATE/DELETE, the qualified concurrency strategy prevents a silent overwrite/deletion. A local hash comparison alone cannot pass this gate. |
| SYNC-08 | Given a supported field edit on a qualified event containing other properties, the round trip preserves those properties; an unsafe mapping blocks the operation without discarding the local draft. |
| SYNC-09 | Given a conflict resolution or mode transition, a new remote/local generation invalidates stale decisions; in-flight outcomes reconcile and undispatched work remains retained. |
| SYNC-10 | Given restart after any durable/provider/network boundary, recovery creates no echo upload, duplicate or cross-account replay, and does not claim success before reconciliation. |
| SYNC-11 | Given received encrypted pages and already acquired usable keys, termination before decryption followed by offline restart resumes mapping/projection without a network request; missing keys remain an explicit dependency. Receipt alone does not advance application progress. |
| SYNC-12 | Given an unchanged event outside the current horizon, advancing the date loads the newly entered interval without requiring a remote change; the old interval is not silently purged. |

## Live release boundary

Use a dedicated controlled account/device and independently inspect both peers.
Measure data coverage, foreground usability, background delay, battery/network
usage and behavior on the intended Android/OEM apps. Qualify all advertised
features; keep unsupported capabilities read-only or unavailable explicitly.
No device/Proton qualification is implied by an APK build or by passing JVM tests.

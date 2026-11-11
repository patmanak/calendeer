# Implementation status

Calendeer bridges Proton Calendar and Android's Calendar Provider. Viewing,
editing and reminders belong to calendar clients; the bridge has no event editor
or reminder notifications. This is current status, not an execution log.

## Current milestone — 0.1 for F-Droid

Scope: incoming synchronization, simple-event editing through personal-v2,
safe migration and retirement of legacy title/text/time paths, hourly incoming
refresh. Recurrence editing, reminder/guest changes and new outgoing families
are outside this milestone. Read-only remains the default per calendar.

Scheduling: a thin calendar SyncAdapter on the existing account supports uploads,
manual refresh and Android periodic sync. It awaits bounded WorkManager execution
and reports its outcome; synchronization logic remains in the existing workers.
Android replaces the six-hour WorkManager periodic trigger: 15 min / 30 min /
1 h default / 2 h / 6 h. Automatic work respects account and master sync settings.
The URI watcher remains temporarily, subject to the same automatic-sync policy;
retire it only after Android-triggered upload is qualified in E4.

Exit: working scope, one grouped physical-device/live-test-account E4 run
(closed-app local edit, refresh from two calendar clients, offline/reconnect,
reboot, permission revocation, Samsung deep sleep with/without exemption,
auto-sync OFF), green remote CI and signing decision. LineageOS results do not
qualify One UI. Signature management remains deferred by the owner.

## Current capabilities

- Proton login and retained sessions, mailbox/second-factor/hosted verification;
  settings display the connected account. Security-key-only login and all account
  variants are not qualified. [Authentication](research/AUTH_INTEGRATION.md).
- Violet deer interface, eight languages, light/dark/system themes, calendar
  selection and per-operation experimental consent. Sync settings expose Android
  switches, the last successful cycle, interval and an optional one-time battery
  offer. [Design](DESIGN.md), [permissions](PERMISSIONS.md).
- Incoming data uses bounded sealed staging, offline decoding, retained keys,
  opaque cursors/change feeds and rolling coverage. Dirty unsupported events hold
  their own projection, not unrelated pages. Colors preserve supported event
  colors with calendar fallback. [Import](READ_ONLY_IMPORT.md).
- Simple CREATE, personal-v2 combined title/text/date edits and DELETE use retained
  intent identity, guarded preparation/submission/readback and exact native/SQL
  acknowledgement. Timed/all-day events and qualified zone changes are supported.
  Existing title/text/time paths remain live pending safe migration to personal-v2.
  [Personal edits](PERSONAL_UPDATE.md), [deletion](DELETE_SUBMISSION.md).
- UNKNOWN is inspect-only, never reset/resend. Unsupported local changes and
  conflicts retain their variants. Native permission/account/generation and consent
  are rechecked; a system sync request cannot lift a paused CREATE.
  [Scheduling](SYNC_SCHEDULING.md), [conflicts](CONFLICT_ARCHIVE.md).
- Domain policy stays independent of Android rows and Proton HTTP/SDK/crypto types.
  Maintained Proton primitives have recorded provenance and license notices.

## Evidence and remaining gates

Existing evidence is frozen in [validation](VALIDATION.md); detailed new runs and
operator notes stay ignored locally. Focused JVM and instrumented contract tests
cover the system relay; they do not replace E4 client/OEM/live-service journeys.
Android batching, periodic intervals and battery exemption promise no deadline.

F-Droid preparation includes English/French metadata and a disabled recipe. Proton
crypto/SRP JNI is rebuilt from pinned source; independent Linux artifacts matched,
API/ABI/alignment/license checks passed, and API35 retained-session crypto and
isolated fresh password login were tested. Remote CI, canonical release/update
installation, other ABIs/OEMs and signing remain release gates.
[Distribution](LICENSING.md), [build security](BUILD_SECURITY.md).

Still required: legacy-family migration, milestone qualification, explicit handling
of unresolved historical associations/conflicts, remote CI and signed packaging.
Trustworthy comparable modification timestamps remain unavailable for general
last-writer-wins. GET then PUT is not atomic; concurrent changes can be overwritten.
[Conditional writes](research/CONDITIONAL_WRITES.md).

Work uses risk-based E1–E4 evidence and bounded batches. See [workflow](WORKFLOW.md),
[architecture](ARCHITECTURE.md) and [requirements](SPECIFICATION.md).

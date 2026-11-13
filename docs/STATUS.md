# Implementation status

Calendeer bridges Proton Calendar and Android's Calendar Provider. Viewing,
editing and reminders belong to calendar clients; the bridge has no event editor
or reminder notifications. This is current status, not an execution log.

Application ID: `com.patmanak.calendeer`; native account type:
`com.patmanak.calendeer.account`. This installs separately from earlier development
builds and needs a new sign-in; their sessions/data are not transferred or deleted.

## Current milestone — 0.1 for F-Droid

Scope: incoming synchronization, simple-event editing through personal-v2,
safe migration and retirement of legacy title/text/time paths, hourly refresh,
and V05 reminder/color/series/occurrence/guest editing. These are release targets;
current outgoing capability remains narrower. Read-only is the default. See the
[accepted specification](SPECIFICATION.md). Guests may be deferred only by the owner.

Scheduling follows the DAVx5 model: the thin calendar SyncAdapter accepts Android
change/manual triggers, enqueues expedited WorkManager work and waits at most ten
minutes without cancelling it. Android always receives success; WorkManager owns
retry and the app owns actual status. The Android 14+ pending latch is cleared.
One network-constrained WorkManager periodic synchronization runs at 15 / 30 / 60 default /
120 / 360 minutes; native Android periodic syncs are removed. Android automatic
switches control push only. App actions are manual and bypass those switches;
periodic sending precedes receiving when push is allowed; receiving continues
alone while push is OFF. Manual requests coalesce with KEEP. The URI watcher remains temporarily;
retire it only after Android-triggered upload is qualified in E4.

Exit: working scope, one grouped physical-device/live-test-account E4 run
(closed-app local edit, Refresh from /e/OS Calendar and Etar, Google Calendar if
possible, offline/reconnect, reboot, permission revocation, prolonged idle
with/without exemption and auto-sync OFF), green remote CI and signing decision.
Samsung/One UI is outside 0.1. Signature management remains deferred by the owner.

## Current capabilities

- Proton login and retained sessions, mailbox/second-factor/hosted verification;
  settings display the connected account. Security-key-only login and all account
  variants are not qualified. [Authentication](research/AUTH_INTEGRATION.md).
- Violet deer interface, eight languages, light/dark/system themes, calendar
  selection and one Read-only / Editable mode per calendar. Previous partial write
  choices migrate to Read-only with an explanation; complete consent stays editable.
  Sign-out requires OK / Cancel, warns about unsynchronized changes and removes
  owned native calendars before clearing the session; failed cleanup is retryable.
  Existing work and uncertain outcomes are retained. Sync settings expose Android
  switches, the last successful cycle, interval and an optional one-time battery
  offer. Disabled push is explained; manual requests receive visible
  feedback. [Design](DESIGN.md), [permissions](PERMISSIONS.md).
- Incoming data uses bounded sealed staging, offline decoding, retained keys,
  opaque cursors/change feeds and rolling coverage. Dirty unsupported events hold
  their own projection, not unrelated pages. Colors preserve supported event
  colors with calendar fallback. [Import](READ_ONLY_IMPORT.md).
- Simple CREATE, personal-v2 combined title/text/date edits and DELETE use retained
  intent identity, guarded preparation/submission/readback and exact native/SQL
  acknowledgement. Timed/all-day events and qualified zone changes are supported.
  Fresh eligible title/text/date edits use personal-v2. Legacy writers are retired;
  retained unsupported work is visible, while historical UNKNOWN inspection and
  proven acknowledgements keep their original identities and formats.
  [Personal edits](PERSONAL_UPDATE.md), [deletion](DELETE_SUBMISSION.md).
- Imported and transferred events also support personal reminder/color edits, including
  removal and calendar-color fallback, while retaining Proton email reminders and
  default inheritance when alerts are unchanged. Changed inherited alerts freeze default emails.
  Initial CREATE also captures reminders/color using a separate personal-v3 preparation
  and submission profile. Historical formats stay unchanged; client journeys remain an E4 gate.
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

Still required: V05 editing, milestone qualification,
unresolved historical associations/conflicts, remote CI and signed packaging.
Trustworthy comparable modification timestamps remain unavailable for general
last-writer-wins. GET then PUT is not atomic; concurrent changes can be overwritten.
[Conditional writes](research/CONDITIONAL_WRITES.md).

Work uses risk-based E1–E4 evidence and bounded batches. See [workflow](WORKFLOW.md),
[architecture](ARCHITECTURE.md) and [requirements](SPECIFICATION.md).

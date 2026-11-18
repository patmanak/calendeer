# Implementation status

Calendeer syncs Proton events into the Android Calendar Provider. Calendar clients handle editing and reminders.

Application ID: `com.patmanak.calendeer`; native account type:
`com.patmanak.calendeer.account`. This installs separately from earlier development
builds and needs a new sign-in; their sessions/data are not transferred or deleted.

## Current milestone — 0.1 for F-Droid

Scope: incoming synchronization, simple-event editing through personal-v2,
safe migration and retirement of legacy title/text/time paths, hourly refresh,
and V05 reminder/color/series/occurrence/guest editing. These are release targets;
current outgoing capability remains narrower. Read-only is the default. See the
[accepted specification](SPECIFICATION.md). Guests may be deferred only by the owner.

SyncAdapter relays Android requests to WorkManager. Periodic work runs hourly by
default. Android’s automatic sync switches control uploads. Manual requests from
Calendeer coalesce and can run with those switches off. The URI watcher remains
until Android-triggered uploads pass the grouped device trials.
[Scheduling and intervals](SYNC_SCHEDULING.md)

Candidate qualification covers closed-app edits, client refresh in /e/OS Calendar
and Etar, offline recovery, reboot, permissions, idle and sync switches. Samsung
One UI is outside 0.1. F-Droid submission follows several weeks of daily use,
maintainer approval and the [distribution checks](FDROID.md).

## Current capabilities

- Proton login and retained sessions, mailbox/second-factor/hosted verification;
  settings display the connected account. Security-key-only login and all account
  variants are not qualified. [Authentication](research/AUTH_INTEGRATION.md).
- Violet deer interface, eight languages, light/dark/system themes, calendar
  selection and one Read-only / Editable mode per calendar. Previous partial write
  choices migrate to Read-only with an explanation; complete consent stays editable.
  Sign-out requires confirmation, reads retained work on opening and clears
  the session even when native calendar removal must wait. Removal is durable,
  resumes when access returns and blocks mirror recreation until completed.
  Existing work and uncertain outcomes are retained. Sync settings expose Android
  switches, the last successful cycle, interval and an optional one-time battery
  offer. Disabled push is explained; manual requests receive visible
  feedback. [Design](DESIGN.md), [permissions](PERMISSIONS.md).
- Incoming data uses bounded sealed staging, offline decoding, retained keys,
  opaque cursors/change feeds and rolling coverage. Dirty unsupported events hold
  their own projection, not unrelated pages. Colors follow each event by default,
  or the calendar through a global offline display preference that preserves
  Proton colors and retained work. [Import](READ_ONLY_IMPORT.md).
- Simple CREATE, personal-v2 combined title/text/date edits and DELETE use retained
  intent identity, guarded preparation/submission/readback and exact native/SQL
  acknowledgement. Timed/all-day events and qualified zone changes are supported.
  Fresh eligible simple title/text/date edits use personal-v2. Legacy writers are retired;
  retained unsupported work is visible, while historical UNKNOWN inspection and
  proven acknowledgements keep their original identities and formats.
  [Personal edits](PERSONAL_UPDATE.md), [deletion](DELETE_SUBMISSION.md).
- Imported and transferred events also support personal reminder/Proton-palette color edits, including
  removal and calendar-color fallback. Compatible editors can read the account palette; Proton email reminders
  and unchanged alert inheritance are preserved. Changed inherited alerts freeze default emails.
  A bounded signed calendar part preserves supported status/transparency and Web fragment framing.
  Initial CREATE captures reminders/color with personal-v3. Finite DAILY CREATE (COUNT 2–366, INTERVAL 1–365) uses
  capsule v4 and fingerprint v2, with a guarded native profile chosen before reservation.
  Whole finite DAILY personal masters without exceptions use series-v3 from ordinary/transferred capture through crypto, readback and native/SQL ACK.
- The first detached occurrence can pass through the worker, signed readback, Android
  acknowledgement and transfer in instrumented tests. The production Proton port keeps
  this capability disabled. Compatible master updates preserve a newer local child edit.
  Removal planning checks signed family members and preserves existing exclusions.
  Whole-series deletion can prepare a sealed batch and inspect each member after an
  uncertain response. Series deletion still needs a worker and native acknowledgement. [Occurrence support](research/OCCURRENCE_WRITES.md).
- Organizer guest changes have encrypted REQUEST/CANCEL preparation for one internal
  recipient. Instrumented tests decrypt and verify both attachments. Separate durable
  claims keep an interrupted Calendar edit from replaying Mail. The real Mail and
  Calendar transports and end-to-end recipient delivery still need qualification. [Guest changes](research/GUEST_WRITES.md).
- UNKNOWN is inspect-only, never reset/resend. Unsupported local changes and
  conflicts retain their variants. Native permission/account/generation and consent
  are rechecked; a system sync request cannot lift a paused CREATE.
  [Scheduling](SYNC_SCHEDULING.md), [conflicts](CONFLICT_ARCHIVE.md).
- Domain policy stays independent of Android rows and Proton HTTP/SDK/crypto types.
  Maintained Proton primitives have recorded provenance and license notices.

## Evidence and remaining gates

[Earlier validation](VALIDATION.md) covers a specific development baseline.
Physical client and live-service trials remain part of candidate qualification.

F-Droid preparation includes English/French metadata and a disabled recipe. Proton
crypto/SRP JNI is rebuilt from pinned source; independent Linux artifacts matched,
API/ABI/alignment/license checks passed, and API35 retained-session crypto and
isolated fresh password login were tested. CI on the release revision, canonical signed update
installation and release signing still need qualification.
[Distribution](LICENSING.md), [build security](BUILD_SECURITY.md).

Still required: remaining V05 recurrence/occurrence/guest editing
([recurrence plan](research/RECURRENCE_WRITES.md)), milestone qualification,
unresolved historical associations/conflicts, CI on the candidate revision and signed packaging.
Trustworthy comparable modification timestamps remain unavailable for general
last-writer-wins. GET then PUT is not atomic; concurrent changes can be overwritten.
[Conditional writes](research/CONDITIONAL_WRITES.md).

[Working method](WORKFLOW.md) · [Architecture](ARCHITECTURE.md) · [Requirements](SPECIFICATION.md)

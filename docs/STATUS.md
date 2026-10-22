# Implementation status

This is the compact entry point, not an execution history. Read only the linked
contract relevant to the task. Existing detailed evidence is in
[validation](VALIDATION.md); run-specific notes remain ignored under `.local/`.

## Product and boundaries

Calendeer bridges Proton Calendar and Android's Calendar Provider. Event viewing
and editing stay in system calendar applications. Read-only is the default;
creation, title-only, description/location, same-kind date changes and deletion have separate
experimental per-calendar options. See [requirements](SPECIFICATION.md),
[architecture](ARCHITECTURE.md) and [acceptance](ACCEPTANCE.md).

Domain policy is independent of Proton HTTP/SDK and native rows. Proton Core
handles cryptography behind replaceable adapters; copied source has recorded
provenance and licenses. Opaque adapter state is versioned. Unsupported data and
uncertain writes are retained, never silently discarded or resent.

## Implemented behavior

| Area | Current behavior | Boundary/reference |
| --- | --- | --- |
| Connection | Real Proton login and session restoration, cleanup/cancellation gates, supported second-factor/mailbox unlock and hosted verification. Settings show the local connected username, with email fallback. | [Authentication](research/AUTH_INTEGRATION.md); security-key-only login and all account variants are not qualified. |
| Interface | Violet deer identity; separate calendar/settings/login screens; eight languages and light/dark/system themes. Calendar options expose pending work, conflicts, independent write consent and the concurrent-write warning. The bridge has no event editor or reminder notifications. | [Design](DESIGN.md), [permissions](PERMISSIONS.md). System calendar apps deliver local reminders. |
| Incoming sync | Scoped mirrors; paginated sealed staging, offline decode with retained keys, durable coverage and change-feed reconciliation. Near-term dates load first, then the wider horizon; compatible scans extend newly entered dates. | [Import](READ_ONLY_IMPORT.md), [behavior](SYNCHRONIZATION_BEHAVIOR.md). Gaps/deferred content stay visible; missing records are not deletion evidence. |
| Incoming contents | Supported timed/all-day events, exclusive all-day end, text, event colors with calendar fallback, supported personal display reminders and read-only participants. Supported recurrence projection and exceptions have dedicated mapping rules. | [Recurrence](research/ANDROID_RECURRENCE.md), [projection](PROJECTION_REPRESENTATION.md). Unsupported/untrusted content is deferred. |
| Outgoing CREATE | System editor discovery, immutable offline capture, guarded Proton submission/readback, same-row acknowledgement and durable handoff. | [Local capture](LOCAL_CREATE_CAPTURE.md). Simple events only; reminders, guests, recurrence and custom colors remain outside the send profile. |
| Outgoing title | Default-off option for title-only edits of qualified ordinary or fully handed-off created timed/all-day events. Durable request ownership, inspection and native/SQL completion. | [Title submission](TITLE_UPDATE_SUBMISSION.md). Known-empty details, no recurrence/color; other fields must stay unchanged. |
| Outgoing text | Default-off description/location option, optionally with simultaneous title change. Separate capsules, wire/proofs, cursors and consent; raw CRLF intent is distinct from canonical LF readback. | [Text delivery](SHARED_TEXT_PREPARATION.md). Creation mode must be enabled; narrow simple-event profile, unchanged dates and metadata. |
| Outgoing time | Default-off date/duration option for simple timed/all-day events, retaining kind, zone and all other fields. Protected preparation, guarded readback, separate durable history and native completion. | [Temporal delivery](TEMPORAL_UPDATE.md). All-day metadata uses UTC midnight and exclusive end dates. Kind/zone transitions and combined edits remain unsupported. |
| Outgoing DELETE | Default-off option with separate worker/consent, protected first request, authenticated inspection, durable results/completion and exact native/SQL ACK. Shared schema 9 and import schema 22 retain a permanent incoming barrier. | [Deletion submission](DELETE_SUBMISSION.md). Ordinary timed and one/multiple-day service/editor/production-worker RUN and restarted REPLAY pass. Timed offline capture/restart and reconnect completion pass. A controlled real preclaim conflict and offline replay retain the full variant. Simple imported events only; uncertain results never trigger another send. |
| Recovery/conflicts | UNKNOWN is inspect-only. Native writes are guarded and receipts precede acknowledgement; newer local edits survive. A shared title/text/time/deletion journal prevents competing owners across generations. Local conflict dossiers preserve observed variants. | [Capture](LOCAL_EDIT_CAPTURE.md), [conflicts](CONFLICT_ARCHIVE.md). Trustworthy edit timestamps and general conflict resolution remain unfinished. |

A refresh with no checkpoint commits its first staged page before draining queued
projections, so a dirty native edit cannot prevent its own capture indefinitely.
That page counts toward the four-page budget. Offline bootstrap consumes only an
already retained page. A different remote observation still preserves a conflict.

An unsupported dirty native event holds only its own incoming projection and
affected recurrence dependencies. Other events/pages continue; held work stays
visible as incomplete and is reobserved on a fresh refresh without clearing
native flags or historical receipts. Completed text/time/deletion proofs remain
retained outside the active staging budget. New local owners rearm only outgoing
discovery before capture, including later native pages and restarted continuations;
existing/uncertain owners never gain a new send or inspection attempt from this wake.

Synchronization attention offers Retry through the existing workers and expandable
explanations; it does not change consent or reset requests. Closed `CalendeerSync`
stage codes identify the reporting branch without event/account contents.
A stamped creation without its durable work identity stays held; do not repair it
by adopting a native row ID or allocating a new send identity.

## Evidence and remaining RC work

Real controlled-account journeys qualify login/import, simple CREATE, title edits,
ordinary timed text edits and same-kind timed/all-day date edits through authenticated
ports. Seven temporal service profiles cover one/multiple civil days, Paris and
New York DST, Lord Howe half-hour transitions and Kathmandu. Time also has
production Provider/worker RUN and active restarted REPLAY for all seven profiles,
with immutable history and no new attempt. Exact dates and scope are in the temporal
contract. Text also has an actual system-editor/production-worker journey,
interrupted-edit recovery and active restart replay without another attempt.
Four actual time-editor RUN/restarted REPLAY cases also pass: single/multiple
all-day dates and Paris autumn/spring durations. The measured time editor is
`foundation.e.calendar` 1.0.47 on Android API 35; Samsung remains unqualified.
Deletion also has actual system-editor/production-worker RUN and fresh-process
REPLAY on disposable ordinary timed, single-day and three-day events, with exact native acknowledgement,
immutable history and five-mode restoration. Separate real-service inspection,
new-process local ACK and replay pass. An interrupted identical incoming projection
is retained until native ACK and cannot resurrect the deletion. A controlled real
preflight conflict retains the full observed variant, including an official-editor
zone change with unchanged instants; local review and fresh-process offline
replay preserve its unclaimed request and complete dossier.
An additional timed deletion passes actual offline local-worker capture and
fresh-process offline replay without any DELETE attempt or ACK. Reconnection
resumes the same retained owner through production submission and native/SQL ACK.
Active fresh-process replay retains one attempt and identical request/result/
completion hashes. All five original modes and network settings are restored.
This qualifies pre-submission disconnection and process restart, not device reboot
or power loss.
An isolated real-service cancellation at response-evidence persistence also keeps
the original UNKNOWN request without a response capsule or ACK. Repeated local
restart recovery has no HTTP ports; fresh authenticated inspection proves absence
before a separate-process native/SQL ACK. This is not a measured network failure
or production-worker interruption during transport. Final restarted replay keeps
the identical request/result/completion without a new send or native write.

An isolated authenticated DELETE also passes actual Wi-Fi loss at the start of
its HTTP body, followed by a natural socket failure and two fresh-process offline
UNKNOWN recoveries. After reconnection, authenticated inspection finds the original
event still present and safely holds the unchanged request without resend or ACK;
another local replay preserves it. Network settings are restored. This uses the
evidence-persistence cancellation checkpoint too; production-worker transport loss
and server-accepted/lost-response interruptions remain unqualified.

Current host suites pass 427 domain, 20 authentication, 852 backend and 199 app JVM cases without
skips. Physical crypto, SQL/migration, Provider, worker/recovery and option UI
checks pass; APK builds and app lint pass. Scope-specific evidence and remaining
qualification are in [validation](VALIDATION.md) and the linked contracts.

Resolved Maven versions and SHA-256 checksums are locked, with a Windows CI
baseline and advisory inventory. Kotlin/Compose 2.4.21 and KSP 2.3.12 replace the
vulnerable Kotlin/KAPT baseline while retaining AGP 9.2.1 and Gradle 9.4.1.
Android modules compile against API 37; the app targets 37 with minimum API 31.
Android 17 runtime qualification remains open; API 35 regressions are separate evidence.
The [qualification plan](ANDROID_17_QUALIFICATION.md) separates static APK checks
from the Android 17 and 16 KiB device journeys still required.
See [build security](BUILD_SECURITY.md) for qualification and trust limits.

For a useful bidirectional RC, finish:

1. Extend [temporal delivery](TEMPORAL_UPDATE.md): same-kind timed/all-day changes
   have authenticated cryptography, current-source ownership, guarded requests,
   native completion and worker/UI consent. Listed all-day and zone/DST service
   and production Provider/worker profiles, offline ACK and restarted replay pass.
   Extend editor/OEM qualification beyond the measured all-day and Paris cases,
   then qualify kind/zone transitions and combined edits; ambiguous local hours
   remain held rather than guessed.
2. Extend [outgoing DELETE](DELETE_SUBMISSION.md): ordinary timed service/editor/
   production-worker and restarted recovery journeys pass, including single-day and
   three-day deletion. A real preclaim conflict and local replay preserve the full
   variant. Offline capture, process restart and reconnection also pass before
   submission. Isolated real-service recovery also passes after injected evidence-
   persistence cancellation. Extend production-worker conflict and actual network/
   process interruption during/after remote claim, device reboot and power loss,
   then richer events and OEM behavior.
   Current source checks and permanent incoming barriers remain required; UNKNOWN
   is never reset or resent.
3. Preservation of ordinary reminders/colors during supported edits; current
   send profiles still reject many such events.
4. Safe handling of incomplete historical creation associations and actionable
   conflicts. Last-modification-wins requires trustworthy comparable edit times;
   capture/processing time is insufficient. Otherwise preserve variants.
5. Live all-day and transferred-CREATE text journeys, Samsung/One UI, periodic
   scheduling, reboot/offline recovery, battery and large-calendar qualification.
6. Signed/versioned release packaging and installation/update checks without
   session or data loss, with accurate capability and license documentation.

A fresh GET followed by PUT is not atomic compare-and-swap; concurrent changes
between them can still be overwritten. Keep this limit explicit; see
[conditional writes](research/CONDITIONAL_WRITES.md). Advanced invitation and
recurrence editing are not implied by the current profile.

## Working entry points

- [Audit closeout](AUDIT_CLOSEOUT.md): corrected findings and remaining qualification/release gates.
- [Working method](WORKFLOW.md): bounded assignments, roles and evidence.
- [Proton source audit](research/PROTON.md) and [FOSS reuse](research/FOSS_SYNC_REUSE.md): source-backed integration choices.
- [Licensing](LICENSING.md) and [workflow provenance](WORKFLOW_SOURCES.md).
- [Build security](BUILD_SECURITY.md): dependency locks/checksums, CI, advisory review and release gates.

Do not append chronological test/commit reports here. Update the affected current
capability, limitation or next step; keep detailed contracts in their own documents.

# Implementation status

This is the compact entry point, not an execution history. Read only the linked
contract relevant to the task. Existing detailed evidence is in
[validation](VALIDATION.md); run-specific notes remain ignored under `.local/`.

## Product and boundaries

Calendeer bridges Proton Calendar and Android's Calendar Provider. Event viewing
and editing stay in system calendar applications. Read-only is the default;
creation, title-only changes and description/location changes have separate
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
| Interface | Violet deer identity; separate calendar/settings/login screens; eight languages and light/dark/system themes. Calendar options expose pending work, conflicts and independent write consent. The bridge has no event editor or reminder notifications. | [Design](DESIGN.md), [permissions](PERMISSIONS.md). System calendar apps deliver local reminders. |
| Incoming sync | Scoped mirrors; paginated sealed staging, offline decode with retained keys, durable coverage and change-feed reconciliation. Near-term dates load first, then the wider horizon; compatible scans extend newly entered dates. | [Import](READ_ONLY_IMPORT.md), [behavior](SYNCHRONIZATION_BEHAVIOR.md). Gaps/deferred content stay visible; missing records are not deletion evidence. |
| Incoming contents | Supported timed/all-day events, exclusive all-day end, text, event colors with calendar fallback, supported personal display reminders and read-only participants. Supported recurrence projection and exceptions have dedicated mapping rules. | [Recurrence](research/ANDROID_RECURRENCE.md), [projection](PROJECTION_REPRESENTATION.md). Unsupported/untrusted content is deferred. |
| Outgoing CREATE | System editor discovery, immutable offline capture, guarded Proton submission/readback, same-row acknowledgement and durable handoff. | [Local capture](LOCAL_CREATE_CAPTURE.md). Simple events only; reminders, guests, recurrence and custom colors remain outside the send profile. |
| Outgoing title | Default-off option for title-only edits of qualified ordinary or fully handed-off created timed/all-day events. Durable request ownership, inspection and native/SQL completion. | [Title submission](TITLE_UPDATE_SUBMISSION.md). Known-empty details, no recurrence/color; other fields must stay unchanged. |
| Outgoing text | Default-off description/location option, optionally with simultaneous title change. Separate capsules, wire/proofs, cursors and consent; raw CRLF intent is distinct from canonical LF readback. | [Text delivery](SHARED_TEXT_PREPARATION.md). Creation mode must be enabled; narrow simple-event profile, unchanged dates and metadata. |
| Recovery/conflicts | UNKNOWN is inspect-only. Native writes are guarded and receipts precede acknowledgement; newer local edits survive. A shared title/text journal prevents competing owners across generations. Local conflict dossiers preserve observed variants. | [Capture](LOCAL_EDIT_CAPTURE.md), [conflicts](CONFLICT_ARCHIVE.md). Trustworthy edit timestamps and general conflict resolution remain unfinished. |

A refresh with no checkpoint commits its first staged page before draining queued
projections, so a dirty native edit cannot prevent its own capture indefinitely.
That page counts toward the four-page budget. Offline bootstrap consumes only an
already retained page. A different remote observation still preserves a conflict.

Synchronization attention offers Retry through the existing workers and expandable
explanations; it does not change consent or reset requests. Closed `CalendeerSync`
stage codes identify the reporting branch without event/account contents.
A stamped creation without its durable work identity stays held; do not repair it
by adopting a native row ID or allocating a new send identity.

## Evidence and remaining RC work

Real controlled-account journeys qualify login/import, simple CREATE, title edits,
and ordinary timed text edits through authenticated ports. Text also has an actual
system-editor/production-worker journey, interrupted-edit recovery and active
restart replay without another attempt. The tested editor is
`foundation.e.calendar`; this is not Samsung qualification.

Focused domain, adapter, SQL, Provider, worker and UI suites exist. The latest
account/attention slice passed 676 backend and 112 app JVM cases, 35 physical UI
cases and 17 isolated pipeline cases; APK builds and lint passed. These totals
are evidence for their scope, not a substitute for the next slice's checks.
Full recipes and limitations are in [validation](VALIDATION.md).

For a useful bidirectional RC, finish:

1. Date/time edits: [protected preparation](TEMPORAL_UPDATE.md) now covers same-kind,
   same-zone movement/duration through the authenticated facade and real Core.
   Separate timed request/readback rules bind all temporal API metadata. Guarded
   submission, a shared journal with reciprocal title/text/time ownership and typed
   Provider ACK are available. Suites pass (418 domain, 785 backend, 116 app JVM;
   six native crypto and 66 isolated SQL/Provider cases). Automatic temporal sending
   remains disabled. Finish import-coordinator/worker/consent integration, service
   qualification, all-day API conventions, timezone changes and kind transitions.
2. Outgoing DELETE with concurrent changes and interrupted acknowledgement.
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

- [Working method](WORKFLOW.md): bounded assignments, roles and evidence.
- [Proton source audit](research/PROTON.md) and [FOSS reuse](research/FOSS_SYNC_REUSE.md): source-backed integration choices.
- [Licensing](LICENSING.md) and [workflow provenance](WORKFLOW_SOURCES.md).

Do not append chronological test/commit reports here. Update the affected current
capability, limitation or next step; keep detailed contracts in their own documents.

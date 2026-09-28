# Specification: Proton Calendar interoperability on Android

Status: development baseline. Confirmed requirements and provisional development
policies are distinguished below; implementation is authorized using recorded
assumptions. This does not turn provisional choices into accepted product decisions.
Updated: 2026-09-26
Decision reference: owner instructions in the Calendar setup and specification conversation.

## Outcome and scope

Make Proton Calendar data available through Android's system calendar integration
so compatible calendar applications can use it. The application provides account,
synchronization and diagnostic controls, not an event browser/editor. Exact setup
screens and the supported calendar applications/device matrix remain to define.

The owner explicitly confirms a bridge-only product: event display, creation,
editing and other calendar interactions belong to the existing Android calendar
application. The bridge configures accounts, selected calendars and their sync
modes; it does not reproduce Proton Calendar's event UI or require changing that
application's UI. Data mapping and synchronization remain the bridge's responsibility.

Samsung Calendar is a proposed first interoperability target to confirm on a
physical device. The owner's stated use of Samsung My Files does not establish
a Calendar application preference or proven compatibility.

## Confirmed owner requirements

| ID | Requirement | Acceptance scenario / evidence |
| --- | --- | --- |
| CAL-01 | Expose supported Proton calendar data to compatible Android system calendar applications. | Given a selected Proton calendar and granted permissions, after successful synchronization its supported events are visible through the Android Calendar Provider and a target calendar app. Integration plus physical device verification. |
| CAL-02 | Provide synchronization without an in-app event management interface. | Account/sync controls can configure and inspect sync; event viewing/editing remains in existing calendar apps. Product review. |
| CAL-03 | Keep Proton/network integration replaceable. | An alternative adapter passes the same accepted behavioral contracts without changing domain/sync rules; versioned adapter state is migrated or safely reconciled. Contract and migration tests. |
| CAL-04 | Maximize reuse of Proton libraries and relevant code copied/adapted from public Proton repositories. | For each Proton-specific capability, record evaluated official components, pinned source/version, usability evidence and adoption choice; any independent rewrite has a concrete documented gap. Imported code retains provenance, applicable license/notices and a record of local changes. Dependency/source audit plus build and integration evidence before adoption. |
| CAL-05 | Synchronize asynchronously and support offline processing. | After initial data acquisition, loss of connectivity does not prevent reading cached supported events or processing already available local work; network-required work remains durable and resumes when execution/network conditions allow. Process-restart and device tests. Offline writes depend on sync direction below. |
| CAL-06 | Define pagination and preloading explicitly. | A fixture exceeding multiple pages becomes usable progressively with bounded resource use; interruption/resumption and page replay do not lose or duplicate events. Coverage remains distinguishable from completeness. Contract/integration tests; limits and load fixture sizes to be fixed in planning. |
| CAL-07 | Deliver bidirectional synchronization, including creation and management from the Android calendar application. A read-only first milestone is acceptable, not the final outcome. | Given a supported writable calendar, an event created in the system app reaches Proton; supported changes made on either side reach the other after sync. Supported offline changes survive restart and upload after reconnection/reconciliation. Provider/adapter integration and physical-device/live-service verification. |
| CAL-08 | Persist an independent read-only or editable choice for each selected calendar. Editable mode is available only where permitted by Proton rights and supported integration capabilities. Read-only remains a permanent product option after bidirectional sync ships. | Given two calendars with different selected modes, after restart remote changes arrive in both, while supported system-app changes can upload only for the editable calendar. A calendar without the required rights cannot be made writable by the local setting. Integration/device and adapter contract verification. |
| CAL-09 | Reuse/adapt Contako's Proton login flow and relevant implementation for the bridge. Keep authentication behind the replaceable Proton boundary. | Given an account requiring a supported login challenge, the corresponding steps complete before a usable Calendar session is exposed. Errors, cancellation and expired sessions are handled without losing queued sync intent. Contract and device/live-service verification; Contako source alone is not proof of Calendar authentication. |
| CAL-10 | Offer the same eight application languages as Contako and a system-language option: English (en), French (fr), German (de), Spanish (es), Italian (it), Dutch (nl), Polish (pl), Portuguese (pt). | Selecting each language changes the bridge's translated UI and persists after restart; system mode follows the device's supported locale with English fallback. All advertised catalogs are packaged and usable offline. Resource and device verification. |
| CAL-11 | Reuse Contako's light, dark and system theme preference behavior. | A forced light/dark choice persists after restart and ignores system theme changes; system mode follows them. Login and settings use the selected appearance without changing external calendar applications. Unit and device verification. |
| CAL-12 | Check the permissions required by enabled features at launch and when returning to the app, and handle missing or revoked access. | When calendar integration is enabled, missing access blocks provider work while login/settings remain usable; contextual requests, refusal, return from settings and revocation during work follow the [permission policy](PERMISSIONS.md). Physical-device verification. |

CAL-07 follows the owner's clarification in this conversation. "Creation" explicitly
includes event creation; whether creating/deleting calendar containers is also
required remains to clarify. Native calendar features are to be evaluated in a
capability matrix, not treated as a promise that every Android feature maps to Proton.

## Login, language and appearance

The owner explicitly requests reusing Contako's login, available languages and
light/dark/system settings. These are bridge setup/preferences screens, consistent
with the absence of an in-app event editor. Relevant source can be extracted and
adapted during implementation; unrelated contact features are outside this reuse.
See the [reuse source record](research/CONTAKO_REUSE.md) for components and limits.

The login reuse baseline includes username/password, second-factor codes,
separate mailbox password/key unlock when required, session restoration and
human-verification handling. Preserve errors, cancellation and secret handling
while adapting the Calendar session bootstrap. Contako's unsupported login cases
must remain explicit; reuse is not a claim of universal Proton login support.

Language and theme both default to system mode, matching the inspected Contako
preference stores. Choices apply to this application only, including its own
authentication and synchronization controls. They do not rewrite event content,
alter timezones or change another application's language/appearance. Shared
Proton challenge screens must be checked separately for localization and theming;
do not promise that third-party UI can be fully restyled.

Reuse suitable login/settings translations and add translations for Calendar's
new strings across all eight languages. Preserve parameter/plural correctness
and ship the catalogs needed for offline language switching. Source catalog
existence is not proof of complete or correct translation of the bridge.

Do not share Contako's stored account sessions, preferences or application data.
Calendar must establish and persist its own authenticated context. Extract the
authentication behavior and dependencies without importing contact permissions,
contact synchronization, application identity or branding as implicit requirements.
Authentication readiness must include the Calendar-specific access/key bootstrap
that later source qualification establishes.

## Per-calendar access modes

Confirmed behavior:

- **Read-only:** Proton-to-Android synchronization continues; the bridge offers
  no supported path to create, modify or delete Proton data from that calendar's
  Android representation.
- **Editable, when available:** synchronization runs in both directions for
  supported operations. Users work in the Android calendar application.
- Modes are independent per selected calendar. The setting cannot grant rights
  that the Proton account lacks. This is a lasting feature, separate from the
  option of delivering read-only import as an initial development milestone.

Proposed implementation safeguards (exact transition policy remains open):

- Reflect effective access in the Android calendar representation and enforce
  it in the sync engine before dispatching writes; do not rely solely on an
  external application's UI. Remote authorization remains authoritative.
- Persist user preference separately from observed remote permissions and
  supported operation capabilities. Permission loss or a rejected write must
  not silently discard local changes or trigger a retry loop.
- On a switch to read-only, pause undispatched outgoing changes and retain them
  for explicit resolution. An already dispatched request can have an uncertain
  remote outcome and must be reconciled; changing a setting cannot undo it.
  The handling of retained edits on re-enabling writes, and the default mode
  for a newly selected calendar, remain product decisions.

## Provisional implementation scope

These choices make incremental implementation concrete and may change before
release. Private decision tracking is not part of the public repository.

| Area | Initial behavior | Later capability / qualification |
| --- | --- | --- |
| Accounts | One account in the initial UI; contracts scoped by account. | Multi-account UI after session/storage validation. |
| Selection | Opt-in synchronization; selected calendars start read-only. | Editable mode requires remote rights and implemented operations. |
| Basic events | Target title, description, location, start/end, timezone and all-day dates. | Timed instants and all-day dates are distinct; unsupported data is blocked, not guessed. |
| Writes | Stage durable create/update/delete intent for supported writable calendars. | Enable live mutation only after round-trip and unknown-field qualification. |
| Recurrences/invitations | No writes until exact semantics are supported. | Masters, exceptions, attendees, replies and shared calendars need dedicated qualification. |
| Containers | Discover/select existing calendars. | Container creation/deletion is outside the initial slice. |
| Offline horizon | Target 90 days past / 365 days future; bounded fetch batches. | Extend progressively; no automatic retention purge initially. |
| Conflicts | Preserve both variants and block upload for explicit resolution. | No implicit last-write-wins. |
| Mode/rights changes | Retain/suspend pending edits; reconcile in-flight outcomes. | Re-enabling writes must not silently discard or replay retained changes. |
| Reminders | System app delivers supported local reminders. | Calendeer synchronizes reminder data only; it posts no event, sync or error notifications. |
| Logout | No destructive purge before pending-change handling exists. | Define explicit retention/removal choices before enabling logout. |

Store/adapter contracts reject mismatched account scope or incompatible adapter
state. A missing page is not an authoritative empty snapshot. Import failure
preserves the previous usable projection and pending work. Loaded coverage is
separate from last sync time. Production UI contains no fabricated calendars.

Initial development budgets: request at most 100 events per adapter page, at
most 4 pages per run, one serialized sync per account, no eager expansion of
unbounded recurrences. Adapter/server limits may require smaller batches.
Production measurements determine final budgets.

## Proposed offline and asynchronous behavior

The following are correctness proposals, not a settled technology stack or a
decision that the first delivered milestone supports uploads. Bidirectional
operation is the confirmed final target and must shape contracts from the start.

- **Local availability:** data already projected into the system provider stays
  available without opening this application or contacting Proton. First login,
  missing keys and never-downloaded events may require connectivity; do not
  label them as available offline. Retention and logout policy remain open.
- **Durable progress:** persist pending local processing, identity mappings,
  remote checkpoints and Android projection progress. Process termination,
  cancellation and reboot must not depend on an in-memory coroutine surviving.
- **Separated stages:** fetching, decrypting/mapping already acquired data,
  projecting to Android, and optional uploading are resumable stages. Local-only
  processing must not inherit a network constraint unnecessarily. Reuse Proton
  engines/queues where their contracts fit; do not duplicate them by default.
- **For editable calendars:** durably capture supported Android edits/deletions,
  retain the base revision and pending change, and reconcile before upload.
  Preserve changes made while the sync process is absent using provider state
  plus reconciliation; a content observer alone is not a durable change log.
  Remote refresh must not silently overwrite pending local edits.
- **Recoverable boundaries:** the private database, Android provider and Proton
  server do not form one transaction. Use durable intent and replay/reconciliation
  around each boundary; a crash after a provider write must not create a second
  event or turn an imported change into a new outgoing edit.
- **Retries:** distinguish transient failures, throttling, expired sessions,
  missing keys, unsupported data and permanent rejection. Back off transient
  retries, respect server retry guidance, and expose blocked work. An upload with
  a lost acknowledgement has an unknown outcome; reconcile rather than blindly
  replaying a create. Do not assume Proton provides idempotency guarantees.
- **Scheduling:** persist intent separately from execution scheduling; coalesce
  triggers, bound concurrent work and serialize conflicting operations. A manual
  request and a background trigger must not run competing syncs for the same
  scope. Exact latency, battery/data constraints and scheduler choice are open.
- **Status:** distinguish pending, processing, blocked, failed and synchronized
  work, plus last successful synchronization and loaded date coverage. Offline
  local success does not mean Proton has acknowledged an upload.

Read-only calendars, including those in an initial read-only milestone, must
be represented as non-writable and verified in target applications. Mode changes
must retain stable identities without recreating user events or losing mappings.

## Proposed pagination and preload strategy

1. Load the account/calendar metadata and keys required by the selected scope.
   Load a bounded useful date window first, then extend coverage in background.
   Initial past/future horizons, whole-history option and retention are open;
   no arbitrary number of months is treated as an accepted default.
2. Keep **date coverage**, **page continuation** and **incremental-change
   checkpoint** separate. Record them per account/calendar/query generation;
   continuation values belong to the versioned Proton adapter. Temporal filters
   and snapshot/delta support must be demonstrated from source, not assumed.
3. Fetch bounded pages and process bounded chunks; apply backpressure so a
   download cannot create an unbounded decrypt/projection backlog. Prioritize
   actionable local work and the initial window without starving backfill.
   Bound memory, requests, concurrency and execution duration independently.
4. Durably record a received page and required downstream work before advancing
   its download checkpoint. Track projection completion separately; persistently
   staged work is not yet visible data. Publish ready events progressively.
5. Deduplicate overlaps/replays by stable remote identity and version semantics.
   If the remote collection changes during pagination, use a verified snapshot
   or change-feed catch-up protocol; otherwise perform bounded reconciliation.
   Never infer deletion from absence in a partial page or date-limited result.
   Expired cursors trigger scoped recovery without erasing pending local work.
6. Include events overlapping the window, long events, and recurring series
   whose master predates it. Fetch the master and relevant exceptions needed for
   a correct supported representation; do not expand an infinite series eagerly.
   Recurrence support and limits remain to specify.
7. Do not rely on observing the date currently displayed by another calendar
   app: such an integration has not been established. Schedule rolling preloads
   and expose coverage/extension controls as needed. A user browsing an unloaded
   date must not be promised automatic fetching or a complete calendar view.
8. Distinguish remote deletion, lost access and local cache eviction. Never
   upload a deletion merely because a date leaves the retention window; never
   evict unresolved local changes. Retention remains a product decision.

## Proposed failure acceptance cases

| ID | Given / when / then | Verification |
| --- | --- | --- |
| RES-01 | Given downloaded events, when the device is offline and the sync process restarts, then supported cached events remain readable and durable local processing resumes without a network call. | Integration/device |
| RES-02 | Given a multipage import, when stopped after page persistence but before projection/checkpoint completion and later resumed, then every expected fixture event appears once and incomplete coverage is not reported complete. | Crash-injection integration |
| RES-03 | Given two overlapping pages and a server-side change between requests, when pagination and reconciliation finish, then the known fixture's final state is represented without false deletions. | Adapter contract |
| RES-04 | Given a recurrence master before the window and an exception inside it, when the window is loaded, then supported occurrences match the independent expected dates/timezones, including a DST boundary. | Domain/provider integration |
| RES-05 | If bidirectional: given an offline Android edit and a remote edit to the same event, when connectivity returns, then both are retained until the selected conflict policy resolves them; no blind overwrite is reported as success. | Integration; policy pending |
| RES-06 | If bidirectional: given a remotely accepted create whose acknowledgement is lost, when execution retries, then reconciliation avoids a duplicate or explicitly blocks unresolved work. | Contract/live-service evidence |
| RES-07 | Given a provider write succeeded just before a crash, when replay resumes, then the mapping is recovered, no duplicate is added and the import is not echoed back as a new edit. | Provider integration |
| RES-08 | Given a revoked permission or unusable session, when sync is triggered, then affected work is retained and marked blocked without destructive cleanup or an endless immediate retry loop. | Integration/device |
| RES-09 | Given a read-only calendar and a local write attempted by an external app, when the bridge processes changes, then no outgoing mutation is dispatched for that calendar; remote refresh still works. Handling of the local discrepancy must follow the defined reconciliation policy. | Provider/engine integration |
| RES-10 | Given queued offline edits, when the calendar becomes read-only or remote write permission is lost, then undispatched edits are retained and blocked; any in-flight outcome is reconciled without claiming cancellation or losing the edit. | Integration; transition policy pending |

These are specified scenarios, not executed tests. The provisional budgets and
matrix above support isolated implementation; production measurements and
capability qualification are prerequisites for enabling live integration.

## Proton reuse and replaceability

The owner explicitly authorizes direct copying and adaptation of relevant code
from public Proton repositories. Prefer an official usable library/SDK when it
fits; source reuse is also a supported route when extracting a bounded component
fits the bridge better or no usable package exists. Consider independently
written protocol code only for documented gaps. Evaluate authentication,
key handling/crypto, serialization,
calendar/event access and any existing sync primitives separately. Reusing a
complete application is not automatically equivalent to reusing a library.

For each imported component, retain the upstream repository, exact commit and
file paths, applicable license/copyright notices, dependency requirements and
local modifications. Keep adaptations small and identifiable to make future
upstream fixes reviewable. Reuse relevant upstream tests/fixtures where suitable
and add bridge-specific contract/integration coverage. Record how upstream fixes
will be tracked; copied source does not receive package updates automatically.

Existing upstream use makes source a valuable reference, not a guarantee of
correctness after extraction. Compilation, dependency behavior, Android lifecycle
and bridge integration still require evidence. This permission to reuse source
does not select a component or claim any source has already been imported.

Record supported behavior, dependency closure, license, Android integration,
maintenance and build evidence for each candidate. Keep upstream types inside
the adapter and map at its boundary. Business rules own sync intent; the adapter
owns Proton-specific payloads, crypto and opaque state. Do not discard unknown
fields during a round trip. Replacing libraries must preserve pending operations
through migration or explicit reconciliation, not reset the local store.

The [Proton baseline](research/PROTON.md) contains source-inspection findings,
not a qualified dependency selection. No complete future Calendar SDK is assumed
available and no exact library version is selected by this specification.

## Android evidence and implementation candidates

Consulted 2026-09-26:

- [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider):
  shared calendar/event storage, recurrence instances, permissions and specialized
  sync-adapter access. This is the proposed interoperability boundary; OEM app
  behavior and safe change tracking still need device evidence.
- [Offline-first guidance](https://developer.android.com/topic/architecture/data-layer/offline-first):
  local persistence and deferred synchronization inform the proposed durable
  processing design. Room is a candidate, not a selected dependency.
- [WorkManager reference](https://developer.android.com/reference/androidx/work/WorkManager):
  a persistent-work scheduler candidate, with execution subject to constraints.
  Scheduler integration and Calendar Provider sync-adapter privileges are
  separate decisions; WorkManager does not itself confer those privileges.

No promise of instant background execution, universal OEM compatibility or
end-to-end encryption inside Android's shared provider is made. Locally projected
event content is accessible according to Android calendar permissions; explain
this consequence in onboarding. Backup, local encryption and cleanup need design.

## Remaining product decisions

1. Delivery sequence: read-only import may be an initial milestone; the final
   bidirectional target is confirmed. Decide the smallest useful first slice in
   planning. Clarify calendar-container creation separately from event creation.
2. Supported fields, shared/subscribed calendars, recurrence/exceptions,
   reminders, attendee/invitation operations and behavior for unsupported edits.
3. Conflict/deletion policy for editable calendars; default mode for newly
   selected calendars; handling of retained changes across mode/permission
   transitions and unexpected local edits in read-only mode.
4. Initial and maximum loaded horizon, preload/retention policy, account count,
   freshness/battery goals and network preferences.
5. Minimum Android version, first physical target/app, privacy/backup/logout
   policy, distribution/license and application identity.

Development proceeds through the isolated slices in [architecture](ARCHITECTURE.md).
Qualify Proton components and refine coverage/capabilities before enabling the
corresponding live integration; pure engine and configuration work can proceed now.

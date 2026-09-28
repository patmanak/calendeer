# Implementation status

Calendeer is in initial development. The [specification](SPECIFICATION.md)
defines confirmed features and provisional implementation choices. The
[architecture](ARCHITECTURE.md) separates synchronization policy, Android
integration and Proton-specific authentication/protocols.

## Target behavior

- Bridge Proton calendars to Android system calendar applications, with no event editor.
- Per-calendar read-only/editable modes; bidirectional sync is the final target.
- Asynchronous offline processing, durable intent and progressive paginated loading.
- Replaceable Proton integration, maximizing official library/source reuse.
- Reuse Contako login behavior, eight languages plus system selection and three theme modes.

## Current delivery boundary

The application is branded **Calendeer**, with an original violet deer mascot,
adaptive and monochrome launcher icons, and light/dark/system appearances.
The connection form, signed-in calendar controls and settings are separate
destinations. Authentication challenges take priority over navigation and open
preference dialogs. Branding preserves the Android application ID, existing
sessions and imported calendars. See [identity and interface](DESIGN.md).

| Module | Implemented | Not established by this slice |
| --- | --- | --- |
| `app` | Configuration, login, discovery and per-calendar read-only import controls; eight languages; durable import queue, Calendar Provider projection, permissions and background scheduling. | Broad Android/OEM qualification, editable mode or complete event feature coverage. |
| `sync` | Pure scoped models, bounded transactional pagination, access gates, conflict checks, uncertain-outcome write states and a separate metadata catalog controller. | Production outgoing mutation storage/capture or bidirectional reconciliation. |
| `auth` | Contako-derived secret contract and state controller, cancellation/cleanup gates, retry policy and focused tests. | Calendar bootstrap or per-calendar capability checks. |
| `proton-auth` | Proton Core login/session integration, calendar discovery, Calendar key bootstrap, paginated decryption, sealed calendar change polling, individual event reads and a bounded recurrence mapping. | All challenge variants, external-author trust resolution, complete recurrence/history coverage or event writes. |

The development application offers real account sign-in and automatically loads
calendar names and colors after session restoration or login. Manual refresh,
loading, genuine empty results and failures are distinct states. A transient
refresh failure retains the previous list in memory; sign-out clears it and
invalidates pending results. The import store separately persists discovered
metadata, selections, supported event contents, projection intents and checkpoints.

The adapter uses Product.Calendar and its own client identity. Calendar discovery
reuses the same authenticated transport and protected session as login. Selected
calendars import asynchronously into a bridge-owned read-only Android mirror.
Custom per-event colors are preserved, with calendar color used only as fallback.
Received pages and bootstrap material are now Keystore-sealed before processing.
Local decryption, deferred-event retries and projection can resume without network
when their keys are already available; network refresh uses bounded pages
and durable continuation. No data is uploaded to Proton. Server challenge variants
still require separate live qualification.
New scans prioritize the past and next 30 days before completing the wider horizon.
Their cursor retains the frozen date range, timezone and order across restarts;
legacy cursors and sealed pages continue with their original interpretation.
An epoch-scoped coverage register now persists interval receipt and local processing
with each corresponding transaction. The UI separates those counts from pending
Android updates, deferred events and history gaps. Legacy receipts and unobserved
prefixes remain unknown; refresh retains the last finished observation without
accumulating interrupted history. Fully processed compatible observations can now
extend only the newly entered future dates when the previous feed is drained and
no deferred work or history gap is known. An unchanged horizon only checks changes.
The admitted plan and feed obligation survive restart; unknown coverage, incompatible
timezones, access loss, blocked work or resets conservatively return to full scans.
Per-interval projection attribution remains unimplemented. Local mirror repair still
requeues known events; the optimization concerns event-window downloads.
Coverage reducers, adapter evidence checks, schema migration and offline coordinator
recovery have passed focused unit and physical-device fixtures. A processing count
does not claim complete event support or complete remote history.
An authorized retained-session live import also completed with all twelve periods
received/processed, no pending Android updates and a drained calendar change feed.
Controlled-clock/provider fixtures verify entering unchanged events and recovery
from interrupted extensions. A second live same-horizon refresh retained the exact
baseline ledger while draining changes. A live date-boundary journey and battery
or large-calendar performance measurements remain unqualified.
Priority-order and legacy-resume behavior have controlled pagination/receipt tests.
A retained-session physical import using the updated adapter also completed and
drained its change feed. Delivery order on a large live calendar remains unqualified.

Retained-session restoration, live Calendar key access, event decryption and
read-only Provider projection have been verified on an authorized physical test
device. This does not establish every account configuration or calendar app.
The [initial import boundary](READ_ONLY_IMPORT.md) documents supported fields,
coverage, offline recovery and limits. Bidirectional editing remains unavailable.
Explicit remote deletion notices
now use a durable, scoped path to the Android mirror; historical deletion recovery
after a server feed reset remains unavailable.

Read-only recurrence support now maps validated daily, weekly, monthly and yearly
rules, additional/excluded dates and single moved/cancelled exceptions to Android's
native recurrence engine. Durable scoped associations retain missing-master work
without blocking later pages and prevent stale children from reappearing after a
master deletion. Deferred parents and pending changes of original date type remain
recoverable; mirror recreation requeues cached content even when Android recycles
the old calendar ID. SQLite migration and optional cached recurrence data preserve
existing simple events. Unsupported recurrence forms remain visibly deferred.
Bounded physical Provider queries verify actual occurrences, including DST,
month ends, leap years, RDATE-only sets and exceptions. Store/coordinator fixtures
verify migration, offline recovery and deletion replay. Broad OEM behavior and
recurrence writes remain unqualified. See the supported subset in
[read-only import](READ_ONLY_IMPORT.md).
An authorized live fixture created through the official Proton client produced
three daily all-day instances. Moving only its second occurrence replaced the
original date in Android without duplication, with the calendar change feed drained.
The identifiable fixture is retained for inspection. This establishes that specific
read-only service journey, not every recurring rule or client implementation.
Per-event color overrides, their removal to restore calendar inheritance and
cache compatibility have passed physical Provider/storage checks. A retained-session
live import also produced a custom-colored event. This establishes the data path,
not color rendering in every calendar client.

Personal DISPLAY reminders and verified organizer/participant details now flow
through the offline cache into native Android rows. Event notification overrides
and inherited calendar defaults remain distinct; email reminders stay with Proton.
Current participant responses use Proton's token matching, with ambiguous or partial
lists deferred. The mirror remains read-only and sends no invitations or replies.
External-author trust resolution is still unavailable: unknown signatures stay
deferred rather than accepting an unverified public key.

SQLite 7 and a separate content profile preserve old receipts while durably requiring
a full scan for newly supported details. Calendar-default changes similarly rescan
within the bounded import horizon, retaining the change feed and existing data.
Atomic Provider replacement removes stale reminders/participants and avoids duplicate
rows on replay. Unit tests, native crypto and physical storage/provider/coordinator
fixtures cover these paths, including interrupted upgrades and offline completion.
An authorized real import retained five events, projected five local reminders and
drained the change feed; recurrence instances and same-horizon reuse also passed.
For a retained timed fixture, the system calendar then delivered the expected
notification ten minutes before its start. This qualifies that device/client
journey, separately from the official Proton application's own notifications.
Alarm delivery across all-day/DST, reboot, Doze and other OEMs remains separate
qualification work. See [validation](VALIDATION.md) for the controlled/live distinction.

Unsupported individual records no longer block other valid records on the same
page. Durable encrypted retry evidence, partial status and verification categories
remain visible. Malformed identities and unavailable local keys still block safely.
SQLite migration, bounded recovery and local completion have passed physical
device fixtures, including a 400-event projection drain; native crypto fixtures
verify sealed-receipt recovery without transport calls. A physical-device journey
also verifies an actual process stop and restart without networking, retaining the
session and identical owned Android events. Interrupted receipt decoding across
process death and broad OEM coverage remain separate qualification work.
Reconnection after that offline journey also completed a real import and drained
the calendar change feed, preserving event identity/content and color overrides.
Calendar change polling is connected to production synchronization. Cursor advancement
retains pending event obligations; 404 reads remain incomplete instead of deleting
local events. Complete catalog refreshes mark inaccessible calendars unavailable
while preserving local data. A valid advancing feed reset now archives its sealed
evidence and restarts a bounded scan before following newer changes. It retains
uncertain old copies and displays a persistent history-gap warning; a rescan never
claims to recover missing historical deletions. Reset notices themselves are not
applied as event actions. Non-advancing resets remain blocked. See the
[reset recovery boundary](research/CHANGE_FEED_RESET.md).
Controlled reset responses have passed physical-device checks for database
migration, transactional recovery, preserved Provider rows and persistent warning
states. This is not qualification of a server-triggered reset.
An authorized live journey through the official Proton client verified creation,
title and individual-color changes, and deletion of one disposable simple event.
Android retained the same event identity through the update, removed the deleted
fixture and preserved the other mirrored events. The change queue drained after
each phase. Server retention/reset behavior still needs separate qualification.
Durable delta migrations and coordinator/provider fixtures have passed on a physical
device, including update/delete projection, local receipt recovery and retry/access
barriers. A retained-session live import also established and drained a real calendar
change cursor. These checks and the live simple-event journey do not establish
bridge uploads, concurrent editing or support for richer event types.

The [synchronization behavior proposal](SYNCHRONIZATION_BEHAVIOR.md) defines the
remaining read-only capabilities and gates for durable editing, conflict resolution
and uncertain outcomes. It does not enable uploads or mark those capabilities delivered.

Worker recovery now requests backoff for temporary Provider outages and selected
SQLite environmental failures, retains pending projections and preserves existing
network errors. Permanent Provider rejections remain blocked; cancellation and
diagnostic failures release the busy state. Worker orchestration has focused JVM
checks. Transient startup selection, catalog persistence and explicit-refresh
storage failures now schedule durable local and network recovery rather than
losing the trigger. Eight isolated recovery tests on a physical device and
twelve related Provider regression tests passed. This is not yet full reboot,
real storage failure or Doze qualification. A separate physical test revoked both
calendar grants through Android while an isolated projection was pending: the
intent stayed queued with `PERMISSION`, then projected once after grants returned.
This does not establish revocation in the middle of a Provider batch or a live
Proton change.

Calendeer posts no notifications. Reminder data belongs to the Android calendar
client; progress and errors are shown inside the bridge. The merged manifest
excludes notification permission and the unused foreground service.

The bridge exposes no account-attribute changes or recovery flow. Security-key-only
authentication and unsupported verification mechanisms remain explicit limits.
See [authentication integration](research/AUTH_INTEGRATION.md) for source evidence.
Login requires only install-time permissions. Calendar import adds a contextual
runtime permission flow, with foreground and operation-time checks described by
the [permission policy](PERMISSIONS.md). Pausing or signing out retains previously
projected local events; there is no automatic destructive purge.
Network failures offer an explicit shortcut to the bridge's Android settings,
because a device can block this app's traffic despite its `INTERNET` grant.
An opt-in debug test checks anonymous Proton reachability through the production
transport; this does not establish account login or Calendar access.

## Project workflow

Public rules live in AGENTS.md; optional private operator instructions live in
Git-ignored AGENTS.local.md. Six local agent roles and five skills support the
workflow. Personal questions, execution records and temporary handoffs stay in
ignored local files.

See [validation](VALIDATION.md) for reproducible checks,
[source provenance](WORKFLOW_SOURCES.md) for workflow adaptations and
[Contako reuse](research/CONTAKO_REUSE.md) for application components.

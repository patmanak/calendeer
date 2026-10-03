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
| `sync` | Pure scoped models, bounded transactional pagination, access gates, immutable simple local CREATE/UPDATE/DELETE intents, conflict checks, uncertain-outcome write states and a separate metadata catalog controller. | General outgoing queue, automatic capture or bidirectional reconciliation. |
| `auth` | Contako-derived secret contract and state controller, cancellation/cleanup gates, retry policy and focused tests. | Calendar bootstrap or per-calendar capability checks. |
| `proton-auth` | Proton Core login/session integration, discovery, Calendar key bootstrap, paginated decryption, sealed change polling, bounded recurrence mapping and experimental single simple-event CREATE with verified readback. | All challenge variants, external-author trust resolution, complete recurrence/history coverage or general event editing. |

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
and durable continuation. Ordinary synchronization remains read-only; outgoing
qualification requires the explicit experimental path below. Server challenge variants
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
The generic mutation engine now requires an explicit adapter capability as well as
calendar access before admitting outgoing work. Adapter capabilities default to
empty; unsupported queued work is retained as blocked without an uncertain-outcome
claim or dispatch. Restoring a capability does not resume blocked work, and losing
one does not reclassify acknowledged or uncertain work. Focused JVM checks passed.
Current production import adapters advertise no generic mutations; the separate
experimental CREATE port is unaffected. This gate does not qualify Proton writes.
The current [conditional-write source audit](research/CONDITIONAL_WRITES.md)
found no demonstrated expected-base mechanism in the inspected official-client
request paths. This does not establish a server limitation. UPDATE/DELETE remain
unavailable pending an authoritative precondition contract or a separately accepted
conflict strategy. Android editing-source reuse also needs explicit unknown-field
preservation qualification before constructing update payloads.

An internal offline shared-title experiment now replaces only the original
encrypted-shared SUMMARY span while retaining the entire clear-shared text and
all other text spans exactly. It reuses Biweekly for typed identity checks and
title escaping/folding, preserving opaque properties, their parameters, duplicates
and nested components without rebuilding them from the display model. Ambiguous
identity/title/framing and parameterized event titles are refused. No-op changes
retain both original strings exactly. This validates selected plaintext fields,
not every opaque property's semantics or a complete Proton envelope. It has no
production caller, signing, persistence, transport or UPDATE capability; timestamps
and signatures still require a separately qualified update preparation path.

An internal offline CREATE preparer now reuses Proton's simple-event partition:
signed clear date metadata and signed encrypted display text. It uses Biweekly
and Proton Core primitives, with caller-supplied UID/timestamp and selected keys.
Focused JVM checks cover escaping, timezones/DST, exclusive all-day dates,
unsupported-field refusal, Unicode/precision/date limits and sanitized key/crypto
failures. A synthetic native physical-device test round-trips timed and all-day
preparations through the production decryptor and rejects alterations to both
signatures. An authenticated preparation port now refreshes account/calendar
context using reads, selects one owned active member with explicit WRITE permission,
verifies the address-signed passphrase and unlocks the primary active calendar key.
It returns a scoped, versioned Keystore-sealed preparation and shares the logout
mutex. Real retained-account preparation has passed timed and all-day fixtures
on two selected calendars. This does not establish service acceptance.
The internal CREATE response parser rejects incomplete, foreign, duplicate-key
and ambiguous responses. Reported success is only a candidate for authenticated
refetch; a reported error never proves that resending is safe.
The shared network factory now guards CREATE attempts at the application and
network-exchange boundaries, with a non-repeatable request body. Controlled local
tests reproduce the original HTTP 503 follow-up and exercise published Core's
401 token-refresh re-entry while observing only one PUT. Rebound or unmarked
CREATE requests are refused; ordinary reads continue. A separate scoped SQLite
dispatch journal commits `OUTCOME_UNKNOWN` before admitting an attempt and has
no automatic requeue. A separate explicit captured path is described below;
there is no background outgoing work or general capture/conflict transaction.
Physical SQLite fixtures verify reopening, immutable work, competing helpers,
rollback and cancellation after admission. Real sealed preparations also survive
reopening and refuse readmission after the unknown transition. These are controlled
fixtures, not proof of power-loss recovery or simultaneous Android processes.
An experimental submission port now composes a separate versioned CREATE request,
freshly requalifies member/rights/key context and admits only one guarded request.
The journal's additive v2 migration retains existing work and can attach immutable
Keystore-sealed response evidence. Transport failure, cancellation and process
restart cannot reopen admitted work. Inspection queries the scoped UID within a
bounded pagination budget, individually reads the unique result, verifies both
shared signatures and signed UIDs, and compares the expected supported content/time.
An authorized physical-device fixture created one simple timed event through this
port, verified its authenticated readback and observed exactly one Android projection
through the production read-only worker. The event and protected journal were retained;
reopening refused readmission. Native signature/UID checks and SQLite evidence/migration
fixtures also passed. This establishes simple live CREATE acceptance and current
readback, not server UID idempotency or real response-loss recovery.
The experimental CREATE journal now retains an immutable authenticated remote
association and a separately revalidated Android row locator. Its additive v3
migration preserves admitted UNKNOWN work and sealed evidence. Association shares
the import coordinator mutex and account/generation admission gates; it never
replays CREATE or injects historical creation content into the current import.
Only the latest stabilized, non-deferred imported event can qualify a unique,
owned, clean Android row with its matching revision. Missing, changed, deleted or
ambiguous rows leave the locator unresolved. Stored row IDs are historical hints,
not permanent ownership or revision guarantees. Existing remote associations can
be reused without calling the submission adapter.
Physical SQLite/coordinator/Provider fixtures passed, including a deterministic
concurrent-duplicate regression. A retained live CREATE was associated with its
current Android projection, then reopened and associated again with the CREATE
port unavailable. The event and admission state were preserved. This does not
qualify power-loss recovery or general outgoing acknowledgement.
The application now owns the experimental CREATE journal and serializes explicit
staging/submission with import. Journal v4 adds a separate permanent pause with its
first cause; it preserves the attempt's admission state and protected evidence. Startup pauses
legacy unadmitted preparations across accounts. Session changes, calendar
disabling/complete-catalog disappearance and observed calendar permission loss
close admission and pause affected preparations. Reconnection, calendar return
and restored permissions never automatically resume paused work. Account identity
is checked separately from the authenticated UI flag. Failed pause persistence
retains an in-memory obligation, retried before the next explicit write; a new
process conservatively pauses all old preparations. Already admitted UNKNOWN work
can retain late response evidence and only be inspected with current access.
This conservative pause policy is provisional; explicit resolution remains future
work. No new logout control, capture or automatic outgoing worker is exposed.
Physical journal/owner fixtures, injected pause failures and read-only import
recovery regressions passed. The retained live association still resolves one
owned Android projection without readmitting CREATE. These results do not qualify
real server rights revocation or a user-facing logout journey.
An explicit internal simple UPDATE capture now retains the last acknowledged
projection base and the Android edit independently of the latest imported remote
record. Import schema 10 preserves immutable paused/conflicted intents, suppresses
their projection and never automatically resets a conflict. A newer import before
capture can therefore coexist with the original projected base. The Provider reader
qualifies ownership, revision, supported fields and an assertion-only snapshot;
incoming writes preserve dirty/deleted rows and recurrence children. No dirty flag
is acknowledged and no mutation is sent. See [local capture](LOCAL_EDIT_CAPTURE.md)
for the subset and crash/snapshot limits.
Focused domain, SQLite, coordinator and real Provider fixtures have passed,
including capture against a newer imported version through the production owner.
Migration, clean projection, recurrence/details, association and read-only recovery
regressions also passed on an authorized physical target. These fixtures use
controlled remote ports and establish no live Proton UPDATE guarantee.
The retained live CREATE association also remains usable after the import upgrade,
without submitting another mutation.
Supported simple upserts now retain an immutable projection preparation and write
its nonce with the event in one Provider transaction. After a lost private ACK,
exact scoped marker inspection can restore the actually projected base without
overwriting a subsequent dirty edit or clearing deletion flags. Baseline comparison,
exact pending consumption and receipt removal share one private transaction;
newer remote evidence remains pending. Missing or ambiguous proof blocks unsafe
recovery, including different content reusing the old opaque revision. Migration
does not invent receipts for legacy rows. Complex projections and deletions retain
their previous conservative recovery limits; the two stores remain separate.
Nonce-backed recovery has passed source review, JVM checks and controlled physical
SQLite/Provider fixtures, including lost private acknowledgement, reused opaque
revision and schema migration. These fixtures do not qualify arbitrary power loss.
An explicit internal simple DELETE capture now requires a present, owned, dirty
Android tombstone. It retains the projected base and observable supported content,
including edits made before deletion, without consuming the Android flags.
Schema 10 adds an UPDATE/DELETE kind to the same unique local journal; existing
UPDATE payloads, states and receipts are preserved. Both kinds suppress projection
and conflict permanently with divergent or deferred remote evidence. A remote
tombstone does not acknowledge a local deletion, and captures cannot coalesce
different kinds or replace retained work. Source review, JVM checks and physical
Provider/SQLite fixtures have passed, including ordinary Android deletion and
migration. This qualifies the installed Provider, not a calendar client's editing
UI. The app exposes no outgoing DELETE path.
An explicit internal simple CREATE capture now reserves a scoped local UUID in a
separate journal before its one permitted Provider identity stamp. Reopening without
a captured intention inspects that marker only; missing or ambiguous evidence never
falls back to a row ID. The first committed supported content and timestamp remain
immutable and paused. This does not connect to the experimental remote CREATE path
or qualify a system calendar creation UI. This capture uses a separate journal.
Interrupted claims can sacrifice availability or lose an uncommitted snapshot;
they cannot automatically rebind an identity after row purge. Source review,
JVM checks and physical SQLite/Provider/owner fixtures have passed, including
ordinary all-day insertion, marker recovery and a deterministic competing-helper
regression. This does not qualify simultaneous processes or a calendar client's UI.
See [local creation capture](LOCAL_CREATE_CAPTURE.md).
Captured simple CREATE intentions can now be explicitly prepared through the
read-only Proton boundary. Local journal v2 retains the first protected payload
against the exact intention; migration preserves v1 claims and captured content.
Current marker/content, account/access and both import/preparation formats are
qualified, including after fresh context reads. A compatible retained preparation
is reused without network or fresh crypto; changed or missing Android evidence
leaves it unqualified. Proton's dedicated known-empty preparation v2 seals an
explicit `Notifications: []` personal override alongside the unchanged shared
cryptographic parts. Historical v1 blobs retain their unresolved obligation and
cannot be reused or replaced automatically. Both local formats are refused by the
submission codec before opening/admission and never enter the experimental dispatch journal.
JVM tests check placement and refusal of unexpected personal data. A separate
synthetic native fixture exercises Core/Keystore and defaults through the production
preparer with controlled context/read shapes. Physical journal migration, owner
gates and Provider preparation/cache fixtures have passed. The native fixture also
passed timed/all-day round-trips, reopened protected storage, signed UID/content
checks, altered-signature refusal and explicit-empty versus inherited defaults.
These local preparation fixtures submit no request; separate submission qualification
is described below.
Created-event inspection now shares a read-only bounded UID lookup, individual
event read, key bootstrap and content comparison. The existing submission v1 path
always uses its shared-content-only profile. A separate internal known-empty v2
profile requires an actual empty `Notifications` array before key access and
preserves known-empty details in the comparison. Missing/null, malformed or non-empty
overrides cannot qualify even when inherited defaults or filtered email notifications
produce no Android reminders. GET readback bodies reject duplicate JSON names,
including escaped equivalents, before parsing; the general import parser is unchanged.
Focused JVM tests and synthetic native timed/all-day fixtures passed, including
signature/UID refusal and changed content/dates. A retained live legacy event also
passed the strict GET parser and existing inspection path without changing its
admission record or protected response; its Android association remains usable.
Shared signatures do not sign the personal notification field.
A separate experimental submission v2 now seals an explicit-empty notification
policy, the known-empty expected content and `Event.Notifications: []`. Opening
selects the strict readback profile from the immutable submission format. Legacy
submission v1 retains its shared-only interpretation. Fresh authenticated
preparation must match the full draft, format, UID and member/key context before
the existing durable UNKNOWN admission and single guarded PUT. Neither local
preparation format is converted or dispatched. Unsupported personal data remains
refused; response loss, cancellation and failed readback never authorize another
submission. JVM tests cover format/policy drift, invalid overrides and inspection
without preparation or upload. This adds no journal migration, writable mirror or
captured-creation upload.
Native submission v2, opaque SQLite journal reopening and retained live legacy
inspection have passed on an authorized physical target. A separate retained timed
creation now has authenticated strict readback with raw empty notifications,
verified shared signatures/UID/content and exactly one owned Android projection
without alarms or reminder rows. The same admitted attempt was inspected after
reopening, without another PUT; UNKNOWN remains permanently non-resendable.
A folded-whitespace fixture also identified byte-only verification refusing Core
text signatures. The decoder now uses the official binary/text verification
wrappers on the same trusted author keys, preserving activity/capability guards
and exact parsed content. Native timed/all-day, altered-signature, foreign-author,
key-refusal and binary-import regressions passed. This establishes the retained
timed service journey, not all-day service behavior, UID idempotency, real response
loss or a system calendar editing UI.
Captured simple creations now have a separate explicit submission-staging entry
point. Local journal v3 immutably reserves a distinct outgoing identity against
the exact captured snapshot before read-only preparation. Only the reserving
invocation can retain the separately protected payload. Reopened gaps are unresolved
and never prepare again; completed work requires current account, selection,
checkpoint, format and unique Provider marker/content qualification before reuse.
Historical local preparations remain unchanged and are never converted. This path
does not itself admit dispatch or acknowledge the original Android row. SQLite
migration, owner gates and Provider qualification passed isolated physical-device
fixtures.
A separate internal captured-dispatch entry point now atomically links the frozen
capture and immutable submission to dispatch journal v5. Only newly staged work
in the same owner can hold its one-use volatile lease; cached/reopened or
access-invalidated work cannot rearm it. Final local Provider, selection, format
and permission checks follow fresh adapter context reads before UNKNOWN admission.
Captured submissions require the exact sealed known-empty draft. Generic dispatch
and association refuse captured links. UNKNOWN work only permits explicit readback;
it never sends again or acknowledges the original local row. Duplicate-free
association for other content types and subsequent-edit service conflicts remain
separate work.
The import owner now retains incoming event projections while captured UNKNOWN
work has no qualified remote identity. An explicit readback can retain a distinct
immutable binding in dispatch journal v6; it narrows the hold to that remote event
without acknowledging or rewriting the original Android row. The proof can resume
offline and excludes generic association paths. Damaged or unreadable work remains
blocked. Unrelated events can project after identity qualification, while received
pages and local processing continue. Held rows remain pending without requesting
automatic worker continuations; a local waiting state is available in all eight
languages. A separate explicit metadata-only attachment now reserves its first
durable receipt in dispatch journal v7 and assigns the remote identity to the
unique original local marker. It preserves local edits, child rows and dirty
flags, and recovers the same receipt after an uncertain Provider response without
another server submission. Missing or ambiguous rows remain unresolved. This
historical proof alone grants no original-row acknowledgement or import release.
A separate explicit offline step now retains the verified sent reference and full
attachment provenance in import journal v11. It consumes only an identical
incoming payload in the same transaction, never invents an absent cache record,
and refuses adoption of generic, damaged or advanced baselines. Different remote
evidence and later local intentions remain intact. The original Android row stays
dirty and incoming projection holds remain active; current local intentions,
conflicts and the final original-row acknowledgement still need reconciliation.
The controlled qualification is described in [validation](VALIDATION.md).
A separate explicit offline reconciliation now inspects the current attached
original row with an atomic, assertion-only Provider snapshot. Supported later
edits or present tombstones retain an immutable UPDATE/DELETE intention against
the sent reference without cleaning Android or consuming incoming records in
that capture transaction. Different or absent cached remote evidence and deferred
work conservatively produce a permanent conflict. Replay rechecks the original;
a newer edit or changed kind cannot replace the first intention. Missing or
ambiguous identity remains unresolved, and reminders, recurrence, colors or other
unsupported data remain intact. An unchanged result is only a current local
observation, never a durable clean proof. A second snapshot can detect edits
after the local commit. The existing local-intention guard can suppress later
incoming projections while preserving remote evidence and conflict; captured
holds remain active. A separate explicit offline original-row acknowledgement
now reserves one immutable nonce in import journal v12 before its conditional
Provider batch. Only an unchanged supported original can be cleaned; the batch
asserts current identity, content, flags and child absence and changes only the
dirty flag and acknowledgement marker. Reopened or uncertain work only inspects
that marker. Historical proof can resume after later edits or tombstones without
cleaning them, and proven work permits subsequent supported local capture.
All local capture entries respect the unresolved reservation fence. Interrupted
admission without a marker remains conservatively blocked, requiring future
explicit resolution. Different incoming data and captured projection holds stay
intact. Historical acknowledgement is not current clean content, hold release or
a current remote version.
An explicit offline qualification now distinguishes a currently clean original
from its historical ACK. It requires the exact proven source, unchanged supported
Android content, clean/non-deleted flags, unique identities and absent children
in an assertion-only batch. Local work and ordinary projection receipts prevent
qualification. The result is a point-in-time observation and releases no hold.
Generic local captures reject all raw captured provenance before replay, even when
ACK is proven. Generic Provider writes and captures assert absent captured markers,
including recurrence parents and descendants, preserving concurrent attachments.
Physical SQL, Provider and coordinator fixtures passed these boundaries and their
related regressions. These fixtures use controlled transport; service behavior is
qualified separately.
An explicit durable handoff can now admit subsequent incoming updates for a
qualified captured creation. Import journal v13 retains the original creation and
ACK evidence separately from the advancing projection reference. Each specialized
Provider update asserts the entire current reference, ownership, unique identity,
clean flags and child absence atomically; it never inserts a missing original or
removes its provenance. Historical marker recovery can advance SQL after a lost
response while preserving later Android edits and newer incoming data. Subsequent
local capture uses the current reference. Dirty, missing or unsupported rows wait
durably without repeatedly scheduling work, allowing unrelated events to drain.
Ordinary projection and cached local-capture replay cannot adopt captured work,
including orphaned or corrupt journal entries. This transfer is limited to
known-empty simple incoming updates; remote deletion, recurrence, color and richer
details remain held. Automatic handoff, local capture and writable UI are not enabled.
Physical-device SQL, Provider and owner fixtures passed migration, conditional
updates, interrupted-response recovery, local preservation and bounded backlog
draining checks. Provider witnesses also require complete, well-formed batch
responses; missing responses retain uncertainty rather than reporting success.
This response validation now covers every Provider batch path, including calendar
creation/removal, ordinary projections, local stamping and captured attachment.
Fixed proof batches verify exact returned counts; variable batches validate each
result shape and rely on the Provider's expected-count assertions. Physical
response-loss, malformed-result, detail and recurrence regressions passed, including
recovery without rewriting a later local edit.
An opt-in retained-session service journey passed through the production owner,
all three journals and the real Proton adapter: a known-empty timed native creation,
one submission, authenticated readback, attachment to the original Android row,
original acknowledgement and released handoff. Reopening then passed inspection
without another preparation or send, preserving identity and non-resendable
admission. The synthetic example is retained. A rejected native fixture with an
implicit participant also remained unstamped and undispatched. Subsequent real
Proton title updates after handoff have now passed a retained-session journey:
an edit through the official client advanced the reference and projected onto the
original Android row, preserving immutable creation evidence and draining the
event-specific queue. A subsequent one-shot native title edit was retained as a
paused intention against that advanced base without dispatch. Inspection after a
controlled process stop and reopen preserved the dirty row and waiting state.
These checks use the production owner, journals, Provider and real incoming Proton
adapter. Creation or editing through a system-calendar UI remains unqualified;
the local mutation in this journey uses an explicit conditional Provider batch.
The application still exposes no writable mirror or event editor. General outgoing
queue/resolution, automatic Provider capture and conditional Proton UPDATE/DELETE
guarantees remain unqualified. See
[source adaptation](../proton-auth/PROVENANCE.md).
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
twelve related Provider regression tests passed. A retained-session physical
reboot with multiple selected calendars preserved five owned Provider events and
allowed local processing offline after the first device unlock. The network
settings and private verification fixture were restored afterward. One selected
calendar already had a blocking status before that local run, so this is a
retention/restart result, not full multi-calendar import completion. On the same
physical device, a delayed production local import worker remained queued after
65 seconds of forced deep idle and completed at its first attempt after idle was
released. This qualifies local WorkManager recovery under forced Doze on that
device; natural Doze timing, other OEMs and real storage
failure remain unqualified. A separate physical test revoked both
calendar grants through Android while an isolated projection was pending: the
intent stayed queued with `PERMISSION`, then projected once after grants returned.
This does not establish revocation in the middle of a Provider batch or a live
Proton change.
An opt-in read-only network-stage probe on the retained test session passed
context refresh, event-page receipt/decode and current delta-page receipt/decode
for both enabled calendars. It reproduced an `UNSUPPORTED` response only when
reading one retained delta cursor. A temporary, sanitized parser trace showed
that this real calendar-scoped response contained `CalendarMembers`, previously
accepted only in the global feed. The adapter now treats calendar member notices
as context changes, requires `Member.CalendarID` to match the calendar for
create/update, and never interprets a bare member deletion as an event deletion.
Focused tests failed before the change and passed afterward. On the physical test
device, the same retained-cursor receipt/decode then passed for both calendars.
Both selected calendars subsequently completed their coverage. A retained-session
refresh through the production WorkManager network queue then passed with two
enabled calendars and one paused calendar: baseline/delta/projection work drained,
distinct read-only mirrors and Provider event identities/revisions were retained,
and the paused calendar was preserved. This qualifies an unchanged refresh after
convergence on that device; interrupted initial backlogs and other OEMs remain
separate journeys. A separately queued production network import worker also
remained `ENQUEUED` with zero recorded attempts after 65 seconds of forced deep
idle. After idle exit and verification process startup, it succeeded and both
retained enabled calendar scopes had complete coverage and drained durable work.
Normal startup work or continuations can contribute to that convergence. Natural
Doze, completion without reopening the process and six-hour periodic delivery
remain unqualified. The public
[Android Calendar DTO](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/data/api/ServerEventsApiImpl.kt#L108-L180)
inspected earlier lists this collection in the global feed, so the
calendar-scoped variant is a live service observation rather than a claim about
that published DTO.

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

# Validation

Use JDK 17 or a compatible newer JDK, Android SDK platform 36 and the checked-in
Gradle wrapper. Set the SDK path in ignored `local.properties` or ANDROID_HOME.
Install neither an emulator nor a device-specific environment as part of a build.

The reusable [acceptance plan](ACCEPTANCE.md) defines expected outcomes by boundary.

From the project root (use `gradlew.bat` on Windows):

```sh
./gradlew :sync:test :auth:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The first build needs dependency downloads. `--offline` is optional and requires
an already populated cache; it is not a clean-checkout setup command.

JVM tests must cover durable-store contracts, pagination replay/budgets,
permission gating and pending/uncertain write handling as implemented. Fakes
establish orchestration behavior only. Android persistence/provider and lifecycle
claims require the corresponding integration/device tests.

Before release, qualify login, Calendar bootstrap, per-calendar modes, supported
event fields, offline edits, process restart, timezone/all-day/recurrence behavior
and background scheduling on a controlled physical device and Proton test account.
Do not infer live compatibility from compilation or mocks.

Keep run-specific outputs, device/account identities, questions and reviewer
handoffs in ignored local files. Update public acceptance and limitations when
behavior changes, not for each command run.

## Targeted configuration tests on an authorized device

Build the instrumentation APK with `:app:assembleDebugAndroidTest`. Install the
debug app and test APKs on the explicitly selected physical target, then run:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.patmanak.proton.andcalendarbridge.PreferencesDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

Replace `DEVICE_SERIAL` locally; never put actual device identifiers in project
files. These tests change only the bridge's theme/language preferences and restore
the original selections. They cover preference-store recreation, the available
platform locale path and activity launch/recreation under the configured package.
They do not establish process-death durability, UI translation quality, Calendar
Provider behavior, Proton authentication or calendar synchronization.

## Targeted authentication UI tests

Run `com.patmanak.proton.andcalendarbridge.AuthenticationUiDeviceTest` using the
same instrumentation command above. These tests inject a sanitized fake port and
exercise credential clearing, masking/capture protection, second-factor navigation,
cleanup failure, retry delay and the separate authenticated-identity display.
They do not load hosted verification or submit credentials to Proton.

`com.patmanak.proton.andcalendarbridge.AuthenticationStartupDeviceTest` uses the
production composition and waits for restoration to finish without a closed
failure. It also recreates the activity and verifies the controller survives.
It never submits credentials or clears a retained account. On an empty app store,
this checks initial Keystore/Room composition, not authenticated-session durability.

The `proton-auth` JVM suite exercises adapter lifecycle, server-proof delegation,
challenge policy and cleanup using controlled fixtures. Its Room schema is exported
under `proton-auth/schemas`. Qualify Keystore/Room persistence across real process
restart, refresh/revocation and a live hosted challenge separately on an authorized
test account. Do not include real credentials in instrumentation arguments, command
lines, fixtures, reports or source files.

## Calendar metadata discovery

The JVM catalog tests cover complete/empty/error results, response bounds,
malformed data, authentication admission, session replacement, refresh retry
delays and late results after sign-out. Run `CalendarCatalogUiDeviceTest` with
the instrumentation command above for synthetic UI state and refresh checks.

For a deliberate read-only live check, retain an already connected test account
and run only this class, opting in explicitly:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e calendarLiveCatalog true \
  -e class com.patmanak.proton.andcalendarbridge.CalendarCatalogLiveDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

It verifies session restoration and an actual complete metadata response, reporting
only the calendar count and closed failure category. An empty response is valid.
It never submits credentials, signs out, creates calendars or changes account
attributes. Install updates with retained app data; do not reset the account to
repeat the check. Passing establishes metadata access, not event decryption,
calendar permissions, Provider integration or synchronization.

## Initial read-only import tests

`ImportStoreDeviceTest` uses isolated temporary databases to check page/checkpoint
rollback, reopen, stale acknowledgements, refresh/reprojection, lease invalidation
and rejected foreign scopes/outgoing writes. `CalendarImportUiDeviceTest` uses
synthetic states and never accesses the account or Calendar Provider.

On an authorized test device, `CalendarPermissionFlowDeviceTest` requires
`-e calendarPermissionFlow true` and initially missing calendar grants. It tests
decline then allow using the real system dialog; it never grants through ADB.
An already granted permission skips that journey and must not be reported as a
new refusal/approval qualification.

`CalendarProviderDeviceTest` requires `-e providerFixture true` and existing grants.
It creates uniquely identified synthetic local calendars/events and removes only
its own fixtures. It verifies replay after a new adapter instance, updates,
scoped deletion, all-day UTC/exclusive-end mapping and read-only calendar access.
The event-color fixture verifies override changes and removal, resulting display
color and stable row identity. The storage fixture checks color persistence and
acknowledgement of older cached payloads without the optional field.
Its refusal fixture simulates denied access; real in-flight OS revocation remains
a separate device journey.

`ProtonEventCryptoDeviceTest` runs a debug-only native cryptographic fixture using
fresh in-memory keys. It exercises the production decryptor and rejects altered
signatures, without opening the account database, contacting Proton or projecting
events. It is not evidence of every real account's key configuration.

`ProtonSimpleEventPreparationTest` exercises offline simple CREATE partition,
escaping/folding, stable supplied UID/timestamp, literal DST/timezone output and
exclusive all-day dates under different default timezones. It checks refusal of
unsupported fields, ambiguous local times, subsecond precision, historical dates,
invalid Unicode and unsuitable keys, plus sanitized crypto errors. Run it with
`:proton-auth:testDebugUnitTest --tests '*ProtonSimpleEventPreparationTest'`.

`ProtonEventPreparationCryptoDeviceTest` exercises the production preparer with
fresh synthetic member/calendar keys. It signs clear metadata and encrypts/signs
display text, using Core `signText` and the existing reader's `verifyData`. Timed
and all-day cases round-trip; separate alterations to each signature must yield
no accepted change. The probe fabricates author resolution and a read-response
shape explicitly; its code has no transport or account/Provider access. This
qualifies native preparation and local verification, not service acceptance, real member
selection, UID idempotency, outgoing intent durability or safe UPDATE/DELETE.

The live pipeline test is intentionally opt-in and requires a retained test session,
one calendar and granted calendar access:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e calendarLiveImport true \
  -e class com.patmanak.proton.andcalendarbridge.CalendarImportLiveDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

This test enables that calendar's persistent read-only mirror and retains the
requested import. It waits for a newly completed traversal, inspects only its own
Provider account, and reports counts without titles/IDs, including the number of
events with a custom color. These counts do not verify visual rendering in a
calendar app. It performs no Proton mutation. A nonzero count exercises real
event decryption; zero only establishes
the empty traversal and key bootstrap. Repeat after retained-data process restart
to qualify stable identities. Record OEM/client UI visibility separately: Provider
success alone does not prove every calendar app supports the mirror.

`CalendarMultiCalendarWorkerLiveDeviceTest` qualifies an unchanged refresh with
at least two enabled, accessible, already imported calendars and one paused
calendar in the same retained test account. Opt in with `calendarMultiWorker=true`.
It waits for the existing unique network queue to finish, calls the application's
`requestSync()`, and observes new production network work and its continuations
until they succeed. It never calls the importer or remote adapter directly.
It then checks complete coverage, drained baseline/delta/projection work, retained
authentication, distinct read-only Provider mirrors, and unchanged Provider event
identities/revisions and paused state. Counts and booleans are the only output.
The periodic worker can contribute to convergence; polling for its running state
does not establish its absence between polls. This journey qualifies a refresh
after convergence, not the initial backlog, every event field, natural Doze or a
specific calendar client's UI.

## Staged import recovery

`ProtonEventImportTest` verifies priority-window ordering, full horizon coverage,
explicit pagination, legacy cursor/first-receipt compatibility, frozen timezone
and strict cursor-layout rejection. `ProtonStagedEventImportTest` also traverses
10,000 generated events in bounded pages while rebuilding the adapter and changing
the clock/timezone between requests. Its fixed fixture expects 147 requests and
no page above 100 events. This is a protocol/pagination fixture, not a live server
volume benchmark, heap measurement or a persisted interval-coverage claim.

`StagedImportEngineTest` covers receipt preservation, offline decode admission,
partial-page isolation, atomic application, bounded continuation and stale retries.
`CoverageTrackerTest` and staged-engine cases distinguish receipt from application,
unknown migrated prefixes, changed plans/formats, epoch changes during acquisition
or decoding, transaction rollback and bounded history. Proton receipt fixtures
verify that all four query types and every page precede a period's terminal marker,
and that sealed dates/timezones are authoritative after a restart.
Proton adapter tests cover scope/format/session validation, sealed receipts, local
address-key mapping and refusal of plaintext Keystore fallback.

`StagedImportStoreDeviceTest` uses isolated databases for v1 migration, reopen,
large receipts, atomic rollback, global capacity, scope isolation and retry CAS.
`StagedImportCoordinatorDeviceTest` requires `providerFixture=true`; it uses an
isolated scheduler/store and a generated synthetic Provider account, projects 400
events across bounded drains, verifies completion publication, offline retry and
local-key recovery, then removes exactly its own fixtures. It does not call Proton.

The native crypto probe now rebuilds the decoder and Keystore receipt codec with
network callbacks that fail if invoked. It checks valid/incompatible/valid rows,
unverified signatures, missing keys and independent event budgets. These fixtures
plus database reopen do not establish actual OS process termination or a complete
live airplane-mode journey; record those separately when performed.

`ProtonChangeFeedTest` currently qualifies only protocol parsing/transport contracts
with fabricated responses. These tests alone do not establish live server behavior.

`DeltaImportEngineTest` covers anchor-before-baseline ordering, atomic cursor/job
commit, offline event receipt recovery, missing-event retention, explicit tombstones,
context barriers, stale completions, malformed scopes and bounded continuation.
`DeltaImportStoreDeviceTest` covers additive migrations, durable delta reopening,
scope isolation, exact acknowledgements, storage admission and unavailable catalogs.
`DeltaImportCoordinatorDeviceTest` requires `providerFixture=true` and uses an owned
synthetic Android account with an isolated database and no-op scheduler. It checks
update/delete projection, offline decode, unresolved IDs, access loss, prerequisite
rechecks after retries and rolling scans despite persistent missing IDs.
Reset fixtures additionally cover atomic archive/cursor/scan rollback, schema 1–3
migration to the current schema, reopen, scope/format isolation and archive admission under
the shared staging quota. The coordinator checks that reset-page deletions are
ignored, an empty rescan preserves uncertain copies, new changes retain identity,
offline work does not spin and the history warning prevents a new completion
timestamp. Deferred recent events keep the partial status alongside that warning.
UI fixtures verify the warning through network errors and pause. These controlled
responses do not establish a real server reset or its retention period.
The opt-in live import also requires a persisted, drained calendar delta cursor.
For a fresh scan it also checks finished, fully received/processed interval observations
and an empty known Android update queue, reporting only counts.
It does not create/edit/delete a remote event or establish reset/retention semantics.

`CoverageStoreDeviceTest` uses isolated SQLite databases for schema 4-to-5 migration,
reopen, atomic rollback, receipt metadata and ledger quota accounting, scoped counts
and refresh epochs. Inbox receipts share the retained-byte budget but do not count
as deferred event records; deferred counts remain scoped to their owning calendar.
Older migrations are exercised by the existing store suites.
`CoverageCoordinatorDeviceTest` requires `providerFixture=true`: with an isolated
store/scheduler/account and synthetic remote it receives a page before keys are
available, resumes local processing without a fetch, blocks then drains projection,
and preserves deferred events and the history warning. Its temporary provider rows
and database are cleaned up. UI fixtures distinguish received/processed periods
from projection/deferred work and do not invent coverage for legacy completion.

`RollingImportPlannerTest` checks same-horizon reuse, entering future dates, bounded
multiweek extensions, DST and conservative fallbacks. `ProtonRollingWindowTest`
checks frozen explicit plans through all query types/pages, scoped cursor validation,
legacy compatibility and sealed offline resumption. These are controlled protocol
fixtures; they do not establish live server selection at every date boundary.
`RollingStoreDeviceTest` checks coverage payload migration, observation persistence,
atomic plan/feed admission, exact coverage comparison, lease rollback and access
loss during an unfinished scan. `RollingCoordinatorDeviceTest` uses
`providerFixture=true` and an isolated synthetic remote/database/provider account:
unchanged timed/all-day events enter the next day's horizon, long overlapping events
keep their identity, same-day refresh avoids event-window requests, interrupted
extensions resume their frozen plan, and resets/zone changes/deferred work prevent
unsafe reuse. Temporary fixtures are removed after each test.

Add `rollingRefresh=true` to the opt-in live import to perform a second same-horizon
refresh. It must retain the exact baseline ledger/epoch and drain the change feed.
This qualifies the production reuse path on that account; an actual server journey
across a date boundary remains separate from controlled-clock tests.

`CalendarOfflineRestartDeviceTest` is an opt-in host-orchestrated journey. Run with
`calendarOfflineRestart=true` and `offlinePhase=prepare` on an already connected,
authorized test account with at least one selected calendar and imported events. Stop the
application process, disable networking, then run with `offlinePhase=verify`.
The test checks a different process, no active network, restored authentication,
identical owned Provider contents across all bridge-owned calendars and local
processing without introducing a new blocking error.
Only a digest and counts are retained in private fixture preferences; event data
and identifiers are not included in test diagnostics. Restore the exact original
network settings in a host `finally` block and always run `offlinePhase=cleanup`.
For a physical reboot journey, compare the kernel boot identity before/after,
wait for the first user unlock before launching the app, and keep networking off
through `verify`. Restore settings even if the device remains locked, then defer
fixture cleanup until the encrypted app storage becomes available.
Qualify reconnection separately: the existing live import fixture requires exactly
one selected calendar and cannot be applied unchanged to a multi-calendar account.
No app-data reset or session logout is part of this journey. It proves retained-session/Provider
recovery; it does not prove an interrupted encrypted receipt was decoded after
process death, since no pending receipt is deliberately staged in this fixture.

`CalendarStatusSnapshotDeviceTest` is an opt-in (`calendarStatusSnapshot=true`)
retained-session diagnostic. It reports only aggregate calendar statuses, coverage
and pending-work counts, plus validated connectivity, without calendar/event
identifiers or content. Launching the app may itself trigger normal read-only
synchronization. `awaitSettled=true` waits for any `RUNNING` status to clear;
`requestSync=true` explicitly requests a read-only network refresh if one was
already present. A successful diagnostic run is not proof that every calendar
finished importing; inspect its returned status counts.

`CalendarNetworkStageDeviceTest` is an opt-in (`calendarNetworkStage=true`)
retained-session diagnostic for multiple enabled calendars. It reads the
production adapter's context, one initial event page, and a current delta page,
then probes any retained delta cursors and runs one bounded coordinator
continuation without starting a fresh scan.
It reports only closed stage/failure categories and aggregate durable-state
counts; it never reports identifiers, cursor values or event content. The
coordinator pass can advance an existing read-only import and launch ordinary
local follow-up work. `COMPLETE` means that pass finished, not that every
calendar has reached `IMPORTED`. The probe does not replace a full network
Worker or server-interoperability journey.

`CalendarDozeSchedulingDeviceTest` is an opt-in, host-orchestrated Worker
journey (`calendarDozeScheduling=true`, phases `prepare`, `verify`, `cleanup`).
`prepare` queues the production `CalendarImportWorker` with a 45-second initial
delay. The default variant has no network input; `dozeNetwork=true` adds the
production network input and connected-network constraint, requires at least two
completed accessible imports, and uses a separate fixture name and preferences.
The host confirms deep idle is enabled and initially inactive, forces deep idle,
then reads the exact prepared WorkManager ID without starting the app. Require
`ENQUEUED` and zero recorded execution attempts after the observation interval;
always call `unforce` in a `finally` block. `verify` waits up to three minutes for
`SUCCEEDED`. The network variant additionally waits for the normal network queue
to settle and checks the retained enabled calendar scopes, authentication,
complete coverage and drained durable baseline/delta/projection state.
`cleanup` cancels only the chosen fixture work and clears its private preferences.
Record the observed WorkManager state, attempt count and elapsed wait. Normal
production continuations or startup work can contribute to final convergence;
verification starts the application process, so completion without reopening it
requires a separate host observation.
Forced idle on one device does not establish natural Doze timing, six-hour
periodic delivery, an interrupted initial backlog or another OEM's scheduling
policy.

`CalendarRemoteMutationDeviceTest` observes a disposable event created, modified
and deleted through the official Proton client in an authorized test account.
It never uploads or directly edits Provider rows. Use `calendarRemoteMutation=true`,
a unique eight-hex-character `fixtureToken`, and `mutationPhase=baseline` before
creating `BridgeQA-<token>-initial`. Run `created` after saving it in Proton, then
rename it to `BridgeQA-<token>-updated`, change its event color and run `updated`.
Delete only that fixture in Proton and run `deleted`, followed by `cleanup`.
The observer checks one owned read-only mirror, the same Provider event ID through
the update, a changed displayed color, fixture removal and unchanged contents/IDs
of every other mirrored event. Private digests stay in app-private preferences;
only counts and booleans appear in instrumentation output. Cleanup removes those
preferences only: the host remains responsible for deleting its remote fixture,
including after a failed test. Each phase uses a bounded import and requires the
calendar change queue to drain. This qualifies Proton-to-Android behavior; it does
not qualify uploads from the bridge, concurrency guarantees or all event features.

## Recurrence validation

`ProtonRecurrenceTest` exercises Biweekly parsing of rules, typed dates, UTC/TZID,
moved/cancelled exceptions and explicit unsupported forms. Native interpretation
is a separate boundary: `RecurrenceProviderDeviceTest` queries actual bounded
`CalendarContract.Instances` for daily, weekly/DST, monthly and yearly rules,
RDATE-only sets, EXDATE, moved and cancelled occurrences, multi-day events,
type transitions and scoped cascading deletion. It also checks stable row identity,
replay, ambiguous parent rejection and calendar recreation even when numeric IDs
are recycled. It requires `providerFixture=true` and cleans its isolated account.

`RecurrenceStoreDeviceTest` covers schema 5-to-6 migration, legacy payload stability,
reopen/rollback/CAS, master ordering, missing or deferred parents, mismatched original
date types, durable tombstones and eligible-result limits. `RecurrenceCoordinatorDeviceTest`
combines the production store/provider with a synthetic paginated remote: children
can precede their masters across execution passes; unresolved dependencies do not
spin; deferred masters recover offline after mirror recreation; stale children cannot
resurrect deleted series. These fixtures make no Proton calls. UI tests distinguish
waiting for a series from a completed import.

For a live retained fixture, create `BridgeQA-<eight-hex-token>-series` through the
official Proton client in the authorized test account: one all-day event, DAILY,
COUNT=3. Add `recurrenceFixture=<token>` and `recurrencePhase=created` to the opt-in
`CalendarImportLiveDeviceTest`. It verifies the imported rule, duration and three
actual native instances. Move only the second occurrence to three days after the
first, retaining the title, then run `recurrencePhase=moved`; the expected offsets
are 0, 2 and 3 days. The observer never uploads and reports only counts/booleans.
Retain the identifiable remote fixture for inspection when that is the authorized
test-data policy. Record live phases separately from controlled Provider results.

## Reminder and participant validation

`ProtonEventDetailsTest` covers personal notification overrides and inheritance,
exact signed durations, email separation, bounded participants, roles, response
tokens and ambiguous/incomplete inputs. Protocol fixtures verify that inherited
settings affect content revisions, legacy receipts retain their acknowledgement
contract, and settings notices durably rescan before event obligations. The native
crypto probe includes signed details, attendee-signature tampering and offline
recovery; this does not establish external-author key trust.

With `providerFixture=true`, `EventDetailsStoreDeviceTest` exercises schema 6-to-7
migration, optional cached details, stale acknowledgements, rollback, reopen and
durable content-profile rescans. `EventDetailsCoordinatorDeviceTest` finishes old
work before automatically admitting a full rescan on a non-fresh continuation,
preserves that obligation offline without spinning, and rechecks the change feed.

`EventDetailsProviderDeviceTest` checks actual reminder and participant rows,
replacement/removal, replay identity, signed all-day offsets, capabilities on an
existing calendar, and scoped exception deletion. It also guards against writing
SELF_ATTENDEE_STATUS: Android can create an implicit participant on insertion and
reject subsequent event updates. These tests clean isolated synthetic accounts
and do not prove that a notification was delivered.

The opt-in live import observer reports aggregate local reminder counts without
contents or account identifiers. Add `reminderFixture=<eight-hex-token>` and
`reminderMinutes=10` to assert one timed `BridgeQA-<token>-reminder` event with
exactly one native DISPLAY reminder at that offset. For real notification qualification, create a
recognizable timed event through the official Proton client, select an explicit
DISPLAY reminder, and synchronize before its trigger. Check the system calendar's
notification permissions/settings, the imported reminder and a notification from
that calendar package for the same fixture. Distinguish this result from Proton's
own notification. Retain the synthetic event when authorized. All-day/DST, reboot,
Doze and other OEMs require separate journeys. Never send invitations merely to
test the participant mapping without applicable messaging authorization.

## Local failure recovery

`ImportWorkExecutionTest` exercises the production worker orchestration with
controlled failures: retry without an extra continuation, readiness failures,
cancellation propagation and permanent errors. It does not simulate SQLite.
`ImportRecoveryDeviceTest`, with `-e providerFixture true`, uses an isolated real
SQLite database and Calendar Provider account. It injects an interruption after a
real Provider write but before acknowledgement, then reopens storage and verifies
the same single Android event. It also checks an existing network failure survives
local recovery and a simulated permission gate retains its intent. The permission
gate fixture is not evidence of real Android permission revocation.
Other cases classify actual Android exception types and distinguish permanent
Provider rejections from temporary outages. Injected exceptions do not establish
behavior during real disk exhaustion, reboot, Provider death or WorkManager timing;
those require separately recorded physical journeys.

`CalendarPermissionRevocationDeviceTest` is a separate opt-in, host-orchestrated
system-grant journey. With existing `READ_CALENDAR` and `WRITE_CALENDAR` grants,
run its `prepare` phase (`-e calendarPermissionRevocation true`,
`-e revocationPhase prepare`) against an isolated SQLite store and owned Provider
fixture. Revoke both permissions through Android, verify both are denied, then run
`denied`: the local import must retain one pending projection and report
`PERMISSION`. Restore the original grants, run `restored`, and require one Provider
event, no pending projection and `IMPORTED`. Always restore grants and run `cleanup`
in a host `finally` block. The fixture makes no Proton call or account mutation;
it does not replace testing the initial runtime permission dialog or establish
revocation during an in-flight Provider batch.

## CREATE preparation boundary

`ProtonCreateContextTest`, `ProtonCreatePreparationAdapterTest` and
`ProtonCreateResponseParserTest` check scope, explicit WRITE permission,
ambiguous members/keys, session replacement at every stage, cancellation,
network versus protected-storage failures, protected preparation and conservative
response handling. Duplicate JSON property names (including escaped equivalents)
and excessive nesting remain unknown. `SharedProtonPortsTest` checks that
preparation and logout share a mutex and authentication gate.

`ProtonEventPreparationCryptoDeviceTest` uses native synthetic keys without
network or account access in the probe. It exercises the real context resolver,
timed/all-day round trips, both shared-part signatures, an altered passphrase
signature and an unusable primary key with a usable secondary present.
Its service read shape is fabricated, not an observed CREATE acknowledgement.

With a retained authorized test session and selected calendars, run only
`CalendarCreatePreparationLiveDeviceTest` with
`-e calendarCreatePreparation true`. It refreshes identity and calendar context
using production reads, prepares timed/all-day synthetic drafts locally, and
checks protected receipts and unchanged selections. It also persists those
sealed preparations in an isolated journal fixture, reopens it, and verifies that
unknown admission is committed before a local-only callback and cannot be
readmitted after reopening. The isolated database is deleted afterwards;
no event is created. Aggregate counts only are
reported. This preparation fixture uploads no event. Ordinary
application startup and background import behavior still run normally.
This does not qualify remote CREATE, lost acknowledgements or writable Provider
calendars.

`ProtonSingleDispatchTest` counts requests received by a loopback MockWebServer.
It reproduces the unsafe two-request HTTP 503/Retry-After behavior with the old
client flags, then verifies one request with the guarded body. Other cases cover
redirect refusal, response loss, timeout, cancellation, competing callers,
interceptor re-entry and unmarked/rebound writes, plus an unaffected ordinary GET.
`ProtonCoreSingleDispatchTest` uses published Core 36.8.0, Retrofit 2.9.0 and
OkHttp 4.12.0 with synthetic sessions. Its real TokenErrorHandler performs one
refresh and re-enters the block twice, while only one PUT reaches the server.
A successful request preserves body and client/session headers. A Retrofit
Content-Type annotation that wraps the body is refused before any exchange.
The fixture uses an internal Core backend, a JVM-only monotonic clock for its
logger, and a loopback security strategy; it does not qualify remote certificate
pins, the full Android factory, DoH/challenge variants or server acceptance.
The clock shim is unit-test source only and is not packaged into either APK.

`CreateAttemptStoreDeviceTest`, with `-e providerFixture true`, exercises an
isolated SQLite database: immutable/idempotent preparation, exact scope and
format matching, reopening, competing helpers, rollback and failure/cancellation
after durable admission. A separate connection checks UNKNOWN is committed
before the callback. These fixtures do not qualify simultaneous processes,
physical power loss or disk exhaustion. The journal has no automatic reset or
remote acknowledgement API. These isolated fixtures do not invoke Provider
capture or account lifecycle; owner integration is covered separately below.
The schema-v2 fixtures additionally verify first-evidence immutability, exact
work matching, reopening without readmission, anomalous prepared rows with evidence,
and additive v1 migration preserving both prepared and unknown work.

`ProtonCreateSubmissionAdapterTest` and `ProtonCreateSubmissionCodecTest` cover
the complete request shape, version/scope binding, changed context before admission,
session replacement during claim, HTTP success without readback, response loss,
cancellation, duplicate/foreign UID results and verified content mismatch.
These synthetic ports establish policy only. `SharedProtonPortsTest` checks all
submission entry points require authentication and serialize with logout.
`ProtonICalendarTest` and the native preparation probe additionally reject a
foreign expected UID even when all signed parts agree with each other.

`CalendarCreateSubmissionLiveDeviceTest` requires deliberate
`-e calendarCreateSubmission true`, a 12-character hexadecimal
`-e createFixtureToken`, and the SHA-256 of the normalized authorized test username
in `-e createAccountHash`. Do not put private account hashes or fixture identities
in public execution reports. It verifies the exact retained account before mutation,
persists the chosen scope/token, and retains one synthetic timed event plus its journal.
Fresh context reads precede admission. Only authenticated signed readback establishes
a verified observation. Reopening refuses admission, repeated submit only inspects
unknown work, and the production read-only worker must project the fixture exactly once.
If the journal disappears after a submission request, the fixture refuses re-creation.
Rerun unresolved work with `-e createInspectOnly true`; never replace its token or
reset the journal to obtain another CREATE. Aggregate status and numeric response
codes are reported, without raw bodies or account/calendar identifiers.
The fixture is intentionally retained, not automatically cleaned up.
It does not qualify user editing, general durable ACK, real lost-ACK
recovery, conditional writes, UPDATE/DELETE, or the future outbox account lifecycle.

## Durable experimental CREATE association

`CreatedEventBindingTest` checks that work identity includes account/calendar,
generation, mutation ID, adapter format and protected-payload fingerprint, while
admission/evidence changes do not substitute the immutable work.
`CreatedEventBindingStoreDeviceTest`, with `-e providerFixture true`, uses isolated
SQLite fixtures for exact UNKNOWN admission, immutable first proof, uniqueness,
reopen, corrupted-parent rejection, lease/cancellation rollback, projection-locator
replacement/clearing and additive v2-to-v3 migration. Existing journal migration
fixtures also check v1-to-current preservation. None accesses the Proton account.

`CreateAssociationCoordinatorDeviceTest` combines isolated real import/journal
databases with fake ports. It checks newer imported content is preserved, retained
bindings bypass remote inspection, pending/deferred/tombstoned imports stay
unresolved, foreign work and prepared attempts are refused, and account loss,
permissions, disabled scopes, missing/mismatched rows and concurrent import obey
the gates. These fixtures do not qualify live service behavior.

`CreateAssociationProviderDeviceTest`, with `-e providerFixture true`, inspects
isolated real Provider rows. It checks scope/revision ownership, mirror recreation,
dirty/deleted/duplicate rejection and a deterministic duplicate inserted between
identity lookup and witness qualification. A controlled regression APK filtering
the second query by the previously found numeric row fails that race assertion;
the corrected whole-identity query passes. The fixture removes only its own local
Provider data. Passing does not make the multi-query witness globally atomic.

`CalendarCreateAssociationLiveDeviceTest` requires deliberate
`-e calendarCreateAssociation true` and the same private `createFixtureToken` and
`createAccountHash` used for the retained submission fixture. It requires the
existing UNKNOWN journal and qualified retained account; it never prepares or
submits another CREATE. Production inspection and the stabilized read-only import
must yield a current owned Android witness. Reopening must retain the remote
association; after clearing only the local locator, reassociation with an
unavailable CREATE port must succeed without any call to that port. Selections,
admission/evidence and the single retained synthetic event remain unchanged.
Aggregate assertions only are reported. This is local recovery with the CREATE
port unavailable, not physical airplane-mode or power-loss qualification.

These association paths have passed physical fixtures and the retained-event live
journey. They do not enable Samsung edit capture, writable mirrors, UPDATE/DELETE,
general outgoing lifecycle or atomic cross-store conflict handling.

## Experimental CREATE lifecycle

`CreateAttemptPauseStoreDeviceTest`, with `-e providerFixture true`, uses isolated
SQLite databases. It checks exact account/calendar/generation pauses, immutable
first cause, independent helpers and reopen, no reset through repeated remember,
atomic pause/claim competition, v3-to-v4 migration, closed dispatch gates,
pre-commit invalidation/cancellation and failed bulk-pause rollback. Already UNKNOWN
work retains protected late evidence and existing associations when dispatch closes.
An intentionally unguarded claim in a controlled regression APK must fail the
paused-work/reopen assertion; the production paused-work SQL guard must pass it.

`CreateLifecycleCoordinatorDeviceTest` combines isolated real import/journal
databases with controlled ports and fake Provider permission state. It checks
production-owner admission, startup pausing across reopen, foreign preparation and
caller rejection, one dispatch followed only by inspection, authentication loss
during preparation/before claim/after claim/inspection, account replacement without
an authenticated-flag transition, and sticky calendar/permission pauses. A complete
successful catalog absence differs from a catalog error. Suspended preparation
fixtures exercise immediate policy fences before serialized selection updates.
SQLite triggers inject pause failures, then restore account/catalog/permission
availability; pending pause obligations must still block the old preparation.

These fixtures do not change the retained Proton session, system grants or real
account attributes. They qualify orchestration and persistence, not server rights
revocation or a user-facing logout journey. The retained-event association live
fixture can be rerun without submitting another CREATE to check session/import
compatibility after deployment. General outgoing resolution, Provider edit capture
and UPDATE/DELETE remain separate qualification.
The lifecycle fixtures, retained-event association and read-only delta/recovery
regressions have passed on an authorized physical target. The worker catalog-pause
failure fixture also guards an observed native runtime crash: access refresh is
isolated from the larger import coroutine while preserving its mutex, failure
propagation and cleanup. Runtime behavior on other devices remains unqualified.

## Internal local UPDATE capture

`LocalEditIntentTest` checks immutable supported-field intent, exact empty/unknown
details preservation, unsupported changes and redacted diagnostics on the JVM.
`LocalEditStoreDeviceTest` uses isolated real SQLite with controlled remote ports.
It checks acknowledged baselines, stale ACK refusal, immutable replay/reopen,
sticky conflicts, remote deletion/deferred evidence, all projection suppression
paths, schema 7 migration, and lease/cancellation/injected-failure rollback. Plain
and staged import engines retain their checkpoint while protecting local work.

`LocalEditCaptureCoordinatorDeviceTest` uses real isolated SQLite and controlled
Provider/remote ports. It checks capture against an older projected base after a
newer import, import during qualification, idempotent retained work, incompatible
format, inactive account/permissions and disabling/session loss during capture.
No port can submit an upload or silently overwrite the local variant.

`LocalEditCaptureIntegrationDeviceTest`, also with `-e providerFixture true`, joins
the production owner, real SQLite and real Provider with a controlled read-only
remote. It imports and acknowledges a simple known-empty-details baseline, makes
a synthetic Android edit, then imports a newer remote version before capture.
Capture must preserve the old base, actual local text and latest remote record as
separate facts in conflict, including after database reopening and requeue. The
single Android row remains dirty and unchanged; no service mutation is possible.

`LocalEditProviderDeviceTest` requires deliberate `-e providerFixture true` and
already-granted calendar permissions. Its scoped real Provider fixtures check
timed/DST and exclusive all-day snapshots, current empty-details preservation,
unsupported child data/flags/precision/colors/recurrence, unique identity, and a
change injected immediately before assertion-only qualification. Dirty/deleted
incoming-write and dirty recurrence-child cases must leave local data intact.
The dirty-overwrite regression must fail against the original unguarded projection
and pass with its atomic clean-row assertions. Fixtures remove only their isolated
local calendars/accounts and never modify retained Proton events.

These layers do not qualify automatic observation, an editable Samsung calendar,
live conditional UPDATE/DELETE, power-loss recovery or atomicity between Provider
snapshot and private database commit. See [local capture](LOCAL_EDIT_CAPTURE.md).

## Interrupted simple projection

`ProjectionReceiptTest` checks the supported simple subset, protocol/nonce
validation, baseline identity and redacted diagnostics on the JVM.
`ProjectionReceiptStoreDeviceTest` uses isolated real SQLite for immutable
preparation/reopen, exact restoration, newer remote/tombstone preservation,
baseline comparison, incompatible formats, local-intent blocking and transaction
rollback on lease loss, cancellation or injected failure. Its independent schema
8 fixture must migrate without inventing receipts or losing retained work.

With `-e providerFixture true`, `ProjectionRecoveryDeviceTest` joins the production
owner, real Provider and real SQLite with a controlled read-only remote. It aborts
private acknowledgement after a successful event write, changes the Android row
and reopens storage. Recovery must use the actually projected base, retain dirty
content and avoid a second row. Cases cover pre-apply interruption, newer remote
content, local deletion, nonce absence/substitution, reused opaque revisions,
duplicate identity, mirror recreation and nonce invalidation immediately before
the proof assertions. Missing proof must leave the baseline, receipt and row intact.
The lost-ACK and reused-revision regressions must fail before their respective fixes.

These controlled fixtures establish neither a live Proton UPDATE nor arbitrary
power-loss or cross-store atomicity. They clean up only isolated local fixture data.

## Internal local DELETE capture

`LocalDeleteIntentTest` checks unchanged or edited-before-deletion content, exact
known-empty/unknown profiles, unsupported recurrence/details/colors, second
precision and redacted diagnostics on the JVM. Deletion is a separate intention;
it cannot become a supported-field UPDATE or authorize a remote request.

`LocalDeleteStoreDeviceTest` uses isolated real SQLite for immutable replay/reopen,
kind and ID uniqueness, conflicts before/after capture, remote tombstones and
deferred evidence, generic projection/requeue/count/ACK barriers, receipt refusal,
and rollback on lease loss, cancellation or injected failure. Its independent
schema 9 database must preserve UPDATE state, payloads, IDs, formats and receipts
when adding the kind. Existing schema 7/8 fixtures must also reach the current
schema without adding the same column twice.

With `-e providerFixture true`, `LocalDeleteProviderDeviceTest` checks timed and
all-day tombstones, unchanged or modified content, strict dirty/deleted flags,
missing/foreign/duplicate identities, unsupported fields/children and an assertion
race. A distinct test deletes an imported event through ordinary `Events.CONTENT_URI`
and requires a retained tombstone without manually setting its flags. This tests
the installed Provider, not Samsung's editing UI. Only still-observable fields can
be captured after deletion; removed child rows do not establish their former absence.

`LocalDeleteCaptureCoordinatorDeviceTest` uses real isolated SQLite and controlled
ports for account/format/permission/catalog gates, import during qualification,
replay without another snapshot, UPDATE/DELETE collisions and cancellation.
`LocalDeleteCaptureIntegrationDeviceTest` joins the production owner, real SQLite
and Provider with a controlled remote. It checks offline capture/reopening without
ACK, edit-then-delete content, persistent conflict, lost projection ACK recovery
on a tombstone and refusal of an ambiguous reused revision without its nonce.
Its principal capture case must fail against the unwired owner and pass after
composition. These fixtures submit no service deletion and remove only isolated
local accounts, calendars and databases.

Live conditional DELETE, editable-calendar UI, automatic observation, undo of
unobservable data, power-loss recovery and cross-store atomicity remain unqualified.

## Internal local CREATE capture

`LocalCreateIntentTest` checks canonical local identity, frozen timestamp/protocol,
known-empty details, unsupported recurrence/details/color, second precision,
timezones, exclusive all-day dates and redacted diagnostics on the JVM.

`LocalCreateStoreDeviceTest` uses isolated real SQLite for the one-shot reservation,
immutable first content, exact identity/format/timestamp, scoped uniqueness,
competing helpers, reopen, lease/cancellation and rollback. No Provider locator is
stored. Existing reservations never renew the right to stamp, even when they have
no content. Backup exclusion applies to the entire database domain.

`LocalCreateProviderDeviceTest` checks supported dirty rows, scoped identity stamp,
unchanged flags/content, marker-only reopen inspection, imported/foreign metadata,
duplicate markers including tombstones, unsupported fields/children and races.
`LocalCreateCaptureCoordinatorDeviceTest` composes the production owner with real
isolated journals and controlled ports for admission gates, first-commit replay,
orphan reservations, marker recovery without row fallback and cancellation.
The principal owner case must fail before wiring and pass afterward. These checks
send no Proton mutation and establish neither Samsung creation UI behavior nor
remote CREATE acceptance. These suites have passed on an authorized physical
target, including ordinary all-day Provider insertion without sync-adapter flags.
`LocalCreateCaptureIntegrationDeviceTest` joins the real owner, Provider and both
SQLite journals for timed/all-day capture, a failed content commit after stamping,
marker-only recovery, replacement rows and duplicate tombstones. The competing
helper fixture must also reproduce a foreign retained intention inserted between
the two owner reads; replay must validate the intention actually read. Its debug
stack hook makes the interleaving deterministic, without qualifying multiple
Android processes.

Lost uncommitted content, sacrificed unstamped reservations, physical power loss,
automatic discovery, editable mode and outgoing reconciliation remain separate
limits or work. See [local creation capture](LOCAL_CREATE_CAPTURE.md).

## Protected preparation of captured local CREATE

`LocalCreateIntentTest` also checks that a retained preparation binds the exact
captured intention, rejects an empty payload and redacts diagnostics. The JVM
serializer test requires known-empty details to preserve the simple shared parts
without inventing personal notifications. Adapter tests require a dedicated
v2 format with a sealed explicit-empty notification policy, `Notifications: []`
inside the event and no submission request. They refuse unexpected null, malformed,
DISPLAY or EMAIL overrides from the preparer instead of silently replacing data.
Unknown-profile preparation retains its original format. Submission rejects
non-empty details before session/context/crypto reads, and both decoding and
dispatch reject local preparation v1 and v2 before opening the payload or admitting
an attempt. These tests use controlled crypto/context
ports; they do not qualify native encryption or service reminder behavior.

`LocalCreatePreparationStoreDeviceTest` uses isolated SQLite to check exact
binding, immutable replay/reopen, competing helpers, schema-1 additive migration,
grouped column constraints, corrupt-row refusal and transactional rollback.
`LocalCreatePreparationCoordinatorDeviceTest` checks frozen draft construction,
cache reuse without another preparation, incompatible or undeclared formats,
current Provider content, account/access/selection/checkpoint changes across the
preparation wait, unsupported/network failure and cancellation.

With `-e providerFixture true`, `LocalCreatePreparationIntegrationDeviceTest`
joins the production owner, real Provider and both journals with a controlled
read-only preparer. It requires retained preparation without Provider flag/content
changes, cache reuse after reopening, refusal of changed/missing/duplicate markers
and retry after an injected SQLite failure. Its principal case must fail against
the unwired preparation owner and pass after wiring. Existing capture/recovery
fixtures remain regression coverage. The journal migration, owner and integration
suites have passed on an authorized physical target. A lease closed by an owner
call must be explicitly readmitted only for a fixture's deliberate SQL setup;
subsequent owner calls still install their production admission gates.

`ProtonEventPreparationCryptoDeviceTest` has a separate known-empty fixture through
the production preparer, resolver, Core cryptography, Keystore protector and real
decoder. Its context and response shapes are synthetic, with non-empty timed and
all-day defaults. It copies the outgoing notification field into the fabricated
response and checks its exact raw empty array before decoding. Absent/null overrides
inherit defaults; an EMAIL-only override can map to empty Android reminders without
being an empty personal list. No network or service mutation is used. Both native
preparation fixtures have passed on an authorized physical target; they do not
establish server acceptance.

Historical failure reproductions need application and instrumentation APKs with
matching source-level constructor/ABI contracts. Keep the original frozen artifacts
and build corrected fixtures against their corresponding application revision.
An ABI crash or an inactive test hook is not evidence of a production regression.

The local preparation is historical and paused. Cached replay does not requalify server
keys or rights. Provider inspection and journal commit remain separate. Old v1
blobs keep their unresolved obligation and are refused by a v2 adapter without
replacement. The separate submission service journey must qualify an authenticated
raw empty notification array, signatures, UID and content. These preparation tests do not qualify editable
mode, automatic observation or upload of a captured creation.

## Created-event readback profiles

`ProtonCreatedEventReadbackTest` exercises the common read-only UID lookup,
individual GET, bootstrap and verifier path with controlled ports. Shared-only v1
preserves the previous normalization of personal details. Known-empty v2 accepts
only the simple known-empty expectation, keeps those details in final comparison
and requires a raw empty `Notifications` array on the individually read event.
Absent/null, wrong types, non-empty DISPLAY/EMAIL overrides, mismatched content,
dates or identity, ambiguous/foreign/oversized UID results and session changes
remain unresolved. CRLF and UTC aliases retain their existing semantics. Cancellation
propagates; other read/verification failures remain unresolved without admission.
No journal, preparation opener or send method is available to this helper.

`ProtonCreatedEventResponseTest` tests the GET-specific bounded UTF-8 reader and
reused unique-key scanner. Duplicate names, including escaped equivalents and
nested unknown fields, fail before JSON can overwrite a prior value. Truncated,
non-successful, oversized, invalid UTF-8 and excessively nested bodies are refused.
Both UID and individual GET use this parser; general import parsing is separate.

The known-empty native probe additionally calls the same readback helper with the
real decoder and controlled reads. It refuses absent/null overrides even with
empty calendar defaults, and EMAIL-only overrides despite empty mapped reminders.
It checks refusal before bootstrap and, directly through the decoder, before local
keys. Timed/all-day content/date comparisons and both signature/UID protections
have passed on an authorized physical target. Fabricated responses do not qualify
the service's handling of personal notification overrides.

`CalendarCreateReadbackLiveDeviceTest` requires `-e calendarCreateReadback true`
and the existing retained fixture's `createFixtureToken` and `createAccountHash`.
It refuses an absent fixture, unavailable scope or non-UNKNOWN record, calls only
`inspect`, and verifies the exact record/evidence after reopening and the existing
association's event identity. It never prepares, submits, admits or creates an event.
This passed against a retained legacy live event, as did the existing Android
association regression. It qualifies authenticated GET parsing and shared-only v1
readback, not known-empty v2 service behavior or stable selection across the read.
The personal array is an authenticated observation separate from shared signatures.
Both submission profiles reject local preparations v1/v2 before opening or admission.

## Experimental explicit-empty CREATE submission

`ProtonKnownEmptySubmissionTest` checks submission v2's protected format, explicit
notification policy, complete frozen expectation and exact raw empty request array.
It rejects inconsistent version/policy/expected/request data before preparation or
claim. Fresh format or draft drift refuses admission. Existing UNKNOWN work only
inspects; absent/null/non-empty readback, response loss and cancellation never
permit another PUT. Legacy submission tests retain the v1 interpretation. These
controlled ports establish orchestration and refusal behavior, not live acceptance.

`CreateAttemptStoreDeviceTest` treats submission v2 as opaque, retaining its exact
payload and response evidence through UNKNOWN admission and reopening without a
schema change or readmission. The separate native submission fixture exercises
production preparation, codec, reopened Keystore, Core signatures/decryption and
strict UNKNOWN inspection with synthetic context/response data. It has no usable
prepare, admission or send path during inspection.
Its additional folded-whitespace case constructs an actual physical iCalendar
line ending in a space. It reproduces rejection by byte-only verification of
Core text signatures, then requires exact timed/all-day content, strict empty
readback and refusal of both altered signatures through the production decoder.
`ProtonEventCryptoDeviceTest` remains the native binary-signature/import regression.

`CalendarKnownEmptySubmissionLiveDeviceTest` requires explicit opt-in
`calendarKnownEmptyCreate=true`, an authorized account hash, a fresh fixture token
and frozen creation/start timestamps. PREPARE freezes scope and retains an unadmitted protected
dispatch record using read-only context preparation; SUBMIT marks the request
before entering the one-shot port; subsequent journeys use INSPECT only. Missing
journal or changed identity blocks reconstruction. A dedicated journal and
preferences keep the earlier legacy fixture separate. Success requires authenticated
raw empty notifications, verified shared signatures/UID/content, exact UNKNOWN work
after reopening and refusal of readmission. Ordinary import must produce the unique
owned Provider row for the verified remote ID and revision, with matching time/title,
`HAS_ALARM=0` and no reminder rows; ownership is rechecked after child queries.
The recognizable synthetic event is retained. No captured local intention, editable
UI or automatic outgoing queue is involved. All-day service behavior, real response
loss and notification delivery by another application require separate qualification.
The native preparation/readback cases, binary-import regression and journal/lifecycle
fixtures have passed on an authorized physical target. The retained legacy event
still verifies. The new timed attempt initially remained unresolved because byte-only
verification refused its text signature. After the reproduced decoder correction,
INSPECT of that same UNKNOWN attempt passed strict authenticated readback, exact
work/evidence reopening, readmission refusal and owned Android projection without
reminders. No second submission, event replacement or account change was used.
This does not establish a successful immediate readback inside SUBMIT, which was
the original failing journey, or power-loss/server-idempotency behavior.

## Captured CREATE submission staging

`LocalCreateSubmissionTest` verifies independent canonical attempt identity and
the frozen captured draft. `LocalCreateSubmissionStoreDeviceTest` uses isolated
SQLite databases and synthetic opaque payloads to check exact capture dependency,
immutable reservation/payload, scoped uniqueness, competing helper admission,
cross-helper ticket refusal, reopening, corruption refusal and additive v2 migration.
Existing capture/preparation tests qualify direct v1 migration and preserve historical
preparations. Cancellation fixtures distinguish rollback before commit from a
committed reservation whose ticket delivery is cancelled, and a consumed ticket
whose payload commit fails. None of these fixtures submits an event.

`LocalCreateSubmissionCoordinatorDeviceTest` checks the exact frozen draft,
historical preparation preservation, completed cache reuse and unresolved gaps
without preparing again. Current account, permission, selection, checkpoint,
marker/content and declared/returned format changes refuse staging. Fake submission
and inspection methods fail if called. A physical failing owner reproduction precedes
the wiring. These controlled ports are not evidence of Proton acceptance or encryption.

`LocalCreateSubmissionIntegrationDeviceTest` opts in with `providerFixture=true`.
It uses the real Calendar Provider and local journals with a controlled preparation
port. Reopening must retain the same submission and original dirty/unacknowledged
row. Concurrent content changes, duplicate marker tombstones, deleted rows, a new
reminder child and an interrupted payload commit cannot qualify work or regenerate
an attempt. Isolated databases, calendars and accounts are removed after each fixture;
retained live Proton examples are separate and are not mutated by this suite.

The staging suite covers read-only preparation and same-process helper concurrency.
It does not establish dispatch; the separate qualification follows below. Multiple
Android processes, physical power loss, live captured upload and reconciliation
with the original local row remain unqualified.
The 42 staging store/owner/Provider cases and 80 related capture/preparation
regressions passed on an authorized physical target. The cancelled ticket-delivery
case retained the already committed reservation without returning a reusable
preparation right. The Provider reminder case uses an actual reminder child;
setting the derived `HAS_ALARM` flag alone was insufficient to construct that fixture.
Host sync, Proton adapter and app tests and Android lint also passed. No new
native crypto or live Proton creation is claimed by this staging qualification.

## Captured CREATE dispatch admission

`CapturedCreateDispatchIdentityTest` checks the independent fingerprint golden,
UTF-8 byte boundaries, exact text/time/zone identities, malformed-Unicode refusal
and redacted diagnostics. `ProtonCapturedSubmissionTest` requires the exact sealed
known-empty draft before fresh preparation or inspection. It covers foreign
content/correlation/scope/details, legacy-format refusal, late claim failure,
context drift, cancellation after UNKNOWN and inspection without another send.
The verified readback preserves observed remote identity/revision while returning
the expected domain representation only after the existing authenticated content
check, which normalizes CRLF/LF and UTC aliases. These are controlled adapter
fixtures, not service acceptance.

`CapturedCreateAttemptStoreDeviceTest` opts in with `providerFixture=true` and
uses isolated synthetic SQLite work. It checks additive v4-to-v5 migration,
preserved evidence/pauses/associations, atomic captured-link retention, full
identity qualification, corrupt/orphan-link refusal, generic-path exclusion,
competing helpers, cancellation, transaction rollback, foreign keys and FULL
synchronous admission. Existing migration fixtures retain older upgrade paths.

`CapturedCreateDispatchCoordinatorDeviceTest` uses real journals and controlled
Provider/adapter ports. Fresh staging can admit once; cached staging, owner
reopening, observed account/permission loss, disable/restore or a consumed failed
claim cannot rearm that right. Current checkpoint or marker/content mismatch
refuses admission; it does not permanently consume an otherwise valid lease. A synthetic
non-reentrant authentication guard rejects current-account reads from the final
claim. Cancellation after admission retains UNKNOWN. A changed/missing original
row permits inspection only, and wrong verified identities/content are refused.
Generic submission and association cannot adopt captured work.

`CapturedCreateDispatchIntegrationDeviceTest` runs with the real Calendar Provider,
isolated journals and a controlled transport. Admission preserves the original
dirty unacknowledged row and its local marker. A Provider title change or actual
new reminder child during fresh context work prevents admission. Reopening a
completed stage cannot rearm it; an admitted attempt remains inspect-only after
the original row disappears. Temporary synthetic calendars/accounts/databases
are removed; retained live examples are separate.

The native captured-inspection case uses production Core crypto, preparation,
Keystore reopening and decoder with synthetic context/readback. Wrong expected
content is refused before UID reads; exact timed/all-day inspection succeeds
without a usable preparation, admission or send path. It does not submit to Proton.

These checks do not establish cross-store atomicity, simultaneous Android
processes, power-loss durability, real rights revocation or live captured upload.
No original-row acknowledgement, remote binding or projection reconciliation is
implemented by the dispatch path itself. A captured live creation must wait for association
that avoids a duplicate ordinary import. Writable UI and automatic outgoing
processing remain unavailable.

The 34 captured journal/owner/Provider cases, six native crypto cases, 53 existing
journal/lifecycle cases and 42 staging regressions passed on an authorized physical
target. Read-only inspection of the retained legacy live event also passed with
the current build, without another creation. Host sync, Proton adapter and app
tests and Android lint passed. No live captured submission was run; controlled
admission and native inspection must not be presented as its service qualification.

## Captured identity binding and incoming projection hold

`CapturedCreateBindingTest` separates a scope-wide hold from exact held event
identities and checks immutable proof identity and redacted diagnostics.
`CapturedCreateBindingStoreDeviceTest` uses isolated SQLite work with explicit
`providerFixture=true`. It verifies independently constructed v5 migration,
preserved attempts/evidence/pauses/associations, no invented bindings, full-link
proof equality, first-proof immutability, scope isolation and uniqueness across
captured and generic bindings. An incompatible current import format keeps the
scope held even with a retained binding. Malformed or orphaned parent/link/proof work keeps
the scope held; a missing table propagates failure. Transaction rollback, revoked
admission, cancellation and competing helpers cannot free unresolved work.
These helpers establish same-process concurrency, not physical power loss or
simultaneous Android processes.

`CapturedCreateImportHoldCoordinatorDeviceTest` checks the production owner with
real journals and controlled remote/Provider ports. An unwired-owner reproduction
must project the received captured event and fail the no-projection assertion.
After wiring, UNKNOWN holds survive reopening, keep incoming work pending, refuse
receipt recovery and local-baseline recovery, and never acknowledge the held event.
Wrong observations and loss of current access cannot create a binding. A retained
binding resumes with no submission port. The held event's newer content or deletion
does not replace the first proof; unrelated events can project. Filtering occurs
before the limit: runnable work behind more than 100 held rows still advances.
Held work alone does not request continuation. Legacy downloads and a retained
staged receipt still advance while projection is held; the latter processes offline
without calling receive. Journal failure does not fall back to ordinary projection.

`CapturedCreateImportHoldIntegrationDeviceTest` uses the real Calendar Provider,
isolated journals and fake captured transport. UNKNOWN followed by incoming data
cannot produce a second row before or after reopening. Readback binding preserves
a later local edit and its dirty marker while allowing an unrelated event to import.
A purged original is not recreated by the held incoming event. Synthetic local
calendars/accounts/databases are removed; existing live examples are untouched.

The production Proton captured-inspection contract remains the previously
qualified adapter; these new fixtures do not prove a captured service upload or
original-row acknowledgement. The hold has no release in this slice. Remote
baseline/pending acknowledgement and conflicts must be qualified before a live
captured-creation journey or writable-mode rollout.

The unwired-owner physical reproduction failed its no-projection assertion; after
wiring, all 35 binding/hold fixtures passed. The same build also passed 34 captured
dispatch regressions, 53 journal/lifecycle cases, 36 import/recovery cases and
12 association cases. Read-only inspection of the retained legacy live event
passed without a new submission. Host checks passed with 114 sync, 274 Proton
adapter and 10 app tests, plus Android lint and both debug APK builds.

Adding hold reads to the larger import coroutine reproduced a native runtime
crash in the existing SQLite pause-intent lifecycle fixture. The same fixture
passed with the owner wiring absent. Moving hold/status publication into a
separate suspend helper made the exact reproduction pass, followed by the full
physical regression groups above. The internal runtime cause remains unproven;
this qualifies the refactor on the tested physical target, not other runtimes.

## Captured original-row metadata attachment

`CapturedCreateAttachmentReceiptTest` checks historical witness identity,
protocol/nonce refusal and redacted diagnostics. This receipt does not grant
dispatch, clean the original row or release import.

`CapturedCreateAttachmentStoreDeviceTest` uses isolated SQL with
`providerFixture=true`. Its independently constructed v6 fixture checks additive
v7 migration, preservation of UNKNOWN/evidence/pauses/generic bindings and no
invented attachment plans. It checks first-nonce immutability, full proof drift,
reopening, revoked admission, cancellation, rollback, competing helpers and
foreign keys. Damaged or orphaned plans block the scope and generic adoption.

`CapturedCreateAttachmentIntegrationDeviceTest` uses real SQLite and Calendar
Provider with controlled captured transport. An unwired-owner reproduction must
return UNRESOLVED and fail the expected attachment result. The wired owner
attaches metadata to one original row, keeps it dirty and keeps incoming data
held after reopening. Later title/reminder changes remain intact. Lost-response
recovery reuses the nonce without another write; a later tombstone retains the
historical proof without consuming deletion. Initial tombstones, purged originals,
replacement rows, duplicate/missing markers, foreign metadata or existing remote
projections remain unresolved. Substituted nonce/full binding, malformed Unicode
and damaged Provider receipts cannot qualify recovery. Current access loss,
controlled permission loss, cancellation and import-format replacement cannot
authorize a write or release the hold. The controlled permission case establishes
owner gating, not real mid-batch Android revocation. Temporary fixtures are
removed; retained server examples are separate.

These checks do not establish baseline/pending acknowledgement, conflict
resolution, live captured upload, writable UI, multiple simultaneous processes
or physical power-loss durability. A historical receipt cannot replace those
qualifications.

The unwired-owner physical fixture failed the expected attachment result. On the
wired build, all 13 attachment journal and 19 Provider/owner cases passed, along
with 35 hold, 34 dispatch, 53 journal/lifecycle, 36 import/recovery, 12 association
and 23 existing Provider cases. Read-only inspection of the retained legacy live
event passed without a new submission. Host checks passed with 118 sync,
274 Proton adapter and 10 app tests, both debug APK builds and Android lint.

## Captured verified sent baseline

`CapturedCreateBaselineTest` checks exact event/revision and frozen content,
known-empty personal data, malformed text refusal and redacted diagnostics. A
matching private fingerprint establishes equality, not remote authentication.

`CapturedCreateBaselineStoreDeviceTest` uses explicit `providerFixture=true`
and isolated SQLite data. It checks import v11 provenance and baseline retention
in the same transaction as a whole-payload pending comparison. Migration preserves
existing work and does not invent capture provenance. Generic or corrupt baseline
rows and unresolved ordinary receipts cannot be adopted. Reopening, drift,
rollback, cancellation, admission loss and competing helpers must preserve the
first reference; incoming differences and deferred/local work stay intact.

`CapturedCreateBaselineIntegrationDeviceTest` uses the production owner, real
journals and Calendar Provider with controlled transport. The unwired owner must
fail the expected retention result. The wired path retains the sent reference
without creating an absent remote cache row, cleans only an identical incoming
payload and preserves updates, equal-revision content differences and deletions.
Local titles, reminders and tombstones stay dirty. Missing or damaged original
markers/receipts, duplicate rows and missing attachment plans cannot establish
a reference. Access loss, controlled permission loss, format replacement and
cancellation cannot authorize an unqualified commit. A failure in the pending
comparison rolls back provenance and baseline together; an access loss after a
durable commit can resume without changing the reference. Fixture data is isolated
and removed. These cases do not qualify a live captured upload, original-row ACK,
hold release, conflict resolution, writable UI or cross-process atomicity.

The unwired-owner physical fixture failed the expected retention result. The
wired build passed all 17 baseline SQL and 20 owner/Provider cases, 322 related
regressions including migration fixtures, and one read-only inspection of the
retained legacy live event. No new live submission was made. Host checks passed
with 124 sync, 274 Proton adapter and 10 app tests, both debug APK builds and
Android lint. These results retain the boundaries described above.

## Current local intention after captured attachment

`CapturedCreateCurrentTest` checks unchanged versus later content and tombstones,
known-empty supported data, precision, malformed text and redacted diagnostics.
The domain snapshot is point-in-time data, never an Android clean lease.

`CapturedCreateCurrentProviderDeviceTest` uses isolated production Provider rows
with `providerFixture=true`. It checks timed/all-day dates, later text, present
tombstones and preservation of unsupported flags, reminders, attendees, extended
properties, recurrence and colors. Missing, duplicate, moved, clean or foreign
rows and altered attachment metadata cannot qualify capture. Injected edits,
duplicate identities, reminders or tombstones before the single assertion batch
must abort a stale snapshot. Admission loss after the batch cannot return a
qualified result. No Provider content or flags are changed by the snapshot.
One fixture simulates an empty alarm flag only in the query cursor, with real
reminder rows retained, to verify child inspection independently of that flag.
It does not require writing the [alarm flag](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns#HAS_ALARM).
Concurrent reminder creation must reach a real assertion batch and reject the
stale snapshot; its alarm metadata can also cause that rejection. This case does
not isolate the child assertion as the only failure source.

`CapturedCreateLocalChangeStoreDeviceTest` uses isolated SQL and existing import
schema v11. First supported UPDATE/DELETE work is immutable and preserves raw
incoming pending flags, payloads, bases and receipts. Reopening and exact replay
cannot clear a conflict or replace kind/content/identity. Different, malformed,
absent or deferred remote evidence conflicts; raw corruption, ordinary receipts,
advanced baselines, incompatible checkpoints and mutation collisions refuse
adoption. Rollback, lease loss, cancellation and competing helpers must preserve
the first intention. An unchanged result requires absence of any retained work.

`CapturedCreateReconciliationIntegrationDeviceTest` uses the production owner,
real journals/Provider and controlled captured transport. An unwired owner must
fail the expected unchanged result. Source and receipt drift, inactive access,
unsupported data, missing or ambiguous originals and format replacement cannot
establish new work. Replays inspect the current row; a second edit, tombstone or
reverted edit leaves the first intention intact and requires resolution. A local
commit failure preserves retry, and access loss after commit preserves durable
work. A new edit between snapshot and commit remains dirty and is detected by
the post-commit snapshot. Remote differences before and after capture preserve
both local intention and remote payload. Later import can suppress projection
through the existing intention guard; that suppression is not an Android ACK.
Fixture data is removed. No original-row acknowledgement, hold release, writable
UI, live captured submission, conflict resolution or cross-process atomicity is
established by these cases. Permission-loss fixtures control the gate rather
than revoking Android grants during a batch.

The unwired-owner physical fixture failed the expected unchanged result. The
wired build passed 17 local-intention SQL and 32 owner/Provider cases. Two initial
Provider fixtures failed because an alarm-only update was rejected; another
race fixture could report rejection before attempting its intended batch.
After correcting those fixtures, all 27 Provider cases passed, including explicit
proof of attempted assertion batches. The same production build passed 300
related regressions and one read-only inspection of the retained legacy live
event, for 377 distinct physical cases. No new live submission was made. Host
checks passed with 131 sync, 274 Proton adapter and 10 app tests, both debug APK
builds and Android lint. These results retain the boundaries described above.

## Conditional original-row acknowledgement

`CapturedCreateAcknowledgementTest` checks canonical nonce/protocol rules,
complete provenance fingerprints, strict identity text and redacted diagnostics.

`CapturedCreateAcknowledgementStoreDeviceTest` uses isolated SQL with
`providerFixture=true`. It checks additive v11-to-v12 migration without invented
proof, immutable UNKNOWN reservations, competing helpers granting only one fresh
admission, strict historical proof and promotion to PROVEN. Raw unresolved or
corrupt evidence fences all three capture paths before replay. Existing local
intentions, ordinary receipts, source drift, nonce collisions, rollback,
lease loss and cancellation cannot authorize a new reservation or promotion.
Records, raw pending flags, deferred evidence, baselines and receipts remain
intact. Proven evidence permits later specialized local capture, without resetting
a conflict; generic snapshots cannot adopt captured provenance.

`CapturedCreateAcknowledgementProviderDeviceTest` uses isolated real Provider
rows. It requires six assertions and one update limited to DIRTY and the exact
acknowledgement marker. Changed, unsupported, duplicate, foreign or missing rows
cannot be cleaned. Race fixtures require proof that the real batch was attempted
after their setup hook. Historical inspection asserts exact identity and markers
without reading current content or consuming dirty/deleted flags or children.
Response-loss fixtures retain proof through later edits, reminders and tombstones;
current capture then requires that exact acknowledgement marker and a dirty row.
Malformed identity, marker drift, admission loss and cancellation cannot return
an unqualified witness.

`CapturedCreateAcknowledgementIntegrationDeviceTest` uses the production owner,
real journals/Provider and controlled transport. An unwired owner must fail the
expected acknowledgement result. Qualified unchanged originals can be cleaned
once without consuming incoming evidence or releasing the captured hold.
Reopening inspects the first nonce without another write. Lost responses, failed
private promotion and later edits/tombstones preserve historical recovery and
subsequent local capture. A proven replay must preserve a retained local intention.
Admission interrupted before the batch remains UNKNOWN with no automatic retry.
Missing or corrupted markers cannot be adopted from proven private state.
Inactive access, incompatible formats and source drift prevent qualification.
Different remote payloads before and after acknowledgement remain pending and
held. Fixture data is isolated and removed. Permission loss controls the gate,
not real grants during the batch. These tests do not establish live captured
submission, hold release, conflict resolution, writable UI, real power loss or
simultaneous processes.

The unwired-owner physical fixture failed the expected acknowledgement result.
The wired build passed 22 acknowledgement SQL, 29 Provider and 25 owner cases.
One initial owner fixture invoked an asynchronous selection change before its
disabled precondition had been persisted. After making that precondition explicit
and awaiting it with a bounded suspension, the corrected case passed. This does
not establish synchronous admission closure on return from the selection setter.
The unchanged production build also passed 411 related regressions, including
migrations and earlier capture recovery, and one read-only inspection of the
retained legacy live event: 488 distinct physical cases. No new live submission
was made. Host checks passed with 139 sync, 274 Proton adapter and 10 app tests,
both debug APK builds and Android lint. These results retain the boundaries above.

## Current clean original qualification

`CapturedCreateCleanProviderDeviceTest` uses isolated real Provider rows and
requires six assertions with no mutation for a clean-original witness. Exact
timed and all-day originals qualify; content drift even with a clean flag, dirty
rows, tombstones, unsupported fields, reminders, extended properties, damaged
markers and duplicate identities remain unqualified and intact. Concurrent
content, flag, child or marker changes abort the actual atomic assertion batch.
Controlled permission/lease loss cannot return a witness. Generic apply, tracked
apply, delete and local capture refuse each captured marker, including concurrent
attachment. Recurrence parent attachment and descendant deletion obey the same
barrier; ordinary projection remains functional.

`CapturedCreateGenericFenceStoreDeviceTest` verifies raw local-capture barriers
before replay for absent, unknown, proven, malformed or orphaned ACK sources.
UPDATE/DELETE intentions, incoming pending flags and all source tables survive
refusal. Unrelated ordinary events and scopes still work. The point-in-time SQL
source check requires an exact PROVEN ACK and matching original base with no raw
local intention or ordinary projection receipt. It preserves different incoming
and deferred data; schema v12 is unchanged.

The additional `CapturedCreateAcknowledgementIntegrationDeviceTest` cases exercise
the production qualification owner with real journals and Provider, and controlled
transport. They verify exact capture/attempt/source provenance, clean qualification
without another ACK or submission, reopen, absent/UNKNOWN proofs, later edits and
tombstones, disappearance, reminders, marker drift, local-intention retention after
Android reversion, incoming-data preservation, foreign witnesses and closure after
interruption, pause, sign-out, permission or format changes. An observation cannot
prevent the next local edit or release the captured hold. The unwired owner fixture
failed with expected CLEAN and actual UNRESOLVED before connection.

The wired build passed 36 new Provider cases, 18 SQL cases and 22 additional owner
cases. Related regressions and the retained legacy read-only live inspection also
passed: 591 distinct physical cases in total. One older baseline regression still
prepared a captured intention through the newly forbidden generic capture path.
Using the specialized full-source capture corrected that precondition while keeping
its replay, intention, pending and receipt assertions. The corrected case and remaining
checks passed with the production APK unchanged. Host checks passed with 139 sync,
274 Proton adapter and 10 app tests, both debug APK builds and Android lint.

These checks qualify the assertion and admission boundaries on the tested physical
device. Permission failures use controlled gates, not real revocation during a batch.
They do not qualify live captured submission, hold transfer, writable UI, simultaneous
processes or real power loss. Isolated fixtures are removed; retained live examples
are preserved.

## Anonymous transport probe

`ProtonConnectivityDeviceTest` is skipped unless instrumentation receives
`-e calendarNetworkProbe true`. Run only that class for a deliberate network check.
It calls Proton's public ping through the production network factory and certificate
pins, using a separately named temporary empty database that is closed/deleted
afterwards. It never reads the account store or submits credentials. The helper is
included only in the debug variant. Results contain network booleans and closed
failure categories, without raw exceptions, headers, response contents or IDs.

An app can be denied network access even when the phone has a validated connection
and the installed package has `INTERNET`. Inspect the app-specific system network
policy before changing the endpoint, TLS checks or client identity. A successful
anonymous ping establishes transport reachability, not account login or Calendar
compatibility.

## Durable captured-creation handoff

`CapturedCreateHandoffTest` exercises independent origin/current references,
canonical admission identity, full previous-reference checks and unsupported data.
The Android opt-in fixtures use real SQLite journals and the installed Calendar
Provider with controlled transport:

- `CapturedHandoffStoreDeviceTest`: migration 12 to 13, immutable role/source,
  corrupt or orphaned evidence, exact prepared receipts, atomic reference advancement,
  newer queues, committed replay and specialized local intentions.
- `CapturedHandoffProviderDeviceTest`: conditional existing-row updates, full origin
  markers, current-reference assertions, races, children, duplicate identities,
  historical recovery and dirty/tombstone snapshots.
- `CapturedHandoffIntegrationDeviceTest`: explicit owner transfer, reopening,
  responses lost at Provider/SQL boundaries, reloaded incoming data, policy and
  permission invalidation, specialized capture, generic replay refusal and bounded
  backlog draining without continuations for held work.

Run the classes with the existing `providerFixture=true` instrumentation opt-in.
Fixtures create isolated accounts/calendars and remove their runner data. They do
not submit a live captured creation or qualify Proton conditional UPDATE/DELETE,
a writable system-calendar UI, automatic capture or simultaneous-process ordering.
A journal upgraded to v13 requires a compatible app during restoration; do not
restore an older APK merely to repeat a test. Legacy-proof refusal fixtures inject
pre-existing SQL evidence directly because ordinary preparation now rejects all
captured provenance before replay.

Physical SQL, Provider and owner fixtures passed these handoff boundaries and
related regressions. The previously unwired owner failed the expected transfer
result before integration. A controlled response loss also reproduced a missing
batch-response check; the unchanged regression passed after the shared captured
helpers refused absent or malformed responses before producing witnesses.

All Calendar Provider batch paths now validate response cardinality, non-null
entries and result shape before reporting success. Insertions additionally require
an identity in the expected collection; fixed assertion/write proofs verify exact
counts. Generic variable batches rely on the Provider's `withExpectedCount` checks
for their exact counts. The additional `CapturedHandoffProviderDeviceTest` cases
exercise response loss before and after execution, malformed results, calendar
creation/removal, stamping, attachment and ordinary receipt recovery. Include
`EventDetailsProviderDeviceTest` and `RecurrenceProviderDeviceTest` when changing
this shared validation because those batches also insert and remove child rows.

`CalendarCapturedHandoffLiveDeviceTest` is a separate retained-session service
journey. It requires `calendarCapturedHandoff=true`, an explicitly authorized
account hash, a unique synthetic fixture token and frozen second-precision dates.
Its `capturedHandoffPhase=CREATE` invokes the production owner and all three
journals: native capture, fresh preparation and one submission, authenticated
readback, attachment, original evidence, acknowledgement and handoff. Creation
admission is frozen before mutation. `INSPECT` resumes the same fixture without
inserting, preparing or sending again; missing admission blocks reconstruction.
Retain the synthetic event and restore only a schema-compatible APK. This journey
does not qualify creation through a system-calendar UI or general outgoing edits
or deletions.

The same retained fixture supports a separate incoming-update qualification:
`PREPARE_UPDATE` freezes the full current reference, original evidence and native
ownership before a title-only edit through the official Proton client.
`VERIFY_UPDATE` requests production synchronization and requires the new revision
and exact title on the original Android row, with all other content unchanged,
immutable creation evidence and drained event-specific work. It freezes the
advanced reference only after those checks pass.
`CAPTURE_LOCAL` then admits one conditional title-only Android mutation. Its
atomic batch asserts the canonical calendar identity, original row, complete
clean content, frozen markers and absent children. Exact batch responses and
operation-time calendar grants are required. Durable admission prevents replay
after a missing response; rerunning never writes again. The production owner must
retain the later intention as `PAUSED` against the advanced reference.
`INSPECT_LOCAL` checks that retained intention, waiting state and dirty native
content without requesting synchronization or repairing the fixture. Ordinary
startup/import work can still run when the application launches.
These phases do not enable writable UI or send Proton UPDATE/DELETE. Unsupported
data introduced by the official-client edit must stay blocked rather than be
removed to make the fixture pass. Build, lint and independent source review passed.
All four retained-session phases passed on a physical device: the official-client
title change projected onto the original row with unchanged remaining content;
the later local edit stayed paused against the advanced base; inspection after a
controlled application process stop and reopen retained the dirty row, provenance
and waiting state. No new submission, native event or calendar was created by
these phases. The visible remote and local examples are retained. This does not
qualify editing through a system-calendar UI, natural process death, concurrent
service writers, richer events or conditional Proton UPDATE/DELETE.
Do not set `SELF_ATTENDEE_STATUS` when constructing a participant-free native
fixture: the Provider can insert a synthetic attendee even for status zero. If an
earlier rejected fixture exists, the positive journey verifies its exact consumed
reservation, missing stamp and absence of raw staging/dispatch evidence before
admitting a distinct fixture. It never removes the child or rearms that reservation.

## Adapter mutation admission

`SyncEngineTest` checks the adapter's default-empty `supportedMutations` ceiling
independently of persisted calendar access. A fixture with all access rights and
matching cached revisions must never dispatch or claim uncertain work through an
adapter that has declared no qualified mutations. CREATE-only capability refuses
UPDATE/DELETE while permitting CREATE. Capability withdrawal blocks admission;
restoration does not resume blocked work. Previously uncertain or acknowledged
work remains intact, including a response lost after admission and capability loss.
The default-capability reproduction failed before the engine check was added.
The five additional tests and all 153 sync JVM cases then passed, along with
274 Proton-adapter and 10 app JVM cases, the Android application build, lint and
independent source review. These are controlled engine fixtures, not evidence of
Proton conditional mutation or Android capture. Current production import adapters
inherit the empty capability set; the experimental CREATE submission port is a
separate boundary and does not advertise generic mutation capability.

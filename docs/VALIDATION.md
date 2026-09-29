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
and refresh epochs. Older migrations are exercised by the existing store suites.
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

`CalendarDozeSchedulingDeviceTest` is an opt-in, host-orchestrated local Worker
journey (`calendarDozeScheduling=true`, phases `prepare`, `verify`, `cleanup`).
`prepare` queues the production `CalendarImportWorker` with a 45-second initial
delay and no network input. The host confirms deep idle is enabled and initially
inactive, forces deep idle, observes the named WorkManager row without starting
the app, then always calls `unforce` in a `finally` block. `verify` waits up to
three minutes for `SUCCEEDED`; `cleanup` cancels only the fixture work and clears
its private ID. Record the observed WorkManager state and elapsed wait. Forced
idle on one device does not establish natural Doze timing, network-worker
completion or another OEM's scheduling policy.

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

# Synchronization scheduling

## Android entry points and execution

A calendar SyncAdapter is registered on the existing Calendeer account type and
`com.android.calendar`, with uploads enabled and no parallel adapter threads.
Android can request upload after native edits, manual synchronization or periodic
refresh. A native signal is only a scheduling hint: it grants no account ownership,
calendar permission, write consent or authority to resend an uncertain request.

The adapter enqueues an independent `CalendarImportWorker` request and waits up to
two minutes. It waits for actual completion, not just enqueue acknowledgement.
Callbacks have distinct IDs and no WorkManager dependency chain; cancelling one
cannot cancel another callback. Interruption or timeout ends only the adapter's
wait and reports RETRY; the independent executor continues. It is not cancelled
because Android stopped waiting, including an interrupted enqueue acknowledgement. Auth,
permission, stale-account and transient failures are reported to Android. A retry,
missing result or exhausted wait is never reported as success.

The system profile restores authentication without enabling ordinary fresh outgoing
recovery. It validates the exact active account before each pass; both coordinators
also bind execution to the expected account under their existing mutexes. Incoming
refresh may be fresh on the first pass; outgoing work always uses `fresh=false`
and `providerWake=true`. UNKNOWN stays inspect-only and paused CREATEs stay paused.
Manual Android extras bypass automatic scheduling settings, never these admissions.

The worker reuses the existing incoming/outgoing pass and drains continuation pages
inside its own execution (up to sixteen passes). It does not report success after
scheduling a hidden successor. More pages or transient failure use normal persistent
WorkManager retry. Only an admitted completed system cycle records its local success
time. This does not mean unsupported/held events were uploaded; their existing
status remains visible.

## Periodic sync and automatic settings

Android is the sole periodic owner: 15 minutes, 30 minutes, one hour by default,
two hours or six hours. Reconfiguration replaces the account's previous periodic
schedule instead of accumulating schedules. The old six-hour WorkManager job is
cancelled at startup and is no longer created. WorkManager remains the executor,
with network constraints, durable queues and existing backoff.

The newly introduced Android channel is enabled once per account. Later startup,
refresh and interval changes preserve the user's per-account switch; the app never
enables Android's global master switch. Automatic workers and the temporary URI
path check both switches. Disabling automatic sync cancels automatic executors and
leaves durable domain intent intact. Manual refresh remains available while the
automatic switches are OFF. Authentication, account, permissions and operation
consent are still required.

Settings show the per-account and global Android states, configured interval and
last successful system cycle, scoped to the connected account. When automatic sync
is OFF, the calendar and settings screens explain that selected calendars wait for
a manual sync or reactivation. The manual action confirms a request to Android or
shows a visible account-not-ready/request-unavailable message. Android can delay
jobs and periodic sync in Doze, standby or under quotas. An interval is not a
maximum delivery delay. A calendar client's refresh button must actually request
Android synchronization; merely reloading its own view cannot trigger the adapter.

## Temporary URI compatibility path

The existing one-shot Events URI watcher remains until real Android-triggered
upload is qualified in the milestone's grouped E4 run. Its durable signal transfer,
rearm-before-capture, dirty-content fingerprint and bounded offline capture remain.
It now respects automatic-sync settings and binds post-authentication execution to
the actual account. Clean imports/ACKs and unchanged held contents do not cause an
outgoing pass; the watcher itself can still wake on unrelated authority changes.
Retiring this path after E4 also removes that remaining broad wake cost.

## Battery and qualification

Calendeer offers an explanation of battery exemption once. Continuing opens the
standard Android permission dialog; refusal leaves the existing choice untouched.
PowerManager state is reread on return. This needs no push SDK, event notification,
foreground service or exact alarm. Exemption does not guarantee a deadline, network
availability or exemption from an OEM's separate restrictions.

E3 covers targeted execution, native settings/periodic metadata and account
admissions. E4 remains grouped at milestone exit: closed-app edit, refresh from two
calendar clients, periodic operation in deep sleep with/without exemption,
auto-sync OFF, offline/reconnect, reboot and permission revocation. Source review,
synthetic Android fixtures and successful enqueue are not live Proton/OEM evidence.

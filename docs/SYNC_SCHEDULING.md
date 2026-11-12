# Synchronization scheduling

## Android change triggers and manual refresh

A thin calendar SyncAdapter is registered on the existing Calendeer account type
and com.android.calendar, with uploads enabled and no parallel adapter threads.
Android supplies local-change or manual-refresh hints. The adapter enqueues an
independent expedited CalendarImportWorker, falling back to ordinary work when
expedited quota is exhausted. Enqueue acknowledgement plus result waiting share
a ten-minute bound. Timeout, interruption or failure ends the wait without
cancelling the independent executor.

Android always receives success: it must not own another retry loop. Actual
outcomes remain in WorkManager and Calendeer; this acknowledgement does not prove
a Proton delivery. On Android 14+, the adapter calls the two-argument native
ContentResolver.cancelSync(account, authority) in finally to clear the pending
latch (issue 320542002). This operates only on an admitted Calendeer account;
it never cancels its WorkManager job.

The system profile validates the expected active account before each pass, and
both coordinators bind execution under their existing mutexes. It reuses the
existing incoming/outgoing implementation. Incoming refresh may be fresh on the
first pass; outgoing uses fresh=false/providerWake=true. Native upload-only work
skips incoming refresh. UNKNOWN stays inspect-only and paused CREATEs stay paused.
A scheduling hint never grants account ownership, permissions or write consent.

The executor drains up to sixteen passes inside the same work item. More pages
or transient failures use WorkManager retry; a hidden successor is not counted
as completed work. Only an admitted completed cycle records its local success
time. Unsupported and held events keep their own existing status.

## Periodic pull

WorkManager is the sole periodic owner: one network-constrained periodic work
item at 15, 30, 60 (default), 120 or 360 minutes. UPDATE preserves the existing
periodic identity and an active executor when configuration changes. Its input
binds it to the selected native account; restoration must still validate the real
session, permissions and account. Each periodic pass checks the native push
switches: when allowed it sends retained local work before receiving; otherwise
it only receives. This also catches missed native change hints and resumes
inspection of uncertain work, without granting authority to resend it.

All native Android periodic schedules for Calendeer are removed at startup;
binding and native settings changes also remove schedules, including defaults
created by setSyncAutomatically. That setting is retained only for content-change
push. It is initialized once per account; later configuration preserves the user's
choice. The global master switch is never enabled by Calendeer.

## Actions in Calendeer and automatic switches

Opening Calendeer, changing calendar synchronization/editing options, Synchronize
and Retry are explicit manual requests. They enqueue WorkManager work directly,
including local/offline processing, even when Android automatic sync is OFF.
Equivalent full manual requests use a per-account unique KEEP name; a callback
whose proposed ID was rejected follows the retained live job. Manual requests
always include receiving, even if an Android caller also supplied an upload hint.
Manual local/network continuations retain their origin and cannot be lost behind
an older automatic KEEP work item. State observers do not acquire manual authority
merely because the app was previously opened.

The account and master switches gate native change-triggered push and the temporary
URI path. Only push-tagged work is cancelled when those settings are disabled;
manual work and periodic pull remain independent. Authentication, current account,
permissions, write opt-ins and durable request identity are still rechecked.
Settings explain this distinction and display the switches, interval and last
successful cycle. Manual feedback confirms a persisted request or explains why
the account/request is unavailable.

## Temporary URI path, battery and evidence

The Events URI watcher remains until Android-triggered upload is qualified in E4.
Its durable handoff, rearm-before-capture, bounded offline capture and dirty-content
fingerprint remain. It is push-gated, revalidates the actual account after auth
and preserves pending intent. Clean imports/ACKs and unchanged held contents do
not initiate outgoing work. Retiring it after E4 removes the remaining broad
wake cost and the duplicate URI/native scheduling path (R2).

Battery exemption is offered once, with explanation and a standard Android dialog
only after the user's action. Refusal leaves the choice unchanged; PowerManager
is checked on return. No push SDK, event notification, foreground service or exact
alarm is added. Intervals and exemption guarantee neither deadlines nor OEM access.

E3 covers targeted worker/metadata/account contracts. E4 remains grouped at
milestone exit: closed-app edits, refresh from two clients, periodic deep sleep
with/without exemption, push OFF with manual/pull still available, offline/reconnect,
reboot and permission revocation. Calendar clients must actually request Android
sync; source and fixture tests cannot establish live Proton/OEM compatibility.

Scheduling reference: [DAVx5 at 4557610](https://github.com/bitfireAT/davx5-ose/tree/4557610e89152a674913b720dc42d7cdbe9da983/core/src/main/kotlin/at/bitfire/davdroid/sync),
particularly SyncAdapterImpl, SyncFrameworkIntegration and AutomaticSyncManager.

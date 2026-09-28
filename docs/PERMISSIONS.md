# Android permission policy

## Authentication and calendar import

The app declares `INTERNET` and `ACCESS_NETWORK_STATE`. These are install-time
permissions, so sign-in has no runtime permission dialog. Authentication and
theme/language preferences do not require Calendar, Contacts, storage, location,
microphone, camera or notification access.

The merged manifest also contains `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
`FOREGROUND_SERVICE` through AndroidX WorkManager dependencies, plus AndroidX's
app-specific signature permission for non-exported dynamic receivers. These are
not runtime consent dialogs and do not mean calendar synchronization is scheduled.
Audit the merged APK manifest, including dependency contributions, when changing
dependencies. Do not infer available feature access from declarations alone.

An install-time `INTERNET` grant is necessary but does not prove network access:
device-specific per-app network restrictions can block all traffic while the
phone itself remains online. Diagnose network availability from the app's own
process. A failed connection must not automatically be blamed on credentials or
TLS. Provide an explicit route to this application's system settings on a network
failure; never silently disable a device firewall, VPN or global restriction.

Calendar import declares `READ_CALENDAR` and `WRITE_CALENDAR`. A contextual button
requests these permissions after explaining the local mirror and its privacy
consequences. No calendar permission dialog is shown automatically at launch.
Launch/foreground checks update the blocked state, and provider operations check
again. Both permissions are needed even for a read-only Proton import.

## Calendar integration acceptance contract

The provider integration follows these requirements:

1. Declare `READ_CALENDAR` and `WRITE_CALENDAR`. Check each independently using
   the platform's current grant state at launch and on foreground return, including
   return from system settings. Never treat a stored preference as a grant.
2. Explain why access is needed and request missing permissions when the user
   enables calendar synchronization, using the Activity Result permission contract.
   Permit declining without blocking authentication or app preferences.
3. Both grants are needed for the bridge's initial import/reconciliation. Read-only
   **Proton** mode still needs **Android write** access to insert/update downloaded
   events; it must not be confused with a read-only Android permission.
4. Treat partial grants, refusal and revoked/auto-reset access as blocked provider
   work. Explain the affected feature. Offer an explicit retry or access to app
   settings where appropriate; never loop permission dialogs at each launch or
   infer permanent denial solely from a false rationale result before any request.
5. Recheck immediately before provider operations, including background work, and
   handle `SecurityException` if permission changes after the check. Retain queued
   changes and checkpoints; do not interpret inability to read as remote deletion,
   clear a session or report successful projection.
6. After access is restored, reconcile durable work under the synchronization
   policy. A permission callback alone is not permission to discard pending work.

Calendeer does not post event, synchronization or error notifications. Supported
reminders are synchronized as Calendar Provider data; the user's calendar client
owns alarm delivery. Synchronization problems are shown inside Calendeer.
The merged manifest excludes POST_NOTIFICATIONS and the unused WorkManager
foreground service and its permission. Bounded background workers remain enabled.
Do not request exact-alarm access or unrestricted battery use simply to open the app.
Qualify background scheduling and OEM restrictions with the actual worker design.

## Verification

- Inspect permissions in the merged manifest and installed package; confirm that
  normal permissions used by authentication are granted without a runtime prompt.
- On a controlled device, qualify initial denial, partial grant, repeated denial,
  settings return, automatic permission reset and revocation during provider work
  once the calendar adapter is implemented. Do not grant permissions through ADB
  as a substitute for testing the actual request/refusal flow.
- Permission audits must not print account/device identifiers or inspect events.

References: [Android runtime permission workflow](https://developer.android.com/training/permissions/requesting)
and [Calendar Provider permissions](https://developer.android.com/identity/providers/calendar-provider).

# Calendeer 0.1 specification

Status: accepted owner scope, adopted on 2026-11-12. Requirements describe release
targets, not claims that every feature is implemented. Current capability and
qualification limits are in [STATUS](STATUS.md). The earlier CAL-01–CAL-12 baseline
is retained in the [archived specification](SPECIFICATION-legacy.md).

## 1. Purpose

Expose Proton calendars in the Android phone's calendar application. Users view
and edit events in their usual calendar client. Calendeer connects, synchronizes
and explains its status; it has no event browser or editor.

## 2. Required behavior

| ID | Requirement | Acceptance |
| --- | --- | --- |
| V01 | Sign in to one Proton account, including supported password, second factor, mailbox password and human verification flows. | Fresh sign-in and retained-session restoration succeed on the test phone. |
| V02 | Select calendars and use one mode per calendar: Read-only by default, or Editable when Proton permits it. Replace the five experimental write options. | Two calendars with different modes retain and enforce them after restart. |
| V03 | Proton to phone: cover 90 days in the past through 365 days ahead, including recurrence and exceptions, all-day events, timezones, colors, reminders and read-only guests. | A multi-page test calendar appears completely, without duplicates, after interruption and resumption. |
| V04 | Phone to Proton on editable calendars: create, edit title/description/location/dates/all-day kind/timezone, and delete events. | Each operation in the qualified calendar clients reaches Proton web. |
| V05 | Also edit reminders, color, recurrence (create/edit/delete a series; edit/delete one occurrence) and guests (add/remove). | Each case reaches Proton web; guests receive the invitation or cancellation sent by Proton. |
| V06 | Never send or lose a local modification outside V04–V05 support, such as responding to a received invitation. Preserve it and explain the limitation. | The event remains marked unsupported and Proton stays unchanged. |
| V07 | Synchronize as defined in section 4: chosen interval, local-change upload, calendar-client Refresh and Calendeer Synchronize. | Measure behavior on the test phone with Calendeer closed. |
| V08 | Offline: events remain readable and local changes upload when connectivity returns. | Edit in airplane mode, then reconnect. |
| V09 | Show last successful sync, calendars with pending work/errors, explanation and Retry. No notifications. | Review the status UI in all eight languages. |
| V10 | Without Calendar access, keep sign-in and settings usable and explain the required action. | Revoke and restore permission. |
| V11 | Eight languages (en, fr, de, es, it, nl, pl, pt), system-language mode, and light/dark/system themes. | Preferences persist and supported locale/theme changes are reflected. |
| V12 | F-Droid distribution: public source, Proton cryptography rebuilt from source, reproducible builds. | Independent local F-Droid builds match. |

Implement V05 in order: reminders, color, recurrence, then guests. Guests require
Proton's encrypted invitation delivery. The owner may defer them to 0.2 after the
research if they would unduly delay 0.1; no such deferral is assumed here.

## 3. Non-goals

- Accepting/declining a received invitation or proposing another time.
- Creating/deleting calendar containers, or writing to another person's shared
  calendar or a subscribed calendar.
- Multiple Proton accounts.
- An event UI or notifications in Calendeer.
- Automatic conflict resolution.

## 4. Scheduling: Android triggers and WorkManager

Use the DAVx5 model: users choose frequency, not the scheduling mechanism.
WorkManager executes synchronization; the thin SyncAdapter receives Android hints.

| Trigger | Entry point | Execution |
| --- | --- | --- |
| Local edit in a Calendeer calendar | SyncAdapter upload hint | Expedited WorkManager upload |
| Calendar-client Refresh or Android Sync now | SyncAdapter | WorkManager sends then receives |
| Periodic check | Unique periodic WorkManager job | Sends pending local changes then receives when push is allowed; receives only otherwise |
| Open Calendeer, Synchronize, calendar configuration or Retry | Calendeer | Manual WorkManager request |

The periodic send/receive rule is the owner's B1 correction. Manual requests use
a unique KEEP name so repeated taps do not accumulate equivalent pending work.

- The adapter contains no synchronization logic. It enqueues work, waits at most
  ten minutes and returns without ever cancelling that work. It always reports
  success to Android; WorkManager owns retries. On Android 14+, clear the native
  pending flag using the workaround for Android issue 320542002.
- Remove native Android periodic schedules, including defaults Android creates.
  setSyncAutomatically controls native local-change push, not the periodic job.
- One network-constrained WorkManager periodic job uses 15, 30, 60 (default), 120
  or 360 minutes. It is the sole periodic scheduler.
- Android local-change upload is scoped to Calendeer's account. Retire the custom
  URI watcher only after that path is qualified.
- Respect and display Android's automatic-sync switches. Actions in Calendeer are
  manual requests and work even when those switches are OFF.
- Offer battery exemption once with an explanation and allow refusal.
- Proton changes arrive at the next check; Proton does not notify the phone.

## 5. Non-negotiable safety rules

1. Preserve and report unsupported local changes; never lose data silently.
2. Inspect an uncertain send outcome; never resend it.
3. Never write to a read-only calendar or without current Proton rights.
4. Prevent duplicates and import/upload echoes.
5. Never put personal event contents, identifiers or credentials in logs or Git.

Keep Proton/network/crypto integration replaceable, reuse maintained Proton
primitives with provenance, and keep Android rows outside domain policy. Existing
behavioral and migration contracts remain applicable.

## 6. Accepted limits

Concurrent edits in Proton web and on the phone may overwrite one another. The
integration has no demonstrated atomic conditional-write guarantee; that risk is
accepted for 0.1. This is not a promise of automatic last-writer-wins resolution.
Android may delay background work; no delivery deadline is guaranteed.

## 7. Owner decisions

| Topic | Accepted decision |
| --- | --- |
| Calendar write options | One Read-only / Editable mode. |
| Concurrent changes | Overwrite risk accepted for 0.1. |
| Minimum Android | Android 12, minSdk 31. |
| Reminders, color, recurrence, guests | Included in V05 for 0.1, subject to the explicit possible guest deferral above. |
| Qualified clients | /e/OS Calendar first, Etar, and Google Calendar if possible. Samsung Calendar is deferred beyond 0.1; the personal Samsung phone is not a test target. |
| Sign out | OK / Cancel warning, mentioning pending uploads when present. OK removes Calendeer calendars from the phone; Cancel changes nothing. |
| Application identity | com.patmanak.calendeer and com.patmanak.calendeer.account before first F-Droid publication. Internal Kotlin package renaming is optional. |

## 8. Implementation consolidation

Keep source-built Proton crypto, authentication, incoming feeds/staging,
projection and supported recurrence decoding. Keep CREATE, personal-v2 edits and
DELETE, extending them for V05 with one pipeline per operation. Safely migrate
pending legacy title/text/time work before retiring those pipelines and their
obsolete tests/docs. Replace the five write choices with one migrated mode.
Split oversized ImportStore/ImportCoordinator collaborators. Retire URI scheduling
after E4 qualifies Android upload. Report added/removed lines for each batch.

## 9. Exit criteria

Verify V01–V12. Run one grouped E4 campaign on the authorized test phone: closed
app, Refresh in /e/OS Calendar and Etar, offline/reconnect, reboot, permission
revocation, prolonged idle with/without battery exemption, and automatic sync OFF
with manual and periodic behavior preserved. Require green remote CI, a signing
decision and no open BLOCKING finding. Samsung qualification is outside 0.1.

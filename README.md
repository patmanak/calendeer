# Calendeer

Calendeer brings your Proton Calendar into Android's own calendar. Your Proton
events appear in the calendar app you already use (for example /e/OS Calendar,
Etar or Google Calendar), alongside your other calendars, and changes you make
there can be sent back to Proton.

Calendeer is an independent application, not an official Proton product. It is
a bridge: it has no event editor of its own. You view and edit events in your
usual calendar app; Calendeer keeps them in sync in the background.

## What you can do

**Connect your Proton account**
- Sign in with your Proton account, including two-factor authentication and
  Proton's human verification. The session is kept, so you sign in once.
- Settings show which account is connected.

**Choose your calendars**
- Calendeer lists your Proton calendars with their names and colors.
- Turn each calendar on or off to show it or hide it on the phone.
- For each calendar, choose **Read-only** (the default: Proton is copied to the
  phone, nothing is sent back) or **Editable** (changes made on the phone are
  sent to Proton).

**See your events in your Android calendar app**
- Events from the selected calendars appear in Android's calendar, with their
  title, description, location, dates and times, all-day events and time zones.
- Your personal reminders and event colors come along too.
- Choose how events are colored: **each event's own color** (default) or
  **the calendar's color**. Switching only changes the display on the phone;
  your colors in Proton are untouched.
- Events stay available without a network connection.

**Edit from your phone** (Editable calendars)
- Create an event, with its reminders and color.
- Change the title, description, location, dates, times, all-day setting or
  time zone.
- Add, change or remove reminders, and change an event's color.
- Delete an event.
- Changes are sent to Proton automatically; **Synchronize** requests a sync now.
  Proton's email reminders are preserved when you edit an event.

**Stay in sync without thinking about it**
- Automatic synchronization every 15 minutes up to every 6 hours (hourly by
  default), plus the **Synchronize** button to request an update.
- Pull-to-refresh in your calendar app also triggers a synchronization.
- One status line tells you at a glance whether everything is up to date,
  in progress, offline or needs your attention.
- If a change can't be sent, it stays on the phone and the
  affected calendar says so in plain words.

**Sign out cleanly**
- Sign-out asks for confirmation and tells you if changes are still waiting to
  be sent.
- It removes the Proton session and Calendeer's calendars from the phone.

**Make it yours**
- Light, dark or system theme.
- Available in English, French, German, Spanish, Italian, Dutch, Polish and
  Portuguese.

## Coming next

- Repeating events (series and single occurrences).
- Guests and invitations.

## Learn more

- [Current status](docs/STATUS.md): what is available today and what remains
  before the first release.
- [Interface](docs/DESIGN.md): screens and their behavior.
- [Specification](docs/SPECIFICATION.md): requirements and accepted limits.
- [Synchronization behavior](docs/SYNCHRONIZATION_BEHAVIOR.md),
  [scheduling](docs/SYNC_SCHEDULING.md) and [permissions](docs/PERMISSIONS.md).
- [Architecture](docs/ARCHITECTURE.md) and [validation](docs/VALIDATION.md).
- [F-Droid preparation](docs/FDROID.md), [build security](docs/BUILD_SECURITY.md)
  and [signed release preparation](docs/RELEASE_DISTRIBUTION.md).
- Contributors and agents: [AGENTS.md](AGENTS.md) and [working method](docs/WORKFLOW.md).

## Build

Use JDK 21 and Android SDK platform 37 (set the SDK in ignored `local.properties`
or `ANDROID_HOME`). First rebuild the Proton native dependency with the
[Linux reference builder](proton-auth/native/golib/README.md) (Go 1.27.1, NDK r27c);
Gradle refuses a missing or altered native artifact. Then run:

```sh
./gradlew :sync:test :auth:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Use `gradlew.bat` on Windows. The debug APK is generated under
`app/build/outputs/apk/debug/`. See [validation](docs/VALIDATION.md) and the
[dependency update procedure](docs/BUILD_SECURITY.md).

## License

Original contributions use [GPL-3.0-or-later](LICENSE). Imported Proton adaptations
retain GPL-3.0-only terms, so the combined application is distributed under GPL
version 3. See [licensing](docs/LICENSING.md) and the notices in
[docs/licenses](docs/licenses).

# Calendeer identity and interface

Calendeer combines **calendar** and **deer**. Its original violet deer mascot
holds a calendar and belongs visually alongside Contako's companion artwork.
Calendeer is an independent application, not an official Proton product.

## Appearance

Use violet for primary actions, lavender containers and neutral tinted surfaces.
The light primary is `#6D42D8`; the dark primary is `#B69AFF`. Preserve readable
foreground contrast in both themes and retain the system/light/dark preference.
The mascot is decorative beside a textual product name; it must not duplicate
screen-reader announcements. The launcher has an adaptive bitmap foreground and
a separately simplified monochrome deer/calendar mark.
The full-color adaptive icon uses a white background in Android light mode and
black in Android dark mode, independent of the in-app appearance preference.
Launchers control icon caching and any optional themed-icon recoloring.

## Destinations

Signed-out users see the mascot, a short introduction and the existing connection
form. Signed-in users arrive at their calendars and synchronization controls.
Settings contains appearance, language and the existing connected-account actions.
Returning from settings restores the main destination; sign-out returns to login.

Confirmed sign-out clears the Proton session even when device calendar access is
unavailable. Owned calendar removal is retained durably and retried when access
returns or the app restarts. Mirror recreation waits until that removal finishes.
The connection screen explains the delay and clears its message after cleanup.
Retained outgoing work and uncertain outcomes remain available for reconciliation.
No event editor or calendar browsing interface is added.

Authenticated navigation uses two labeled destinations, Calendars and Settings.
The Calendars page has one synchronization banner with the last successful cycle,
configured interval and manual synchronization action. It distinguishes working,
attention, connection unavailable, paused and waiting states. A completed import
alone never produces Up to date; retained work and recorded failures remain visible.
The interval is not an exact next-run deadline. Disabled automatic sending is
explained without suggesting that receiving or manual synchronization is disabled.
Calendars uses one compact row per persisted calendar, showing its color, name,
Read-only/Editable badge and an independent display switch. Touching the name
opens a scrollable detail sheet with a separate display control, segmented
Read-only/Editable choice, three supported-operation summaries and explicit
upcoming recurrence/guest limits. Opening a
card or destination does not enable writes or queue event synchronization. Newly
discovered calendars remain noninteractive until their scope is persisted.
The catalog refreshes once on entry when available, respecting loading/cooldown;
this discovery request never shares the manual outgoing synchronization action.

Pending Android projections, held events, history gaps, local conflicts and
outgoing work keep a concise visible notice on their row, including for paused
calendars. The sheet retains all recorded counts and preservation explanations,
even without calendar permission; sign-out removes it. A consent already enabled
can always be revoked there. The concurrency warning appears once in the sheet.
Import completion is never presented as full bidirectional convergence.
Permission requests and failures remain actionable. Longer explanations live in
details rather than repeating throughout the main view.

Settings groups Account, Synchronization, Appearance and language, and About.
Synchronization offers interval chips, Android automatic-switch status and its
settings link, plus a battery-exemption status and explicit Adjust action. Manual
synchronization stays in the Calendars banner. Version and
independent-project information belong in About. A red Sign out action follows
About. Its confirmation reads retained operation counts afresh from the existing
account-scoped stores, including paused and older generations. The count is a
sample of recorded operations, not unique events or uncaptured Android changes.
Zero hides the pending-work panel; unavailable or timed-out counts are explicit
and still allow sign-out. Cancelling or changing the authentication epoch removes
the sample. The existing controller still owns session clearing and deferred
native removal. Login uses a short introduction,
one form and a dedicated error surface; it does not restore entered credentials.
Controls use at least 48dp targets, scrollable content and text labels in addition
to icons or color. Light and dark appearances retain violet accents on neutral
surfaces. Navigation and preference dialogs close or disable when authentication
requires attention, including when settings was opened before sign-in.

Each calendar reports retained Android change and recorded conflict counts when
its local UPDATE/DELETE journal is nonempty. Its details explain that unsupported
changes remain local. Enabling editing permits supported operations, subject to
current permissions and Proton rights; it does not enable unsupported operations.
Counts remain visible for paused/unavailable calendars and without calendar
permission, but disappear on sign-out. Zero counts hide the entire explanation.
No event contents, event identifiers or resolution actions are presented. This
reports captured history; it does not claim automatic detection of all local edits.

An active authentication attempt or challenge cannot be hidden by settings
navigation. Server human verification takes priority even after sign-in, including
when requested during synchronization. Existing credential clearing, secure-window
protection and explicit cancellation remain in force.

Old complete write consent migrates to Editable; partial consent becomes Read-only
with an explanation inviting a new choice. This never discards pending work or
reissues an uncertain request. Fresh calendars default to Read-only. Eight
supported languages remain available.

## Artwork provenance

`app/src/main/res/drawable-nodpi/calendeer_mascot.png` was generated with the built-in
image generation tool for this project. Contako's existing mascot was supplied as
a style reference: rounded shapes, soft violet shading and expressive eyes.
The subject is a distinct deer with simplified branching antlers, holding a pale
calendar, on a transparent background, with no text or Proton logo.
The monochrome vector is an original simplified implementation for themed icons.

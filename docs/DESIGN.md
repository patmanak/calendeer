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
No event editor or calendar browsing interface is added.

An active authentication attempt or challenge cannot be hidden by settings
navigation. Server human verification takes priority even after sign-in, including
when requested during synchronization. Existing credential clearing, secure-window
protection and explicit cancellation remain in force.

The application label changes without changing its Android application ID, account
type, Provider ownership markers or local storage names. Existing sessions and
mirrors are not recreated for branding. Eight supported languages remain available.

## Artwork provenance

`app/src/main/res/drawable-nodpi/calendeer_mascot.png` was generated with the built-in
image generation tool for this project. Contako's existing mascot was supplied as
a style reference: rounded shapes, soft violet shading and expressive eyes.
The subject is a distinct deer with simplified branching antlers, holding a pale
calendar, on a transparent background, with no text or Proton logo.
The monochrome vector is an original simplified implementation for themed icons.

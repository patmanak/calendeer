# Android account event colors

Calendeer advertises the concrete adapter's optional palette through
`CalendarContract.Colors`, as `TYPE_EVENT` entries on its native account.
The Proton adapter offers the same twenty RGB values used by fresh-write
admission. This metadata grants no edit consent and sends no Proton request.
Calendar clients decide whether to display account palettes; arbitrary new
colors remain subject to the existing unsupported-write protection.

The project-owned `EventColorPalette` contract contains only stable keys and
RGB values. The application injects it into the native projection. A different
adapter can offer another palette or none without exposing DTOs or SDK classes
to Android/domain policy.

Publication snapshots a bounded palette and inserts only missing entries.
Existing keys must have exactly the same event type and opaque ARGB value;
collisions stay untouched and are reported. Existing rows and absent keys are
asserted in one transaction before insertion. A palette conflict or unavailable
table emits only a closed diagnostic; calendar configuration and event import
continue. Revoked access and cancellation still stop the operation. No color is
reassigned, updated or deleted, because a Provider color update can recolor
referencing events without
marking them dirty. Unrelated entries and accounts are preserved.

For a key-based local choice, the native reader requires exactly one matching
account/type/key/ARGB row. That row and the complete event observation are
asserted in the same capture or acknowledgement batch. Missing or inconsistent
keys remain on the phone. Canonical event intent still contains RGB; existing
request formats and uncertain-outcome recovery are unchanged. Display masking
retains canonical color independently of the optional native key.
An empty key is a calendar-color alias only with a null/zero raw color; a
contradictory RGB stays held. Historical simple profiles still refuse nonempty keys.

Provider evidence is separate from calendar-client qualification. Actual Etar
and /e/OS journeys remain part of grouped milestone E4.

References: [Android contract](https://developer.android.com/reference/android/provider/CalendarContract.Colors),
[pinned AOSP Provider](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarProvider2.java),
[Proton palette provenance](../proton-auth/PROVENANCE.md#fresh-event-color-admission).

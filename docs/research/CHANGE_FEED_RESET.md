# Calendar change-feed reset recovery

The bridge handles an explicit, validated reset response as a gap in observation,
not as an empty calendar. It retains the complete sealed response, resumes from
the returned cursor and scans the current import horizon again before consuming
new changes. These steps commit together; existing rows, deferred records and
previously acknowledged jobs survive. Notices inside the reset response are not
applied as event actions.

Each reset receipt is retained in a scope-specific archive under the shared 32 MiB
staging budget. Database schema 4 adds this archive without replacing version 1–3
data. Scope and adapter-format checks prevent evidence from being reinterpreted
under another adapter. Capacity or transaction failures preserve the old cursor
and pending receipt. A reset whose cursor does not advance stays blocked to avoid
an endless rescan loop.

The history-gap warning persists across scans, restart, pause and network errors.
Finishing new work reports recent changes with incomplete older history and does
not update the last completed-import timestamp. Old copies may have been changed,
moved outside the horizon or deleted during the gap; absence from a bounded scan
cannot distinguish these cases. No automatic deletion or clearing of this warning
is implemented. New authoritative deletion notices still follow the normal path.

## Source basis and limits

The inspected official Core implementation reuses `nextEventId` before notifying
reset listeners and constructs the next request from that value. Its reset branch
does not call normal event-action dispatch:
[EventManagerImpl](https://github.com/ProtonMail/protoncore_android/blob/1b87f94ebfdfaf5e67145e8668efc52dbb931e0b/event-manager/data/src/main/kotlin/me/proton/core/eventmanager/data/EventManagerImpl.kt),
[EventMetadata](https://github.com/ProtonMail/protoncore_android/blob/1b87f94ebfdfaf5e67145e8668efc52dbb931e0b/event-manager/domain/src/main/kotlin/me/proton/core/eventmanager/domain/entity/EventMetadata.kt).

The official Calendar reset listener clears its own cache and schedules a fresh
window acquisition. It does not consume the reset response as individual changes:
[CalendarEventListenerNew](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/eventmanager/listeners/calendar/CalendarEventListenerNew.kt).
The bridge adopts the anchor/scan ordering, retaining Android copies because their
removal is not established by this source behavior. No upstream code is copied in
this slice; the existing Proton parser already suppresses reset-page notices.

These pinned sources support client behavior, not a server retention guarantee or
proof of a live reset. The Core source revision is not asserted to be the exact
revision of the packaged Core dependency. Generic HTTP errors, including 404, are
not classified as expired history. Tests use controlled reset responses; live
server expiry, full historical reconciliation and bidirectional writes remain
separate qualification work.

# FOSS calendar synchronization reuse

Inspected on 2026-10-05. This is a source audit, not a dependency integration,
Android/OEM qualification or Proton mutation contract. No upstream build,
account operation or device operation was performed.

## Scope and resolved references

Calendeer's release-candidate target is bidirectional use from Android calendar
applications. Read-only import remains a validation stage. The simple-event
subset is a proposed first editable scope, not complete recurrence editing.

| Repository / ref | Resolved commit | Inspection |
| --- | --- | --- |
| `bitfireAT/davx5-ose`, `main` | `76cc5128b75bc8ba09882b4d84a233e48a2e1fe4` | Calendar sync, local event/collection, upload/delete and conflict policy. |
| `bitfireAT/synctools`, `main` | `cb4f75fd5d71c1960c9a59e1b873aea5433bcbeb` | Provider storage, recurring exceptions, reserved sync columns and dependency declarations. |
| `bitfireAT/ical4android`, `main` | `01bce4a8ee8395402a53c06263839ce3afe97e2b` | README migration notice. |
| `etesync/android`, `master` | `e1de5c281729e656ec755ee41f140ffe6411ade0` | Calendar capture, local identity, upload acknowledgement, cache and paginated download. |

All four recursive repository trees were returned with `truncated=false`. This
does not mean every file was reviewed. Licenses are GPLv3-family; the inspected
synctools files explicitly identify `GPL-3.0-or-later`. Exact copied-file notices
and combined distribution requirements must be retained when importing code.
No code or dependency was imported by this audit.

## Android mechanisms worth reusing

DAVx5's [LocalCalendar](https://github.com/bitfireAT/davx5-ose/blob/76cc5128b75bc8ba09882b4d84a233e48a2e1fe4/core/src/main/kotlin/at/bitfire/davdroid/resource/local/LocalCalendar.kt)
queries `DIRTY` and `DELETED` rows, while
[CalendarSyncManager.prepare](https://github.com/bitfireAT/davx5-ose/blob/76cc5128b75bc8ba09882b4d84a233e48a2e1fe4/core/src/main/kotlin/at/bitfire/davdroid/sync/CalendarSyncManager.kt)
normalizes changed/deleted exceptions through their master before generating an
iCalendar resource. These are concrete references for periodic discovery after
process absence, independent of a transient observer notification.

EteSync's [LocalCalendar](https://github.com/etesync/android/blob/e1de5c281729e656ec755ee41f140ffe6411ade0/app/src/main/java/com/etesync/syncadapter/resource/LocalCalendar.kt)
also separates master/exception rows and bounds modified-row selection by ID and
limit; its deleted-row selection is not similarly bounded in this class.
[LocalEvent](https://github.com/etesync/android/blob/e1de5c281729e656ec755ee41f140ffe6411ade0/app/src/main/java/com/etesync/syncadapter/resource/LocalEvent.kt)
persists identity before upload. These patterns can inform Calendeer's existing
immutable capture journals without replacing their scope and snapshot checks.

Synctools is the current library to evaluate: the old
[ical4android README](https://github.com/bitfireAT/ical4android/blob/01bce4a8ee8395402a53c06263839ce3afe97e2b/README.md)
explicitly says it has been superseded by synctools. Its
[AndroidRecurringCalendar](https://github.com/bitfireAT/synctools/blob/cb4f75fd5d71c1960c9a59e1b873aea5433bcbeb/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/AndroidRecurringCalendar.kt)
contains Provider exception handling and companion native tests. Direct adoption
is not safe: [EventsContract](https://github.com/bitfireAT/synctools/blob/cb4f75fd5d71c1960c9a59e1b873aea5433bcbeb/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/EventsContract.kt)
assigns `SYNC_DATA1` through `SYNC_DATA4` to ETag, flags, sequence and schedule tag.
Calendeer already uses these columns for ownership, revision, historical receipt
and event identity. Reuse must adapt this layout and preserve existing metadata.
Its declared build uses SDK 36, minimum SDK 24, ical4j 4.2.5, Kotlin 2.3.21 and
AGP 9.2.1 ([library build](https://github.com/bitfireAT/synctools/blob/cb4f75fd5d71c1960c9a59e1b873aea5433bcbeb/lib/build.gradle.kts),
[version catalog](https://github.com/bitfireAT/synctools/blob/cb4f75fd5d71c1960c9a59e1b873aea5433bcbeb/gradle/libs.versions.toml)).
These declarations are not evidence of build compatibility with Calendeer.

## Server-dependent mechanisms and policy differences

DAVx5's [SyncManager](https://github.com/bitfireAT/davx5-ose/blob/76cc5128b75bc8ba09882b4d84a233e48a2e1fe4/core/src/main/kotlin/at/bitfire/davdroid/sync/SyncManager.kt)
supplies ETag or schedule-tag preconditions for updates and deletes. Its
precondition-failure path discards the local modification and fetches the server
version. Its local-delete path can remove the local tombstone after an HTTP
failure. These are observed upstream policies, not policies selected for Calendeer.
Keep retained local intentions and explicit conflict/uncertain states; do not
copy these retirement decisions or infer that Proton accepts DAV preconditions.

EteSync's [SyncManager](https://github.com/etesync/android/blob/e1de5c281729e656ec755ee41f140ffe6411ade0/app/src/main/java/com/etesync/syncadapter/syncadapter/SyncManager.kt)
uses Etebase cache/item batches and paginated `stoken`/`isDone` downloads. It
acknowledges local flags after successful submission/cache work. This is useful
ordering evidence, not an exportable Proton queue or a demonstrated generic
conflict resolver. Its local `clearDirty` updates do not assert the captured
content; Calendeer must keep its conditional acknowledgement when another Android
edit occurs during upload. The declared Etebase client is 2.3.2 and the application
also uses an ical4android submodule; neither dependency supplies Proton transport.

The [Proton conditional-write audit](CONDITIONAL_WRITES.md) remains applicable.
Source reuse cannot supply a missing demonstrated server precondition. A bounded
alternative conflict strategy can be considered separately, with the residual
read/write race documented instead of claiming atomic protection.

## Recommended next experiment

Use the existing capture and creation journals for one simple-event journey:
create in a system calendar client, discover the Provider change durably, submit
through Proton, read back and attach to the original row. Verify restart/offline
replay and a second Android edit during submission. Consult the upstream capture
and exception tests rather than rebuilding those mechanisms without comparison.

Keep source reuse within the Android adapter; keep crypto and request builders
in the existing Proton boundary. Before importing synctools or extracted source,
compare a small fixture against current metadata, unknown fields and conditional
acknowledgements, record exact provenance, and check the actual dependency build.
UPDATE/DELETE activation still needs an accepted and tested concurrency strategy.
This audit enables implementation choices; it enables no outgoing capability.

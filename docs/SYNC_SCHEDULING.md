# Synchronization scheduling

## Android edits

When a selected, available calendar has creation/editing enabled, Calendeer
registers a one-time WorkManager 2.8.1 content trigger for
`CalendarContract.Events.CONTENT_URI`, including descendants. Android owns this
registration while the application process is absent. Notifications are grouped
with a five-second update delay and a thirty-second maximum batching delay.
These are requested batching limits, not a delivery deadline: Android quotas,
power management, permissions and connectivity can delay execution. A force-stop
prevents ordinary background operation until Android permits the app to run again.

The native URI job first awaits a durable, independent local handoff without URI
constraints. That job can retry authentication, registration and capture without
requiring another content notification. It persists its successor token before
enqueuing an independent URI job,
and awaits WorkManager's enqueue operation before scanning. It does not use KEEP
on its own RUNNING job or replace an active capture. A pending token survives
failed enqueue/capture/handoff. Conditional acknowledgement cannot consume a newer
signal. Continuations are separate durable jobs and preserve their origin.
Registration is removed when resolved selections have no writable calendar;
initial session restoration is distinguished from a resolved empty selection.
A revoked operation lease does not terminate the long-lived state observer;
genuine cancellation of its coroutine still propagates.

The worker restores authentication and reads retained selections without starting
an incoming refresh. Automatic normal scheduling stays closed in a process
started solely for this wake. The UI and ordinary import/periodic workers retain
their normal scheduling entry points. The in-process observer repairs the URI
registration; it no longer schedules a fresh network refresh for every Provider
notification. The existing six-hour periodic catch-up is retained.

A notification grants no ownership or permission to send. The existing bounded
native discovery, capture and outgoing pipeline recheck session, permissions,
selection, account/generation, opt-ins and immutable request identity. The URI
origin does not reset recovery exhaustion. Uncertain outcomes remain inspect-only;
it cannot lift a durable paused CREATE in the SQL resume transaction. Normal
explicit requalification keeps its previous behavior. No reminder notification
or event editor is added to Calendeer.

Before capture, a dedicated read-only adapter hashes the full values of owned
dirty/deleted events and their reminder, attendee and extended-property rows.
No raw contents are stored in the scheduling preferences. Unchanged contents,
including an unchanged held event, do not initiate another capture/network pass.
Clean incoming imports and completed acknowledgements have no dirty rows and
therefore do not initiate outgoing work. The fingerprint grants no admission.

Hashing is limited to 4096 rows and 16 MiB of framed values per pass. On overflow,
capture proceeds through its existing bounded pages with **no cached fingerprint**;
one large unsupported event cannot starve other edits. Deduplication is unavailable
for such a snapshot until it falls below the limits. Local continuations carry
this uncached state explicitly. A network-constrained job is persisted only after
the local capture traversal finishes, so offline edits remain durably captured.

Normal WorkManager retry and the retained periodic catch-up remain necessary for
crashes, unavailable providers and platform scheduling limits. This mechanism does
not provide an atomic transaction between preferences, WorkManager and the
Calendar Provider, or a guaranteed latency across every interruption. If the
initial durable handoff itself fails, URI retry alone does not guarantee another
notification; normal startup/periodic catch-up remains necessary. Qualification
must separate actual URI registration, process-absent editor delivery, offline
reconnection, bursts, reboot, permission revocation and Samsung/Doze behavior.

## Incoming changes

This change does not alter the incoming six-hour periodic cadence or add a push
service. Shorter polling intervals and a foreground change-feed polling loop need
separate product decisions and battery/data measurements. A Proton change-feed
endpoint is a pull API; its presence is not proof of a background push service.

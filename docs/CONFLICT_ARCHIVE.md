# Retained conflict history

Calendar options provide local, read-only access to retained conflict dossiers.
This is an exception-inspection surface, not an event browser or editor. Opening,
refreshing or paging it does not prepare, send, retry, resolve or acknowledge work.

## Evidence and provenance

Each dossier belongs to an exact account, calendar, generation, event and local
mutation. Its original projected base and first captured local content are
immutable. A local deletion is labelled explicitly: its captured fields are not
a complete undo image and do not represent the current Android event.

While that local intention exists, distinct incoming observations are appended
transactionally rather than replacing earlier evidence. Verified supported
content, explicit remote tombstones, opaque deferred evidence, an unreadable cache
and an absent cache remain distinct. Deferred protected payloads and unreadable
cache bytes stay in private storage; the view exposes their status, not unverified
event contents. An opaque revision alone
does not identify a distinct content variant.

Sequence numbers order local collection. Received timestamps are collection
times, never author modification times. Cached observations have no invented
timestamp. None of these values establishes a last-modification-wins order,
current server contents or atomic protection against a concurrent server edit.

Schema 18 adds the archive without changing existing mutation or attempt payloads.
Schema 19 extends observation storage for unreadable cached evidence while
preserving existing dossier identities and observation sequences.
Existing conflicted owners are seeded from their retained base/local capture and
the cache still available at migration. Earlier overwritten versions cannot be
reconstructed. A dossier survives cleanup of its current local intention; no
further remote observations are appended after that intention is gone. Pausing
or losing discovery access does not delete history. Explicit deletion of a stored
calendar scope removes its archive through a database cascade.

## Bounds and isolation

`ImportStore` retains admission, locking and transaction ownership. Its synchronous
`ConflictArchiveSql` collaborator reads and appends evidence only inside the
caller's database operation, using the existing capacity check. It cannot open or
commit a transaction, schedule work or acknowledge an event. `ImportPayloadCodec`
retains the historical cache/checkpoint representation; bounded SQL values remain
separate from decoding and domain policy.

Archive discovery is metadata-only. Dossier details and observations are read
separately, with at most 50 entries per page and one observation payload at a time
in a bounded SQL cursor window. Cursors bind their protocol and exact scope or
dossier; they are not synchronization cursors or completeness proofs. Refreshing
starts the local first page again. The UI retains one page rather than accumulating
an unbounded event history.

The comparison displays title, time, location and description. Color, recurrence
and other event properties are explicitly outside this partial display; their
omission does not establish equality between retained variants.

Archive payloads share the import staging budget. Capacity failure rolls back the
observation, conflict state and checkpoint together, without silently evicting an
unresolved variant. Reading already retained evidence does not enforce that write
budget. Malformed or oversized evidence produces an unavailable result, without
leaking contents into diagnostics.

Every read checks the retained authenticated account, session/policy epoch,
calendar permissions and exact known scope before and after suspension. Known
paused or unavailable calendars remain inspectable. Cancellation propagates even
when a dossier is missing. Presentation data is ephemeral, cleared on disposal or
session/permission invalidation, and never put into saved instance state.

This feature preserves observed evidence. General conflict resolution, trustworthy
comparable edit timestamps and server-side atomic concurrency guarantees remain
separate release work.

The [shared-journal exclusion primitive](CONFLICT_RESOLUTION.md) is the first
internal concurrency step for that work. It is not connected to a resolution
action and does not change this archive's read-only contract.

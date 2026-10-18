# Guarded deletion submission

## Capability and supported scope

The typed deletion port has separate worker orchestration, default-off experimental
per-calendar consent, durable proofs and native acknowledgement. Ordinary timed
service/editor/production-worker and restarted replay journeys are qualified;
it does not advertise conditional DELETE in `RemoteCalendarAdapter`. Local
tombstone capture is described in
[local edit capture](LOCAL_EDIT_CAPTURE.md).

The first submission profile accepts an ordinary nonrecurring event with known-empty
mapped details and no custom event color. The retained tombstone content must equal
the complete projected base. Prior edits, transferred creation origins, reminders,
invitations and richer events remain held. The adapter decrypts and verifies the
current remote event using the existing Proton Core event verifier; mapped display
text alone is not a revision or signature check.

## Durable request and interruption rules

Each immutable request binds the account, calendar, generation, local deletion ID,
base event/revision, complete raw content, adapter formats and protected literal
request. Its deterministic local identity is not a server idempotency key.

The shared SQLite journal arbitrates title, text, time and deletion owners across
generations. It preserves the first prepared wire, commits `OUTCOME_UNKNOWN` before
transport, and retains the first available protected HTTP response independently of
continued write consent. Unknown work is never reset, replaced or automatically
resent. Corrupt or foreign history fails closed. Shared journal schema 9 adds
authenticated-absence results and immutable completion proofs without releasing
the event or request identity, including across generations.

A separate DELETE permit authorizes one exchange of the precise single-event
operation. CREATE and UPDATE permits cannot authorize it. Mixed tags, body/URL
rebinding, retries, redirects and interceptor reentry are refused. Cancellation
remains cancellation and cannot reopen a consumed request.

## Conflicts and observed absence

Before claim, a freshly authenticated exact-event read must match the retained
base and current membership/context. A verified different event is returned as a
conflict with its full mapped variant; no deletion is sent. An UNKNOWN request is
inspected without submission. An unchanged event still present is unresolved;
a changed verified event is a conflict.

Indexed success code 1000 alone is insufficient. Verification requires a newly
qualified exact-event lookup reporting structured Proton code 2501 for the bound
calendar and event, with membership/context rechecked afterward. A generic HTTP
404, missing list entry, failed scan, lost access, malformed reply, cancellation or
network error is not deletion evidence. Absence establishes current convergence,
not causal attribution, immutable remote history or a server lock. This scoped
2501 interpretation is qualified on controlled disposable ordinary timed and
all-day events.
The bounded parser
accepts only the exact authenticated event route, status 200/400/404/422 and the
expected structured error fields. Optional `Details` must be an empty object;
unknown keys, nonempty details and malformed or duplicate fields remain unresolved.

Core can convert a structured API error before Retrofit returns its response. A
separate single-use GET witness captures only the exact route and authenticated
session's response before that conversion. It preserves the original JSON, limits
both wire and gzip-expanded bodies, and leaves Core's stream untouched. Reentry,
foreign/ambiguous headers, malformed encodings and repeated consumption invalidate
the witness. An exception's parsed code alone cannot donate absence evidence.

The fresh GET and sync PUT are not atomic. An edit arriving between them can still
race with deletion; see [conditional writes](research/CONDITIONAL_WRITES.md).
Without trustworthy comparable modification timestamps, preserving observed
variants takes precedence over inventing last-modification-wins ordering.

## Worker, acknowledgement and incoming barrier

Deletion has independent default-off per-calendar consent. Disabling general
editing clears it. The worker captures original account/generation/policy leases,
uses separate bounded owner/inspection/offline cursors and never renews an expired
lease. Factual response retention survives sending-consent loss, but not logout or
account replacement. Local source admission runs under the import lock before the
authenticated adapter; callbacks never re-enter authentication or the import lock.

Import schema 22 reserves one immutable full-binding native receipt before the
network claim. Reservation is neither remote absence nor acknowledgement authority.
Only a durable verified result can authorize native acknowledgement. A matching
remote deletion echo may converge without creating a conflict; any different
verified upsert or deferred variant remains preserved as a visible conflict.

A dirty deletion can interrupt an incoming no-change projection. Its receipt may
coexist only when both its change and previous base equal the entire deletion
base, its import format/protocol match and both representations are historical
direct mode. The receipt stays intact through preparation and reservation. Only
verified native acknowledgement can retire this exact obligation by a full-tuple
SQL comparison in the same transaction as restoration. Changed or malformed
receipts remain held; a rollback retains both obligations.

Native acknowledgement conditionally changes only DIRTY and its exact receipt
marker on the unique, unchanged, ordinary deleted row. DELETED remains set, with
the original content and base revision. Android's Provider also clears its
[`MUTATORS` history](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarProvider2.java#4154)
when the sync adapter resets DIRTY; the bridge does not rewrite
event content to perform acknowledgement. The same atomic batch asserts the retained
post-write row: a Provider that purges it cannot qualify this protocol. A missing
row alone is never success. Historical inspection of the exact marker recovers a
lost reply without rewriting newer content, flags or children. Before the first
write, the reserved receipt can be retried only against the complete original
native source. SQL restoration and journal completion are idempotent.

The original local owner and baseline remain a permanent incoming barrier after
completion. Finished paused deletions disappear from actionable counts; conflicts
remain visible. Pending projection queues, requeues and old retained receipts
cannot recreate the event. A later native restoration or edit is preserved and
reported as conflict. Equal-valued remote recreation cannot be distinguished from
stale replay and stays held; no ordering is invented from opaque revisions.

## Qualification

Disposable ordinary timed, single-day and three-day events pass actual `foundation.e.calendar` deletion,
production worker RUN and fresh-process REPLAY with one immutable request and
exact restoration of the five target modes. A separate service journey passes
restarted inspection without another send, deferred ACK, new-process local
acknowledgement and replay. Physical isolated tests cover native conflicts,
lost replies, source/policy loss, SQL rollback and interrupted incoming projection.
All-day worker fixtures freeze the civil-date profile before creation, verify UTC
midnight and exclusive end bounds before editor deletion and after acknowledgement,
and retain the exact history through active fresh-process replay. Bounded test
admission accepts previous completed deletions only with full journal/result/
completion, exact SQL/native receipts and unchanged clean deleted rows and children;
unresolved unrelated work is never exempted. Production admission is unchanged.
A real simple-event preflight conflict also preserves the full authenticated
variant after an official-editor title save changed the event zone from `Z` to
`Europe/Paris` without changing its instants. The wire stays PREPARED, the local
owner becomes CONFLICT, and no DELETE claim, result, completion or native ACK is
allowed. Local evidence review and fresh-process offline replay preserve the
complete dossier and request; the modified service event remains available.
An additional disposable timed event passes actual editor deletion while offline,
production local-worker capture and fresh-process offline replay. Both offline
phases retain the same PAUSED owner, complete base/content and dirty deleted native
row, with no DELETE attempt or ACK. After reconnection, production RUN completes
the original retained owner's deletion and native/SQL acknowledgement. Active
fresh-process replay preserves the one attempt and exact request/result/completion
hashes. The five original modes are restored after every active phase. The fixture
requires no INTERNET-capable network throughout its offline phases; normal
constrained network work may remain queued. This qualifies disconnection before submission and
application-process restart, not interruption during/after claim, device reboot
or power loss. Qualification also does not cover every production-worker conflict
path, atomic remote preconditions or Samsung/One UI.
Existing retained synthetic events must not be used as disposable fixtures.
Detailed measured evidence is recorded in [validation](VALIDATION.md); passing fake
boundaries alone does not qualify the service, Provider, editor or WorkManager.

# Guarded deletion submission

## Capability and supported scope

The typed deletion port is an internal integration boundary. It does not advertise
conditional DELETE in `RemoteCalendarAdapter`, enable a system worker, expose a
calendar option or acknowledge Android. Local tombstone capture is described in
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
resent. Corrupt or foreign history fails closed. This first journal stage has no
completion operation and keeps its owner reserved.

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
2501 interpretation still requires live service qualification.

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

## Remaining integration and qualification

Before enabling deletion from an ordinary system calendar, add an independent
default-off per-calendar consent, current local owner/source admission under the
import coordinator lock, durable conflict/result retention, bounded inspection,
and typed native/SQL acknowledgement with restart recovery. A changed or restored
native event must survive acknowledgement. A missing native row cannot establish
historical success without an exact previously retained acknowledgement proof.
Retain a deletion barrier across already-staged incoming work: replaying an older
upsert must not recreate an acknowledged deletion. Subsequent remote observations
need explicit qualification, without ordering them by local processing time.

Qualify disposable events through the actual system calendar application, including
remote conflict, lost reply, process interruption before/after claim and native
acknowledgement, offline restart and repeated inspection without another send.
Existing retained synthetic events must not be used as disposable fixtures.

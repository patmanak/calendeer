# Shared-journal exclusion for future conflict resolution

This is an opt-in concurrency primitive, not a working conflict-resolution button.
The current product still preserves conflicting variants without choosing one.
Android collection times are not trustworthy author timestamps for last-write-wins.

## Durable exclusion

Shared attempt schema 11 adds an independent conflict-resolution fence table.
The migration from schema 10 is additive: request, response, result, completion
and preparation bytes remain unchanged. The fence binds the exact account,
calendar, generation, event, original local mutation, resolution identity and an
immutable caller-owned SHA-256 plan fingerprint. Public representations redact it.

Acquisition and every shared-family preparation reservation, remember and claim
use the same SQLite writer transaction boundary. An active fence prevents a new
title, text, time, personal or DELETE request for that event across generations.
A worker retaining an old object cannot bypass that check. Acquisition requires
no unresolved attempt or preparation reservation in any of those families;
PREPARED and OUTCOME_UNKNOWN both refuse it. No attempt is retired or treated as
completed by this protocol.

Acquisition qualifies at most 128 history rows per family/reservation table for
the account/calendar. Exceeding that bound refuses every new acquisition for
that calendar; no history is truncated or evicted. Fence lookups also fail closed
above 128 matching retained entries or on malformed/ambiguous evidence. Matching
historical identities are qualified: acquiring
a fence must not hijack a request or local-mutation identity on another event.
Acquisition also refuses before inserting a row that would exceed its own
history-reader bound. Existing history is kept intact and remains readable.

Releasing a fence requires its exact immutable identity and an immutable caller-
supplied finalization commitment. There is no timeout or automatic release.
The first commitment is retained; release replay must match it. Permanent
tombstones continue to reject reuse of the original local and resolution
identities after release. A distinct later mutation may proceed on the event.

## Authority and integration limits

The store cannot prove that Android or the import database applied a chosen
version. The caller must establish those facts through a separate durable
receipt/native comparison/finalization protocol before releasing the fence.
A hash or successful acquisition is never that proof.

No production/UI caller acquires these fences yet. They do not grant network,
Provider, account, selection or consent authority. This primitive covers only the
shared title/text/time/personal/DELETE journal, not CREATE's separate handoff
journal or quarantined historical standalone stores. A future resolver must
qualify those sources as well before offering a choice.

Implementing “Use this Proton version” still requires a frozen preview, a durable
import-store reservation, exact conditional native application, historical nonce
inspection and restart-safe finalization between databases. Supporting existing
PREPARED requests needs a distinct, audited retirement disposition. UNKNOWN
requests remain inspection-only. “Keep both” additionally requires a real
independent copy and a defined calendar/lifecycle policy; an archive alone is
not a second event. See [retained evidence](CONFLICT_ARCHIVE.md).

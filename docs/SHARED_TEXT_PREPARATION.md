# Shared text preparation boundary

Basic bidirectional event management includes title, description and location.
The internal `ProtonSharedTextPatcher` prepares changes to these three text
properties without rebuilding the original iCalendar document. Production
submission remains title-only: this primitive does not enable description or
location uploads. See the [current title path](TITLE_UPDATE_SUBMISSION.md).

## Selected properties and original data

The caller supplies a selected set of `SUMMARY`, `DESCRIPTION` and `LOCATION`
changes, each with its expected original value and replacement. A null optional
value means that the property is absent; an empty string means a present, empty
property. They are distinct observations. At least one property must be selected;
SUMMARY must remain present.

Both shared partitions must retain the existing unambiguous UID and DTSTAMP
binding. The encrypted event must also retain one unparameterized SUMMARY, even
when only another field is selected. Selected properties must be unique,
unparameterized and in the encrypted shared event. A stale value, duplicate,
parameterized property or conflicting
clear-part property refuses the whole patch, including a requested no-op.
Unsupported parameters are not discarded or retained with a contradictory
alternate representation.

Only changed property spans are replaced or removed. Optional properties absent
from the original are inserted before the event's closing marker, in a fixed
order independent of the caller's map order. Selected properties whose normalized
values do not change retain their original spans even when another property
changes. Unselected properties, original folding, opaque duplicate fields,
parameters, nested components and unrelated partitions remain byte-identical.
An entirely unchanged request returns the original object after binding checks.
The explicit `patchAt` variant also changes the three metadata spans described
below; those spans are the exception to this raw preservation rule.

This validates framing and selected text, not every property or a general editing
profile. Keeping unknown fields intact does not establish their meaning or prove
that related alternate text representations remain consistent. A broader upload
profile must separately qualify the complete original representation.

## Serialization and metadata

The existing Biweekly dependency serializes only replacement text properties.
Logical CRLF newlines normalize to LF. Invalid UTF-8 input, unsupported control
characters and over-budget values refuse preparation. Each selected value is
bounded to 65,536 UTF-8 bytes; the combined partitions are bounded to 1 MiB,
20,000 physical lines per partition and 16 component levels. Generated physical
lines must fit 75 UTF-8 octets. Original lines are never refolded; writer output
that exceeds that bound is refused, including some long whitespace sequences.

The explicit `patchAt` operation retains the title path's restricted metadata
policy: unchanged CREATED and SEQUENCE, both DTSTAMP values and clear
LAST-MODIFIED updated from the caller's whole-second UTC instant. The instant
must meet the existing range checks; an actual change also requires the existing
monotonicity checks. A no-op checks the explicit instant and identity, retains all
bytes, and does not certify the additional
metadata layout for a later edit. No wall clock is read.

`ProtonSharedTitlePatcher` delegates to the common implementation while preserving
its previous API, exception type, sanitized message and no-op behavior. Existing
protected preparation formats, journals, submission profiles and worker consent
are unchanged. The extension introduces no cryptographic primitive or dependency.

## Protected fragments

`ProtonEventDecryptor.prepareSharedTextAt` now verifies the original shared
signatures and caller-supplied scope, event ID, revision, UID and original title
before preparing selected text. It reuses the existing Proton Core key bootstrap,
signing and encryption paths. Changed clear text is signed again; changed secret
text is signed and encrypted with the original shared session key. The original
SharedKeyPacket and all other part metadata remain unchanged. A no-op retains
the original ciphertext and signatures. Personal and attendee partitions must
be explicitly empty; no address key packet is admitted. These local checks do
not prove current server access or complete upload eligibility.

Input maps and JSON values are copied before local key access. Output containers
return independent snapshots. Cancellation stops preparation before subsequent
crypto or sealing work, including ordinary storage errors after cancellation.
Diagnostic representations omit event content.

`prepareAndSealTextAt` protects the complete original row and separate replacement
fragments with the existing payload protector. The distinct
`proton-calendar-offline-text-preparation` version 1 format binds the selected
properties, expected and replacement values (including absence versus empty),
explicit timestamp and all caller identifiers. Opening with a fresh codec checks
the exact format, binding, closed JSON structure, budgets and allowed fragment
differences. Duplicate keys and historical title formats are refused. Reopening
is structural: it does not reverify signatures, certify a fresh observation or
authorize dispatch. No bootstrap or private key material is stored in the record.

## Complete local edit binding

`LocalTextEditPreparation` is a separate domain-owned historical record. Its
constructor retains a frozen copy of the paused local intent: mutation identity,
scope, observation format, complete original projection, complete replacement and
explicit timestamp. Only title, description and location may differ. Dates, zone,
precision, original color and null versus empty details remain exact. Recurrence
and nonempty details are refused. All six original/replacement texts have strict
UTF-8, control-character and 65,536-byte checks. Empty detail lists are detached
from mutable inputs. The existing title preparation type is not broadened.

`prepareAndSealLocalTextEdit` requires the current event observation format and
decodes the same frozen original row with the already unlocked keys. The complete
decoded base must match the local intent before patching, signing or encryption.
The bounded patcher observation reuses its existing framing and selected-property
checks to distinguish an absent optional property from an explicit empty value.
Selection comes only from raw differences in the local intent; an unchanged empty
field keeps its original presence and bytes. For a changed optional field, a new
empty domain string means removal. Observed text comparisons normalize CRLF to LF;
the retained local intent is never normalized. A newline-only difference can
therefore retain a complete intent while producing unchanged crypto fragments.

The distinct `proton-calendar-offline-local-text-preparation` version 1 format
seals that full intent together with the original row, fragments and nullable
property bindings. `openLocal` compares the complete expected intent before
reading the UID from protected history, then checks exact typed bindings,
selection and fragment structure. It needs no external UID. Payload swaps between
otherwise identical records with different mutation identities, altered dates or
projection formats, duplicate keys, unknown fields and other preparation formats
are refused.
These checks do not prove encrypted text semantics or renew current access.

## Authenticated fresh preparation

`CalendarLocalTextPreparationPort` is a separate project-owned capability for
the full paused text intent. `ProtonAuthentication.localTextPreparations` exposes
it through the existing shared authenticated runtime. Its local format is the
distinct local text format above; the title port, consent and formats retain
their restrictions. Unsupported guarded implementations return null without
falling back to an unrestricted operation.

The facade freezes and validates the intent before dispatcher switching or
waiting for the shared authentication mutex. The adapter refreshes the Core user
and addresses with separate checkpoints, then reads current calendar metadata,
bootstrap and the exact original event. Each response is copied before later
suspensions. It checks the exact local session, account/calendar/event identities,
UID, original revision including settings, and current member/signing-key policy
before invoking the existing full-base decoder and protected crypto preparation.
These successive reads do not constitute an atomic server snapshot.

The guarded path checks the caller's original scoped admission around each
controlled read and preparation stage. Once denied, that operation cannot reopen.
The facade also validates the complete returned intent, time and format and
expires its callback on every exit. Cancellation takes precedence even when a
dependency cancels then returns normally or throws an ordinary error. Network,
storage, permission and unsupported failures expose only closed categories.
Logout uses the same mutex: cleanup waits for an in-flight operation, rather than
immediately interrupting it. Preparation grants no sending authority or durable
retention; the caller still owns mode, generation and local intent lifetime.

## Protected UPDATE request and supplied readback

An internal text request codec seals a distinct versioned envelope binding the
request identity, complete local preparation, its protected-payload hash, exact
context and literal same-calendar UPDATE body. Reopening reconstructs that body
from the retained fragments and refuses substitutions, unknown keys, duplicate
JSON keys, malformed UTF-8 and oversized values. Inputs and returned structures
are snapshotted. Protection failures remain sanitized and cancellation takes
precedence around protection callbacks.

Request construction shares a pure closed profile with the existing title path;
its compatibility wrappers and historical formats remain unchanged. This profile
requires known-empty details, no color, no recurrence, no personal/invitation
payload and exactly two shared parts. Unknown GET fields cannot be silently
dropped. A normalized newline-only preparation remains recoverable history but
cannot become an UPDATE request. This narrower profile does not make every
supported offline preparation sendable.

The separate readback verifier consumes supplied observations without fetching
them. It checks cancellation before serialization and bounds each JSON snapshot
to 4 MiB of strict UTF-8 before parsing or opening protected history.
It requires the retained account/context/bootstrap, exact signed parts and
untouched metadata, then invokes native verification with the known-empty policy.
The decoded event identity, computed new revision and entire content must match.
Only CRLF-to-LF equivalence in title, description and location is accepted; dates,
zone, precision and other fields remain exact. It returns the actual decoded
content while retaining the raw local intent. Future acknowledgement must handle
that distinction explicitly.

The shared clock comparison allows the narrowly measured title transformations
of ModifyTime and LastEditTime. Applying it to broader text is conservative reuse,
not evidence of a description/location server round trip. These internal records
have no submission state, transport authority or journal integration. Supplied
readback does not prove fresh access, an atomic server snapshot or durable success.

## Quarantined durable attempt history

A portable immutable text attempt now retains the complete raw intent, preparation,
request format and protected bytes, with PREPARED or OUTCOME_UNKNOWN state and
optional opaque evidence. Equality includes every preparation field rather than
the identity of a preparation object. Known-empty details, no color/recurrence and
a meaningful text change remain required. Evidence cannot accompany PREPARED.

A SQLite journal retains one unresolved event and request identity per
account/calendar across generations. All operations require explicit scoped
leases. Exact PREPARED retention is idempotent; claim compares the entire retained
record and returns true only after committing UNKNOWN with synchronous=FULL.
Revocation before commit rolls back. Cancellation after commit cannot undo that
history. UNKNOWN cannot be reset, replaced or automatically retried. The first
opaque evidence is immutable and grants no verified success or acknowledgement.

The project-owned binary capsule preserves raw CRLF, temporal precision, timezone,
exclusive all-day end and opaque adapter formats. It has explicit schema/tags,
strict UTF-8, bounded fields, a 16 MiB aggregate limit and mandatory end of input.
The backup-excluded database stores protected payloads alongside local intention
metadata; binary framing itself is not encryption. SQL bounds metadata before
CursorWindow allocation and reads the capsule in 64 KiB BLOB segments within one
writer transaction. Corrupt or incompatible storage raises a sanitized error;
it cannot masquerade as absent work. There is no implicit migration or purge.

The title helper now hosts an internal text journal in the same database. Title
reservation, retention and claim (including historical overloads) qualify text
owners by event or request ID across generations. Reservation adoption also checks
the retained canonical request ID. Text retention and claim qualify the full title
history, including bare reservations, reverse reservation/wire links and complete
completion proofs. Only a valid title completion releases its event; its request ID
cannot be reused by text on another event or generation. Account/calendar scopes
remain independent. All these checks and writes share one SQLite writer transaction;
SQL callbacks stay synchronous on its owning thread, with cancellation checks.
Keeping existing evidence, results or completions is separate from acquiring a
new owner. The additive title schema upgrade creates the text table without
rewriting historical title cells.

The standalone text database remains quarantined, with no implicit import into
the shared journal. The shared journal now retains a first immutable verified
result and a separately qualified completion. Completion releases the event only;
the historical request identity remains reserved. Every completed row is checked
against its full result and RESTORED acknowledgement before another owner is
admitted. Corrupt historical completion cannot be treated as released ownership.
Two independent databases cannot grant concurrent sending for the same event.

## Guarded delivery and local acknowledgement

A separate authenticated text submission port reuses the existing UPDATE transport
permit and Proton signing/encryption boundary. Its source qualification surrounds
the PREPARED-to-UNKNOWN claim and the exchange. UNKNOWN is inspection-only. A
distinct, scoped evidence lease can retain a factual reply after editing consent
is revoked; it expires with the invocation and cannot survive logout. This grants
no native acknowledgement or another send.

The first complete local capsule determines the deterministic request identity.
The coordinator rereads any retained wire before preparing a new one, and never
replaces randomized ciphertext with a later preparation. The text discovery profile
requires a meaningful description or location change; a simultaneous title change
is allowed. Title-only changes keep their own consent and historical formats.

Import storage retains the raw local intent and a receipt before native writes.
Android acknowledgement compares the complete original row, child rows and
identity in one batch, then clears DIRTY and advances revision/nonce markers.
Only text fields that differ by CRLF/LF are canonicalized to the actual verified
readback. Dates, timezone, colors and other fields are unchanged. A retained pending
receipt only inspects these markers; it never repeats the native write. SQL then
restores the actual remote baseline while preserving newer edits and deferred
data. Completion is committed separately, so an interruption between databases
keeps the event held until local recovery finishes.

Bounded offline proof recovery precedes discovery. Text owner, inspection and
recovery cursors use distinct preference namespaces. The worker captures its
policy lease before suspension; the traversal passes that original admission
through to acknowledgement. The import lock is acquired before the authenticated
submission lock, and its source callback performs local reads without reacquiring
either lock. This preserves the order used by incoming synchronization.

The production text activation gate is enabled for this restricted experimental
profile after separate service, automatic-worker and system-editor qualification.
The text option is hidden without both
recovery and submission controllers. Its per-calendar consent is separate and
off by default; disabling creation clears it. Existing durable proof can recover
with text sending disabled, subject to current account, calendar and native access.

## Integration and evidence limits

The plaintext patcher itself has no keys, network reads, persistence, dispatch
permission or Android provider access. Protected fragment preparation adds no
database transaction, automatic recovery or worker integration.

Focused JVM tests cover optional-property insertion/removal, absence versus empty,
combined edits, untouched spans, Unicode escaping/folding, invalid bindings,
budgets, metadata preservation and sanitized refusals. Existing title tests check
the compatibility entry point. Codec tests cover exact bindings, structural
refusals, protection failures and snapshot isolation. A synthetic physical-device
probe checks original binary/text signatures, replacement decryption and signatures,
original key-packet reuse, untouched fields, no-op ciphertext and reopening with
new protector/codec instances. Its cancellation cases inspect errors inside the
cancelled child coroutine. Domain and local codec tests additionally cover complete
local-observation binding, unchanged absent/empty properties, removal, newline-only
no-ops and mutable input isolation. A separate synthetic local-intent native probe
checks full-base refusal before sealing, timed/all-day date preservation and those
optional-field cases. Native recovery uses new instances in the same process;
the probe also exercises the exact runtime crypto callback through the fresh
adapter with synthetic read responses, rejects a full-base mismatch before
sealing, and refuses revoked admission before reads.
Its explicitly closed-profile extension builds protected requests and verifies
supplied timed/all-day readbacks through the real Core decoder, including combined
text edits, multiline notes, optional removal and normalized no-op refusal.
An invalid signature is also retained in both the expected fragments and supplied
row, so rejection requires native verification rather than a structural mismatch.
JVM readback tests additionally require malformed or oversized observations to be
rejected before opening protected history, and cancellation before serialization.
It does not qualify a durable journal or process-death recovery. These checks do
not establish a description or location round trip with the Proton service or a
system calendar client.

Separate JVM tests exercise the full attempt identity and a literal binary fixture,
including malformed lengths, tags, UTF-8 and budgets. Opt-in physical SQLite
fixtures exercise helper reopening, concurrent ownership and claim, full-record
substitution refusal, lease rollback/cancellation, immutable evidence, capsules
larger than a CursorWindow cell and corrupt metadata/payloads.
Shared-database fixtures additionally exercise cross-family reservation/retention
and claim, competing helpers, request IDs across events/generations, valid versus
malformed completion release, canonical reservation adoption and additive migration
with exact historical-cell snapshots and failed-upgrade rollback. Opaque synthetic
strings qualify persistence only. Helper reopening is not a process-death or
power-loss qualification, nor evidence of production recovery or remote sending.

An explicitly composed ordinary timed service journey qualifies a combined
multiline title/description/location UPDATE through the real authenticated ports.
It verifies actual canonical LF content, retains raw CRLF intent after mode
revocation, and acknowledges the same Android row offline in a distinct process.
Historical replay preserves a later local owner and the complete original proof.
This explicit composition does not qualify scheduling or a system editor.

A separate physical journey opens the imported ordinary timed event in the
system editor, changes description and location, and observes production workers
without calling an outgoing controller directly. It requires full immutable
request/result/completion history, same-row native acknowledgement and converged
queues. Active-worker replay in another process preserves that history without
another attempt. A separate recovery phase qualifies an already saved interrupted
editor edit, including its strictly redundant incoming receipt. All three calendar
mode flags are restored for the target and temporarily isolated other calendars.
The tested editor is `foundation.e.calendar`; Samsung, all-day service UPDATE,
transferred CREATE text delivery and periodic scheduling remain unqualified.

Historical title records must not be
reinterpreted as permission for broader edits. The existing concurrent GET/PUT
race remains; plaintext patching supplies no server-side write precondition.

See [source attribution](../proton-auth/PROVENANCE.md),
[metadata source audit](research/TITLE_EDIT_METADATA.md) and
[conditional writes](research/CONDITIONAL_WRITES.md).

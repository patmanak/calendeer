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

## Integration and evidence limits

Inputs and outputs are ephemeral adapter-owned plaintext with redacted diagnostic
representations. Changed shared parts need replacement signatures and encryption
before they can form a request. This primitive has no keys, authenticated network
reads, persistence, dispatch permission or Android provider access.

Focused JVM tests cover optional-property insertion/removal, absence versus empty,
combined edits, untouched spans, Unicode escaping/folding, invalid bindings,
budgets, metadata preservation and sanitized refusals. Existing title tests check
the compatibility entry point. These checks do not establish a description or
location round trip with the Proton service or a system calendar client.

The next integration needs a distinct protected text preparation format, complete
authenticated readback, guarded durable submission and acknowledgement, followed
by physical system-editor qualification. Historical title records must not be
reinterpreted as permission for broader edits. The existing concurrent GET/PUT
race remains; plaintext patching supplies no server-side write precondition.

See [source attribution](../proton-auth/PROVENANCE.md),
[metadata source audit](research/TITLE_EDIT_METADATA.md) and
[conditional writes](research/CONDITIONAL_WRITES.md).

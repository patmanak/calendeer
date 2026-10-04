# Canonical events and projection representations

## Current boundary

`ProjectedEventReference` is a pure, immutable model in `sync`. It separates a
canonical event from the recurrence representation selected for a native view.
It has no account/calendar scope, row locator, receipt, acknowledgement or write
authority. The model and bounded calculator have no runtime import callers.
Neither persistence nor production activation is implemented by this model.

The canonical upsert retains its opaque event ID and revision, original timezone,
time, rule, UID, exclusion/original-slot identities and supported display details.
A finite date view changes only the rule and additional dates. It is not another
remote event and must never replace the canonical import or edit baseline.
Unknown details remain unknown; a representation cannot invent empty details.

| Representation | Creation behavior | Limits of the claim |
| --- | --- | --- |
| `direct`, version 1 | Own an immutable canonical snapshot; its native change is the same event. | Does not qualify Android admission or the current geometry of an older row. Existing native mapping rules still apply. |
| `finite-daily-dates`, version 1 | Calculate and own exact instants once; derive an RDATE view from the canonical event. | Limited to the bounded resolved-time profile below; not a historical reload API or Proton source qualification. |

Representation versions are independent of receipt protocols, remote adapter
formats and mapping profiles. Equality includes the entire canonical event,
representation and exact dates. Two representations of the same event differ
even when `COUNT=1` gives them the same visible occurrence.

Factories revalidate mutable collection aliases and freeze recurrence, reminder
and participant lists before calculation. No public constructor or `copy` can
replace a reference's canonical event or provide arbitrary finite dates.
Diagnostics redact event contents, dates and identities.

## Bounded finite calculation

`FiniteDailyRecurrenceProjection` contains the arithmetic previously qualified by
the debug experiment. Each local date is derived independently from the original
resolved start. Repeated hours use the earlier offset; nonexistent hours use the
preceding offset without carrying the normalized hour to subsequent dates.
The original rule and exclusions stay separate from the generated dates.

The profile requires an unambiguous resolved timed master, exact simple DAILY
COUNT from 1 through 32, optional INTERVAL from 1 through 366, at most 366 nominal
days from first to last date, no additional input dates and at most 32 timed
exclusions. Duration is 1 second through 7 days; timestamps have whole seconds and
native dates stay in years 1 through 9999. See the
[source audit](research/ANDROID_RECURRENCE.md) for arithmetic and source limits.
An already normalized instant does not recover a raw nominal DTSTART in a gap.

The debug fixture delegates to this pure calculator. The calculator is now part
of the main `sync` source set; the fixture entry point remains debug-only. This
extraction does not enable a new parser or Calendar Provider path.

## Required durable integration

The chosen integration separates canonical content from an immutable, versioned
representation. It must save the exact calculated dates before writing Android;
an algorithm name alone cannot preserve results across timezone database or
planner changes. Reopening must decode saved values without recalculating them.
Creation eligibility and historical structural decoding are separate operations.
These storage/recovery requirements are not yet implemented.

Preparation must bind the exact canonical intention, representation, nonce and
complete previous applied reference in one local transaction. A previous
canonical event alone cannot distinguish two representations of that event.
Successful acknowledgement must retain the canonical event **and** its applied
representation after deleting the temporary receipt. Newer imported evidence
must remain pending when an older receipt is recovered.

Android application must use the saved view, while ownership, event/series IDs,
revision and details continue to derive from the canonical event. Historical
inspection remains an identity/revision/nonce proof, independent of later local
content. Local restoration authorizes only the exact persisted preparation;
the marker alone does not authenticate recurrence geometry or copied content.

Migration must keep existing canonical encodings and retain old preparations.
Rows without representation metadata remain in the historical direct mode;
they must not be silently replanned or repaired. Unknown/malformed representation
formats remain blocked and preserved, without a direct fallback. An older
application must not reinterpret the new storage format as a supported one.

The shared captured-create handoff remains simple/direct. Its strict historical
receipt encoding requires compatibility checks before extending shared models.
Ordinary local capture continues to refuse recurrence; a native RDATE edit cannot
be treated as a canonical RRULE edit. Exception UID and original-slot identity
remain those of the master, including changed display date types.

Activation requires integrated SQL migration/reopening, complete-reference
comparison, lost-acknowledgement recovery, dirty/deleted protection, exact native
instances/exceptions/cache replacement and raw-source eligibility. The model
does not qualify recurrence editing, infinite/complex rules, larger bounds,
general timezone/OEM behavior or arbitrary power loss.

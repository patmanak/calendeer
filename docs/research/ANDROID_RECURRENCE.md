# Android recurrence expansion and timezone transitions

Source baseline inspected on 2026-10-04. This audit explains the measured native
transition defects and defines the next bounded experiment. It does not change
projection, admission, historical receipts or editing capabilities.

## Pinned source baseline

| Repository | Inspected revision | Relevant paths |
| --- | --- | --- |
| `platform/frameworks/opt/calendar` | Android 15 tag, `08ed6ee3a860b6236e38794884207f97a91238dd` | `src/com/android/calendarcommon2/{RecurrenceProcessor,Time,EventRecurrence,RecurrenceSet,DateException}.java` |
| `platform/packages/providers/CalendarProvider` | Android 15 tag, `a37b87f14b6ac0daeade7f8c29a83b02cae581fe` | `src/com/android/providers/calendar/{CalendarProvider2,CalendarInstancesHelper}.java` |
| `platform/libcore` | Android 15 tag, `a996d969fdd17c6707b84544e5d1c35e0c25b5cb` | `ojluni/src/main/java/java/util/GregorianCalendar.java` |

The earlier recurrence audit used a 2012
[processor revision](https://android.googlesource.com/platform/frameworks/opt/calendar/+/a30f9f3ab4e0e42e02540518dd37b352257cd98d/src/com/android/calendarcommon2/RecurrenceProcessor.java).
That source uses `android.text.format.Time` and direct local-field arithmetic.
The [2020 migration](https://android.googlesource.com/platform/frameworks/opt/calendar/+/7bb1f3b0ebe44e28fbd3e34da7e6995d0c7a2d82)
introduces the `GregorianCalendar` implementation used by the inspected Android 15
processor. Its `Time` blob is `f0af248e09335886366d33e7bad04cbfeaef28e7`, and its
processor blob is `24decce3948790d56887fd10622ae1132c05b3f2`.
The inspected `main` revision, `68b3632330e7a9a4f9813b7eb671dbfd78c25bcd`, has the
same `Time` blob; its processor differs only by removal of two unused variables.
This comparison establishes no correction of the examined path at that revision,
not the absence of fixes in every branch or OEM implementation.

## Expansion path

The [Provider instance helper](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarInstancesHelper.java#290)
sets the event timezone and stored start, passes recurrence data to
`RecurrenceProcessor.expand`, then uses returned epoch values for `Instances.BEGIN`
and adds the event duration for `Instances.END` (lines 290–355).
Changing the query/display timezone after expansion computes display fields; it
does not correct an already incorrect occurrence instant.

The [modern processor](https://android.googlesource.com/platform/frameworks/opt/calendar/+/08ed6ee3a860b6236e38794884207f97a91238dd/src/com/android/calendarcommon2/RecurrenceProcessor.java#875)
reads each iteration's local hour, generates a candidate and advances the iterator
with `Time.add` (lines 875–881, 978–981, 1062–1086).
The [Time implementation](https://android.googlesource.com/platform/frameworks/opt/calendar/+/08ed6ee3a860b6236e38794884207f97a91238dd/src/com/android/calendarcommon2/Time.java#134)
delegates addition to `GregorianCalendar.add`; setting local fields immediately
reads normalized calendar fields, and getters read the calendar itself
(lines 134–140, 162–166, 294–348). The older `unsafeNormalize` comment therefore
does not establish that modern iteration retains a nonexistent nominal hour.

The inspected [Android GregorianCalendar](https://android.googlesource.com/platform/libcore/+/a996d969fdd17c6707b84544e5d1c35e0c25b5cb/ojluni/src/main/java/java/util/GregorianCalendar.java#2748)
resolves ambiguous local fields using standard time (lines 2748–2768).
Its date-addition and wall-time offset paths explain normalization across the
spring gap (lines 1065–1096, 2846–2958). These mechanisms are consistent with the
previous Provider measurements; source similarity does not identify OEM binaries.

## Transition mechanisms and evidence

Fixtures use Paris local 02:30 and `FREQ=DAILY;COUNT=3`. Expected instants follow
[RFC 5545 with verified erratum 4271](https://www.rfc-editor.org/rfc/inline-errata/rfc5545.html):
the first passage of a repeated local time, and the preceding offset for a
nonexistent local time. A gap occurrence consumes `COUNT`; each subsequent date
must retain the original nominal local hour.

| Case | Expected UTC starts | AOSP replay on the controlled Android runtime |
| --- | --- | --- |
| Later autumn fold | Oct 26 `00:30`, Oct 27 `00:30`, Oct 28 `01:30` | Oct 26 `00:30`, Oct 27 `01:30`, Oct 28 `01:30` |
| Later spring gap | Mar 30 `01:30`, Mar 31 `01:30`, Apr 1 `00:30` | Mar 30 `01:30`, Mar 31 `01:30`, Apr 1 `01:30` |

All dates in this table are in 2030. The autumn conversion chooses the later
passage. At the spring gap, the iterator becomes 03:30 and propagates that
normalized hour into the following day. Removing the gap occurrence with an
exclusion does not independently restore the iterator's nominal hour.

Explicit dates have another loss mechanism: the
[processor's intermediate set](https://android.googlesource.com/platform/frameworks/opt/calendar/+/08ed6ee3a860b6236e38794884207f97a91238dd/src/com/android/calendarcommon2/RecurrenceProcessor.java#665)
stores packed local date/time fields, without offset (lines 665–691, 1284–1300).
Two UTC instants representing both passages of Paris 02:30 become one set key.
Final conversion resolves that key again. An `EXDATE` for either passage can also
remove the common key. Native UTC preserves distinct keys and exact exclusions;
merely writing `Z` in RDATE while retaining the Paris master timezone does not.
This explains the existing UTC representation for timed RDATE-only masters.

Ten isolated source-replay cases passed on a controlled Android runtime: a safe
daily rule, later fold and gap, ambiguous start, Paris/UTC explicit date sets,
Paris/UTC single-passage exclusions, and spring/autumn rules with explicit
`BYHOUR=2`. The explicit-hour spring case retains 02:30 on the following day,
but the autumn case still shifts. This is not a general rule-rewriting solution.

Replay compiled unchanged `RecurrenceProcessor`, `Time`, `EventRecurrence` and
`DateException` from the pinned Android 15 source, with a minimal already-parsed
recurrence carrier. On Android it used platform time and logging classes, and ran
outside the application without accessing the Provider or Proton. It did not
exercise upstream recurrence serialization, Provider caching, calendar UI or
receipts. Previously measured Provider results independently match the fold,
gap and explicit-date mechanisms. Neither result proves the OEM uses these exact
source files. Downloaded code and execution artifacts are research-only, not
dependencies of the shipped application.

The same replay on OpenJDK 21 reproduced nine cases but differed for the simple
spring rule: Mar 30 `01:30Z`, Mar 31 `00:30Z`, Mar 31 `23:30Z`.
The failed host comparison is retained as evidence of runtime dependence.
A desktop replay cannot substitute for Android or Provider qualification.

## Cache invalidation

[CalendarProvider2](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarProvider2.java#4093)
recalculates `LAST_DATE` from merged event data when temporal/recurrence fields
change, but calls instance update with the modification values
(lines 4093–4119, 4165–4168).
[CalendarInstancesHelper](https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/a37b87f14b6ac0daeade7f8c29a83b02cae581fe/src/com/android/providers/calendar/CalendarInstancesHelper.java#626)
returns early when those values contain no `DTSTART` (lines 626–638).
Thus a timezone-only change can leave previously expanded instances unchanged.
Supplying DTSTART alone is not a qualified replacement contract either: the
helper also reads recurrence and original-slot fields from those values.
Qualification must reapply the complete intended temporal/recurrence payload,
check already-expanded windows and linked exceptions, and preserve native IDs.
The existing production mapper supplies these fields; this audit adds no repair.

## Finite daily representation experiment

A bounded prototype derives each local date independently from the
original nominal start, chooses the first offset during a fold or the preceding
offset during a gap, and produces a separate complete RDATE recurrence view.
The existing native mapper can project that synthetic view in UTC without RRULE.
There is no runtime import caller or change to the parser/native admission guards.
The prototype snapshots the original rule and exclusions separately and does not
rewrite the canonical input event, timezone or exception identity.

Its provisional experiment bounds are an unambiguous resolved timed start,
exact uppercase `FREQ=DAILY;COUNT=1..32`, optional `INTERVAL=1..366`, at most
366 nominal days from first to last date, no additional RDATE inputs, and at most
32 timed EXDATE inputs. Duration is 1 second through 7 days; timestamps have whole
seconds, and every nominal date and native start/end/exclusion stays in years
1 through 9999. Other selectors, infinite rules, all-day masters and ambiguous
starts are refused. Dates and recurrence lists are owned immutable snapshots.
These bounds do not establish support for every admitted timezone or Provider cost.

The experiment's source is an already resolved domain instant. It cannot recover
an original nominal DTSTART that an upstream parser has already normalized out
of a gap. Source-level eligibility and original local-field preservation need
separate qualification before any production integration.

Nine focused JVM cases pass, including independent fold/gap arithmetic, a
half-hour gap, interval/count boundaries, exclusions, year overflow, refusal and
snapshot isolation. The initial placeholder failed six positive cases; review
then exposed and reproduced year-boundary and input-list alias defects before
their correction. Four physical Android fixtures pass for exact instances,
exceptions, cached replacement, dirty/deleted protection and lost-ACK reopening.
The first run exposed an incorrect native-marker assertion in the recovery test;
the corrected test qualifies the persisted-receipt comparison described below.
Both complete recurrence/recovery device classes pass 78 methods without failures
or skips on the qualified physical target. These are isolated native fixtures,
not a live Proton import or broader timezone/OEM qualification.

Those fixtures deliberately persist and authenticate the synthetic RDATE view.
They do not establish that a canonical RRULE import can use that representation
while keeping its durable canonical baseline and historical receipts. Native
inspection verifies a historical identity/nonce marker, not recurrence content;
SQLite recovery must reject a copied canonical receipt because it differs from
the complete persisted date-view receipt. Versioned
representation selection, parser eligibility, ordinary matching and recovery
must be integrated and tested together before activation.

Acceptance must cover exact starts/ends, original-slot exclusions, moved and
cancelled exceptions, conversion of exception display types, cached expansion,
clean replacement with stable parent/child IDs, and restart after a lost
acknowledgement. Historical receipts and pending preparations must retain their
existing interpretation; a new representation must be recognized by normal
matching and recovery before it can be enabled. Dirty/deleted rows must remain
protected. Evaluate Provider size and cost before choosing a larger bound:
the admission guard's 4096 limit is not a materialization performance guarantee.

A direct UTC RRULE would keep a fixed UTC hour and change the nominal local hour
after a transition. Synthesizing corrective exceptions would require separate
source/native original-slot mappings. Infinite or complex rules require a
durable horizon-renewal design and remain outside this experiment. Current
admission guards and unsupported-source preservation remain in force.

The calculator has since been extracted into the main `sync` source set;
`FiniteDailyRecurrenceExperiment` remains a debug-only fixture entry point.
A pure [versioned reference](../PROJECTION_REPRESENTATION.md) freezes canonical
event content separately from the generated native view, including exact dates
in equality. Nine reference cases and the original nine calculator cases pass.
This model has no persistence or historical reload API and does not enable the
bounded profile in the Proton parser or Android projection.

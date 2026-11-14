# Recurring-event writes: Proton source study and implementation slices

Inspected 2026-11-14. Requirement: V05; preservation outside qualified support:
V06. This is published-source evidence and an implementation plan, **not an
outgoing recurrence capability or live qualification**. Current simple-event
formats and UNKNOWN recovery remain unchanged.

## Reproducible source baseline

Anonymous GitHub API resolution found five Android branches. The default branch
is older than `release/2.34.1`; that release was resolved independently. Both
recursive trees were complete (`truncated=false`): Android 1,060 entries and Web
26,146 entries. Source files were read, not executed or added as dependencies.

| Official repository/ref | Resolved commit | Relevant implementation |
| --- | --- | --- |
| Android Calendar, `release/2.34.1` | `dbc20fa485ca9a3e02988ec8f430145fc9e0b1af` | [Save](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt), [delete](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleDeleteUseCase.kt), [event model](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/app/src/main/java/me/proton/android/calendar/domain/model/Event.kt) |
| WebClients, `main` | `c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672` | [Recurring save actions](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/eventActions/getSaveRecurringEventActions.ts), [delete actions](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/eventActions/getDeleteRecurringEventActions.ts), [encrypted payload construction](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/getSyncMultipleEventsPayload.ts) |

## Observed behavior and reuse

- A master and a detached exception have different remote event IDs and share an
  iCalendar UID. Resolve a family by account, calendar and canonical UID. Native
  hashed sync IDs and a bounded display window cannot establish complete membership.
- Editing one generated occurrence creates a detached event. It retains the UID,
  removes RRULE/EXDATE and records the **original** slot in RECURRENCE-ID, with the
  master's DATE/DATE-TIME type and timezone. Its new DTSTART may move or change
  event type. Updating an existing exception keeps that original slot. See
  [createSingleRecurrence](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/recurrence/createSingleRecurrence.ts).
- Deleting a generated occurrence adds an EXDATE to its master. Deleting a detached
  exception can combine that update with deletion of the child; deleting just the
  child may restore the generated occurrence. Android's implementation also handles
  orphan/standalone cases separately. See
  [deleteSingleRecurrence](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/recurrence/deleteSingleRecurrence.ts).
- Whole-series edits have explicit modes for retaining or removing exceptions and
  exclusions. Keeping exceptions can require propagating changed fields to each
  child; a master-only PUT is not equivalent. Whole-series deletion enumerates the
  family. “This and future” is another split operation, not part of the initial
  series/one-occurrence slice. See
  [updateAllRecurrence](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/recurrence/updateAllRecurrence.ts).
- Recurring save actions may initialize a missing parent SEQUENCE; child sequence
  must account for its parent's sequence. Preserve signed unknown fields and use
  Proton's established key/encryption flow, including personal alerts/colors.
  A cancelled invitation is not interchangeable with a personal EXDATE/deletion.
- The [API](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/api/calendars.ts)
  uses PUT `calendar/v1/{calendarID}/events/sync` for grouped creates/updates/deletes.
  [Types](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/packages/shared/lib/interfaces/calendar/Api.ts)
  expose indexed per-operation responses and paginated UID lookup. Source inspection
  establishes neither atomic batch commit nor retry idempotency, including for
  `Overwrite: 0`. An uncertain batch needs inspection, never automatic replay.

Reuse Biweekly already present in Calendeer; Android pins the same 0.6.8 in its
[manifest](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/gradle/libs.versions.toml).
Port narrow transformations and selected upstream fixture cases into the existing
adapter; do not import Android use cases with their repository/UI dependencies or
Web's TypeScript dependency graph. Both repository LICENSE files contain GPLv3:
[Android](https://github.com/ProtonMail/android-calendar/blob/dbc20fa485ca9a3e02988ec8f430145fc9e0b1af/LICENSE),
[Web](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/LICENSE).
Retain applicable notices and pin every extracted file/adaptation in provenance.
This study adds no upstream code to the shipped application.

Do not copy the [Web slot helper's](https://github.com/ProtonMail/WebClients/blob/c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672/applications/calendar/src/app/containers/calendar/event/getSingleEditRecurringData.ts)
fallback to a nearby occurrence when an original slot cannot be found, or its
ten-million-occurrence expansion bound. Calendeer must retain an unresolved change
as unsupported, with bounded work. The Android save source itself contains timezone
TODOs; published implementation is guidance, not proof that every case is correct.

## Integration and ordered slices

Extend the existing CREATE, personal-update and DELETE pipelines and their sealed
preparations/attempt stores. Do not add a separate recurring-event synchronization
engine. New admitted profiles need versioned capture/preparation/request contracts;
do not relax historical constructors/decoders or reinterpret saved requests.
`LocalCreateIntent`, `LocalEditIntent`, personal-v2 preparation/readback and native
capture currently reject recurrence deliberately. A change to only the iCalendar
serializer cannot complete this feature.

| Slice | Observable result and owned boundary | Required evidence before activation |
| --- | --- | --- |
| L9b | Create a new personal series from Provider, initially a strict supported RRULE subset. Extend CREATE capture, crypto/readback and exact native acknowledgement together; preserve old profiles. | E2 RED for rule/type/zone admission and version compatibility; E3 Provider → retained intent → crypto/request → readback → ACK, all-day/timed, offline staging, lost responses, no duplicate creation. |
| L9c | Edit a whole personal series without detached children first; retain unsupported child-bearing edits visibly until L9f. Extend the personal-update profile and ownership proof. | E3 canonical rule/exclusion preservation, dirty/newer edit protection, cached Instances after complete temporal replacement, color masking, retained UNKNOWN and account/permission revocation. |
| L9d | Edit one occurrence, creating or updating its detached event at an exact original slot. Bind the observed Android exception to its qualified parent, never by row ID alone. | E3 moved slot, DATE ↔ DATE-TIME, original timezone, duplicates, orphan/invalid parent, cross-calendar isolation and lost CREATE/ACK. |
| L9e | Delete one occurrence or an entire series without regenerating deleted slots or leaving remote exceptions. | E3 complete paginated family discovery, parent EXDATE plus child deletion, per-operation outcomes, interrupted inspection and exact native tombstone acknowledgement. |
| L9f | Support whole-series edits with detached exceptions and the qualified rule set. | E3 explicit preservation/propagation policy, complete family snapshot, newer child edits, interrupted grouped requests, all unknown fields/default alerts; no guessed family deletion. |

Each slice is several bounded commits if needed. Keep unqualified forms visible
and retained; these intermediate restrictions do not reduce the V05 release target.
Guest/invitation changes remain L10. Live client journeys remain grouped E4.

Before a compound write, freeze complete family membership, source revisions,
original slots and every encoded operation in the existing preparation envelope.
Recheck current access and ownership without regenerating that identity. Record
and verify each result; inspect all uncertain operations independently of current
native edits. A partial batch must not release unrelated acknowledgements or permit
another operation to recreate a slot. The existing GET-then-PUT concurrency limit
still applies; source inspection supplies no server compare-and-swap guarantee.

Use project-owned recurrence/slot types and behavioral ports; Proton DTOs, keys
and pagination remain adapter-owned, Provider rows remain in the native boundary.
Run the same intent/recovery contract against a deterministic replacement adapter
before wiring the real one. Begin with source-preserving serialization/crypto
fixtures, then Provider/SQL E3. Never activate a parser-only or serializer-only path.

Keep the measured [Android DST and finite-representation constraints](ANDROID_RECURRENCE.md)
and saved [representation references](../PROJECTION_REPRESENTATION.md). Display RDATE
materialization is not a canonical rule to upload. Existing finite receipts remain
inspectable; unsupported transitions must not be normalized or silently shifted.

## Evidence and next action

PASS: current official refs resolved, complete trees checked, listed implementations
and licenses read, existing Calendeer admission boundaries compared. No new upstream
dependency was introduced. Live service/client journeys remain NOT RUN; no recurring
writer is enabled in the controller.
L9b's [qualified foundations](../LOCAL_CREATE_CAPTURE.md#series-create-foundations--not-enabled-for-submission)
now include strict rule admission, content snapshot schema 3, explicit native
observation, shared signed/encrypted serialization, protected v4 capsules and their
canonical readback, plus v2 dispatch fingerprints and existing-table migration.
Explicit native current-content and acknowledgement operations now retain the rule,
duration and verified UID, with atomic child guards and historical ACK recovery.
Canonical expectations now reopen from the exact capsule and reach binding,
restoration and ACK through an explicit internal series-staging route. Historical
capsule profiles remain unchanged; the worker's default capture and Proton v4
dispatch stay closed. Series handoff and historical recovery now use that same source;
contextual transfer checks preserve the simple-edit refusal and hold unsupported
local changes. Next: enable the qualified fresh Proton submission capability and
worker route with end-to-end E3, then group live-service/client journeys in E4.

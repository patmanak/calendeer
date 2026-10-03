# Title-edit metadata boundary

Inspection date: 2026-10-03. Evidence: pinned official client source and an
offline plaintext experiment, not a server contract or a qualified UPDATE.

## Observed client behavior

Web Calendar's `withUpdatedDtstamp` consults `getHasUpdatedInviteData`. SUMMARY
is among the compared properties; comparison unescapes, removes HTML and trims
values. Thus a meaningful title change can renew DTSTAMP, while a whitespace-
or markup-only difference can preserve it. This is not a rule that every edit
always advances DTSTAMP.
[Timestamp helper](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/containers/calendar/eventActions/dtstamp.ts#L9),
[comparison](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/calendar/mailIntegration/invite.ts#L951).

For unchanged times and recurrence, Web's sequence helper can retain an existing
SEQUENCE. Android's `handleSequence` considers status, start/end and recurrence
changes; title alone is absent from its increment condition. SEQUENCE therefore
remains scheduling metadata, not an immutable revision or conflict precondition.
[Web sequence helper](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/applications/calendar/src/app/containers/calendar/eventActions/sequence.ts#L19),
[Android save logic](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/HandleSaveUseCase.kt#L980).

The clients differ in partition placement. Android's split puts CREATED and
LAST-MODIFIED in clear shared data and repeats DTSTAMP in clear/encrypted shared
parts. Web's encrypted shared field list includes CREATED; LAST-MODIFIED falls
outside its known field lists into remaining encrypted properties. A single
placement rule cannot be presented as universal Proton compatibility.
[Android partitioning](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/utils/ICalUtilsImpl.kt#L301),
[Web field lists](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/calendar/constants.ts#L315),
[Web partitioning](https://github.com/ProtonMail/WebClients/blob/40cc08c12c8fbac7f66521186c7297ddf3e17861/packages/shared/lib/calendar/veventHelper.ts#L296).

## Local experimental policy

The separate `ProtonSharedTitlePatcher.patchAt` experiment admits only a narrow
Android-shaped plaintext layout, also emitted by Calendeer's CREATE serializer.
Recognizing this layout does not prove that Calendeer created an event.
It requires unique unparameterized CREATED, LAST-MODIFIED and SEQUENCE in clear
shared text, with none of those properties in encrypted shared text. Both existing
DTSTAMP fields and the expected signed UID/title must already match.

For a changed title, an explicit caller-supplied UTC instant at second precision
replaces both DTSTAMP spans and clear LAST-MODIFIED. CREATED and the original
SEQUENCE span remain exact, including spelling/folding/leading zeroes. Other
properties, parameters, duplicate opaque fields and nested components remain raw.
The instant must be in 1970–9999, not precede either old modification stamp, and
CREATED must not follow the existing modification stamps. These chronology
checks and the LAST-MODIFIED update are local admission choices, not a demonstrated
Proton server requirement or an exact copy of every client behavior.

Recognized event-level recurrence/invitation markers and calendar METHOD are refused for a
transformation. This only checks plaintext markers, not complete event eligibility
or opaque JSON partitions. A validated logical no-op retains both original
strings exactly, without metadata rewriting; its explicit instant must still have
the supported representation. Other layouts are refused rather than moved or
rebuilt from the display model. No timestamps are read from a system clock.

This method has no production or crypto-preparation caller. Its output is
plaintext only; old signatures would not authenticate either modified part.
Re-signing both parts, confirming their authors, synchronizing timestamp-bearing
personal partitions, proving current access and preserving the full wire envelope
require separate qualification. Existing title-only crypto fragments retain their
earlier timestamp behavior. No event JSON or UPDATE request is assembled, and
the [conditional-write boundary](CONDITIONAL_WRITES.md) remains unchanged.

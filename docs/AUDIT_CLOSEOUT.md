# Engineering audit closeout

The initial audit's three confirmed robustness defects are corrected and have
focused regressions. This closes their remediation, not release qualification or
an exhaustive security assessment. No exploitable authentication, transport or
WebView defect was confirmed by the bounded source audit.

| Finding | Disposition and remaining boundary |
| --- | --- |
| F1: unsupported local edits block independent incoming work | Corrected. Held events and recurrence dependencies retain native contents/evidence while independent events and page ingestion continue. |
| F2: completed history exhausts active capacity | Corrected accounting. Completed proofs and restored deletion barriers stay outside the active-work budget; unresolved obligations remain bounded. Historical disk growth remains; no compaction or retention policy is qualified. |
| F3: later captured pages miss outgoing traversal | Corrected. New durable owners rearm discovery without granting held/UNKNOWN owners another send or inspection attempt. |
| A1/A2: duplicated mechanics and concentrated responsibilities | Substantially reduced through existing transaction, admission, journal, codec, archive and acknowledgement boundaries. Typed profiles, persistent formats and transaction ownership remain explicit. Further refactoring needs a concrete maintenance benefit, not a file-length target. |
| A3: cross-path and lifetime coverage | Focused regressions and a bounded physical selection are maintained. Controlled volume/interruption checks pass for 4,097 imported records and 67 outgoing owners. Isolated socket loss, production-worker radio loss, worker death at body start and isolated death before response persistence, after proof retention and after native ACK pass. Endurance, partial upload, network response loss, later worker death, reboot and power-loss behavior remain separate qualification work. |
| A4: build assurance and packaging | CI configuration, version locks, strict checksums, dependency/advisory tooling and the patched build chain are present. Remote CI execution, release signing, signed update/session preservation and complete distribution notices remain release gates. |
| A5: documentation drift | README, architecture and permissions now describe the implemented restricted write profiles and Android integration. The compact status remains the capability entry point. |
| R1: concurrent remote write race | Open. Fresh GET followed by PUT/DELETE is not atomic. Require a supported server precondition or an explicit accepted-risk product decision; experimental options remain default-off with the warning. |

## Next qualification work

Extend the controlled volume/interruption baseline beyond the measured worker
radio-loss, worker body-start process-death and isolated response/proof/ACK persistence
boundaries to partial upload/server-accepted network response loss, later worker death, large
calendars, periodic scheduling and battery use.
Qualify reboot/power loss and the intended OEM separately. Android 17 and 16 KiB
runtime tests still require compatible hardware; static APK alignment is separate
evidence. Do not extend event write profiles merely to close an audit item.

Preserve read-only defaults, separate write consent, current account/generation
admission, immutable request identities, inspect-only UNKNOWN outcomes, exact
native/SQL acknowledgement and unsupported-data retention throughout.

Current behavior and remaining product work are in [status](STATUS.md), measured
scope is in [validation](VALIDATION.md), and distribution/concurrency decisions
are in [release gates](BUILD_SECURITY.md#remaining-release-gates).

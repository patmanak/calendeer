# Android 17 and 16 KiB qualification

This is a repeatable qualification plan with bounded evidence, not a declaration
of complete runtime support.
The build targets/compiles API 37 with minimum API 31. Existing evidence is in
[validation](VALIDATION.md#sdk-37-migration); the platform review is in
[build security](BUILD_SECURITY.md#android-api-37-baseline).

## Available checks without a compatible device

Use the strict build and host checks in [build security](BUILD_SECURITY.md#routine-checks-and-dependency-updates).
Use Python 3.11+; for freshly built debug and unsigned release APKs, run:

```powershell
python -m unittest discover -s tools -p 'test_*.py'
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

The read-only checker reports each APK's SHA-256 and checked ARM64/x86_64
libraries. It requires uncompressed, 16 KiB ZIP-aligned libraries, consistent
ELF LOAD alignment and RELRO protection separated from other writable data.
It requires LOAD/RELRO segments and rejects empty/duplicate native inventories,
unsupported ABIs and malformed
layouts; it does not repair binaries or change loader/packaging policy. Known
32-bit ABIs are outside this check. A non-page-aligned RELRO end is valid when
its containing LOAD has no other writable data in the rounded protection range.
See Android's [16 KiB and RELRO guidance](https://developer.android.com/guide/practices/page-sizes#check-relro).

Exit 0 means only these static checks pass. Runtime fields remain `NOT_RUN`;
this tool does not validate signatures, the SDK/manifest, actual native execution,
all ELF/linker rules, page-size assumptions or a distribution AAB. CI checks both
APKs after building them. For packaging/SDK/security changes, also inspect the
compiled manifest and resources with the SDK's `aapt2`, and independently run
`zipalign -c -P 16 4` on each APK. Retain minimum API 31, target/compile 37, all
eight app languages, backup/cleartext exclusions and exported-component gates.

## Required device profiles and preflight

| Profile | Required observations | What it can establish |
| --- | --- | --- |
| Android 17 / 4 KiB | API 37, actual page size 4096, supported ABI | Android 17 behavior on the 4 KiB configuration only. |
| Android 17 / 16 KiB | API 37, actual page size 16384, supported 64-bit ABI | Combined Android 17 and native 16 KiB behavior. |
| Earlier Android / 16 KiB | API 35 or 36, actual page size 16384, supported 64-bit ABI | Additional 16 KiB regression evidence; cannot replace Android 17 coverage. |

To claim both Android 17 page-size configurations, cover both API 37 rows.
Results for one ABI, device or OEM do not qualify another. A large-screen API 37
target is additionally needed for the large-screen row below. The currently
measured API 35 / ARM64 / 4096 target cannot satisfy any row in this table.

Before any device access, use the authorized target selection and coordination
policy; never select the first connected device implicitly. Read the API and ABI
properties and `getconf PAGE_SIZE` through that harness, with explicit target
selection. Record the app commit/APK hash, OS version, ABI, page size and WebView
version privately. A model name or a "16 KB supported" label is not a page-size
witness. Require native 16 KiB operation without app page-size backcompat; the
absence of its warning alone is insufficient. Verify the app's compatibility
setting as described in Android's [backcompat documentation](https://developer.android.com/guide/practices/page-sizes#16-kb-backcompat-mode).
Preflight never changes boot, global linker settings or memory limits. An unmet
prerequisite is `BLOCKED` or `NOT_RUN`, never `PASS`.

## Device test matrix

Unmeasured rows remain **NOT RUN** for each profile. Run applicable rows on both
API 37 profiles and all native/page-size rows on every claimed 16 KiB profile.
Use controlled fixtures and existing session restoration; execute real-service
rows only with the separately authorized test account and bounded fixture scope.

| ID / required target | Journey | Pass criteria and coverage boundary |
| --- | --- | --- |
| D01 / every profile | Install/update the same-signed debug app without clearing data; cold-start and recreate the activity. Run `AuthenticationStartupDeviceTest`. | Retained session/database opens, no JNI/loader/final-field failure, no unintended login or data reset. A debug update does not qualify signed release upgrades. |
| D02 / every 16 KiB profile and API 37 | Run native crypto/parser cases from the controlled regression list. Exercise Compose path rendering and isolated DataStore read/write as well. | Native calls actually execute without load/crash errors or backcompat. APK presence or crypto-only success does not prove graphics/DataStore execution; unobserved paths stay `NOT_RUN`. |
| D03 / API 37 | Read authenticated calendar metadata through production HTTPS, restore the session, and complete hosted verification when a challenge is available. | Normal TLS/origin validation succeeds; rejected origins/TLS errors remain rejected. Do not bypass CT/ECH, trust or WebView protections. A run with no challenge leaves hosted verification `NOT_RUN`. |
| D04 / API 37 | Run `AuthenticationUiDeviceTest`, then rotate/background/re-enter sensitive UI with synthetic inputs; check touch and physical keyboards. | Secrets clear at the existing boundaries, remain masked and capture-protected. No credential/account content enters screenshots or diagnostics. |
| D05 / API 37, including large screen | Run `PreferencesDeviceTest`; verify eight app languages, theme, recreation, split-screen/resizing and keyboard visibility in login/settings. | Controls remain reachable without clipped consent/error text; no secret state returns. Phone-only results do not qualify large screens or physical keyboard behavior. |
| D06 / every profile | Run the controlled Provider/SQL/consent/recovery suite. Revoke/regrant calendar access only within its bounded fixture scope. | No unauthorized projection/ACK, dirty/unsupported changes remain held, account/generation and independent consent gates hold. Use isolated calendars, never unrelated user rows. |
| D07 / API 37 and every 16 KiB profile | Exercise production workers online/offline, then fresh-process recovery of synthetic staged and uncertain work. | Immutable request identities and proofs survive; UNKNOWN stays inspect-only, with no second send. Forced worker invocation is not natural periodic scheduling evidence. After-claim interruption uses controlled ports; real transport interruption needs its own bounded plan. |
| D08 / API 37 | Observe natural periodic scheduling, idle/background transitions and restart/reboot recovery with retained synthetic work. | Bounded work eventually resumes under OS constraints without consent/identity changes or duplicate sends. Record timing and conditions; do not disable battery/scheduler protections to obtain a pass. Reboot requires an independently safe, authorized device window. |
| D09 / API 37 and every 16 KiB profile | Repeat three fixed cold-start/recovery cycles and exercise bounded capacity/large synthetic-calendar fixtures; observe memory/exit behavior. | No native crash, proof loss, unbounded retry or duplicate request. Record measured memory/capacity and distinguish process death, low-memory kill, reboot and power loss. Do not globally raise/disable memory limits. |
| D10 / intended distribution profile | Install/update the actual signed release package and repeat startup/native/retained-session smoke checks. | Correct signer/version and retained data/session; no compatibility mode. Current unsigned release APKs cannot satisfy this row; AAB delivery/split alignment also needs separate packaged-artifact checks. |

The maintained [controlled regression selection](../tools/device-regressions.txt)
is the starting point for D02/D06/D07, not a complete device qualification.
Add `AuthenticationUiDeviceTest` and `AuthenticationStartupDeviceTest` explicitly
for D01/D04; all class names use the app's test package. The regression selection
already includes `PreferencesDeviceTest`. Run with `providerFixture=true` through
the authorized harness. Check runner completion, failures, errors and both ignored
and assumption skips; an empty or partially skipped run cannot pass the row.
These tests use synthetic ports/data and do not establish D03/D08/D10.

## Measured subset

On an isolated pre-release API 37 / x86_64 / 16 KiB image, selected D01/D02/D06
fixtures pass: 102 native/SQL/handoff/startup cases, four debug native crypto
cases, 470 migration regressions, 24 UI cases and three capacity cases. These
counts cover different selections; they do not certify every matrix assertion.

D10 has partial measured evidence: a non-debuggable same-key release update and
actual reboot retain synthetic Provider/SQL/UNKNOWN state and a target-UID
AndroidKeyStore challenge. Thirteen startup/native/Provider/persistence cases
pass before and after. There is no authenticated release session on this fixture;
D10 as a whole remains open. This local reboot also does not establish D08
production-worker scheduling. API 37 / 4 KiB, ARM64, Samsung, large-screen,
physical keyboard and live API/WebView journeys remain unmeasured here.
See [validation](VALIDATION.md) for scope and remaining boundaries.

## Evidence and restoration

For each row record `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`, profile, commit/APK
hash, test selection, counts/skips, observed assertions and a sanitized evidence
reference. Keep timestamps, device witnesses and detailed logs private. Distinguish
controlled ports from live Proton, and static alignment from native execution.
Do not commit serials, account/event IDs, credentials, session data or heap dumps.

Stop on an identity, consent, preservation or uncertain-send violation. Retain
the original owner/proofs for inspection; never reset/resend it to repeat a test.
Restore temporary permissions, per-calendar modes, network and appearance changes
even after failure. Preserve the existing session; no routine sign-out/storage
reset, account security change, TLS/loader exception or new permission is part of
qualification. DELETE tests use only explicitly disposable synthetic events.

Platform references: Android 17 [target-specific changes](https://developer.android.com/about/versions/17/behavior-changes-17)
and [all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all).
See [release gates](BUILD_SECURITY.md#remaining-release-gates) for distribution
and the other work this matrix does not close.

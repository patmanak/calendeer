# Signed release preparation and preserved-state update

This procedure prepares local candidates. Publication is a separate authorized
action. Current native license evidence and authenticated signed-update
qualification remain blocked;
a successful build alone does not make a release ready.

## Private signing and versioning

Keep the new Calendeer key and its backup outside all project trees. Provision it
privately; do not reuse debug signing, store passwords in source/properties, pass
passwords on command lines or include a key in a source/archive artifact. Retain
the public signing-certificate SHA-256 separately so every candidate can be checked
against the intended identity. Losing the key prevents ordinary future updates.

Every Gradle release build is **unsigned**. Gradle accepts only the public
`CALENDEER_VERSION_CODE` / `CALENDEER_VERSION_NAME` pair. Do not configure an AGP
`SigningConfig`: AGP writes plaintext store/key passwords into signing-config
intermediates even when configuration caching is disabled.

Launch the Gradle child with all private signing fields removed from its environment
(the `sanitized_environment` function in `tools/sign_release.py` provides the exact
field list). Keep only the public version pair, then run:

```powershell
.\gradlew.bat --no-daemon --no-configuration-cache --dependency-verification strict :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Qualify that
artifact before signing. The separate offline `tools/sign_release.py` helper
accepts these process environment fields; they must never be passed to Gradle:

| Field | Requirement |
| --- | --- |
| `CALENDEER_SIGN_RELEASE` | Exactly `true`, explicit offline-signing opt-in. |
| `CALENDEER_KEYSTORE` | Existing private keystore outside the canonical project directory. |
| `CALENDEER_KEYSTORE_PASSWORD` | Private nonblank store password. |
| `CALENDEER_KEY_ALIAS` | Private nonblank key alias. |
| `CALENDEER_KEY_PASSWORD` | Private nonblank key password. |
| `CALENDEER_VERSION_CODE` | Explicit integer 1–2100000000; increase for every update. |
| `CALENDEER_VERSION_NAME` | Explicit safe version such as `0.1.0-rc1`. |

The version fields must be supplied together and match the actual compiled APK.
The helper rejects partial inputs, debug key aliases/debuggable APKs, a keystore
inside the project, existing signature blocks, output overwrites and mismatching
package/version/certificate. It does not build, generate keys or contact a device.
Pass the expected **public** release certificate SHA-256 as a required pin:

```powershell
python tools/sign_release.py app/build/outputs/apk/release/app-release-unsigned.apk build/distribution/0.1.0-rc1/Calendeer-0.1.0-rc1.apk --apksigner <sdk-apksigner> --aapt <sdk-aapt> --certificate-sha256 <public-sha256>
```

Only the offline `apksigner sign` child receives the two password environment
variables, through `--ks-pass env:CALENDEER_KEYSTORE_PASSWORD` and
`--key-pass env:CALENDEER_KEY_PASSWORD`. Password values never enter arguments,
reports or Gradle intermediates. AAPT and signature-verification children receive
the sanitized environment. Native output is captured; failure diagnostics with
private values are not printed. The helper verifies the final certificate,
package and version, then reports only public fingerprints/version fields. A failed
fresh output is removed; the unsigned input and key are retained. Keep private
fields out of build scans/logs and remove them from the operator environment after
signing. Preserve application ID, permissions, backup/TLS policy and consent gates.
The environment password syntax and separate sign/verify stages are documented in
[Android's apksigner reference](https://developer.android.com/tools/apksigner).

## Artifact and notice gates

1. Run strict build/security checks, OSV review and native alignment checks from
   [build security](BUILD_SECURITY.md) and [Android qualification](ANDROID_17_QUALIFICATION.md).
   Retain actual results and limitations; do not rewrite locks or verification
   metadata to bypass an unexpected mismatch.
2. Run `python tools/release_notices.py`. It must pass before distributing. To
   refresh candidate evidence from the cache after an intentional resolved change,
   use `python tools/release_notices.py --capture`; this overwrites review decisions.
   Complete the source/license/native review in [licensing](LICENSING.md).
3. Preserve all required dependency license/copyright/NOTICE texts in the APK's
   legal assets and the distribution. `python tools/release_notices.py --bundle-assets`
   deterministically updates `app/src/main/assets/legal/THIRD-PARTY-NOTICES.txt` from
   the reviewed evidence and retained unresolved material. A blocked audit stays
   blocked and is clearly marked in that asset. Rebuild and inspect the actual APK
   assets after completing the remaining two source/licensing questions in
   [licensing](LICENSING.md#concrete-distribution-blockers).
   The app excludes JSR305 only from `releaseRuntimeClasspath` and retains it as
   `compileOnly` for annotation processing/compiler visibility. After regenerating
   the actual affected locks and rebuilding the signed candidate, run:

   ```powershell
   python tools/check_release_annotations.py build/distribution/0.1.0-rc1/Calendeer-0.1.0-rc1.apk
   ```

   This requires the coordinate to be absent from the resolved release-runtime
   lock and checks class definitions in every APK DEX file. References to absent
   annotation types are distinguished from distributed definitions. It rejects
   the four problematic classes and any remaining `javax.annotation` definitions;
   malformed/unsupported DEX input fails closed. Run the release behavior suites
   separately before claiming annotation removal is compatible. No broad warning
   suppression, binary editing or removal from unrelated configurations is used.
4. Assemble a versioned local directory such as `build/distribution/0.1.0-rc1/`.
   Copy the verified APK as `Calendeer-0.1.0-rc1.apk`, public certificate fingerprint,
   notices/license texts, resolved dependency inventory, gate results and exact
   corresponding-source archive with build/install instructions. Record SHA-256
   for every deliverable and bind the APK to the source revision and build inputs.
   Inspect the actual archive contents for private/generated state before release.
   The source must cover adaptations and applicable native/dependency obligations;
   a repository URL alone is not a complete source-delivery procedure.

## Signed update qualification

An installed debug-signed app cannot be updated by a new Calendeer signing key.
The tool rejects that mismatch. Never uninstall or clear the existing app merely
to make this gate pass: that would destroy the very state being qualified.
First establish a separately authorized baseline already signed with the new key
on the enrolled test target. Retain a controlled authenticated session, selected
calendar, Provider rows and durable completed/held request history. Build a second
candidate signed by the same key with a greater version code.

The offline preflight performs no device access:

```powershell
python tools/release_update.py baseline.apk candidate.apk --apksigner <sdk-apksigner> --aapt <sdk-aapt> --certificate-sha256 <public-sha256>
```

It verifies APK signatures, the expected public signer, exact application ID,
non-debuggable flags and increasing version code. Offline success reports
`signedUpdate: NOT_RUN`.

For execution, enclose the **entire** command in the private enrolled-target
coordination harness required by local operator instructions. Resolve the target
inside the held lock, put it in the process-only `CALENDEER_TEST_SERIAL` environment
and add `--execute-update --adb <sdk-adb> --witness-command <private-witness-program>`.
Never log that environment. The private witness program inherits the selected
target, waits for session restoration, and returns only this JSON shape:

```json
{"authenticated":true,"session":"<64 lowercase hex>","selections":"<64 lowercase hex>","providerRows":"<64 lowercase hex>","durableHistory":"<64 lowercase hex>"}
```

The four fingerprints must independently cover retained session identity, calendar
selection/consent, controlled Provider row identities and immutable durable request
history. Produce stable canonical witnesses without exporting secrets or event
contents; use private keyed hashing for sensitive low-entropy identifiers. The
program must observe release behavior (no added release probe/debuggable bypass),
perform no login/reset/send and emit no diagnostic output mixed with JSON.
This witness program is an operator prerequisite, not supplied runtime evidence.

Automation compares the installed base APK bytes with the qualified baseline,
runs only `adb install -r`, restarts the application process, then requires the
authenticated witness to remain exact. Native outputs are captured rather than
printed. A failure preserves current state for inspection; no uninstall, downgrade,
retry-as-fresh, data clear or remote mutation follows. The tool supports a single
base APK; split installation and key rotation need separate qualification.
After success, independently inspect session restoration and controlled events
in the actual system calendar UI and record the measured device/OS scope privately.

## Measured local-state update subset

An isolated non-debuggable release baseline and higher-version candidate pass
same-certificate preflight, in-place installation and an actual emulator reboot
on API 37 / x86_64 / 16 KiB. `ReleaseStateRetentionDeviceTest`, explicitly opted
in with a frozen fixture token, verifies exact synthetic Provider rows, SQL bytes,
UNKNOWN history and a target-UID AndroidKeyStore challenge in separate processes.
Thirteen startup/native/Provider/persistence cases pass before and after the
update/reboot. The fixture uses no HTTP request and holds no authenticated
Proton session: this evidence does not satisfy the authenticated witness required
by `--execute-update`. It also does not qualify a debug-to-release signer change,
OEM behavior or production reboot scheduling. See [validation](VALIDATION.md).

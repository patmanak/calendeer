# Build security and release gates

The build locks resolved versions in the root buildscript and four modules.
`gradle/verification-metadata.xml` checks artifact and metadata SHA-256 values;
the Gradle wrapper distribution also has its own pinned checksum. Normal builds
must use the checked-in files without update or verification-disable flags.
The initial checksum inventory was bootstrapped from the locally resolved public
repositories/cache. This is trust on first use, not independent upstream signature
verification or proof that the original download was trustworthy.

Fresh online resolution also reads parent/BOM metadata that an existing offline
cache may not expose. Reviewed SHA-256 pins cover the Guava parent POMs for
[33.2.1-jre](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/33.2.1-jre/guava-parent-33.2.1-jre.pom)
and [33.3.1-jre](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/33.3.1-jre/guava-parent-33.3.1-jre.pom),
JUnit BOM module metadata for
[5.10.2](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.10.2/junit-bom-5.10.2.module)
and [5.11.0-M2](https://repo.maven.apache.org/maven2/org/junit/junit-bom/5.11.0-M2/junit-bom-5.11.0-M2.module),
and the [coroutines 1.8.0 BOM POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/1.8.0/kotlinx-coroutines-bom-1.8.0.pom).
Their coordinates and exact cached bytes were checked against both Maven Central
endpoints and published checksums: SHA-512 for JUnit/coroutines and SHA-1 for the
Guava parents. The recorded pins use SHA-256 throughout. These additions retain
all existing pins, versions and strict-verification rules; the upstream trust
limit above still applies.

## Routine checks and dependency updates

Use JDK 17 and the Android SDK specified by the build. The checked-in Windows CI
workflow runs the host suites, lint, debug app/test and unsigned release builds,
workflow validation, tooling tests and an offline dependency inventory. Actions
are pinned by commit, permissions are read-only and no publication is configured.
Device tests require an authorized, explicitly selected physical or isolated emulator target;
`tools/device-regressions.txt` defines the bounded controlled-fixture selection.

```powershell
.\gradlew.bat --dependency-verification strict :sync:test :auth:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
python -m pip install --only-binary=:all: -r tools/requirements.txt
python tools/validate_workflow.py
python -m unittest discover -s tools -p 'test_*.py'
python tools/dependency_audit.py
python tools/dependency_audit.py --osv
```

The optional OSV check sends only public Maven names and resolved versions. Its
JSON report distinguishes `NOT_RUN`, `NO_MATCHES`, `FINDINGS` and `ERROR`; findings
exit 1 and incomplete/unavailable lookups exit 2. It includes root plugin execution
dependencies, runtime, tests and resolved Android tooling configurations, retaining
their scopes and separate versions. It is an advisory lookup, not exploitability
analysis, a complete SBOM/license inventory or a vulnerability-free certificate.
The Gradle distribution, Python packages, SDK and CI runner are outside this Maven
inventory. CI's offline inventory does not query the advisory service.

For an intentional update, review release notes/advisories and compatible fixed
versions first. Targeted security overrides are centralized in
`gradle/security-versions.properties`; the root buildscript and project resolution
stages both consume it. Preserve the Proton Core release-train/native constraints.
Regenerate locks and checksum metadata using the same full task list above with
`--write-locks --write-verification-metadata sha256`, then inspect the actual
coordinate/checksum diff and provenance. Never accept an unexpected checksum
failure by blindly regenerating this file. Rerun the normal strict build and OSV
review before committing. Requalify native cryptography and affected Android
paths when runtime dependencies change. The current inventory is qualified on
Windows; another host may need reviewed host-specific artifacts, not disabled
verification.

References: [Gradle locking](https://docs.gradle.org/current/userguide/dependency_locking.html),
[Gradle verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
and [OSV API](https://google.github.io/osv.dev/api/).

## Patched Kotlin toolchain

The build uses Kotlin/KGP and the Compose compiler plugin 2.4.21, with KSP 2.3.12.
AGP 9.2.1, Gradle 9.4.1, Java bytecode target 17, Room 2.8.4 and the Proton
Core/native crypto versions stay unchanged. The explicit root buildscript
classpath upgrades AGP's built-in Kotlin/KSP baseline; the JVM and Compose plugin
declarations use the same Kotlin version. The obsolete
`android.disallowKotlinSourceSets=false` workaround is removed. No compiler
metadata-version bypass or new processor-language opt-in is used.

Kotlin 2.4.21 contains the fix for
[GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp).
The earlier plugin-activation guard is removed after upgrading the underlying
component; KAPT remains unconfigured and Room continues using KSP. An activation
guard alone was not a substitute for the patch: KSP also reuses KAPT cache
infrastructure. Verify the actually selected classpath and all resolved locks,
not just a plugin version declaration. The OSV tool retains its normal matching
and failure behavior, with no advisory suppression or exception.

Compatibility sources: [Kotlin's Gradle/AGP table](https://kotlinlang.org/docs/gradle-configure-project.html),
[AGP's supported classpath upgrade](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin),
[KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12) and its
[versioned cache integration](https://github.com/google/ksp/blob/2.3.12/gradle-plugin/src/main/kotlin/com/google/devtools/ksp/gradle/KspSubplugin.kt).
The KSP release/source supports the chosen candidate; actual compilation,
generated-schema checks and Android tests provide the project-specific
qualification in [validation](VALIDATION.md). Do not restore untrusted build caches;
CI uses fresh runners without configured build-cache restoration.

## Instrumentation compatibility and fresh metadata

The UI test dependency is explicitly Espresso 3.7.0, including its matching idling
resource. Espresso 3.5.0 failed on the measured API-37 image because it reflects
`InputManager.getInstance`; the stable replacement uses `getSystemService`.
See [AndroidX Test 3.7.0 release notes](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0).
All 24 selected authentication/preferences/dashboard UI cases pass after this
change. This affects instrumentation only; application runtime coordinates do not
change. Three obsolete test-only transitives are removed by the resolved graph.

The two AARs and two POMs match the official Google Maven bytes. Fresh online
resolution also consumed the existing Core dependency graph metadata for
[android-golib 2.9.0-2 POM](https://repo.maven.apache.org/maven2/me/proton/crypto/android-golib/2.9.0-2/android-golib-2.9.0-2.pom),
[coroutines 1.8.0 module](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.8.0/kotlinx-coroutines-core-1.8.0.module)
and [Room 2.7.2 module](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.7.2/room-runtime-2.7.2.module).
These cached files were checked byte for byte against their official repositories
before adding their SHA-256 pins. Existing pins are retained. Consumed metadata
is not evidence that those earlier versions ship: the resolved application still
uses its existing Core/native/Room constraints. Native runtime smoke tests compile
against the same Core release through test-only compile dependencies, without
packaging another Core copy in the instrumentation APK.

## Native advisory review boundary

The Maven advisory inventory does not cover the Go modules embedded in
`android-golib`. The recovered native build information in the
[dependency inventory](release-dependencies.json) records 25 exact Go module
versions and Go 1.23.12 across four ABIs. Official OSV/Go queries find advisory
version matches in several modules and the standard library; compiler/toolchain
records are kept separate from runtime package reports. Examples include
[edwards25519](https://pkg.go.dev/vuln/GO-2026-4503) and
[protobuf](https://pkg.go.dev/vuln/GO-2024-2611).

These are version matches, not demonstrated Android reachability or exploits.
Neither successful native round trips nor a clean Maven query clears them. A
public RC requires a recorded applicability/reachability assessment or a qualified
fixed native build, alongside the corresponding-source/license work. The official
[Maven native metadata](https://repo.maven.apache.org/maven2/me/proton/crypto/android-golib/maven-metadata.xml)
still lists the pinned 2.10.0-2 as latest at this review; no newer patched aggregate
was identified. No advisory is suppressed and no cryptography is rewritten to
make this gate appear green.

## Android API 37 baseline

Both Android modules compile against API 37; the app targets 37 and retains
minimum API 31. CI installs the stable `platforms;android-37.0` package, not a
preview or a later minor platform. AGP 9.2.1 supports API 37.0 with Gradle 9.4.1
and Build Tools 36.0.0; the Kotlin/KSP and Proton Core/native versions remain
unchanged. See the [AGP compatibility table](https://developer.android.com/build/releases/agp-9-2-0-release-notes#compatibility)
and [SDK setup](https://developer.android.com/about/versions/17/setup-sdk).

Review of [target-specific changes](https://developer.android.com/about/versions/17/behavior-changes-17)
and [all-app changes](https://developer.android.com/about/versions/17/behavior-changes-all)
found no required application-code workaround:

| Area | Reviewed behavior and remaining boundary |
| --- | --- |
| Native loading | The resolved `android-golib` uses `go.Seq` and `System.loadLibrary("gojni")`. No application extraction/`System.load` path or writable-code exception is added. Native execution passes on an isolated API 37 / x86_64 / 16 KiB image, including the signed release after update/reboot; other profiles remain unmeasured. |
| TLS and authentication | Keep platform CT/ECH defaults, HTTPS validation, WebView SSL cancellation and origin checks. No trust override, cleartext allowance or new permission is added. Real Android 17 API/WebView transport remains to be measured. |
| Sensitive UI | Authentication and hosted verification already use `FLAG_SECURE`; no deprecated content-capture opt-out replaces it. Test Android 17 recreation, keyboard and large-screen behavior before claiming qualification. |
| Background work and Provider | Workers remain bounded, with existing request/consent/recovery gates. The documented CP2 changes concern Contacts rather than Calendar Provider. Android 17 scheduling, permissions and recovery still need device coverage. |
| Other platform changes | No application use of MessageQueue reflection, final-field mutation, LAN discovery, SMS, RemoteViews, audio or Bluetooth was found. Memory-limit behavior remains unmeasured. |

Build, host, enrolled API 35 and bounded API 37 emulator evidence is in
[validation](VALIDATION.md). It does not establish complete Android 17 runtime
compatibility. Do not weaken platform
security or synchronization guarantees to make a future qualification pass.
The [qualification plan](ANDROID_17_QUALIFICATION.md) defines the repeatable
native APK gate, required device profiles, test matrix and pass criteria.

## Remaining release gates

| Gate | Required evidence or decision |
| --- | --- |
| Concurrent remote edits | A supported atomic server precondition or an explicit accepted-risk product decision. GET then PUT/DELETE leaves a race; the experimental options display this warning and remain default-off. See [conditional writes](research/CONDITIONAL_WRITES.md). |
| Distribution | Original contributions use GPL-3.0-or-later, while imported Proton terms constrain the combined application to GPLv3. Private offline signing is prepared; preserve complete notices and qualify actual signed updates. Native corresponding-source/license and advisory applicability gates remain open. See [licensing](LICENSING.md) and [distribution](RELEASE_DISTRIBUTION.md). |
| Android qualification | Extend the measured editor/device coverage to the intended OEM, real reboot/power loss, production-worker interruption during transport, periodic scheduling, battery and large calendars. Process restart and synthetic fixtures do not substitute for these journeys. |
| Supported write profile | Keep unsupported fields/variants held. Richer events, combined edits and kind/zone transitions need explicit profile contracts and qualification before enabling them. |

Do not create signing keys, choose the owner's license or promise a server
guarantee merely to close an audit item. Current measured scope and outstanding
work remain in [status](STATUS.md) and [validation](VALIDATION.md).

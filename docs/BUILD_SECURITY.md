# Build security and release gates

The build locks resolved versions in the root buildscript and four modules.
`gradle/verification-metadata.xml` checks artifact and metadata SHA-256 values;
the Gradle wrapper distribution also has its own pinned checksum. Normal builds
must use the checked-in files without update or verification-disable flags.
The initial checksum inventory was bootstrapped from the locally resolved public
repositories/cache. This is trust on first use, not independent upstream signature
verification or proof that the original download was trustworthy.

## Routine checks and dependency updates

Use JDK 17 and the Android SDK specified by the build. The checked-in Windows CI
workflow runs the host suites, lint, debug app/test and unsigned release builds,
workflow validation, tooling tests and an offline dependency inventory. Actions
are pinned by commit, permissions are read-only and no publication is configured.
Physical-device tests require the separately authorized enrolled test target;
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

## Remaining release gates

| Gate | Required evidence or decision |
| --- | --- |
| Concurrent remote edits | A supported atomic server precondition or an explicit accepted-risk product decision. GET then PUT/DELETE leaves a race; the experimental options display this warning and remain default-off. See [conditional writes](research/CONDITIONAL_WRITES.md). |
| Distribution | Select the overall license, preserve required resolved dependency notices, provision release signing privately and qualify signed installation/update without session or data loss. Current release APKs are unsigned development artifacts. See [licensing](LICENSING.md). |
| Android qualification | Extend the measured editor/device coverage to the intended OEM, real reboot/power loss, production-worker interruption during transport, periodic scheduling, battery and large calendars. Process restart and synthetic fixtures do not substitute for these journeys. |
| Supported write profile | Keep unsupported fields/variants held. Richer events, combined edits and kind/zone transitions need explicit profile contracts and qualification before enabling them. |

Do not create signing keys, choose the owner's license or promise a server
guarantee merely to close an audit item. Current measured scope and outstanding
work remain in [status](STATUS.md) and [validation](VALIDATION.md).

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

## Retained Kotlin tooling advisory

OSV still reports Kotlin Gradle plugin 2.2.10 under
[GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp).
The referenced [upstream fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6)
restricts deserialization of KAPT's incremental Java/annotation-processing caches.
This build uses KSP for Room, with no KAPT plugin or annotation-processing Gradle
dependency. Both KAPT plugin identifiers are explicitly rejected in all current
projects, including the root. This guard does not cover future included builds.

This is a reviewed applicability boundary, not a Kotlin patch or proof of general
non-exploitability. The advisory is neither suppressed nor removed from reports;
`--osv` continues to exit 1. A compatible Kotlin/Compose/KSP/AGP toolchain containing
the upstream fix must be qualified before removing the guard or introducing KAPT.
Do not restore untrusted build caches. The CI uses fresh runners with no configured
build-cache restoration; no advisory exception turns the scan green.

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

# Source-built Proton cryptography

Calendeer builds its Android Go/JNI aggregate from the locked Proton and mobile
generator sources in `go.mod`, `go.sum` and `dependencies.go`. The result replaces
the upstream Maven JNI dependency through a group-exclusive local repository.
See [source provenance and adaptation terms](PROVENANCE.md),
[licensing](../../../docs/LICENSING.md) and
[F-Droid preparation](../../../docs/FDROID.md).

This is a selected aggregate, not a byte-for-byte reconstruction of Proton's
larger AAR. It exposes the required crypto, armor, constants, models, subtle,
helper and SRP bindings. The unused key-transparency bindings are omitted;
source/API/module checks do not establish all runtime compatibility. Preserve
the project's Calendeer crypto, authentication and calendar qualification gates.

## Linux reference build

Use Linux with Go **1.27.1**, JDK **21** (the admitted reference uses Temurin
**21.0.13+9**), Android NDK r27c
**27.2.12479018**, SDK platform **android-37.0** and Build Tools **36.0.0**.
The native binding targets Android API 31, matching the application's minimum.
The builder checks Linux, exact Go and NDK versions, JDK 21 and SDK presence.
It requires Python 3, a C build toolchain, `realpath`, `sha256sum`, and network
access for checksum-verified Go modules and the pinned API-reference AAR.

From the repository root, with the toolchain directories already installed:

```sh
export GOROOT=/path/to/go1.27.1
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash proton-auth/native/golib/build.sh --go-home "$GOROOT" \
  --java-home "$JAVA_HOME" --android-sdk "$ANDROID_HOME" \
  --android-ndk "$ANDROID_NDK_HOME" \
  --work "$PWD/proton-auth/native/build/linux-work" \
  --repository "$PWD/proton-auth/native/build/maven"
bash gradlew --no-daemon --dependency-verification strict :app:assembleDebug :app:assembleRelease
```

Do not run Gradle before the local native build exists. The root build verifies
the approved AAR/POM hashes and embedded source-descriptor provenance. Missing,
modified or stale outputs must fail; the exclusive repository has no fallback
to the published upstream JNI artifact. Source changes require rebuilding and
reviewing the candidate, not disabling verification or automatically blessing
new checksums. Keep Gradle verification metadata and dependency locks intact.

The output coordinate is
`com.patmanak.calendeer.crypto:android-golib:2.10.0-2-go1.27.1-16k-linux1`.
The local repository contains its `.aar` and canonical LF `.pom` under
`com/patmanak/calendeer/crypto/android-golib/2.10.0-2-go1.27.1-16k-linux1/`.
Work directories contain downloads, module/build caches, raw AAR, extracted
libraries and logs. These are generated artifacts and must remain uncommitted.

`build.ps1` is retained with its adaptation provenance; its Windows-native
outputs are not the Linux reference and are not assumed to match the approved
Linux artifact. Use a Linux environment, including an existing WSL installation,
for the reference build and pass Linux toolchain paths to `build.sh`.

## Guards and independent reconstruction

The official upstream AAR is downloaded only as a SHA-256-pinned public Java API
reference. Packaging compares the supported Java API and takes JNI libraries
from the new source build. It checks Go runtime/module identity, the selected
Proton versions, absence of linked key-transparency code, all four Android ABIs,
ELF machine and 16 KiB load/RELRO layout. It retains linked-module and Go license
files and embeds `META-INF/calendeer-native-provenance.json` with source hashes,
API identity, modules and native hashes. ZIP layers are normalized and the POM
uses LF. These checks do not alone establish license completeness or F-Droid
acceptance; preserve the release inventory and notices review.

For reproducibility, build the same reviewed source in two clean Linux
checkouts, with distinct `--work` and `--repository` directories. Each work path
isolates Go module, build and GOPATH caches. Compare both AAR and POM bytes and
review the provenance and logs. Use clean checkouts with the recorded source
revision and toolchain; do not compare two copies of one output. Native equality
does not establish independently rebuilt APK equality or signing continuity.
Signing remains a separate, pending distribution decision.

Run the small, device-free guards directly because their filenames contain
hyphens and do not match the project's `test_*.py` discovery pattern:

```sh
python3 proton-auth/native/golib/test-build.py
python3 proton-auth/native/golib/test-package-aar.py
```

The preflight tests require Linux and use controlled tool stubs. The packager
tests use ELF/ZIP fixtures. Neither suite compiles Proton or qualifies an APK.

## Continuous integration

The [verification workflow](../../../.github/workflows/verify.yml) is configured
for Ubuntu 24.04 and Temurin 21.0.13+9. It downloads the official Linux amd64 Go 1.27.1
archive and checks SHA-256
`63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445`, as listed in
[Go's official download metadata](https://go.dev/dl/?mode=json&include=all).
It installs the selected SDK/NDK, runs the native guards and source build, then
runs the existing host suites, Android lint/debug/test/unsigned-release builds,
workflow/tooling tests, APK alignment checks and dependency inventory.

This describes the configured workflow, not a successful remote CI execution.
No signing, publishing, device access or artifact commit is configured.

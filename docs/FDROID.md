# F-Droid distribution preparation

Calendeer is not included in F-Droid. The [draft recipe](../fdroid/metadata/com.patmanak.proton.andcalendarbridge.yml)
is disabled at application and build level, with update checks and automatic
builds off. It describes the existing development baseline, code `1` and name
`0.1.0-dev`; it does not create a release or a tag. The selected native source
build and bounded application checks are qualified below. Full build-server
validation, matching signed-release comparison, current security/runtime release
gates and the signing choice remain open.

## Listing and versions

The [Fastlane listing](../fastlane/README.md) contains English and French text and
Calendeer's existing deer artwork. No screenshots are supplied. The product is a
bridge to Android calendar applications: read-only by default, with separate
experimental write options and no internal event editor or calendar browser.
Keep the listing aligned with [current capabilities](STATUS.md). The recipe
uses `GPL-3.0-only` for the combined APK; original project contributions retain
their separate [licensing terms](LICENSING.md).

The proposed `TetheredNet` label describes the Proton service dependency. Its
classification and inclusion remain subject to F-Droid review.

`gradle/version.properties` is the central version source. Keep listing changelog
filenames aligned with Android versionCode. The draft refuses public
`CALENDEER_VERSION_CODE` or `CALENDEER_VERSION_NAME` environment overrides before
either Gradle phase so the checked-in version is used. Version `0.1.0-dev` is preparation
only. Before a release, choose its real name/code, review the resulting manifest
and APK, and select the exact source commit containing its inputs and listing.
The draft pins development source commit
`ee6b19c1494664eae21b9f0e63ab2fed53d2bfad`; keep it disabled. A later release
version needs its own reviewed immutable inputs. No stable-tag pattern or
auto-update policy is asserted yet.

## Linux build candidate

The draft uses F-Droid's Gradle build method from the repository root, with an
explicit APK output. F-Droid cleans before scanning and invokes its own Gradle
launcher after the native build; it removes the checked-in wrapper scripts during
scanning, so the native build script must not invoke them. The recipe builds Go
`1.27.1` from the `go` srclib, requires bootstrap Go `1.24.6` or later, JDK 21,
NDK r27c (`27.2.12479018`), SDK `platforms;android-37.0` and Build Tools `36.0.0`.
The distro bootstrap package may be too old: the explicit guard must fail in
that case. Qualify a suitable F-Droid image rather than removing the guard.

Native compilation runs in `build`, after scanning. The versioned
[native builder](../proton-auth/native/golib/README.md) populates
`proton-auth/native/build/maven` from selected Proton sources. The project-owned
coordinate is `com.patmanak.calendeer.crypto:android-golib:2.10.0-2-go1.27.1-16k-linux1`.
The Gradle build then produces
`app/build/outputs/apk/release/app-release-unsigned.apk`. A coordinate or matching
Java API alone does not qualify native behavior or provenance. Keep the source,
module, license, checksum, API and four-ABI/16 KiB ELF checks; never substitute
the precompiled upstream JNI libraries when the local source build is missing.

Use Linux as the native release reference. Two independent clean builds must use
distinct source/work/cache paths and compare the resulting AAR and canonical LF
POM, with their provenance and module/ELF checks. Do not adopt Windows-generated
checksums as Linux expectations or regenerate verification metadata merely to
make a build pass. Native identity and whole-APK reproducibility are separate
gates. Device crypto/calendar qualification must use the actual reconstructed
Calendeer native library.

The official source scanner flags the group-exclusive local Maven declaration
as an unknown repository. The draft limits `scanignore` to `settings.gradle.kts`
and checks its exact canonical LF SHA-256 during prebuild. That reviewed file
declares only the group-exclusive generated repository and standard public
repositories. The source-built artifact is generated after scanning, with its
own source and checksum guards. No dependency declaration, bundled binary or
source directory is exempted. Maintainers still need to review this proposed
exception; a local scanner pass is not acceptance by F-Droid.

For metadata and scanner qualification, use a separate disposable
[fdroiddata checkout](https://gitlab.com/fdroid/fdroiddata) and the official
[build environment](https://f-droid.org/docs/Build_Server_Setup/). Copy the draft
to its `metadata/` directory. In that copy only, select the immutable candidate
commit and remove the application/build disable fields so checks do not skip it:

```sh
fdroid readmeta
fdroid lint com.patmanak.proton.andcalendarbridge
fdroid build --server --verbose com.patmanak.proton.andcalendarbridge:1
```

Keep the tracked recipe disabled until all release gates pass. YAML parsing or
text limits are structural checks; they do not establish a successful scanner,
build-server, native build, install or independent APK comparison. The
[metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/) defines
the recipe fields and build phases.

## Measured local checks

| Check | Result and boundary |
| --- | --- |
| Native source build | Two independent Linux compilations produce identical admitted AAR/POM bytes; per-class API, module hashes, four-ABI ELF and source/license guards pass. |
| Host/application | Strict Windows build, lint and 1,622 host tests pass. The unsigned Linux APK also builds with strict verification after reviewing its Linux AAPT2 artifact. |
| Device crypto | 24 synthetic crypto cases and one read-only retained-session catalogue case pass on API 35 / arm64 / 4 KiB. Fresh SRP login, other profiles and full synchronization journeys are separate. |
| Metadata/scanner | fdroidserver 2.4.5 `readmeta`, lint and source scanner pass on an isolated activated copy of the recipe pinned to the source commit. The tracked recipe stays disabled; the proposed scanner exception still needs maintainer acceptance. |
| Reproducibility | Two independent clean Git Linux builds of the pinned source produce byte-identical unsigned APKs, with Git metadata preserved. Compilation caches and application outputs are not reused between them. |
| Release/distribution | Build-server, matching signed-release verification and current native advisory applicability remain open. Signing is deferred; no submission or publication has occurred. |

See [validation](VALIDATION.md) for the measured source-native scope. A scanner
exception and metadata classification still need maintainer acceptance.

The measured unsigned APK SHA-256 is
`f76b66b6d00d1fc68a2e6d11c34cb2e35c9abf5e9602c89d56120dc9c8dd0a1a`
for development source commit `ee6b19c1494664eae21b9f0e63ab2fed53d2bfad`.
Both checkouts remain clean after the builds. The SDK/toolchain and public Maven
downloads are reused; source paths, compilation/transform caches, outputs and
the independently built native inputs are separate. This does not establish a
matching published signed APK, a future release version or an F-Droid server
build. Ensure the pinned source is available publicly before external builds.

## Signing is pending

The signing choice is deferred. The draft contains neither `Binaries` nor
`AllowedAPKSigningKeys`; no certificate has been selected or pinned for F-Droid.
Do not interpret their absence in a disabled preparation recipe as a choice of
F-Droid signing. No private key is needed for source, listing or unsigned build
preparation.

If developer-signed reproducible distribution is later selected, publish a real
matching APK only under separate publication authority, verify its public
certificate, then add the actual versioned `Binaries` URL and explicit Calendeer
certificate pin. Compare that signed APK against an independent unsigned Linux
rebuild from a clean Git checkout of the reviewed commit. Verify the published
signature and the final signature-copy comparison; source/build logs and Git
revision metadata must be retained. Never realign or modify the signed APK.
Signing-tool padding may require a reviewed, pinned normalization tool applied
only to the unsigned rebuild. Two copies of one build and fake comparison tests
do not establish reproducibility. See [F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).

If F-Droid signing is selected instead, document its certificate and update
continuity implications before submission. The existing
[release preparation](RELEASE_DISTRIBUTION.md) remains a separate process; this
document does not authorize signing, publishing, a ticket or a merge request.

## Method provenance

The disabled recipe's Go bootstrap and native-build structure were adapted from
Patmanak's GPL-3.0-or-later Contako preparation at commit
`e1cc5a077a9cbc35ee7f9c57d0c93a0f9f43cf39`, paths
`fdroid/metadata/com.patmanak.contako.yml` and `docs/FDROID.md`. This adaptation
retains the project [GPL license](../LICENSE). Contako's versions, certificate,
assets, donation details, native binaries and qualification results are not
Calendeer inputs or evidence.

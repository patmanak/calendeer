# Licensing and source provenance

The owner selected **GPL-3.0-or-later for original project contributions**, subject
to dependency compatibility. The grant is in [LICENSE](../LICENSE), with the full
GPLv3 text in [COPYING](../COPYING). This does not relicense imported code.
Imported Proton Calendar/WebClients adaptations are GPL-3.0-only: the combined
APK is constrained to GPL version 3. Do not label the complete APK GPL-3.0-or-later
or promise a later-version option for those upstream contributions.

Distribution remains blocked until resolved dependency notices and native-source
licensing are reviewed. See the [release procedure](RELEASE_DISTRIBUTION.md).

## Imported components

- Contako-derived authentication and appearance components retain their
  GPL-3.0-or-later notices. The license text is preserved in
  [Contako-GPL-3.0.txt](licenses/Contako-GPL-3.0.txt). Exact imported paths and
  adaptations are recorded alongside each module as `PROVENANCE.md`.
- The Gradle 9.4.1 wrapper scripts/JAR originate from the inspected Contako build;
  scripts retain Gradle headers. The wrapper JAR SHA-256 is
  `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`.
  Its distribution URL and checksum are pinned in
  `gradle/wrapper/gradle-wrapper.properties`. Preserve the
  [Gradle license](licenses/Gradle-LICENSE.txt) and [notice](licenses/Gradle-NOTICE.txt).
- Workflow source notices remain in [workflow provenance](WORKFLOW_SOURCES.md).
- The calendar metadata protocol adaptation and its fixtures carry GPL-3.0-only
  from Proton WebClients. The pinned sources and local changes are recorded in
  [adapter provenance](../proton-auth/PROVENANCE.md); the accompanying GPL version 3
  text does not grant a later-version option for this material.
- Calendar event/bootstrap/crypto integration adapts the pinned official Android
  Calendar source under GPL-3.0-only. Biweekly parses iCalendar using the same
  published version as that source. Its bundled notices are preserved under
  `proton-auth/licenses`; exact sources and dependencies remain in adapter provenance.
- The Android Proton authentication adapter records its Contako extraction,
  Proton Core and native crypto dependencies in
  [adapter provenance](../proton-auth/PROVENANCE.md). Its packaged-library licenses
  remain applicable; the existing GPL text does not replace a complete resolved
  dependency notice inventory before distribution.

Do not copy account state, signing keys, private configuration or generated
artifacts when reusing source. Capture exact revisions and local changes;
reused source still needs integration tests and upstream maintenance tracking.

Before distribution, generate a resolved dependency inventory from the actual
build and preserve required licenses/notices. Declared build versions alone are
not a complete transitive dependency or security assessment.

## Resolved release inventory

[release-dependencies.json](release-dependencies.json) covers all 218 coordinates
in the checked-in app `releaseRuntimeClasspath` lock, including metadata-only BOMs
and multiplatform variants conservatively. 217 have reviewed license evidence;
the remaining native coordinate/source limitation below still blocks distribution.
JSR305 is no longer assigned to the resolved app release runtime; its separate
compiler/tooling evidence is retained under `nonPackagedComponents` rather than
included in the APK notice bundle. Actual signed APK DEX-definition inspection
and targeted release runtime qualification pass; see [validation](VALIDATION.md).

Every POM's bytes match the cached SHA-256 and both public repository endpoints
(Maven Central canonical/mirror, or Google's canonical/redirect endpoint). This
resolves the earlier 186 POM verification gaps without changing Gradle verification
pins. Published versioned source archives were inspected for copyright/license
headers and required notices, alongside pinned AAR/JAR contents; source-root
licenses fill the few archives without embedded grants. This is source/license
evidence, not independent publisher identity verification. The three missing POM
declarations are resolved as Apache-2.0 by actual source grants/notices:
`com.google.guava:listenablefuture:1.0`, `io.swagger.core.v3:swagger-annotations:2.2.7`
and `javax.inject:javax.inject:1`. Their source URLs, archive hashes and preserved
headers are recorded per coordinate; no group-prefix license inference is used.

The preserved text set in [dependency-notices](dependency-notices) includes source
copyright headers, embedded third-party notices and complete applicable license
texts. In particular, Jackson's shaded FastDoubleParser/Schubfach code carries
MIT, Boost-1.0 and BSD-2-Clause notices beyond its Apache POM declaration. OkHttp's
Public Suffix data retains MPL-2.0; Chromium-derived WebKit boundary sources retain
BSD-3-Clause. The deterministic legal asset
`app/src/main/assets/legal/THIRD-PARTY-NOTICES.txt` preserves the notices with their
source origins, coordinate/license map and unresolved distribution gates. Regenerate
it with `python tools/release_notices.py --bundle-assets` after an evidence change.

## Concrete distribution blockers

- JSR305 packaging mitigation (qualified for the measured APK): `com.google.code.findbugs:jsr305:3.0.2`
  contains four published source files under
  `javax.annotation.concurrent` retain Brian Goetz's CC-BY-2.5 grant. The
  [author's current documentation](https://jcip.net/annotations/doc/net/jcip/annotations/package-summary.html)
  confirms that license. Other JSR305 source has a BSD grant; the aggregate POM's
  Apache declaration does not resolve these file-level terms; the jar is not
  silently classified as Apache. The packaging mitigation excludes this coordinate
  only from the app release runtime while retaining compiler visibility with
  `compileOnly`. Actual Gradle lock regeneration removed only its
  `releaseRuntimeClasspath` assignment, preserving versions and other configurations.
  All four
  problematic classes have `RetentionPolicy.CLASS`; inspected published dependency
  sources use JSR305 through annotation imports/documentation, without an executable
  reference found. Actual signed APK DEX-definition inspection
  (`tools/check_release_annotations.py`) confirms absence and targeted release
  runtime checks pass. This is a packaged-artifact mitigation, not a reclassification
  of the original source grant. The
  runtime notice inventory now follows the verified 218-coordinate resolved snapshot.
- `me.proton.crypto:android-golib:2.10.0-2`: all four pinned ELF libraries expose
  the same Go 1.23.12 build information and exact 25-module dependency/version/h1
  graph. All 25 public Go module archives recompute to those embedded h1 sums;
  24 have preserved compatible source grants. However,
  `github.com/ProtonMail/pm-key-transparency-go-client@v0.0.1` has no license/grant
  in its exact source archive or
  [official version tree](https://github.com/ProtonMail/pm-key-transparency-go-client/tree/v0.0.1).
  The publisher's AAR POM declares GPLv3 for the aggregate, but its application to
  this source needs confirmation; do not invent a separate MIT/GPL source grant.
  The generated `gobind` root is recorded as `(devel)`, without an exact build-source
  revision. The public gopenpgp build script at the module tag does not match all
  packages in this aggregate. Retain the recovered dependency graph and obtain
  the matching aggregate build/corresponding-source mapping before release.
  Additional primary-source inspection identified
  [Proton's gomobile build tool](https://github.com/ProtonMail/gomobile-build-tool),
  whose MIT license covers that tool rather than supplying the absent key-transparency
  grant. The public ProtonCore `gopenpgp/build-config.json` at commit
  `1332f316564156221bd177520f41202826f4ca21` identifies `2.9.0-1`, not the pinned
  `2.10.0-2` AAR. Neither source closes the exact aggregate-source question.

`python tools/release_notices.py` exits nonzero for missing/stale coordinates,
unreviewed compatibility, unresolved evidence, missing/changed notice files or
an incomplete native-source review. Regeneration with `--capture` deliberately
resets reviews. Complete each entry's `licenseExpression`, `reviewEvidence` and
`reviewed` only from versioned official source/artifact evidence; resolve its
`problems` and add any missing notices with SHA-256. Record native source evidence
and its notice list separately, including exact `sourceRevision`, `buildMapping`,
and individual `dependencies` with coordinate/license/review evidence. The checker
allows only explicitly supported compatible selected license identifiers; a new
expression needs a reviewed policy update. A passing schema check does not replace the
human determination of obligations or corresponding-source completeness.

Hash-bound notice files and the generated legal asset retain their exact bytes
through Git attributes. Lockfiles, verification metadata and the JSON inventory
use LF line endings; asset generation writes UTF-8 bytes without host newline
translation. This keeps reviewed hashes and asset generation reproducible across
Windows and other hosts. A license-check shell script accidentally matched by
filename discovery is excluded; actual grants and copyright notices are retained.

Each reviewed record also carries a canonical `reviewAttestation.sha256` over its
complete artifacts/POM/notices/source-review/license record. Removing a notice,
artifact or source term invalidates that attestation until the changed record is
explicitly reviewed and rebound. This guards accidental omissions; it is not a
publisher signature, legal opinion or automatic licensing decision. Existing
reviews were bound only after rechecking current pinned artifact bytes and all
preserved notice hashes against the prior source audit.

An empty artifact inventory is accepted only with an explicit reviewed
`metadataOnly` record. Five POM-only nodes have verified `packaging=pom` metadata;
19 multiplatform nodes have pinned Gradle module metadata whose applicable
Android/JVM runtime variants redirect to separately inventoried coordinates without
local runtime files. Capture without that evidence records a missing-artifact
problem. The native source inventory has a separate snapshot digest even while
unreviewed, so dropping a dependency or notice adds a gate. Completing its review
also requires an attestation over the full native inventory; it remains unreviewed.

GPLv3 permits combining compatible permissive code while preserving its notices;
the applicable version-3 obligations remain. Primary guidance:
[GNU's GPL compatibility FAQ](https://www.gnu.org/licenses/gpl-faq.html#AllCompatibility)
and [GPL version 3](https://www.gnu.org/licenses/gpl-3.0.html).

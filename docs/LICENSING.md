# Licensing and source provenance

The project's overall distribution license is not selected yet. Do not interpret
that as a change to imported code licenses or as publication readiness.
Preserve upstream notices and evaluate the combined distribution before release.

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

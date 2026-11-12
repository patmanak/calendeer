# Android store metadata

Application ID: `com.patmanak.calendeer`. The disabled F-Droid recipe has the
matching metadata filename; internal Kotlin namespaces are not store identities.

The English and French listings describe Calendeer's current bridge capabilities,
including the default read-only mode and experimental, limited write options.
They do not announce an F-Droid release. Changelog filenames are Android
versionCodes; `1.txt` describes the current `0.1.0-dev` development baseline.
Review that text with the eventual release version rather than presenting it as
a published release.

`en-US/images/icon.png` is an unchanged copy of Calendeer's own
`app/src/main/res/drawable-nodpi/calendeer_mascot.png`, the artwork used by the
application's launcher foreground. Keep its existing project licensing and
provenance; this listing does not introduce third-party artwork. Other locales
use the English fallback icon. No screenshots are supplied yet. Add only current
Calendeer screenshots with controlled fictional data after visual review.

This source-repository layout follows the
[F-Droid graphics and text conventions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).
Do not copy this directory unchanged into fdroiddata, whose localized text
filenames differ. See [distribution preparation](../docs/FDROID.md).

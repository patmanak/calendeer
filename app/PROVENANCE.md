# Application source provenance

## Contako adaptations

Source: [patmanak/contako](https://github.com/patmanak/contako), commit
`4a1d2e4f6626a2a1e1b194542bd175ac019013e0`.
The source files listed below match this commit; unrelated local changes were
excluded. Copyright (C) 2026 Patmanak. Original Contako code is
**GPL-3.0-or-later**. The adapted source files retain that notice.

The original license grant is preserved verbatim in
[`src/main/assets/legal/CONTAKO-LICENSE.txt`](src/main/assets/legal/CONTAKO-LICENSE.txt).
Paths mentioned inside that upstream grant refer to the Contako repository.
The complete GPL version 3 text is packaged alongside it as
[`GPL-3.0.txt`](src/main/assets/legal/GPL-3.0.txt).
No source grant or copyright notice is replaced by the source extraction.

Paths in the source column are relative to Contako. Destination paths are relative
to this `app` module. SHA-256 values below identify the exact inspected source bytes.

| Source | Destination | Local changes |
| --- | --- | --- |
| `app/src/main/java/com/patmanak/contako/ui/theme/ThemePreferenceStore.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/ui/theme/ThemePreferenceStore.kt` | New package and copyright header; application-scoped context; same StateFlow, preference persistence and system default; constants restricted to their owner. |
| `app/src/main/java/com/patmanak/contako/ui/theme/ContakoTheme.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/ui/theme/ThemePolicy.kt` | Extract only `ThemeMode` and `resolveDarkTheme`; omit Contako palette, typography, shapes, motion and semantic colors. Calendar's `BridgeTheme.kt` is new. |
| `app/src/main/java/com/patmanak/contako/ui/locale/LanguagePreferenceStore.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/ui/locale/LanguagePreferenceStore.kt` | New package and notices; add language endonyms; use application context; treat LocaleManager as authoritative on API 33+, refresh on activity entry and migrate a pre-33 choice once; copy Configuration before overriding locales on API 31–32. Keep the same eight languages, system default and private preference persistence. |
| `app/src/main/java/com/patmanak/contako/MainActivity.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/MainActivity.kt` | Extract only localized base context, theme/locale flow collection, system-bar appearance and pre-33 activity recreation. Replace the entire app composition with the configuration screen; omit authentication, contacts, accounts, permissions, network and scheduling. |
| `app/src/main/res/xml/backup_rules.xml` | `src/main/res/xml/backup_rules.xml` | Verbatim copy. |
| `app/src/main/res/xml/data_extraction_rules.xml` | `src/main/res/xml/data_extraction_rules.xml` | Verbatim copy, including cloud/D2D and device-protected storage exclusions. |
| `LICENSE` | `src/main/assets/legal/CONTAKO-LICENSE.txt` | Verbatim copy; upstream paths retain their original meaning. |
| `app/legal/licenses/GPL-3.0-only.txt` | `src/main/assets/legal/GPL-3.0.txt` | Verbatim complete GPL v3 text. The original source grant remains GPL-3.0-or-later. |

| Source filename (unique in the table above) | SHA-256 |
| --- | --- |
| `ThemePreferenceStore.kt` | `44f7e8bcb82ee1beb9da2c1f45807a86aad47ff527330fba403d7513fb4a6d95` |
| `ContakoTheme.kt` | `16f25c4b3894d4b1757fab15feaa21920a563f8b36c5d0ef3bc71fda191b1ba5` |
| `LanguagePreferenceStore.kt` | `b1144be9beba4163ffcd253ed1e6d468ce6f0dae04fa6c3f3e07dfa437c54de8` |
| `MainActivity.kt` | `b19c869b4e548ded058e8d6fde092ec7f3dabfc77de9702a32db970cae0a642c` |
| `backup_rules.xml` | `2c5c697984f4c52b526337ab61113f5843e0bfc6af4722374916ffe6da38dd9a` |
| `data_extraction_rules.xml` | `7a4e6defdcb7e21a8cdb4bb4cbdbbef15bcd4c6bae923372fbfa07f0642ac29a` |
| `LICENSE` | `d5b2d976e0d146055fecab38d3cb47810d4406c931a5900ba181fbbedb441d72` |
| `GPL-3.0-only.txt` | `0abbff814cd00e2b0b6d08395af2b419c1a92026c4b4adacbb65ccda45fa58cf` |

## New application material

The configuration layout, teal appearance, simple vector calendar symbol,
launcher resources, Calendar-specific text and translations are new material.
No Contako artwork, visual identity, contact code, source translation catalogs,
account data, stored preferences or generated output is imported.
The pure theme tests assert Calendar's accepted system/override behavior; the
Contako theme test file was inspected as a reference, not copied.

The original configuration slice has since gained an application-owned login
controller and a real network adapter. See [authentication UI provenance](AUTH_UI_PROVENANCE.md)
and [Proton adapter provenance](../proton-auth/PROVENANCE.md) for those additions.
`MainActivity` now hosts either the credential panel or an interactive verification
panel. Calendar permissions and synchronization remain unavailable. Theme/language
choices affect the bridge only. License assets document source reuse; they are
not a complete resolved dependency inventory for distribution.

## Updating reused components

Before changing these adaptations, compare the pinned files with newer upstream
revisions and review relevant fixes. Update this source record and retained notices
when adopting changes. Copying source does not provide automatic dependency updates.

Validate both locale paths (API 31–32 and 33+), the OS-upgrade handoff, external
Android app-language changes, activity/process recreation and all eight offline
catalogs. Unit tests cover theme policy only; they cannot establish Android
preference persistence, visual accessibility, OEM behavior or server compatibility.

# Contako reuse: login, languages and appearance

Evidence: local source inspection, 2026-09-26. No build, device execution or live
Proton authentication was performed for this assessment. This is the original
reuse assessment. Subsequent imports and their exact revisions are documented in
[auth provenance](../../auth/PROVENANCE.md) and [app provenance](../../app/PROVENANCE.md).

Source project: Contako, inspected HEAD
`33289e18e1303d1a1788f1e6d2154f0603738c54`.
Paths below are relative to that source repository, not the Calendar checkout.
The source tree has unrelated/uncommitted changes: the general application UI,
translation catalogs and some test/docs files are not a pristine commit snapshot.
Pin the chosen source when extracting code and record any local differences.

## Login flow and reusable components

The inspected flow supports username/password with Proton Core SRP, a second
factor code (TOTP/recovery), a separate mailbox password where needed, protected
session restoration and key unlock before readiness. A one-password login can
retain a session-bound mutable password buffer for five minutes to unlock after
2FA; loss/expiry requires re-entry. An incomplete 2FA session is cleaned up on
restart. Preserve these distinctions when adapting the state machine.

Human verification is a hosted challenge presented in a constrained WebView and
resumes the pending Core request. Supported result parsing is not evidence that
every server challenge type has been exercised. Typed failures, retry delays,
cancellation, duplicate submission guards and stale-result guards are reusable.

| Source path | Reuse scope |
| --- | --- |
| `app/src/main/java/com/patmanak/contako/domain/auth/AuthenticationFlow.kt` | Application-owned login steps, failures and one-use mutable secret boundary. |
| `app/src/main/java/com/patmanak/contako/ui/auth/` | Login ViewModel, screens, capture policy and human-verification presentation; adapt resources and visual identity. |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonAuthenticationFlowAdapter.kt` | UI/domain-to-gateway mapping; depends on `app/src/main/java/com/patmanak/contako/data/gateway/ProtonGateways.kt`. |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonGateCRuntime.kt` | Core login/key orchestration; extract authentication from the contact-specific composition. |
| `app/src/main/java/com/patmanak/contako/data/proton/PendingLoginPassword.kt` | Session-bound temporary secret lifetime and cleanup. |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonCoreSessionCoordinator.kt` | Session persistence/refresh/invalidation; inspect its database and protected-storage dependencies together. |
| `app/src/main/java/com/patmanak/contako/data/proton/GateCInteractiveHumanVerification.kt` | Pending challenge lifecycle; preserve the exact-origin/frame restrictions in `app/src/main/java/com/patmanak/contako/data/proton/OriginBoundVerificationBridge.kt`. |
| `app/src/main/java/com/patmanak/contako/data/proton/ProtonGateCNetwork.kt` | Core networking assembly; adapt application identity and configuration rather than reusing Contako's identity. |

The auth source and dependency manifests inspected have no local diff from the
recorded HEAD. `app/gradle/libs.versions.toml` declares Proton Core 36.8.0;
auth/account/network/user/key/challenge/human-verification/crypto dependencies
must be qualified together. This is a source version, not a selected Calendar
dependency. The custom UI also depends on Compose/Lifecycle/coroutines and the
verification presentation on Android WebView/WebKit; persistence uses Room and
protected storage. Do not copy the full Contako dependency graph by default.

Known extraction limits: Contako currently admits one account, uses `Product.Mail`
in Core composition and does not support security-key-only login, distinct device
verification, account recovery/reset or scope elevation. Calendar account count,
product/scopes and access/key bootstrap must be specified/qualified separately.
The challenge WebView requires supported messaging/document-start features and
fails closed otherwise; renderer recovery is not fully qualified. No inherited
claim of live Calendar compatibility is justified.

Reusable auth scenarios live in `app/src/test/java/com/patmanak/contako/ui/auth/`,
`app/src/test/java/com/patmanak/contako/data/proton/ProtonGateCAdaptersTest.kt`,
`app/src/test/java/com/patmanak/contako/data/proton/PendingLoginPasswordTest.kt`,
`app/src/test/java/com/patmanak/contako/data/proton/GateCInteractiveHumanVerificationTest.kt`
and `app/src/androidTest/java/com/patmanak/contako/data/proton/OriginBoundVerificationBridgeDeviceTest.kt`.
All remain NOT RUN in this inspection; copied tests must gain Calendar-specific
session/access cases. Mutable secret buffers are cleared, but this does not
establish zeroization of transient immutable strings in UI/Core dependencies.

## Language preferences

`app/src/main/java/com/patmanak/contako/ui/locale/LanguagePreferenceStore.kt`
defines `AppLanguage` and a preference boundary backed by SharedPreferences and
StateFlow. It offers system, en, fr, de, es, it, nl, pl and pt, defaulting to system.
The source uses application locales on Android 13+ and a localized context below
that version; the activity wiring handles recreation for older versions.

`app/src/main/res/xml/locales_config.xml` declares the same eight locales.
Resources live in `app/src/main/res/values/strings.xml` for English and
`values-fr`, `values-de`, `values-es`, `values-it`, `values-nl`, `values-pl` and
`values-pt`. `app/build.gradle.kts` disables bundle language splits to package
the advertised catalogs for offline switching. Reuse the behavior and relevant
strings; Calendar-specific text still needs translations and verification.

The preference store, locale config and build file inspected here have no local
diff from the recorded HEAD. Translation catalogs and general UI do have local
changes; their worktree content is not attributed wholly to that commit.

## Theme preferences

- `app/src/main/java/com/patmanak/contako/ui/theme/ThemePreferenceStore.kt`:
  preference boundary and persistent SYSTEM/LIGHT/DARK selection, default SYSTEM.
- `app/src/main/java/com/patmanak/contako/ui/theme/ContakoTheme.kt`:
  `ThemeMode`, `resolveDarkTheme`, Compose Material 3 integration and light/dark
  palettes. These files have no local diff from the recorded HEAD.
- `app/src/main/java/com/patmanak/contako/MainActivity.kt`:
  applies preferences to the UI and system bars.
- `app/src/main/java/com/patmanak/contako/ui/ContakoApp.kt`:
  language/theme selectors are embedded in a larger contact application and must
  be extracted, not copied wholesale. This file has local changes.

Reuse does not automatically select Contako branding, palette, package name or
the full contact UI for Calendar. Persist preferences in Calendar's own sandbox.

## Verification to carry forward

Source tests provide reusable scenarios, not execution evidence for Calendar:

- `app/src/test/java/com/patmanak/contako/ui/theme/ThemeFoundationTest.kt`:
  theme resolution, including explicit overrides of the system choice.
- `app/src/androidTest/java/com/patmanak/contako/ui/ThemeFoundationDeviceTest.kt`:
  preference persistence and UI scenarios.
- `app/src/androidTest/java/com/patmanak/contako/ui/LocaleSwitchingDeviceTest.kt`:
  language persistence and resource switching examples. These examples do not
  establish complete coverage of eight translated Calendar catalogs.

Verify both Android locale paths for the selected minimum version, restart,
offline switching, changes to system settings, and login/settings appearance.
Keep queued synchronization intact across activity recreation. Test packaged
catalogs and parameter/plural consistency in Calendar's eventual build.

## Provenance and extraction boundary

Contako's `docs/LICENSING.md` identifies its original code as GPL-3.0-or-later;
its root `LICENSE` and `app/legal/` contain the associated license and dependency
records. Preserve applicable notices and record the selected files/dependencies
when importing. This assessment does not select Calendar's distribution license.

Reuse source and behavior only: no stored sessions, account records, credentials,
signing files, build outputs or private instructions are part of the extraction.
Do not carry contact permissions or contact-sync startup into Calendar login.

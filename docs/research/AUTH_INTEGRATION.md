# Proton authentication integration

The Android adapter uses Proton Core 36.8.0 with its maintained SRP and crypto
implementations. The matching source JARs are published under
[Maven Central](https://repo.maven.apache.org/maven2/me/proton/core/).
Contako adaptations and exact source records live in
[proton-auth provenance](../../proton-auth/PROVENANCE.md).

The Calendar-specific composition follows public Android Calendar sources at
commit `83bbd6a15b0aaa135e424fb5ac195c0a52929b4f` (release/2.34.0):

- [ApplicationModule](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/di/ApplicationModule.kt)
  selects `Product.Calendar`. Use that product consistently for auth, user and
  local account repositories; it also selects the Calendar challenge identity.
- [Build configuration](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/build.gradle.kts)
  and [constants](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/common/Constants.kt)
  select the `calendar-api` prefix and `proton.me` host. The adapter uses
  `https://calendar-api.proton.me/`.
- Core's login response supplies session scopes; the adapter preserves them
  through second factor and refresh instead of inventing requested scopes.
- Calendar Bridge uses its own `Other_0.1.0+andcalendarbridge` client header and
  user agent, with an exact validator adapted from Contako. It does not copy the
  official app identity. Source inspection and local validation cannot establish
  that Proton accepts this client identity; that requires live qualification.

Authenticated identity is deliberately separate from Calendar readiness.
[BootstrapCalendarUseCase](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/BootstrapCalendarUseCase.kt)
loads calendar keys, passphrases and membership. The subsequent
[passphrase cache use case](https://github.com/ProtonMail/android-calendar/blob/83bbd6a15b0aaa135e424fb5ac195c0a52929b4f/app/src/main/java/me/proton/android/calendar/domain/usecase/CacheCalendarPassphraseUseCase.kt)
selects an address/member and verifies/decrypts the calendar passphrase. These
operations belong to the later calendar adapter, before enabling synchronization.

This login integration unlocks existing account keys. It does not import account
setup, recovery, password changes or automatic calendar/background worker startup.
Protocol changes remain inside the adapter module.

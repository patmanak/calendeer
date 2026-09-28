# Authentication UI source provenance

The authentication panel and capture policy adapt
[Contako](https://github.com/patmanak/contako), commit
`4a1d2e4f6626a2a1e1b194542bd175ac019013e0`.
Copyright (C) 2026 Patmanak; **GPL-3.0-or-later**.
The original grant and complete GPL text are retained in
[`CONTAKO-LICENSE.txt`](src/main/assets/legal/CONTAKO-LICENSE.txt) and
[`GPL-3.0.txt`](src/main/assets/legal/GPL-3.0.txt).

Source paths are relative to Contako. Destination paths are relative to this module.

| Source | Destination | Source SHA-256 |
| --- | --- | --- |
| `app/src/main/java/com/patmanak/contako/ui/auth/AuthenticationScreen.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/ui/auth/AuthenticationPanel.kt` | `a1e4edcaa38a507914225b376b68c25e2318925446e5a695737f405b2e584db4` |
| `app/src/main/java/com/patmanak/contako/ui/auth/AuthenticationCapturePolicy.kt` | `src/main/java/com/patmanak/proton/andcalendarbridge/ui/auth/AuthenticationWindowProtection.kt` | `cf1c736e8493431178af7dd2030715fde8b3fb8ac2b43e3d4b51004895a535ea` |

The source files were unchanged relative to that commit when inspected. The
adaptation replaces the fullscreen presentation and branding with an embeddable
panel, binds the project-owned authentication controller, and adds cleanup/retry
gates. Passwords and second-factor codes remain masked; no visibility toggle is
offered. Inputs are excluded from saved state and cleared on submission,
destination/purpose change, backgrounding and disposal. Temporary mutable arrays
are cleared after ownership transfer. Compose text editing still uses immutable
Strings; dropping those references is not a heap-zeroization guarantee.

The window policy preserves pre-existing secure-window flags, disables autofill
and Android content capture on the host Compose view while credentials are
visible, and restores the prior policy on disposal. The host must display the
credential and human-verification panels exclusively to avoid competing owners
of the same window policy.

`HumanVerificationPanel.kt`, the eight `auth_strings.xml` catalogs and
`AuthenticationUiDeviceTest.kt` are new Calendar material. The verification panel
delegates all URL, JavaScript, challenge and token policy to the Proton adapter;
it owns only the WebView lifecycle and presentation. No token is exposed to Compose
state. The adapter owns destruction through `release`.

Instrumented UI tests use sanitized fake authentication ports and do not exercise
live Proton compatibility or hosted verification. They cover clearing inputs,
masked fields, second-factor cancellation, cleanup gates, retry blocking,
backgrounding, secure-window policy and the distinction between authenticated
identity and Calendar readiness. Recheck the pinned upstream files for relevant
fixes when changing these adaptations; copied source does not update automatically.

When Proton cannot be reached, the panel offers an explicit action to open this
application's standard Android settings page. It does not change permissions or
network policy, and is absent from normal sign-in and authenticated states.

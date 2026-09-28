# Authentication source provenance

This JVM module adapts the application-facing authentication contract and state
holder from **Contako**, copyright 2026 Patmanak, GPL-3.0-or-later. Preserve that
license and attribution with these sources. The source repository was the
owner-provided Contako checkout, pinned to commit
`33289e18e1303d1a1788f1e6d2154f0603738c54`; the imported files had no working-tree
diff from that commit. Other files in that checkout had unrelated changes.
No account data, generated output or stored session was imported.

## Imported files

Source paths are relative to Contako. SHA-256 values identify the exact source
file bytes read for extraction (including line endings); Git blob IDs also
identify the repository-normalized source.

| Original path | Git blob | SHA-256 |
| --- | --- | --- |
| `app/src/main/java/com/patmanak/contako/domain/auth/AuthenticationFlow.kt` | `aa719efda539b21ac388d49c5883eec359589600` | `a204af320417d37df3ecb325f9f37d2dcbc2cfdb8a7098051ea0e15d8bc5e7c6` |
| `app/src/main/java/com/patmanak/contako/ui/auth/AuthenticationViewModel.kt` | `c0a4a5f388d8985c5bc6896bb24d7724415763e6` | `d6622d4da29e5a3d6bc35bf23c10ea7d473080c1a564ac4db66052f363bf9a96` |
| `app/src/test/java/com/patmanak/contako/ui/auth/AuthenticationViewModelTest.kt` | `f929e3bef927f3c7af2ad56d1019021136ab538c` | `7cebde8ee82cd53e41ae5601801a9e7e062d1fdfa16f5509912739623d7c218b` |

## Local adaptations

- Package changed to `com.patmanak.proton.andcalendarbridge.auth`; contract/controller types are
  public so a separately implemented adapter/UI can depend on them.
- `AuthenticationViewModel` became `AuthenticationController`, with an injected
  coroutine scope and monotonic millisecond clock. Android Lifecycle, its factory,
  Main-dispatcher globals and `SystemClock` were removed. Callers must serialize
  access on the scope's dispatcher and dispose the controller before stopping it.
- `AuthenticationUiState` became `AuthenticationState`. The terminal `Ready` step
  and `READY` destination became `Authenticated` and `AUTHENTICATED`: they describe
  identity and account-key readiness only. Calendar bootstrap is not implemented.
- Added suspend `dispose()` to invalidate late results, clear transient inputs,
  stop retry work and await adapter cancellation. Disposal rejects further inputs.
  `AuthenticationSecret.close()` clears its buffer even while a suspended consumer
  has reserved it; this closes the cancellation-time lifetime gap in the extracted
  one-shot holder. Consumption still clears on exit and cannot be repeated.
- Documented adapter failure/cancellation/partial-session responsibilities and
  preservation of queued Calendar work. No Proton protocol or cryptography was
  copied or recreated in this slice.
- Retained the source's stage/error mapping, second-factor/mailbox distinction,
  duplicate-admission guard, stale-result guard, bounded secret input and monotonic
  server retry deadline. `ACCOUNT_ALREADY_CONNECTED` remains an adapter-reported
  error, not a Calendar-wide single-account policy.
- Tightened credential admission to the current destination. Out-of-stage inputs
  are cleared without invoking the adapter or changing the current step. A failed
  cancellation leaves `isCleanupRequired` set and exposes `CLEANUP_FAILED`; new
  credentials remain blocked until an explicit cancellation retry succeeds.
  Dismissing the message or reaching a retry deadline cannot clear that guard.
- Honor an explicit server retry delay for every failure category. The default
  30-second delay applies only to RATE_LIMITED without a supplied delay; generic
  failures without a delay remain retryable immediately.
- Adapted upstream controller tests to the injected test scope/clock and renamed
  states. Removed Android capture/back-routing assertions, which belong to the
  future Android UI integration. Added `AuthenticationContractTest.kt` for secret
  lifetime, mailbox progression, disposal, cleanup failure/retry, stage admission
  and the authentication-only boundary.

## Dependencies and integration boundary

The extracted production code uses Kotlin/JVM, JDK 17 and
`kotlinx-coroutines-core:1.10.2`; tests use JUnit 4.13.2 and
`kotlinx-coroutines-test:1.10.2`. It does not use Android, Room, Proton Core, a
network client or a crypto provider. There is no production implementation of
`AuthenticationFlowPort`, session storage, hosted challenge UI or Calendar access
bootstrap in this module. No connected login UI is enabled by this extraction.

Fake ports qualify controller behavior only. They cannot establish Proton SRP,
second-factor, CAPTCHA, protected storage, Calendar scopes/keys or Android lifecycle
compatibility. Future integration must preserve those distinctions and port the
relevant Contako adapter/device tests with sanitized fixtures.

## Maintenance

Copied source does not update automatically. Before auth integration or a release,
compare the three original paths with this pinned source, review upstream fixes,
and update this record, adaptations and tests together. Keep upstream auth/session
and challenge fixes separate from contact-specific changes. Runtime dependency
qualification and applicable third-party notices belong to the connected adapter
slice, not to the pure controller's passing JVM tests.

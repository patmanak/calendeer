# Proton Calendar source baseline

Source inspection date: 2026-09-26, from the project's initial feasibility study.
Evidence level: source inspection only. No compilation or live account validation.
Revalidate these findings before selecting a dependency or implementing a protocol.

## Confirmed direction

Proton announced a Calendar rewrite and describes a shared Rust direction for its
next-generation mobile apps:

- [2026 roadmap](https://proton.me/blog/2026-spring-summer-roadmaps)
- [Mobile engineering architecture](https://proton.me/blog/next-generation-proton-mail-mobile-apps)

## Published source findings

| Repository/ref | Finding | Reuse implication |
| --- | --- | --- |
| `ProtonMail/clients` at `2ecb794dbc221384dc6d88840965ac144db301ad` | Cargo declares `project/calendar/rust/calendar-api`, crypto, database, events, queue and other modules; the complete public tree has no `project/calendar` directory | Future architecture evidence, not available implementation |
| Same `clients` revision, `project/mail/rust/calendar/calendar-api` | Published `mail-calendar-api-v1` supports event retrieval/search, invite responses and personal settings | Partial Mail-side Calendar integration, not a complete synchronization SDK |
| Same `clients` revision, `project/mail/rust/crypto/crypto-calendar` | Event encryptor/decryptor implementation exists; manifest states `AGPL-3.0-only` and workspace dependencies | Evaluate dependencies, license and Android integration before copying or linking |
| `ProtonMail/WebClients` at `914d7520eff52aafed9a1adfe19859143bdf1dc9` | Calendar requests, change-feed calls and grouped event mutations are public | Reference for current protocol semantics, not a stable third-party contract |
| `ProtonMail/android-calendar`, branch `release/2.34.0` inspected | Kotlin API implementation for calendars, events, bootstrap, keys and sync | Native reference candidate; resolve the current branch to a commit before adopting source |

Pinned source links:

- [Future module declarations](https://github.com/ProtonMail/clients/blob/2ecb794dbc221384dc6d88840965ac144db301ad/Cargo.toml)
- [Public project tree](https://github.com/ProtonMail/clients/tree/2ecb794dbc221384dc6d88840965ac144db301ad/project)
- [Mail Calendar API](https://github.com/ProtonMail/clients/blob/2ecb794dbc221384dc6d88840965ac144db301ad/project/mail/rust/calendar/calendar-api/src/lib.rs)
- [Crypto manifest](https://github.com/ProtonMail/clients/blob/2ecb794dbc221384dc6d88840965ac144db301ad/project/mail/rust/crypto/crypto-calendar/Cargo.toml)
- [Web Calendar API](https://github.com/ProtonMail/WebClients/blob/914d7520eff52aafed9a1adfe19859143bdf1dc9/packages/shared/lib/api/calendars.ts)
- [Android API reference branch](https://github.com/ProtonMail/android-calendar/blob/release/2.34.0/app/src/main/java/me/proton/android/calendar/data/api/CalendarsApiImpl.kt)

## Implications for the next phase

Keep the synchronization policy and Android representation independent of the
current protocol. Do not assume the future SDK will be published on a particular
date, compile standalone, retain current crypto/session types, or preserve old
pagination/change cursors. Its current method names are not requirements.

The next research assignment should validate the chosen narrow capability from
published code, then propose a bounded experiment. A future adapter must satisfy
the same accepted behavioral contracts and an explicit persisted-state migration
or reconciliation strategy. No service credentials are required for this source
baseline.

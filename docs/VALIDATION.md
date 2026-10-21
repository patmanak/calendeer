# Validation

## Shared bounded attempt metadata

The existing `BoundedAttemptBlobReader` owns the identical metadata query and
strict decoding formerly copied in text, time and deletion SQL readers. Three
private metadata records become one nested record. Each typed reader still
supplies its codec's capsule limit, decodes the capsule and compares scope,
mutation, event and state before accepting it. Transaction, ownership, claim,
evidence and completion rules remain unchanged; no schema or interface changes.

PASS: mechanical comparison reconstructs all three original query/decoding
bodies. The remaining typed SQL differs only in delegation, unused imports and
removal of the redundant database argument at 30 private calls. Every call uses
the instance connection already bound to the shared reader. Chunk/proof reading
is byte-identical. SQL types, 4096-byte text limits, nonnegative generations,
strict UTF-8, uniqueness and cancellation checks are preserved. Independent
source review finds no correctness defect.

PASS: all 196 app JVM cases and all 57 selected physical SQL cases, without
failures, errors or skips. The physical selection comprises text attempt (12)
and proof (19), shared time ownership (10), deletion attempt (10) and deletion
recovery (6) suites on the enrolled API 35 target with new debug app/test APKs.
Controlled fixtures cover malformed/oversized metadata and capsules, strict
UTF-8, metadata/payload disagreement, large chunked reads, reopened immutable
proofs, cross-family/generation ownership, concurrency, revocation and rollback/
cancellation. These cases do not add Provider, live-service, OEM, Android 17 or
16 KiB runtime qualification. The static native gate passes on both new APKs,
covering 12 packaged ARM64/x86_64 library copies.

PASS: debug app/test and unsigned release APK builds and lint, offline with
strict dependency verification and resolved Kotlin/Compose/KSP assertions.
Lint retains zero errors and nine existing `UseKtx` warnings, without suppression.

## Coverage serialization in the existing import codec

Eleven pure coverage encoding/validation methods move from `ImportStore` to
`ImportPayloadCodec`, removing 84 net lines from the SQL facade. No new class,
interface or schema is introduced. The four module-local entry points preserve
their call sites; SQL, transaction and lease boundaries remain unchanged.
The page schema stays at 1; ledgers still read schemas 1/2 and write schema 2.
The shared 256 KiB byte budget, 128-window bounds, null handling and progress/
timestamp/history validation are retained.

PASS: mechanical comparison proves byte-identical method bodies after the four
visibility changes, with the rest of both production files unchanged apart from
imports. Independent source review finds no correctness defect.

PASS: all 196 app JVM cases, including four new handwritten JSON compatibility
and rejection cases. They qualify legacy progress/observation, page encoding,
invalid plans/schemas/history/timestamps, exact/+1-byte input limits and
multibyte UTF-8 read/write budgets. All eight selected physical cases pass:
seven isolated coverage-store cases and one production coordinator/Provider case
with a synthetic remote. They cover reopening, scoped state, quota/lease rollback,
legacy migration and receipt/application/projection separation on the enrolled
API 35 target with new debug app/test APKs. No failures or skips are reported;
these checks do not add live-service, OEM, Android 17 or 16 KiB runtime coverage.

PASS: debug app/test and unsigned release APK builds and lint, offline with
strict dependency verification and resolved Kotlin/Compose/KSP assertions.
Lint retains zero errors and nine existing `UseKtx` warnings, without suppression.
The static native gate passes on both new APKs, covering 12 packaged ARM64/x86_64
library copies.

## Shared attempt work-page SQL

The text, time and deletion journals share one synchronous metadata reader
instead of three copies of their paging query and decoding loop. Each journal
retains its typed state/ref/page construction and transaction/admission wrapper.
The reader accepts only the three fixed table names. It preserves the validated
query's limit plus one lookahead row, account/calendar/generation scope and
SQLite `BINARY` continuation order. SQL bounds mutation/event/state cells to
4096/4096/15 bytes before materialization; UTF-8 decoding and per-row cancellation
remain strict. Capsules and proofs never enter the metadata cursor; discovery
still grants no ownership or dispatch authority. No new class, schema or
lifecycle framework is introduced.

PASS: mechanical comparison reconstructs all three original query/decoding
blocks from the shared reader. Complete journals differ only in page delegation
and two unused imports each. Independent source review finds no correctness
defect.

PASS: all 192 app JVM cases and all 29 selected physical cases, without failures,
errors or skips. The physical selection comprises 13 text work-page cases, six
deletion recovery-journal cases and ten time recovery/controller cases on the
enrolled API 35 target with new debug app/test APKs. Isolated SQLite fixtures
cover malformed/oversized metadata, lookahead corruption, Unicode continuation,
opaque multi-megabyte payloads, scoped reopening, revocation and cancellation.
The native boundary in time recovery is controlled; these checks do not add
Provider, live-service, OEM, Android 17 or 16 KiB runtime qualification.

PASS: debug app/test and unsigned release APK builds and lint, offline with
strict dependency verification and resolved Kotlin/Compose/KSP assertions.
Lint retains zero errors and nine existing `UseKtx` warnings, without suppression.
The static native gate passes on both new APKs, covering 12 packaged ARM64/x86_64
library copies.

## Shared projection acknowledgement guards

`ImportStore` shares the identical ordinary-source, redundant-projection
retirement and exact-result-echo mechanics across title/text/time updates.
Nine private helpers become three at nine call sites, removing 75 net source
lines. Callers pass frozen fields from the same typed receipt. Source-specific
preparation tables, the title owner reader, capsule/result checks and transaction
boundaries remain unchanged. Projection retirement still requires native proof
and an exact SQL delete inside baseline/owner restoration. No new type, schema
or generic lifecycle framework is introduced.

PASS: mechanical comparison reconstructs all nine original bodies and the rest
of the store, except removed duplicate documentation and the shared projection
error label. SQL, bindings and guard order are identical. Independent source review
finds no correctness defect.

PASS: all 192 app JVM cases, without failures, errors or skips. Debug app/test
and unsigned release APKs build offline with strict dependency verification and
resolved Kotlin/Compose/KSP assertions. Lint retains zero errors and nine existing
`UseKtx` warnings, without suppression. The static native gate passes on both new
APKs, covering 12 packaged ARM64/x86_64 library copies.

PASS: all 102 selected physical cases, without failures or skips: three
acknowledgement stores (69), captured-create handoff store (15) and 18 targeted
coordinator/Provider/SQL integration cases. They cover exact/refused echoes,
redundant/distinct projections, immutable provenance, later edits and native/SQL
rollback and recovery. The enrolled API 35 target runs the new debug app/test
APKs using controlled fixtures. No additional live-service, OEM, Android 17 or
16 KiB runtime qualification is claimed.

## Shared update acknowledgement checks

`ImportStore` replaces 14 duplicated private acknowledgement helpers with five
shared functions and updates their 28 call sites, removing 88 net source lines.
Only identical title/text/time guards are shared: retained-observation requeue,
redundant projection, direct baseline and format checks, plus the text/time owner
reader. The title owner reader stays separate because its profile validation
differs. Typed transactions, source admission, preparation, histories and codecs
retain their existing behavior; no new type, interface or schema is introduced.
One time-history error label now correctly names time instead of text.

PASS: mechanical comparison reconstructs all original helper bodies and the rest
of the store after the expected immutable-field substitutions and call renames,
apart from comments, blank lines and fixed diagnostic labels. SQL, bindings and
guard order are unchanged; the title owner function is byte-identical.
Independent source review finds no correctness defect.

PASS: all 192 app JVM cases, without failures, errors or skips. Debug app/test
and unsigned release APK builds and lint pass offline with strict dependency
verification and resolved Kotlin/Compose/KSP assertions. Lint retains zero errors
and nine existing `UseKtx` warnings, with no suppression. The static native gate
passes on both new APKs, covering 12 packaged ARM64/x86_64 library copies.

PASS: all 203 selected physical cases, without failures or skips: the three
acknowledgement stores (69), title history (18), coordinator/Provider/SQL
integration (101) and captured-create handoff store (15). The new debug app/test
APKs are installed on the enrolled API 35 target. Controlled fixtures cover
requeue/replay, exact ownership, later edits, represented projections, captured
creation, scope/format rejection and transactional rollback/cancellation.
These checks do not add live-service or OEM qualification. Android 17 and
16 KiB runtime qualification remains **NOT RUN** pending compatible hardware.

## Android 17 / 16 KiB qualification preparation

The [qualification plan](ANDROID_17_QUALIFICATION.md) separates static APK
checks from Android 17 / 4 KiB, Android 17 / 16 KiB and earlier Android / 16 KiB
device profiles. Its device rows define prerequisites, assertions, restoration
and evidence boundaries; none is marked passed by a build or static tool.

PASS: 19 Python tooling cases, including 11 native-gate cases for valid separate
RELRO LOADs, both rounded protection edges, bad alignment/header/table/segment
data, ABI mismatch, empty/duplicate inventories, compressed/misaligned packaging
and aggregate CLI failure. The read-only native gate passes on the existing SDK
37 debug/release APKs: six ARM64/x86_64 libraries each, 12 packaged copies. Every
report keeps Android 17 and 16 KiB runtime fields `NOT_RUN`. CI now runs this
gate on both built APKs and propagates a failed check; the remote job is not run.

A read-only capability probe confirms that the enrolled target is API 35,
ARM64 with 4096-byte pages. It cannot exercise either new runtime profile.
Android 17 / 16 KiB journeys remain **NOT RUN**. This preparation changes only
tooling, CI and documentation; application builds and physical regressions from
the SDK migration below are reused, not rerun or counted as new qualification.

## SDK 37 migration

Both Android modules compile against API 37; the app targets 37 and retains
minimum API 31. The local platform is stable Android 37.0 revision 2. AGP 9.2.1,
Gradle 9.4.1, Build Tools 36.0.0, Kotlin/Compose 2.4.21 and KSP 2.3.12 remain
unchanged. CI installs `platforms;android-37.0`; its remote JDK 17 job has not
been run. Local builds use JBR 21.0.10 with Java bytecode target 17.
No application-code, dependency-lock, checksum, Room-schema or security-override
change is required. The static compatibility review and remaining platform
coverage are in [build security](BUILD_SECURITY.md#android-api-37-baseline).

PASS: all 1,483 host cases (427 domain, 20 authentication, 844 backend, 192 app),
without failures, errors or skips. The full task graph builds debug app/test and
unsigned release APKs and runs host suites/lint offline with strict dependency
verification, without lock/checksum update flags. Resolved Kotlin/Compose/KSP
classpath assertions also pass. Local build destinations are isolated while
using the normal sources and task list. Final lint reports zero errors and nine
`UseKtx` warnings; both SDK-age hints are gone. The checked synchronous
`commit()` results remain unchanged, with no suppression or severity override.

PASS: all 145 selected physical cases after installing the SDK 37-targeting APKs
on the enrolled Android 15/API 35 device, without failures or skips. This repeats
the bounded synchronization, SQL/migration, consent, native crypto/parser and
authentication UI/retained-startup selection described below. These controlled
fixtures qualify backward regression coverage; they do not exercise Android 17
behavior or new live-service transport paths.

PASS: compiled debug/release manifests declare compile/target API 37 and minimum
31, retain backup/cleartext exclusions and exported service/receiver permission
gates, and reference the same eight packaged app languages. Both APKs pass
`zipalign -c -P 16 4`. All six packaged ARM64/x86_64 libraries in each APK have
16 KiB-aligned LOAD segments. Rounded RELRO protection does not overlap writable
data outside RELRO, including the [separate-LOAD case](https://developer.android.com/guide/practices/page-sizes#check-relro).
This static check covers 12 packaged library copies; it does not qualify runtime
page-size assumptions on a 16 KiB device. No RELRO or loader bypass is added.

PASS: workflow validation and the advisory lookup on the unchanged 571 Maven
name/version pairs (`NO_MATCHES`, exit 0). The SDK platform is outside this Maven
inventory. Android 17 runtime/16 KiB testing remains **NOT RUN**, including native
startup, real HTTPS and hosted verification, Provider/permissions, scheduling and
recovery, rotation/keyboard, large screens and memory limits.

## Generated application language configuration

AGP now generates the application locale configuration from resources, with
English as the unqualified-resource locale and the existing eight-language filter.
The duplicate handwritten XML list and source-manifest reference are removed;
the generated manifest still declares app languages for Android 13+. The Android
12/12L in-app language path is unchanged. This follows Android's
[automatic per-app language support](https://developer.android.com/guide/topics/resources/app-languages#auto-per-app-languages).
No lint suppression, baseline, severity override or dependency change is added.

PASS: the 192 app JVM cases, without failures, errors or skips, and debug app/test
and unsigned release APK builds with offline strict dependency verification.
Structural checks compare all generated locale variants and the compiled XML in
both APKs against the previous eight-language declaration and the in-app picker.
They also verify the packaged manifest's locale reference, unchanged SDK levels,
backup/cleartext exclusions and exported service/receiver permission gates.
This resource/configuration change does not rerun physical-device or live-service
journeys; their previous measured scope remains below.

Final offline lint reports zero errors and 11 warnings, down from 12:
the manual manifest's `UnusedAttribute` finding is gone. Nine `UseKtx` suggestions
remain because these consent, cursor and identity writes check the synchronous
`Editor.commit()` result. The [AndroidX edit extension](https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/content/SharedPreferences.kt)
returns `Unit`, including with `commit = true`, so a direct replacement would
discard failure evidence. These calls remain unchanged. The other two warnings
recommend newer compile/target SDKs; both remain at the qualified API 36 baseline.
The [Android 17 target behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
require separate qualification, including native loading, authentication and
background work. Compiler/plugin security updates remain in effect.

## Patched Kotlin build chain

Kotlin/KGP and the Compose compiler plugin are upgraded to 2.4.21, with KSP
2.3.12. AGP 9.2.1 and Gradle 9.4.1 stay unchanged. The resolved root plugin
classpath is checked against all three selected versions; the five lockfiles
record the new graph. The obsolete Android Kotlin source-set workaround and
the earlier KAPT activation guard are removed. The patched component replaces
that mitigation; no advisory exception or compiler-diagnostic suppression is added.
Compatibility and advisory sources are in [build security](BUILD_SECURITY.md#patched-kotlin-toolchain).

The first upgraded compilation reproduced Kotlin's
`TYPE_INTERSECTION_AS_REIFIED_ERROR` in eight heterogeneous SQL binding arrays.
Those test fixtures now declare `arrayOf<Any?>` explicitly. Mechanical comparison
of all five complete files confirms that queries, binding values, assertions and
all other lines are identical. No production application source changes.

PASS: all 1,483 host cases (427 domain, 20 authentication, 844 backend, 192 app),
without failures, errors or skips. All 145 selected physical Android cases pass,
without failures or skips: the maintained 131-case regression selection, nine
authentication UI/startup cases and the five affected SQL-fixture methods. This
covers synchronization admission/recovery, native crypto/parser checks, secure
authentication UI, retained-session startup and the independently constructed
legacy-schema migration. Controlled fixtures and activity recreation do not
qualify additional OEMs, reboot/power loss or live-service transport. Room's
exported schemas and the Proton Core/native crypto pins remain unchanged.
The merged debug/release manifests retain backup and cleartext exclusions and
permission gates on exported services/receivers.

PASS: the final full Gradle task graph builds debug app/test and unsigned release
APKs and runs the host suites/lint offline with strict dependency verification,
without lock/checksum update flags. The resolved-classpath assertions confirm
Kotlin/KGP 2.4.21, Compose compiler plugin 2.4.21 and KSP 2.3.12. An init script
isolates local build destinations while using the normal production sources and
task list. Final offline lint reports zero errors and 12 warnings (one API target,
one version-gated locale attribute, one dependency-age hint and nine synchronous
`commit()` suggestions whose boolean results remain required). Online metadata
can produce additional version-availability hints; these are separate from the
advisory lookup. No lint suppression is added.

PASS: the Maven inventory contains 571 distinct name/version pairs. OSV returns
`NO_MATCHES` with exit 0, including the resolved root Kotlin plugin, without
suppression. This supersedes the earlier 553-package baseline below and does not
establish complete vulnerability coverage. The five principal Kotlin/KSP plugin,
compiler and analysis JAR hashes match Maven Central HTTPS checksum sidecars or
fresh downloads. An intentionally incorrect AGP checksum is rejected by strict
dependency verification; the metadata is restored byte for byte afterward.
The eight advisory-tooling cases and workflow validation also pass. The configured
JDK 17 GitHub CI workflow has not been run remotely; local builds use Android
Studio's JBR 21.0.10 with Java bytecode target 17.

## Audit stabilization and build assurance

The backend text/time adapters now share `UpdateRequestLease`, the identical
admission lifetime previously copied inside each adapter. Mechanical comparison
reconstructs both complete adapters and the original lease body. Independent
review confirms unchanged cancellation, refusal, separation of factual evidence and
closure; typed payload, session, claim and readback paths remain separate. Four
new regressions cover foreign namespaces, irreversible refusal, admission failure
and swallowed/replaced cancellation. The shared multi-operation controller is now
named `SystemUpdateController`; reversing that symbol change reconstructs all
eight affected source/documentation files.

PASS: all 1,483 host cases (427 domain, 20 authentication, 844 backend, 192 app),
without failures, errors or skips. All 131 selected physical Android cases pass,
without failures or skips, including the localized warning/independent consent
assertion and seven native crypto/parser checks. These use synthetic keys/events,
isolated SQL/Provider fixtures and controlled remote ports, not live-service or
additional OEM/reboot qualification. After the final tooling-only fixes, installation
and 11 consent/native-crypto cases pass again against the final debug app/test APKs,
without skips. These are repeated checks, not 11 additional distinct cases.
Eight dependency-audit tooling cases pass,
including missing module/plugin locks, incomplete/error envelopes, malformed
results and paginated advisory lookup. Workflow validation and CI YAML parsing
pass; the configured GitHub workflow has not been run remotely.

PASS: the final full Gradle task graph builds debug app/test and unsigned release
APKs and runs host suites/lint offline with strict dependency verification, without
lock/checksum update flags. An init script isolated local build destinations while
using the normal production sources and task list. Final offline lint reports
zero errors and 12 warnings (one API target, one version-gated locale attribute,
one dependency-age hint and nine synchronous `commit()` suggestions whose boolean
results remain required). An online pass also reports version-availability hints;
these counts are not a vulnerability score. The rebuilt release manifest retains
backup/cleartext exclusions and system permission gates for exported services and
diagnostic receivers.

The final Maven inventory contains 553 distinct name/version pairs, including
the root plugin classpath. OSV retains exactly one finding:
`GHSA-r937-wjx7-w2jp` on Kotlin Gradle plugin 2.2.10 (`buildscript:classpath`),
so the advisory command correctly exits 1. No other matches were returned for
the resolved inventory; this is not complete vulnerability coverage. The upstream
fix concerns KAPT's incremental-cache deserialization. KAPT is not used; all four
attempts to activate its canonical/legacy plugin identifiers at the root or a JVM
module fail through the explicit guard before tasks execute. The finding is not
suppressed in that baseline. The [patched toolchain](BUILD_SECURITY.md#patched-kotlin-toolchain)
supersedes this earlier applicability assessment; a plugin guard alone did not
qualify KSP's reuse of KAPT cache infrastructure.

Targeted fixes cover Jackson Core (Android runtime), Netty 4.1, Commons Lang,
HttpClient, Bouncy Castle, jose4j and JDOM (the other findings were in tooling/test
dependencies). All 19 updated JAR hashes match Maven Central HTTPS checksum
sidecars or fresh artifact downloads. This supplements the initial trust-on-first-use
inventory; it does not authenticate the entire dependency graph. An intentionally
wrong AGP JAR checksum is rejected by strict verification, and the final metadata
is restored byte for byte before normal validation.

The production options and retained panel display the concurrent-write warning in
all eight languages before write consent. Counter labels preserve every format
argument while avoiding singular/plural ambiguity. Unused fallback strings and an
unused icon are removed, launcher XML is moved unchanged, and UI preference/style
calls are simplified. Synchronous boolean `commit()` results used as persistence
proofs remain unchanged; no lint suppression is added. Resource XML, duplicate
qualifier keys, localized argument formats and launcher byte identity pass checks.
README, architecture and permission documentation now match the implemented
restricted DELETE, Provider/storage/scheduling and foreground-service removal.

| Audit area | Result and boundary |
| --- | --- |
| Independent incoming progress | Unsupported dirty events remain held while other events/pages progress; native flags and evidence are preserved. |
| Lifetime capacity | Completed text/time/deletion history is retained outside active staging capacity; no proof purge is introduced. |
| Later native pages | New durable owners rearm outgoing discovery only; held/UNKNOWN owners do not receive new send or inspection authority. |
| Duplication and large classes | Existing transaction, journal, conflict, owner, acknowledgement and source/admission boundaries have focused shared implementations. Operation-specific profiles, payloads and security fences stay typed; no generic synchronization framework is added. |
| Cross-path regression maintenance | `tools/device-regressions.txt` records the bounded progress/capacity/capture/recovery/consent/native-crypto selection. Controlled fixtures do not qualify live transport, OEM, reboot or power loss. |
| Automated assurance | Windows CI, root/module version locks, SHA-256 verification and scoped Maven inventory/advisory tooling are checked in. See [build security](BUILD_SECURITY.md) for updates and trust limits. |
| Remote concurrency and release | Explicitly open: atomic server preconditions or accepted-risk policy, signing/license distribution decisions and remaining device/transport qualification. The warning does not close the race between GET and the write. See [release gates](BUILD_SECURITY.md#remaining-release-gates). |

## Shared text/time submission callbacks

`EditSubmissionLease` groups one invocation's lifetime, source refusal and factual
evidence refusal. Text/time gateways retain the original operation context and
account-evidence capture point, typed validation, submission and durable readback.
DELETE keeps its separate callback logic; no generic dispatch pipeline is added.

PASS: mechanical comparison of callback bodies and closure order, followed by
inlining both delegations, reconstructs the complete original coordinator.
Independent review confirms unchanged scope checks, short-circuiting, cancellation,
live format reads, lock order and final closure. Source refusal remains permanent
without revoking otherwise admitted factual evidence. Evidence refusal cannot
borrow restored account/caller access. Neither channel can survive the invocation
or acquire acknowledgement/dispatch authority from retained proof.

Ten added JVM regressions cover lazy admission, live format revocation, rejection
followed by restored contents, account/calendar/generation isolation, independent
evidence refusal, retained callbacks after closure, ordinary source failure,
swallowed cancellation and closure/cancellation during admission observation.

PASS: all 47 selected physical Android cases across six classes, without failures
or skips. Text/time owner gateways and recovery preserve import/auth lock order,
independent consent/evidence, expired callbacks, UNKNOWN history, transferred-CREATE
provenance and inspection-only recovery of interrupted receipts. Existing decode
cancellation and handoff permission-loss regressions also pass. Controlled fixture
ports do not extend live Proton, OEM, reboot or power-loss qualification.

All 1,479 host cases pass (427 domain, 20 authentication, 840 backend and 192 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Shared text/time source checks

Three private coordinator functions share native-content qualification, the
surrounding SQL/source rechecks and fresh-acknowledgement role checks. Four copies
of the native preflight now use one definition. No new type, interface or generic
outgoing pipeline is introduced; operation profiles, preparations, verified
results, journals and completion remain typed.

PASS: checking all three helper bodies and inlining the six delegation sites
reconstructs the entire pre-change coordinator. Independent review confirms the
same read order, short-circuiting, scope holds, transferred-CREATE provenance and
null/unsupported-source refusal. ACK result identities already bind the event to
the intent's base. Every suspended read retains the original scoped access and
cancellation fences. A fresh reservation remains the only native-write authority;
pending historical receipts only inspect. Format reads, UNKNOWN handling, lease
closure, schema, payload and journal formats are unchanged.

PASS: all 113 selected physical Android cases across eight classes, without
failures or skips. Text/time owner gateways and recovery cover raw-versus-canonical
text, date changes, transferred owners, revocation, cancellation, UNKNOWN and
reopened pending receipts that cannot issue another native write. Both Provider
ACK suites and the existing decode-cancellation/handoff-permission regressions
also pass. Controlled fixture ports do not extend live Proton, OEM, reboot or
power-loss qualification.

All 1,469 host cases pass (427 domain, 20 authentication, 840 backend and 182 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Title owner and source qualification

`LocalTitleOwnerQualification` is extracted from the coordinator's existing inner
class. Preparation, retention and guarded submission share the same collaborator;
the title-only profile and released-handoff match each have one definition. The
coordinator retains the mutex, original generation/policy capture, admission
installation/cleanup and irreversible refusal latches.

PASS: mechanical comparison of the complete extracted qualification body and
profile, plus independent review of the complete retained coordinator delta,
preserves check order, short-circuiting and cancellation. Current adapter formats
remain live reads. Normal account observation retains the original epoch; guarded
submission uses only the separate cached account path and cannot re-enter the
adapter's authentication lock. Exact SQL owner/preparation, native contents,
incoming holds and transferred-CREATE provenance remain rechecked around
suspensions. No new claim, submission, acknowledgement or recovery right is added;
journal, schema and payload formats are unchanged.

PASS: all 95 selected physical Android cases across seven classes, without
failures or skips. Four coordinator suites cover wire preparation, retention,
guarded submission and durable results; focused Provider integration covers
ordinary and transferred owners, revoked/restored access, incomplete provenance,
later native edits, UNKNOWN and cancellation. Existing captured-handoff permission
loss and import decode-cancellation regressions also pass. Controlled fixture
ports do not extend live Proton, OEM or power-loss qualification.

All 1,469 host cases pass (427 domain, 20 authentication, 840 backend and 182 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Shared local UPDATE/DELETE capture

`LocalChangeCapture` groups imported-event UPDATE and DELETE snapshots, sharing
selection/current-account checks and admission setup/cleanup. The coordinator
retains its mutex, original generation/policy and distinct initial guards. Incoming
CREATE holds and historical baseline qualification still use the existing paths
with that same epoch; native snapshots and stored intents remain typed.

PASS: independent comparison of the complete retained coordinator and mechanical
comparison of both extracted bodies preserve check order, retained-owner identity,
opposing-owner refusal, live format reads and cancellation. `beforeNewOwner` remains
after the native snapshot and before first retention, with account/access rechecked
afterward. Existing owners do not notify it again. Capture never sends, acknowledges
the Provider or renews dispatch authority; schema and payload formats are unchanged.

An old account-return fixture expectation reproduced identically before extraction:
observing the returned account advances policy and invalidates that invocation.
The strengthened test requires `INACTIVE`, no native read and no retained owner,
then preserves successful explicit capture with observer mode off, the requested
identity and exactly one native read. No production guard was relaxed.

PASS: 79 other physical cases plus all 12 final edit-capture coordinator cases
validate 91 unique selected cases across ten classes, without skips on the same
production APK. Coordinator/native integration, title preparation, dirty-row
recovery, DELETE ownership and captured-CREATE holds preserve unsupported content,
immutable owners, cancellation and discovery notification order. Controlled ports
do not extend live Proton, OEM or power-loss qualification.

All 1,469 host cases pass (427 domain, 20 authentication, 840 backend and 182 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Local history reader boundary

`LocalChangeHistory` isolates retained-change listing, local edit/delete inspection
and conflict-archive reads. The coordinator retains the mutex and captures the
original generation/policy before delegation. The reader shares the current-account
predicate and admission installation/cleanup, without adding sending consent or
requiring known historical calendars to remain enabled/available.

PASS: mechanical comparison preserves all four operation bodies after explicit
delegation substitutions. Independent comparison of the complete retained
coordinator confirms the same snapshot timing, predicate short-circuit order,
typed early `Unqualified` results, archive-specific cancellation/error rules and
cleanup order. No Provider content read, native acknowledgement, journal/schema
change or renewed send authority is introduced.

PASS: all 67 selected physical Android cases across six classes, without failures
or skips. The 65 history cases cover scoped SQL reads, paging, paused/unavailable
calendars, account/permission loss, malformed evidence, cancellation and sanitized
session failures. Both existing import cancellation/permission-loss recovery
regressions pass as well. Controlled fixture ports do not extend live Proton,
OEM or power-loss qualification.

All 1,469 host cases pass (427 domain, 20 authentication, 840 backend and 182 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Shared worker edit admission and calendar passes

`SystemEditAdmission` replaces the identical text/time worker predicates and
factual-evidence admission bodies. Each invocation still captures generation,
policy, account and authentication in the original order. Sending consent remains
operation-specific; historical proof does not read it. Factual evidence can survive
editing-policy loss, but never observed logout or account replacement. Deletion
keeps its existing second consent latch over the common proof predicate.

PASS: mechanical comparison confirms exact shared bodies after local-reader
substitution. Independent source review confirms the retained predicates,
short-circuit order, final generation/policy fence and irreversible rejection,
with no eager snapshot at helper construction or authenticated-adapter call.
Eight added host regressions cover captured epochs, permanent refusal, calendar
scope, independent consent, policy changes during selection and factual evidence.

The first admission APK exposed a native ART abort during the existing decode-
cancellation fixture, without an assertion failure. It reproduced in isolation;
the exact previously validated APK passed that same case. `runLocked` was unchanged
but still produced a very large coroutine state machine. Its calendar pass is now
a private method, preserving the four global inactive exits, status/catch order
and cross-calendar retry precedence. Independent source comparison confirms those
semantics. `runLocked` falls from 192 to 35 source lines, and its DEX disassembly
from 112,278 to 3,313 lines (285 to 37 registers). Both decode cancellation and the
previous captured-handoff permission-loss regression pass unchanged after this
split. The underlying runtime/compiler cause is not established by these tests.

PASS: 78 physical cases in the expanded selection, followed by all seven final
delta coordinator cases, validate 85 selected cases without skips on the same
production APK. They cover edit admission, retained evidence, dirty projections,
staged pages, delta/recovery, rolling windows, coverage and recurrence. An old
delta fixture expectation also failed on the exact pre-extraction APK: bootstrap
had already projected its baseline. Its correction verifies SQL/native original
content, an empty projection queue and the same native row after offline delta
recovery, retaining the no-network and final-status assertions. Independent review
confirms the stronger expectations. Controlled fixture ports do not extend live
Proton, OEM or power-loss qualification.

All 1,469 host cases pass (427 domain, 20 authentication, 840 backend and 182 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Captured CREATE handoff and import finalization boundaries

`CapturedCreateHandoff` isolates proven CREATE transfer and later local-change
reconciliation. Identical availability and admission setup/cleanup are shared;
the coordinator retains its mutex, original epoch and phase-specific consent.
Source qualification remains shared with other owner paths. Historical native
proof recovery never writes or reconstructs sending authority.

PASS: independent source comparison preserves both operation bodies, historical
recovery and the retained coordinator outside the factory/delegations. A second
comparison confirms that moving diagnostic finalization into `finishImportRun`
preserves its exact body and the rest of the coordinator. Original exceptions,
including cancellation, retain priority over secondary refresh failures; `busy`
cleanup and successful-network-only scheduling keep their order and conditions.

The permission-loss-after-Provider-write fixture reproduced a native ART abort
(`Unexpected instruction: unused-f8`) on both the initial extraction and an exact
clean pre-extraction build. Resetting compiled package artifacts and rebuilding
the extraction cleanly did not resolve it. The same unmodified test passes after
isolating suspending finalization. DEX inspection confirms a large pre-existing
coroutine state machine; the runtime/compiler cause remains undetermined.

PASS: all 190 selected physical Android cases across seven classes, without
failures or skips. Handoff SQL/Provider/integration, production CREATE pipeline,
incoming holds and 19 transferred-title follow-ups preserve provenance, later
edits, historical recovery, cancellation and exact offline echo continuation.
These fixtures use controlled remote ports; they do not extend live Proton,
OEM or power-loss qualification.

All 1,461 host cases pass (427 domain, 20 authentication, 840 backend and 174 app),
without failures or skips. Final debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Local CREATE capture and staging boundary

`LocalCreateStaging` isolates capture, protected read-only preparation and durable
submission staging. Their identical admission installation/cleanup is shared;
phase-specific qualification remains separate. The coordinator retains its mutex,
initial account/permission gates, lifecycle initialization and send-lease registry.
Only fresh durable staging records the original epoch's lease; cache reads never
reconstruct that authority.

PASS: independent comparison preserves all three operation bodies and the retained
coordinator outside the factory/delegations, including orphan marker inspection,
frozen identities/content, live format reads, cancellation and cleanup order.

Two legacy fixture expectations reproduced identically before extraction. Observing
a return to the original account invalidates that invocation's policy snapshot.
The fixtures now explicitly require `INACTIVE` for that observation before retaining
their original fresh-invocation capability/reservation assertions. Preparation,
inspection and retained-payload assertions remain enforced; no production guard
was relaxed.

PASS: the final physical selection passes all 115 tests across ten classes,
without failures or skips. Capture, preparation, staging, native markers, guarded
dispatch and production CREATE pipeline fixtures preserve immutable reservations,
unsupported data and cancellation; historical staging cannot rearm a send.
All 1,461 host cases are green (427 domain, 20 authentication, 840 backend and 174
app). Final debug app/test APK builds and app lint pass (zero errors, 43 warnings).
These device fixtures use controlled remote ports and do not extend live Proton,
OEM or power-loss qualification.

## Local title acknowledgement and completion boundary

`LocalTitleCompletion` isolates native acknowledgement and durable closure of an
already verified title result. Both phases share their availability check; the
coordinator retains the mutex and recovery traversal, explicitly passing the
original generation/policy rather than capturing a fresh epoch between phases.

PASS: independent source comparison preserves the complete retained coordinator
outside the factory/delegations, both extracted operation bodies, active-first
short-circuit order, live format reads, cancellation and phase-specific cleanup.
Fresh receipts alone authorize an ACK write; retained receipts inspect historical
proof and completion retains exact durable evidence. Schema, formats and consent
requirements remain unchanged.

PASS: 276 selected physical Android tests across nine classes, without failures
or skips, cover coordinator admission, end-to-end fixture recovery, receipt/history
storage, native acknowledgement and completion journals, including transferred
CREATE events. They preserve later edits, recover lost replies without another ACK
write, reject mismatched identities/proofs and retain the original epoch across
revocation/restoration. Fixture ports are controlled; this does not extend live
Proton, OEM or power-loss qualification.

All 1,461 host cases are green (427 domain, 20 authentication, 840 backend and 174
app), without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Captured CREATE restoration boundary

Six captured-CREATE restoration operations have one concrete collaborator:
binding, original-row attachment, baseline retention, acknowledgement, clean
inspection and later local-change reconciliation. The coordinator retains its
mutex, lifecycle initialization and original generation/policy capture. Identical
availability checks share one implementation; adapter format remains a live read.

PASS: independent source comparison against the complete pre-extraction coordinator
preserves all six operation bodies, including cancellation distinctions, exact
identity/content checks, fresh-only ACK writes, historical inspection and admission
cleanup. No schema, persistent format or submission policy changes.

PASS: 305 selected physical Android tests across 13 classes, without failures or
skips, exercise restoration through the existing coordinator entry points and
subsequent handoff/import holds. They cover access and format loss, interruption,
reopening, immutable original identities, fresh versus historical ACK, preservation
of later native edits/unsupported data and refusal of generic projection adoption.
These fixtures use controlled remote ports; no new live Proton, OEM or power-loss
qualification is implied.

All 1,461 host cases are green (427 domain, 20 authentication, 840 backend and 174
app), without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings).

## Import schema isolation and shared scoped owner guard

PASS: 279 selected physical Android tests across 19 classes, without failures or
skips, cover fresh import databases, legacy migrations, conflict evidence backfill,
sequence preservation, migration rollback, retained receipts and text/time/deletion
owner gateways. The text fixtures leave time consent disabled; successful text
operations and proof recovery after text consent is disabled remain qualified.
Revocation while waiting for the import lock cannot renew an operation's epoch;
retained callbacks expire and UNKNOWN work cannot submit again. Cancellation and
newer native changes remain protected.

`ImportSchema` isolates the existing SQL definitions and migration branches without
changing schema version 22. `SQLiteOpenHelper` retains transaction/rollback ownership,
and backfill uses the existing store capacity check. Six text operations reuse the
existing scoped edit guard with explicit text consent. Independent source review
compares the complete retained classes and all extracted migration methods against
their pre-refactor source; SQL, migration order and time/deletion guard behavior
remain unchanged.

All 1,461 host cases are green (427 domain, 20 authentication, 840 backend and 174
app), without failures or skips. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings). Device fixtures use controlled remote ports; no new live
Proton, OEM or power-loss qualification is implied.

## Import archive extraction and bounded journal reads

PASS: four host fixtures cover the historical cache/checkpoint representation,
including timed CRLF text, omitted optional fields, all-day recurrence and people,
tombstones, opaque scope/checkpoint values and rejection of an unsupported details
schema. Extraction preserves the writer's field insertion order and encoding.

PASS: 197 selected physical Android tests, without failures or skips, cover conflict
archive paging/isolation, import/projection receipts, local preparation, active
capacity, dirty projection isolation and text/time/deletion journal persistence.
Large capsules/proofs still use bounded chunks; malformed, wrong-typed, truncated
or misbound evidence fails closed, and cancellation remains propagated.

`ImportStore` retains admission, locks and transaction boundaries while synchronous
archive SQL, historical payload encoding and bounded SQL values have separate
internal helpers. The three typed attempt stores share only bounded BLOB reading;
their ownership checks, codecs, claims and proof semantics remain distinct.
Focused independent source review finds no behavior or persistent-format change.
All 1,461 host cases pass (427 domain, 20 authentication, 840 backend and 174 app),
without failures or skips. Debug app/test APK builds and app lint pass (zero errors,
43 warnings). These fixtures use controlled remote ports and do not extend live
Proton or OEM qualification.

## Local projection isolation and active journal capacity

PASS: isolated Android regressions retain unsupported dirty rows and their
recurrence dependencies while independent projections and bounded page ingestion
continue. They cover current dirtiness after historical receipt recovery, changes
between observation and native assertion, missing native masters with dirty
exceptions, held-state reopening and more than one batch of held rows. No held
intent, receipt or native edit is consumed to free the queue.

PASS: real SQLite capacity transactions admit completed text/time histories and
RESTORED deletion barriers without charging the active-work budget; 2,100 rows in
each retained table survive unchanged. PENDING receipts still enforce the limit
and roll back new work. This verifies accounting, not cryptographic validity of
the synthetic accounting-only capsules.

PASS: host pagination regressions cover 25+1 captured owners, restarted
continuations, insertions before/after the cursor, held-work termination and
revocation. Production capture invalidates discovery before insertion; existing
owners and the separate UNKNOWN inspection cursors are not rearmed by this wake.

The relevant host suites contain 427 domain, 20 authentication, 840 backend and
170 app cases, with no failures or skips. A focused physical selection of 279
SQL, Provider, recovery, journal and owner-gateway cases passes without skips;
nine historical recovery expectations now distinguish durable held work from
runnable work, preserve prior offline diagnostics and assert no extra native
write after known dirtiness. Debug app/test APK builds and app lint pass (zero
errors, 43 warnings). Independent source review covers the changes and the shared
text/time/deletion transaction helper. Typed ownership, payload formats and
claim/evidence rules remain separate. These fixtures use controlled remote ports;
they do not qualify new live Proton, OEM, reboot, power-loss or transport-failure
behavior. Completed history still occupies disk; no safe compaction is claimed.

## Interrupted deletion evidence persistence

PASS: `EVIDENCE_INTERRUPTION` creates a new disposable ordinary timed service
event and opens its isolated native copy in `foundation.e.calendar` on Android
API 35. After actual editor deletion, SEND uses the production coordinator and
authenticated adapter, delegating the real durable claim. A journal decorator
then injects a unique cancellation at `retainEvidence`, after the transport
attempt, without saving the supplied response capsule. Only that exact
cancellation is accepted. Reaching this checkpoint alone does not prove a
successful remote deletion or a received response.

The original UNKNOWN request, full base/content, PAUSED local owner, reserved
PENDING receipt and dirty deleted native row remain unchanged. No verified
result, completion or native write is allowed. Atomic fixture evidence binds
the first request/native hashes, receipt nonce, adapter format and process.
New-process UNKNOWN_REPLAY traverses the normal UNKNOWN guard and offline
recovery, which holds one request without preparing, submitting or inspecting.
Repeating that phase in another invocation preserves the first process witness
and the unique history. This composition has no HTTP ports; independent
application traffic is not claimed to be offline.

PASS: restarted INSPECT validates the original bindings before any GET, then
retains genuine authenticated absence without another preparation or submission.
The response capsule remains absent; native ACK stays deferred until proof is
durable. Inspection proof hashes and its process witness are committed together.
ACK in a further process restores the exact native/SQL receipt, keeps the row
deleted and clean, and rejects a staged old upsert through the incoming barrier.
Final fresh-process REPLAY preserves the identical request, result and completion,
with no new send, replacement history or native write. All seven physical
executions pass, including the repeated UNKNOWN_REPLAY. The phone is released
with Calendeer reopened; runtime calendar options and network settings were not
changed by this isolated journey.

Debug app/test APK builds and focused independent source review pass. The first
instrumentation build exposed the JVM method-size limit; moving the existing
offline ACK assertions into a separate coroutine resolves it. Runtime policy,
adapter and schema are unchanged. Earlier host-suite/lint results were not rerun
for this instrumentation-only change. This is controlled cancellation at evidence
persistence with real-service recovery, not a measured transport disconnection,
process kill during transport, production-worker fault injection, device reboot,
power loss or Samsung/One UI qualification.

## Offline deletion capture and process restart

PASS: a new disposable ordinary timed service event was deleted through
`foundation.e.calendar` on Android API 35 while Wi-Fi was disconnected on a
phone with no SIM. PREPARE, OFFLINE_CAPTURE and OFFLINE_REPLAY pass in separate
application processes. The fixture checks that no network has the INTERNET
capability at entry, during local worker polling and at exit; it does not infer
offline operation merely from a missing validated connection.

The normal production setters and WorkManager trigger capture one PAUSED local
deletion. The restarted offline phase retains its original identity, full base
and content, with no DELETE attempt or acknowledgement. The dirty deleted native
row remains unchanged. Its digest covers every returned column except the joined
calendar access level, which the normal consent setters switch between READ and
EDITOR; that field is checked separately against those two values. All other
joined metadata remains covered. The first owner, native digest and process
witnesses are persisted atomically with success and cannot be replaced by replay.

PASS: after restoring the original network settings, production RUN completes
the same retained owner's guarded deletion and exact native/SQL acknowledgement.
Active fresh-process REPLAY retains one attempt and identical request/result/
completion hashes. The native row remains deleted and clean, and the permanent
incoming barrier prevents reprojection. All five instrumented phases pass. The
five original target modes are restored after each active phase; original network
settings are verified before releasing the phone. Fixture admission still refuses
unrelated pending work before temporary activation.

Debug app/test APK builds and focused independent source review pass. This slice
changes instrumentation and documentation only; runtime policy, adapter and
schema are unchanged. Host suites and lint were not rerun for this fixture-only
change; their earlier results below remain separate evidence. This journey
qualifies disconnection before submission and application-process restart. It
does not qualify interruption during/after a remote claim, device reboot or
power loss, or Samsung/One UI.

## All-day deletion through the system editor and worker

PASS: six real instrumented phases qualify `ALL_DAY_SINGLE` and
`ALL_DAY_MULTIPLE`, each using a new disposable service event and independent
PREPARE, production RUN and active fresh-process REPLAY. The measured editor is
`foundation.e.calendar` on Android API 35; Samsung/One UI remains NOT RUN.

Each profile freezes its identity and civil dates before CREATE. Single-day
bounds are [D, D+1); multiple-day bounds are [D, D+3). The fixture checks UTC
midnight start/end, exclusive end dates, native identity and the same retained
row before editor deletion and after acknowledgement. RUN uses normal consent
setters and WorkManager scheduling. Both profiles retain one immutable request,
result and completion through restarted replay, with no new attempt and exact
restoration of the five original modes. No runtime policy, schema or adapter
change was needed for these profiles.

Bounded fixture admission now permits previous completed DELETE histories only
after checking the exact owner, protected request, verified result, completion,
RESTORED SQL receipt and native acknowledgement. The native row must still be
clean and deleted, with exact content/time/metadata and no children. Only these
validated owner tuples and row IDs are exempt; unrelated pending work, changed
rows and old generations remain refused. Independent source review passes.

Debug app/test APK builds pass. This slice changes instrumentation fixtures and
documentation only; the runtime policy, adapter and schema are unchanged. Earlier
host-suite and lint results below are separate evidence, not repeated runs.

## Real deletion conflict and local restart recovery

PASS: a new disposable ordinary timed service event was prepared and its isolated
Android copy deleted through `foundation.e.calendar`. The official Proton Calendar
editor then changed the synthetic title. Its save also changed the event zone from
`Z` to `Europe/Paris`, with identical start/end instants and other mapped contents.
The real guarded adapter returned the authenticated different variant before
claim, and the coordinator retained the full conflict dossier and original owner.

The initial fixture assertion expected a title-only change and failed on that
zone difference. Read-only SQL inspection found the exact PREPARED request, a
CONFLICT owner and the VERIFIED RECEIVED variant. No request was claimed, no
protected response/result/completion was created, and the native acknowledgement
remained PENDING. The fixture now freezes an explicit expected editor zone for new
conflict journeys; an existing missing declaration can be bound only by a fully
validated local review, never by another send or replacement preparation.

PASS: `CONFLICT_REVIEW` validates that existing dossier with the measured zone and
unchanged instants, full original/local/observed contents, exact wire hash, receipt
and dirty deleted native row. The original preflight was not repeated. A separate
fresh-process `CONFLICT_REPLAY` retains the same complete evidence and holds the
unclaimed request. This fixture composition has no HTTP, preparation, submission,
inspection or native acknowledgement path during review/replay. Independent app
traffic is not claimed to be offline. The modified service event is retained.

Debug app/test APK builds and focused independent source reviews pass. This is a
controlled simple-event preflight conflict and local restart case; it does not
qualify an atomic remote write precondition, general conflict resolution, every
production-worker conflict path, further network/power interruptions or Samsung.

## Deletion worker and Android acknowledgement

The [deletion contract](DELETE_SUBMISSION.md) now has separate production worker
orchestration, default-off per-calendar consent, authenticated absence results,
native acknowledgement and durable completion. Shared journal schema 9 and import
schema 22 preserve request ownership, pending receipts and the incoming barrier.
Completion never releases the original deleted event for reprojection.

PASS: 427 domain, 840 backend and 167 app JVM cases, without failures or skips.
Focused cases cover cancellation and access revocation, immutable first requests,
inspect-only UNKNOWN recovery, independent offline proof recovery, foreign conflict
binding, separate DELETE cursors and bounded/corrupt result and completion decoding.

A failing regression reproduced the measured exact-event GET response: HTTP 422,
structured code 2501 and empty `Details`. The strict parser now accepts this form
only on the bound authenticated route with current context/membership checks.
Nonempty, null, scalar or array details, unknown/duplicate fields, unrelated routes
and generic errors remain unresolved. Core loopback tests cover both plain and
gzip responses before structured API-error conversion. Absence is current
convergence, not proof of causality or an atomic write precondition.

PASS: 81 focused physical Provider, owner-gateway, migration, CREATE-pipeline and
option UI cases, including ordinary editor deletion and Provider-cleared `MUTATORS`.
Wider SQLite/Provider regression coverage includes 543 passing physical executions.
The remaining fixture failures were invalid color setup/cleanup, expected identity
refusal and historical-schema reconstruction; corrected focused cases pass.
Native checks cover retained clean tombstones, full receipt binding, newer edits,
lost replies, child/metadata refusal, revoked leases and permanent incoming barriers.

PASS: 65 further physical executions cover 18 deletion-store, nine owner-gateway,
24 ordinary projection and 14 temporal-acknowledgement cases. The production
journey reproduced a held local DELETE with no network request because an identical
incoming projection receipt remained pending. Its strict no-change exception now
retains the incoming obligation through reservation and retires it only after
native ACK. Regressions preserve different incoming contents, previous revisions,
formats and initial projections, verify rollback/reopening after a SQL fault and
refuse old projection replay after completion.

The isolated live fixture uses a new disposable service event, opened and deleted
in `foundation.e.calendar`. A restarted read-only inspection retained authenticated
absence without preparing or sending another DELETE, with native ACK deliberately
deferred. New-process ACK and another fresh-process replay pass with the same row,
immutable request/result/completion and old-upsert suppression. This fixture's
recovery ports cannot access HTTP; independent application traffic is not claimed
to be offline.

PASS: actual system-editor/production-worker RUN and fresh-process REPLAY for a
second new disposable ordinary timed event. PREPARE creates and verifies the
service event and seeds its ordinary production mirror. The external driver
verifies the exact enrolled title, then deletes it through `foundation.e.calendar`
and its observed confirmation dialog. RUN uses only production consent setters
and the normal WorkManager scheduling trigger; it does not directly prepare,
capture, submit, inspect or acknowledge the deletion. Exact result/completion,
same-row native ACK and the permanent incoming barrier pass. Active restarted
REPLAY retains one attempt and the identical request/result/completion hashes.

The five original target modes are durably restored, including initially read-only
QA activation after full quiescence checks. Before activation, bounded audits reject
foreign CREATE work, native changes, local owners/preparations, unfinished
acknowledgements and invalid/unclosed title/text/time history. Only the enrolled
deletion and separately validated completed DELETE histories are exempt. Other calendars retain existing
consent and may run their authorized work; global silence is not claimed.

Final app JVM cases and debug app/test APK builds pass; lint passes with zero
errors and 43 warnings. Focused independent reviews and staged-blob confidentiality
checks pass. All-day and real preflight-conflict evidence is recorded above.
Actual network loss or process kill during/after remote claim, device reboot,
power loss and Samsung/One UI qualification remain NOT RUN. Pre-submission offline
recovery and injected evidence-persistence cancellation are recorded above.

## Guarded deletion request foundation (earlier slice)

The [deletion submission contract](DELETE_SUBMISSION.md) separates the internal typed
port from a user-facing system-calendar capability. This slice adds protected
requests, a separate one-exchange DELETE permit, shared durable ownership and a
serialized authenticated facade. It does not add a deletion worker, native ACK,
user option or generic conditional-write capability.

PASS: 423 domain, 839 backend and 143 app JVM cases, without skips, and debug app/test
APK builds. Final app lint passes with zero errors and 42 warnings. The final
app/test APKs were installed in place and Calendeer was launched on the enrolled
phone under the shared lock. A focused guard RED run first failed all four new dispatch cases;
adding the separate DELETE permit to both guards fixes qualified dispatch,
reentry and mixed-tag refusal. The domain suite also reproduced malformed UTF-8
escaping as an unsanitized exception; validation now rejects it with a sanitized
argument error. New domain/backend/storage cases cover immutable raw request
binding, independent scoped evidence retention, pre-send verified conflicts,
interruption after claim, no UNKNOWN resend, generic 404 refusal, membership
requalification and strict bounded binary decoding.

A published Core/Retrofit regression then reproduced structured 2501 being
converted to an error before Retrofit could return the raw response. The fix
captures only the exact session-bound GET's response before conversion and ignores
exception codes. Core tests now cover both identity and gzip 404 responses,
unchanged authenticated request bytes, no token-refresh/repeated PUT, and foreign
session refusal. Seven additional witness cases cover reentry, unique headers,
consume-once/close, non-consuming capture, wire/inflated-size bounds and malformed
or unsupported compression. Independent review found the pre-decompression gzip
case; its bounded Okio fix and regression fixtures pass.

PASS: 42 physical Android SQL executions, including ten new deletion cases and
32 shared-ownership/migration regressions. They cover reopening UNKNOWN with the
first wire/evidence unchanged, revoked/cancelled transactions, title/text/time
exclusion across generations and request IDs, corrupted capsules/completions,
segmented reads of large payloads and schema 7→8 preservation. Existing legacy
migration fixtures explicitly reconstruct the older schema and now expect version
8. These are isolated native SQLite tests, not an actual remote DELETE or a
calendar-editor journey. Temporary databases were removed and the application was
restored. App/test APKs were installed in place without clearing application data.

PASS: focused independent source review and staged-blob confidentiality checks.
The review examined the request/claim/inspection boundary, scoped absence parsing,
shared ownership and final facade/HTTP integration. Real-service code 2501 semantics,
remote conflict retention in the coordinator, native completion, offline ACK and
actual system-editor deletion remain NOT RUN or unimplemented as stated in the
contract. No live account event was deleted by this slice.

## Temporal preparation and delivery

The current bounded temporal evidence is in [temporal delivery](TEMPORAL_UPDATE.md).
All host suites pass (418 sync, 798 backend, 133 app; no skips). Physical coverage
includes six real-Core synthetic crypto cases, 66 isolated journal/Provider cases,
39 isolated import/owner cases, 379 migration/ownership/worker regression cases and
eight time/text option UI cases. Controlled UTC service RUN/offline ACK/replay and
ordinary Provider-edit/production-worker RUN/restarted REPLAY also pass, retaining
recognizable synthetic events. APK builds, resource checks and app lint pass.

The production worker fixture changes only target consent and preserves other
scopes' mode flags. It allows ordinary shared background processing under existing
consent and does not establish global silence, exclusive observer causality or an
actual calendar-editor/OEM journey. Current native projection nonces may advance
on an unchanged incoming observation; immutable historical proof is checked in SQL.
The all-day extension adds eight physical isolated executions: two real-Core time
cases, four text crypto regressions and two Provider matrix methods containing eleven
fixtures. Seven guarded live profiles pass RUN/offline ACK/restarted REPLAY (21
executions), including all-day UTC/exclusive metadata, Paris spring/autumn, New York
spring, Lord Howe autumn and Kathmandu. Gap/fold refusal and further date-crossing
zones have separate JVM/native evidence. The exact profile table and capability
boundaries are in the temporal contract. All seven profiles also pass production
Provider/worker RUN and fresh-process REPLAY (14 executions), with exact mode
restoration, same-row acknowledgement and immutable history without another attempt.
These fixtures move timed bounds by one hour or all-day dates by one civil day;
synthetic service events remain available for inspection. Final app/test APK builds,
eight-language resource checks and lint pass (zero errors, 42 warnings). Kind/zone
transitions and other editor/OEM profiles remain unqualified.

Actual date/time editor qualification uses `timeEditor=true` with explicit
`editorPackage=foundation.e.calendar`. Version 1.0.47 on Android API 35 passed
four RUN/fresh REPLAY pairs: single/multiple all-day dates and Paris 25-hour autumn /
23-hour spring events. Twelve additional guarded source executions use new fixture
identities, retaining the earlier Provider-only history. Before Save, the UI driver
checks exact dates/times, calendar and unchanged text. After launch the fixture
only observes production workers/proofs; there is no direct Provider edit or
manual outgoing call. Same row, exact signed contents/metadata, restored modes and
immutable replay without another attempt all passed. See the temporal contract
for literal dates and all-day inclusive-display/exclusive-storage expectations.
An initial preflight ordering failure was reproduced and corrected in the fixture:
ordinary incoming recovery finishes before the full audit, while outgoing uncertainty
is still rejected immediately. Final test-APK build, lint and independent review pass.
Runtime synchronization code is unchanged. Prior evidence below retains its own
original scope and limitations.


Use JDK 17 or a compatible newer JDK, Android SDK platform 36 and the checked-in
Gradle wrapper. Set the SDK path in ignored `local.properties` or ANDROID_HOME.
Install neither an emulator nor a device-specific environment as part of a build.

The reusable [acceptance plan](ACCEPTANCE.md) defines expected outcomes by boundary.

The [internal title UPDATE experiment](TITLE_UPDATE_SUBMISSION.md#repeatable-verification)
has separate domain, SQLite, Core/loopback and explicitly authorized live recipes.
Its isolated journal suite passed fourteen physical cases without skips. A single
title update of a retained simple timed event qualified full signed/decrypted
readback after process restart, with UNKNOWN work and the protected preparation
unchanged and no further dispatch admission. Separate internal native ACK paths
cover ordinary and transferred CREATE title results using isolated Provider/SQLite
fixtures; the experiment adds no production UPDATE worker. The server edit-clock rule is a measured, bounded
profile; it is not a conditional-write guarantee.

Experimental system CREATE qualification covers `SystemCreateWorkStoreDeviceTest`,
`SystemCreatePipelineDeviceTest`, `SystemImportedChangeResolutionDeviceTest`,
`LocalCreateProviderDeviceTest` and `CapturedCreateDispatchCoordinatorDeviceTest`.
Run these isolated tests with `providerFixture=true`. Their physical fixtures
verify offline capture, restart identity, uncertain readback without resend,
mid-send edits, final-claim mode changes, inactive-mirror access restriction,
pending visibility and canonical imported-event IDs. Preserve the strict negative
cases for reminders, guests and unsupported metadata.

`CalendarSystemCreateLiveDeviceTest` is a separate authorized-account journey.
Its opt-in `calendarSystemCreate=true` requires a frozen token, account verification
hash and whole-second start. `systemCreatePhase=LAUNCH` freezes the original scope
and opens one public system-editor insertion; never repeat a frozen launch.
Save the synthetic example without reminders. `VERIFY` uses the same persisted
fixture and production controller, requiring original-row ACK, released handoff
and non-resendable journal identity. `systemCreateAutomaticOnly=true` uses a
separate fixture and waits for the actual background worker before any direct
controller replay. Retain visible service examples; isolate and remove temporary
Provider fixtures only. A successful editor profile on one client is not proof
of Samsung or another OEM's behavior.

From the project root (use `gradlew.bat` on Windows):

```sh
./gradlew :sync:test :auth:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The first build needs dependency downloads. `--offline` is optional and requires
an already populated cache; it is not a clean-checkout setup command.

JVM tests must cover durable-store contracts, pagination replay/budgets,
permission gating and pending/uncertain write handling as implemented. Fakes
establish orchestration behavior only. Android persistence/provider and lifecycle
claims require the corresponding integration/device tests.

Before release, qualify login, Calendar bootstrap, per-calendar modes, supported
event fields, offline edits, process restart, timezone/all-day/recurrence behavior
and background scheduling on a controlled physical device and Proton test account.
Do not infer live compatibility from compilation or mocks.

Keep run-specific outputs, device/account identities, questions and reviewer
handoffs in ignored local files. Update public acceptance and limitations when
behavior changes, not for each command run.

## Account label and actionable synchronization attention

`ProtonConnectedAccountLabelTest` uses synthetic local Core account records and
a serialized facade with event services that refuse every call. It covers a
unique ready authenticated account, username/email selection, display bounds,
unavailable metadata, fresh reads, logout serialization and cancellation priority.
It does not establish live account or network behavior.
The ten reader cases passed within the complete 676-case backend JVM suite;
the 112 app JVM cases also passed. App and instrumentation APK compilation passed.

The focused presentation suites are `ConnectedAccountUiDeviceTest`,
`CalendarDashboardUiDeviceTest`, `AuthenticationUiDeviceTest` and
`CalendeerScreenDeviceTest`. Their 35 cases use synthetic states, including eight
new cases for the account label, explicit retry, explanation, unchanged consent,
busy/permission/selection gates and disappearance on cleanup or sign-out.
Compile the app and instrumentation APK with `:app:assembleDebug` and
`:app:assembleDebugAndroidTest`, then run those suites on an authorized physical
device. All 35 cases passed on a physical device without skips. A separate
real-session settings check confirmed the displayed enrolled account name, and
the explicit retry returned the real application to active synchronization.
These checks do not establish every language, OEM or correction of the underlying
failure; an attention flag can reappear after that work finishes.
The aggregate attention flag cannot identify the cause of an individual failure.
Retry uses the existing worker path and never resets uncertain requests.
`CalendeerSync` warning logs identify the reporting branch using closed stage
codes only, without exception messages, account identifiers or event contents.
They do not identify an individual event or prove the underlying cause.
The 17 isolated `SystemCreatePipelineDeviceTest` cases also passed after this
diagnostic change, retaining cancellation, consent and uncertain-outcome behavior.

## Guarded description/location delivery

`DirtyProjectionRefreshDeviceTest` exercises eight isolated real-SQL cases with
controlled remote and native ports. A missing checkpoint must be restored before
an interrupted incoming projection reaches a dirty row. The complete case then
captures the edit, retains its first text request, acknowledges its verified
result and converges the next import. Other cases cover the four-page budget,
offline absence versus a retained empty page, divergent remote content, receive
and decode failures, and cancellation after the received page is durable. Failures
retain the original receipt and raw edit without native writes. All eight cases
passed on a physical device without skips; this controlled fixture does not
qualify service compatibility or a system editor. The bootstrap is a separate
suspending helper; cancellation remains observable in the calling child job.

Run the portable policy, guarded adapter and host orchestration tests with
`:sync:test :proton-auth:testDebugUnitTest :app:testDebugUnitTest`. The text suites
cover full raw identity, first capsule/wire retention, uncertain outcomes,
revocation, cancellation and offline proof recovery. Host JSON tests use a
test-only implementation and do not qualify Android storage semantics.

On an authorized physical test device, select these isolated instrumented suites
with `providerFixture=true`: `TextUpdateProofStoreDeviceTest`,
`TextUpdateWorkPageDeviceTest`, `TextUpdateAcknowledgementProviderDeviceTest`,
`TextLocalPreparationStoreDeviceTest`, `TextUpdateAcknowledgementStoreDeviceTest`
and `SystemTextOwnerGatewayDeviceTest`. They cover additive migration and rollback,
complete history validation, bounded BLOB reads, cross-family ownership, atomic
raw-to-readback acknowledgement, explicit CREATE provenance and interrupted
restoration. The gateway uses a synthetic transport; it makes no service or crypto
compatibility claim. Its barrier-controlled concurrency cases check the
import-before-auth lock order and revocation while waiting.
Its twelve cases include completion through an interrupted redundant incoming
receipt, plus recovery after the native write succeeded but its reply was lost.
The original receipt is removed only after the full verified text result qualifies.

`SystemTextRecoveryDeviceTest` adds eight real-SQL/controller cases with a
controlled native boundary and forbidden service calls. They verify that durable
proof recovery precedes discovery, traverses separate bounded cursors, survives
new instances and respects account, permission, cancellation and original policy
leases. Recovery cannot repeat a native write or turn uncertain work into a new
submission. This suite passed on a physical device without skips; it does not
establish Android Provider or Proton service behavior.

`TextModesStoreDeviceTest` and `SystemTextModeUiDeviceTest` separately check
default-off scope-bound consent, independent title/text options, permission and
selection gating, logout and the closed feature gate. Keep the existing title
Provider/CREATE-handoff suites as regression coverage. Require the expected test
count with no failures, errors or skipped cases; a skipped opt-in suite is not a
qualification. Isolated Provider rows, accounts, preferences and databases are
removed by their fixtures. These suites do not prove process death, power loss,
an OEM editor, background scheduling or a Proton server round trip.

`CalendarTextGuardedJourneyLiveDeviceTest` is a separate explicitly authorized
service fixture. It requires `calendarTextGuardedJourney=true`, a frozen
`textFixtureToken`, an enrolled `textAccountHash` and `textGuardedPhase=submit`
followed by `recover` in a distinct process. It uses the uniquely selected dedicated
QA calendar, real authenticated ports and an isolated native generation. Recovery
has sentinels that forbid fixture-path preparation, submission and inspection;
it does not claim the entire application has no network traffic. A repeated
recovery checks immutable history and the later local owner. Never renew the token
to retry an uncertain request. The timed combined multiline service journey is
qualified separately from automatic scheduling and an OEM editor.

`CalendarSystemTextWorkerLiveDeviceTest` separately qualifies the production
pipeline with `calendarSystemTextWorker=true`, frozen `textFixtureToken` and
`textAccountHash`, and `textWorkerPhase=RUN` followed by `REPLAY` in a new process.
`RUN` opens the retained ordinary timed service example in the selected system
editor; save only its enrolled synthetic description and location. After launch
the fixture observes actual workers, with no direct outgoing controller calls.
It requires full request/result/completion binding, same-row acknowledgement,
successful network work and quiescent queues. `REPLAY` reactivates the same policy
and compares the complete immutable history, not just displayed text.
`RECOVER` is reserved for the exact previously enrolled unfinished editor change;
it does not create a replacement identity or claim a fresh editor journey.
The optional `isolateOtherModes=true` requires immutable local restoration
provenance before changing flags. All three original flags on the target and
other calendars must be restored; on process interruption inspect that provenance
before another live run. Never discard it to bypass enrollment guards.

The ordinary timed system-editor RUN and active-worker REPLAY passed in distinct
processes, as did interrupted-edit RECOVER and its separate REPLAY. The tested
editor is `foundation.e.calendar`. These cases do not qualify Samsung, all-day or
transferred CREATE service text delivery, reboot/power loss or the periodic worker.
The production text option is now enabled, experimental and off by default.
Retain visible synthetic service events when performing authorized live tests;
do not infer service success from opaque fixture strings or a local completion.
The known GET/PUT concurrency window remains, as documented in
[shared text preparation](SHARED_TEXT_PREPARATION.md).

## Retained change metadata pagination

`RetainedLocalChangePageTest` covers page/limit/cursor contracts, duplicate refusal,
strict UTF-8 identity bounds, ownership, frozen lists and redacted diagnostics.
The domain preserves the store's order; it does not compare opaque event IDs
using JVM UTF-16 string ordering.

`RetainedLocalChangePageDeviceTest` uses isolated real SQLite stores and controlled
account/permission ports. It checks fifty-row pages, exact/empty boundaries,
account/calendar/generation isolation, SQLite BINARY Unicode ordering and bound
SQL parameters, cursor reopening, mutable traversal, both kinds/states and exact
routing into the existing historical inspection entries. Unreadable snapshots
prove discovery does not decode captured contents or grant content qualification.
First-row/look-ahead corruption, oversized/control identities, malformed UTF-8
TEXT bytes and non-TEXT SQL storage classes refuse complete pages.
Session/policy/reconnect/cancellation
fixtures verify initial/final gates and lease closure. Complete typed SQL snapshots
compare before/after reads; scheduler counters stay unchanged except when a
controlled reconnection intentionally schedules normal startup recovery.
No Provider or service contents are accessed by these fixtures.

The BLOB diagnostic test failed before explicit SQL type checks because the
store returned a platform conversion exception instead of the constant metadata
refusal. On the qualified Android device, conversion already failed; no paging
loop was observed. This distinction avoids claiming a device defect from a
theoretical SQLite storage-class ordering risk. UPDATE/DELETE submission, automatic
capture, a UI issue list and a frozen cross-page snapshot remain unqualified.

## Debug-only read-only event version inspection

`ProtonEventVersionProbeTest` covers descriptive header/number categories,
bounded strict UTF-8 parsing, exact event/calendar envelopes, body closure,
cancellation and account/session refusal. `ProtonCoreSingleDispatchTest` also
checks metadata access through the published Core backend with synthetic sessions
and loopback HTTP. Only a GET reaches the fixture, with no conditional headers.
These fixtures establish local transport behavior, not server preconditions.

`CalendarEventVersionLiveDeviceTest` is opt-in via
`calendarEventVersionInspection=true`, `createFixtureToken` and `createAccountHash`.
It requires the separately authorized retained known-empty CREATE fixture, its
existing uncertain journal and selected calendar; it cannot submit a new event.
Authorize the exact test identity before any event reads. Execute the whole
installation/test/restoration journey under the applicable device lock.

The test obtains authenticated readback before/after the metadata GET, compares
the decoded event, SQL projected baseline and UPDATE/DELETE intentions, uncertain
CREATE record, selections/access and Provider ownership/revision/dirty/deleted
witness. It does not snapshot every table, checkpoint or Provider field. Output
contains enum categories and boolean witnesses only; keep all fixture/run inputs
and logs private. Retain the synthetic event and restore the normal app screen.
On the qualified response ETag/Last-Modified were absent and ModifyTime/CreateTime
were non-negative integers. Production UPDATE/DELETE remain unavailable.

## Targeted configuration tests on an authorized device

Build the instrumentation APK with `:app:assembleDebugAndroidTest`. Install the
debug app and test APKs on the explicitly selected physical target, then run:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.patmanak.proton.andcalendarbridge.PreferencesDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

Replace `DEVICE_SERIAL` locally; never put actual device identifiers in project
files. These tests change only the bridge's theme/language preferences and restore
the original selections. They cover preference-store recreation, the available
platform locale path and activity launch/recreation under the configured package.
They do not establish process-death durability, UI translation quality, Calendar
Provider behavior, Proton authentication or calendar synchronization.

## Targeted authentication UI tests

Run `com.patmanak.proton.andcalendarbridge.AuthenticationUiDeviceTest` using the
same instrumentation command above. These tests inject a sanitized fake port and
exercise credential clearing, masking/capture protection, second-factor navigation,
cleanup failure, retry delay and the separate authenticated-identity display.
They do not load hosted verification or submit credentials to Proton.

`com.patmanak.proton.andcalendarbridge.AuthenticationStartupDeviceTest` uses the
production composition and waits for restoration to finish without a closed
failure. It also recreates the activity and verifies the controller survives.
It never submits credentials or clears a retained account. On an empty app store,
this checks initial Keystore/Room composition, not authenticated-session durability.

The `proton-auth` JVM suite exercises adapter lifecycle, server-proof delegation,
challenge policy and cleanup using controlled fixtures. Its Room schema is exported
under `proton-auth/schemas`. Qualify Keystore/Room persistence across real process
restart, refresh/revocation and a live hosted challenge separately on an authorized
test account. Do not include real credentials in instrumentation arguments, command
lines, fixtures, reports or source files.

## Calendar metadata discovery

The JVM catalog tests cover complete/empty/error results, response bounds,
malformed data, authentication admission, session replacement, refresh retry
delays and late results after sign-out. Run `CalendarCatalogUiDeviceTest` with
the instrumentation command above for synthetic UI state and refresh checks.

For a deliberate read-only live check, retain an already connected test account
and run only this class, opting in explicitly:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e calendarLiveCatalog true \
  -e class com.patmanak.proton.andcalendarbridge.CalendarCatalogLiveDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

It verifies session restoration and an actual complete metadata response, reporting
only the calendar count and closed failure category. An empty response is valid.
It never submits credentials, signs out, creates calendars or changes account
attributes. Install updates with retained app data; do not reset the account to
repeat the check. Passing establishes metadata access, not event decryption,
calendar permissions, Provider integration or synchronization.

## Initial read-only import tests

`ImportStoreDeviceTest` uses isolated temporary databases to check page/checkpoint
rollback, reopen, stale acknowledgements, refresh/reprojection, lease invalidation
and rejected foreign scopes/outgoing writes. `CalendarImportUiDeviceTest` uses
synthetic states and never accesses the account or Calendar Provider.

On an authorized test device, `CalendarPermissionFlowDeviceTest` requires
`-e calendarPermissionFlow true` and initially missing calendar grants. It tests
decline then allow using the real system dialog; it never grants through ADB.
An already granted permission skips that journey and must not be reported as a
new refusal/approval qualification.

`CalendarProviderDeviceTest` requires `-e providerFixture true` and existing grants.
It creates uniquely identified synthetic local calendars/events and removes only
its own fixtures. It verifies replay after a new adapter instance, updates,
scoped deletion, all-day UTC/exclusive-end mapping and read-only calendar access.
The event-color fixture verifies override changes and removal, resulting display
color and stable row identity. The storage fixture checks color persistence and
acknowledgement of older cached payloads without the optional field.
Its refusal fixture simulates denied access; real in-flight OS revocation remains
a separate device journey.

`ProtonEventCryptoDeviceTest` runs a debug-only native cryptographic fixture using
fresh in-memory keys. It exercises the production decryptor and rejects altered
signatures, without opening the account database, contacting Proton or projecting
events. It is not evidence of every real account's key configuration.

`ProtonSimpleEventPreparationTest` exercises offline simple CREATE partition,
escaping/folding, stable supplied UID/timestamp, literal DST/timezone output and
exclusive all-day dates under different default timezones. It checks refusal of
unsupported fields, ambiguous local times, subsecond precision, historical dates,
invalid Unicode and unsuitable keys, plus sanitized crypto errors. Run it with
`:proton-auth:testDebugUnitTest --tests '*ProtonSimpleEventPreparationTest'`.

`ProtonEventPreparationCryptoDeviceTest` exercises the production preparer with
fresh synthetic member/calendar keys. It signs clear metadata and encrypts/signs
display text, using Core `signText` and the existing reader's `verifyData`. Timed
and all-day cases round-trip; separate alterations to each signature must yield
no accepted change. The probe fabricates author resolution and a read-response
shape explicitly; its code has no transport or account/Provider access. This
qualifies native preparation and local verification, not service acceptance, real member
selection, UID idempotency, outgoing intent durability or safe UPDATE/DELETE.

The live pipeline test is intentionally opt-in and requires a retained test session,
one calendar and granted calendar access:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e calendarLiveImport true \
  -e class com.patmanak.proton.andcalendarbridge.CalendarImportLiveDeviceTest \
  com.patmanak.proton.andcalendarbridge.test/androidx.test.runner.AndroidJUnitRunner
```

This test enables that calendar's persistent read-only mirror and retains the
requested import. It waits for a newly completed traversal, inspects only its own
Provider account, and reports counts without titles/IDs, including the number of
events with a custom color. These counts do not verify visual rendering in a
calendar app. It performs no Proton mutation. A nonzero count exercises real
event decryption; zero only establishes
the empty traversal and key bootstrap. Repeat after retained-data process restart
to qualify stable identities. Record OEM/client UI visibility separately: Provider
success alone does not prove every calendar app supports the mirror.

`CalendarMultiCalendarWorkerLiveDeviceTest` qualifies an unchanged refresh with
at least two enabled, accessible, already imported calendars and one paused
calendar in the same retained test account. Opt in with `calendarMultiWorker=true`.
It waits for the existing unique network queue to finish, calls the application's
`requestSync()`, and observes new production network work and its continuations
until they succeed. It never calls the importer or remote adapter directly.
It then checks complete coverage, drained baseline/delta/projection work, retained
authentication, distinct read-only Provider mirrors, and unchanged Provider event
identities/revisions and paused state. Counts and booleans are the only output.
The periodic worker can contribute to convergence; polling for its running state
does not establish its absence between polls. This journey qualifies a refresh
after convergence, not the initial backlog, every event field, natural Doze or a
specific calendar client's UI.

## Staged import recovery

`ProtonEventImportTest` verifies priority-window ordering, full horizon coverage,
explicit pagination, legacy cursor/first-receipt compatibility, frozen timezone
and strict cursor-layout rejection. `ProtonStagedEventImportTest` also traverses
10,000 generated events in bounded pages while rebuilding the adapter and changing
the clock/timezone between requests. Its fixed fixture expects 147 requests and
no page above 100 events. This is a protocol/pagination fixture, not a live server
volume benchmark, heap measurement or a persisted interval-coverage claim.

`StagedImportEngineTest` covers receipt preservation, offline decode admission,
partial-page isolation, atomic application, bounded continuation and stale retries.
`CoverageTrackerTest` and staged-engine cases distinguish receipt from application,
unknown migrated prefixes, changed plans/formats, epoch changes during acquisition
or decoding, transaction rollback and bounded history. Proton receipt fixtures
verify that all four query types and every page precede a period's terminal marker,
and that sealed dates/timezones are authoritative after a restart.
Proton adapter tests cover scope/format/session validation, sealed receipts, local
address-key mapping and refusal of plaintext Keystore fallback.

`StagedImportStoreDeviceTest` uses isolated databases for v1 migration, reopen,
large receipts, atomic rollback, global capacity, scope isolation and retry CAS.
`StagedImportCoordinatorDeviceTest` requires `providerFixture=true`; it uses an
isolated scheduler/store and a generated synthetic Provider account, projects 400
events across bounded drains, verifies completion publication, offline retry and
local-key recovery, then removes exactly its own fixtures. It does not call Proton.

The native crypto probe now rebuilds the decoder and Keystore receipt codec with
network callbacks that fail if invoked. It checks valid/incompatible/valid rows,
unverified signatures, missing keys and independent event budgets. These fixtures
plus database reopen do not establish actual OS process termination or a complete
live airplane-mode journey; record those separately when performed.

`ProtonChangeFeedTest` currently qualifies only protocol parsing/transport contracts
with fabricated responses. These tests alone do not establish live server behavior.

`DeltaImportEngineTest` covers anchor-before-baseline ordering, atomic cursor/job
commit, offline event receipt recovery, missing-event retention, explicit tombstones,
context barriers, stale completions, malformed scopes and bounded continuation.
`DeltaImportStoreDeviceTest` covers additive migrations, durable delta reopening,
scope isolation, exact acknowledgements, storage admission and unavailable catalogs.
`DeltaImportCoordinatorDeviceTest` requires `providerFixture=true` and uses an owned
synthetic Android account with an isolated database and no-op scheduler. It checks
update/delete projection, offline decode, unresolved IDs, access loss, prerequisite
rechecks after retries and rolling scans despite persistent missing IDs.
Reset fixtures additionally cover atomic archive/cursor/scan rollback, schema 1–3
migration to the current schema, reopen, scope/format isolation and archive admission under
the shared staging quota. The coordinator checks that reset-page deletions are
ignored, an empty rescan preserves uncertain copies, new changes retain identity,
offline work does not spin and the history warning prevents a new completion
timestamp. Deferred recent events keep the partial status alongside that warning.
UI fixtures verify the warning through network errors and pause. These controlled
responses do not establish a real server reset or its retention period.
The opt-in live import also requires a persisted, drained calendar delta cursor.
For a fresh scan it also checks finished, fully received/processed interval observations
and an empty known Android update queue, reporting only counts.
It does not create/edit/delete a remote event or establish reset/retention semantics.

`CoverageStoreDeviceTest` uses isolated SQLite databases for schema 4-to-5 migration,
reopen, atomic rollback, receipt metadata and ledger quota accounting, scoped counts
and refresh epochs. Inbox receipts share the retained-byte budget but do not count
as deferred event records; deferred counts remain scoped to their owning calendar.
Older migrations are exercised by the existing store suites.
`CoverageCoordinatorDeviceTest` requires `providerFixture=true`: with an isolated
store/scheduler/account and synthetic remote it receives a page before keys are
available, resumes local processing without a fetch, blocks then drains projection,
and preserves deferred events and the history warning. Its temporary provider rows
and database are cleaned up. UI fixtures distinguish received/processed periods
from projection/deferred work and do not invent coverage for legacy completion.

`RollingImportPlannerTest` checks same-horizon reuse, entering future dates, bounded
multiweek extensions, DST and conservative fallbacks. `ProtonRollingWindowTest`
checks frozen explicit plans through all query types/pages, scoped cursor validation,
legacy compatibility and sealed offline resumption. These are controlled protocol
fixtures; they do not establish live server selection at every date boundary.
`RollingStoreDeviceTest` checks coverage payload migration, observation persistence,
atomic plan/feed admission, exact coverage comparison, lease rollback and access
loss during an unfinished scan. `RollingCoordinatorDeviceTest` uses
`providerFixture=true` and an isolated synthetic remote/database/provider account:
unchanged timed/all-day events enter the next day's horizon, long overlapping events
keep their identity, same-day refresh avoids event-window requests, interrupted
extensions resume their frozen plan, and resets/zone changes/deferred work prevent
unsafe reuse. Temporary fixtures are removed after each test.

Add `rollingRefresh=true` to the opt-in live import to perform a second same-horizon
refresh. It must retain the exact baseline ledger/epoch and drain the change feed.
This qualifies the production reuse path on that account; an actual server journey
across a date boundary remains separate from controlled-clock tests.

`CalendarOfflineRestartDeviceTest` is an opt-in host-orchestrated journey. Run with
`calendarOfflineRestart=true` and `offlinePhase=prepare` on an already connected,
authorized test account with at least one selected calendar and imported events. Stop the
application process, disable networking, then run with `offlinePhase=verify`.
The test checks a different process, no active network, restored authentication,
identical owned Provider contents across all bridge-owned calendars and local
processing without introducing a new blocking error.
Only a digest and counts are retained in private fixture preferences; event data
and identifiers are not included in test diagnostics. Restore the exact original
network settings in a host `finally` block and always run `offlinePhase=cleanup`.
For a physical reboot journey, compare the kernel boot identity before/after,
wait for the first user unlock before launching the app, and keep networking off
through `verify`. Restore settings even if the device remains locked, then defer
fixture cleanup until the encrypted app storage becomes available.
Qualify reconnection separately: the existing live import fixture requires exactly
one selected calendar and cannot be applied unchanged to a multi-calendar account.
No app-data reset or session logout is part of this journey. It proves retained-session/Provider
recovery; it does not prove an interrupted encrypted receipt was decoded after
process death, since no pending receipt is deliberately staged in this fixture.

`CalendarStatusSnapshotDeviceTest` is an opt-in (`calendarStatusSnapshot=true`)
retained-session diagnostic. It reports only aggregate calendar statuses, coverage
and pending-work counts, plus validated connectivity, without calendar/event
identifiers or content. Launching the app may itself trigger normal read-only
synchronization. `awaitSettled=true` waits for any `RUNNING` status to clear;
`requestSync=true` explicitly requests a read-only network refresh if one was
already present. A successful diagnostic run is not proof that every calendar
finished importing; inspect its returned status counts.

`CalendarNetworkStageDeviceTest` is an opt-in (`calendarNetworkStage=true`)
retained-session diagnostic for multiple enabled calendars. It reads the
production adapter's context, one initial event page, and a current delta page,
then probes any retained delta cursors and runs one bounded coordinator
continuation without starting a fresh scan.
It reports only closed stage/failure categories and aggregate durable-state
counts; it never reports identifiers, cursor values or event content. The
coordinator pass can advance an existing read-only import and launch ordinary
local follow-up work. `COMPLETE` means that pass finished, not that every
calendar has reached `IMPORTED`. The probe does not replace a full network
Worker or server-interoperability journey.

`CalendarDozeSchedulingDeviceTest` is an opt-in, host-orchestrated Worker
journey (`calendarDozeScheduling=true`, phases `prepare`, `verify`, `cleanup`).
`prepare` queues the production `CalendarImportWorker` with a 45-second initial
delay. The default variant has no network input; `dozeNetwork=true` adds the
production network input and connected-network constraint, requires at least two
completed accessible imports, and uses a separate fixture name and preferences.
The host confirms deep idle is enabled and initially inactive, forces deep idle,
then reads the exact prepared WorkManager ID without starting the app. Require
`ENQUEUED` and zero recorded execution attempts after the observation interval;
always call `unforce` in a `finally` block. `verify` waits up to three minutes for
`SUCCEEDED`. The network variant additionally waits for the normal network queue
to settle and checks the retained enabled calendar scopes, authentication,
complete coverage and drained durable baseline/delta/projection state.
`cleanup` cancels only the chosen fixture work and clears its private preferences.
Record the observed WorkManager state, attempt count and elapsed wait. Normal
production continuations or startup work can contribute to final convergence;
verification starts the application process, so completion without reopening it
requires a separate host observation.
Forced idle on one device does not establish natural Doze timing, six-hour
periodic delivery, an interrupted initial backlog or another OEM's scheduling
policy.

`CalendarRemoteMutationDeviceTest` observes a disposable event created, modified
and deleted through the official Proton client in an authorized test account.
It never uploads or directly edits Provider rows. Use `calendarRemoteMutation=true`,
a unique eight-hex-character `fixtureToken`, and `mutationPhase=baseline` before
creating `BridgeQA-<token>-initial`. Run `created` after saving it in Proton, then
rename it to `BridgeQA-<token>-updated`, change its event color and run `updated`.
Delete only that fixture in Proton and run `deleted`, followed by `cleanup`.
The observer checks one owned read-only mirror, the same Provider event ID through
the update, a changed displayed color, fixture removal and unchanged contents/IDs
of every other mirrored event. Private digests stay in app-private preferences;
only counts and booleans appear in instrumentation output. Cleanup removes those
preferences only: the host remains responsible for deleting its remote fixture,
including after a failed test. Each phase uses a bounded import and requires the
calendar change queue to drain. This qualifies Proton-to-Android behavior; it does
not qualify uploads from the bridge, concurrency guarantees or all event features.

## Recurrence validation

`ProtonRecurrenceTest` exercises Biweekly parsing of rules, typed dates, UTC/TZID,
moved/cancelled exceptions and explicit unsupported forms. Native interpretation
is a separate boundary: `RecurrenceProviderDeviceTest` queries actual bounded
`CalendarContract.Instances` for daily, weekly/DST, monthly and yearly rules,
RDATE-only sets, EXDATE, moved and cancelled occurrences, multi-day events,
type transitions and scoped cascading deletion. It also checks stable row identity,
replay, ambiguous parent rejection and calendar recreation even when numeric IDs
are recycled. It requires `providerFixture=true` and cleans its isolated account.

Converted-occurrence fixtures prime the native expansion cache before replacing
a slot across the Paris daylight-saving boundary. Fixed UTC expectations check
both timed-to-all-day and all-day-to-timed changes, including native event IDs,
endpoints and `ALL_DAY`. Disjoint original/destination windows and a combined
window must expose the replacement exactly once with no original slot left over.
Cancellation must remove both slots; ordinary child deletion must restore only
the original series. Reconversion must keep the child ID, invalidate the former
display slot and refuse the old historical receipt while accepting the new one.
These are isolated native fixtures, not Proton or calendar-editor tests.

A separate characterization seeds native legacy state directly, bypassing the
production admission guards. It retains a known defect rather than declaring
correct fold handling: a Paris master stores `2030-10-27T00:30:00Z`, but its first native
instance begins at `01:30:00Z`. Fixed expectations verify that the shifted master
slot survives an exception targeting the stored original instant, with matching
or changed display type, moved or cancelled. Android expansion is not fixed.
Converted-slot qualification uses unambiguous original times and does not close
this discrepancy.
`ProtonRecurrenceTest` rejects an ambiguous final merged RRULE start and admits
unambiguous boundary times, UTC, all-day and unchanged profiles without a rule.
`ProtonStagedEventImportTest` joins the real parser to controlled plaintext decoding
and receipt handling: valid surrounding rows proceed, the unsupported source is
retained and offline replay/retry uses no network. This is not cryptographic evidence.
Native admission fixtures reject both fold instants on master insertion/replacement,
ordinary and tracked, without changing cached instances or historical proofs.
New and replacement moved/cancelled exceptions also refuse a seeded ambiguous
legacy parent. Owner/SQLite reopening retains the rejected preparation, intention,
previous baseline and native details. A historical proof remains valid when its
previously non-ambiguous native row becomes ambiguous. Receipts whose own historical
payload already had an ambiguous start are not directly exercised here.
The parent's start/zone/rule/type are reasserted in the exception batch, but no new
fixture injects a change between that read and the batch. General fold/gap behavior
and repair of older copies remain unqualified.
All 39 selected parser/staged JVM cases and 64 Provider/recovery cases passed,
without skipped tests; native qualification used the authorized physical target.
The native total includes the explicit legacy defect characterization, not a fix
to Android's expansion behavior.

Later-transition characterization measures daily Paris 02:30 rules with an
unambiguous start and `COUNT=3`. The autumn expectation is October 26 `00:30Z`,
October 27 `00:30Z`, October 28 `01:30Z`; native expansion chooses `01:30Z` on
October 27. The spring expectation is March 30 `01:30Z`, March 31 `01:30Z`, April 1
`00:30Z`; native expansion instead retains `01:30Z` on April 1, drifting to 03:30
local time. Dates are in 2030 and each duration is one absolute hour. Expectations
follow [RFC 5545 sections 3.3.5 and 3.3.10](https://www.rfc-editor.org/rfc/rfc5545)
with [verified erratum 4271, reproduced in the informative inline rendering](https://www.rfc-editor.org/rfc/inline-errata/rfc5545.html):
the repeated hour selects the first occurrence; a nonexistent local occurrence
uses the preceding offset and consumes the count. It is not skipped.
Initial semantic assertions failed before any moved/cancelled child was applied;
those child scenarios are not qualified by this experiment.

Five explicit UTC RDATE-only sets are measured under both `Europe/Paris` and
`UTC`. Under Paris, the three sets requesting the early October 27 instant fail:
it shifts to `01:30Z`, merging with the late instant when both were requested and
creating the late instant even when only the early one was requested. A late-only
set and the spring boundary set remain exact. Under UTC, all five sets remain
exact, including both repeated-hour instants. Assertions retain the full stored
`DTSTART`, literal UTC `RDATE` list and timezone, native event ID, absolute one-hour
endpoints, timed display and one master row. Repeated expansion queries and fresh
receipt inspection retain the observed results.

The final 23 recurrence tests pass without skips, including three new explicit
defect characterizations. The initial semantic run failed those three tests; a
separate diagnostic measured all ten timezone/set combinations before fixing
the characterization expectations to the observed behavior. Passing these tests
does not fix the defects. The UTC comparison constructs UTC domain fixtures; it
does not establish a production mapping that retains the original Paris domain
timezone. Exclusions, moved/cancelled RDATE children, owner/SQLite lost-ACK recovery,
dirty-row migration, calendar-editor behavior and live Proton are not qualified
by those initial characterization fixtures. APK/test compilation and lint pass
with zero errors and 20 existing warnings. That initial characterization changed no production mapping
or admission policy. The legacy Paris comparison now explicitly seeds the old
native timezone, bypassing the current RDATE-only mapper.
Historical receipts establish an applied batch, not correct native expansion.

Timed RDATE-only master projection now uses native UTC while the domain event and
receipt retain the original Paris timezone. A physical semantic assertion failed
before this change because the early repeated-hour slot disappeared. The same
assertion now requires both original instants with exact event IDs and endpoints.
Five Paris date sets exercise no exclusion and each individual date exclusion,
including either branch of the repeated hour and exclusion of DTSTART. Replay
retains one master row and its ID. Both native timezone columns must equal UTC.

Exceptions targeting either repeated-hour instant are moved or cancelled with
timed or all-day display. Tests prime expansion, query separate origin/destination
and combined windows, reapply child and master without duplication, retain parent
and child IDs, and restore the exact original set after ordinary child deletion.
Legacy clean master reapplication with a linked child adopts UTC without changing
those identities; dirty and deleted masters refuse reapplication and retain their
Paris timezone, flags, cached instances and historical proof.

Owner/SQLite lost-ACK fixtures retain the Paris domain baseline and recover a new
UTC projection without repeating a write or overwriting a later local title edit.
The same controlled reopening recovers a seeded legacy Paris receipt without
repairing its incorrect expansion: historical proof remains distinct from native
fidelity. Rule-based expansion is unchanged, including the separately characterized
later fold/gap defects. No live Proton or calendar-editor behavior is established
by these isolated fixtures.

All 71 selected native cases pass without skips: 26 recurrence projection and
45 owner/SQLite recovery tests. This includes the unchanged rule defect
characterizations, not fixes for those rules. APK/test compilation and lint pass
with zero errors and 20 existing warnings.

`RecurringLocalTimeRiskTest` checks the bounded daily admission policy separately
from native expansion. It detects later Paris fold/gap slots while admitting
ordinary hours, fixed offsets, COUNT=1, a last slot before the transition and an
interval skipping it. Tests distinguish the 4096 limit from unscanned larger or
complex profiles, preserve the initial ambiguity check for every rule, and exercise
field ordering, duplicate-field nonqualification, long arithmetic and date overflow.
Each candidate derives from the original local start, not a normalized predecessor.
This detector is not a recurrence validator; a false result does not establish
native compatibility for profiles outside the scan.
RDATE additions to an RRULE are not expanded or qualified by this detector.

Parser fixtures reject final merged daily profiles reaching later fold/gap slots,
including a gap excluded by EXDATE, while safe short/interval profiles remain
admitted. The staged decoder joins this parser to controlled plaintext fixtures:
surrounding valid records proceed, the sealed unsupported source is retained, and
offline replay/retry makes no network calls. It does not qualify cryptography.
Native fixtures refuse ordinary/tracked insertions and replacements of affected
masters and moved/cancelled exceptions against legacy parents, with timed/all-day
display and existing/new children. Row identity/counts, cached instances and
historical proofs remain intact. Owner/SQLite reopening retains the refused
intention, preparation and previous baseline without rewriting native details.
Later fold/gap characterization explicitly seeds legacy rows, bypassing admission;
it still demonstrates the underlying Android defects rather than their repair.
All 48 selected JVM tests and 74 native tests pass without skips: 6 risk-policy,
20 parser, 15 staged and 7 iCalendar JVM cases; 28 native recurrence projection
and 46 owner/SQLite recovery cases. APK/test compilation and lint pass with
zero errors and 20 existing warnings. No live Proton write is part of these tests.

### Android recurrence source replay

The [pinned source audit](research/ANDROID_RECURRENCE.md) records the inspected
processor, time implementation, libcore and Provider paths. To reproduce its
arithmetic independently, compile the unchanged pinned `RecurrenceProcessor`,
`Time`, `EventRecurrence` and `DateException` with a minimal recurrence carrier
whose fields have the upstream types. Supply already-parsed rules and explicit
epoch arrays. This deliberately excludes upstream serialization and Provider
caching; it is not a replacement for the instrumentation fixtures above.

Run the same synthetic inputs on the host and, after compiling to DEX, on an
authorized Android runtime. Compare epoch starts, not formatted local strings.
Cover safe daily times, later fold/gap, ambiguous DTSTART, both passages in an
explicit date set, exclusion of only one passage, native Paris versus UTC, and
explicit `BYHOUR=2` on spring/autumn rules. Keep source artifacts and execution
logs private and remove the temporary device artifact after the journey.
Changing process-local defaults does not require changing the device timezone.

Ten Android source-replay cases match the audit's exact arithmetic expectations.
Nine host cases match; the simple spring rule differs on OpenJDK 21 and its failed
comparison is retained. These are source-characterization results, including
incorrect iCalendar outcomes, not ten new production-correctness tests. No app
installation, Provider/calendar access or Proton transport is needed for replay.
The audit changes documentation only; application builds and the previously
qualified production test suites were not rerun for that documentation change.

### Debug-only finite daily recurrence experiment

`FiniteDailyRecurrenceExperimentTest` has nine JVM cases for independent Paris
fold/gap dates, a half-hour gap, exact COUNT and interval behavior, unchanged
exclusions, experiment bounds, malformed/unsupported inputs and immutable output.
Run `:app:testDebugUnitTest --tests '*FiniteDailyRecurrenceExperimentTest'`.
The initial placeholder failed six positive cases. Two review findings also failed
before correction: a generated local year outside the bound despite a valid UTC
year, and aliasing a caller's empty additions list in the canonical snapshot.
The final nine cases have no failures, errors or skips.

Three additional `RecurrenceProviderDeviceTest` methods check projected synthetic
date views: exact starts/one-hour duration and EXDATE (including first/all slots
and a different fold instant); moved/cancelled timed/all-day exceptions and
reconversion after expansion; cached COUNT=3-to-4 replacement retaining parent
and child IDs; and ordinary/tracked dirty or deleted replacement refusals retaining
the previous receipt. The temporal source and canonical rule stay separate.

`finiteDailyDateViewRecoversLostAcknowledgementWithoutClaimingCanonicalRuleProof`
in `ProjectionRecoveryDeviceTest` uses the production SQLite/Provider/coordinator
with a controlled remote, a synthetic date-view input and a lost private ACK.
It checks two-hour instance duration, reminders/participants, reopening without
a repeated write, and preservation of a subsequent dirty local title. Its baseline
is explicitly the RDATE view. The native marker identifies the historical write
by scope, identities, revision and nonce; it does not authenticate recurrence
content. Copying that receipt to the canonical RRULE can pass native inspection,
but SQLite restoration must reject the changed receipt and retain the original
preparation, baseline and pending view. This is not canonical RRULE
storage/recovery support. The first physical run exposed the test's incorrect
assumption that native inspection alone would reject copied content; the test
now checks the full persisted-receipt comparison instead.
The fixture's original autumn query defaults remain unchanged; this test supplies
spring/autumn windows explicitly.

Native tests are opt-in via `providerFixture=true` and require an authorized
physical target. The four new methods pass without failures or skips on the
qualified physical target. Both complete modified classes also pass: 31 recurrence
and 47 recovery methods, 78 total, without failures or skips. Run with
`-e providerFixture true -e class` selecting `RecurrenceProviderDeviceTest` and
`ProjectionRecoveryDeviceTest`; the fixtures use isolated Provider/SQLite data
and a controlled remote, without Proton service writes.
APK/test compilation and lint pass with zero errors and
20 existing warnings. `:app:compileReleaseSources` also passes; the fixture entry
point remains debug-only and its unit tests belong to `testDebug`. The extracted
pure calculator is now in the main `sync` source set, as described below.
This is source compilation, not release APK or distribution qualification.
There are no new production callers, adapter capabilities,
schema changes or enabled RRULE materialization. No Proton transport or calendar
editor is part of these fixtures; broader timezone/OEM/UI and cost qualification
remain separate.

### Pure versioned projection reference

`ProjectedEventReferenceTest` has 13 cases for direct and finite representations,
exact spring/autumn instants, canonical field preservation, first/all exclusions,
unknown details, immutable recurrence/reminder/participant/date snapshots,
stable equality/hash after caller mutation, invalid aliases, unsupported finite
profiles, changed exception display types, redacted diagnostics and historical
restoration without timezone recalculation. Stored count/start/order/precision
and bounds are checked; duplicate saved instants from the whole-day Apia gap
remain intact. An initial reference stub failed seven of nine cases; a later
historical-restore placeholder failed three of 13 before implementation.
Run `:sync:test`. All 215 sync cases pass without failures, errors or skips,
including receipt binding of exact current/previous canonical representations.

The debug calculator fixture delegates to `FiniteDailyRecurrenceProjection` in
`sync`. Run `:app:testDebugUnitTest --tests '*FiniteDailyRecurrenceExperimentTest'`:
all nine cases pass after extraction. Debug app/test APK builds, release source
compilation and lint pass; lint retains zero errors and 20 existing warnings.
The four targeted physical recurrence/recovery methods also pass again after
extraction, without failures or skips. They still persist synthetic date views,
not the new reference model. The earlier 78-method native regression result
remains the qualification of the unchanged production Provider/recovery paths.
The shared calculator/model compile with main sources; only the fixture entry
point is debug-only. SQL preparation/decoding now uses the reference as described
below; no runtime parser/coordinator selects the finite calculator, and the
Provider refuses finite metadata. Pure checks alone do not establish native
canonical-rule recovery or activation.

### Stored projection representations

`ProjectionRepresentationStoreDeviceTest` has six isolated methods for schema
14-to-15 migration with byte-identical source payloads/nonces, saved-date reloading,
canonical baseline plus representation retention after acknowledgement, newer
pending source preservation, complete previous-reference comparison, copied
metadata refusal, finite-to-legacy direct replacement, malformed/unknown formats,
untracked acknowledgement refusal and the disabled native finite boundary.
Saved instants intentionally differ from today's calculation in one fixture to
prove that reopening does not expand again; structural decoding does not verify
their original geometry. SQL restore uses synthetic applied witnesses, never a
claim of a successful finite Provider write.

Two additional `CapturedHandoffStoreDeviceTest` methods refuse unknown/explicit
metadata on the captured base and on released bases/receipts, retaining complete
raw table snapshots. Historical six-field handoff receipts cannot discard added
metadata. All eight new physical methods pass without failures or skips. The
18 affected storage, migration, native recovery and handoff classes also pass
all 373 methods without failures or skips. The first regression exposed one
historical test insertion that supplied eight unnamed values after the receipt
table gained two columns; naming its historical columns fixed the fixture.
That method passed independently before the complete regression passed again.
Run the store class and the two targeted handoff
methods with `-e providerFixture true` on an authorized physical device.

`:app:testDebugUnitTest` passes all 19 cases, including the nine debug calculator
cases. Debug app/test compilation, release source compilation and lint pass;
lint has zero errors and 20 existing warnings. The new schema retains legacy NULL
metadata and rejects unknown formats without clearing them. Production ordinary
imports still prepare direct receipts; finite native application, raw-source
eligibility, downgrade and arbitrary power-loss recovery remain unqualified.

### Direct projection admission before native operations

Seven additional `ProjectionRecoveryDeviceTest` methods exercise the production
coordinator/store and isolated real Calendar Provider rows. Finite SQL baselines
are seeded with synthetic witnesses while the native event stays direct; these
fixtures qualify refusal, never a finite native write. They cover tracked and
protocol-less update/delete, restart, finite current-only and previous-only
receipt tags, malformed baseline/receipt metadata, metadata introduced by an
AFTER INSERT preparation trigger, legacy/explicit direct baselines, explicit
direct preparation and lost-acknowledgement recovery, and refusal of an untracked
fallback when a tracked direct receipt remains outstanding.

Before correction, five of seven methods failed: an ordinary apply was reached
before finite or malformed baseline acknowledgement failed, finite receipts
reached historical inspection, metadata introduced during preparation reached
tracked apply, and an unapplied explicit direct receipt could not resume. All
seven methods now pass without failures or skips. Provider rows, flags, instances,
reminders and participants are compared where applicable; raw projection/record
snapshots and pending work survive the refused runs and reopening. Operation
counters establish refusal before native event apply or historical inspection,
not the absence of calendar setup or other normal startup reads.

The admission reads all metadata in one SQL transaction and checks scope/lease
admission. Its return is an observation, not a lease across independent SQL and
Provider transactions. These fixtures do not qualify a concurrent finite writer,
finite native recovery, live Proton source eligibility or other OEMs. Debug
app/test builds, release source compilation, all 19 app unit cases and lint pass;
lint retains zero errors and 20 existing warnings.
The 15 affected storage, recovery, recurrence, import, capture and handoff classes
also pass all 197 physical methods without failures or skips. This includes the
seven new methods and existing direct import/recovery and specialized handoff
paths. No service requests are part of these isolated fixtures.

### Explicit finite first insertion and recovery

`FiniteInsertionDeviceTest` has twelve isolated methods using the real Provider
and production import store. A saved `FINITE_DAILY_V1` master is prepared without
a previous baseline, reopened and inserted through the explicit finite port.
Spring/autumn instances, EXDATE, duration, color, reminders/participants and the
canonical rule/timezone in SQL are checked. One saved fold instant intentionally
differs from today's calculator: it qualifies saved-date use, not source eligibility.

A trigger interrupts the actual SQL acknowledgement after a successful native
batch. Reopening, historical inspection and exact restoration retain later local
DIRTY/DELETED content, child-detail rows and newer pending source evidence without
another event batch. Modified date receipts cannot authorize SQL recovery;
copying a finite receipt to direct cannot claim its native history. Missing,
changed-nonce, foreign-owner and duplicate rows establish no history.

Other cases refuse existing event/series identity, same-nonce insertion replay,
previous baselines, wrong representation, orphan children and concurrent identity
or orphan insertion. Ordinary update/delete, parent exception admission,
missing-master child deletion and marker changes at the batch boundary preserve
finite/future-marker rows. The test wrapper counts only successfully returned
event modification batches; assertion-only inspection is distinguished from writes.

The initial ten methods failed before implementation: eight reached the unavailable
port and two reproduced missing ordinary marker protections. All twelve final
methods pass without failures or skips. Debug app/test builds, release source
compilation, all 19 app unit cases and lint pass, with zero lint errors and the
same 20 warnings. First insertion and explicit historical recovery are qualified
on the tested device; replacement, finite-parent exceptions, automatic import,
raw-source eligibility and other OEMs remain unqualified. These fixtures make no
Proton requests and do not alter account attributes.

The complete selected regression passes all 255 physical methods across 15
storage, Provider, recurrence, recovery, import, capture and handoff classes,
without failures or skips. This includes the twelve new methods. The final
application was installed and reopened after the isolated fixtures completed.

`RecurrenceStoreDeviceTest` covers schema 5-to-6 migration, legacy payload stability,
reopen/rollback/CAS, master ordering, missing or deferred parents, mismatched original
date types, durable tombstones and eligible-result limits. `RecurrenceCoordinatorDeviceTest`
combines the production store/provider with a synthetic paginated remote: children
can precede their masters across execution passes; unresolved dependencies do not
spin; deferred masters recover offline after mirror recreation; stale children cannot
resurrect deleted series. These fixtures make no Proton calls. UI tests distinguish
waiting for a series from a completed import.

For a live retained fixture, create `BridgeQA-<eight-hex-token>-series` through the
official Proton client in the authorized test account: one all-day event, DAILY,
COUNT=3. Add `recurrenceFixture=<token>` and `recurrencePhase=created` to the opt-in
`CalendarImportLiveDeviceTest`. It verifies the imported rule, duration and three
actual native instances. Move only the second occurrence to three days after the
first, retaining the title, then run `recurrencePhase=moved`; the expected offsets
are 0, 2 and 3 days. The observer never uploads and reports only counts/booleans.
Retain the identifiable remote fixture for inspection when that is the authorized
test-data policy. Record live phases separately from controlled Provider results.

## Reminder and participant validation

`ProtonEventDetailsTest` covers personal notification overrides and inheritance,
exact signed durations, email separation, bounded participants, roles, response
tokens and ambiguous/incomplete inputs. Protocol fixtures verify that inherited
settings affect content revisions, legacy receipts retain their acknowledgement
contract, and settings notices durably rescan before event obligations. The native
crypto probe includes signed details, attendee-signature tampering and offline
recovery; this does not establish external-author key trust.

With `providerFixture=true`, `EventDetailsStoreDeviceTest` exercises schema 6-to-7
migration, optional cached details, stale acknowledgements, rollback, reopen and
durable content-profile rescans. `EventDetailsCoordinatorDeviceTest` finishes old
work before automatically admitting a full rescan on a non-fresh continuation,
preserves that obligation offline without spinning, and rechecks the change feed.

`EventDetailsProviderDeviceTest` checks actual reminder and participant rows,
replacement/removal, replay identity, signed all-day offsets, capabilities on an
existing calendar, and scoped exception deletion. It also guards against writing
SELF_ATTENDEE_STATUS: Android can create an implicit participant on insertion and
reject subsequent event updates. These tests clean isolated synthetic accounts
and do not prove that a notification was delivered.

The opt-in live import observer reports aggregate local reminder counts without
contents or account identifiers. Add `reminderFixture=<eight-hex-token>` and
`reminderMinutes=10` to assert one timed `BridgeQA-<token>-reminder` event with
exactly one native DISPLAY reminder at that offset. For real notification qualification, create a
recognizable timed event through the official Proton client, select an explicit
DISPLAY reminder, and synchronize before its trigger. Check the system calendar's
notification permissions/settings, the imported reminder and a notification from
that calendar package for the same fixture. Distinguish this result from Proton's
own notification. Retain the synthetic event when authorized. All-day/DST, reboot,
Doze and other OEMs require separate journeys. Never send invitations merely to
test the participant mapping without applicable messaging authorization.

## Local failure recovery

`ImportWorkExecutionTest` exercises the production worker orchestration with
controlled failures: retry without an extra continuation, readiness failures,
cancellation propagation and permanent errors. It does not simulate SQLite.
`ImportRecoveryDeviceTest`, with `-e providerFixture true`, uses an isolated real
SQLite database and Calendar Provider account. It injects an interruption after a
real Provider write but before acknowledgement, then reopens storage and verifies
the same single Android event. It also checks an existing network failure survives
local recovery and a simulated permission gate retains its intent. The permission
gate fixture is not evidence of real Android permission revocation.
Other cases classify actual Android exception types and distinguish permanent
Provider rejections from temporary outages. Injected exceptions do not establish
behavior during real disk exhaustion, reboot, Provider death or WorkManager timing;
those require separately recorded physical journeys.

`CalendarPermissionRevocationDeviceTest` is a separate opt-in, host-orchestrated
system-grant journey. With existing `READ_CALENDAR` and `WRITE_CALENDAR` grants,
run its `prepare` phase (`-e calendarPermissionRevocation true`,
`-e revocationPhase prepare`) against an isolated SQLite store and owned Provider
fixture. Revoke both permissions through Android, verify both are denied, then run
`denied`: the local import must retain one pending projection and report
`PERMISSION`. Restore the original grants, run `restored`, and require one Provider
event, no pending projection and `IMPORTED`. Always restore grants and run `cleanup`
in a host `finally` block. The fixture makes no Proton call or account mutation;
it does not replace testing the initial runtime permission dialog or establish
revocation during an in-flight Provider batch.

## CREATE preparation boundary

`ProtonCreateContextTest`, `ProtonCreatePreparationAdapterTest` and
`ProtonCreateResponseParserTest` check scope, explicit WRITE permission,
ambiguous members/keys, session replacement at every stage, cancellation,
network versus protected-storage failures, protected preparation and conservative
response handling. Duplicate JSON property names (including escaped equivalents)
and excessive nesting remain unknown. `SharedProtonPortsTest` checks that
preparation and logout share a mutex and authentication gate.

`ProtonEventPreparationCryptoDeviceTest` uses native synthetic keys without
network or account access in the probe. It exercises the real context resolver,
timed/all-day round trips, both shared-part signatures, an altered passphrase
signature and an unusable primary key with a usable secondary present.
Its service read shape is fabricated, not an observed CREATE acknowledgement.

With a retained authorized test session and selected calendars, run only
`CalendarCreatePreparationLiveDeviceTest` with
`-e calendarCreatePreparation true`. It refreshes identity and calendar context
using production reads, prepares timed/all-day synthetic drafts locally, and
checks protected receipts and unchanged selections. It also persists those
sealed preparations in an isolated journal fixture, reopens it, and verifies that
unknown admission is committed before a local-only callback and cannot be
readmitted after reopening. The isolated database is deleted afterwards;
no event is created. Aggregate counts only are
reported. This preparation fixture uploads no event. Ordinary
application startup and background import behavior still run normally.
This does not qualify remote CREATE, lost acknowledgements or writable Provider
calendars.

`ProtonSingleDispatchTest` counts requests received by a loopback MockWebServer.
It reproduces the unsafe two-request HTTP 503/Retry-After behavior with the old
client flags, then verifies one request with the guarded body. Other cases cover
redirect refusal, response loss, timeout, cancellation, competing callers,
interceptor re-entry and unmarked/rebound writes, plus an unaffected ordinary GET.
`ProtonCoreSingleDispatchTest` uses published Core 36.8.0, Retrofit 2.9.0 and
OkHttp 4.12.0 with synthetic sessions. Its real TokenErrorHandler performs one
refresh and re-enters the block twice, while only one PUT reaches the server.
A successful request preserves body and client/session headers. A Retrofit
Content-Type annotation that wraps the body is refused before any exchange.
The fixture uses an internal Core backend, a JVM-only monotonic clock for its
logger, and a loopback security strategy; it does not qualify remote certificate
pins, the full Android factory, DoH/challenge variants or server acceptance.
The clock shim is unit-test source only and is not packaged into either APK.

`CreateAttemptStoreDeviceTest`, with `-e providerFixture true`, exercises an
isolated SQLite database: immutable/idempotent preparation, exact scope and
format matching, reopening, competing helpers, rollback and failure/cancellation
after durable admission. A separate connection checks UNKNOWN is committed
before the callback. These fixtures do not qualify simultaneous processes,
physical power loss or disk exhaustion. The journal has no automatic reset or
remote acknowledgement API. These isolated fixtures do not invoke Provider
capture or account lifecycle; owner integration is covered separately below.
The schema-v2 fixtures additionally verify first-evidence immutability, exact
work matching, reopening without readmission, anomalous prepared rows with evidence,
and additive v1 migration preserving both prepared and unknown work.

`ProtonCreateSubmissionAdapterTest` and `ProtonCreateSubmissionCodecTest` cover
the complete request shape, version/scope binding, changed context before admission,
session replacement during claim, HTTP success without readback, response loss,
cancellation, duplicate/foreign UID results and verified content mismatch.
These synthetic ports establish policy only. `SharedProtonPortsTest` checks all
submission entry points require authentication and serialize with logout.
`ProtonICalendarTest` and the native preparation probe additionally reject a
foreign expected UID even when all signed parts agree with each other.

`CalendarCreateSubmissionLiveDeviceTest` requires deliberate
`-e calendarCreateSubmission true`, a 12-character hexadecimal
`-e createFixtureToken`, and the SHA-256 of the normalized authorized test username
in `-e createAccountHash`. Do not put private account hashes or fixture identities
in public execution reports. It verifies the exact retained account before mutation,
persists the chosen scope/token, and retains one synthetic timed event plus its journal.
Fresh context reads precede admission. Only authenticated signed readback establishes
a verified observation. Reopening refuses admission, repeated submit only inspects
unknown work, and the production read-only worker must project the fixture exactly once.
If the journal disappears after a submission request, the fixture refuses re-creation.
Rerun unresolved work with `-e createInspectOnly true`; never replace its token or
reset the journal to obtain another CREATE. Aggregate status and numeric response
codes are reported, without raw bodies or account/calendar identifiers.
The fixture is intentionally retained, not automatically cleaned up.
It does not qualify user editing, general durable ACK, real lost-ACK
recovery, conditional writes, UPDATE/DELETE, or the future outbox account lifecycle.

## Durable experimental CREATE association

`CreatedEventBindingTest` checks that work identity includes account/calendar,
generation, mutation ID, adapter format and protected-payload fingerprint, while
admission/evidence changes do not substitute the immutable work.
`CreatedEventBindingStoreDeviceTest`, with `-e providerFixture true`, uses isolated
SQLite fixtures for exact UNKNOWN admission, immutable first proof, uniqueness,
reopen, corrupted-parent rejection, lease/cancellation rollback, projection-locator
replacement/clearing and additive v2-to-v3 migration. Existing journal migration
fixtures also check v1-to-current preservation. None accesses the Proton account.

`CreateAssociationCoordinatorDeviceTest` combines isolated real import/journal
databases with fake ports. It checks newer imported content is preserved, retained
bindings bypass remote inspection, pending/deferred/tombstoned imports stay
unresolved, foreign work and prepared attempts are refused, and account loss,
permissions, disabled scopes, missing/mismatched rows and concurrent import obey
the gates. These fixtures do not qualify live service behavior.

`CreateAssociationProviderDeviceTest`, with `-e providerFixture true`, inspects
isolated real Provider rows. It checks scope/revision ownership, mirror recreation,
dirty/deleted/duplicate rejection and a deterministic duplicate inserted between
identity lookup and witness qualification. A controlled regression APK filtering
the second query by the previously found numeric row fails that race assertion;
the corrected whole-identity query passes. The fixture removes only its own local
Provider data. Passing does not make the multi-query witness globally atomic.

`CalendarCreateAssociationLiveDeviceTest` requires deliberate
`-e calendarCreateAssociation true` and the same private `createFixtureToken` and
`createAccountHash` used for the retained submission fixture. It requires the
existing UNKNOWN journal and qualified retained account; it never prepares or
submits another CREATE. Production inspection and the stabilized read-only import
must yield a current owned Android witness. Reopening must retain the remote
association; after clearing only the local locator, reassociation with an
unavailable CREATE port must succeed without any call to that port. Selections,
admission/evidence and the single retained synthetic event remain unchanged.
Aggregate assertions only are reported. This is local recovery with the CREATE
port unavailable, not physical airplane-mode or power-loss qualification.

These association paths have passed physical fixtures and the retained-event live
journey. They do not enable Samsung edit capture, writable mirrors, UPDATE/DELETE,
general outgoing lifecycle or atomic cross-store conflict handling.

## Experimental CREATE lifecycle

`CreateAttemptPauseStoreDeviceTest`, with `-e providerFixture true`, uses isolated
SQLite databases. It checks exact account/calendar/generation pauses, immutable
first cause, independent helpers and reopen, no reset through repeated remember,
atomic pause/claim competition, v3-to-v4 migration, closed dispatch gates,
pre-commit invalidation/cancellation and failed bulk-pause rollback. Already UNKNOWN
work retains protected late evidence and existing associations when dispatch closes.
An intentionally unguarded claim in a controlled regression APK must fail the
paused-work/reopen assertion; the production paused-work SQL guard must pass it.

`CreateLifecycleCoordinatorDeviceTest` combines isolated real import/journal
databases with controlled ports and fake Provider permission state. It checks
production-owner admission, startup pausing across reopen, foreign preparation and
caller rejection, one dispatch followed only by inspection, authentication loss
during preparation/before claim/after claim/inspection, account replacement without
an authenticated-flag transition, and sticky calendar/permission pauses. A complete
successful catalog absence differs from a catalog error. Suspended preparation
fixtures exercise immediate policy fences before serialized selection updates.
SQLite triggers inject pause failures, then restore account/catalog/permission
availability; pending pause obligations must still block the old preparation.

These fixtures do not change the retained Proton session, system grants or real
account attributes. They qualify orchestration and persistence, not server rights
revocation or a user-facing logout journey. The retained-event association live
fixture can be rerun without submitting another CREATE to check session/import
compatibility after deployment. General outgoing resolution, Provider edit capture
and UPDATE/DELETE remain separate qualification.
The lifecycle fixtures, retained-event association and read-only delta/recovery
regressions have passed on an authorized physical target. The worker catalog-pause
failure fixture also guards an observed native runtime crash: access refresh is
isolated from the larger import coroutine while preserving its mutex, failure
propagation and cleanup. Runtime behavior on other devices remains unqualified.

## Internal local UPDATE capture

`LocalEditIntentTest` checks immutable supported-field intent, exact empty/unknown
details preservation, unsupported changes and redacted diagnostics on the JVM.
`LocalEditStoreDeviceTest` uses isolated real SQLite with controlled remote ports.
It checks acknowledged baselines, stale ACK refusal, immutable replay/reopen,
sticky conflicts, remote deletion/deferred evidence, all projection suppression
paths, schema 7 migration, and lease/cancellation/injected-failure rollback. Plain
and staged import engines retain their checkpoint while protecting local work.

`LocalEditCaptureCoordinatorDeviceTest` uses real isolated SQLite and controlled
Provider/remote ports. It checks capture against an older projected base after a
newer import, import during qualification, idempotent retained work, incompatible
format, inactive account/permissions and disabling/session loss during capture.
No port can submit an upload or silently overwrite the local variant.

`LocalEditCaptureIntegrationDeviceTest`, also with `-e providerFixture true`, joins
the production owner, real SQLite and real Provider with a controlled read-only
remote. It imports and acknowledges a simple known-empty-details baseline, makes
a synthetic Android edit, then imports a newer remote version before capture.
Capture must preserve the old base, actual local text and latest remote record as
separate facts in conflict, including after database reopening and requeue. The
single Android row remains dirty and unchanged; no service mutation is possible.

`LocalEditProviderDeviceTest` requires deliberate `-e providerFixture true` and
already-granted calendar permissions. Its scoped real Provider fixtures check
timed/DST and exclusive all-day snapshots, current empty-details preservation,
unsupported child data/flags/precision/colors/recurrence, unique identity, and a
change injected immediately before assertion-only qualification. Dirty/deleted
incoming-write and dirty recurrence-child cases must leave local data intact.
The dirty-overwrite regression must fail against the original unguarded projection
and pass with its atomic clean-row assertions. Fixtures remove only their isolated
local calendars/accounts and never modify retained Proton events.

These layers do not qualify automatic observation, an editable Samsung calendar,
live conditional UPDATE/DELETE, power-loss recovery or atomicity between Provider
snapshot and private database commit. See [local capture](LOCAL_EDIT_CAPTURE.md).

## Interrupted projection

`ProjectionReceiptTest` checks the supported import subset, protocol/nonce
validation, baseline identity and redacted diagnostics on the JVM.
Timed/all-day imports with supported reminder, organizer and participant details
can have historical receipts. Moved/cancelled exceptions qualify with matching or
different original/display date types. Already supported masters qualify with rule-based or
additional-date-only sets and exclusions. Deep snapshots own
applied and previous-base lists, and revalidate aliases mutated after model
construction, including participant count and case-insensitive uniqueness.
The reminder-only and organizer/participant acceptance assertions fail against
their respective original implementations.
The occurrence acceptance assertion likewise fails before exception support.
The type-changing acceptance assertion fails before removing the same-type receipt restriction.
Exception rule/list mutation after construction is refused. Original timed
subsecond slots are refused by the display model before receipt qualification.
The master acceptance assertion fails before master support. Aliased master date
lists must still obey size/type/precision bounds and an additional-date-only set
must not become empty before snapshot ownership.
`ProjectionReceiptStoreDeviceTest` uses isolated real SQLite for immutable
preparation/reopen, exact restoration, newer remote/tombstone preservation,
baseline comparison, incompatible formats, local-intent blocking and transaction
rollback on lease loss, cancellation or injected failure. Its independent schema
8 fixture must migrate without inventing receipts or losing retained work.
Detail preparations and restoration witnesses are frozen before the first SQL
lease callback; alias mutations there must not change the durable snapshot.
Reopening and newer pending reminder/participant contents remain independently
covered. A fabricated application witness here tests only SQL mechanics; the
Provider fixtures below supply real historical evidence.

With `-e providerFixture true`, `ProjectionRecoveryDeviceTest` joins the production
owner, real Provider and real SQLite with a controlled read-only remote. It aborts
private acknowledgement after a successful event write, changes the Android row
and reopens storage. Recovery must use the actually projected base, retain dirty
content and avoid a second row. Cases cover pre-apply interruption, newer remote
content, local deletion, nonce absence/substitution, reused opaque revisions,
duplicate identity, mirror recreation and nonce invalidation immediately before
the proof assertions. Missing proof must leave the baseline, receipt and row intact.
The lost-ACK and reused-revision regressions must fail before their respective fixes.
Reminder-only cases additionally compare the exact event and reminder row IDs and
count tracked applies after timed/all-day lost-ACK reopening. Later dirty reminder
changes are preserved and remain outside local-edit capture. Older applied evidence
must not consume a newer reminder import. Missing/foreign nonces and DIRTY refusal
leave the receipt unacknowledged. An injected failing assertion at the end of a
real Provider batch must roll back the event, nonce and reminder replacements
together. Alias mutations at Provider admission exercise both tracked application
and historical inspection snapshot ownership.

Organizer/participant fixtures cover timed/all-day lost acknowledgements with
exact attendee row IDs and no repeated tracked apply. An organizer already present
as a participant must not add another row; organizer-only and participant-only
profiles recover independently. Changed local organizer metadata, guest names and
responses stay dirty and unqualified for local-edit capture. Newer imports and
missing/foreign proofs remain unacknowledged. Whole-batch failure must roll back
organizer metadata, attendee/reminder rows and nonce together. Provider admission
callbacks mutate list aliases to verify snapshot ownership. Existing
`EventDetailsProviderDeviceTest` cases qualify the unchanged read-only mapping
and replacement behavior separately.

Exception fixtures first import a real master, then project the moved/cancelled
occurrence through the production owner. Timed/all-day lost-ACK reopening must
preserve exact exception/detail row IDs without another apply, and retain selected
parent columns and its reminder/participant rows. Cancellation must retain
`STATUS_CANCELED` with no reminder rows. Later dirty edits/tombstones, newer pending
occurrences and invalid nonces retain the same recovery safeguards. A vanished
native parent rejects direct application despite valid SQL preparation. A failed
atomic batch leaves the previous exception target and parent witness intact.
Separate SQL reopening and newer-occurrence cases qualify storage only. Existing
`RecurrenceProviderDeviceTest` and `RecurrenceStoreDeviceTest` qualify unchanged
mapping/dependency behavior. These checks do not inject concurrent parent-rule
mutation. The lookup precedes the batch, which now reasserts its observed
start, zone, rule and date type before an exception write.

The exception recovery matrix also covers both timed-to-all-day and all-day-to-timed
display changes. Before and after reopening, the original timestamp/date type and
parent ID remain distinct from the current display type and endpoints. A parent
changed to match only the display type still refuses application: its type must
match the original slot. A subsequent dirty native display conversion is preserved
without rewriting event/detail rows. Newer blocked content retains its own
unapplied preparation after older proof recovery. Separate SQL cases round-trip
both conversions and cancellation, preserving an inverse display change pending
against the recovered base with its master untouched.
An additional owner-driven lost-ACK fixture checks native instances for both
conversions and cancellation against fixed UTC expectations before and after
reopening. A moved instance can overlap another master occurrence, so exact event
IDs and endpoints, rather than counts alone, must stay unchanged with no repeated
tracked apply. This controlled reopening does not qualify arbitrary process
termination, power loss or calendar-editor behavior.

Master fixtures exercise timed/all-day rule and additional-date-only imports with
exclusions. Lost-ACK reopening preserves event/detail IDs, bounded native instances
and tracked-apply counts. Later local rule removal and tombstones remain intact;
an older proof leaves newer rules pending. Missing/foreign nonces and foreign
series identities refuse recovery. Duplicate series identities are tested both
before inspection and when inserted immediately before the atomic proof assertions.
Linked exception columns and detail rows stay unchanged during historical master
proof/restoration. This isolated step uses a fresh Provider inspector and direct
store restoration, separately from the owner-driven lost-ACK cases above.
A normal master change still requeues its exceptions; a separate
drain then applies that existing obligation without rewriting the recovered master.
Newer blocked content can acquire its own preparation after the old proof is consumed.
A final failing assertion rolls back a master-to-simple transition and its exception
removal together, including child detail rows. Changing an established series UID is
refused by storage before preparation; successful transitions are not qualified here.
Date-list aliases at SQL/Provider admission cannot change the prepared or historical
snapshot. Separate SQLite cases cover reopened sets and newer master content with
a retained child. These cases add no recurrence rules or editable capability.

All 195 sync and ten app JVM cases passed. Four selected physical suites passed:
forty-two recovery, twenty-four receipt-store, thirteen recurrence-Provider and nine
recurrence-store cases. The expanded exception matrix, two native boundary cases
and two SQLite cases extend the earlier series qualification. APK builds and lint passed (zero errors, twenty
existing warnings), followed by independent source/evidence review. These cases
use isolated fixtures and a controlled remote; no Proton event was changed.

These controlled fixtures establish neither a live Proton UPDATE nor arbitrary
power-loss, Samsung editing/alert delivery or cross-store atomicity. They clean up
only isolated local fixture data. Detail editing/upload, invitation/response
dispatch and downgrade beyond an older binary's profile are not qualified.

## Internal local DELETE capture

`LocalDeleteIntentTest` checks unchanged or edited-before-deletion content, exact
known-empty/unknown profiles, unsupported recurrence/details/colors, second
precision and redacted diagnostics on the JVM. Deletion is a separate intention;
it cannot become a supported-field UPDATE or authorize a remote request.

`LocalDeleteStoreDeviceTest` uses isolated real SQLite for immutable replay/reopen,
kind and ID uniqueness, conflicts before/after capture, remote tombstones and
deferred evidence, generic projection/requeue/count/ACK barriers, receipt refusal,
and rollback on lease loss, cancellation or injected failure. Its independent
schema 9 database must preserve UPDATE state, payloads, IDs, formats and receipts
when adding the kind. Existing schema 7/8 fixtures must also reach the current
schema without adding the same column twice.

With `-e providerFixture true`, `LocalDeleteProviderDeviceTest` checks timed and
all-day tombstones, unchanged or modified content, strict dirty/deleted flags,
missing/foreign/duplicate identities, unsupported fields/children and an assertion
race. A distinct test deletes an imported event through ordinary `Events.CONTENT_URI`
and requires a retained tombstone without manually setting its flags. This tests
the installed Provider, not Samsung's editing UI. Only still-observable fields can
be captured after deletion; removed child rows do not establish their former absence.

`LocalDeleteCaptureCoordinatorDeviceTest` uses real isolated SQLite and controlled
ports for account/format/permission/catalog gates, import during qualification,
replay without another snapshot, UPDATE/DELETE collisions and cancellation.
`LocalDeleteCaptureIntegrationDeviceTest` joins the production owner, real SQLite
and Provider with a controlled remote. It checks offline capture/reopening without
ACK, edit-then-delete content, persistent conflict, lost projection ACK recovery
on a tombstone and refusal of an ambiguous reused revision without its nonce.
Its principal capture case must fail against the unwired owner and pass after
composition. These fixtures submit no service deletion and remove only isolated
local accounts, calendars and databases.

Live conditional DELETE, editable-calendar UI, automatic observation, undo of
unobservable data, power-loss recovery and cross-store atomicity remain unqualified.

## Internal local CREATE capture

`LocalCreateIntentTest` checks canonical local identity, frozen timestamp/protocol,
known-empty details, unsupported recurrence/details/color, second precision,
timezones, exclusive all-day dates and redacted diagnostics on the JVM.

`LocalCreateStoreDeviceTest` uses isolated real SQLite for the one-shot reservation,
immutable first content, exact identity/format/timestamp, scoped uniqueness,
competing helpers, reopen, lease/cancellation and rollback. No Provider locator is
stored. Existing reservations never renew the right to stamp, even when they have
no content. Backup exclusion applies to the entire database domain.

`LocalCreateProviderDeviceTest` checks supported dirty rows, scoped identity stamp,
unchanged flags/content, marker-only reopen inspection, imported/foreign metadata,
duplicate markers including tombstones, unsupported fields/children and races.
`LocalCreateCaptureCoordinatorDeviceTest` composes the production owner with real
isolated journals and controlled ports for admission gates, first-commit replay,
orphan reservations, marker recovery without row fallback and cancellation.
The principal owner case must fail before wiring and pass afterward. These checks
send no Proton mutation and establish neither Samsung creation UI behavior nor
remote CREATE acceptance. These suites have passed on an authorized physical
target, including ordinary all-day Provider insertion without sync-adapter flags.
`LocalCreateCaptureIntegrationDeviceTest` joins the real owner, Provider and both
SQLite journals for timed/all-day capture, a failed content commit after stamping,
marker-only recovery, replacement rows and duplicate tombstones. The competing
helper fixture must also reproduce a foreign retained intention inserted between
the two owner reads; replay must validate the intention actually read. Its debug
stack hook makes the interleaving deterministic, without qualifying multiple
Android processes.

Lost uncommitted content, sacrificed unstamped reservations, physical power loss,
automatic discovery, editable mode and outgoing reconciliation remain separate
limits or work. See [local creation capture](LOCAL_CREATE_CAPTURE.md).

## Protected preparation of captured local CREATE

`LocalCreateIntentTest` also checks that a retained preparation binds the exact
captured intention, rejects an empty payload and redacts diagnostics. The JVM
serializer test requires known-empty details to preserve the simple shared parts
without inventing personal notifications. Adapter tests require a dedicated
v2 format with a sealed explicit-empty notification policy, `Notifications: []`
inside the event and no submission request. They refuse unexpected null, malformed,
DISPLAY or EMAIL overrides from the preparer instead of silently replacing data.
Unknown-profile preparation retains its original format. Submission rejects
non-empty details before session/context/crypto reads, and both decoding and
dispatch reject local preparation v1 and v2 before opening the payload or admitting
an attempt. These tests use controlled crypto/context
ports; they do not qualify native encryption or service reminder behavior.

`LocalCreatePreparationStoreDeviceTest` uses isolated SQLite to check exact
binding, immutable replay/reopen, competing helpers, schema-1 additive migration,
grouped column constraints, corrupt-row refusal and transactional rollback.
`LocalCreatePreparationCoordinatorDeviceTest` checks frozen draft construction,
cache reuse without another preparation, incompatible or undeclared formats,
current Provider content, account/access/selection/checkpoint changes across the
preparation wait, unsupported/network failure and cancellation.

With `-e providerFixture true`, `LocalCreatePreparationIntegrationDeviceTest`
joins the production owner, real Provider and both journals with a controlled
read-only preparer. It requires retained preparation without Provider flag/content
changes, cache reuse after reopening, refusal of changed/missing/duplicate markers
and retry after an injected SQLite failure. Its principal case must fail against
the unwired preparation owner and pass after wiring. Existing capture/recovery
fixtures remain regression coverage. The journal migration, owner and integration
suites have passed on an authorized physical target. A lease closed by an owner
call must be explicitly readmitted only for a fixture's deliberate SQL setup;
subsequent owner calls still install their production admission gates.

`ProtonEventPreparationCryptoDeviceTest` has a separate known-empty fixture through
the production preparer, resolver, Core cryptography, Keystore protector and real
decoder. Its context and response shapes are synthetic, with non-empty timed and
all-day defaults. It copies the outgoing notification field into the fabricated
response and checks its exact raw empty array before decoding. Absent/null overrides
inherit defaults; an EMAIL-only override can map to empty Android reminders without
being an empty personal list. No network or service mutation is used. Both native
preparation fixtures have passed on an authorized physical target; they do not
establish server acceptance.

Historical failure reproductions need application and instrumentation APKs with
matching source-level constructor/ABI contracts. Keep the original frozen artifacts
and build corrected fixtures against their corresponding application revision.
An ABI crash or an inactive test hook is not evidence of a production regression.

The local preparation is historical and paused. Cached replay does not requalify server
keys or rights. Provider inspection and journal commit remain separate. Old v1
blobs keep their unresolved obligation and are refused by a v2 adapter without
replacement. The separate submission service journey must qualify an authenticated
raw empty notification array, signatures, UID and content. These preparation tests do not qualify editable
mode, automatic observation or upload of a captured creation.

## Created-event readback profiles

`ProtonCreatedEventReadbackTest` exercises the common read-only UID lookup,
individual GET, bootstrap and verifier path with controlled ports. Shared-only v1
preserves the previous normalization of personal details. Known-empty v2 accepts
only the simple known-empty expectation, keeps those details in final comparison
and requires a raw empty `Notifications` array on the individually read event.
Absent/null, wrong types, non-empty DISPLAY/EMAIL overrides, mismatched content,
dates or identity, ambiguous/foreign/oversized UID results and session changes
remain unresolved. CRLF and UTC aliases retain their existing semantics. Cancellation
propagates; other read/verification failures remain unresolved without admission.
No journal, preparation opener or send method is available to this helper.

`ProtonCreatedEventResponseTest` tests the GET-specific bounded UTF-8 reader and
reused unique-key scanner. Duplicate names, including escaped equivalents and
nested unknown fields, fail before JSON can overwrite a prior value. Truncated,
non-successful, oversized, invalid UTF-8 and excessively nested bodies are refused.
Both UID and individual GET use this parser; general import parsing is separate.

The known-empty native probe additionally calls the same readback helper with the
real decoder and controlled reads. It refuses absent/null overrides even with
empty calendar defaults, and EMAIL-only overrides despite empty mapped reminders.
It checks refusal before bootstrap and, directly through the decoder, before local
keys. Timed/all-day content/date comparisons and both signature/UID protections
have passed on an authorized physical target. Fabricated responses do not qualify
the service's handling of personal notification overrides.

`CalendarCreateReadbackLiveDeviceTest` requires `-e calendarCreateReadback true`
and the existing retained fixture's `createFixtureToken` and `createAccountHash`.
It refuses an absent fixture, unavailable scope or non-UNKNOWN record, calls only
`inspect`, and verifies the exact record/evidence after reopening and the existing
association's event identity. It never prepares, submits, admits or creates an event.
This passed against a retained legacy live event, as did the existing Android
association regression. It qualifies authenticated GET parsing and shared-only v1
readback, not known-empty v2 service behavior or stable selection across the read.
The personal array is an authenticated observation separate from shared signatures.
Both submission profiles reject local preparations v1/v2 before opening or admission.

## Experimental explicit-empty CREATE submission

`ProtonKnownEmptySubmissionTest` checks submission v2's protected format, explicit
notification policy, complete frozen expectation and exact raw empty request array.
It rejects inconsistent version/policy/expected/request data before preparation or
claim. Fresh format or draft drift refuses admission. Existing UNKNOWN work only
inspects; absent/null/non-empty readback, response loss and cancellation never
permit another PUT. Legacy submission tests retain the v1 interpretation. These
controlled ports establish orchestration and refusal behavior, not live acceptance.

`CreateAttemptStoreDeviceTest` treats submission v2 as opaque, retaining its exact
payload and response evidence through UNKNOWN admission and reopening without a
schema change or readmission. The separate native submission fixture exercises
production preparation, codec, reopened Keystore, Core signatures/decryption and
strict UNKNOWN inspection with synthetic context/response data. It has no usable
prepare, admission or send path during inspection.
Its additional folded-whitespace case constructs an actual physical iCalendar
line ending in a space. It reproduces rejection by byte-only verification of
Core text signatures, then requires exact timed/all-day content, strict empty
readback and refusal of both altered signatures through the production decoder.
`ProtonEventCryptoDeviceTest` remains the native binary-signature/import regression.

`CalendarKnownEmptySubmissionLiveDeviceTest` requires explicit opt-in
`calendarKnownEmptyCreate=true`, an authorized account hash, a fresh fixture token
and frozen creation/start timestamps. PREPARE freezes scope and retains an unadmitted protected
dispatch record using read-only context preparation; SUBMIT marks the request
before entering the one-shot port; subsequent journeys use INSPECT only. Missing
journal or changed identity blocks reconstruction. A dedicated journal and
preferences keep the earlier legacy fixture separate. Success requires authenticated
raw empty notifications, verified shared signatures/UID/content, exact UNKNOWN work
after reopening and refusal of readmission. Ordinary import must produce the unique
owned Provider row for the verified remote ID and revision, with matching time/title,
`HAS_ALARM=0` and no reminder rows; ownership is rechecked after child queries.
The recognizable synthetic event is retained. No captured local intention, editable
UI or automatic outgoing queue is involved. All-day service behavior, real response
loss and notification delivery by another application require separate qualification.
The native preparation/readback cases, binary-import regression and journal/lifecycle
fixtures have passed on an authorized physical target. The retained legacy event
still verifies. The new timed attempt initially remained unresolved because byte-only
verification refused its text signature. After the reproduced decoder correction,
INSPECT of that same UNKNOWN attempt passed strict authenticated readback, exact
work/evidence reopening, readmission refusal and owned Android projection without
reminders. No second submission, event replacement or account change was used.
This does not establish a successful immediate readback inside SUBMIT, which was
the original failing journey, or power-loss/server-idempotency behavior.

## Captured CREATE submission staging

`LocalCreateSubmissionTest` verifies independent canonical attempt identity and
the frozen captured draft. `LocalCreateSubmissionStoreDeviceTest` uses isolated
SQLite databases and synthetic opaque payloads to check exact capture dependency,
immutable reservation/payload, scoped uniqueness, competing helper admission,
cross-helper ticket refusal, reopening, corruption refusal and additive v2 migration.
Existing capture/preparation tests qualify direct v1 migration and preserve historical
preparations. Cancellation fixtures distinguish rollback before commit from a
committed reservation whose ticket delivery is cancelled, and a consumed ticket
whose payload commit fails. None of these fixtures submits an event.

`LocalCreateSubmissionCoordinatorDeviceTest` checks the exact frozen draft,
historical preparation preservation, completed cache reuse and unresolved gaps
without preparing again. Current account, permission, selection, checkpoint,
marker/content and declared/returned format changes refuse staging. Fake submission
and inspection methods fail if called. A physical failing owner reproduction precedes
the wiring. These controlled ports are not evidence of Proton acceptance or encryption.

`LocalCreateSubmissionIntegrationDeviceTest` opts in with `providerFixture=true`.
It uses the real Calendar Provider and local journals with a controlled preparation
port. Reopening must retain the same submission and original dirty/unacknowledged
row. Concurrent content changes, duplicate marker tombstones, deleted rows, a new
reminder child and an interrupted payload commit cannot qualify work or regenerate
an attempt. Isolated databases, calendars and accounts are removed after each fixture;
retained live Proton examples are separate and are not mutated by this suite.

The staging suite covers read-only preparation and same-process helper concurrency.
It does not establish dispatch; the separate qualification follows below. Multiple
Android processes, physical power loss, live captured upload and reconciliation
with the original local row remain unqualified.
The 42 staging store/owner/Provider cases and 80 related capture/preparation
regressions passed on an authorized physical target. The cancelled ticket-delivery
case retained the already committed reservation without returning a reusable
preparation right. The Provider reminder case uses an actual reminder child;
setting the derived `HAS_ALARM` flag alone was insufficient to construct that fixture.
Host sync, Proton adapter and app tests and Android lint also passed. No new
native crypto or live Proton creation is claimed by this staging qualification.

## Captured CREATE dispatch admission

`CapturedCreateDispatchIdentityTest` checks the independent fingerprint golden,
UTF-8 byte boundaries, exact text/time/zone identities, malformed-Unicode refusal
and redacted diagnostics. `ProtonCapturedSubmissionTest` requires the exact sealed
known-empty draft before fresh preparation or inspection. It covers foreign
content/correlation/scope/details, legacy-format refusal, late claim failure,
context drift, cancellation after UNKNOWN and inspection without another send.
The verified readback preserves observed remote identity/revision while returning
the expected domain representation only after the existing authenticated content
check, which normalizes CRLF/LF and UTC aliases. These are controlled adapter
fixtures, not service acceptance.

`CapturedCreateAttemptStoreDeviceTest` opts in with `providerFixture=true` and
uses isolated synthetic SQLite work. It checks additive v4-to-v5 migration,
preserved evidence/pauses/associations, atomic captured-link retention, full
identity qualification, corrupt/orphan-link refusal, generic-path exclusion,
competing helpers, cancellation, transaction rollback, foreign keys and FULL
synchronous admission. Existing migration fixtures retain older upgrade paths.

`CapturedCreateDispatchCoordinatorDeviceTest` uses real journals and controlled
Provider/adapter ports. Fresh staging can admit once; cached staging, owner
reopening, observed account/permission loss, disable/restore or a consumed failed
claim cannot rearm that right. Current checkpoint or marker/content mismatch
refuses admission; it does not permanently consume an otherwise valid lease. A synthetic
non-reentrant authentication guard rejects current-account reads from the final
claim. Cancellation after admission retains UNKNOWN. A changed/missing original
row permits inspection only, and wrong verified identities/content are refused.
Generic submission and association cannot adopt captured work.

`CapturedCreateDispatchIntegrationDeviceTest` runs with the real Calendar Provider,
isolated journals and a controlled transport. Admission preserves the original
dirty unacknowledged row and its local marker. A Provider title change or actual
new reminder child during fresh context work prevents admission. Reopening a
completed stage cannot rearm it; an admitted attempt remains inspect-only after
the original row disappears. Temporary synthetic calendars/accounts/databases
are removed; retained live examples are separate.

The native captured-inspection case uses production Core crypto, preparation,
Keystore reopening and decoder with synthetic context/readback. Wrong expected
content is refused before UID reads; exact timed/all-day inspection succeeds
without a usable preparation, admission or send path. It does not submit to Proton.

These checks do not establish cross-store atomicity, simultaneous Android
processes, power-loss durability, real rights revocation or live captured upload.
No original-row acknowledgement, remote binding or projection reconciliation is
implemented by the dispatch path itself. A captured live creation must wait for association
that avoids a duplicate ordinary import. Writable UI and automatic outgoing
processing remain unavailable.

The 34 captured journal/owner/Provider cases, six native crypto cases, 53 existing
journal/lifecycle cases and 42 staging regressions passed on an authorized physical
target. Read-only inspection of the retained legacy live event also passed with
the current build, without another creation. Host sync, Proton adapter and app
tests and Android lint passed. No live captured submission was run; controlled
admission and native inspection must not be presented as its service qualification.

## Captured identity binding and incoming projection hold

`CapturedCreateBindingTest` separates a scope-wide hold from exact held event
identities and checks immutable proof identity and redacted diagnostics.
`CapturedCreateBindingStoreDeviceTest` uses isolated SQLite work with explicit
`providerFixture=true`. It verifies independently constructed v5 migration,
preserved attempts/evidence/pauses/associations, no invented bindings, full-link
proof equality, first-proof immutability, scope isolation and uniqueness across
captured and generic bindings. An incompatible current import format keeps the
scope held even with a retained binding. Malformed or orphaned parent/link/proof work keeps
the scope held; a missing table propagates failure. Transaction rollback, revoked
admission, cancellation and competing helpers cannot free unresolved work.
These helpers establish same-process concurrency, not physical power loss or
simultaneous Android processes.

`CapturedCreateImportHoldCoordinatorDeviceTest` checks the production owner with
real journals and controlled remote/Provider ports. An unwired-owner reproduction
must project the received captured event and fail the no-projection assertion.
After wiring, UNKNOWN holds survive reopening, keep incoming work pending, refuse
receipt recovery and local-baseline recovery, and never acknowledge the held event.
Wrong observations and loss of current access cannot create a binding. A retained
binding resumes with no submission port. The held event's newer content or deletion
does not replace the first proof; unrelated events can project. Filtering occurs
before the limit: runnable work behind more than 100 held rows still advances.
Held work alone does not request continuation. Legacy downloads and a retained
staged receipt still advance while projection is held; the latter processes offline
without calling receive. Journal failure does not fall back to ordinary projection.

`CapturedCreateImportHoldIntegrationDeviceTest` uses the real Calendar Provider,
isolated journals and fake captured transport. UNKNOWN followed by incoming data
cannot produce a second row before or after reopening. Readback binding preserves
a later local edit and its dirty marker while allowing an unrelated event to import.
A purged original is not recreated by the held incoming event. Synthetic local
calendars/accounts/databases are removed; existing live examples are untouched.

The production Proton captured-inspection contract remains the previously
qualified adapter; these new fixtures do not prove a captured service upload or
original-row acknowledgement. The hold has no release in this slice. Remote
baseline/pending acknowledgement and conflicts must be qualified before a live
captured-creation journey or writable-mode rollout.

The unwired-owner physical reproduction failed its no-projection assertion; after
wiring, all 35 binding/hold fixtures passed. The same build also passed 34 captured
dispatch regressions, 53 journal/lifecycle cases, 36 import/recovery cases and
12 association cases. Read-only inspection of the retained legacy live event
passed without a new submission. Host checks passed with 114 sync, 274 Proton
adapter and 10 app tests, plus Android lint and both debug APK builds.

Adding hold reads to the larger import coroutine reproduced a native runtime
crash in the existing SQLite pause-intent lifecycle fixture. The same fixture
passed with the owner wiring absent. Moving hold/status publication into a
separate suspend helper made the exact reproduction pass, followed by the full
physical regression groups above. The internal runtime cause remains unproven;
this qualifies the refactor on the tested physical target, not other runtimes.

## Captured original-row metadata attachment

`CapturedCreateAttachmentReceiptTest` checks historical witness identity,
protocol/nonce refusal and redacted diagnostics. This receipt does not grant
dispatch, clean the original row or release import.

`CapturedCreateAttachmentStoreDeviceTest` uses isolated SQL with
`providerFixture=true`. Its independently constructed v6 fixture checks additive
v7 migration, preservation of UNKNOWN/evidence/pauses/generic bindings and no
invented attachment plans. It checks first-nonce immutability, full proof drift,
reopening, revoked admission, cancellation, rollback, competing helpers and
foreign keys. Damaged or orphaned plans block the scope and generic adoption.

`CapturedCreateAttachmentIntegrationDeviceTest` uses real SQLite and Calendar
Provider with controlled captured transport. An unwired-owner reproduction must
return UNRESOLVED and fail the expected attachment result. The wired owner
attaches metadata to one original row, keeps it dirty and keeps incoming data
held after reopening. Later title/reminder changes remain intact. Lost-response
recovery reuses the nonce without another write; a later tombstone retains the
historical proof without consuming deletion. Initial tombstones, purged originals,
replacement rows, duplicate/missing markers, foreign metadata or existing remote
projections remain unresolved. Substituted nonce/full binding, malformed Unicode
and damaged Provider receipts cannot qualify recovery. Current access loss,
controlled permission loss, cancellation and import-format replacement cannot
authorize a write or release the hold. The controlled permission case establishes
owner gating, not real mid-batch Android revocation. Temporary fixtures are
removed; retained server examples are separate.

These checks do not establish baseline/pending acknowledgement, conflict
resolution, live captured upload, writable UI, multiple simultaneous processes
or physical power-loss durability. A historical receipt cannot replace those
qualifications.

The unwired-owner physical fixture failed the expected attachment result. On the
wired build, all 13 attachment journal and 19 Provider/owner cases passed, along
with 35 hold, 34 dispatch, 53 journal/lifecycle, 36 import/recovery, 12 association
and 23 existing Provider cases. Read-only inspection of the retained legacy live
event passed without a new submission. Host checks passed with 118 sync,
274 Proton adapter and 10 app tests, both debug APK builds and Android lint.

## Captured verified sent baseline

`CapturedCreateBaselineTest` checks exact event/revision and frozen content,
known-empty personal data, malformed text refusal and redacted diagnostics. A
matching private fingerprint establishes equality, not remote authentication.

`CapturedCreateBaselineStoreDeviceTest` uses explicit `providerFixture=true`
and isolated SQLite data. It checks import v11 provenance and baseline retention
in the same transaction as a whole-payload pending comparison. Migration preserves
existing work and does not invent capture provenance. Generic or corrupt baseline
rows and unresolved ordinary receipts cannot be adopted. Reopening, drift,
rollback, cancellation, admission loss and competing helpers must preserve the
first reference; incoming differences and deferred/local work stay intact.

`CapturedCreateBaselineIntegrationDeviceTest` uses the production owner, real
journals and Calendar Provider with controlled transport. The unwired owner must
fail the expected retention result. The wired path retains the sent reference
without creating an absent remote cache row, cleans only an identical incoming
payload and preserves updates, equal-revision content differences and deletions.
Local titles, reminders and tombstones stay dirty. Missing or damaged original
markers/receipts, duplicate rows and missing attachment plans cannot establish
a reference. Access loss, controlled permission loss, format replacement and
cancellation cannot authorize an unqualified commit. A failure in the pending
comparison rolls back provenance and baseline together; an access loss after a
durable commit can resume without changing the reference. Fixture data is isolated
and removed. These cases do not qualify a live captured upload, original-row ACK,
hold release, conflict resolution, writable UI or cross-process atomicity.

The unwired-owner physical fixture failed the expected retention result. The
wired build passed all 17 baseline SQL and 20 owner/Provider cases, 322 related
regressions including migration fixtures, and one read-only inspection of the
retained legacy live event. No new live submission was made. Host checks passed
with 124 sync, 274 Proton adapter and 10 app tests, both debug APK builds and
Android lint. These results retain the boundaries described above.

## Current local intention after captured attachment

`CapturedCreateCurrentTest` checks unchanged versus later content and tombstones,
known-empty supported data, precision, malformed text and redacted diagnostics.
The domain snapshot is point-in-time data, never an Android clean lease.

`CapturedCreateCurrentProviderDeviceTest` uses isolated production Provider rows
with `providerFixture=true`. It checks timed/all-day dates, later text, present
tombstones and preservation of unsupported flags, reminders, attendees, extended
properties, recurrence and colors. Missing, duplicate, moved, clean or foreign
rows and altered attachment metadata cannot qualify capture. Injected edits,
duplicate identities, reminders or tombstones before the single assertion batch
must abort a stale snapshot. Admission loss after the batch cannot return a
qualified result. No Provider content or flags are changed by the snapshot.
One fixture simulates an empty alarm flag only in the query cursor, with real
reminder rows retained, to verify child inspection independently of that flag.
It does not require writing the [alarm flag](https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns#HAS_ALARM).
Concurrent reminder creation must reach a real assertion batch and reject the
stale snapshot; its alarm metadata can also cause that rejection. This case does
not isolate the child assertion as the only failure source.

`CapturedCreateLocalChangeStoreDeviceTest` uses isolated SQL and existing import
schema v11. First supported UPDATE/DELETE work is immutable and preserves raw
incoming pending flags, payloads, bases and receipts. Reopening and exact replay
cannot clear a conflict or replace kind/content/identity. Different, malformed,
absent or deferred remote evidence conflicts; raw corruption, ordinary receipts,
advanced baselines, incompatible checkpoints and mutation collisions refuse
adoption. Rollback, lease loss, cancellation and competing helpers must preserve
the first intention. An unchanged result requires absence of any retained work.

`CapturedCreateReconciliationIntegrationDeviceTest` uses the production owner,
real journals/Provider and controlled captured transport. An unwired owner must
fail the expected unchanged result. Source and receipt drift, inactive access,
unsupported data, missing or ambiguous originals and format replacement cannot
establish new work. Replays inspect the current row; a second edit, tombstone or
reverted edit leaves the first intention intact and requires resolution. A local
commit failure preserves retry, and access loss after commit preserves durable
work. A new edit between snapshot and commit remains dirty and is detected by
the post-commit snapshot. Remote differences before and after capture preserve
both local intention and remote payload. Later import can suppress projection
through the existing intention guard; that suppression is not an Android ACK.
Fixture data is removed. No original-row acknowledgement, hold release, writable
UI, live captured submission, conflict resolution or cross-process atomicity is
established by these cases. Permission-loss fixtures control the gate rather
than revoking Android grants during a batch.

The unwired-owner physical fixture failed the expected unchanged result. The
wired build passed 17 local-intention SQL and 32 owner/Provider cases. Two initial
Provider fixtures failed because an alarm-only update was rejected; another
race fixture could report rejection before attempting its intended batch.
After correcting those fixtures, all 27 Provider cases passed, including explicit
proof of attempted assertion batches. The same production build passed 300
related regressions and one read-only inspection of the retained legacy live
event, for 377 distinct physical cases. No new live submission was made. Host
checks passed with 131 sync, 274 Proton adapter and 10 app tests, both debug APK
builds and Android lint. These results retain the boundaries described above.

## Conditional original-row acknowledgement

`CapturedCreateAcknowledgementTest` checks canonical nonce/protocol rules,
complete provenance fingerprints, strict identity text and redacted diagnostics.

`CapturedCreateAcknowledgementStoreDeviceTest` uses isolated SQL with
`providerFixture=true`. It checks additive v11-to-v12 migration without invented
proof, immutable UNKNOWN reservations, competing helpers granting only one fresh
admission, strict historical proof and promotion to PROVEN. Raw unresolved or
corrupt evidence fences all three capture paths before replay. Existing local
intentions, ordinary receipts, source drift, nonce collisions, rollback,
lease loss and cancellation cannot authorize a new reservation or promotion.
Records, raw pending flags, deferred evidence, baselines and receipts remain
intact. Proven evidence permits later specialized local capture, without resetting
a conflict; generic snapshots cannot adopt captured provenance.

`CapturedCreateAcknowledgementProviderDeviceTest` uses isolated real Provider
rows. It requires six assertions and one update limited to DIRTY and the exact
acknowledgement marker. Changed, unsupported, duplicate, foreign or missing rows
cannot be cleaned. Race fixtures require proof that the real batch was attempted
after their setup hook. Historical inspection asserts exact identity and markers
without reading current content or consuming dirty/deleted flags or children.
Response-loss fixtures retain proof through later edits, reminders and tombstones;
current capture then requires that exact acknowledgement marker and a dirty row.
Malformed identity, marker drift, admission loss and cancellation cannot return
an unqualified witness.

`CapturedCreateAcknowledgementIntegrationDeviceTest` uses the production owner,
real journals/Provider and controlled transport. An unwired owner must fail the
expected acknowledgement result. Qualified unchanged originals can be cleaned
once without consuming incoming evidence or releasing the captured hold.
Reopening inspects the first nonce without another write. Lost responses, failed
private promotion and later edits/tombstones preserve historical recovery and
subsequent local capture. A proven replay must preserve a retained local intention.
Admission interrupted before the batch remains UNKNOWN with no automatic retry.
Missing or corrupted markers cannot be adopted from proven private state.
Inactive access, incompatible formats and source drift prevent qualification.
Different remote payloads before and after acknowledgement remain pending and
held. Fixture data is isolated and removed. Permission loss controls the gate,
not real grants during the batch. These tests do not establish live captured
submission, hold release, conflict resolution, writable UI, real power loss or
simultaneous processes.

The unwired-owner physical fixture failed the expected acknowledgement result.
The wired build passed 22 acknowledgement SQL, 29 Provider and 25 owner cases.
One initial owner fixture invoked an asynchronous selection change before its
disabled precondition had been persisted. After making that precondition explicit
and awaiting it with a bounded suspension, the corrected case passed. This does
not establish synchronous admission closure on return from the selection setter.
The unchanged production build also passed 411 related regressions, including
migrations and earlier capture recovery, and one read-only inspection of the
retained legacy live event: 488 distinct physical cases. No new live submission
was made. Host checks passed with 139 sync, 274 Proton adapter and 10 app tests,
both debug APK builds and Android lint. These results retain the boundaries above.

## Current clean original qualification

`CapturedCreateCleanProviderDeviceTest` uses isolated real Provider rows and
requires six assertions with no mutation for a clean-original witness. Exact
timed and all-day originals qualify; content drift even with a clean flag, dirty
rows, tombstones, unsupported fields, reminders, extended properties, damaged
markers and duplicate identities remain unqualified and intact. Concurrent
content, flag, child or marker changes abort the actual atomic assertion batch.
Controlled permission/lease loss cannot return a witness. Generic apply, tracked
apply, delete and local capture refuse each captured marker, including concurrent
attachment. Recurrence parent attachment and descendant deletion obey the same
barrier; ordinary projection remains functional.

`CapturedCreateGenericFenceStoreDeviceTest` verifies raw local-capture barriers
before replay for absent, unknown, proven, malformed or orphaned ACK sources.
UPDATE/DELETE intentions, incoming pending flags and all source tables survive
refusal. Unrelated ordinary events and scopes still work. The point-in-time SQL
source check requires an exact PROVEN ACK and matching original base with no raw
local intention or ordinary projection receipt. It preserves different incoming
and deferred data; schema v12 is unchanged.

The additional `CapturedCreateAcknowledgementIntegrationDeviceTest` cases exercise
the production qualification owner with real journals and Provider, and controlled
transport. They verify exact capture/attempt/source provenance, clean qualification
without another ACK or submission, reopen, absent/UNKNOWN proofs, later edits and
tombstones, disappearance, reminders, marker drift, local-intention retention after
Android reversion, incoming-data preservation, foreign witnesses and closure after
interruption, pause, sign-out, permission or format changes. An observation cannot
prevent the next local edit or release the captured hold. The unwired owner fixture
failed with expected CLEAN and actual UNRESOLVED before connection.

The wired build passed 36 new Provider cases, 18 SQL cases and 22 additional owner
cases. Related regressions and the retained legacy read-only live inspection also
passed: 591 distinct physical cases in total. One older baseline regression still
prepared a captured intention through the newly forbidden generic capture path.
Using the specialized full-source capture corrected that precondition while keeping
its replay, intention, pending and receipt assertions. The corrected case and remaining
checks passed with the production APK unchanged. Host checks passed with 139 sync,
274 Proton adapter and 10 app tests, both debug APK builds and Android lint.

These checks qualify the assertion and admission boundaries on the tested physical
device. Permission failures use controlled gates, not real revocation during a batch.
They do not qualify live captured submission, hold transfer, writable UI, simultaneous
processes or real power loss. Isolated fixtures are removed; retained live examples
are preserved.

## Anonymous transport probe

`ProtonConnectivityDeviceTest` is skipped unless instrumentation receives
`-e calendarNetworkProbe true`. Run only that class for a deliberate network check.
It calls Proton's public ping through the production network factory and certificate
pins, using a separately named temporary empty database that is closed/deleted
afterwards. It never reads the account store or submits credentials. The helper is
included only in the debug variant. Results contain network booleans and closed
failure categories, without raw exceptions, headers, response contents or IDs.

An app can be denied network access even when the phone has a validated connection
and the installed package has `INTERNET`. Inspect the app-specific system network
policy before changing the endpoint, TLS checks or client identity. A successful
anonymous ping establishes transport reachability, not account login or Calendar
compatibility.

## Durable captured-creation handoff

`CapturedCreateHandoffTest` exercises independent origin/current references,
canonical admission identity, full previous-reference checks and unsupported data.
The Android opt-in fixtures use real SQLite journals and the installed Calendar
Provider with controlled transport:

- `CapturedHandoffStoreDeviceTest`: migration 12 to 13, immutable role/source,
  corrupt or orphaned evidence, exact prepared receipts, atomic reference advancement,
  newer queues, committed replay and specialized local intentions.
- `CapturedHandoffProviderDeviceTest`: conditional existing-row updates, full origin
  markers, current-reference assertions, races, children, duplicate identities,
  historical recovery and dirty/tombstone snapshots.
- `CapturedHandoffIntegrationDeviceTest`: explicit owner transfer, reopening,
  responses lost at Provider/SQL boundaries, reloaded incoming data, policy and
  permission invalidation, specialized capture, generic replay refusal and bounded
  backlog draining without continuations for held work.

Run the classes with the existing `providerFixture=true` instrumentation opt-in.
Fixtures create isolated accounts/calendars and remove their runner data. They do
not submit a live captured creation or qualify Proton conditional UPDATE/DELETE,
a writable system-calendar UI, automatic capture or simultaneous-process ordering.
A journal upgraded to v13 requires a compatible app during restoration; do not
restore an older APK merely to repeat a test. Legacy-proof refusal fixtures inject
pre-existing SQL evidence directly because ordinary preparation now rejects all
captured provenance before replay.

Physical SQL, Provider and owner fixtures passed these handoff boundaries and
related regressions. The previously unwired owner failed the expected transfer
result before integration. A controlled response loss also reproduced a missing
batch-response check; the unchanged regression passed after the shared captured
helpers refused absent or malformed responses before producing witnesses.

All Calendar Provider batch paths now validate response cardinality, non-null
entries and result shape before reporting success. Insertions additionally require
an identity in the expected collection; fixed assertion/write proofs verify exact
counts. Generic variable batches rely on the Provider's `withExpectedCount` checks
for their exact counts. The additional `CapturedHandoffProviderDeviceTest` cases
exercise response loss before and after execution, malformed results, calendar
creation/removal, stamping, attachment and ordinary receipt recovery. Include
`EventDetailsProviderDeviceTest` and `RecurrenceProviderDeviceTest` when changing
this shared validation because those batches also insert and remove child rows.

`CalendarCapturedHandoffLiveDeviceTest` is a separate retained-session service
journey. It requires `calendarCapturedHandoff=true`, an explicitly authorized
account hash, a unique synthetic fixture token and frozen second-precision dates.
Its `capturedHandoffPhase=CREATE` invokes the production owner and all three
journals: native capture, fresh preparation and one submission, authenticated
readback, attachment, original evidence, acknowledgement and handoff. Creation
admission is frozen before mutation. `INSPECT` resumes the same fixture without
inserting, preparing or sending again; missing admission blocks reconstruction.
Retain the synthetic event and restore only a schema-compatible APK. This journey
does not qualify creation through a system-calendar UI or general outgoing edits
or deletions.

The same retained fixture supports a separate incoming-update qualification:
`PREPARE_UPDATE` freezes the full current reference, original evidence and native
ownership before a title-only edit through the official Proton client.
`VERIFY_UPDATE` requests production synchronization and requires the new revision
and exact title on the original Android row, with all other content unchanged,
immutable creation evidence and drained event-specific work. It freezes the
advanced reference only after those checks pass.
`CAPTURE_LOCAL` then admits one conditional title-only Android mutation. Its
atomic batch asserts the canonical calendar identity, original row, complete
clean content, frozen markers and absent children. Exact batch responses and
operation-time calendar grants are required. Durable admission prevents replay
after a missing response; rerunning never writes again. The production owner must
retain the later intention as `PAUSED` against the advanced reference.
`INSPECT_LOCAL` checks that retained intention, waiting state and dirty native
content without requesting synchronization or repairing the fixture. Ordinary
startup/import work can still run when the application launches.
These phases do not enable writable UI or send Proton UPDATE/DELETE. Unsupported
data introduced by the official-client edit must stay blocked rather than be
removed to make the fixture pass. Build, lint and independent source review passed.
All four retained-session phases passed on a physical device: the official-client
title change projected onto the original row with unchanged remaining content;
the later local edit stayed paused against the advanced base; inspection after a
controlled application process stop and reopen retained the dirty row, provenance
and waiting state. No new submission, native event or calendar was created by
these phases. The visible remote and local examples are retained. This does not
qualify editing through a system-calendar UI, natural process death, concurrent
service writers, richer events or conditional Proton UPDATE/DELETE.
Do not set `SELF_ATTENDEE_STATUS` when constructing a participant-free native
fixture: the Provider can insert a synthetic attendee even for status zero. If an
earlier rejected fixture exists, the positive journey verifies its exact consumed
reservation, missing stamp and absence of raw staging/dispatch evidence before
admitting a distinct fixture. It never removes the child or rearms that reservation.

## Adapter mutation admission

`SyncEngineTest` checks the adapter's default-empty `supportedMutations` ceiling
independently of persisted calendar access. A fixture with all access rights and
matching cached revisions must never dispatch or claim uncertain work through an
adapter that has declared no qualified mutations. CREATE-only capability refuses
UPDATE/DELETE while permitting CREATE. Capability withdrawal blocks admission;
restoration does not resume blocked work. Previously uncertain or acknowledged
work remains intact, including a response lost after admission and capability loss.
The default-capability reproduction failed before the engine check was added.
The five additional tests and all 153 sync JVM cases then passed, along with
274 Proton-adapter and 10 app JVM cases, the Android application build, lint and
independent source review. These are controlled engine fixtures, not evidence of
Proton conditional mutation or Android capture. Current production import adapters
inherit the empty capability set; the experimental CREATE submission port is a
separate boundary and does not advertise generic mutation capability.

## Offline shared-title preservation

`ProtonSharedTitlePatcherTest` exercises an internal plaintext-only experiment,
not an authenticated remote read or an UPDATE payload. Exact string comparisons
verify that only the top-level encrypted-shared SUMMARY span changes: clear text,
opaque duplicates, quoted parameters, folding, field order, VALARM titles and
other components stay untouched. No-op changes return the original strings.
Biweekly escapes and folds a new Unicode title, including delimiters and text
that resembles component framing, and reparsing recovers its logical value.
Generated lines must fit 75 UTF-8 octets. Tests cover CJK, surrogate pairs and
mixed text at folding boundaries; long whitespace runs that make the dependency
exceed this bound are refused. Untouched original lines are not refolded.

Rejection cases cover stale expected titles, missing/duplicate/parameterized
SUMMARY/UID/DTSTAMP, mismatched identities, strict UTC stamps, invalid framing,
multiple events/calendars, invalid encoding, controls and bounded size/depth/lines.
A near-limit input is accepted unchanged but refused when its patched output
exceeds the combined byte budget. Original inputs and closed diagnostics remain
intact on refusal. The scanner preserves opaque values rather than interpreting
their semantics; a retained malformed unrelated date deliberately demonstrates
that preservation is not complete RFC validation or upload qualification.

The experiment has no production caller, signature/key handling, timestamp update,
storage, transport or advertised UPDATE capability. Full encrypted/JSON envelope
preservation, personal/attendee partitions, rights, concurrency and live service
updates require separate qualification. Unit tests do not establish those claims.
All 13 focused cases and the 287 Proton-adapter JVM cases passed. Independent
source review passed; no device or service mutation was performed for this slice.

## Opening verified shared text for offline title patches

`ProtonSharedTitlePatcherTest` additionally binds both signed iCalendar UIDs to
the expected observation, including no-op changes. `ProtonSharedTitleBasisTest`
checks independent deep JSON copies, redacted container/error diagnostics and the
separation between an unchanged original signed row and changed plaintext.
Container fixtures alone do not establish signature verification.

`ProtonSharedTitleCryptoDeviceTest` runs two isolated native fixtures with fresh
synthetic Core keys, binary and canonical text signatures. They open the exact
clear/encrypted shared originals, patch a Unicode/escaped title and check raw
untouched spans and all opaque JSON fields. Row and bootstrap backing maps are
mutated during local key access to verify snapshots occur before suspension.
Refusals cover account/calendar/event/revision bindings, unsigned UID substitution
against the signed UIDs, stale titles, ambiguous shared part types, alternative
address-key packets, altered signatures/plaintext, unresolved authors, foreign
local key owners, missing keys, tampered passphrase signatures and foreign
calendar settings. Cancellation must propagate without conversion to a failure.

Run the selected native class with `ProtonEventCryptoDeviceTest` and
`ProtonEventPreparationCryptoDeviceTest` to cover the shared bootstrap helper's
existing import and preparation behavior. These fixtures use no account data,
network, Calendar Provider writes or service mutation. Neither native signatures
nor JSON preservation qualify opaque partition semantics, active-session/access
guards, timestamp updates, concurrent writes or live UPDATE compatibility.
The 291 Proton-adapter JVM cases passed. The two new native cases and six existing
import/preparation cases passed on an authorized physical device. Application and
test APK builds, lint (zero errors) and independent source review passed. The
current application was reopened after the isolated tests; existing test account
examples were retained. No service update or system-calendar edit was attempted.

## Offline shared-title re-encryption

`ProtonReencryptedSharedTitleTest` verifies that the original JSON and replacement
shared part remain separate, deeply isolated snapshots with redacted diagnostics.
Fabricated ciphertext in these JVM container checks provides no crypto evidence.

Two additional `ProtonSharedTitleCryptoDeviceTest` fixtures start from binary- or
text-signed originals. Native Core decrypts the new Data using the original
SharedKeyPacket, recovers the exact patched plaintext and verifies its new text
signature. Both signature verifiers reject the old signature on the changed text.
Only Data/Signature differ in the shared part; its Author/opaque metadata, the
original clear part, other partitions and original key packet remain unchanged.
No-op preparation retains the exact original ciphertext/signature. Author case
is preserved after case-insensitive comparison with the selected local signer.

Refusals exercise source bindings/signatures, stale titles, missing or foreign
personal-calendar metadata, missing WRITE rights/member flags, disabled/non-sending
addresses, unavailable keys and a locally trusted author different from the selected
writer. Metadata, bootstrap and row backing collections are mutated during the
single local address read; preparation must use their earlier snapshots. Cancellation
must propagate. These are isolated fixtures, with no account, network, Provider
mutation or event-level authorization proof. Timestamps/SEQUENCE, semantic validity
of opaque part metadata and live UPDATE/conflict behavior remain unqualified.
An additional key-isolation case uses a distinct unavailable primary signing key
and an available original secondary address key. Original content opens through
the secondary; a changed title is refused with LOCAL_KEYS, while no-op preparation
retains the original fragment without unlocking the unavailable primary.

All 294 Proton-adapter JVM cases passed. All ten selected native cases passed on
an authorized physical device: four shared-title cases and six existing import/
preparation regressions. Application/test APK builds, lint (zero errors) and
independent source review passed. Calendeer was reopened after testing. No real
event or account attribute was modified by the isolated fixtures.

## Offline title metadata policy

`ProtonSharedTitleTimestampTest` exercises the separate plaintext `patchAt`
experiment with an explicit edit instant. Exact string comparisons permit only
SUMMARY, both DTSTAMP spans and clear LAST-MODIFIED to change. CREATED and the
original SEQUENCE stay exact, including case, folding and leading zeroes. Other
opaque spans and nested alarm metadata remain untouched. Logical no-ops return
the original strings without applying additional edit eligibility checks; UID,
title and the explicit instant's representation are still validated.

Refusals cover unsupported precision/range, backwards edit times, invalid dates,
creation after previous modification stamps, missing/duplicate/parameterized or
misplaced metadata, negative/nondecimal/overflowing sequence values, recognized
event-level recurrence/invitation markers, calendar/event METHOD and output byte
budget overflow. The legacy title-only API retains its earlier behavior.
These are plaintext policy checks, not event-level rights or service validation.
The plaintext method itself supplies no signatures. Earlier native title-only
crypto results did not qualify the new timestamp-bearing plaintext. Personal
partition timestamp handling remains unqualified; the later crypto continuation
below explicitly excludes those parts.
The legacy EXRULE marker was initially admitted; a targeted recurrence-refusal
test failed before adding the explicit exclusion in both shared partitions.
All 15 focused cases and the 309 Proton-adapter JVM cases then passed. Application
build, lint (zero errors) and independent source review passed. No device or
service operation was performed; this slice qualifies only the local text policy.

## Offline timestamped title signatures

`ProtonTimestampedSharedTitleTest` checks independent frozen copies of the original
row, clear fragment and encrypted fragment, retaining the original key packet and
original signatures without substituting the replacement parts. Nested mutable
input collections and getter aliases cannot change another fragment; diagnostics
redact all three values. These are container tests, not crypto evidence.

Two additional `ProtonSharedTitleCryptoDeviceTest` fixtures begin with binary- or
text-signed synthetic originals and an explicit edit instant. They compare the
clear text exactly against changes limited to DTSTAMP/LAST-MODIFIED, and the
decrypted text against SUMMARY/DTSTAMP changes. CREATED, raw SEQUENCE, all other
text spans and opaque row/part metadata remain unchanged. New signatures verify,
old signatures reject the changed text, and the original SharedKeyPacket still
decrypts the replacement ciphertext. The production opener and page decoder
read the synthetic replacement parts locally; imported content equals the original
decoded content with only the expected title changed, including original times,
description, reminders and recurrence state.

At the original instant, an unchanged clear fragment retains its exact signature.
A folded DTSTAMP that is rewritten at that same instant does receive a new
signature: comparisons concern actual text, not just logical date equality.
A different verified local clear author is admitted only when its part stays
unchanged. Changed clear authors must match the selected primary writer. Author
casing is preserved. No-op changes retain both original parts and require no
primary signing-key unlock, even when a distinct primary is unavailable and a
secondary can read the original. No-op results do not certify edit eligibility.

Refusals cover missing, null or nonempty personal/attendee arrays (also in no-ops),
backdates, nanoseconds, stale bindings, altered original signatures, invalid
calendar/WRITE context and unavailable local keys. Inputs are frozen before the
single local address read; cancellation propagates. Personal partitions remain
excluded rather than silently retaining potentially stale DTSTAMP values.

Both new native fixtures first failed on unchanged clear dates with the legacy
SUMMARY-only crypto preparation. After integration all 312 Proton-adapter JVM
cases and twelve selected native cases passed on an authorized physical device:
six shared-title cases and six existing import/preparation regressions. App/test
APK builds, lint (zero errors) and independent source review passed. The final
application was installed and reopened after testing. The fixtures use synthetic
in-memory keys and events, without service or Calendar Provider writes.

This verifies the restricted offline fragments, not an uploadable event envelope,
current access/session proof, timestamp-bearing personal parts, concurrent-write
protection or Proton UPDATE acceptance. Production UPDATE/DELETE remain disabled.

## Recoverable protected title preparations

`ProtonTitlePreparationCodecTest` covers the independent protected-format contract
using fabricated parts and a memory protector, without claiming cryptographic
evidence. Round-trips keep the original row, clear fragment and encrypted fragment
separate. Every expected scope/event/revision/UID/title/time binding must match,
and external headers and format must match before opening storage. Strict kind,
version, keysets and duplicate-key preflight reject ambiguous JSON, including
escaped duplicate names and duplicates within opaque originals.

Structural checks run before sealing and after reopening. They require exactly
the two original shared types, matching source identity, empty personal/attendee
arrays, a nonblank shared key packet, and no alternative address key packet.
Fragments preserve all fields except Data/Signature. Unchanged Data retains the
complete original part; changed Data need not have different signature bytes,
because the codec does not verify Proton crypto and text signatures authenticate
canonical text. The changed flag must agree with actual part differences.
The opaque base revision is compared to the expected observation, not recomputed
from unsigned JSON without bootstrap settings.

Tests also cover character/strict UTF-8 bounds before and after protection,
malformed Unicode, excessive JSON depth, invalid edit precision/range, filtered
storage/structure failures, exact cancellation propagation and redacted
diagnostics. Both inputs and opened fragments retain independent snapshots.
The round-trip test first failed on the initial unsupported codec. A later
focused key-profile test reproduced acceptance of a missing SharedKeyPacket
before the shared profile checks were added.

Two more `ProtonSharedTitleCryptoDeviceTest` cases call the real
`prepareAndSealTitleAt` entry with binary- or text-signed synthetic originals.
They recover through new AndroidCryptoContext/protector/codec instances before
running the full timestamp/signature/original-session-key/native-import checks.
Expected target, header generation and format mismatches are refused, and a
modified protected ciphertext yields a filtered storage failure. No bootstrap
or private key copy is added to the protected format. The native fixtures use
the existing Core Keystore protector, including its availability guard.

All eighteen codec cases and all 330 Proton-adapter JVM cases passed. Fourteen
selected native cases passed on an authorized physical device: eight shared-title
cases and six existing import/preparation regressions. App/test APK builds, lint
(zero errors) and independent source review passed. The final application was
installed and reopened after testing. No service or Provider fixture data was
modified.

These results qualify a recoverable protected adapter format and new-instance
recovery. They do not establish SQL durability, a killed-process journey,
automatic worker resumption, current Proton access, refreshed signature
verification on opening, server acceptance or concurrent-write protection.
Production UPDATE/DELETE remain disabled.

## Durable journal for protected title preparations

`LocalEditPreparationTest` checks the domain title-only/paused profile, exact
identity, explicit whole-second time, redacted diagnostics, strict UTF-8 limits
and empty-detail snapshot isolation. Context identifiers and both format names
cannot bypass the journal's row-size bounds. Legacy unknown detail data remains
unknown. The context-bound test failed before the missing limit was added.

`LocalEditPreparationStoreDeviceTest` uses isolated app-private SQLite fixtures.
It checks first retention/reopen, exact replay versus altered evidence, missing
or forged owners, foreign scopes/events, conflict between preparation and commit,
historical inspection after conflict/pause/catalog absence, lease loss,
cancellation and injected SQL rollback. Other cases check a 3 MiB payload beyond
the default cursor window, competing helpers, orphaned/changed owners, malformed
metadata, mismatched owner event/revision, mutable input isolation, opaque future
formats, additive schema 13 migration and shared staging-capacity rollback.
The first retention test failed on the initial store stub before implementation.

Two `LocalEditPreparationCryptoDeviceTest` cases call the real
`prepareAndSealLocalTitleEdit` wrapper for binary- and text-signed synthetic
originals. They capture the exact intent in an isolated ImportStore, retain the
sealed preparation, close the helper, and recover through a new helper and new
Keystore/codec instances. The recovered fragments then undergo original-key
decryption, renewed signature verification and native import/readback checks.
The remote cache, acknowledged baseline, local intent and projection queue stay
unchanged by preparation retention. No account, network or Provider operation
is used by these fixtures.

Run the two journal classes with AndroidJUnitRunner. Selected legacy
migration/reopen cases also require `providerFixture=true`; this opts in their
isolated fixtures, not a live synchronization journey. Downgrade fixtures remove
the new table before simulating an older schema. The crypto/journal fixture uses
the catalog's generation-zero scope; mismatched scopes must remain refused.

All seven new domain cases and the full 160 sync/330 Proton-adapter JVM cases
passed. Forty-one physical-device cases passed: nineteen journal cases, two
journal/crypto cases, eight shared-title regressions and twelve legacy
migration/reopen checks spanning schemas 1–12. The new journal case covers
13-to-14 migration. App/test APK builds, lint (zero errors, twenty existing
warnings) and independent review passed. The final APK was installed and launched.

The journal freezes the historical paused snapshot; current conflict state remains
in the owner table. It is not dispatch admission. The original qualification used
caller-supplied UID expectations. Helper reopening does not qualify a
killed-process journey or autonomous worker recovery. The journal does not
revalidate current Proton access or refresh signatures without remote context.
Production UPDATE/DELETE remain disabled.

## Record-only local-title preparation recovery

`ProtonLocalTitlePreparationCodecTest` checks a separate, versioned local-intent
format with fabricated fragments and a memory protector. Its round-trip opens
only a `LocalEditPreparation`, without an external UID or binding. The complete
typed local observation must match the sealed record: mutation, scope, event and
revision, observation format, paused state, both display snapshots and explicit
edit time. Cases cover all display fields, color null versus an override, unknown
versus empty details, timed/all-day type, endpoints, zone and nanosecond precision.
Equal titles and remote identities do not permit swapping another mutation's
sealed preparation.

Future, foreign and generic v1 formats are refused before storage opening.
Strict root/nested shapes, duplicate names, malformed Unicode, UID/source mismatch,
fragment preservation, depth/UTF-8 limits, redaction, storage failure and
cancellation retain the existing closed behavior. Empty detail lists are copied
before the protector can change the caller's mutable input. The first round-trip
failed on the initial unsupported entry point before implementation.

The two journal crypto fixtures now call the real local-intent preparer and
return the recovered SQL record to a new Keystore/codec instance. Its `openLocal`
call receives no separate UID or title binding. Additional native checks refuse
changed mutation/title/time/format and damaged ciphertext. The resulting original
row and separate fragments then pass the existing original-key decryption,
renewed-signature and native import checks. Generic v1 keeps its separate opener
and regression fixtures; no journal migration or legacy conversion is performed.

All eighteen new contract cases and all 348 Proton-adapter JVM cases passed.
Twenty-nine selected physical cases passed: nineteen store cases, two record-only
crypto/journal cases and eight shared-title regressions. App/test APK builds,
lint (zero errors, twenty existing warnings) and independent source review passed.
The final application was installed and launched. These fixtures use no service
or Calendar Provider writes.

This qualifies opening a retained preparation without a caller UID dependency,
not a killed-process journey or automatic worker resumption. Opening authenticates
the saved local observation; it does not requalify old records against remote
content. Current rights/context, refreshed
signature verification and concurrent-write protection remain separate work.
Production UPDATE/DELETE remain disabled.

## Complete imported-base qualification before local-title sealing

`ProtonLocalTitleBaseCryptoDeviceTest` uses synthetic Core keys with binary and
text shared signatures. Its first description-mismatch assertion failed against
the previous implementation, which accepted the mismatched intent and sealed it.
The corrected wrapper refuses an unsupported observation format and compares the
complete frozen base with the supported upsert decoded using the same key/address
context. No patch, signing or protection is admitted on a mismatch or deferred
decode.

The fixtures refuse differences in description, location, color, null details,
start/end, nanosecond precision, zone identity and timed/all-day type. They count
protector calls to require zero seals on every refusal. Malformed notification
metadata retains valid shared signatures and passes title opening, but defers in
the full importer and is refused by preparation. A positive case mutates the
caller-owned row, settings, metadata and empty reminder list during the local
address read: the preparation retains the frozen intent and performs exactly one
address read. Fresh Keystore opening, original-key decryption, renewed-signature
verification and full native import then check the resulting fragments.

This qualifies fresh preparation against the supported mapping of the supplied
observation, including observed unsigned metadata. It does not authenticate every
opaque remote field, refresh access or establish concurrent-write safety. Opening
older local-title v1 records does not apply the new preparation guard or migrate
their contents. No service or Calendar Provider fixture writes are required;
production UPDATE/DELETE remain disabled.

The 348 Proton-adapter JVM cases passed with zero failures or errors. The eighteen
selected native cases passed: two new base checks, two journal/crypto round-trips,
eight shared-title regressions, five create/preparation regressions and one import
crypto regression. App/test APK builds and lint passed (zero errors, twenty
existing warnings), as did independent source review. The final application was
installed and launched after testing.

## Authenticated local-title preparation boundary

`ProtonLocalTitlePreparationAdapterTest` uses controlled session/read/crypto ports
to check ordering, scope and UID origin, fresh WRITE permission, local keys, changed
revisions, missing/foreign identifiers, format/profile/time preflight, and freezing
of caller-owned intent/metadata/bootstrap/event/address lists across suspensions.
It refuses incompatible returned records and checks sanitized authentication,
storage, network, missing-read, retry and unsupported failures. Cancellation remains
cancellation at every suspending stage. The first success case failed on the initial
unsupported stub before implementation; this is new-feature RED evidence.

Two `SharedProtonPortsTest` cases exercise the actual shared facade: logout cleanup
waits for a held preparation, later preparations are refused, and empty details are
frozen before dispatcher switching and waiting for the mutex. A logout request
does not imply immediate cancellation of preceding work.

`ProtonAuthenticatedTitlePreparationDeviceTest` combines controlled read responses
with real Core crypto using the same `localTitleCryptoPreparation` factory as the
runtime. Binary and text signature fixtures refuse read-only membership, a changed
revision and mismatched decoded content with zero seals. The positive path performs
one local address read and one seal, then exercises fresh Keystore opening,
original-key decryption, renewed-signature verification and complete native import.
No account/service or Calendar Provider writes are used by these fixtures.

These tests qualify controlled protocol/session behavior and native crypto wiring.
Live service qualification of this port, killed-process orchestration, automatic
capture/retention, scope generation/mode admission and concurrent-write protection
remain separate work. Successive authenticated GETs are observations, not an atomic
server snapshot. Existing sealed records receive no migration or retroactive
qualification; UPDATE/DELETE remain disabled.

All thirteen new adapter cases and both shared-facade cases passed, with all 363
Proton-adapter and 160 sync JVM cases. Twenty selected native cases passed: the
two new context/crypto fixtures plus eighteen base/journal/shared-title/create/
import regressions. App/test APK builds, lint (zero errors, twenty existing
warnings) and independent source review passed. The final application was
installed and launched after testing.

## Captured local-title preparation and atomic retention

`LocalTitlePreparationCoordinatorDeviceTest` uses isolated real SQL with controlled
account, permission and preparation ports. Its first retention assertion failed
against the unsupported stub before implementation. Twenty-three cases then check
first retention/reopening, historical replay without new preparation or timestamp
replacement, scope/mutation/profile/time/format gates, session/account/permission
loss, calendar pause and catalog loss, import/deferred conflicts, cancellation,
incompatible returned records and owner replacement while preparation is running.

The final insertion transaction also refuses a SQL baseline removed after the
last account read and preserves an independently inserted first record. Generic
or future saved formats return HISTORICAL without reinterpretation. Remote
conflicts conditionally mark only the exact paused owner, without a new imported
base; an SQL trigger rejects that transition to verify rollback, a sanitized result
and explicit retry. A cancelled coroutine after port completion retains no capsule.
The transaction lease closes after failures and cancellation; no dispatch or
Android acknowledgement is admitted.

The two native `LocalEditPreparationCryptoDeviceTest` fixtures now pass their
already produced Core preparations through this coordinator and journal, repeat
as HISTORICAL without another port call, and reopen with a different SQL helper.
The recovered record then passes fresh Keystore opening, original-key decryption,
renewed signatures and native readback. These fixtures qualify coordinator-to-SQL
retention of native crypto evidence; their preparation port returns a controlled
record, so they do not establish an end-to-end coordinator/service GET journey.

All twenty-three new cases and fifty-five selected physical cases passed:
twenty-three coordinator, nineteen journal, two native journal/crypto, nine edit
capture and two native adapter-context regressions. All ten app JVM cases passed.
App/test APK builds, lint (zero errors, twenty existing warnings) and independent
source review passed. The final application was installed and launched.

The observed projection base is SQL history, not current Android contents. No
service or Calendar Provider fixture writes occur. Live port qualification,
killed-process/automatic resumption and server concurrency remain unqualified.
Preparation journal columns and historical opening are unchanged; production UPDATE/DELETE remain
disabled, and no UI or worker automatically calls this entry.

## Retained-session live local-title preparation

`CalendarLocalTitlePreparationLiveDeviceTest` is opt-in through
`calendarLocalTitlePreparation=true`, with an explicitly configured retained
synthetic fixture token and exact account identity hash. It uses the previous
known-empty CREATE fixture only after its submission was recorded; inspection is
read-only and the original attempt remains non-resendable. The exact account is
checked before event reads. Missing, changed, inaccessible or no longer imported
fixtures fail rather than creating another event or resetting the session.

The test reads the complete remote fixture and acknowledged production baseline,
then captures a title-only intent into isolated real SQL. The real authenticated
preparation port performs fresh context/original reads through the coordinator;
the isolated scheduler and projection have no write effects. First retention and
historical replay pass, with only one call to the preparer. Independent helpers
inspect the journal without reopening the coordinator's closed transaction lease.

Debug-only `ProtonLocalTitlePreparationProbe` opens the complete retained record
with a fresh Keystore codec and reads calendar bootstrap under the authentication
mutex. The production native decoder verifies the original signed row against the
complete base and the renewed shared fragments against the complete intended
content. Replacement fragments are combined only in memory; no UPDATE request
is constructed. Session identity is checked after reads and native decoding.
An altered local intent and corrupted ciphertext are refused. Diagnostics return
only booleans; no keys, payloads, identities or event text are printed.

The live case passed on the authorized physical device. A second authenticated
inspection returned the exact original remote event. The production SQL baseline,
absence of captured edit, original CREATE journal, selected scopes and owned
Provider identity/revision/dirty witness were unchanged. Twenty-seven native
regressions also passed: twenty-three owner cases, two journal/crypto cases and
two adapter-context cases. App/test APK builds, lint (zero errors, twenty existing
warnings) and independent review passed. The final app was installed and launched;
the retained service fixture, session and permissions were preserved.

This qualifies one simple known-empty live preparation and protected native
recovery profile. Normal application startup may schedule ordinary read-only
workers. The Provider witness does not measure present event contents; service
observations are successive reads, not a concurrency guarantee. Other event
profiles, general editable mode and live UPDATE/DELETE remain unqualified. The
ordinary isolated SQL fixture is removed after each test; the explicit process
qualification below retains it across phases. No remote event is edited or deleted.

## Offline local-title recovery after a controlled process stop

The live title fixture additionally accepts `titlePreparationPhase=PREPARE_STOP`.
It performs the same real preparation, recovery and original-event inspections,
then closes all SQL helpers and saves a private on-device manifest. The manifest
pins scope, mutation, event, observation/preparation formats, timestamp, ciphertext
digest and process PID. Existing manifests/databases block another preparation;
there is no automatic reconstruction or replacement of missing recovery data.

The host observes `title_preparation_ready_for_stop=true` together with all
completed preparation witnesses, verifies that exact PID is still alive, runs
`am force-stop` and checks that no application PID remains. This intentionally
aborts the waiting instrumentation. Its abort is accepted only after the marker,
exact live PID and host stop; it is not reported as a normally passed JUnit case.
The host waits for its ADB helper to finish before continuing or releasing device
ownership, including on failure.

`CalendarLocalTitleRestartDeviceTest` then runs with
`calendarLocalTitleRestart=true` and `titleRestartPhase=VERIFY`, using the same
explicit fixture/account witnesses. It has no Activity rule, sign-in restoration
or real Proton port. A different process and absent active network are required.
It reads the preserved journal without reseeding it, checks every manifest binding,
and compares the paused owner, projected baseline, imported base and empty pending
projection queue. A fresh Keystore codec opens the complete historical record.
A different account witness, substituted timestamp and corrupted ciphertext fail.

The coordinator uses controlled historical account/permission observations and
a preparation port that fails if invoked. Replay returns HISTORICAL without
changing the record or timestamp. The network must remain absent through the
end of verification. `titleRestartPhase=CLEANUP` removes only the manifest-bound
isolated database and preferences; the original service fixture is retained.

The physical host journey passed: committed marker and exact PID stop/absence,
offline verification in a different process, then cleanup. VERIFY and CLEANUP
each completed with `OK (1 test)`. App/test APK builds, lint (zero errors, twenty
existing warnings) and independent review passed. Airplane mode, Wi-Fi and mobile
data settings were captured and restored exactly, checked before final launch.

The offline probe authenticates protected storage and historical binding only;
it does not perform fresh signature verification, read current server data or
renew access rights. Application startup can still initialize ordinary local
bookkeeping. This stop occurs after a completed commit, not during a transaction
or simultaneous-process race. Power-loss/reboot recovery, automatic resumption,
other event profiles and live UPDATE/DELETE remain unqualified.

## Explicit Android capture and local-title preparation

`LocalTitleCapturePreparationDeviceTest` is opt-in through `providerFixture=true`.
It uses real isolated Android Calendar Provider rows and SQLite journals with
synthetic accounts, plus controlled session, remote and preparation ports. It
submits no Proton request and cleans only its isolated fixtures.

Eighteen cases cover capture and retention, historical replay, later Android edits,
non-title content, invalid timestamps, clean/no-op/wrong-revision rows, newer
remote imports before and during preparation, reconnect and pause/resume at the
capture boundary, missing access, permission loss, network retry, unsupported
preparation, cancellation after capture and during preparation, and
account-observation failures.
Assertions preserve the complete captured intent and dirty Android row, check
immutable historical evidence and closed admission, and inspect the journal
through independent helpers after the coordinator releases its lease.

Two existing `LocalEditPreparationCryptoDeviceTest` cases also use the combined
entry with controlled capture and preparation ports. They transport a capsule
already produced by native Proton Core primitives, then reopen it through fresh
SQL/Keystore helpers and verify the original and renewed fragments. This connects
the orchestration to native protected recovery; it does not qualify a fresh
authenticated preparation starting from a real Android row.

The feature-absence RED test uses an `UNSUPPORTED` stub and fails the nominal
Provider case with expected `RETAINED`; it is not evidence of an old defect.
All seventy-one selected physical cases passed: eighteen combined-entry cases,
nine capture regressions, twenty-three preparation-owner regressions, two native
crypto/journal cases and nineteen journal cases. All ten app JVM cases passed.
App/test APK builds, lint (zero errors, twenty existing warnings) and independent
source review passed. The application was installed and launched after the run.

The boundary remains explicit and preserves the first durable intent before
preparation. It does not grant a current Provider-content lease, acknowledge
Android changes, resolve conflicts automatically or enable UPDATE/DELETE.

## Integrated live Android capture and title preparation

`CalendarCapturedTitlePreparationLiveDeviceTest` is opt-in through
`calendarCapturedTitlePreparation=true`, an explicit retained fixture token and
exact authorized account hash. It requires the previously submitted known-empty
CREATE fixture, its non-resendable journal and acknowledged production baseline.
Account identity is checked before authenticated event reads. Missing or changed
fixtures fail instead of creating a replacement or resetting authentication.

The test creates a temporary native Provider mirror under the existing Android
account, using a random positive generation distinct from every selected scope.
Its calendar must be newly created and absent from the initial account calendar
set. A fixture-only projection port translates the exact SQL scope and base to
this private Provider generation, then calls the real `captureOwnedSimpleEdit`.
It fabricates no snapshot and refuses other projection operations. Its admission
and permissions delegate to the native implementation. Namespace isolation is
explicit: this does not establish the unmodified production Provider binding or
an atomic namespace reservation against a concurrent creator.

The isolated SQL baseline is seeded and acknowledged; no local edit is inserted
by the fixture. A sync-adapter update changes only TITLE and DIRTY on the exact
temporary row and calendar. The combined coordinator entry captures the complete
native snapshot and calls the real authenticated preparation port. First retention
must equal the full expected intent and explicit timestamp. After a second Android
title change, replay returns HISTORICAL with one capture and one preparation,
preserving the exact first record and the later dirty title.

Independent SQL helpers reopen the retained record without reopening coordinator
admission. Fresh Keystore/native verification checks the original and renewed
signed fragments; a substituted intent is refused. Another authenticated read
retains the complete original remote event. Production baseline, absent captured
edit, complete Provider event row/identity, CREATE journal, session and selected
scopes are unchanged. A separate Provider instance inspects production witnesses
without changing the capture port's closed admission.

Non-cancellable cleanup removes only the calendar proven newly created in the
private namespace and the isolated database. It checks disappearance of that
calendar, the original account/calendar set and production row afterward. It never
removes the real Android account or retained remote event.

The physical case passed with `OK (1 test)` and all five completion witnesses:
real capture/preparation recovery, preserved later Android edit, unchanged remote
and production data, completed isolated cleanup and disabled outgoing UPDATE.
Test APK build, lint (zero errors, twenty existing warnings) and independent source
review passed. The final app was installed and launched. Production sources were
unchanged; earlier controlled tests remain separate evidence rather than newly
reported runs.

This qualifies one simple known-empty integrated fixture. It performs no remote
write, no Samsung Calendar UI edit and no forced process interruption. Successive
server reads are not concurrency protection; automatic capture, other profiles,
conflict resolution and live UPDATE/DELETE remain unqualified.

## CREATE-only transport permits on the shared sync endpoint

`ProtonCreateOnlyDispatchTest` uses loopback HTTP only. Its RED case obtains an
original event through GET, advances the fixture's remote state and submits an
UPDATE-shaped body with a CREATE single-dispatch tag. The original transport
permit accepts it and the fixture's newer value is overwritten. The same case
must preserve the newer value and observe only one GET after envelope validation.
This reproduces an internal transport gap, not an observed production upload or
a Proton backend defect.

Six new tests cover that race, UPDATE/DELETE/move/mixed containers, import and
overwrite, malformed or ambiguous JSON, strict field types, current shared-only
and explicit-empty profiles, byte-identical bodies and the 4 MiB UTF-8 boundary.
Eight existing OkHttp and three published Core/Retrofit tests use a valid synthetic
CREATE envelope instead of an empty placeholder. They retain their checks for
503 follow-ups, token refresh, rebound/rewrapped bodies, redirects, lost responses,
timeouts, cancellation, concurrent invocation and repeated interceptor exchanges.
The deliberately unguarded 503 reproduction still demonstrates why the connection
retry flag alone is insufficient. All seventeen focused cases passed.

Debug-only `ProtonEventPreparationCryptoProbe` additionally passes native generated
shared-only requests and reopened explicit-empty submission requests through the
new permit. It writes their one-shot bodies into a local buffer and compares exact
bytes, without creating an HTTP call. Existing timed/all-day, protected recovery,
signature/readback and folded-whitespace assertions remain. This checks native
producer compatibility, not fresh live CREATE acceptance.

All 369 Proton-adapter JVM cases passed without failures, errors or skips. Five
physical native preparation cases passed; four exercise the added permit/body
check through generated shared-only or reopened explicit-empty requests. Both
app/test APK builds, lint (zero errors, twenty existing warnings) and independent
source review passed. The final app was installed and launched after testing.
No live mutation or server-precondition experiment was performed.

No domain model, capability, journal schema, stored preparation or outgoing worker
changes. Constructor refusal has sanitized diagnostics without the payload or
underlying cause. If durable admission occurred earlier, its unknown outcome stays
recorded; refusal is not a reason to reopen or resend it. UPDATE/DELETE remain
disabled pending a qualified concurrency strategy.

## Read-only inspection of captured UPDATE history

`LocalEditReviewTest` establishes the domain comparison with eight cases. Its
initial case failed with UNOBSERVED instead of UNCHANGED before the relation was
implemented. The suite distinguishes absent records from explicit tombstones,
unknown/incompatible checkpoint formats, deferred evidence, changed opaque
revisions or content, retained conflict, missing projected bases and non-title
capture. Foreign event identities are refused and diagnostic strings are redacted.

`LocalEditInspectionDeviceTest` uses real isolated SQLite databases and controlled
session/permission ports that refuse every service and Provider operation. Its
seventeen cases cover exact owner reads, preserved preparations and monotone
conflicts after an import returns to its base, tombstones versus absence, deferred
data, format uncertainty, paused/unavailable known calendars, scope/mutation
isolation, initial/final permission and account loss, reconnect and policy changes,
cancellation, sanitized failures, corrupt owners/preparations/imported/projected
identities, DELETE refusal and independent helper reopening. Applicable reads
compare every SQL table before/after and check scheduler counts and closed leases.
Reconnect/policy fixtures hold the next asynchronous owner's session read until
cleanup so its separate lease cannot mask the inspected owner's closure.

All 168 sync and ten app JVM cases passed via `:sync:test` and
`:app:testDebugUnitTest`. Native execution selected `LocalEditInspectionDeviceTest`,
`LocalEditPreparationStoreDeviceTest` and
`LocalTitlePreparationCoordinatorDeviceTest`: all fifty-nine physical cases passed
without failures or skips. App/test APK builds and `:app:lintDebug` passed with
zero errors and twenty existing warnings. Independent source review passed, and
the final app was installed and launched after isolated database cleanup.

The SQL snapshot is atomic only inside its private transaction. Selection/session
checks are separate observations; historical SQL records do not establish current
Provider or server contents. Retained title metadata never renews crypto/access or
permits dispatch. No schema migration, UI/worker caller, automatic resolution,
Provider fixture write or service request is added. UPDATE/DELETE remain disabled;
live conflict resolution and conditional writes are NOT RUN.

## Retained local changes in the calendar controls

`LocalChangeSummaryDeviceTest` uses isolated real SQL for five cases. Its targeted
RED case found zero retained changes after an UPDATE capture instead of one before
the metadata query was added. The completed cases cover UPDATE and DELETE, conflict
counts that remain after an imported return to the base, account/calendar/generation
isolation, independent helper reopening, paused/unavailable scopes, zero capture
and unreadable opaque journal contents. The summary does not decode event payloads,
change retained owners/bases or release their suppressed projections. Constructor
checks prevent negative counts or a conflict count exceeding retained changes.

`CalendarImportUiDeviceTest` now has twelve cases, including four new cases for
counts with missing permission, paused/unavailable calendars, updated counts,
zero/sign-out hiding and no unsolicited action. An isolated SQL-to-panel journey
captures a synthetic UPDATE and passes real selection metadata to the composable;
captured/base titles and event/mutation IDs remain absent from its semantics tree.
A private synthetic French light presentation capture was visually inspected:
the count and explanation are readable without clipping. XML/placeholder checks
passed for all eight languages; rendering in every language/theme remains NOT RUN.

Physical execution selected `LocalChangeSummaryDeviceTest`,
`CalendarImportUiDeviceTest`, `LocalEditStoreDeviceTest` and
`ImportStoreDeviceTest`. All forty-three cases passed without failures or skips.
All ten app JVM cases passed through `:app:testDebugUnitTest`; app/test APK builds
and `:app:lintDebug` passed with zero errors and twenty existing warnings.
Independent source/documentation review passed. Isolated SQL databases and the
temporary device capture were cleaned, and the final app was installed and launched.

The fixtures make no service or Provider event requests. The UI exposes metadata
only; no event viewer/editor, capture callback, conflict resolver or dispatch is
added. Counts describe existing journal entries, not every current Android edit
or present service conflict. CREATE experiment journals are excluded. Journal
columns and UPDATE/DELETE dispatch restrictions are unchanged.

## Read-only inspection of captured DELETE history

`LocalDeleteReviewTest` adds seven pure-domain cases. The initial unchanged-import
case failed with UNOBSERVED instead of UNCHANGED before implementation. UPDATE and
DELETE now share the same format/deferred/absence/change/tombstone policy, with
the eight existing UPDATE comparison cases retained as regression checks. DELETE
tests preserve unchanged deletion content, edits observed before deletion, both
captured versions and monotone recorded conflict. Foreign identities are refused
and diagnostic strings omit contents and identifiers.

`LocalDeleteInspectionDeviceTest` has eighteen isolated real-SQL cases with
controlled session/permission ports that refuse Provider contents and service
requests. They cover exact owners, unchanged and edited deletion snapshots,
remote tombstones versus absence, retained conflict after an imported return to
base, deferred/format uncertainty, paused/unavailable scopes, scope/mutation
isolation, initial/final permission/account loss, reconnect/policy invalidation,
cancellation, generic failures, corrupt identities/revisions, non-integer/out-of-
range versions, incompatible preparation presence, UPDATE-kind refusal and helper
reopening. Applicable reads compare every SQL table before/after, scheduler counts
and closed leases. Reconnect/policy fixtures suspend the next asynchronous owner's
session read so its lease cannot mask the inspected owner's closure.

All 175 sync and ten app JVM cases passed via `:sync:test` and
`:app:testDebugUnitTest`. Physical execution selected
`LocalDeleteInspectionDeviceTest`, `LocalEditInspectionDeviceTest` and
`LocalEditStoreDeviceTest`: all fifty-four cases passed without failures or skips.
App/test APK builds, `:app:lintDebug` (zero errors, twenty existing warnings) and
independent source review passed. Isolated databases were cleaned and the final
app was installed and launched.

Only the private SQL snapshot is atomic. Separate account/permission observations
do not establish current remote/Provider contents or concurrency protection.
Remote deletion evidence never acknowledges the local deletion, and retained
content is not a complete undo image. No UI/worker caller, preparation payload,
schema migration, service/Provider fixture write or dispatch is added. Live DELETE
and conflict resolution remain NOT RUN; UPDATE/DELETE submission stays disabled.

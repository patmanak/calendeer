# Calendar working method

## Entry and handoff

The primary agent reads `AGENTS.md`, `docs/STATUS.md`, then only the artifacts
needed for the current task. Existing owner decisions carry across sessions.
Read the optional root `AGENTS.local.md` for private communication and environment
preferences, including when delegating. Do not copy its contents into public
artifacts. Maintained project artifacts use English.

| Phase | Agent | Skill | Concrete result |
| --- | --- | --- | --- |
| Specify | `calendar_specifier` | `calendar-specify` | Observable requirements, acceptance cases, open decisions |
| Research | `calendar_proton_researcher` | `calendar-proton-research` | Versioned source evidence and reuse limits |
| Plan | `calendar_planner` | `calendar-plan` | Interfaces, alternatives, dependency-ordered slices |
| Develop | `calendar_developer` | `calendar-implement` | One integrated behavior change and focused evidence |
| Test | `calendar_tester` | `calendar-verify` | Behavioral tests and actual verification results |
| Review | `calendar_reviewer` | `calendar-verify` | Independent findings against the requested outcome |

These are roles to invoke when useful, not six mandatory agents for every change.
Use a researcher before implementation if an API or dependency is uncertain.
Review substantial plans before spending implementation effort; review risky
changes against the actual code and tests rather than the developer's summary.

## Specification

Start from the owner's problem and the current conversation. Separate agreed
requirements from suggestions. Assign stable requirement IDs only when drafting
real requirements. Each requirement needs an observable acceptance scenario.
Keep scope, non-goals and unresolved choices explicit.

Use [the specification template](templates/specification.md) as a writing aid,
not a questionnaire to fill mechanically. Follow the current authorized phase.
When specifications are requested, present a concrete draft before
asking for decisions; do not substitute an approval question for the draft.

## Plan and interface design

Plan backward from acceptance evidence. Each slice crosses the minimum necessary
boundaries to deliver a demonstrable behavior. Name dependencies, owned files,
verification and recovery. Avoid separate "all interfaces / all code / all tests"
milestones that cannot demonstrate working behavior.

Proton integration contracts belong to this project. They define capabilities,
opaque identity/revision handling, pagination/checkpoint semantics, unsupported
data preservation, cancellation and errors. Do not assume server idempotency,
atomicity or incremental sync guarantees without source or experiment evidence.

Keep proposed domain/sync rules, Android Calendar Provider integration, local
persistence, and Proton adapters separate. A future adapter should satisfy the
same accepted behavioral contracts. Its tokens/cursors may need migration or
reconciliation; replacing a transport does not imply all state is compatible.

Use an ADR only for an actual consequential decision (context, alternatives,
choice, consequences). Do not create a speculative abstraction framework or
select the full technology stack during workflow preparation.

## Delegation

The primary agent stays responsible for the outcome. A delegation message includes:

- the question or accepted slice, and the exact relevant inputs;
- writable paths, or "read-only";
- the observable result and required evidence;
- side-effect limits and unresolved dependencies.

At most three children run concurrently; no nested delegation. Read-only source
research and reviews can run in parallel. Freeze shared interface decisions
before parallel implementation and give every file one active writer. Children
do not edit the shared status record or create external tasks/issues.

The primary agent examines the actual changes and reported evidence, resolves
conflicts, and records the next action. If agents are unavailable, apply the
same role instructions sequentially and disclose that the review was not
independent. A discovered blocking correctness defect is fixed before claiming
completion; optional style feedback does not create a repeated approval cycle.

## Development and testing

For behavior changes, work in small failing-test / implementation / verification
cycles where the test can express the behavior. For debugging: reproduce, collect
evidence, form a falsifiable hypothesis, change the cause, then rerun the relevant
check. Do not stack speculative fixes or generate implementation-mirroring tests.

Use domain tests for pure policy, adapter contracts for interchangeable behavior,
integration tests for persistence/provider boundaries, and physical-device plus
Proton observations for supported user journeys. A mock server or fake adapter
does not qualify the real service or Samsung interoperability.

Calendar-specific risks to select according to the accepted scope include:
timezone/DST, all-day events, recurrence exceptions, deletions, unknown fields,
offline edits, interrupted uploads, lost acknowledgements, duplicate prevention,
permissions, process restart, account isolation and quiet no-change sync.
This list is a risk menu, not a promise that v1 supports every capability.

## Evidence and resumption

Use [the slice template](templates/implementation-slice.md) and
[verification template](templates/verification.md) when those artifacts are
needed. Trace requirement -> slice -> check -> evidence. Source existence, source
compilation, device behavior and live server compatibility are distinct claims.

Only run commands grounded in the actual project; do not invent Gradle tasks
before a build exists. A missing environment is BLOCKED/NOT RUN, not PASS. Keep
private evidence under ignored `.local/`; commit only sanitized summaries.

Keep run-specific evidence, questions, assumptions awaiting operator decisions
and handoffs under ignored `.local/`. Update `docs/STATUS.md` only with reusable
implementation state and product limitations. Do not publish session diaries,
workstation details or personal decision trackers.

## Native Codex setup

Custom roles use standalone `.codex/agents/*.toml` definitions. Skills live under
`.agents/skills/<name>/SKILL.md`; their `agents/openai.yaml` files provide UI labels.
Model and reasoning settings are inherited. Research/review roles request a
read-only sandbox and also carry explicit no-write instructions.

The files follow the official [subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents)
and [skill discovery documentation](https://learn.chatgpt.com/docs/build-skills),
checked on 2026-09-26. Native loading must be verified in a session rooted in
Calendar; file validation alone does not establish it. No global installation,
MCP service, external issue tracker or upstream workflow runtime is required.

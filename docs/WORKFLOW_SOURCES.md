# Workflow sources and adaptation decisions

Reviewed on 2026-09-26. These are selected, rewritten project methods, not a
verbatim installation or a claim that the upstream frameworks are active.
The [source lock](workflow-sources.json) records exact revisions and inspected
files; original MIT notices are in [licenses](licenses).

| Source | Pinned revision | Retained ideas | Calendar adaptation |
| --- | --- | --- | --- |
| [obra/superpowers](https://github.com/obra/superpowers/tree/8ca22dba9a94f28898bbce59f2537ff4d87c747d) | `8ca22dba9a94f28898bbce59f2537ff4d87c747d` | Clarify intent, bounded plans, causal debugging, independent review, evidence before completion | Five focused skills; no global hooks, universal brainstorming trigger, forced worktree or repeated phase approvals |
| [addyosmani/agent-skills](https://github.com/addyosmani/agent-skills/tree/2686b620fc1fed2e8f60c704839c766b8594c6b6) | `2686b620fc1fed2e8f60c704839c766b8594c6b6` | Specifications, behavioral interfaces, source/version evidence, test and review roles | Android/Proton boundaries, source-vs-runtime evidence and concrete data-loss cases; no web-only tooling or generic approval gates |
| [mattpocock/skills](https://github.com/mattpocock/skills/tree/c55ee46073ed923f86ce59a5eb3b6d895095d1b7) | `c55ee46073ed923f86ce59a5eb3b6d895095d1b7` | Synthesize existing conversation, small interfaces hiding complexity, vertical slices, public-behavior tests | Local specs/plans instead of publishing tracker issues; ask only unresolved questions; do not mandate exhaustive stories or one testing boundary for every layer |
| [open-gsd/gsd-core](https://github.com/open-gsd/gsd-core/tree/b3a055c07d6577f5bcbce8d360653ddd6979d1c5) | `b3a055c07d6577f5bcbce8d360653ddd6979d1c5` | Work backward from outcomes, requirement coverage, dependency ordering, verify real integration, durable resumption state | One STATUS record and lightweight templates; no GSD runtime, database, CLI wrappers, large agent fleet or automatic phase advancement |

## Coverage by local artifact

- `calendar-specify`: Superpowers brainstorming, Addy specification method, Matt
  conversation-to-spec synthesis. Owner intent is recovered before new questions.
- `calendar-plan`: Superpowers plans, Addy interface contracts, Matt module design
  and vertical tickets, GSD outcome-first planning and plan checking.
- `calendar-proton-research`: Addy source-driven development, specialized for
  repository completeness, protocol evidence and Proton dependencies.
- `calendar-implement`: Matt behavior-first test slices and Superpowers causal
  debugging; implementation follows current authorization and accepted scope.
- `calendar-verify`: Superpowers evidence discipline, Addy test/review roles,
  GSD outcome/artifact/wiring verification and requirement coverage.
- `docs/STATUS.md` and handoffs: GSD resumption concepts, reduced to one current
  record owned by the primary agent.

## Conflicts deliberately resolved

Upstream documents sometimes demand different approval sequences, terminology,
test styles, issue trackers or runtimes. Those instructions were reviewed as
source material and were not installed as controlling skills. Calendar uses one
coherent workflow, with owner instructions above local defaults.

The framework preparation does not authorize Android implementation, create
product decisions or publish anything externally. Later explicit requests can
authorize planning and execution together; do not ask for the same authority
again at each substep. A concrete missing product decision can still require a
question. Routine reversible edits and focused checks proceed autonomously.

Independent review is requested for substantial or risky changes, not every file
edit. Tests target behavior at useful boundaries; documentation edits need
structural validation. Real Android/Proton behavior requires its own evidence.

## Maintenance

Updates are deliberate: review relevant upstream changes, adapt only useful
changes, update the pinned record and notices, then rerun local validation.
No startup-time downloads or automatic upstream instruction replacement.

The upstream MIT notices apply to reused/adapted workflow material. This record
does not choose the future application license or relicense Proton dependencies.

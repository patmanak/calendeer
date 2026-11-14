# Calendar working method

## Entry and roles

Read `AGENTS.md`, optional private `AGENTS.local.md` and the compact
[status](STATUS.md) once per assignment. Then load only the relevant contract and
skill. Reuse unchanged context on continuations; changed authority or scope still
requires checking the affected instructions. Maintained artifacts use English.

| Need | Optional role | Skill |
| --- | --- | --- |
| Requirements | `calendar_specifier` | `calendar-specify` |
| Uncertain Proton API/source | `calendar_proton_researcher` | `calendar-proton-research` |
| Consequential interfaces/sequence | `calendar_planner` | `calendar-plan` |
| Independent implementation slice | `calendar_developer` | `calendar-implement` |
| Focused behavioral checks | `calendar_tester` | `calendar-verify` |
| Independent correctness review | `calendar_reviewer` | `calendar-verify` |

These are available roles, not a mandatory pipeline. The primary handles routine
work. Substantial/risky changes get one focused independent review; another round
is justified by a new material defect or changed contract, not each small edit.

## Bounded delegation

Use at most two concurrent children, normally one. No grandchildren. Prefer a
fresh context with a short self-contained brief (`fork_turns="none"` when exposed),
not the entire conversation or a long-lived agent carrying unrelated tasks.
A brief names the outcome, relevant paths/symbols, write scope, side-effect limits,
acceptance checks and results already obtained. Read-only reviewers never edit.
Each file has one writer. Children do not publish, commit, edit shared status or
start unassigned builds/device operations. The primary coordinates checks once.

Subagents use the inherited model, medium reasoning and low verbosity by default.
Use higher reasoning only for a specific complex correctness question. Target
150-word final reports: result/findings, changed paths, actual checks, blockers.
Do not omit important findings to meet that target. Send intermediate messages
only for a blocker or contract change. No ritual approval/freeze exchanges,
repeated GO requests, unchanged polling or duplicate parent/child build runs.

## Read, implement and verify

- Search paths/symbols with `rg`; read bounded sections before whole files. Do not
  reread all of `VALIDATION.md`, specifications or source audits at startup.
- Plan from accepted behavior into the smallest integrated slice. Record a real
  consequential decision when needed; do not create speculative abstractions.
- Before starting a batch, write in the journal the milestone item it serves, the
  expected user-visible result, the evidence level (E1-E4 in `AGENTS.md`) and a
  budget of about 400 changed production lines. When the budget is exceeded, stop,
  commit what is coherent and move the rest into a new batch.
- Preserve the Proton boundary, opaque state compatibility and synchronization
  invariants in `AGENTS.md`. Unknown capabilities need source/evidence, not guesses.
- Reproduce a sync defect, form a falsifiable cause, fix it, rerun focused checks.
  Select tests for touched policy, adapter, SQL or Provider boundaries. Broaden only
  after relevant changes/failures; skip Android builds for workflow-only changes.
- Save verbose output once in ignored `.local/`; return counts and relevant
  failure excerpts. Use `--tests` or explicit instrumentation classes when appropriate.
  Do not dump successful full logs or stable private identifiers into messages.
- Distinguish source review, fakes, physical Android and live Proton/OEM evidence.
  Missing hardware is BLOCKED/NOT RUN, not PASS. Private device rules still apply.

Templates in [templates](templates) are optional writing aids. Keep open questions,
run reports and handoffs ignored under `.local/`. Public status stays short and
current; link maintained contracts instead of copying their history. After
compaction, resume from the compact state and relevant evidence without replaying
completed research/tests. Owner decisions survive handoffs.

## Configuration

Native roles live in `.codex/agents/*.toml`; project settings in
`.codex/config.toml`; skills in `.agents/skills/*/SKILL.md`. Files define defaults
for future applicable sessions; a live chat can retain earlier settings. Follow
this bounded method immediately without claiming a hot reload was verified.

Six project agents are defined in `.codex/agents/` (`calendar_specifier`,
`calendar_planner`, `calendar_proton_researcher`, `calendar_developer`,
`calendar_tester`, `calendar_reviewer`) and five skills in `.agents/skills/`
(`$calendar-specify`, `$calendar-plan`, `$calendar-proton-research`,
`$calendar-implement`, `$calendar-verify`). The primary agent coordinates the work
and owns integration. A fresh checkout works without the Git-ignored
`AGENTS.local.md`; keep its contents out of public documentation and commits.

Open the Calendar directory itself as the Codex project for native discovery of
these files; starting from its parent does not imply discovery, and existing
sessions may need a reload. Respect the project trust prompt instead of changing
global trust or permissions. From a parent-directory task, explicitly read
`Calendar/AGENTS.md` and the relevant skill, or pass the role's instructions in a
subagent task; do not describe this fallback as proof of native discovery.

With Python 3.11+, `python -m pip install --target .local/workflow-python -r
tools/requirements.txt` then `python tools/validate_workflow.py` checks skill/agent
metadata, references and source records. It proves neither agent loading nor app
behavior; the helper dependency stays in ignored `.local/`.

Configuration fields were checked against the official
[subagent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents).
TOML/link checks validate files, not native role discovery or measured session
spending. Do not claim a token reduction percentage from word counts alone.
Workflow adaptations retain their [source attribution](WORKFLOW_SOURCES.md).

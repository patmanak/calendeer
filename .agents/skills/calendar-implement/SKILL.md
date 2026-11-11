---
name: calendar-implement
description: Implement or debug an authorized Calendar slice with behavior tests and isolated Proton adapters. Use after requirements are clear; not for initial product specification or workflow-only setup.
---

# Implement Calendar

Read AGENTS.md, the assigned slice and relevant contracts. Inspect existing files
and build descriptors first. Respect the batch evidence level and size budget.

## Deliver one behavior

Confirm the requested acceptance case and exclusive write scope. For meaningful
behavior changes, establish a failing test or reproduction, implement the smallest
complete change, then verify it. Expected results come from the requirement or
independent fixture, not a reimplementation of the code under test.

Exercise project-owned boundaries. Keep Proton DTOs, network libraries, crypto
objects and provider rows out of the domain. Translate errors and preserve data
at adapters. Use maintained cryptography. Follow the agreed persistence and sync
contracts; do not decide conflict/deletion policy inside a mapper.

For sync changes, cover the retry, cancellation and lost-acknowledgement cases
the changed code can actually reach, at the batch evidence level. Process-death
and device journeys belong to milestone qualification (E4). Extend the existing
pipeline rather than adding a parallel family. Avoid implicit destructive full replacement and
duplicate creation after an uncertain upload result. Preserve unsupported fields
or report a limitation instead of silently removing them.

For a defect: trace the failing data/control path, state a falsifiable cause,
change it, and repeat the original reproduction. If the cause remains unclear,
gather discriminating evidence rather than stacking speculative patches.
Refactor only when it supports the slice and the relevant checks remain green.

## Finish with evidence

Run checks appropriate to the actual changed scope and available build. Do not
run all live instrumented tests indiscriminately. Observe the project account,
device and fixture restrictions. Never claim device/OEM/live success from fakes.

Inspect the diff or changed files for unrelated changes, private data and
generated files. Return changes, exact checks and outcomes, remaining risks and
any interface changes needed by dependent work. Do not edit the shared status
record as a child agent or rewrite other agents' files.

Method provenance: [workflow sources](../../../docs/WORKFLOW_SOURCES.md).

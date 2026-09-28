---
name: calendar-plan
description: Plan accepted Calendar requirements into verifiable implementation slices and replaceable integration contracts. Use for architecture, task sequencing, or a Proton adapter migration.
---

# Plan Calendar

Read AGENTS.md, docs/STATUS.md and the accepted requirement or explicit scoped
implementation request. Use [the slice template](../../../docs/templates/implementation-slice.md).
If no product specification exists, work on the requested research or proposal;
do not invent acceptance or treat setup as product implementation authority.

## Design from behavior

Start with what must be observable for each requirement to pass. Identify the
minimum artifacts, runtime connections and checks that make that outcome real.
Compare meaningful alternatives for consequential decisions and record the
selected rationale. Small interfaces should hide substantial integration detail;
do not introduce wrappers that merely rename each Proton endpoint.

For the Proton boundary, define applicable:
- operations and supported capabilities in project-owned types;
- opaque identifiers, revisions and cursor ownership;
- pagination, cancellation, authentication failure, throttling and retry behavior;
- unknown-field preservation and unsupported-operation outcomes;
- durable mutation and lost-acknowledgement reconciliation responsibilities;
- adapter version/state migration and compatibility evidence.

Do not invent server guarantees. Network, session, crypto and serialization
implementation stay in adapters; Android provider rows stay in the Android
boundary. Domain/sync behavior must remain testable without Proton or Android.
The exact module topology remains an engineering decision grounded in the spec.

## Slice and verify

Create dependency-ordered slices that each demonstrate an accepted behavior.
Assign requirement IDs, bounded write scopes, existing/proposed paths, acceptance
checks and recovery steps. Include adapter contract tests and a concrete check
that replacing the adapter does not force domain or Android rewrites.

Expose high-risk uncertainties as bounded source research or experiments with a
question and stopping condition. Do not write a whole future SDK spec from names
in a manifest. Review dependencies for cycles, overlapping file ownership and
unwired interfaces before assigning parallel implementation.

Return the plan, coverage of requirements, unresolved dependencies and review
findings. Routine choices inside authorized scope do not require repeated owner
approval. Do not start implementation when the request is only to plan.

Method provenance: [workflow sources](../../../docs/WORKFLOW_SOURCES.md).

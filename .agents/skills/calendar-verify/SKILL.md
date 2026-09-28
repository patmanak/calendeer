---
name: calendar-verify
description: Test or independently review Calendar requirements, plans, implementation and adapter replacement evidence. Use for acceptance, regression checks, interoperability verification or review findings.
---

# Verify Calendar

Read AGENTS.md, the actual requirement/plan and affected files. Use
[the verification template](../../../docs/templates/verification.md) when a
durable report is useful. State whether this is independent review or self-review.
Do not trust a developer's completion summary as evidence.

## Choose the mode

- Specification/plan review: trace each requirement to observable acceptance,
  dependency order, owned scope and an actual integration path. Identify decisions
  incorrectly presented as accepted and missing recovery semantics.
- Test engineering: implement tests only in the assigned scope. Derive expected
  outcomes from requirements/fixtures; prove a regression test detects the defect.
- Implementation review: inspect behavior and real wiring through entry points.
  A file, interface or stub existing is not a completed feature.
- Workflow setup review: validate configuration, skill routing, links, provenance
  and phase boundaries. Do not require Android builds for an app not yet created.

## Verify the relevant boundaries

Use pure-policy tests, reusable adapter contracts, real persistence/provider
integration and device/service journeys for different claims. Confirm production
composition uses the adapter under test; do not stop at a passing fake.

Select cases from accepted scope: timezones/DST, all-day events, recurrence
exceptions, deletion, unsupported fields, retries, uncertain acknowledgements,
offline changes, revocation, restart, account isolation and no-change behavior.
Test that adapter replacement preserves domain behavior and handles versioned
adapter state rather than reinterpreting old opaque cursors blindly.

Check permissions, sanitized diagnostics and fixture cleanup where applicable.
Principal-account mutations and unrequested session resets are not test tools.
A physical Samsung/Proton journey remains NOT RUN if only local tests ran.

## Report accurately

For each claim use PASS, FAIL, BLOCKED or NOT RUN, with exact commands or steps,
source/build/environment and sanitized results. Do not rerun passed checks without
a relevant change. Distinguish static/source review, build, device and server
evidence. Never mark an unavailable case passed.

Findings need severity, location, concrete trigger, impact and evidence. Flag a
missing required behavior as a blocker; keep optional style advice optional.
A review-only agent reports fixes without making them. Return the outstanding
gaps and let the primary agent integrate changes and update status.

Method provenance: [workflow sources](../../../docs/WORKFLOW_SOURCES.md).

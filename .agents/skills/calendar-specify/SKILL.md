---
name: calendar-specify
description: Specify Calendar product behavior and acceptance cases from the owner's requirements. Use when starting or revising a Calendar specification, before implementation.
---

# Specify Calendar

Read the project AGENTS.md and docs/STATUS.md. Use
[the specification template](../../../docs/templates/specification.md) when a
durable draft helps. Do not create a spec during workflow setup: the owner has
reserved that for the next step.

## Produce a reviewable specification

1. Recover the existing intent and decisions. Do not ask the owner to repeat them.
2. Separate confirmed requirements, assumptions, options and open questions.
   Ask the most consequential unanswered question first. Research technical
   questions from source rather than assigning them to the owner.
3. Describe the user's observable result, supported scope and non-goals.
   Use stable requirement IDs and Given/When/Then acceptance cases with
   independently determined expected outcomes.
4. For synchronization, clarify relevant semantics: direction, supported data,
   recurrence, deletions, offline edits, permissions, conflicts and recovery.
   Do not choose these policies merely because another project chose them.
5. Record the accepted replaceable-Proton constraint. Keep endpoint names and
   SDK objects out of product requirements; put evidenced implementation
   constraints in a separate section.
6. Present a concrete draft and its remaining decisions. Mark it accepted only
   when an actual owner instruction supports that status. Do not start product
   implementation from an unresolved draft.

A small unambiguous change may need only an acceptance note; do not force a large
PRD. Reuse the current conversation before conducting another interview.
Do not publish to an issue tracker or assume slash commands from upstream exist.

Return the draft path (or draft text if read-only), remaining decisions and
which evidence is still needed. The primary agent updates the status record.

Method provenance: [workflow sources](../../../docs/WORKFLOW_SOURCES.md).

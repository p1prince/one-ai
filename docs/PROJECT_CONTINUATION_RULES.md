# ONE AI — Mandatory Project Continuation Rules

**Authority:** This document records the project owner's explicit scope clarification and approval on 2026-11-07. It is mandatory for every AI assistant, developer, contributor, and future conversation continuing this repository.

## Product being built

Build ONE AI as a complete, independent Android AI application. The foundation release includes the working application, its AI engine, five-section interface, generic command framework, permanent Floating Bubble, generic plugin management centre, Developer Mode/SDK, security, settings, and activity management.

The plugin ecosystem is a first-class part of ONE AI. The purpose of the current project is to build the host platform and generic extension infrastructure—not to build example capability plugins.

## Non-negotiable scope boundary

1. **Do not implement individual capability plugins as current roadmap deliverables.** Browser/Web Research, Voice, Screen Intelligence, App Control, Device Control, AutoDroid, automation examples, and any other named capability are examples of possible future plugins only. They are not current priorities, acceptance requirements, milestones, or reasons to delay the foundation release.
2. **Do not extract, migrate, package, build, integrate, or prioritise reference APKs for those example capabilities.** Do not create a sequence for doing so. Only the owner can add a specific plugin project later.
3. **The Plugins section is required Core functionality.** Complete the generic Kodi-inspired plugin management experience: discovery/catalogue, import or installation, installed-plugin details, trust and compatibility, dependencies, enable/disable, configuration, updates, removal, permissions, lifecycle, status, and generic integration with ONE AI and the Floating Bubble. Implement the generic host capabilities without supplying example capability plugins.
4. **The Developer section and SDK are generic platform infrastructure.** They must let independent developers create compatible extensions without requiring Core source edits for every new plugin.
5. **The Floating Bubble is permanent Core infrastructure.** It starts empty; plugin enablement and Bubble access remain separate user-controlled decisions.
6. Existing capability-specific source may be inspected only where necessary to identify a dependency or contamination of the standalone host. Preserve existing history and working code unless a focused removal is justified by source/dependency evidence and the owner-approved stage. Do not turn cleanup into a plugin extraction project.
7. Do not reinterpret examples in the original plan as required deliverables. The original plan labels these examples illustrative and identifies individual capabilities as future plugins.

## Roadmap authority and stage order

The owner's *ONE AI Official Open-Shell Development Plan* (revised 3 November 2026) remains the source for the 12-stage structure and core product requirements. The owner's approved corrected roadmap and this document supersede conflicting implementation sequences, tracker wording, audit recommendations, and prior AI-generated interpretations.

Keep the 12 stages. Complete and reconcile Stages 1–10 in order before formal Stage 11 full acceptance. Stage 11 then verifies the integrated standalone application and generic plugin platform. Stage 12 prepares the foundation release. Do not send the owner backwards to repeat completed work; use recorded commits, CI evidence, and device evidence to establish status.

## Required work protocol

For every continuation:
1. Read this document first, then the official plan, current tracker, decision record, Stage 2 audit, and relevant stage specification.
2. Inspect the current branch HEAD and working implementation before proposing changes. Do not rely on stale summaries or assume tracker claims are accurate.
3. Reconcile existing commits/tests/evidence. Never repeat completed work without identifying a concrete defect or missing acceptance criterion.
4. Work on the approved validation branch unless the owner explicitly directs otherwise. Keep `foundation/batch-03-ai-engine` untouched.
5. Follow: inspect → verify → recommend → obtain approval when required → implement → test → record exact commit and evidence.
6. Make focused changes only for current-stage requirements. No speculative redesign, parallel roadmap, scope expansion, or individual capability-plugin work.
7. Keep CI, JVM tests, integration tests, emulator tests, and physical-device tests distinct. Never claim a test that was not performed.
8. Do not merge, publish, build another APK, or change the original foundation branch without explicit authorization.
9. At each handoff, update the tracker with exact branch, commit SHA, completed work, tests, outstanding work, and the single next task. A future AI must continue from that state, not restart the audit or ask the owner to restate settled requirements.

## If instructions or documents conflict

Resolve conflicts in this order:
1. Latest explicit owner instruction.
2. This mandatory continuation/scope document and the owner's approved corrected roadmap.
3. Original Official Open-Shell Development Plan for product requirements and the fixed 12-stage structure.
4. Updated decision record and current tracker.
5. Older audit text, archived trackers, comments, prior AI summaries, and examples.

When an older document describes capability extraction, reference-plugin implementation, or a named optional capability as current work, treat that passage as superseded historical context—not an active task.

## Handoff requirement

Before ending a work session, record what changed, exact commit SHA, validation evidence, original-branch protection status, and the next concrete task. The next AI should be able to continue without making the owner repeat this scope clarification.

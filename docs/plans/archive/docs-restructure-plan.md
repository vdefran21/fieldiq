You are working inside the FieldIQ repository.

Task: restructure the docs tree into a cleaner long-term layout for human review, Codex implementation, and ChatGPT architectural analysis.

Important constraints:
- Preserve all content. Do not delete information.
- Prefer move/rename/relink over rewriting.
- Do not change application code or behavior, except for path/reference updates strictly required because docs moved.
- Do not run the application.
- Keep diffs clean and reviewable.
- If something is ambiguous, preserve it and classify it conservatively.

Current doc roles to preserve:
- The numbered Phase 1 docs are the canonical implementation/spec sequence.
- IMPLEMENTATION_TRACKING.md is the live progress ledger and must remain prominent.
- next_steps.md and the implementation review addendum are status/review docs, not canonical specs.
- 10_Threat_Model.md is a security/beta-hardening doc.
- FieldIQ_Business_Plan_2026_v4.docx.md is product/strategy.
- aviability_and_seed_data.md and sprint 4 remediatian plan.md are tactical plans and should be archived.
- Keep docs/drafts/ intact.

Target structure:

docs/
  README.md
  specs/
    phase-1/
      00-overview.md
      01-schema.md
      02-auth-calendar.md
      03-backend.md
      04-negotiation-protocol.md
      05-agent-layer.md
      06-mobile.md
      07-ci-testing.md
      08-architecture-diagrams.md
  security/
    threat-model.md
  status/
    current-state.md
    implementation-tracking.md
    next-steps.md
    reviews/
      implementation-review-addendum.md
  product/
    fieldiq-business-plan-2026.md
  plans/
    archive/
      demo-availability-and-seed-data.md
      sprint-4-remediation-plan.md
  drafts/
    ...existing contents...

Required file moves/renames:

- docs/00_Phase1_Overview.md
  -> docs/specs/phase-1/00-overview.md

- docs/01_Phase1_Schema.md
  -> docs/specs/phase-1/01-schema.md

- docs/02_Phase1_Auth_Calendar.md
  -> docs/specs/phase-1/02-auth-calendar.md

- docs/03_Phase1_Backend.md
  -> docs/specs/phase-1/03-backend.md

- docs/04_Phase1_Negotiation_Protocol.md
  -> docs/specs/phase-1/04-negotiation-protocol.md

- docs/05_Phase1_Agent_Layer.md
  -> docs/specs/phase-1/05-agent-layer.md

- docs/06_Phase1_Mobile.md
  -> docs/specs/phase-1/06-mobile.md

- docs/07_Phase1_CI_Testing.md
  -> docs/specs/phase-1/07-ci-testing.md

- docs/08_Architecture_Diagrams.md
  -> docs/specs/phase-1/08-architecture-diagrams.md

- docs/10_Threat_Model.md
  -> docs/security/threat-model.md

- docs/IMPLEMENTATION_TRACKING.md
  -> docs/status/implementation-tracking.md

- docs/next_steps.md
  -> docs/status/next-steps.md

- docs/09_Next_Steps_Addendum_Implementation_Review.md
  -> docs/status/reviews/implementation-review-addendum.md

- docs/FieldIQ_Business_Plan_2026_v4.docx.md
  -> docs/product/fieldiq-business-plan-2026.md

- docs/aviability_and_seed_data.md
  -> docs/plans/archive/demo-availability-and-seed-data.md

- docs/sprint 4 remediatian plan.md
  -> docs/plans/archive/sprint-4-remediation-plan.md

- docs/drafts/
  -> keep as docs/drafts/ without content changes

Naming rules:
- Use lowercase kebab-case for renamed files.
- Fix spelling mistakes in filenames during the move:
  - aviability -> availability
  - remediatian -> remediation
- Preserve the numbered sequence only for the canonical Phase 1 spec set under docs/specs/phase-1/.

Create these new docs:

1) docs/README.md

This should explain:
- what each top-level docs folder is for
- which docs are authoritative vs status vs archived context
- the recommended reading order for humans and Codex
- that archived plans are historical context, not active source-of-truth specs

Recommended reading order to document:
1. AGENTS.md
2. docs/status/current-state.md
3. docs/status/implementation-tracking.md
4. docs/specs/phase-1/00-overview.md
5. the relevant Phase 1 spec doc(s)
6. docs/security/threat-model.md when touching auth, relay, websocket, OAuth, or notifications
7. docs/product/fieldiq-business-plan-2026.md only for product/market context

2) docs/status/current-state.md

Create a concise, non-speculative synthesis using only existing facts from:
- docs/status/implementation-tracking.md
- docs/status/next-steps.md
- docs/status/reviews/implementation-review-addendum.md

Structure it as:
- What is implemented now
- What is in progress
- Highest-priority remaining work
- Current beta blockers
- Recommended immediate focus

Do not invent roadmap items or status that are not already in the docs.

Reference updates required:
- Update all relative links inside moved docs.
- Update root AGENTS.md to point at the new doc paths.
- Update any repo files that reference old doc paths, including but not limited to:
  - README.md
  - CLAUDE.md
  - scripts
  - comments or docs in backend/, agent/, mobile/, or shared/
- Search the repository for old filenames and stale paths and fix them.

Guardrails:
- Do not extract ADRs in this pass.
- Do not rewrite the implementation content of the spec docs except for path/link cleanup and small wording fixes needed because files moved.
- Do not delete historical implementation plans; archive them.
- Do not touch code unless a string path/reference must be updated because of the docs move.

Validation steps:
1. Print the final docs tree.
2. Run ripgrep searches for old filenames and confirm no stale references remain.
3. Verify all internal relative markdown links were updated.
4. Summarize:
   - old path -> new path mapping
   - files newly created
   - any ambiguous items left unchanged on purpose

Deliverable standard:
- Make the docs tree easier for both humans and AI agents to navigate.
- Keep authoritative specs, live status, security guidance, product strategy, and archived tactical plans clearly separated.
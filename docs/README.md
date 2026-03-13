# FieldIQ Docs

This directory separates authoritative implementation specs from live status, security guidance, product context, archived tactical plans, and draft working notes.

## Top-Level Folders

- `specs/phase-1/` contains the canonical Phase 1 implementation/spec sequence. Treat these files as the primary source of truth for behavior and architecture.
- `status/` contains live project state: the synthesized current snapshot, the implementation tracker, next-step planning, and review addenda.
- `security/` contains security and beta-hardening guidance that should be consulted alongside the specs when touching sensitive flows.
- `product/` contains business and strategy context. It informs priorities, not runtime behavior.
- `plans/archive/` contains historical tactical plans preserved for context. These are not active specs.
- `drafts/` contains existing draft material and scratch analysis. These files are intentionally preserved as non-authoritative working docs.

## Authority Model

- Authoritative specs: `docs/specs/phase-1/*.md`
- Live project ledger: `docs/status/implementation-tracking.md`
- Current status and review context: `docs/status/`
- Historical context only: `docs/plans/archive/` and `docs/drafts/`

Archived plans should not be treated as the current source of truth unless a live status or spec document explicitly points back to them.

## Recommended Reading Order

1. `AGENTS.md`
2. `docs/status/current-state.md`
3. `docs/status/implementation-tracking.md`
4. `docs/specs/phase-1/00-overview.md`
5. The relevant Phase 1 spec document(s) under `docs/specs/phase-1/`
6. `docs/security/threat-model.md` when touching auth, relay, WebSocket, OAuth, or notifications
7. `docs/product/fieldiq-business-plan-2026.md` only when product or market context matters

## Notes For Reviewers And Agents

- Prefer the Phase 1 specs plus the implementation tracker when deciding whether code or tests should change.
- Use `docs/status/current-state.md` for a fast factual snapshot before diving into the tracker.
- Treat archived plans as historical rationale, not as active requirements.

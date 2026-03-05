# FieldIQ — Next Steps to Launch Fast

## This Week (Days 1–7)

- [ ] **Secure the domain.** Check `fieldiq.com`, `fieldiq.app`, `getfieldiq.com`. Buy today — this space is heating up.
- [ ] **Set up the legal entity.** File FieldIQ as a new S-corp or DBA under your existing S-corp (talk to your accountant — keeping it under the existing entity may be cleaner for Year 1). Open a dedicated business checking account.
- [ ] **Recruit 10 beta managers now.** Your wife is #1. Ask her for 4–5 opposing team managers she regularly schedules against. That's your DMV seed network. You need pairs of teams — not just single users — to prove the cross-team protocol.
- [ ] **Do a 30-minute Otto Sport AI deep-dive.** Read `ottosport.ai`, their Jan 2026 press release, and any product demos. Know exactly what Otto Pilot does and does not do. This defines your "what we are that they aren't" talking point.

---

## Month 1 — Foundation

- [ ] **Define the cross-team negotiation data contract.** Before writing a line of app code, spec out the protocol: what data two FieldIQ instances exchange to find a mutual schedule window, what privacy boundaries exist, and how conflicts are resolved. This is your core IP — design it first.
- [ ] **Spin up AWS baseline.** ECS Fargate + RDS PostgreSQL + SQS. You know this stack cold. Get a `dev` environment running. Estimated cost: ~$80–120/mo at this stage.
- [ ] **Data model v1.** Teams, players, events, availability windows, negotiation sessions. Keep it tight — resist scope creep.
- [ ] **Build the auth layer.** Phone-number-based magic link (no passwords) — parents won't remember credentials. Consider Clerk or AWS Cognito for speed.

---

## Months 1–4 — MVP + Cross-Team Protocol (Ship Together)

- [ ] **Calendar sync.** Google Calendar OAuth first (80% of your users). Apple CalDAV second. This is the availability ingestion layer that powers everything else.
- [ ] **Scheduling Agent v1.** Given availability from calendar sync, surface optimal windows. No LLM needed yet — deterministic logic handles this.
- [ ] **Cross-team negotiation protocol v1.** Two FieldIQ instances propose and counter-propose times via your API. This ships WITH the MVP — not after. It's the demo that sells.
- [ ] **Communication Agent.** RSVP reminders, cancellation notices, follow-ups on non-responders. Use Claude Haiku for drafting — cheap, fast, good enough.
- [ ] **React Native (Expo) app — iOS first.** Home screen, schedule view, team roster, notification inbox, approve/reject agent proposals. Keep UI minimal — the AI does the work, the UI just confirms it.
- [ ] **Run your first live cross-team negotiation test** with two real DMV teams. Even manually facilitated — prove the concept before automating it fully.

---

## Month 4–7 — DMV Beta Launch

- [ ] **Onboard 15–25 teams.** Target pairs of teams that play each other — the network effect only activates with bilateral adoption.
- [ ] **Instrument everything.** Time-saved per manager per week is your north star metric. Capture it from day one.
- [ ] **Set up a simple Stripe integration** for future payment collection — don't activate billing yet, just wire it in.
- [ ] **Weekly feedback calls** with 3–4 of your most active beta managers. Your wife's ongoing frustrations are your product roadmap.

---

## Ongoing — Competitive Watch

- [ ] **Set a Google Alert** for: "Otto Sport AI", "TeamSnap ONE scheduling", "LeagueApps AI", "Fastbreak AI", "youth sports AI scheduling"
- [ ] **Quarterly scan** per the protocol in the business plan (30 minutes, start of each quarter)
- [ ] **Watch TeamLinkt's "Emi"** specifically — it's closer to your product than it looks

---

## The One Thing That Matters Most

> Ship a working cross-team scheduling negotiation between two real DMV teams **before Otto expands out of its enterprise rollup phase**. Everything else is secondary to that milestone.
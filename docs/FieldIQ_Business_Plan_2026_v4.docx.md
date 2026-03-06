**FieldIQ**

**Multi-Stakeholder Coordination Framework**

Business Plan  ·  2026

| Implementation 1: FieldIQ for Youth Sports Autonomous AI agents that eliminate the coordination burden on volunteer team managers |
| :---: |

| Prepared by: Vinny DeFrancesco | March 2026  ·  Confidential |
| :---- | ----: |

# **1\. Executive Summary**

FieldIQ is a domain-agnostic multi-stakeholder coordination framework — an AI agent layer that eliminates the manual overhead of scheduling, resource booking, availability negotiation, and confirmation chasing in any volunteer-heavy or resource-constrained organization.

The framework is built around a single foundational capability that no existing platform offers: autonomous cross-party negotiation, where two or more AI agent instances, each representing a distinct stakeholder group, find consensus on a shared resource without any human chasing another human.

FieldIQ's first implementation targets youth sports team management — a $4.5B+ market with over 30 million youth athletes in the US, served today by passive organizational tools (TeamSnap, SportsEngine, PlayMetrics) that record logistics but perform none of the coordination work. Every reschedule, every availability check, every RSVP follow-up is still done manually by unpaid volunteer managers.

| The Framework Thesis The same architecture that negotiates a soccer game reschedule between two FieldIQ instances can negotiate a facility inspection slot between two city departments, a shared equipment reservation between two research labs, or shift coverage between two volunteer fire stations. Youth sports is the wedge. The protocol is the product. |
| :---- |

## **Competitive Position**

As of March 2026, no competitor — incumbent or new entrant — has shipped autonomous cross-team scheduling negotiation. TeamSnap ONE (relaunched late 2025\) adds internal AI automation. Otto Sport AI (launched January 2026, $16.5M seed) deploys an AI chatbot for club administration. Neither involves two AI instances negotiating a shared time slot across organizational boundaries. That gap is FieldIQ's founding moat, and the window to establish it is 12–18 months.

## **Implementation 1 at a Glance**

| Dimension | FieldIQ Youth Sports |
| :---- | :---- |
| **Target User** | Volunteer youth sports team managers (soccer, lacrosse, baseball) |
| **Core Pain** | Scheduling reschedules, chasing RSVPs, coordinating with opposing teams — all manual, all unpaid work |
| **Key Differentiator** | Cross-team AI negotiation: two FieldIQ instances find a mutual game slot autonomously |
| **Go-to-Market** | Free tier → bottom-up adoption in DMV youth soccer → club director upsell |
| **Beta Target** | 15–25 DMV soccer teams by Q4 2026 |
| **Year 3 ARR** | $579,600 (conservative) — see Financial Projections |
| **Framework Expansion** | Municipal parks & rec, HOA management, healthcare scheduling, local government coordination |

# **2\. The FieldIQ Framework**

## **What It Is**

FieldIQ is a coordination layer for organizations where humans currently perform multi-party logistics manually: chasing availability, negotiating shared resources, booking facilities, and confirming participation across multiple independent stakeholders who each have their own schedules, constraints, and communication preferences.

At its core, the framework provides four reusable capabilities:

| Capability | What It Does |
| :---- | :---- |
| **Multi-Party Availability Aggregation** | Ingests calendar data, declared preferences, and historical patterns to build a true availability picture for each stakeholder group — without requiring individual input every time. |
| **Autonomous Cross-Party Negotiation** | AI agent instances representing each stakeholder propose, counter-propose, and converge on a mutually acceptable resource slot (time, space, equipment) without human intermediaries. This is the core IP. |
| **Resource Conflict Resolution** | When multiple parties compete for a shared resource, the framework applies configurable prioritization rules and surfaces ranked alternatives — eliminating the negotiation back-and-forth. |
| **Confirmation & RSVP Automation** | Handles the last-mile problem: personalized reminders, RSVP tracking, escalation for non-responders, and outcome notifications — via SMS, push, or email depending on stakeholder preference. |

## **Why This Architecture Is Defensible**

The cross-party negotiation protocol creates a network effect that compounds over time. Each pair of organizations using FieldIQ generates negotiation data — preferred windows, typical conflict patterns, acceptance rates — that improves the scheduling model for all future sessions. An organization that joins FieldIQ when 10 other organizations are already on it experiences faster, more accurate negotiations than one joining when the network is empty. This is the same moat dynamic that made OpenTable hard to displace: the value is in the network, not just the software.

A second defensibility layer: the negotiation protocol itself is a proprietary API standard. Once two organizations adopt it for coordination, switching one party off FieldIQ breaks the automation for both — creating bilateral lock-in that unilateral churn cannot solve.

## **Framework Architecture**

| FieldIQ Protocol Stack ┌─────────────────────────────────────────────────────┐ │  Mobile / Web UI  (React Native · Expo · iOS-first) │ ├─────────────────────────────────────────────────────┤ │  Agent Layer  (Node.js / TypeScript · SQS workers)  │ │  Scheduling · Negotiation · Communication · Booking  │ ├─────────────────────────────────────────────────────┤ │  API Layer  (Kotlin Spring Boot · REST \+ WebSocket)  │ ├─────────────────────────────────────────────────────┤ │  Data Layer  (PostgreSQL 16 · Redis · Flyway)        │ ├─────────────────────────────────────────────────────┤ │  Infrastructure  (Docker local → Railway → AWS)      │ └─────────────────────────────────────────────────────┘ |
| :---: |

# **3\. Implementation 1: Youth Sports**

**Why Youth Sports First**

Youth sports is the optimal first market for four compounding reasons: the pain is acute and personally validated, the target user (volunteer team manager) has no existing AI solution, the sales cycle is short (word-of-mouth between managers at the same field), and the cross-team scheduling problem is visually demonstrable — making it easy to show rather than explain.

## **The Problem**

Over 30 million youth athletes participate in organized sports in the US. Each team has a volunteer manager — a parent spending 5–10 unpaid hours per week on coordination tasks that generate zero athletic value. The most painful task is rescheduling: when a game must move, the manager must manually check team availability, contact the opposing team manager via text or email, negotiate a new time, confirm the field, and notify two full rosters. This process takes 2–4 days and 20+ messages — every single time.

Existing platforms (TeamSnap ONE, SportsEngine, PlayMetrics, LeagueApps) are passive organizational tools. They provide a place to record logistics, but they perform none of the coordination work. The manager must still initiate every action involving a party outside their own team. No platform has shipped autonomous cross-team negotiation as of March 2026\.

## **The Solution**

| Without FieldIQ | With FieldIQ |
| :---- | :---- |
| Manager texts opposing manager to find a new time | FieldIQ agent initiates negotiation automatically |
| 2–4 days of back-and-forth messages | Match found in minutes, sent for human approval |
| Manager manually checks who's available | Calendar sync surfaces real availability instantly |
| RSVP follow-up is a second round of manual chasing | Communication agent drafts and sends follow-ups |
| Field must be rebooked manually | Field Agent handles rebooking via facility integrations |

## **Target Customer Segments**

Revenue flows from organizations, not individual managers. The free tier hooks volunteer managers bottom-up; the paying customers are the entities with budgets.

| Segment | Budget Reality | Priority | FieldIQ Value Prop |
| :---- | :---- | :---- | :---- |
| **Competitive / Travel Clubs** | $2K–$8K/player/season. Paid staff. | **★★★★★ Primary** | Reduces manager churn, automates scheduling across 20+ teams |
| **Municipal Parks & Rec** | Paid staff, real budget. Grant-eligible. | **★★★★☆ High** | Replaces manual coordination across dozens of leagues |
| **Tournament Operators** | Scheduling IS the product. Strong ROI. | **★★★★☆ High** | AI bracket \+ cross-team logistics for multi-day events |
| **High School Athletic Depts** | Paid admin, compliance requirements. | **★★★☆☆ Medium** | Multi-sport scheduling with compliance audit trail |
| **Rec League Volunteer Managers** | No budget. Acquisition channel only. | **★★☆☆☆ Funnel** | Free tier. Creates network density that sells clubs. |

# **4\. Framework Expansion Markets**

The FieldIQ coordination framework is intentionally domain-agnostic. Youth sports is Implementation 1\. The same protocol stack — availability aggregation, cross-party negotiation, resource booking, RSVP automation — maps directly to multiple high-value markets where volunteer-heavy or resource-constrained organizations are drowning in manual coordination overhead.

| Strategic Logic Youth sports establishes proof-of-concept, generates reference customers, and validates the network effect moat. Each subsequent market is entered with a working protocol, an established technology stack, and a demonstrated business model — not a greenfield bet. |
| :---- |

## **Tier 1 Expansion — Direct Architecture Reuse**

| Market | Coordination Problem | Buyer / Budget | Framework Mapping |
| :---- | :---- | :---- | :---- |
| **Municipal Parks & Recreation** | Leagues, field reservations, facility rentals, program registrations — managed by skeleton staff with heavy volunteer dependency | Paid government employees. Real budget. DMV alone \= DC, Montgomery, Fairfax, Arlington, Prince George's | Swap 'team' → 'program', 'opposing manager' → 'facility coordinator'. Protocol maps 1:1. Natural upsell from youth sports beta users. |
| **HOA & Community Association Management** | Amenity reservations, vendor scheduling, maintenance windows, community event coordination — all via volunteer boards drowning in email chains | Property management companies, HOA boards. \~350,000 HOAs in the US. AppFolio/Buildium serve this space passively. | Amenity \= field. Vendor \= opposing team. Board approval \= manager confirmation. Same negotiation loop. |
| **Volunteer Fire & EMS Departments** | Shift coverage when someone calls out — a crisis-level coordination problem solved today by phone trees and group texts | Fire chief, department administrator. Modest direct budget but strong grant eligibility (FEMA SAFER, state EMS grants) | Availability aggregation → coverage proposal → confirmation. Urgency escalation built into the communication agent. |

## **Tier 2 Expansion — Larger Market, More Complexity**

| Market | Coordination Problem | Buyer / Budget | Key Consideration |
| :---- | :---- | :---- | :---- |
| **Local Government — Permit & Inspection Coordination** | Multi-department sign-off (zoning, fire, structural, utilities). Each department has own availability; applicant chases everyone. | Municipal IT departments, GovTech integrators. Large contracts, sticky. ARPA/SLFRF digital modernization funds available. | Slower procurement. Enter via DMV municipality relationship built during youth sports expansion. Warm intro, not cold RFP. |
| **School District Operations** | Substitute placement, facilities scheduling, special education service coordination, transportation changes. | District operations directors. FERPA compliance required — adds surface area but not prohibitive. | Strong framework fit. Entry via districts that run youth sports leagues — natural adjacency to Implementation 1 user base. |
| **Outpatient Healthcare Coordination** | Patient scheduling across multiple providers (PCP, specialist, imaging, labs) — multi-party negotiation with hard constraints. | Healthcare systems, large practice groups. High value per contract. Long sales cycle. | HIPAA compliance required. Highest revenue potential per customer. Phase 3+ expansion after youth sports establishes revenue base. |

## **The Municipal / Government Opportunity — Deep Dive**

Municipal and state/county government markets deserve specific attention because their procurement dynamics differ materially from the consumer youth sports market — but the alignment with the FieldIQ framework is unusually strong.

Government buyers move slowly but pay reliably, churn almost never, and provide reference credibility that accelerates subsequent sales. A contract with Montgomery County Parks is worth more over five years than 500 recreational league teams, and the reference sells the next county without a cold sales motion.

The grant pipeline is an active tailwind. Federal ARPA funds, State and Local Fiscal Recovery Funds (SLFRF), and various digital equity programs have temporarily expanded municipal IT budgets. A product that demonstrably saves staff-hours on coordination tasks is precisely what those grants are designed to fund — and FieldIQ can be positioned as a digital modernization tool, not just a scheduling app.

The entry path is direct: FieldIQ's DMV youth sports beta users already play in leagues managed by Montgomery County, Fairfax County, and DC parks & rec departments. Every team manager who uses FieldIQ and raves about it to the parks & rec coordinator is an organic sales introduction. The transition from 'we coordinate your leagues' teams' to 'we can help your staff too' is a warm conversation, not a cold procurement process.

# **5\. Competitive Landscape**

## **Implementation 1: Youth Sports Competitors**

As of March 2026, no incumbent or new entrant has shipped the cross-team agentic coordination that defines FieldIQ's core capability. The market is reacting to AI pressure — TeamSnap ONE, LeagueApps, and Otto Sport AI have all moved — but all remain on the internal-automation side of the capability gap.

| Platform | Type | AI Status | Cross-Team Negotiation | Key Gap vs. FieldIQ |
| :---- | :---- | :---- | :---- | :---- |
| **TeamSnap ONE (Nov 2025\)** | Consumer \+ AI Rebuild | AI-assisted internal tools; manual for inter-team | None — no cross-team protocol | Still human-driven coordination |
| **Otto Sport AI (Jan 2026, $16.5M)** | AI OS for clubs | Otto Pilot chatbot: comms/admin automation | None — chat-focused, not scheduling negotiation | **No inter-org protocol; PRIMARY new threat** |
| **LeagueApps** | League/Tournament | AI Schedule Importer \+ Whippy AI comms | None — human-led import, not autonomous | Aggressive AI roadmap; has club distribution |
| **Fastbreak AI Compete (Jun 2025\)** | Tournament AI Engine | Full AI for fair matchups \+ venue optimization | None — internal bracket only | Tournament-only; no day-to-day team mgmt |
| **PlayMetrics** | Club-focused | Fully manual as of early 2026 | None | No inter-team autonomy |
| **SportsEngine HQ** | Club/League (NBC Sports) | Minimal beyond basic automation | None | Fragmented; slow to innovate |
| **FieldIQ** | **AI-First Agent** | Full autonomous agents across all coordination tasks | **✓ FULL — proprietary cross-instance negotiation protocol** | N/A — this is the differentiator |

## **The Otto Risk — Named Threat**

| ⚠  Primary Competitive Risk: Otto Sport AI Otto launched January 2026 with $16.5M seed funding, three strategic acquisitions (Demosphere, SportWrench, University Athlete), and a founding team from SportsEngine and TeamSnap. They are building an 'intelligent operating system' for clubs targeting soccer, volleyball, and lacrosse — FieldIQ's exact launch vertical. Otto Pilot today handles inbound parent questions and admin tasks via chatbot. It does not yet do cross-team scheduling negotiation. The window before they build that capability is 12–18 months. Shipping the cross-team protocol before Otto expands out of its enterprise rollup phase is the single most important milestone in Phase 1\. |
| :---- |

## **Framework Competitors — Expansion Markets**

In the expansion markets, FieldIQ would compete with different incumbents — but from a structurally advantaged position, entering with a proven protocol and reference customers rather than a concept.

* Municipal Parks & Rec: ActiveNet, RecDesk, CivicRec — all passive record-keeping tools with no agentic coordination layer

* HOA Management: AppFolio, Buildium, TOPS — same passive tool dynamic as sports incumbents

* Government Permitting: Tyler Technologies, Accela — enterprise vendors with long replacement cycles; no AI negotiation capability

* Healthcare Scheduling: Kyruus, NexHealth — exist but focus on patient-facing booking, not cross-provider coordination

# **6\. Business Model & Monetization**

## **Revenue Streams**

| Stream | Pricing | Target Customer | Phase |
| :---- | :---- | :---- | :---- |
| **Team Subscriptions** | Free / $14/mo / $32/mo (AI Concierge) | Individual managers | Phase 1 (Free), Phase 2 (Paid) |
| **Club / Org Licensing** | $150–$600/mo by team count | Travel clubs, rec orgs | Phase 2 |
| **Payment Processing** | 2.5% of transactions | All paying tiers | Phase 2 |
| **Tournament / Event SKU** | $200–$800 per event | Tournament operators | Phase 2 |
| **Framework Licensing (future)** | Custom — municipal/enterprise | Government, healthcare, HOA | Phase 3+ |

## **Unit Economics**

| Metric | Value | Basis |
| :---- | :---- | :---- |
| **CAC (organic / referral)** | \< $15 | Word-of-mouth at fields; no paid acquisition |
| **LTV (18-month retention @ $14/mo)** | $252 | Conservative 18-month seasonal retention |
| **LTV:CAC Ratio** | 16:1 | Strong unit economics from low-cost acquisition |
| **Infrastructure cost per team/month** | \~$0.25 (Phase 1 scale) | Docker local → Railway $80/mo ÷ 320 teams |

# **7\. Financial Projections**

## **3-Year Conservative Model — Implementation 1**

| Metric | Year 1 | Year 2 | Year 3 |
| :---- | :---- | :---- | :---- |
| **Paying Teams** | 25 | 150 | 600 |
| **Club / Org Licenses** | 2 | 12 | 40 |
| **MRR (end of year)** | $1,085 | $10,500 | $48,300 |
| **ARR** | $13,020 | $126,000 | $579,600 |
| **Infrastructure Costs** | $3,600 | $12,000 | $48,000 |
| **AI API Costs** | $2,400 | $8,400 | $36,000 |
| **Total Operating Costs** | $11,300 | $31,200 | $96,000 |
| **Break-even** | Month 14–18 (end of Year 1 / start of Year 2\) |  |  |

Framework expansion markets (municipal, HOA, government) are not modeled in the 3-year projection above. They represent upside scenarios contingent on Implementation 1 reaching product-market fit. A single municipal contract at $1,500–$3,000/month would materially change the Year 2–3 trajectory.

# **8\. Go-to-Market Strategy**

## **Phase 1: DMV Youth Soccer Beta (Months 1–7)**

The go-to-market exploits a structural advantage: the founder's wife is an active youth soccer team manager in the DMV area, providing direct access to 10–15 beta managers and their opposing team counterparts. This is not a cold sales motion — it's a warm network with a validated pain point.

* Target pairs of teams, not individual managers — the negotiation protocol requires bilateral adoption to demonstrate value

* Ship the cross-team negotiation demo before any paid features — the demo sells itself when a manager watches a reschedule happen automatically

* North star metric: minutes saved per manager per week — instrument from day one

* Do not charge money in Phase 1 — network density matters more than revenue at this stage

## **Phase 2: Club Director Conversion (Months 7–14)**

Bottom-up adoption creates top-down sales pressure. When 15+ teams in a club are on FieldIQ, the club director notices — both because managers are happier and because they can see scheduling efficiency improve. That's the moment for a club licensing conversation.

## **Phase 3: Municipal & Framework Expansion (Month 14+)**

DMV parks & rec departments manage the leagues that FieldIQ's beta teams play in. The warm introduction path: a beta user (team manager) mentions FieldIQ to their parks & rec coordinator → coordinator sees a demo → pilot conversation begins. No cold outreach required in the initial expansion market.

## **Network Effect Moat**

| Every game is a conversion touchpoint Each game FieldIQ negotiates against a non-FieldIQ team demonstrates the friction differential to the opposing manager. They see their counterpart approve a proposed game time in 30 seconds while they're still waiting for a text reply. That contrast is the most effective sales pitch possible — and it happens automatically at every game. |
| :---- |

# **9\. Risks & Mitigation**

| Risk | Likelihood | Impact | Mitigation |
| :---- | :---- | :---- | :---- |
| **Otto Sport AI announces inter-team scheduling before FieldIQ ships** | **High** | **High** | Cross-team protocol ships in Phase 1 MVP — not Phase 2\. Otto is a rollup, not a ground-up agentic system. Ship before Q4 2026 and establish network nodes. Quarterly competitor scan mandatory. |
| **LLM cost spiral at scale** | **Medium** | **High** | Deterministic logic for all routine scheduling. Claude Haiku for communications. Sonnet/Opus only for ambiguous negotiation conflicts. Per-team token budgets enforced in agent layer. |
| **Network effect timing — critical mass before traction** | **Medium** | **High** | Fallback mode: Team B not on FieldIQ gets a simple scheduling link showing Team A's available windows. Still better than texting. Every interaction is a conversion touchpoint. |
| **Low rec manager willingness to pay** | **High** | **Low** | Rec managers are acquisition channel, not revenue target. Revenue comes from clubs, municipalities, and tournament operators. Free tier is intentional. |
| **COPPA compliance (minors' data)** | **Low** | **High** | Store parent/manager data only — never player PII. Legal review before beta launch. No player name, age, or photo in any agent communication. |
| **Competitive blind spots — stealth AI startups** | **Ongoing** | **High** | Mandatory quarterly competitor scan: TeamSnap ONE release notes, App Store search for 'youth sports scheduling AI', YC batch announcements, Crunchbase funding alerts, LeagueApps product page. |
| **Framework expansion fails to gain traction** | **Medium** | **Medium** | Youth sports standalone generates $579K ARR by Year 3 at conservative assumptions — a viable independent business. Expansion is upside, not survival. |

# **10\. Infrastructure Strategy**

## **Local-First Development Approach**

FieldIQ's infrastructure strategy is intentionally staged to minimize cost and DevOps overhead during the validation phase, then migrate to managed cloud services as real users arrive. A sole developer building against a 12-month competitive window should spend zero time on infrastructure management during Phase 1\.

| Stage | Infrastructure | Monthly Cost | Trigger to Move Up |
| :---- | :---- | :---- | :---- |
| **Phase 1 — Local Dev** | Docker Compose: Postgres, Redis, LocalStack (SQS emulation), second Postgres for Team B simulation | **$0** | Cross-team protocol works end-to-end with two real devices |
| **Phase 2 — Beta** | Railway or Render: managed Postgres \+ app hosting. Zero DevOps. | $50–100/mo | First 5–10 real teams onboarded and using the app daily |
| **Phase 3 — Growth** | AWS ECS Fargate \+ RDS Multi-AZ \+ SQS \+ ElastiCache. Matches existing expertise. | $300–600/mo | Paying customers, SLA requirements, or Railway cost exceeds AWS |
| **Phase 4 — Scale** | AWS full stack: ECS multi-service, RDS Multi-AZ, CDN, WAF, monitoring | $2,800–6,800/mo | 5,000+ active teams, enterprise contracts, or framework expansion deployment |

## **Technology Stack**

| Layer | Technology |
| :---- | :---- |
| **Backend API** | Kotlin Spring Boot · REST \+ WebSocket · Flyway migrations |
| **Agent Layer** | Node.js / TypeScript · SQS workers · Vercel AI SDK or LangGraph |
| **Mobile** | React Native (Expo) · iOS first · Expo Router |
| **Database** | PostgreSQL 16 · Redis (caching \+ session) · Flyway schema management |
| **LLM** | Claude Haiku for communication drafting (cheap, fast) · Claude Sonnet for negotiation conflict resolution (reserved) |
| **Auth** | Phone OTP (Twilio) \+ email magic link (dev) · JWT \+ refresh tokens · SecureStore on device |
| **Integrations** | Google Calendar OAuth (read-only busy/free) · Apple CalDAV (Phase 2\) · Expo Push Notifications (FCM) |

# **11\. Development Roadmap**

## **Phase 1 — MVP \+ Cross-Team Protocol (Months 1–4)  ← PRIORITY**

| Critical Sequencing Note The cross-team negotiation protocol ships WITH the MVP — not after. This is a deliberate departure from typical 'shipping the simplest thing first' logic. The protocol is the differentiator, and the 12–18 month competitive window means it must be live and network-building before Otto Sport AI or TeamSnap ONE can replicate it. |
| :---- |

| Phase | Timeline | Deliverables |
| :---- | :---- | :---- |
| **Phase 1** | Months 1–4 | Docker Compose dev environment · Postgres schema (teams, users, events, availability, negotiation sessions) · Kotlin Spring Boot API skeleton · Auth (phone OTP \+ JWT) · Availability window CRUD · Google Calendar OAuth \+ sync agent · SchedulingAgent v1 (deterministic — no LLM) · Cross-team negotiation protocol v1 · React Native iOS app (schedule feed, team view, negotiation approval screen) · First live cross-team negotiation test with two real DMV teams |
| **Phase 2** | Months 4–7 | DMV Beta: 15–25 teams onboarded (target pairs, not individuals) · CommunicationAgent (Claude Haiku for RSVP drafting) · Push notifications (Expo \+ FCM) · Time-saved metric instrumentation · Stripe integration (wired in, billing not activated) · Deploy to Railway/Render · COMPETITIVE TRIGGER: if Otto announces inter-team features, accelerate to paid launch immediately |
| **Phase 3** | Months 7–10 | Club licensing tier activation · Club admin dashboard · Field Agent (manual field entry \+ notification) · Tournament SKU · Apple Calendar support · Payment processing (2.5% transaction fee) · Android app (Expo cross-compile) · Municipal parks & rec pilot outreach |
| **Phase 4** | Months 10–14 | Multi-sport expansion (lacrosse, baseball) · Additional DMV metros · SportsEngine / TeamSnap data migration tools · Framework abstraction layer (domain-agnostic config) · First municipal contract pilot · Partnership program with travel clubs |

# **12\. Competitive Intelligence: Quarterly Scan Protocol**

Given that TeamSnap ONE launched in late 2025 with an explicit AI mandate, Otto Sport AI launched in January 2026 with $16.5M seed funding, and LeagueApps is actively shipping AI features — the window to establish the cross-team negotiation moat is 12–18 months. Competitive monitoring is an operational discipline, not an ad-hoc task. Complete the following scan at the start of each quarter before roadmap planning.

## **Platforms to Monitor**

| Platform | Current Status (Mar 2026\) | Threat Level | Watch Signal |
| :---- | :---- | :---- | :---- |
| **Otto Sport AI** | $16.5M seed, Jan 2026\. Otto Pilot chatbot for club comms/admin. 3 acquisitions. Soccer/volleyball/lacrosse focus. PRIMARY THREAT. | **HIGH** | Any announcement of inter-team scheduling, cross-org negotiation, or field booking agent. Check ottosport.ai monthly. |
| **TeamSnap ONE** | AI platform rebuild Nov 2025\. Internal AI tools. No cross-team protocol yet. | **HIGH** | Any announcement of scheduling negotiation, inter-team automation, or API partnership for coordination. |
| **LeagueApps** | AI Schedule Importer live (2025). Whippy AI comms. Most aggressive AI incumbent. Has club distribution. | **MEDIUM-HIGH** | Any autonomous feature, agentic scheduling announcement, or major soccer club contract wins. |
| **PlayMetrics** | Fully manual as of early 2026\. Deep soccer club relationships. No agentic layer. | **MEDIUM** | Any AI product announcement, funding round, or acquisition activity. |
| **Fastbreak AI Compete** | Full AI tournament scheduling engine (Jun 2025). 40+ orgs committed 2026\. Tournament-only. | **MEDIUM** | Any product launch outside tournament ops (season management, team coordination, rec league support). |
| **TeamLinkt / Emi AI** | Emi scheduling/comms assistant in existing team mgmt app. Bolt-on today but embedded AI with real users. | **MEDIUM** | Emi feature expansion into cross-team coordination, field booking, or autonomous rescheduling. |
| **Stealth / YC-backed** | Unknown. Highest-risk blind spot. Monitor ProductHunt, YC batches, App Store quarterly. | **HIGH** | YC batch announcements; ProductHunt launches; App Store 'youth sports scheduling AI' search. |

## **Quarterly Scan Checklist (30 Minutes)**

1. Review TeamSnap ONE and Otto Sport AI release notes / blog posts for scheduling automation or inter-team features

2. Search App Store: 'youth sports scheduling' and 'team manager AI' — screenshot top results, note new entrants

3. Check YC batch announcements (ycombinator.com/companies) filtered to 'sports' and 'scheduling'

4. Review PlayMetrics, SportsEngine, LeagueApps product pages for AI feature announcements

5. Search Crunchbase / PitchBook for recent funding rounds tagged 'youth sports' or 'sports management'

6. Log findings in a one-page competitive snapshot — update roadmap priority if any watch signal is triggered

# **13\. Immediate Next Steps**

## **This Week**

* Secure domain — fieldiq.app or fieldiq.io preferred over .com alternatives

* Legal entity — DBA or new S-corp under existing entity (consult accountant)

* Recruit beta manager pairs — wife is \#1; get 4–5 opposing managers she regularly coordinates with

* Otto Sport AI deep-dive — 30 minutes on ottosport.ai; understand exactly what Otto Pilot does vs. does not do

## **Month 1**

* Design cross-team negotiation data contract before writing any application code — this is core IP

* Set up Docker Compose dev environment (Postgres x2, Redis, LocalStack)

* Create CLAUDE.md in repo root with full stack context and architecture decisions

* Scaffold Kotlin Spring Boot backend with Flyway migrations V1 (schema) and V2 (negotiation protocol)

## **The One Thing That Matters Most**

| Ship a working cross-team scheduling negotiation between two real DMV teams before Otto Sport AI expands out of its enterprise rollup phase. Everything else is secondary to that milestone. |
| :---: |


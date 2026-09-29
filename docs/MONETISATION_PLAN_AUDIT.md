# Monetisation-First Product & Business Plan — Independent Audit

**Date:** 2026-09-29
**Subject:** "Monetisation-First Product & Business Plan" (Founder draft supplied in session; section numbers below refer to it)
**Repository evidence:** `main` @ `59f1257b2a4353e79eea06c38773f2ddd8dd3580`
**Auditor stance:** adversarial, as the plan's section 28 requires. The audit looks for reasons to kill, cut, merge, delay or replace. It is not an endorsement.

> **Evidence boundary.** Repository claims cite files. Market facts were checked by web search on 2026-09-29 and are listed under Sources. Where a number is an estimate or an assumption, it is labelled. Nothing here is legal advice. The regulatory items need an Australian lawyer's review before launch.

---

## 0. Verdict

**The plan's commercial logic is mostly sound. Its product scope, positioning and platform choice are not.**

The plan correctly identifies retention, not animation, as the principal risk (section 26). It correctly demands cost metering, sunk-cost discipline and a growth gate. Those rules should stay.

Three problems are serious enough to change direction:

1. **The headline differentiator only exists on the smaller platform.** "It lives on your screen" is an Android overlay. iOS does not allow it. Australia is about 65% iOS by mobile page views (StatCounter, August 2026).
2. **"Personal AI Operating System" is the category where the platform owners are strongest.** ChatGPT already sells memory, scheduled tasks, voice and a proactive daily brief (Pulse). Gemini ships as the Android system assistant with a daily brief. Both cost less than, or about the same as, the proposed Plus and Pro tiers.
3. **The repository has built the part the plan says matters least.** Leon today is an animated 2D photo with stateless voice chat. None of the plan's retention systems exist: memory, reminders, goals, proactivity, accounts and billing. Recent commits went almost entirely to mesh fidelity (hips, wrists, elbows, breathing).

**Answer to the auditor's question:** "If we were starting today with no sunk cost, would we build it this way?" **No.** We would not start with a photoreal 2D-mesh overlay on Android and a general personal-AI promise. We would start with **one customer, one recurring job and one measurable outcome**, delivered on the platform where that customer is. The character's job would be to make the accountability loop engaging, not to be the product.

**Recommended direction:** a **character-led accountability coach for a single goal type (training consistency first)**. Distribution goes partly through **personal trainers who pay for their clients' between-session check-ins** (business-to-business-to-consumer). The general "personal AI OS" becomes a later expansion, gated by evidence. It stops being the launch positioning.

---

## 1. What exists today vs. what the plan assumes

| Plan system (section 6 / Phase 1) | Repository state at `59f1257` | Evidence |
|---|---|---|
| Persistent avatar | **Partly built.** One 941x1672 photo deformed by a 2D bone mesh drawn with `Canvas.drawBitmapMesh`. No 3D. Blinks and lip-sync are animated in the rig but never drawn. | `apps/leon-android/README.md`, `docs/LEON_TECHNICAL_TRUTH_AUDIT.md` sections 2 and 8.5 |
| Stable voice | **Built for one user.** Whisper speech-to-text, `gpt-4o-mini` reply, OpenAI `tts-1` speech. The README and truth audit say "an Anthropic model", but the code calls OpenAI. | `apps/api/app/services/leon_voice.py` lines 199–208, `apps/api/app/core/config.py` line 194 |
| AI conversation | **Stateless.** At most 8 turns of history, sent by the phone per request. | `leon_voice.py` `MAX_HISTORY_TURNS = 8` |
| Accounts / multi-user | **None.** One shared bearer token for the whole app. | `config.py` `leon_voice_app_token` comment: "single-user overlay with no account system" |
| Personal memory + "What my AI knows" screen | **Not started.** | No memory, reminder or billing code under `apps/leon-android/app/src/main` |
| Reminders, goals, proactivity, daily brief | **Not started.** | Same |
| Subscription system | **None for Leon.** HumanGate has Stripe code, but Android digital subscriptions sold in-app go through Google Play Billing. | `docs/UNIT_ECONOMICS_MODEL.md` ("Billing default: Disabled") |
| Cost tracking | **Partly reusable.** A fail-closed spend reservation ledger exists, capped at US$2/day and US$20/month for the whole app. It is not per user. | `app/services/leon_voice_spend.py`, `config.py` lines 209–210 |
| Analytics | **None for Leon.** | — |

**Also in the repository, and absent from the plan:** HumanGate, a multi-tenant business-to-business content pipeline with a Human Review Gate. Its status is "private-beta capable, production blocked", with no documented paying customer (`docs/LAUNCH_BLOCKERS.md`, `docs/EXECUTIVE_STATUS_REPORT.md`). Its roadmap lists "Building an agent OS" and "TikTok-led growth" as **explicit non-goals** (`docs/ROADMAP.md`). The new plan makes both its core strategy. The plan never says whether HumanGate continues.

---

## 2. Critical flaws

### C1. The differentiator is Android-only, and Australia is mostly iOS

- The persistent overlay depends on Android's "Display over other apps" permission. iOS has no equivalent. The closest iOS surfaces are widgets, Live Activities, the Dynamic Island and notifications.
- Australian mobile page views are about 65% iOS and 35% Android (StatCounter, August 2026).
- "Test economics on Android first" (section 18) measures a minority segment. The result will not transfer, because the iOS product must work differently.
- **Fix:** make the value platform-neutral: the coach, the memory and the check-in loop. Treat the overlay as an Android bonus, not the promise. An Android-only willingness-to-pay test is acceptable as a *test*. An iOS plan must exist before the growth gate.

### C2. The build order contradicts the plan's own risk analysis

- Section 26 says the risk is "will customers keep paying", not "can we make an avatar move".
- Most Leon commits since 2026-09-22 fix mesh deformation: hips, wrist seams, elbow pivots, breathing width and walking.
- The ten Phase 1 systems are a multi-month build for a solo founder working through agents. No willingness-to-pay evidence exists yet to justify them.
- **Fix:** freeze avatar fidelity now. Build only the loop in section 10 of this audit ("Build now").

### C3. "Personal AI Operating System" is the least winnable category for a startup

- **ChatGPT** has memory, scheduled tasks, advanced voice and Pulse, a proactive daily brief built from memory and a connected calendar. It sells for about A$13 a month (Go) and about A$30–35 a month (Plus).
- **Gemini** is the default assistant on Android and floats over apps natively. Google lists a Daily Brief among paid-plan features. Pricing is A$7.99, A$32.99 and A$149.99 a month.
- Platform owners already hold the calendar, email, contacts and the operating-system permissions that an "action agent" needs.
- **Dot** by New Computer, a memory-first personal AI companion, shut down in October 2025. Humane and Rabbit failed at general delegation hardware.
- **Fix:** own a specific outcome the big assistants will not package: accountability for one goal, with a character, check-ins, streaks and optionally a human professional. Do not sell "memory + reminders + brief" as the product. Those are table stakes.

### C4. No target customer

- The examples span fitness, savings, family, dating, work follow-ups and study.
- Without one customer you cannot write the ad, design onboarding, choose the first integration or price the product.
- **Fix:** pick one. The plan's own examples, specialist roadmap and viral hooks ("my personal trainer follows me around my phone") all point to **training consistency** as the natural wedge.

### C5. The persona and art asset have commercial blockers

- **Trademark.** The only source image shows Adidas marks: the three stripes and the trefoil (`docs/leon-reference/README.md`). They cannot ship in a paid product or in ads.
- **Asset provenance.** The repository does not record who made the character sheet or who holds the rights to it. That must be documented before the image is used commercially.
- **Persona.** The system prompt fixes one character for every user, including a specific ethnic dialect ("contemporary African American vernacular"). Two issues follow:
  - AI personas built on racial identity have drawn public criticism. Meta's AI personas are one example, in January 2025. The risk is highest when the company behind the persona does not share that identity.
  - One fixed voice narrows appeal for a product meant to be used daily by a broad audience.
- **Sycophancy.** The prompt says the persona is "always positive, always encouraging" and should "hype the user up". A coach who never pushes back weakens accountability. It also cuts against the plan's own "no unhealthy dependence" rule.
- **Fix:** remove the trademarks, document the rights, and offer two or three coaching styles rather than one fixed identity. Make the default style honest-supportive rather than always-hype.

### C6. The "action agent" (Pro and Phase 3) runs into Android and Google permission walls

- Google Play restricts SMS and call-log permissions to default handler apps. It prohibits using the Accessibility API to automate other apps unless the app is a genuine accessibility tool.
- Gmail's restricted scopes need an annual third-party security assessment. Google Calendar scopes need OAuth verification.
- A small company cannot make "connected services + action agent" its paid differentiator on a short timeline.
- **Fix:** meet the need with **Android intents**. They open the Maps route, the prefilled message or the calendar-insert screen without holding permissions, and the user confirms by tapping send. Add read-only calendar later. Drop "action agent" as a paid tier.

### C7. The backend is single-user; the plan needs a consumer platform

- A consumer subscription app needs accounts, per-user data, entitlements from Play Billing, per-user cost caps, data export and deletion.
- None of these exist for Leon.
- HumanGate's workspace, RLS, audit and spend-ledger code is reusable. It is business-to-business shaped, so it needs a per-person tenancy model.

### C8. Two companies, one founder, heavy process

- The repository contains a business-to-business content pipeline and a consumer companion app.
- The process is heavy for a pre-revenue product:
  - 72 documents under `docs/`, 18 of them audits.
  - 540 coordination comments on issue #90 in three weeks, many of them 15-minute "Lead-PM check-in" posts.
  - A two-agent audit gate on every commit.
- The safety non-negotiables (Human Review Gate, RLS, spend caps, audit logging) protect customers and must stay. Process ceremony that protects nothing a customer sees consumes the founder's time and money. The plan's rule 8 (no sunk cost) applies to it too.
- **Fix:** the Founder decides HumanGate's fate explicitly (section 13). For the consumer test, keep the safety controls and reduce the ceremony.

### C9. Regulation now reaches AI companions in Australia

- The eSafety Commissioner's **Phase 2 industry codes** came into effect on **9 March 2026**. They add risk-assessment and **age-assurance** obligations for services with AI companion chatbots.
- A product positioned as an AI that "lives with you" falls squarely into that scope. A coach positioned for adults, gated 18+ with age assurance, reduces exposure but does not remove it.
- Fitness, weight, sleep and mood data can be **health information**, which is sensitive information under the Privacy Act and needs consent.
- The statutory tort for serious invasions of privacy is in force. Automated-decision transparency duties are due from December 2026.
- **Fix:** add 18+ gating, consent flows and a privacy review to the Phase 1 scope, not later.

---

## 3. Incorrect or unsupported assumptions

| Plan claim | Finding |
|---|---|
| "The avatar itself is our marketing asset" (s.16) | Character-led apps can sell: Tolan reports more than 100,000 paying users. Tolan's character is stylised and ownable. Leon is a photoreal human bent in 2D, with no blinks or lip-sync. In close-up video this risks the uncanny valley. Test the ad creative before assuming the asset works. |
| "Memory is the moat" (s.22) | Memory is becoming standard in every major assistant. Durable switching cost comes from **outcome history** (months of logged workouts and streaks), **habit** and a **human relationship** (a trainer). Stored facts alone are not enough. |
| "Integrations create switching cost" (s.3) | For a small app, integrations are permission-gated commodities. They are a cost before they are a moat. |
| Free tier includes "limited voice" (s.7) | Voice is the main variable cost (section 5 below). A perpetual free voice tier subsidises tourists. Serve free users on-device text-to-speech, which is already in the app as `LeonSpeechController`. Alternatively, make free a time-limited trial. |
| Plus A$19.99, Pro A$34.99 | Pro costs more than ChatGPT Plus and Google AI Pro, and its features do not exist. Plus sits above ChatGPT Go (A$13) and Tolan (about US$10 a month). See section 7. |
| "AI cost under 20–25% of net revenue" (s.11) | Achievable only with usage caps. At heavy voice use it can exceed 100% of net revenue (section 5). The denominator must be defined as revenue after GST, store fee and refunds. |
| Partner/family network effects (s.15) | Unproven demand. It needs two activated users per account and competes with free family tools from Apple and Google. Defer. |
| Creator marketplace (s.14) | Needs supply and demand at once. It carries voice-cloning, impersonation and moderation risk. Defer indefinitely. |
| Viral loops (s.17) | People rarely share private reminders. "Share my avatar" works only if the avatar is customisable and ownable, and today there is one fixed character. The strongest loop in the plan is **trainer brings clients**, which is really a distribution channel. |
| North Star "Weekly Successfully Assisted Users" (s.20) | The direction is right, but "meaningful assistance event" is undefined and easy to game: the system can create a reminder and count itself. Redefine it as a **user-confirmed outcome** (section 9). |
| "Lock/unlock continuity" as a requirement (s.6A) | Android hides all overlays on the lock screen and other secure surfaces. On Android 15 and later, starting a foreground service from the background also requires a *visible* overlay window. Microphone foreground services have tighter limits again. This area has already produced regressions (issues #206 and #207). It is engineering cost with no revenue case. |

---

## 4. Section 28 challenge list, point by point

| Topic | Finding | Recommendation |
|---|---|---|
| Target customer narrow enough? | No (C4). | One customer: Australian adults 20–45 trying to train 3–5 times a week, plus the personal trainers who coach them. |
| Personal-AI positioning differentiated? | No (C3). | Position as "your training coach who checks in and keeps you honest", with a character. |
| Australian pricing | Plus is plausible but untested. Pro is unjustified. | One paid tier. Test A$14.99 against A$19.99 a month and A$99 against A$129 a year (section 7). |
| Willingness to pay | Evidence exists for character companions (Tolan) and for coaching (people pay trainers). No evidence yet for *this* product. | Pre-sell before building (section 11, stage 0). |
| Free vs paid boundary | Free voice is the costliest thing to give away. | Free: text chat plus on-device voice, one goal and basic check-ins. Paid: neural voice, memory, unlimited goals, weekly review and trainer link. |
| Trial and paywall | "Paywall after aha" is right. The plan omits the other arm to test. | Test a hard paywall with a 7-day trial, shown right after an onboarding that produces the aha, against freemium. Subscription-industry reports have generally found hard paywalls convert downloads to paid better than freemium. Measure retention too. |
| Inference and voice cost | Unmeasured per user. Text-to-speech dominates per-turn cost. | Per-user metering, caps and on-device speech for free users (section 5). |
| Gross-margin target | 20–25% AI cost is reasonable if defined on net revenue at the 90th-percentile user, not the average. | Add a total variable-cost ceiling of 35% of net revenue. |
| Retention strategy | The loop in section 5 of the plan is right but broad. | One loop: goal → scheduled check-in → log → streak → weekly review → adjust. |
| Android persistent avatar | Feasible, fragile and costly (C1, section 3). | Keep what works and stop investing. |
| Privacy and security | Principles are right. Health data, age assurance and deletion are missing. | Build consent, 18+ gating, export and delete into Phase 1. |
| Platform and store rules | Play Billing is mandatory for in-app digital subscriptions. Restricted permissions block the action agent. | Budget for Play Billing. Use intents, not permissions. |
| Action-agent permissions | See C6. | Intents only until paid retention is proven. |
| Competitive threats | Understated (section 6). | Differentiate on outcome and character, not on features. |
| Marketplace timing | Correctly late, but still on the roadmap. | Remove it from the roadmap until 5,000 or more paying users. |
| Customer acquisition | TikTok-first with no numeric gate. | Trainer channel plus small, capped creative tests. |
| Viral loops | Weak (section 3). | Trainer → clients, and a "workout buddy" invite with shared streaks. |
| Partner-avatar demand | Unproven. | Defer. Revisit only if users ask for it unprompted. |
| Creator and trainer monetisation | Trainer revenue share is the best idea in the plan and belongs much earlier. | Move a *trainer* offer into stage 0. Leave the open marketplace out. |
| Regulatory risk | Understated (C9). | Legal review before public launch. |
| Technical feasibility | Leon's renderer works. The retention systems are unbuilt. | Section 10. |
| Abuse cases | Not addressed. | Section 12. |

---

## 5. Unit economics, worked with the current code path

**Revenue per Plus subscriber per month** (assumptions labelled):

| Line | A$ |
|---|---|
| List price (includes 10% GST) | 19.99 |
| Less GST (1/11) | −1.82 |
| Less Google Play subscription fee, modelled at 15%. Google announced 10% for subscriptions in March 2026; the rollout varies by market. | −2.73 |
| Less refunds and chargebacks, assumed 2% | −0.31 |
| **Net revenue** | **≈ 15.13** (about **US$10** at an assumed 0.65 exchange rate) |

**Variable AI cost per voice turn** (US$):

- Speech-to-text and speech rates are the values in `config.py`.
- The `gpt-4o-mini` rates are public list prices at the time of writing and must be re-verified.
- The assumed turn is 8 seconds of speech, about 1,500 input tokens (system prompt plus 8 turns of history) and a 250-character spoken reply.

| Stage | Current stack | With a frontier-class reply model (about $3 and $15 per million tokens) |
|---|---|---|
| Speech-to-text, `whisper-1` ($0.006 per minute) | 0.0008 | 0.0008 |
| Reply (`gpt-4o-mini`) | 0.0003 | 0.0060 |
| Speech, `tts-1` ($15 per million characters) | 0.0038 | 0.0038 |
| **Per turn** | **≈ 0.005** | **≈ 0.011** |

**Per subscriber per month** (voice only; memory extraction and briefs add to this):

| Usage profile | Turns a month | Current stack | Share of US$10 net | Frontier stack | Share of US$10 net |
|---|---|---|---|---|---|
| Light: 5 a day | 150 | $0.73 | 7% | $1.58 | 16% |
| Medium: 20 a day | 600 | $2.90 | 29% | $6.33 | 63% |
| Heavy: 60 a day | 1,800 | $8.70 | 87% | $18.99 | 190% |

**What this means:**

- The 25% target holds only up to about **17 voice turns a day** on the cheap stack, or about 8 a day with a frontier model.
- **Speech output is about three-quarters of per-turn cost** on the current stack. It is the first thing to optimise: shorter replies, cached common phrases, a cheaper neural voice for paid users and on-device voice for free users.
- Hands-free listening sends every voice-activity trigger to paid speech-to-text, including false triggers. Meter it separately or gate it.
- Plus needs a fair-use voice allowance. **Start at about 500 voice turns a month** and tune it from real data. Text chat on a small model is cheap enough to leave effectively uncapped.
- Price comparison: ChatGPT Plus users get near-unlimited voice for about A$30–35. Heavy talkers will compare. The product should sell outcomes, not minutes.

---

## 6. Missing competitors

The plan names none. At minimum:

- **General assistants:**
  - ChatGPT: memory, Tasks, Pulse and voice.
  - Google Gemini: Android system assistant, Daily Brief, scheduled actions and Gemini Live.
  - Apple Intelligence and Siri.
  - Meta AI.
  - Microsoft Copilot, which added an animated voice character in late 2025.
- **Character companions:**
  - Tolan: an animated alien. It reports more than 100,000 paying subscribers and over US$1M a month in revenue. Pricing is US$4.99 a week, US$10 a month or about US$70 a year. It launched on iOS first.
  - Replika, Character.AI, Nomi, Kindroid and Pi.
  - Dot, which shut down in 2025 and serves as a cautionary case.
- **Habit and goal apps with a character:** Finch (a self-care pet with goals and streaks) and Duolingo's character-driven streak model.
- **Planners and reminders:** Todoist, TickTick, Motion, Sunsama, Structured and Tiimo.
- **Fitness coaching:**
  - Future, which pairs clients with a human coach by app.
  - Fitbod, Ladder, Whoop Coach and Strava's AI features.
  - Apple's Workout Buddy on Apple Watch.
  - Trainer software such as Trainerize and TrueCoach, which could add AI check-ins themselves.

Please verify the current status and pricing of each before external use. This market changes monthly.

---

## 7. Pricing experiments

Run these in order. Do not test more than two variables in one cohort.

1. **Price point.** A$14.99 against A$19.99 a month. Show annual first, testing A$99 against A$129 a year. Measure 60-day revenue per install, not conversion alone.
2. **Paywall model.** A 7-day trial with a hard paywall after the onboarding aha, against freemium: free text chat and on-device voice with paid neural voice and memory.
3. **Voice allowance.** 300 against 600 voice turns a month on the paid tier. Measure churn and cost per payer at the 90th percentile.
4. **Trainer channel.** The trainer pays about A$8–12 per client a month, against the client paying the full price with a trainer code that gives the trainer a revenue share.
5. **No Pro tier** until there are at least 200 payers and the top usage decile is known. Build Pro from what heavy users actually do.

Keep the plan's rule against a weekly plan as the default. Tolan offers one, and it may lift short-term revenue. It is not a priority test.

---

## 8. KEEP / CHANGE / KILL / ADD

### KEEP

- The core rule that every feature must name the commercial metric it moves (s.0, s.24.7).
- The sunk-cost rule and the explicit licence to kill (s.24.8–9).
- Monthly plus annual as the core plans, and no weekly default (s.9).
- Paywall after the aha, tested rather than guessed (s.10).
- The per-user profitability dashboard and the explicit cost list (s.11–12).
- The growth gate before paid scale (s.21).
- The trust screen ("What my AI knows about me"), confirmation for consequential actions, and no hidden actions (s.6B, s.23).
- The repository's fail-closed spend reservation design. It is the right base for per-user metering.
- The safety non-negotiables in `AGENTS.md`.

### CHANGE

- **Positioning:** from "Personal AI Operating System" to an accountability coach with a character, for one goal type.
- **Target customer:** from everyone to adults training toward a weekly target, plus the trainers who coach them.
- **Tiers:** from Free, Plus and Pro to Free or trial plus one paid tier.
- **Avatar's role:** from "the body of your AI" to the face of your coach. Keep it. Stop polishing it.
- **Persona:** from one fixed identity and dialect to two or three selectable coaching styles, with an honest-supportive default.
- **North Star:** from a loosely defined assist event to a user-confirmed outcome (section 9).
- **Phase order:** move the trainer relationship from Phase 4 into stage 0 as a distribution channel.
- **Cost target:** define it on net revenue at the 90th-percentile user, and add a 35% total variable-cost ceiling.
- **Platform plan:** Android-only is acceptable for the willingness-to-pay test. An iOS design (notifications, widgets, Live Activities) must exist before the growth gate.

### KILL

- "Personal AI Operating System" as launch positioning.
- The Pro tier at launch, and "action agent" and "connected services" as paid differentiators.
- The creator and avatar marketplace, removed from the roadmap. Revisit only after 5,000 or more paying users.
- "Lock/unlock continuity" and walking as requirements.
- The single fixed dialect persona as the default.
- Any "unlimited" claim.

### ADD

- A named customer, a named job and kill criteria with dates (section 11).
- Numeric growth-gate thresholds (section 9).
- A trainer offer, pre-sold before building.
- Accounts, Play Billing entitlements, per-user cost caps, data export and delete.
- 18+ gating with age assurance, health-data consent, and a privacy and legal review.
- A persona and asset rights register, with trademark removal.
- On-device voice for free users and a cheaper neural voice for paid users.
- A cancellation flow that is as easy as sign-up. The ACCC is scrutinising subscription practices.
- Twenty customer interviews and twenty trainer conversations before any new build.
- An explicit decision on HumanGate (section 13).

---

## 9. Launch metrics and proposed gate thresholds

The thresholds below are **starting hypotheses**, not benchmarks. Revise them once two cohorts have data. They make the gate in section 21 of the plan testable.

| Metric | Definition | Proposed gate |
|---|---|---|
| Activation | New user sets a goal *and* acts on at least one check-in within 72 hours | ≥ 40% of installs |
| Retention | Opened *and* logged an outcome on day 7 and on day 30 | D7 ≥ 25%, D30 ≥ 12% |
| Trial to paid | Opt-out trial conversions | ≥ 25% (or download to paid ≥ 3% for freemium) |
| Paid retention | Payers still paying at month 2 and month 3 | M2 ≥ 70%, M3 ≥ 50% |
| Unit cost | Variable AI cost at the 90th-percentile payer, over net revenue | ≤ 25% (total variable ≤ 35%) |
| Refunds | Refunds over gross subscriptions | < 5% |
| **North Star: Weekly Confirmed Outcomes** | Users who confirmed at least one completed outcome in the week (a workout logged or a commitment marked done) that the coach set or prompted | Grows week on week within paid cohorts |

**Kill criterion:** after two cohorts totalling 300 or more activated users, if trial-to-paid is below 10% *and* M2 paid retention is below 50%, stop the consumer path. Then run the pivot test in section 10.

---

## 10. BUILD NOW / BUILD LATER / STOP BUILDING

### BUILD NOW — the smallest set that tests willingness to pay (about 4–6 weeks)

1. **Accounts and per-user data**, reusing HumanGate's Supabase-shaped auth, RLS and audit patterns.
2. **Google Play Billing**, one subscription with monthly and annual prices. Adding a subscription SDK needs a work package under repository rules.
3. **One goal loop:** set a weekly training target, get scheduled check-ins, log by voice or tap, see a streak, get a weekly review, adjust.
4. **Memory v0:** goals, commitments, people and dates extracted from conversation. Include the "What my coach knows" screen with delete.
5. **Reminders and check-ins** through local notifications and server-scheduled nudges. Tap-to-act uses Android intents.
6. **Per-user metering and caps**, extending the spend ledger from app-wide to per-user.
7. **Analytics events** for every metric in section 9.
8. **Compliance basics:** 18+ gate, health-data consent, export and delete.
9. **Asset fixes:** remove the Adidas marks, document rights and add selectable coaching styles.

### BUILD LATER — valuable, but it must not delay the test

- Daily morning and evening brief, after 4 weeks of retention data.
- Read-only calendar, then intent-based actions.
- A trainer dashboard. Start with a concierge spreadsheet if the trainer channel validates.
- iOS app built around notifications, widgets and Live Activities, before the growth gate.
- Routines, and coach modes beyond training (study, running).
- Lip-sync and blinks, only if ad tests show the avatar drives installs.
- Partner or household features, only on unprompted user demand.
- Specialist or professional avatars, after trainer channel data.

### STOP BUILDING — now

- Avatar mesh fidelity work: hips, elbows, walking and breathing refinements.
- Lock-screen and unlock continuity work.
- Always-on hands-free listening, until its cost and value are measured.
- The marketplace, creator tools and multi-avatar features.
- HumanGate content-pipeline feature work, pending the Founder decision in section 13.
- Coordination ceremony that protects no customer-facing guarantee, such as 15-minute check-in comments.

---

## 11. Revised commercial roadmap (evidence-gated)

Each stage ends at a gate. Missing a gate means changing direction, not extending the stage.

**Stage 0 — Decide and pre-sell (weeks 0–2)**

- Founder decisions from section 13.
- Apply the stop list. Fix the asset and persona.
- 20 interviews with people who have a training goal they keep missing.
- 20 conversations with Australian personal trainers.
- Pre-sell with a landing page at a real A$ price and a paid waitlist, plus trainer letters of intent for a pilot.
- **Gate:** at least 5 trainers commit to a pilot, *or* paid-waitlist conversion is at least 3% from a small, capped ad test.

**Stage 1 — Willingness-to-pay MVP on Android (weeks 2–8)**

- Build the "Build now" list.
- Recruit 50–150 users through trainer pilots, plus a capped ad test of about A$1–2k.
- Run pricing experiments 1 and 2.
- **Gate:** activation, D30 and trial-to-paid thresholds from section 9.

**Stage 2 — Retention engine (weeks 8–16)**

- Weekly review, daily brief, routines and read-only calendar.
- Trainer dashboard if the channel validated.
- iOS design and build.
- Pricing experiments 3 and 4.
- **Gate:** M2 and M3 paid retention, and the unit-cost ceiling at the 90th-percentile payer.

**Stage 3 — Scale (after the stage 2 gate)**

- iOS launch, an annual-plan push and paid acquisition at a known payback period.
- Intent-based actions.
- A Pro tier designed from heavy-user behaviour.

**Stage 4 — Expansion (evidence-gated, optional)**

- More goal types, specialist coaches from trainer supply and household features.
- A marketplace only after 5,000 or more payers and proven trainer supply.

**Pivot options if the stage 1 gate fails:**

1. **Trainer software first.** Sell AI check-ins as a feature to trainers at a monthly price per client, with the trainer approving messages. That is a direct reuse of the Human Review Gate idea.
2. **Executive-function organiser.** A remember-and-nudge tool for people who struggle with follow-through. Pain and willingness to pay are high. Avoid health or treatment claims, which could bring medical-device regulation.
3. **Return to HumanGate business-to-business**, only if its own 2-week sales test (10 agency demos, 3 paid pilots) succeeds.

---

## 12. Abuse cases to design for before launch

- **Minors** using the companion, which the eSafety codes address through age assurance.
- **Self-harm or disordered eating** disclosed in a fitness context. The product needs safe-messaging behaviour and referral to support services, not "hype".
- **Coercive monitoring.** A partner or parent sets goals and check-ins on someone else's phone. Partner features make this worse.
- **Leaked app token.** Today one token unlocks the whole voice backend. The spend cap bounds cost, but per-user auth is required.
- **Voice spoofing or cloning**, if custom voices ever ship.
- **Prompt injection** through calendar or email content once integrations exist.
- **Trainer misuse:** a trainer pushing unsafe advice through the AI under the product's brand. Trainers need terms of service, and their content needs review.
- **Overlay tapjacking concerns.** Keep the overlay small and pass-through, and never draw over other apps' sensitive user interface.

---

## 13. Decisions only the Founder can make

1. **HumanGate:** freeze it, run a 2-week sales test, or continue in parallel. Running both products at full process weight is the costliest option.
2. **Wedge:** consumer training coach, trainer channel, or both. This audit recommends both, with the trainer channel leading.
3. **Persona and asset:** approve removing the trademarks, documenting rights and adding selectable coaching styles.
4. **Process weight:** keep the safety non-negotiables, and decide how much audit ceremony applies to pre-revenue consumer experiments.
5. **iOS timing:** confirm that iOS is designed before the growth gate, not after Android economics are "proven".

---

## Sources (checked 2026-09-29)

- StatCounter, mobile OS share, Australia, Aug 2025 – Aug 2026: https://gs.statcounter.com/os-market-share/mobile/australia
- ChatGPT pricing (Australian prices via third-party trackers): https://chatgpt.com/pricing/ , https://opentherank.com/ai-pricing/chatgpt/
- Google AI plans, Australia: https://gemini.google/au/subscriptions/?hl=en-AU
- OpenAI, "Introducing ChatGPT Pulse": https://openai.com/index/introducing-chatgpt-pulse/
- Tolan funding and traction (GeekWire, 2025): https://www.geekwire.com/2025/ai-companionship-app-tolan-raises-20m-to-help-more-people-grow-with-a-virtual-alien-friend/
- Dot shutdown (TechCrunch, 5 Sep 2025): https://techcrunch.com/2025/09/05/personalized-ai-companion-app-dot-is-shutting-down/
- Google Play service fees: https://support.google.com/googleplay/android-developer/answer/112622 and https://android-developers.googleblog.com/2026/03/a-new-era-for-choice-and-openness.html
- Android 15 overlay and foreground-service restrictions: https://developer.android.com/about/versions/15/behavior-changes-15 and https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- eSafety industry codes (Phase 2 in effect 9 March 2026): https://www.esafety.gov.au/industry/codes and https://www.twobirds.com/en/insights/2025/australia/digital-duty-of-care-what-phase-2-esafety-codes-demand-from-providers
- Repository: `apps/leon-android/README.md`, `docs/LEON_TECHNICAL_TRUTH_AUDIT.md`, `apps/api/app/services/leon_voice.py`, `apps/api/app/core/config.py`, `docs/leon-reference/README.md`, `docs/ROADMAP.md`, `docs/LAUNCH_BLOCKERS.md`, `docs/UNIT_ECONOMICS_MODEL.md`

Claims made from general knowledge rather than a source checked this session should be re-verified before external use. These are the items on Play restricted permissions, Gmail restricted-scope assessments, Meta's persona backlash, Microsoft's Copilot character, Apple's Workout Buddy, the Privacy Act timelines and the ACCC's subscription focus.

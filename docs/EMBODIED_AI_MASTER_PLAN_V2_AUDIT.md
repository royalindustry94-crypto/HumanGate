# Embodied AI Avatar Platform — Master Plan v2.0: Independent Audit

**Date:** 2026-09-30
**Subject:** "Embodied AI Avatar Platform — Monetisation-First Product & Business Master Plan v2.0" (Founder PDF, 24 pages; section numbers below refer to it)
**Previous audit:** `docs/MONETISATION_PLAN_AUDIT.md` (2026-09-29)
**Repository evidence:** `main` @ `f550b52` (PR #207 merged). The Leon renderer and rig are unchanged since `59f1257`.
**Mandate:** the plan's appendix. The auditor is authorised to recommend killing, narrowing, replacing or radically changing any part of the strategy.

> **Evidence boundary.** Repository claims cite files. Market, regulatory and pricing facts were checked by web search on 2026-09-29 and 2026-09-30 and are listed under Sources. Numbers marked *assumption* are modelling inputs, not measurements. Nothing here is legal advice. The companion, wellbeing, likeness and memorial items need an Australian lawyer's review before any real user sees them.

---

## 0. Verdict

**v2 is a much better plan. It now tests the right question. It does not yet say how that question will be answered, and the current technology cannot produce the product it describes.**

v2 fixes most of what the first audit raised:

- one paid tier and no Pro;
- per-user economics;
- an honest iOS presence model;
- a rights register;
- safety in the MVP;
- a four-field feature rule with kill conditions;
- a single, falsifiable central hypothesis (section 45).

That hypothesis is that **embodiment itself** raises willingness to pay, retention or engagement. It is the right thing to prove before anything else.

I accept the Founder's two rejections in section 34 as decisions: the product stays broad, and embodiment is the primary differentiator. This audit tests the plan on its own terms. It does not re-argue the training wedge.

**Five problems remain. Each one could sink the company:**

1. **The kill test cannot fire as designed.** Section 43 says to reconsider the thesis if embodiment does not beat "simpler AI interfaces". No experiment in the plan includes a simpler interface. Every campaign and every beta user sees an avatar, so any retention can be credited to embodiment. **The fix is a randomised control arm: the same app with the avatar off.**
2. **The current renderer cannot deliver the MVP.** Leon is one photograph bent by a 2D mesh. Its skeleton and skin boundaries are hand-measured from that single photo. Blinks and lip-sync are computed but never drawn. "3–5 avatar options", "improved face, lip and eye behaviour" and "custom avatars" all need a different rendering pipeline (section 6).
3. **The arms most likely to win on engagement are the least profitable and the most regulated.** Companion, positive-friend and wellbeing users talk the most. At A$14.99 a month, net revenue is about US$7.40. The 25% AI-cost ceiling covers only about 13 voice turns a day. These same arms sit squarely inside the Australian eSafety codes, the FTC's companion-chatbot inquiry and the wrongful-death litigation that forced Character.AI to ban minors.
4. **Phase 0 can produce a false positive.** Ads that show "realistic representations" of avatars the product cannot yet render measure appetite for a picture, not for the product. Unless they are clearly labelled as concepts, they also create misleading-conduct risk under Australian Consumer Law.
5. **The market signal for embodiment is mixed, not strong.**
   - Tolan, a stylised animated character, has more than 100,000 paying users.
   - xAI announced on 24 July 2026 that it was retiring Grok's 3D animated companions to focus on core chat. Grok had distribution most startups will never have.
   - The whole AI companion category was on track for about US$120M of consumer spending in 2025. General assistants took about 40% of spending across the top AI apps.
   - Embodiment may be valuable. It is not yet proven valuable, and the plan is right to treat it as the hypothesis.

**Technical feasibility verdict:** **not feasible on the current architecture; feasible with a renderer change.** Keep the behaviour, state, voice and overlay layers. Replace the photo-mesh renderer after a two-week spike (section 6).

**Fastest path to first revenue:** a clearly labelled **founding-member pre-sale** for the top one or two Phase 0 arms. Then charge from day one of the Android beta through Play Billing, with a trial (section 9).

---

## 1. What v2 gets right (KEEP)

- **The central hypothesis and the ultimate kill test** (sections 43 and 45). This is the most important improvement over v1.
- **One paid tier and no Pro** until usage justifies one (section 15).
- **Marketing stays narrow while the product stays broad** (sections 20–21). Measure each campaign separately.
- **A distinction between essential and vanity avatar work**, with an avatar quality gate (sections 5 and 27). The gate needs a number (section 8).
- **The four-field feature rule:** commercial metric, user evidence, test and kill condition (section 32).
- **The cost targets:** 90th-percentile AI cost at or below 25% of net revenue, and total variable cost at or below 35% (section 31).
- **The iOS presence model:** app, notifications, widgets and Live Activities (section 28).
- **The likeness and voice rights register**, and consent for self-avatars and living people (section 29).
- **Wellbeing companion, not AI therapist**, with safety handling for self-harm, abuse and emergencies (sections 10 and 30).
- **Memorial avatars excluded from the MVP**, with a legal and ethics review first (section 9).
- **Trust controls:** "What my avatar knows about me", plus correct, delete and block by category (section 12).
- **The deferrals:** marketplace, voice cloning, family networking, AR, VR, walking, physics and wardrobe (sections 26 and 35).

---

## 2. Critical findings

### F1. The central hypothesis has no control group

- Sections 21–23 compare five campaigns against each other. Every arm shows an avatar.
- Phase 1 launches every beta user with an avatar.
- So no result can show whether embodiment adds anything over "a voice AI with memory and check-ins".
- **Fix:** randomise new beta users 50/50 inside each winning arm:
  - **Embodied:** the full avatar and overlay.
  - **Control:** the same voice, memory, personality and check-ins, shown as a static portrait in a notification-first interface.
  - Compare D30 retention, trial-to-paid conversion and revenue per install.
- **Size it for a large effect.** A 50% relative lift in D30 retention, from 12% to 18%, needs about **550 activated users per arm** at 80% power. A 25% lift needs about 2,000 per arm.
- If embodiment's effect is smaller than about 50%, it probably cannot carry a company's entire differentiation. The test is sized to answer the question that matters, at a budget a founder can afford.
- **Cheap within-user signal the app already supports.** Leon has a minimised state. Track the share of active time each user keeps the avatar visible. If most users minimise it, that is evidence before the formal test ends.
- Phase 0 has the same flaw. Add a **no-avatar control ad**, for example "An AI coach that remembers you and checks in", to every campaign family. Otherwise click-through measures the desire, not the body.

### F2. The current renderer cannot produce the MVP in section 38

Evidence, from `main` @ `f550b52`:

| MVP requirement | Current state | Evidence |
|---|---|---|
| 3–5 avatar options | **One.** The skeleton and skin boundaries are constants measured from Leon's photo: joint positions, hip, spine and chest lines, and a body-edge contour. Fifteen geometry constants sit in the rig and twelve in the mesh. A new avatar means re-measuring and re-tuning by hand. | `rig/LeonRig.java` lines 22–58; `render/LeonMeshRig.java` lines 25–87 (`BODY_EDGE_Y`, `BODY_EDGE_HALF_WIDTH`, `ARMPIT_Y`) |
| Improved face, lip and eye behaviour | **Computed but invisible.** Blink, gaze, viseme and lip-sync controllers exist. The mesh binds the whole head to one bone, so none of them show. | `anim/BlinkBehaviour.java`, `anim/LeonLipSyncController.java`, `anim/MouthShapeResolver.java`; README "Known limitation" |
| Head turns and posture | **Front view only.** One flat photo, with no out-of-plane rotation. | `docs/LEON_TECHNICAL_TRUTH_AUDIT.md` section 2 |
| Custom and generated avatars (Phase 2 and revenue engine 2) | **Impossible on this pipeline.** Each avatar needs a clean alpha-cut photo, hand-entered landmarks and hand-tuned skin constants. | `assets/leon/leon-front.txt` holds 3 hand-entered numbers |
| Clothing, hair and accessories (revenue engine 3) | **Impossible.** Clothing is baked into the photo pixels. | Same |

**What is reusable:**

- the behaviour layer: breathing, head drift, gaze, blinks, gestures, nods, posture, visemes and lip-sync;
- the nine-state machine;
- the conversation controller;
- the overlay service with its unlock and permission hardening;
- the voice pipeline;
- the spend-capped backend.

These produce animation *parameters*. A new renderer can consume them. Section 6 recommends the replacement.

### F3. "Let the market choose" will choose engagement, and engagement costs money

**Net revenue at A$14.99 a month:**

| Line | A$ |
|---|---|
| List price, including GST | 14.99 |
| Less GST (1/11) | −1.36 |
| Less store fee, modelled at 15% | −2.04 |
| Less refunds, *assumption* 2% | −0.23 |
| **Net** | **≈ 11.36**, about **US$7.40** at an assumed 0.65 exchange rate |

- The current voice stack (Whisper, `gpt-4o-mini`, `tts-1`) costs about **US$0.005 per voice turn**. Speech output is about three-quarters of that. The derivation is in the previous audit, section 5.
- The 25% ceiling is about US$1.85 a month, or about **380 voice turns a month, roughly 13 a day**.
- Companion users are the heaviest users in consumer AI. An arm that "wins" on interactions a day could lose money on every payer.
- Visible lip-sync adds a requirement. The current `tts-1` call returns audio without timing data. Either drive the mouth from audio amplitude on the phone, which is free, or buy speech output that returns viseme or word timing, which adds cost.

**Fix:** rank arms by **contribution margin per install**, not engagement. Contribution margin is net revenue minus variable cost. Set a voice allowance on the paid tier from day one, and meter it per user.

### F4. The companion, friend and wellbeing arms carry the highest legal and reputational risk in consumer AI

| Event | Date | Relevance |
|---|---|---|
| eSafety Phase 2 industry codes in effect; AI companion chatbots face age-assurance and risk-assessment duties | 9 March 2026 | Applies directly to campaigns A, D and wellbeing in Australia |
| FTC 6(b) orders to seven companion-chatbot operators on harms to children and teens | September 2025 | Signals US enforcement direction |
| Italy's data regulator fined Replika's developer and upheld its restrictions | 2025 | Privacy and minors |
| Character.AI banned under-18 open-ended chat | November 2025 | Industry-standard response |
| Character.AI and Google agreed to mediate settlements in teen wrongful-death suits | January 2026 | Liability is real, not theoretical |

Two lines in v2 conflict with this environment:

- **Section 14:** "Make users momentarily forget they are interacting with software." That is the deceptive-anthropomorphism pattern regulators target. Rephrase it as **"feels alive; always known to be AI"**, and disclose AI status persistently.
- **Section 13:** "Yesterday sounded rough. How are you doing today?" This infers mental state, which is health information and sensitive under the Privacy Act. It needs explicit consent and a user setting to turn it off.

**Before any companion, friend or wellbeing arm reaches real users, it needs:**

- 18+ age assurance;
- crisis detection with referral to Australian services such as Lifeline 13 11 14;
- no sexual content;
- **no guilt-based retention messages**, for example "I missed you, don't leave";
- usage guardrails for very heavy or late-night use;
- a documented eSafety risk assessment.

### F5. Phase 0 must not sell a picture the product cannot render

- Section 37 proposes ads with "realistic representations" of companion, bodybuilder coach, assistant, friend and custom avatars.
- If the beta renders something visibly worse, the test produces a **false positive**, followed by refunds and bad reviews.
- Depicting capabilities that do not exist, without clear labelling, risks **misleading or deceptive conduct** under the Australian Consumer Law.
- **Fix:**
  - Label concept visuals as "concept" or "coming".
  - Take refundable deposits only, not subscriptions.
  - Once the renderer spike ends, re-shoot the winning arm's creative with **real in-app capture** before spending on paid acquisition.

### F6. Custom, self and person-inspired avatars are a deepfake surface

Text-to-avatar generation can produce lookalikes of real people. Photo upload can be used on someone else's photo.

**Controls needed before any custom or self avatar ships:**

- a liveness selfie to prove the uploader is the subject of a self-avatar;
- no third-party photo uploads in the MVP;
- public-figure similarity blocking on generated faces;
- a ban on sexualised content for any likeness;
- takedown handling.

Australia criminalised non-consensual sexual deepfakes in 2024. The rights register in section 29 is necessary but not sufficient. Enforcement has to sit in the generation pipeline.

**Memorial avatars:** move from "research track" to **do not build for 12 months** (section 11). The upside is uncertain. A single misstep with a grieving user is a national headline for a company this size.

### F7. The North Star rewards the behaviour regulators and plaintiffs will cite

- "Weekly Meaningful Avatar Relationships" optimises relationship intensity. In discovery or a regulator's inquiry, that reads as dependency-seeking.
- It is also subjective, as v2 itself admits.
- **Fix:** make the North Star **Paid Weekly Active Users with positive contribution margin**. Keep relationship depth as a diagnostic. Add guardrail metrics:
  - the share of users above 3 hours a day;
  - late-night usage share;
  - the crisis-flag rate;
  - the share of users who keep the avatar minimised.

### F8. Eight onboarding roles means eight products to make good

- "Who do you want me to be?" with eight roles, plus Custom, means the beta must be credible in every role, including a fitness coach giving training advice and a wellbeing companion handling distress.
- **Fix:** keep the question and all the options. **Build only the Phase 0 winners properly**, meaning the top two arms. Show the rest as "coming soon" and count taps. That is a free in-app demand test consistent with section 21.

### F9. Five campaign arms need a real budget, and the plan does not state one

**Rough in-app beta sizing** (*assumptions*):

- 2 arms × 2 randomised variants × about 550 activated users is about 2,200 activated users.
- At 50% activation, that means about 4,400 installs.
- At an assumed A$3–6 cost per install in Australia, that is about **A$13k–26k**.

Phase 0 landing pages across five arms plus controls are far cheaper, about A$1–3k at small scale, and should come first. State the budget and the stopping rule up front.

---

## 3. Competitive differentiation

**Embodiment alone is not a moat.** Large players can add an animated body in months, and have removed one just as fast. Durable differentiation, if it exists, will come from four things together:

- the **user's own customised character**, which is identity plus ownership;
- **continuity:** memory plus a visual relationship history;
- **presence on Android** that the big assistants do not offer as a character overlay;
- a **safety posture** that lets a small company operate in a category where larger players are being sued.

**Embodied and companion competitors to track:**

- **Tolan:** a stylised animated alien. More than 100,000 payers and over US$1M a month by its own reports. US$4.99 a week, US$10 a month, about US$70 a year. Proof that a stylised, ownable character can sell.
- **Grok Companions (xAI):** 3D animated companions launched in 2025. **xAI announced their retirement on 24 July 2026.** Its public reasoning was to focus on core chat and memory. The animated layer moved to a standalone app, Animates, from Animation Inc, the studio behind the avatars.
- **Replika:** 3D avatar plus chat. Regulatory action in Italy.
- **Character.AI:** mostly text characters. Minor ban and litigation.
- **Kindroid, Nomi and Talkie:** companion apps with generated selfies, voice and video features.
- **Microsoft Copilot:** added an animated voice character in late 2025.
- **Real-time photoreal avatar APIs** such as HeyGen LiveAvatar and Tavus. These make photoreal talking heads available to anyone at about US$0.10 a minute, falling to about US$0.01 at enterprise volume.
- **Desktop and VTuber character tools** such as Live2D-based apps and VRM avatar ecosystems. They prove demand for characters on screen and set user expectations for expressiveness.

Verify each competitor's status and pricing before external use.

---

## 4. Market and willingness to pay

- **Category size.** AI companion apps were on track for about **US$120M** of consumer spending in 2025, up 64% year on year. Revenue per download was about **US$1.18** (Appfigures, via TechCrunch, August 2025). About 33 apps had passed US$1M in lifetime spending.
- **Implication.** Companion revenue is concentrated and small next to general assistants. A company built only on companionship is chasing a thin, contested pool. The plan is right not to be a companion app. Its multi-role design is the right hedge, if the roles work.
- **Price anchors in Australia:**
  - ChatGPT Go costs about A$13 a month and Plus about A$30–35.
  - Google AI plans cost A$7.99, A$32.99 and A$149.99 a month.
  - Tolan costs about US$10 a month.
  - The plan's A$14.99–19.99 range is reasonable. Test A$14.99 against A$19.99, and an annual plan around A$99–129.
- **Custom-avatar monetisation.** One-off purchases pay a store fee, *assumption* 15% under Google's small-developer programme; verify it. Custom creation fees are margin-rich only with a **parametric** pipeline, where new avatars are assembled from parts at near-zero marginal cost. Hand-rigged avatars cost an artist's time per character, typically hundreds of dollars or more. Only a professional or creator tier could carry that.

---

## 5. Unit economics by arm (modelled)

These use the same per-turn cost as F3. They cover voice only; memory extraction and proactive calls add to them.

| Arm (*assumed* voice turns a day) | Turns a month | AI cost a month (US$) | Share of US$7.40 net |
|---|---|---|---|
| Assistant or coach (8) | 240 | 1.16 | 16% |
| Positive friend (20) | 600 | 2.90 | 39% |
| Companion (40) | 1,200 | 5.81 | 78% |
| Heavy companion (80) | 2,400 | 11.62 | 157% |

**Photoreal streaming avatar** (for example LiveAvatar at about US$0.10 a minute):

- 10 minutes of speaking a day costs about US$30 a month, which is **about 4 times net revenue**.
- At a US$0.01 enterprise rate it costs about US$3 a month, or about 40%.
- **Not viable for continuous presence at A$15.** It is possible only as an occasional premium "video moment".

**Levers, in order:**

1. A voice allowance on the paid tier.
2. On-device speech output for free users and the control arm. Android's `TextToSpeech` is already in the app.
3. Shorter replies.
4. Cached common phrases.
5. A cheaper neural voice.
6. Mouth animation driven by audio amplitude on the phone.

---

## 6. Technical feasibility

### Options for the renderer

| Option | Presence ceiling | Many avatars and customisation | Face, lip and eyes | Phone GPU and battery | Cost | Verdict |
|---|---|---|---|---|---|---|
| **A. Keep the 2D photo-mesh** | Low: one front view; face animation needs per-part art that does not exist | No: each avatar hand-measured | Only with new per-part art | Low | Sunk | **Dead end beyond one character** |
| **B. Live2D-style 2D rig** | Medium-high; the standard for expressive 2D characters | Premade characters yes; per-user custom needs an artist | Yes: mouth, eye and brow parameters | Low-medium | SDK free for small businesses under about ¥10M a year in revenue (verify); artist rig per character | **Best for 3–5 premade characters fast** |
| **C. 3D humanoid (VRM-style) with parametric parts** | Medium-high; head turns, posture, clothing | **Yes: assembled from parts**; the basis for custom avatars and assets | Yes: standard blendshapes for visemes and eyes | Medium-high; must be managed | Engine integration plus a stylised base model and parts library | **Best if custom avatars and assets are real revenue engines, as sections 8, 16 and 17 say** |
| **D. Real-time neural video (photoreal)** | Highest realism while talking | Easy | Yes | Server-side | About US$0.10 a minute, falling to US$0.01 | **Not for always-on presence** (section 5) |

### Recommendation

- **Stop photo-mesh work now.**
- **Run a two-week renderer spike** of B against C. Put one stylised character on each inside the existing overlay, driven by the existing behaviour and viseme layer.
- Choose on three measured numbers:
  - **Presence score:** 30 blind testers. Pass if at least 60% rate "feels like a character living on my screen" at 4 out of 5 or higher, and fewer than 20% describe it as a distorted photo or a sticker. These are *proposed thresholds*.
  - **Battery:** idle overlay at or below about 2% an hour on a mid-range Android phone, and speaking at or below about 6% an hour. *Proposed thresholds*; measure on at least two devices.
  - **Frame stability:** no visible stutter at 30 fps while speaking; idle rendering can run lower.
- **Choose stylised, not photoreal.** Every commercial proof point is stylised: Tolan, Finch, Duolingo and Grok's own anime companions. Photoreal is the hardest target, the most uncanny when slightly wrong, and the most exposed to deepfake misuse.
- **Is 3D necessary?** Only if customisation is a revenue engine. The plan says it is. If the spike confirms 3D meets the battery gate, choose C once rather than building B and then C.
- **Overlay constraints still apply on Android:**
  - overlays are hidden over the lock screen and system dialogs;
  - on Android 15 and later, a background foreground-service start needs a visible overlay;
  - microphone foreground services have tighter rules again.
  Recent issues #206 and #207 were in exactly this area. Keep the overlay optional and resilient.

---

## 7. Section-by-section challenge (appendix mandate)

| Topic | Finding | Recommendation |
|---|---|---|
| Actual differentiation | Embodiment is copyable and has been reversed by a large player | Differentiate on the user's own character plus continuity plus Android presence plus safety (section 3) |
| Embodied competitors | See section 3 | Track Tolan's pricing and retention signals; learn from Grok's retirement |
| Avatar and companion market revenue | About US$120M in 2025 for companions; concentrated | Multi-role is the right hedge; companion-only is a thin pool |
| Willingness to pay | Unproven for embodiment specifically | Pre-sale plus the randomised control (F1) |
| Custom-avatar monetisation | Margin-rich only if parametric | Choose renderer C if this engine matters |
| Character creation cost | Hand-rigged: hundreds of dollars or more per character; parametric: near zero marginal | Budget 3–5 premade characters; parametric for custom |
| Android and iOS restrictions | Covered well in section 28 | Keep the overlay optional; design iOS presence before the growth gate |
| Requirements for lifelike presence | Eyes, blinks, mouth, head turn and state transitions matter most | The behaviour layer exists; the renderer does not show it |
| Can current 2D do it? | **No** (F2) | Replace the renderer |
| Does 3D become necessary? | Yes, if custom avatars and assets are revenue | Spike B against C |
| GPU, rendering and battery | Unmeasured | Battery gate in the spike |
| Subscription economics | Companion arms risk negative margin | Allowance plus ranking by contribution margin |
| AI and voice cost | Speech output dominates; lip-sync timing adds a need | On-device amplitude lip-sync first |
| Likeness and voice rights | Register exists; enforcement missing | Liveness checks, public-figure block, no third-party photos |
| Memorial risk | High | Do not build for 12 months |
| Mental-health safety | Section 10 is right in principle | Crisis flow, no guilt retention, consent for mood inference |
| Australian regulation | eSafety codes, Privacy Act sensitive information, ACL advertising, deepfake offences | Lawyer review before any beta user |
| App-store policy | Play Billing for digital subscriptions and items; age rating for companion content | Play Billing in the MVP; honest store listing |
| Privacy | Sensitive inferences | Category-level opt-out (already in section 12) plus consent |
| Age assurance | Required for companion features in Australia | 18+ gate in the MVP |
| Deepfake and impersonation abuse | F6 | Pipeline controls |
| Creator and professional marketplace | Correctly deferred | A concierge professional pilot is cheaper than a marketplace (section 10) |
| Acquisition | Five arms without a budget or control | Add a budget, a stopping rule and a no-avatar control |
| Switching cost | The user's own character plus history is plausible | Measure churn by customisation depth |
| Retention hypothesis | Plausible; untested | F1 test |

---

## 8. Top 10 commercial risks

1. **Embodiment adds no measurable lift** over a voice AI with memory, and the company's differentiation disappears.
2. **Big platforms add or ignore characters at will.** xAI's retirement of Grok Companions shows the feature is cheap for them to try and cheap to drop.
3. **Companion arms win engagement but lose money** on voice cost (F3, section 5).
4. **A safety incident with a vulnerable user** in the companion or wellbeing arms, with litigation and regulatory precedent already set (F4).
5. **The renderer never crosses the presence threshold** and the product reads as a distorted photo (F2).
6. **Phase 0 false positive** from concept visuals the product cannot match (F5).
7. **Deepfake or likeness misuse** through custom or self avatars (F6).
8. **A thin, concentrated category.** Companion spending is small and top-heavy (section 4).
9. **Android-only beta results do not transfer** to iOS, which is about 65% of Australian mobile use and needs a different presence model.
10. **Founder capacity is split** across HumanGate, heavy process and Leon. v2 still does not say what happens to HumanGate.

## 9. Top 10 revenue opportunities

1. **Founding-member pre-sale:** a refundable annual deposit at, for example, A$79–99 for the winning arms. It is the fastest cash and the strongest willingness-to-pay signal.
2. **Charging from day one of the beta** through Play Billing with a 7-day trial. Paying beta users are the only evidence that counts.
3. **An annual plan as the default offer.** It improves cash flow and retention.
4. **Paid character slots or premium characters.** A second character costs extra, which tests the "that's my AI" ownership thesis directly.
5. **Custom avatar creation** as a one-off fee, *if* the pipeline is parametric.
6. **Role packs** such as bodybuilding, study or language partner, tested first as part of Premium and later as add-ons (section 18).
7. **A professional avatar concierge pilot:** 5–10 trainers or creators pay a setup fee plus a monthly fee for a branded character for their clients. It brings in business money before any marketplace exists.
8. **Voice top-ups** above the allowance, priced above cost.
9. **Cosmetic packs** such as outfits and environments, only after retention is proven. The plan already says this.
10. **Licensing the character runtime** (overlay plus behaviour layer plus voice) to brands or apps later, if the renderer becomes strong.

---

## 10. KEEP / CHANGE / KILL / ADD

**KEEP:** everything in section 1.

**CHANGE:**

- Add a randomised **no-avatar control** to Phase 0 and Phase 1 (F1).
- Change the **North Star** to Paid WAU with positive contribution margin, with relationship depth as a diagnostic and guardrails (F7).
- **Rank arms** by contribution margin per install, not engagement (F3).
- Rephrase the **lifelike objective** to "feels alive; always known to be AI" (F4).
- **Build only the top two arms** properly and show the other roles as "coming soon" (F8).
- **Label Phase 0 concept creative** and take refundable deposits only (F5).
- Move **memorial avatars** from research track to do-not-build for 12 months (F6).
- Give the **avatar quality gate** numbers (section 6).
- **State the test budget** and the stopping rules (F9).

**KILL:**

- The **2D photo-mesh renderer** as the product path. Keep its behaviour layer.
- **Photoreal** as the visual target for the MVP.
- **Streaming video avatars** for continuous presence.
- The **guilt-based or dependency-seeking retention copy** implied by relationship-intensity metrics.

**ADD:**

- the renderer spike (B against C) with presence, battery and frame gates;
- the randomised embodiment test design and sample sizes;
- safety controls in the MVP: 18+ assurance, crisis flow, no sexual content, consent for mood inference, guardrail metrics;
- deepfake controls in the pipeline: liveness, public-figure block, no third-party photos;
- a per-user voice allowance and on-device amplitude lip-sync;
- a founding-member pre-sale and a concierge professional pilot;
- an explicit HumanGate decision.

---

## 11. BUILD NOW / BUILD LATER / DO NOT BUILD

### BUILD NOW (first 90 days)

1. **Renderer spike:** B against C, one stylised character each, in the existing overlay.
2. **Accounts and per-user data**, reusing HumanGate's auth, RLS and audit patterns.
3. **3 premade stylised characters** on the chosen renderer, with visible blinks, gaze, amplitude lip-sync and head motion.
4. **Role and personality configuration** as prompt and behaviour settings on one engine (sections 6 and 11). Build the top two roles properly; list the rest as "coming soon".
5. **Memory v0** with "What my avatar knows about me": view, correct, delete and block categories.
6. **Proactive check-ins v0** with Quiet, Normal, Proactive and Coach levels.
7. **Play Billing:** one tier, monthly and annual, with a trial.
8. **Analytics**, including avatar-visible share, the embodied/control assignment and contribution margin per user.
9. **Per-user cost metering and a voice allowance**, extending the existing spend ledger.
10. **Safety:** 18+ age assurance, crisis detection and referral, content limits, guardrail metrics, and account and data deletion.

### BUILD LATER (evidence-gated)

- custom avatar creator (parametric), after the embodiment test passes;
- multiple simultaneous roles on one character;
- deeper memory, routines and goal tracking;
- iOS presence app (notifications, widgets, Live Activities), designed during the beta and built before the growth gate;
- emotional animation and environment-aware behaviour;
- role packs and cosmetic packs;
- a professional avatar pilot, beyond the concierge stage;
- occasional premium "video moments" with a streaming photoreal avatar, only if priced above cost.

### DO NOT BUILD (next 12 months)

- memorial or deceased-person avatars;
- voice cloning of real people;
- third-party photo likeness;
- creator or professional marketplace;
- partner or family network;
- complex action agent;
- always-on listening;
- AR and VR;
- walking systems and physics;
- more photo-mesh polish;
- a Pro tier.

---

## 12. Fastest path to first revenue

1. **Weeks 1–2:** landing pages for the five arms plus no-avatar controls, with concept creative labelled as concept. Offer a **refundable founding-member annual deposit**. Web checkout is allowed outside the app; HumanGate already has Stripe code.
2. **Weeks 7–8:** open the Android beta **with Play Billing live and a 7-day trial**. No free beta.
3. **In parallel:** approach 5–10 personal trainers or creators for a **concierge professional avatar pilot** with a setup fee and a monthly fee, if the fitness arm shows demand.

This produces real money within weeks and turns willingness to pay into revealed preference, not interview answers.

---

## 13. 90-day roadmap

| Weeks | Work | Gate at end |
|---|---|---|
| **1–2** | Founder decisions (section 16). Stop photo-mesh work. Renderer spike B against C. Phase 0 landing pages for 5 arms plus controls, and pre-sale. 20–30 interviews. Safety and legal spec. | Renderer passes the presence, battery and frame gates. At least one arm reaches pre-sale conversion of 1% or more *and* beats its no-avatar control on click-through or deposit rate. |
| **3–6** | BUILD NOW items 2–10 on the chosen renderer for the top two arms. | Internal quality review. Safety test script passes. Cost per turn measured. |
| **7–10** | Controlled Android beta. Randomised embodied against control inside each arm. Price test A$14.99 against A$19.99. | At least 550 activated users per variant, or a documented reason for a smaller test with a larger minimum effect. |
| **11–13** | Read results against the kill criteria in section 15. Decide to scale, iterate or pivot. | Embodiment lift; paid conversion; M1 paid retention; contribution margin at the 90th percentile. |

## 14. 12-month roadmap

- **Q1 (months 1–3):** the 90-day plan above.
- **Q2 (months 4–6):**
  - If embodiment passes: deepen the winning arm, build multiple roles, and design and build the iOS presence app.
  - If it fails: ship the voice-first product without the body as the core, and keep the avatar as an optional skin.
- **Q3 (months 7–9):**
  - iOS launch if the growth gate passes.
  - Test the parametric custom avatar creator as a paid add-on.
  - Test role packs.
  - Push the annual plan.
- **Q4 (months 10–12):**
  - Scale paid acquisition at a known payback period.
  - Expand the professional pilot if the fitness or coaching arm leads.
  - Evaluate marketplace economics on paper only.
  - Legal and ethics review of the memorial concept, which stays unbuilt.

## 15. Kill criteria

These are *proposed thresholds*, to be revised once two cohorts have data.

| Test | Kill or change if |
|---|---|
| **Embodiment** (the central thesis) | In the randomised beta, the embodied variant shows less than a 25% relative lift in D30 retention **and** in paid conversion or revenue per install, compared with control. Then drop embodiment as the core thesis and keep it as an optional feature. |
| **Presence** | The renderer fails the presence gate after two iterations, about 8 weeks. Then stop avatar R&D and go voice-first with a simple character. |
| **Phase 0** | No arm reaches 1% deposit conversion, or no arm beats its no-avatar control. Then do not build Phase 1 as planned. |
| **Commercial** (per arm) | After 300 or more activated users, trial-to-paid is below 10% **and** M2 paid retention is below 50%. Then stop that arm. |
| **Economics** | 90th-percentile AI cost stays above 40% of net revenue after allowance changes. Then reprice or cap. |
| **Safety** | Any arm produces unresolved crisis-handling failures or guardrail breaches. Then pause that arm immediately. |
| **Battery** | Idle overlay above about 3% an hour on reference devices after optimisation. Then default the overlay off. |

---

## 16. Decisions only the Founder can make

1. **Renderer direction:** approve the two-week B-against-C spike and the stop on photo-mesh work.
2. **Test budget:** approve roughly A$15–30k for Phase 0 plus the randomised beta, or a smaller budget with a larger minimum effect.
3. **Arms in scope:** confirm that companion, friend and wellbeing arms go to real users only after the safety controls and a lawyer's review.
4. **Visual target:** stylised rather than photoreal for the MVP.
5. **Memorial avatars:** confirm do-not-build for 12 months.
6. **HumanGate:** freeze, sell-test or continue. It is still unaddressed in v2.

---

## Sources (checked 2026-09-29 and 2026-09-30)

- Appfigures via TechCrunch, AI companion spending 2025: https://techcrunch.com/2025/08/12/ai-companion-apps-on-track-to-pull-in-120m-in-2025/
- Appfigures, "Rise of AI Apps 2025": https://land.appfigures.com/rise-of-ai-apps-report-2025
- Tolan traction and pricing (GeekWire, 2025): https://www.geekwire.com/2025/ai-companionship-app-tolan-raises-20m-to-help-more-people-grow-with-a-virtual-alien-friend/
- Grok Companions retirement (secondary sources reporting xAI's 24 July 2026 announcement; confirm against xAI's own notice): https://www.sloane.world/guides/grok-companions-shutting-down-2026 , https://aicompanionguides.com/blog/grok-companions-alternatives-2026/
- Character.AI under-18 chat ban: https://blog.character.ai/u18-chat-announcement/
- Character.AI and Google settlement mediation (K-12 Dive, 13 January 2026): https://www.k12dive.com/news/characterai-google-agree-to-mediate-settlements-in-wrongful-teen-death-la/809411/
- FTC companion-chatbot 6(b) inquiry: https://www.ftc.gov/news-events/news/press-releases/2025/09/ftc-launches-inquiry-ai-chatbots-acting-companions
- Italian Garante and Replika: https://www.edpb.europa.eu/news/ai-the-italian-supervisory-authority-fines-company-behind-chatbot-replika_en
- eSafety industry codes: https://www.esafety.gov.au/industry/codes
- Live2D SDK licence: https://www.live2d.com/en/sdk/license/
- HeyGen LiveAvatar pricing: https://www.liveavatar.com/ , https://help.heygen.com/en/articles/12758516-introducing-liveavatar
- StatCounter, mobile OS share in Australia; ChatGPT and Google AI pricing in Australia; Google Play fees; Android 15 overlay rules: see `docs/MONETISATION_PLAN_AUDIT.md` Sources.
- Repository: `apps/leon-android/app/src/main/java/ai/leon/companion/rig/LeonRig.java`, `.../render/LeonMeshRig.java`, `.../anim/*`, `apps/leon-android/README.md`, `docs/LEON_TECHNICAL_TRUTH_AUDIT.md`, `apps/api/app/services/leon_voice.py`, `apps/api/app/core/config.py`.

Claims from general knowledge rather than a source checked this session should be re-verified before external use. These are: Kindroid, Nomi and Talkie features; Microsoft Copilot's animated character; Australia's 2024 deepfake offences; character rigging costs; and the Play fee on one-time purchases.

**Sample-size note.** The figures in F1 use a two-proportion test at α = 0.05 (two-sided) and 80% power: 12% against 18% needs about 550 per arm, and 12% against 15% needs about 2,000 per arm.

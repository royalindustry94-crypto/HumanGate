# Launch Blockers

**Repository:** HumanGate (renamed from Content Orchestrator, PR #127, 2026-09-13)
**Updated:** 2026-09-19 (docs-reconciliation pass — independently re-probed against protected `main`; supersedes the 2026-09-14 prose below wherever they conflict)
**Current protected `main` reference:** `79ee9592b07eb8d7f60af4315e0a02e31477caa9` (merged 2026-09-17 via PR #157; current exact-main CI run [35257054967](https://github.com/royalindustry94-crypto/HumanGate/actions/runs/35257054967) green on all 6 required jobs)
**Last independently Codex-passed baseline:** `dfacbbd1f941c98e9c437d828575acd35bf7d96c` (`CODEX_BASELINE: PASS` on coordination issue #90, 2026-09-14)

> **2026-09-19 reconciliation update:** the prior version of this file was current through
> `main` @ `dfacbbd1f941c98e9c437d828575acd35bf7d96c` / Alembic `0057` and did not reflect the
> post-baseline merges that followed: PR #142/#143 (comprehensive code-audit remediation),
> PR #145 (TD-093), PR #147 (TD-089), PR #149 (TD-090), PR #151 (TD-094), PR #153 (TD-091),
> PR #155 (TD-092), PR #157 (TD-096), and PR #159 (main CI format-gate repair). This pass
> independently re-probed exact protected `main` at `79ee9592b07eb8d7f60af4315e0a02e31477caa9`:
> Alembic head by walking every `revision`/`down_revision` pair in
> `apps/api/alembic/versions/` (**`0058`**, one true head — the three parallel `0031_*`
> leaves still merge via `0032_merge_p1`), the CI coverage floor (**79%**, not 75% —
> `.github/workflows/ci.yml:57-58`), exact-head CI run
> [35257054967](https://github.com/royalindustry94-crypto/HumanGate/actions/runs/35257054967)
> (green on all 6 required jobs: `api`, `worker`, `web`, `docker-build`, `browser-smoke`,
> `security`; `api` logs show migration replay passed, `browser-smoke` logs show the exact
> desktop + 390px smoke passed), and current pull-request ownership (**1** open draft PR:
> #161, this docs-only lane, owned by Copilot as the sole active Builder task). Protected
> `main` itself remains the last independently Codex-passed baseline at `dfacbbd…`; this
> docs-only pass does **not** claim a new `CODEX_BASELINE: PASS`.

**Source of truth:** exact-head CI, retained browser evidence, repository/runtime probes — not prior chat claims

## Rule

Nothing is considered deployable or releasable from documentation alone. The current exact candidate must satisfy `docs/MILESTONE_AUDIT_STANDARD.md` and receive PASS before merge/release.

---

## Current verdict

| Target | Status | Reason |
|---|---|---|
| Product code baseline | **PRIVATE-BETA CAPABLE** | Business Manager + audited Research → Strategy → Content → Production → Compliance preview is merged and fail-closed; the four Critical P0 production blockers from issue #126 (JWT fail-closed, staging credential exposure, durable automation ownership, unsupported-execution fail-closed) are now also closed |
| Development governance | **GREEN** | Independent audit standard is merged; GitHub `main` branch protection verified live as of the 2026-09-09 recovery audit (not re-probed again this pass — no reason to expect regression, but flagging that this pass relied on the prior verification rather than re-reading the ruleset itself) |
| Operational private beta | **CONDITIONAL / ONE MANUAL STEP REMAINING** | The Founder Studio Test (issue #68) golden path already has a functional **PASS** verdict (2026-09-10, run against local Postgres at verified parity with the managed project) and a confirmed-reachable live deployment (2026-09-11, `https://royalindustry9.vercel.app`). The issue remains open only because no tool in any session so far has been able to click through the live URL itself — that specific walkthrough needs a human (or a future session with real browser/POST tooling) and is not a code gap. |
| Production | **BLOCKED** | Live providers (PROVIDER-001), managed-database Alembic parity re-verification (RUNTIME-001 has fresh drift — see below), billing go-live (BILLING-001), policy/rights adapters, and external publishing (PUBLISH-001) remain separate gates |

---

## Merged audited baseline

PR #48 merged the bounded Founder Preview pipeline with these workspace-scoped stages:

1. Business Manager UI
2. Scout + Research Auditor
3. Strategist + Strategy Auditor
4. Content Department
5. Producer + Media QA
6. Compliance + Chief Auditor

Safety boundaries remain explicit:

- Human Review Gate remains mandatory.
- External publishing remains disabled in the preview path.
- Unconfigured provider paths are truthful and spend zero provider cost.
- Workspace/RLS negative tests cover the new domain slices.
- No autonomous publishing or live-provider execution was enabled by the preview milestone.

PR #94 and PR #95 (merged 2026-09-09) closed the full TD-072–TD-088 batch (RLS
tenant-isolation backstops across `content_jobs.py`/`review_gates.py`/`profiles`/the
Operations Dashboard, a CRITICAL cross-tenant dashboard-blending fix, Stripe webhook
hardening, audit-trail and idempotency fixes) and hardened the Operations Dashboard
frontend. Every finding has its own regression test, independently re-audited PASS
(issue #91). PR #98/#108/#109 subsequently closed the Founder Studio Test's two
functional gaps (edit-before-approve on review gates; a visible Ready-to-Publish queue —
see issue #68).

**PR #129, #130, #137, #140 (merged 2026-09-14)** closed the four Critical findings from
issue #126's independent external audit. **Post-baseline merges after that exact head**
closed seven of issue #126's eight follow-up warnings and one CI regression: PR #145
(TD-093), PR #147 (TD-089), PR #149 (TD-090), PR #151 (TD-094), PR #153 (TD-091),
PR #155 (TD-092), PR #157 (TD-096), and PR #159 (restore `main`'s API format gate). The
only still-open issue-#126 follow-up item is TD-095 (oversized modules / duplicated
business logic).

### Verified engineering evidence

Independently re-probed against protected `main` @ `79ee9592b07eb8d7f60af4315e0a02e31477caa9`
during this 2026-09-19 reconciliation pass:

- Exact-head CI run [35257054967](https://github.com/royalindustry94-crypto/HumanGate/actions/runs/35257054967): **green** on all 6 required jobs (`api`, `worker`, `web`, `docker-build`, `browser-smoke`, `security`)
- Alembic: current head is **`0058`** (independently derived from the revision graph, not read from prior docs)
- API coverage gate: **79%** (`ci.yml`), not the previously-documented 75%
- `main` branch protection: carried forward from the 2026-09-09 verification (`protected: true`) — not independently re-read this pass
- Open pull requests: **1** — draft PR #161 (`copilot/reconcile-launch-technical-debt-docs`), owned by Copilot, and explicitly bounded to this docs-only reconciliation task

This pass did **not** re-run a fresh local `pytest`/coverage measurement (docs-only task,
30-minute budget) — the exact-head CI run above is the evidence of record for test/coverage
passing state going forward. The specific pass counts and coverage percentages previously
printed here (e.g. "339 passed, 80.82%") are **removed** rather than carried forward
unverified, since the codebase has materially changed since they were measured (Alembic
`0054`→`0058`, coverage floor 75%→79%, post-`dfacbbd` hardening merges) and restating stale numbers next
to a corrected head would be misleading. Re-measure locally before quoting a specific
count again.

---

## Open blockers / conditions

### P0-1..P0-4 — Critical production blockers (issue #126) — **CLOSED (2026-09-14)**

An independent external audit (baseline `895398a`) found four Critical findings; Codex
posted `CODEX_BASELINE: CHANGES_REQUESTED` and defined the bounded remediation scope.
All four are now closed and independently re-verified:

| ID | Finding | Fix |
|---|---|---|
| P0-1 | JWT secret/claims did not fail closed | PR #129 |
| P0-2 | Staging PostgreSQL exposed default credentials + published host port | PR #130 |
| P0-3 | Scheduler/outbox/maintenance loops lived in FastAPI lifespan, incompatible with Vercel's stateless function model | PR #137 (migration `0057`) |
| P0-4 | Worker executor returned generic success for unimplemented stages | PR #140 |

`CODEX_BASELINE: PASS` posted on issue #90 for exact head `dfacbbd1f941c98e9c437d828575acd35bf7d96c`; issue #126 closed. Since that exact head, merged PRs #145/#147/#149/#151/#153/#155/#157 independently closed TD-089, TD-090, TD-091, TD-092, TD-093, TD-094, and TD-096 on protected `main`. Only TD-095 (oversized modules / duplicated business logic) remains open from issue #126's follow-up warnings; see `docs/TECHNICAL_DEBT_REGISTER.md`.

### GOV-001 — Protect `main` — **CLOSED (2026-09-09, carried forward)**

Tracked by GitHub issue **#50** (closed 2026-08-28; its linked implementation PR #59 was
never merged, so the 2026-09-09 recovery audit re-verified the control directly rather than
trusting the issue's closed state at face value).

Evidence (as of the 2026-09-09 audit, not independently re-read this pass): live read via
`mcp__github__list_branches` returned `{"name":"main","protected":true}`. The exact
per-check ruleset detail (which status checks are required, force-push/deletion block,
admin-enforcement) was not individually re-read then and still hasn't been — reopen narrowly
if that finer-grained evidence is ever needed for an external audit.

### RUNTIME-001 — Managed Supabase/runtime verification — **RE-OPENED (drift, 2026-09-14)**

Previously marked CLOSED. The last independently confirmed managed-database Alembic head
(`docs/TECHNICAL_DEBT_REGISTER.md` TD-071, 2026-09-10 update) was **`0055`**. `main` has
since advanced through migrations `0056`, `0057`, and `0058` (issue #108's review-gate RLS
widening, P0-3's automation-ownership migration, and TD-090's shared request-rate-limit
table) — **none has been applied to or verified against the managed
`content-orchestrator-test` project**, per this pass's independent re-derivation of the
current Alembic head. This is a real, growing gap (3 unapplied migrations, up from the
2-migration gap the last update tracked), not a new finding — but it was stale in this
document and needs the same apply-and-verify treatment TD-071 previously received. Reopen
scope: apply `0056`/`0057`/`0058` to the managed project via Supabase MCP,
cross-check pre/post RLS+grant state as TD-071's prior passes did, re-run the Security
Advisor. **Not performed in this pass** — this reconciliation task is docs-only and this
action touches the managed database, which is out of scope here.

### PROVIDER-001 — Live provider activation — **OPEN / DEFERRED**

Before enabling OpenAI/Anthropic/Gemini/ElevenLabs/Creatomate/n8n or equivalent live effects, require a separate audited milestone covering credentials, provider abstraction, retries/backoff/timeouts, idempotency, spend reserve/commit accounting, redaction/logging, supervised provider tests and failure behavior. Re-verified this pass (`docs/TECHNICAL_DEBT_REGISTER.md` TD-041): still fully deferred, no code has changed on this path since 2026-09-08.

### BILLING-001 — Billing go-live — **OPEN / DEFERRED**

Billing code exists but production billing remains a separate live-secret/reconciliation gate. Do not infer paid-production readiness from the in-repo Stripe implementation. Re-verified this pass (`docs/TECHNICAL_DEBT_REGISTER.md` TD-085): `billing_enabled` still defaults `false`, still fully deferred.

### PUBLISH-001 — External publishing — **BLOCKED BY DESIGN**

No autonomous/external publishing milestone is authorized. Any future enablement requires current platform policy/rights evidence, exact-artifact compliance, immutable Human Review approval, rollback/kill-switch evidence and Founder authorization. Unchanged this pass.

### SAFE-ENG-001 — Safe engineering follow-up (non-launch, non-runtime) — **OPEN**

Protected `main` is currently clean on the required six-part CI run, but the exact-main
`api` and `browser-smoke` job logs for run `35257054967` both emit GitHub Actions'
forced-Node-24 warnings because the pinned official actions still target Node 20. This is
not a release blocker today — the jobs passed — but it is real near-term maintenance work
that should be kept separate from the human/mobile test, managed-database operator work,
provider activation, billing, and publishing gates above. TD-095 (oversized modules /
duplicated business logic) is the other remaining safe-engineering follow-up still open
from issue #126's post-P0 warning set.

---

## Historical P0/P1 baseline

Previously closed P0/P1 engineering controls remain closed unless new evidence demonstrates
regression. Their historical records remain in release/audit documents; this file now
reflects the current `0058` codebase rather than the obsolete `0032_merge_p1` snapshot.
Issue #126's P0-1..P0-4 batch (above) is a **second, later, and distinct** P0 wave — an
independent external audit found genuine new Critical findings after the original P0/P1
baseline had already closed; do not conflate the two waves when reading historical audit
documents that predate 2026-09-13.

---

## Related

- `docs/MILESTONE_AUDIT_STANDARD.md`
- `docs/EXECUTIVE_STATUS_REPORT.md`
- `docs/TECHNICAL_DEBT_REGISTER.md`
- `docs/FINAL_RELEASE_AUDIT.md`
- `docs/DISASTER_RECOVERY_REPORT.md`
- `docs/BETA_RELEASE_CHECKLIST.md`
- GitHub issue #50 — protect `main`
- GitHub issue #126 — P0 remediation (closed 2026-09-14)
- GitHub issue #68 — Founder Studio Test (open; one manual mobile-verification step remaining)
- GitHub issue #90 — coordination hub

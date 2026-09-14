# Launch Blockers

**Repository:** HumanGate (renamed from Content Orchestrator, PR #127, 2026-09-13)
**Updated:** 2026-09-14 (docs-reconciliation pass — independently re-probed against protected `main`; supersedes the 2026-09-09 prose below wherever they conflict)
**Audited baseline:** `main` @ `dfacbbd1f941c98e9c437d828575acd35bf7d96c`, after PR #129 (P0-1), PR #130 (P0-2), PR #137 (P0-3), and PR #140 (P0-4) merged 2026-09-14, closing issue #126's full Critical remediation scope

> **2026-09-14 reconciliation update:** the prior version of this file was current through
> `main` @ `2ca92f8` / Alembic `0054` (2026-09-09 recovery audit) and did not reflect the four
> Critical production blockers subsequently found and closed under issue #126, nor the repo
> rename to HumanGate, nor the true current Alembic head. This pass independently re-probed
> (not carried forward from prior chat/doc claims): Alembic head by walking every
> `revision`/`down_revision` pair in `apps/api/alembic/versions/` (**`0057`**, one true head —
> the three parallel `0031_*` leaves merge via `0032_merge_p1`), the CI coverage floor
> (**79%**, not 75% — `.github/workflows/ci.yml:55`), exact-head CI run
> [34858778445](https://github.com/royalindustry94-crypto/HumanGate/actions/runs/34858778445)
> (green on all 6 required jobs: `api`, `worker`, `web`, `docker-build`, `browser-smoke`,
> `security`), and open pull requests (**0** — a prior doc's "~30 open PRs" claim is stale).
> `CODEX_BASELINE: PASS` for this exact head is posted on coordination issue #90
> (2026-09-14T15:02:54Z / 15:03:39Z).

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
issue #126's independent external audit — see "P0-1..P0-4" under Open blockers/conditions
below for the closure record; this is the most recent and most safety-relevant addition to
the audited baseline and was not reflected in this document before now.

### Verified engineering evidence

Independently re-probed against `main` @ `dfacbbd1f941c98e9c437d828575acd35bf7d96c` during
this 2026-09-14 reconciliation pass:

- Exact-head CI run [34858778445](https://github.com/royalindustry94-crypto/HumanGate/actions/runs/34858778445): **green** on all 6 required jobs (`api`, `worker`, `web`, `docker-build`, `browser-smoke`, `security`)
- Alembic: current head is **`0057`** (independently derived from the revision graph, not read from prior docs)
- API coverage gate: **79%** (`ci.yml`), not the previously-documented 75%
- `main` branch protection: carried forward from the 2026-09-09 verification (`protected: true`) — not independently re-read this pass
- Open pull requests: **0** (independently listed this pass; corrects a prior "~30 open PRs" claim)

This pass did **not** re-run a fresh local `pytest`/coverage measurement (docs-only task,
30-minute budget) — the exact-head CI run above is the evidence of record for test/coverage
passing state going forward. The specific pass counts and coverage percentages previously
printed here (e.g. "339 passed, 80.82%") are **removed** rather than carried forward
unverified, since the codebase has materially changed since they were measured (Alembic
`0054`→`0057`, coverage floor 75%→79%, four new P0 fixes) and restating stale numbers next
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

`CODEX_BASELINE: PASS` posted on issue #90 for exact head `dfacbbd1f941c98e9c437d828575acd35bf7d96c`; issue #126 closed. Its text carries a **follow-up warnings** list (health-detail exposure, distributed rate limiting, worker concurrency/lease renewal, dashboard N+1/polling/export pagination, concurrent last-admin mutation, browser security headers/session design, oversized modules/duplicated business logic, stronger CI security supply-chain gates) — none yet independently triaged; recorded as TD-089 through TD-096 in `docs/TECHNICAL_DEBT_REGISTER.md`.

### GOV-001 — Protect `main` — **CLOSED (2026-09-09, carried forward)**

Tracked by GitHub issue **#50** (closed 2026-08-28; its linked implementation PR #59 was
never merged, so the 2026-09-09 recovery audit re-verified the control directly rather than
trusting the issue's closed state at face value).

Evidence (as of the 2026-09-09 audit, not independently re-read this pass): live read via
`mcp__github__list_branches` returned `{"name":"main","protected":true}`. The exact
per-check ruleset detail (which status checks are required, force-push/deletion block,
admin-enforcement) was not individually re-read then and still hasn't been — reopen narrowly
if that finer-grained evidence is ever needed for an external audit.

### RUNTIME-001 — Managed Supabase/runtime verification — **RE-OPENED (drift, status unverified, 2026-09-14)**

Previously marked CLOSED. The last independently confirmed managed-database Alembic head is
**`0056`** — not `0055`: a Copilot review on PR #141 caught this document under-citing the
evidence trail, since issue #68's 2026-09-11 live-infrastructure check already reported the
deployed backend's `/api/health/ready` connected to the `content-orchestrator-test` project
at migration head `0056`, matching `main` on that date (superseding TD-071's 2026-09-10
`0055` note, which is now the stale figure, not the current one). `main` has since advanced
once more, to `0057`, via P0-3's automation-ownership migration (PR #137, 2026-09-14).
**Whether `0057` has been applied to the managed project is unverified** — no Supabase query
has been run by any session since the 2026-09-11 check, so say "unverified," not "not yet
applied": that would assert a negative nobody has actually confirmed. Reopen scope: query the
managed project's current `alembic_version` first: if it already reads `0057`, this item
closes immediately with no further action; if it still reads `0056`, apply `0057` via
Supabase MCP, cross-check pre/post RLS+grant state as TD-071's prior passes did, and re-run
the Security Advisor. **Not performed in this pass** — this reconciliation task is docs-only
and this action touches the managed database, which is out of scope here.

### PROVIDER-001 — Live provider activation — **OPEN / DEFERRED**

Before enabling OpenAI/Anthropic/Gemini/ElevenLabs/Creatomate/n8n or equivalent live effects, require a separate audited milestone covering credentials, provider abstraction, retries/backoff/timeouts, idempotency, spend reserve/commit accounting, redaction/logging, supervised provider tests and failure behavior. Re-verified this pass (`docs/TECHNICAL_DEBT_REGISTER.md` TD-041): still fully deferred, no code has changed on this path since 2026-09-08.

### BILLING-001 — Billing go-live — **OPEN / DEFERRED**

Billing code exists but production billing remains a separate live-secret/reconciliation gate. Do not infer paid-production readiness from the in-repo Stripe implementation. Re-verified this pass (`docs/TECHNICAL_DEBT_REGISTER.md` TD-085): `billing_enabled` still defaults `false`, still fully deferred.

### PUBLISH-001 — External publishing — **BLOCKED BY DESIGN**

No autonomous/external publishing milestone is authorized. Any future enablement requires current platform policy/rights evidence, exact-artifact compliance, immutable Human Review approval, rollback/kill-switch evidence and Founder authorization. Unchanged this pass.

---

## Historical P0/P1 baseline

Previously closed P0/P1 engineering controls remain closed unless new evidence demonstrates
regression. Their historical records remain in release/audit documents; this file now
reflects the current `0057` codebase rather than the obsolete `0032_merge_p1` snapshot.
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

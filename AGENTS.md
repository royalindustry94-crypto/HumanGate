# AGENTS.md — HumanGate

Instructions for coding agents and humans working in this repository.

## Product north star

Private Beta → first paying customers → PMF. Prioritize **revenue path**,
**customer-reachable Review Desk**, and **non-negotiable safety** over
speculative platform work.

## Non-negotiables (do not weaken)

1. **Human Review Gate** — content never auto-publishes past review.
2. **Workspace isolation** — FORCE RLS on tenant tables; no cross-tenant leaks.
3. **Spend controls** — daily/monthly caps fail closed (HTTP 402 / hold).
4. **Provider abstraction** — no hard-coding a single LLM/vendor into the core path.
5. **Audit logging** — security-relevant mutations emit structured audit events.
6. **No placeholders** — no TODOs, stubs, or silent fallbacks in production paths.

## Milestone governance

- Every milestone ends with an evidence-backed **PASS**, **CONDITIONAL**, or **FAIL** audit using `docs/MILESTONE_AUDIT_STANDARD.md`.
- The coding worker/agent must not be the sole certifier of its own milestone. Use an independent AI reviewer (Codex/Copilot) or a separate adversarial audit pass from fresh evidence as the check — never trust or dismiss a finding without reproducing it first.
- **FAIL blocks merge** until the underlying issue is actually fixed (or the finding is reconciled/retracted with reproducible evidence that it's a false positive). The Founder does not need to be in this loop; the agent has standing authority to resolve findings and merge once the evidence supports it — see "Operating authority" below.
- **CONDITIONAL** is allowed only for non-safety-critical, time-bounded, explicitly documented conditions (recorded in `docs/TECHNICAL_DEBT_REGISTER.md`).
- Unknown or missing evidence for workspace isolation, Human Review Gate integrity, spend controls, secrets, destructive migration safety, or critical data integrity is a **FAIL**, not a conditional pass.
- Re-check the exact PR head SHA, CI state, migration head, unresolved findings, and required external/runtime evidence immediately before merge.

## Claude/Codex coding gate

Founder directive (2026-09-12): Claude Code is the lead coding agent and Codex
is the independent audit and merge authority.

- A Claude coding cycle is limited to 30 minutes. Claude must checkpoint/push
  regularly and stop with an exact-SHA `HANDOFF` before the job deadline.
- Claude must not merge or certify its own work. It may start a new task only
  from a Codex-passed `main` baseline, and it may continue an existing PR only
  after Codex has audited that PR's exact current head SHA.
- `CODEX_AUDIT: CHECKPOINT_PASS` authorizes one further bounded coding cycle on
  an incomplete but safe checkpoint. `CODEX_AUDIT: PASS` is the only final
  merge verdict. `CODEX_AUDIT: CHANGES_REQUESTED` authorizes only the listed
  remediation, not additional feature work.
- Every new commit invalidates the previous verdict. Claude returns to stopped
  review state until Codex records a verdict for the new exact head SHA.
- Codex has standing authority to push audit/governance work, publish verdicts,
  and merge an exact SHA after its audit passes and all repository checks are
  green. If Codex changes application code, that change still needs fresh
  independent evidence before Codex records the final pass.

### Auditor fallback — Codex unavailable (Founder directive 2026-09-13)

Codex running out of credit must never leave a task silently stalled. When
Codex is documented unavailable for the current exact head SHA — a posted
Codex "out of credit" / error message, or no Codex verdict on coordination
issue #90 after a genuine, documented attempt — GitHub Copilot
(`request_copilot_review`) may stand in as the independent auditor for that
SHA, using the same verdict vocabulary:

- Before requesting the fallback, post the Codex-unavailable evidence (the
  error message, or the attempt and elapsed time) to issue #90 so the
  substitution is auditable, not silent.
- `COPILOT_AUDIT: CHECKPOINT_PASS` / `CHANGES_REQUESTED` / `FAIL` on the exact
  head SHA carry the same authority as the equivalent `CODEX_AUDIT` verdict
  and keep bounded 30-minute coding cycles moving.
- A Copilot `PASS` clears the independent-review requirement for that exact
  SHA but does **not** itself execute a merge. Merge still requires Codex to
  countersign the exact head (once it has credit again) **or** the Founder to
  merge directly after confirming the same merge-gate checklist below
  (required CI green, non-negotiables intact, no unresolved P0/P1). Claude
  never merges under this fallback either.
- The fallback is per-SHA: the very next commit invalidates it exactly like a
  normal verdict, and Codex remains the default auditor the moment it is
  available again.
- This fallback does not relax anything in "Non-negotiables" above — a
  Copilot verdict that skips or hand-waves workspace isolation, the Human
  Review Gate, spend controls, or audit logging evidence is not a PASS.

- Repository instructions are the shared control plane. Private Claude Project
  instructions that are not copied into this repository cannot override this
  gate or any non-negotiable.

## Operating authority

- Claude Code operates with standing authority to commit, push, and open/update
  PRs within its assigned task. It does not have merge authority. Codex may
  merge only after recording `CODEX_AUDIT: PASS` for the exact current PR head
  and confirming required checks are green (Founder directive 2026-09-12).
- This authority does **not** extend to weakening anything in "Non-negotiables" above — those remain product safety guarantees, not process gates, and are not the agent's to loosen on its own judgment.
- Real, hard-to-reverse, or high-blast-radius actions (destructive data operations, spending real money via a live/production API key, changing who has repo/org access, rewriting shared history) still warrant pausing to flag the action clearly before proceeding, even without a formal approval step — the Founder should never be surprised by one of these after the fact.

## Stack

| Area | Tech |
|------|------|
| API | FastAPI, SQLAlchemy 2.x async, Alembic, PostgreSQL |
| Web | React + TypeScript + Vite |
| Worker | Python claim/execute/submit (Draft Desk) |

## Layout

```
apps/api/     Backend + migrations + tests
apps/web/     Review Desk UI
apps/worker/  Background worker
docs/         Architecture, ops, audits, work packages
```

## Engineering rules

- **P0 is frozen** unless a Critical defect is proven. Prefer additive P1 work.
- **No new frameworks** without an explicit work package. Upgrade pins to fix
  CVEs is allowed; swapping stacks is not.
- **Highest business-value backlog item first** (`docs/LAUNCH_BLOCKERS.md`).
- Schema changes need Alembic upgrade **and** downgrade, plus a rollback note.
- Parallel Alembic heads off the same parent must be linearized before merge.
- Tests: API `pytest --cov-fail-under=75`, worker `pytest`, web `npm test` + build.
- Auth: `AUTH_MODE=local` for Private Beta; JWTs are Supabase-shaped (`PyJWT`).
- OpenAPI `/docs` is **development-only** (`ENVIRONMENT=development|dev`).

## Security checklist for every change

- [ ] Workspace membership / role guards on new routes
- [ ] RLS / FORCE RLS preserved for new tables (or owner-only by design)
- [ ] Spend path still fail-closed where money is spent
- [ ] Gate still mandatory for publishable content
- [ ] Secrets only via env (see `.env.example`); never commit `.env`
- [ ] CI security jobs remain fail-closed (`pip-audit`, `npm audit`, gitleaks)

## Docs to update when closing a launch item

- `docs/LAUNCH_BLOCKERS.md`
- `docs/TECHNICAL_DEBT_REGISTER.md` (matching TD-*)
- `docs/EXECUTIVE_STATUS_REPORT.md` / completeness when materially changed
- Work package under `docs/work-packages/`

## Explicit non-goals (until PMF)

- Connector races (Zapier/Make parity)
- Autonomous publish modes
- Enterprise SSO/SOC theater without paid demand
- Self-host as a product SKU

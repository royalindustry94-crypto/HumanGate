# Agent Operating Protocol

This protocol applies to every human or AI agent working in this repository.
`AGENTS.md` remains authoritative. Under the Founder directive dated 2026-09-12,
Claude Code leads coding while Codex owns the exact-SHA audit verdict and merge.
If instructions conflict, the newer authority rule and the stricter safety,
review, and evidence requirement win.

**2026-09-10 update (Milestone 0 — Multi-Agent Orchestration Ready, Founder-directed):** Claude Code is the **Lead Orchestrator** for this repository — it dispatches bounded work to the other agents below, using GitHub MCP (issues/PRs/comments/reviews) as the permanent control plane, and drives every delegated task through independent review before merge. This supersedes the older "Orchestrator: Codex" row below. Codex and Copilot remain independent workers/reviewers — critically, **Claude Code never treats its own dispatch of a task as that task's independent review**; a worker's output still gets a separate review pass (another agent, or a from-scratch reproduction) before merge, per the existing "Builders cannot certify their own work" rule. See [coordination hub #90](https://github.com/royalindustry94-crypto/HumanGate/issues/90) for the live Milestone 0 evidence trail (per-worker READY/BLOCKED status, delegation tests, end-to-end results).

## Job ownership

| Role | Primary worker | Owns | Must not do |
| --- | --- | --- | --- |
| Founder | Mitch / `royalindustry94-crypto` | Priorities, product decisions, real-money/credential decisions | Delegate the Human Review Gate to automation |
| **Lead coding agent** | **Claude Code** | Owns one bounded coding task, branch, tests, checkpoints, PR, and exact-SHA handoff per 30-minute cycle | Continue without the matching Codex (or documented Copilot-fallback) checkpoint verdict; certify or merge its own work; weaken a non-negotiable |
| Delegated implementation worker | Cursor (background/cloud agent) | Bounded implementation tasks assigned via Cursor's own dispatch surface, once connected | Merge, deploy, or act outside an assigned bounded task |
| Independent auditor / merge authority | Codex | Exact-head code, security, CI, and release-readiness audit; checkpoint/final verdict; merge after final PASS | Pass a different SHA; merge with failed checks or unresolved blockers; treat its own application-code change as independently verified |
| Fallback independent auditor | GitHub Copilot (`request_copilot_review`) — only when Codex is documented unavailable (out of credit / no verdict after a genuine attempt) | Stand in for Codex's exact-head checkpoint/audit verdict (`COPILOT_AUDIT: CHECKPOINT_PASS/CHANGES_REQUESTED/FAIL/PASS`) so a 30-minute coding cycle isn't stalled by Codex's credit balance | Execute the merge itself; substitute for Codex when Codex is actually available; wave through a verdict that skips non-negotiable evidence |
| Cheaper bounded-work / test / docs worker | GitHub Copilot (`assign_copilot_to_issue`, `request_copilot_review`) | Small, well-specified bounded tasks (test fixes, docs, lint-scale changes) end-to-end: issue → PR; lightweight PR review | Own architecturally significant work; merge its own PR; act as sole reviewer of its own diff |
| Builder (legacy label, still valid) | Claude Code or Cursor, explicitly assigned per task | One queued issue, one branch, implementation, tests, and pull request | Merge; work without a current exact-SHA authorization; weaken controls; work outside the assigned issue |
| Reviewer / QA | A fresh Codex, Copilot, or other designated agent that did not build the change | Scope review, regression checks, exact-head CI evidence | Modify the reviewed head while claiming independence |
| Security auditor | Independent agent | PASS / CONDITIONAL / FAIL audit against the exact head SHA and non-negotiables | Approve its own implementation or ignore missing evidence |
| Build watchdog | GitHub Actions | Monitor the latest repository CI run only and retry genuine failures within bounded limits | Change product code, alter protections, expose secrets, or merge |

Only one Builder/worker owns a task at a time. A task must have an issue, a named owner, a branch, and a pull request before it can reach review.

## Work states

Use these labels when the queue is enabled:

- `agent:queued`: ready for assignment.
- `agent:active`: one Builder owns the task.
- `agent:review`: implementation stopped; exact-head review is required.
- `agent:blocked`: a real credential, external service, or safety decision only the Founder can make is required.
- `agent:done`: merged after every required gate.

A handoff must state the issue, owner, branch, exact head SHA, completed work, tests and run URLs, blockers, and the next role/action. Agents resume from that evidence instead of silently starting over.

Every Claude invocation is capped at 30 minutes. A safe checkpoint must be
pushed at least every 10 minutes, and the final handoff starts by minute 25.
Codex then records one exact-SHA outcome:

- `CHECKPOINT_PASS`: one more bounded feature cycle is authorized.
- `PASS`: final merge is authorized after all required checks are green.
- `CHANGES_REQUESTED`: only the listed remediation is authorized.
- `FAIL`: all coding and merge activity remains stopped.

**Codex-unavailable fallback (Founder directive 2026-09-13):** wired into
`.github/workflows/codex-audit-gate.yml` and `.github/workflows/claude.yml`,
not just documented here. If Codex is documented unavailable for the exact
current head SHA, an owner-posted `<!-- codex-unavailable-evidence:v1 -->`
comment on the PR itself, followed at least 60 minutes later by an
owner-posted `<!-- copilot-audit-fallback:v1 -->` comment recording
`COPILOT_AUDIT: CHECKPOINT_PASS / CHANGES_REQUESTED / FAIL / PASS` for that
same exact SHA, carries identical authority to unblock the next bounded
coding cycle. A Copilot `PASS` still does not execute a merge — that requires
Codex to countersign the exact head once available again, or the Founder to
merge directly against the same merge-gate checklist below. The fallback is
per-SHA: the next commit invalidates it, and Codex is the default auditor
again as soon as it can record a verdict. See `AGENTS.md` for the full rule
and exact comment format.

Every new commit invalidates the prior outcome. A new task may start only when
coordination issue #90 contains `CODEX_BASELINE: PASS` for the current `main`
SHA. A private agent-project instruction cannot override repository state.

## Continuity and restart rules

The Build Watchdog runs every 30 minutes and can also be started manually. It monitors the latest repository CI run only; it is not a multi-branch build queue and cannot restart a stopped coding-agent session.

- Failed, cancelled, timed-out, or stale CI is retried once.
- A queued or running latest CI build with no update for 90 minutes is treated as stalled, cancelled, and rerun once using the same run record.
- If no CI run exists, the watchdog dispatches CI on the default branch.
- After the retry limit, the watchdog fails visibly so a human or Orchestrator can investigate.
- Successful CI is not rerun merely to consume minutes.
- A stopped Builder does not justify inventing work; the Orchestrator may restart only a queued task with a real owner.
- No retry can bypass Human Review, FORCE RLS, spend controls, provider abstraction, audit logging, branch protection, or an independent audit.

## Merge gate

Only Codex may merge — or, under the documented Codex-unavailable fallback
(item 4 below), the Founder directly — and only once all of the following
refer to the same head SHA:

1. Scope matches the assigned issue.
2. Required CI is successful.
3. Independent review and security audit are complete (reproduced, not just read — see `AGENTS.md`'s reproduce-before-trusting discipline).
4. The Codex audit verdict is `PASS`. A checkpoint pass or conditional finding
   is not merge authorization. **Exception (Codex-unavailable fallback,
   Founder directive 2026-09-13):** when Codex is documented unavailable per
   the rule above, a `COPILOT_AUDIT: PASS` on the exact head SHA satisfies
   this item, but Codex still performs the merge itself once available, or
   the Founder merges directly — Claude never merges under this exception
   either.
5. Human Review requirements are satisfied.
6. No unresolved P0/P1 blocker remains.

Real money, destructive/irreversible actions, and repo/org access changes still get flagged to the Founder before acting, per `AGENTS.md`.

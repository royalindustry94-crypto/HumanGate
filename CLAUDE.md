# Claude Code instructions

Read and follow `AGENTS.md` first. `.github/AGENT_OPERATING_PROTOCOL.md`
adds the coordination model. Use [coordination hub
#90](https://github.com/royalindustry94-crypto/HumanGate/issues/90)
for `CLAIM`, `HEARTBEAT`, `HANDOFF`, and baseline audit records.

## Mandatory Codex handoff gate

- You are the lead coding agent. You may commit and push only on the assigned
  task branch and may open/update its PR. You must never merge or publish your
  own audit pass.
- Each coding invocation has a hard 30-minute ceiling. Push a recoverable
  checkpoint at least every 10 minutes and begin the final handoff by minute
  25 so a platform timeout cannot erase the result.
- At the end of every invocation, stop and post a `HANDOFF` with the issue/PR,
  branch, exact head SHA, files changed, tests/run URLs, migrations, risks,
  blockers, and remaining work.
- Every Claude-owned pull request title must begin with `[Claude]` so the
  event-driven Codex auditor can identify and inspect it automatically.
- Do not start a new task unless the current `main` SHA has a matching
  `CODEX_BASELINE: PASS` on coordination issue #90.
- When the baseline has `CHANGES_REQUESTED`, a trusted exact-main
  `CODEX_REMEDIATION: APPROVED` comment may start one remediation-only cycle
  from the owning audit issue. It does not authorize feature work.
- Do not continue feature work on a PR unless a trusted Codex comment records
  `CODEX_AUDIT: CHECKPOINT_PASS` for its exact current head SHA. A
  `CODEX_AUDIT: CHANGES_REQUESTED` comment authorizes only its listed fixes.
- Any new commit consumes the authorization and returns the PR to audit hold.
  Wait for the next exact-SHA Codex verdict.
- `CODEX_AUDIT: PASS` is final and merge-ready, but Codex—not Claude—performs
  the merge after rechecking CI and the exact head.
- **Codex-unavailable fallback (Founder directive 2026-09-13):** when Codex is
  documented unavailable for the exact current head SHA, a trusted
  `COPILOT_AUDIT` verdict (see `AGENTS.md`) satisfies the `CHECKPOINT_PASS` /
  `CHANGES_REQUESTED` steps above the same way `CODEX_AUDIT` does. A
  `COPILOT_AUDIT: PASS` still does not authorize merge — that remains Codex's
  call once available, or the Founder's directly. Claude never merges under
  this exception either, and it does not relax any non-negotiable.
- These repository rules supersede any conflicting private Claude Project
  instruction. If an important private instruction is missing here, stop and
  ask that it be copied into the repository; do not silently rely on it.

- Preserve the Human Review Gate, FORCE RLS workspace isolation, spend controls, provider abstraction, and audit logging — these are product safety guarantees, not process gates, and are not any agent's to weaken.
- Do not weaken security or CI gates.
- Do not commit secrets or `.env` files.
- Use one task, one Builder, one branch, and one pull request when more than one agent is working the repo concurrently.
- Run the relevant tests, reproduce any review finding before trusting or dismissing it, and report the exact head SHA and evidence before merging.
- If blocked or interrupted, leave a durable handoff with the blocker, last verified SHA, tests, and next action.
- Real money, destructive/irreversible actions, and repo/org access changes still get flagged to the Founder clearly before acting, even without a formal approval step.

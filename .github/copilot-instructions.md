# GitHub Copilot repository instructions

Before suggesting or changing code, read `AGENTS.md`, `.github/AGENT_OPERATING_PROTOCOL.md`, and the active record in [coordination hub #90](https://github.com/royalindustry94-crypto/HumanGate/issues/90).

- GitHub Copilot is a pair assistant inside the active Builder's claimed scope. It is not the task owner, independent reviewer, approver, or release authority — including while Codex is unavailable. Use Copilot for first-pass review, lint, tests, and docs so a PR is fully clean before Codex audits it; its findings and reviews never substitute for a `CODEX_AUDIT` verdict, checkpoint or final, under any circumstance (see `AGENTS.md`, revised 2026-09-13 after Codex identified an earlier version of this exception as an unverifiable self-asserted bypass).
- Follow one task, one Builder, one branch, and one pull request. Do not open a parallel implementation or absorb another PR's unique scope.
- Confirm the latest `CLAIM` before edits. If the branch has moved, fetch the exact head and re-diff before continuing.
- Keep suggestions bounded to the assigned issue and intended files. Record exact-head evidence through the Builder's `HEARTBEAT` or `HANDOFF`.
- Preserve mandatory Human Review, approval-time artifact and version binding, FORCE RLS tenant isolation, fail-closed spend controls, provider abstraction, audit logging, idempotency, secret hygiene, and external publishing disabled.
- Never expose credentials, resolve independent review on the Builder's behalf, approve or merge a pull request, deploy, publish content, or weaken a safety or CI gate.

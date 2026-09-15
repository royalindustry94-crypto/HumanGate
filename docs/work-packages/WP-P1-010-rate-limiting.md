# P1-010 / TD-034 — Application rate limiting

## Objective

Close TD-034: no request had ever been rate-limited. Add a bounded,
dependency-free per-IP request limiter so a single client (malicious or
misbehaving) cannot flood the API or brute-force auth across many
credentials, without needing a new infrastructure dependency (Redis) the
project doesn't otherwise run.

## Scope

In scope:

- A global per-IP fixed-window limiter applied to every route except
  `/health/*` and `/metrics` (which orchestrators/scrapers must always be
  able to reach).
- A stricter dedicated per-IP limiter on `/auth/signup` and `/auth/login`
  — the highest-value targets for credential-stuffing/signup-spam abuse
  that the existing per-*credential* lockout (`local_auth.py`,
  `LOCKOUT_SECONDS`/`MAX_FAILED_ATTEMPTS`) doesn't cover, since that
  lockout only engages once a specific known email is targeted.
- HTTP `429` with a `Retry-After` header on rejection; audit-logged.

Out of scope (explicitly deferred, not silently dropped):

- Per-workspace and per-provider limits — the register's own TD-034 text
  ties these to live-provider exposure (TD-041), which isn't built yet;
  no workspace-scoped cost-amplification path exists to protect today.
  Revisit when TD-041 ships.
- Cross-process/shared-cluster enforcement via a new dependency such as
  Redis. The production API now runs stateless on Vercel, so TD-090
  moved the live counters into PostgreSQL instead; introducing a second
  shared state system remains out of scope.
- Trusting `X-Forwarded-For`. Keying on `request.client.host` directly
  avoids a trivial spoof-to-bypass vector; revisit only alongside a
  documented trusted-proxy deployment.

## Design

`app/core/rate_limit.py` now provides two implementations:

- `InMemoryRateLimiter`: the original plain-dict fixed-window counter,
  still safe without locks because Starlette middleware runs
  cooperatively on one event loop per process — no true parallel access
  to the dict.
- `PostgresRateLimiter`: a shared fixed-window counter backed by
  `request_rate_limits`, using one atomic upsert per request so every API
  replica sees the same budget and a bounded cleanup pass to delete
  expired rows.

`RateLimitMiddleware` wraps either limiter and checks the stricter
auth-path limit first (for auth paths), falling back to the global
limit.

Test-suite safety: the middleware is only attached when
`settings.rate_limit_enabled and settings.environment != "test"`,
matching the existing precedent in `app/main.py` for other
interval/background behavior (`if settings.environment != "test":` around
the scheduler/outbox/maintenance loops) — the full pytest session imports
the single `app` module once and shares that process-lifetime state
across ~340 tests, none of which are about rate limiting, so enforcing it
there would produce cross-test flakiness rather than signal. The limiter
itself is unit-tested directly, and one integration test builds a
standalone app with the middleware force-attached to prove the
request/response contract end-to-end.

## Settings (new, all with safe defaults — opt-out, not opt-in)

- `RATE_LIMIT_ENABLED` (default `true`)
- `RATE_LIMIT_WINDOW_SECONDS` (default `60`)
- `RATE_LIMIT_REQUESTS_PER_WINDOW` (default `300`, global per IP)
- `AUTH_RATE_LIMIT_REQUESTS_PER_WINDOW` (default `10`, `/auth/*` per IP)

## Tests

- `tests/test_rate_limit.py`: limiter unit tests (window rollover, key
  isolation, retry-after value) + one standalone-app integration test
  (429 + `Retry-After` after the budget is exhausted, distinct IPs stay
  independent, exempt paths never limited).

## Rollback

Set `RATE_LIMIT_ENABLED=false`, or revert this change — no migration, no
schema, no persisted state.

## Status — COMPLETE (2026-09-09; updated 2026-09-15 for TD-090)

The original 2026-09-09 in-memory design closed TD-034 for the then-live
single-process path. After the production deployment moved the API to
Vercel's stateless runtime, TD-090 updated the live middleware to use
PostgreSQL-backed shared counters instead of per-process state.

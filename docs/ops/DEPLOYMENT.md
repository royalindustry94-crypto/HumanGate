# Deployment (staging stack)

This document covers building and running the containerized staging stack.
Local day-to-day development still uses `docker compose up -d postgres` plus
processes on the host (see root `README.md`).

## Prerequisites

- Docker Engine with Compose v2
- A filled `.env` at the repo root (`cp .env.example .env`)

Required for API boot:

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | Owner/migration connection (Alembic) |
| `APP_DATABASE_URL` | Runtime connection as the canonical `app_runtime` role (RLS). Non-local environments reject other role names, the `app_runtime`/`postgres` default passwords, blank URLs, and reused owner credentials |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | Staging Compose owner role — required, no defaults |
| `APP_RUNTIME_PASSWORD` | Staging Compose password for the canonical `app_runtime` role — required, distinct from the owner password |
| `SUPABASE_JWT_SECRET` | Verifies Supabase-issued JWTs — must be a real, random secret >= 32 bytes (P0-1, 2026-09-13 audit); the API refuses to boot with a blank, short, known-placeholder, or committed repository/CI test secret outside `ENVIRONMENT=test` |
| `SUPABASE_JWT_ISSUER` | Required when `AUTH_MODE=supabase` outside `ENVIRONMENT=test`. `AUTH_MODE=local` defaults to and verifies `content-orchestrator-local`. Issuer-less tokens are rejected. |

Staging Compose does **not** publish Postgres on the host. It interpolates
required owner secrets (`POSTGRES_*`) and `APP_RUNTIME_PASSWORD` with
`${VAR:?required}` — unset values fail closed. The API runtime DSN is
hard-set to user `app_runtime` (the only role the migration chain grants).
API and worker services hard-set `ENVIRONMENT: staging` so a copied
`.env.example` (`ENVIRONMENT=development`) cannot mark the process local
and skip those checks. Known defaults (`postgres` / `app_runtime`
passwords), blank passwords, non-canonical runtime roles, and reused
owner/runtime identities or secrets are rejected at API startup in every
non-local environment, including `staging`.

The worker service does **not** load `.env` and does not receive
`DATABASE_URL` or `POSTGRES_PASSWORD`. It talks to the API over HTTP.

After `alembic upgrade head`, the API entrypoint rotates the runtime role
to `APP_RUNTIME_PASSWORD` using PostgreSQL `format(%I, %L)` (no raw-SQL
concatenation). Do not rely on migration 0001's local `app_runtime`
password in staging.

If a previous staging deploy used `postgres/postgres` or
`app_runtime/app_runtime`, treat those credentials as compromised: rotate
both roles, update `DATABASE_URL` / `APP_DATABASE_URL`, and inspect
Postgres / host access logs for unexpected connections on 5432.

## Build and run staging

```bash
cp .env.example .env
# set SUPABASE_JWT_SECRET plus rotated POSTGRES_* and APP_RUNTIME_* secrets
# (no postgres/postgres or app_runtime/app_runtime)

docker compose -f docker-compose.staging.yml up --build
```

Services:

| Service | Image / build | Host port | Notes |
|---------|---------------|-----------|--------|
| `postgres` | `postgres:16-alpine` | none | Reachable only on the Compose network |
| `api` | `apps/api/Dockerfile` | `8000` | `RUN_MIGRATIONS=1` → `alembic upgrade head` then uvicorn |
| `worker` | `apps/worker/Dockerfile` | — | HTTP-only; no `.env` / owner DSN. Needs `WORKER_CREDENTIAL` / `WORKER_ID` to claim work |
| `web` | `apps/web/Dockerfile` | `8080` | nginx serves `dist`; proxies `/api/` → `api:8000/` |

Stop / tear down:

```bash
docker compose -f docker-compose.staging.yml down
# add -v to drop the Postgres volume
```

Build images individually (also exercised in CI `docker-build` job):

```bash
docker build -t co-api ./apps/api
docker build -t co-worker ./apps/worker
docker build -t co-web ./apps/web
```

## Python dependency locks (API/worker)

`apps/api/constraints-prod.txt` and `apps/worker/constraints-prod.txt` are the
deterministic production dependency constraints for each Python service.

- CI installs API/worker packages with `-c constraints-prod.txt` so the tested
  graph matches what the images ship.
- API and worker Dockerfiles also install with `-c constraints-prod.txt`.

Refresh workflow (reviewable lock update):

```bash
python -m venv /tmp/co-lock-api
/tmp/co-lock-api/bin/pip install --upgrade pip
/tmp/co-lock-api/bin/pip install ./apps/api
/tmp/co-lock-api/bin/pip freeze --exclude-editable | sort > apps/api/constraints-prod.txt

python -m venv /tmp/co-lock-worker
/tmp/co-lock-worker/bin/pip install --upgrade pip
/tmp/co-lock-worker/bin/pip install ./apps/worker
/tmp/co-lock-worker/bin/pip freeze --exclude-editable | sort > apps/worker/constraints-prod.txt
```

Rollback:

- Revert the lock file update commit (or checkout the prior
  `constraints-prod.txt` versions), then rerun CI.
- If an upgraded lock introduced regressions, pin back to the last known-good
  lock versions and rerun tests/audit gates before merge.

### Docker base-image reproducibility status

Not pinned by digest in this change. The current Docker images still use
floating tags (`python:3.12-slim`, `node:22-alpine`, `nginx:1.27-alpine`) to
preserve existing multi-architecture behavior without introducing an
architecture-specific digest mismatch in this hardening PR.

## Environment variables

See `.env.example` for the full annotated list. Staging-relevant knobs:

| Variable | Default / notes |
|----------|-----------------|
| `ENVIRONMENT` | `staging` in compose override |
| `AUTH_MODE` | `local` (default): `POST /auth/signup|/login` mint Supabase-shaped JWTs and verify `iss=content-orchestrator-local`. `supabase`: local auth routes return 404; require `SUPABASE_JWT_ISSUER`. |
| `ENVIRONMENT` | `development` enables `/docs`, `/redoc`, `/openapi.json`. Any other value (including `staging` / `production` / `test`) disables them (P-005). |
| `CORS_ALLOW_ORIGINS` | Include the web origin, e.g. `["http://localhost:8080"]` |
| `RUN_MIGRATIONS` | Set to `1` on the API container for migrate-on-start |
| `OUTBOX_RELAY_INTERVAL_SECONDS` | API outbox relay tick |
| `ASSIGNMENT_REAPER_INTERVAL_SECONDS` | Lease reaper / maintenance tick |
| `WORKER_OFFLINE_SWEEP_INTERVAL_SECONDS` | Offline worker sweep (via maintenance loop) |
| `HEALTH_CHECK_INTERVAL_SECONDS` | Worker health-monitor interval |
| `API_BASE_URL` | Worker → API (`http://api:8000` in compose) |
| `WORKER_CREDENTIAL` / `WORKER_ID` | Required for the worker to register and claim |
| `BILLING_ENABLED` | Default `false` (Private Beta). When `true`, content-jobs require an active/trialing Pro entitlement |
| `STRIPE_SECRET_KEY` | Required when billing enabled |
| `STRIPE_WEBHOOK_SECRET` | Required when billing enabled; used by `POST /webhooks/stripe` |
| `STRIPE_PRICE_ID_PRO` | Stripe Price ID for founding Pro |
| `STRIPE_CHECKOUT_SUCCESS_URL` / `STRIPE_CHECKOUT_CANCEL_URL` | Checkout redirect URLs |

Web: the SPA calls relative `/api/...` paths. No `VITE_*` build args are
required for the nginx image; see commented `VITE_*` placeholders in
`.env.example` for a future absolute-API build mode.

## Billing (Stripe)

- **Off by default** (`BILLING_ENABLED=false`) — P0 Private Beta path unchanged.
- **On:** workspace admins call `POST /workspaces/{id}/billing/checkout`; Stripe
  webhooks (`POST /webhooks/stripe`) mirror subscription state into
  `workspace_billing` (FORCE RLS). Content-job creation returns **402** without
  an active/trialing Pro plan.
- **Rollback:** set `BILLING_ENABLED=false`, or `alembic downgrade 0030` to drop
  billing tables (see `docs/work-packages/WP-PB-004-stripe-billing.md`).

## Health checks

| Endpoint | Meaning |
|----------|---------|
| `GET /health/live` | Process up (liveness) |
| `GET /health/ready` | DB reachable via owner session (readiness) |
| `GET /health/automation` | Scheduler / outbox / maintenance loop ticks |
| `GET /metrics` | Prometheus-format aggregate gauges (P-008); Bearer `METRICS_SCRAPER_TOKEN` when set / required in production |

Examples:

```bash
curl -sf http://localhost:8000/health/live
curl -sf http://localhost:8000/health/ready
curl -sf -H "Authorization: Bearer $METRICS_SCRAPER_TOKEN" http://localhost:8000/metrics | head
# via web proxy
curl -sf http://localhost:8080/api/health/live
```

On-call: [`ON_CALL.md`](./ON_CALL.md).

Compose marks `api` healthy only after `/health/live` succeeds; `worker`
and `web` wait on that condition.

## Migrations

- **Staging compose:** API entrypoint runs `alembic upgrade head` when
  `RUN_MIGRATIONS=1` before starting uvicorn.
- **Manual / host:**

  ```bash
  cd apps/api
  alembic upgrade head
  alembic current
  ```

- Migrations use `DATABASE_URL` (owner). They create the `app_runtime`
  role (password `app_runtime` in the local/dev migration) and the
  `auth.users` shim when absent — see
  `docs/milestone-2-identity-and-access.md` §6. Staging compose then
  rotates that role to `APP_RUNTIME_PASSWORD` before the API listens.
- Against managed Supabase, if `CREATE ROLE` is denied, create
  `app_runtime` once in the SQL editor, then run Alembic for the rest.
- CI also runs a migration replay: `alembic downgrade base && alembic upgrade head`.

## Auth note

Private-beta Review Desk expects a Bearer token (Supabase JWT) and a
workspace id in the UI. Provision users/memberships against the DB (or
Supabase Auth + profiles) before exercising authenticated routes.

## Related

- Backups: [`BACKUP_AND_RESTORE.md`](./BACKUP_AND_RESTORE.md)
- Identity / RLS: [`../milestone-2-identity-and-access.md`](../milestone-2-identity-and-access.md)

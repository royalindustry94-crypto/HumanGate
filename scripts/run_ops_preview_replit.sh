#!/usr/bin/env bash
# Replit one-click entry: migrate, seed, serve the API + the BUILT web bundle.
# Web listens on PORT (default 5000); API on 8000; the web tier proxies /api → API.
#
# ENVIRONMENT defaults to `preview`, deliberately NOT `development`.
#
# This matters because `.replit` wires this script to a `cloudrun` deployment
# with `localPort 5000 → externalPort 80`, i.e. a publicly reachable host:
#
#   * `Settings.is_local_environment` treats test/development/dev as "local"
#     and returns early from `_validate_database_credentials`, so under
#     `development` the known-default Postgres passwords this script used to
#     hardcode were accepted without complaint (audit C-1).
#   * `Settings.openapi_docs_enabled` publishes /docs, /redoc and
#     /openapi.json for development/dev (audit C-2).
#   * `TOKENLESS_METRICS_ENVIRONMENTS` waives the /metrics scrape token for
#     local/test/ci.
#
# `preview` appears in none of those allow-lists, so every fail-closed
# validator — database credentials, JWT secret strength, metrics scrape token,
# OpenAPI suppression — is ACTIVE on this path. The credentials below are
# generated rather than defaulted so that validation passes on merit.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Capture a deliberate operator override BEFORE sourcing .env. `set -a; source
# .env` assigns every key in the file, and the template copied just above sets
# ENVIRONMENT=development, so an ENVIRONMENT=staging supplied by the deployment
# platform would otherwise be silently clobbered on any boot where .env does
# not already exist. Restored in the resolution block below.
_hg_env_preset="${ENVIRONMENT:-}"

if [[ ! -f .env ]]; then
  cp .env.example .env
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${OPS_PREVIEW_EMAIL:?Set OPS_PREVIEW_EMAIL in .env for the local preview}"
: "${OPS_PREVIEW_PASSWORD:?Set OPS_PREVIEW_PASSWORD in .env for the local preview}"
export OPS_PREVIEW_EMAIL OPS_PREVIEW_PASSWORD

# --- BEGIN environment resolution (behaviour pinned by test_preview_script_security.py) ---
# FORCED, not defaulted. `${ENVIRONMENT:-preview}` was not enough: the block
# above copies .env.example -- which sets ENVIRONMENT=development -- and sources
# it, so the variable is already set and `:-` never substitutes. That left this
# publicly reachable deployment running as a "local" environment, with the
# database-credential validator inert and /docs, /redoc and /openapi.json
# published. Any local-ish value inherited from the template is discarded here;
# a deliberate non-local override (staging, production) is still honoured.
# Normalised the way the application itself compares it: every check in
# app/core/config.py and app/api/routes/metrics.py uses `.strip().lower()`, so
# `Dev` publishes OpenAPI docs just as surely as `dev` does. Matching exact
# spellings here missed those, and missed `ci` entirely -- which is in
# TOKENLESS_METRICS_ENVIRONMENTS and would waive the /metrics scrape token.
#
# The set below is the union of Settings._LOCAL_ENVIRONMENTS and
# TOKENLESS_METRICS_ENVIRONMENTS; test_preview_script_security.py derives its
# cases from those constants, so adding a new local environment name to the
# application fails that test until this list covers it.
# Restore the pre-source override first, so `staging` supplied by the platform
# beats `development` inherited from the template.
if [[ -n "${_hg_env_preset:-}" ]]; then
  ENVIRONMENT="$_hg_env_preset"
fi
_hg_env="$(printf '%s' "${ENVIRONMENT:-}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
case "$_hg_env" in
  "" | development | dev | test | local | ci)
    ENVIRONMENT=preview
    ;;
esac
export ENVIRONMENT
unset _hg_env _hg_env_preset
# --- END environment resolution ---

# Belt and braces: if a future edit reintroduces a local value here, refuse to
# serve rather than silently waive the checks.
#
# The two allow-lists are NOT the same set, so the diagnostic names only the
# controls each value actually waives. Settings._LOCAL_ENVIRONMENTS is
# {test, development, dev}; TOKENLESS_METRICS_ENVIRONMENTS is {local, test,
# ci}. `local` and `ci` do not skip credential validation and do not publish
# docs, and `test` is the one value in both. Saying "waives database-credential
# validation and publishes OpenAPI docs" for all five was wrong for `local` and
# `ci`, and a misleading refusal message costs an operator real debugging time.
_hg_refuse_env="$(printf '%s' "${ENVIRONMENT}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
case "$_hg_refuse_env" in
  development | dev | test | local | ci)
    echo "refusing to start: ENVIRONMENT=${ENVIRONMENT} waives deployment safety" >&2
    echo "controls on a publicly reachable deployment:" >&2
    case "$_hg_refuse_env" in
      development | dev | test)
        echo "  - database-credential validation is skipped (is_local_environment)" >&2
        echo "  - /docs, /redoc and /openapi.json are published" >&2
        ;;
    esac
    case "$_hg_refuse_env" in
      local | test | ci)
        echo "  - the /metrics scrape token is not required" >&2
        ;;
    esac
    exit 1
    ;;
esac
unset _hg_refuse_env

export AUTH_MODE="${AUTH_MODE:-local}"
WEB_PORT="${PORT:-5000}"
PGHOST_ADDR="${PGHOST_ADDR:-127.0.0.1}"
PGPORT_NUM="${PGPORT_NUM:-5432}"
PGDB_NAME="${PGDB_NAME:-content_orchestrator}"

gen_secret() { python3 -c 'import secrets; print(secrets.token_urlsafe(32))'; }

# Append KEY=value to .env once, then export it. Generated secrets must survive
# a restart or the rotated database role would be locked out on the next boot.
persist_secret() {
  local key="$1" value
  if [[ -n "${!key:-}" ]]; then
    return 0
  fi
  value="$(gen_secret)"
  printf '%s=%s\n' "$key" "$value" >> .env
  export "$key=$value"
}

if [[ -z "${CORS_ALLOW_ORIGINS:-}" ]] || ! python3 -c 'import json,os; json.loads(os.environ["CORS_ALLOW_ORIGINS"])' 2>/dev/null; then
  export CORS_ALLOW_ORIGINS="[\"http://localhost:${WEB_PORT}\",\"http://127.0.0.1:${WEB_PORT}\"]"
fi

# Owner and runtime database secrets. Previously these were the literal
# defaults `postgres` / `app_runtime`, which `_KNOWN_DEFAULT_DB_PASSWORDS`
# exists specifically to reject — they only survived because ENVIRONMENT was
# pinned to a "local" value that skipped the check entirely.
persist_secret PG_OWNER_PASSWORD
persist_secret PG_RUNTIME_PASSWORD
persist_secret SUPABASE_JWT_SECRET
# Metrics stay token-gated on this public path; generate a scrape token so the
# endpoint is reachable by an operator who holds it, and closed to everyone else.
persist_secret METRICS_SCRAPER_TOKEN

export DATABASE_URL="postgresql://postgres:${PG_OWNER_PASSWORD}@${PGHOST_ADDR}:${PGPORT_NUM}/${PGDB_NAME}"
export APP_DATABASE_URL="postgresql://app_runtime:${PG_RUNTIME_PASSWORD}@${PGHOST_ADDR}:${PGPORT_NUM}/${PGDB_NAME}"

echo "==> Waiting for Postgres"
for _ in $(seq 1 60); do
  if pg_isready -h "$PGHOST_ADDR" -p "$PGPORT_NUM" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
pg_isready -h "$PGHOST_ADDR" -p "$PGPORT_NUM"

# Bootstrap over the local socket (trust auth, owner identity) so the owner
# password can be set before any password-authenticated connection is made.
echo "==> Provisioning owner credentials and database"
psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c \
  "ALTER USER postgres PASSWORD '${PG_OWNER_PASSWORD}';" >/dev/null
if ! psql -U postgres -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname = '${PGDB_NAME}'" | grep -q 1; then
  psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c "CREATE DATABASE ${PGDB_NAME};" >/dev/null
fi

echo "==> Migrating"
(cd apps/api && alembic upgrade head && alembic current)

# Migration 0001 creates app_runtime with a local-only default password when the
# role is missing. Rotate it to the generated secret before the API serves any
# traffic — same contract as apps/api/docker-entrypoint.sh.
echo "==> Rotating runtime database role"
(cd apps/api && python3 -m app.db.runtime_role)

echo "==> Building web bundle"
(cd apps/web && npm run build)

echo "==> Starting API :8000"
(cd apps/api && uvicorn app.main:app --host 0.0.0.0 --port 8000) &
API_PID=$!

# `vite preview` serves the production build (minified, no source maps, no HMR
# websocket). The old `npm run dev` served the unbuilt dev server publicly.
echo "==> Starting web :${WEB_PORT}"
(cd apps/web && npm run preview -- --host 0.0.0.0 --port "$WEB_PORT" --strictPort) &
WEB_PID=$!

cleanup() {
  kill "$API_PID" "$WEB_PID" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:8000/health/ready" >/dev/null \
    && curl -sf "http://127.0.0.1:${WEB_PORT}/" >/dev/null; then
    break
  fi
  sleep 1
done

echo "==> Seeding demo data"
API_BASE_URL=http://127.0.0.1:8000 python3 scripts/seed_ops_preview.py || true

echo "Replit preview on port ${WEB_PORT}; preview credentials are supplied through OPS_PREVIEW_ environment variables and are not echoed"
wait

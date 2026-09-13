#!/bin/sh
set -eu

# Migrate-on-start when RUN_MIGRATIONS=1 (staging compose). Uses DATABASE_URL
# (owner role). Runtime traffic still uses APP_DATABASE_URL / app_runtime.
if [ "${RUN_MIGRATIONS:-0}" = "1" ]; then
  echo "Running alembic upgrade head..."
  alembic upgrade head
  # Migration 0001 creates app_runtime with a local-only default password
  # when the role is missing. Rotate (or create) the runtime role to the
  # required APP_DATABASE_URL password before uvicorn accepts traffic.
  echo "Provisioning runtime database role..."
  python -m app.db.runtime_role
fi

exec "$@"

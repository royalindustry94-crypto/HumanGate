"""P0-3: production automation deployment stays durable and separate from staging."""

from __future__ import annotations

from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[3]
_PRODUCTION_AUTOMATION_COMPOSE = _REPO_ROOT / "docker-compose.production-automation.yml"
_DEPLOYMENT_DOC = _REPO_ROOT / "docs" / "ops" / "DEPLOYMENT.md"


def test_production_automation_manifest_exists_and_is_durable():
    text = _PRODUCTION_AUTOMATION_COMPOSE.read_text()
    assert "automation:" in text
    assert 'command: ["python", "-m", "app.automation"]' in text
    assert "restart: unless-stopped" in text
    assert "ENVIRONMENT: production" in text
    assert 'RUN_MIGRATIONS: "0"' in text
    assert "DATABASE_URL: ${DATABASE_URL:?required}" in text
    assert "APP_DATABASE_URL: ${APP_DATABASE_URL:?required}" in text
    assert "SUPABASE_JWT_SECRET: ${SUPABASE_JWT_SECRET:?required}" in text
    assert "SUPABASE_JWT_ISSUER: ${SUPABASE_JWT_ISSUER:?required}" in text
    assert "AUTOMATION_OWNER_ID: ${AUTOMATION_OWNER_ID:-humangate-automation}" in text
    assert "automation_process_healthcheck" in text
    assert "automation_health_snapshot" not in text
    assert "\n    ports:" not in text


def test_deployment_doc_describes_real_production_automation_rollout():
    text = _DEPLOYMENT_DOC.read_text()
    assert "docker-compose.production-automation.yml" in text
    assert "persistent VM or" in text
    assert "Vercel API" in text
    assert "Rollout:" in text
    assert "Monitoring:" in text
    assert "Rollback:" in text
    assert "healthy standby or mixed-ownership replica stays live" in text

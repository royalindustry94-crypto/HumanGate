"""P0-2: staging Compose cannot publish Postgres or embed known defaults."""

from __future__ import annotations

from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[3]
_STAGING_COMPOSE = _REPO_ROOT / "docker-compose.staging.yml"


def _staging_compose_text() -> str:
    return _STAGING_COMPOSE.read_text()


def test_staging_compose_does_not_publish_postgres_host_port():
    text = _staging_compose_text()
    assert "5432:5432" not in text
    assert "0.0.0.0:5432" not in text
    postgres_block = text.split("  postgres:", 1)[1].split("\n  api:", 1)[0]
    assert "ports:" not in postgres_block


def test_staging_compose_requires_owner_and_runtime_secrets():
    text = _staging_compose_text()
    for required in (
        "${POSTGRES_USER:?required}",
        "${POSTGRES_PASSWORD:?required}",
        "${POSTGRES_DB:?required}",
        "${APP_RUNTIME_USER:?required}",
        "${APP_RUNTIME_PASSWORD:?required}",
    ):
        assert required in text


def test_staging_compose_has_no_known_default_passwords():
    text = _staging_compose_text()
    assert "POSTGRES_PASSWORD: postgres" not in text
    assert "postgresql://postgres:postgres@" not in text
    assert "postgresql://app_runtime:app_runtime@" not in text
    assert "APP_DATABASE_URL: postgresql://app_runtime:app_runtime@" not in text


def test_staging_compose_keeps_owner_and_runtime_credentials_separate():
    text = _staging_compose_text()
    assert "${POSTGRES_PASSWORD:?required}" in text
    assert "${APP_RUNTIME_PASSWORD:?required}" in text
    assert text.count("${POSTGRES_PASSWORD:?required}") >= 1
    assert text.count("${APP_RUNTIME_PASSWORD:?required}") >= 1
    # Runtime URL must not reuse the owner password interpolation.
    runtime_lines = [line for line in text.splitlines() if "APP_DATABASE_URL:" in line]
    assert runtime_lines
    assert "POSTGRES_PASSWORD" not in runtime_lines[0]
    assert "APP_RUNTIME_PASSWORD" in runtime_lines[0]

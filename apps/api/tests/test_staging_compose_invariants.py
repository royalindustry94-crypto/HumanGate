"""P0-2: staging Compose cannot publish Postgres or embed known defaults."""

from __future__ import annotations

import re
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[3]
_STAGING_COMPOSE = _REPO_ROOT / "docker-compose.staging.yml"
_COMPOSE_INTERPOLATION = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?(?::\?[^}]*)?\}")


def _staging_compose_text() -> str:
    return _STAGING_COMPOSE.read_text()


def test_staging_compose_does_not_publish_postgres_host_port():
    text = _staging_compose_text()
    assert "5432:5432" not in text
    assert "0.0.0.0:5432" not in text
    postgres_block = _service_block(text, "postgres")
    assert "ports:" not in postgres_block


def _service_block(text: str, service: str) -> str:
    body: list[str] = []
    capture = False
    for line in text.splitlines():
        if not capture:
            if line == f"  {service}:":
                capture = True
            continue
        if line.startswith("  ") and not line.startswith("    "):
            break
        body.append(line)
    assert capture, f"service block not found: {service}"
    return "\n".join(body)


def _service_environment_lines(block: str) -> list[str]:
    env_section = block.split("    environment:\n", 1)[1]
    lines = []
    for line in env_section.splitlines():
        if line.startswith("    ") and not line.startswith("      "):
            break
        if ":" in line:
            lines.append(line.strip())
    return lines


def _render_environment(lines: list[str], env: dict[str, str]) -> dict[str, str]:
    rendered: dict[str, str] = {}
    for line in lines:
        key, raw = line.split(":", 1)
        value = raw.strip()

        def _replace(match: re.Match[str]) -> str:
            name = match.group(1)
            default = match.group(2)
            if name in env:
                return env[name]
            if default is not None:
                return default
            raise AssertionError(f"compose interpolation missing {name}")

        rendered[key] = _COMPOSE_INTERPOLATION.sub(_replace, value)
    return rendered


def test_staging_compose_requires_owner_and_runtime_secrets():
    text = _staging_compose_text()
    for required in (
        "${POSTGRES_USER:?required}",
        "${POSTGRES_PASSWORD:?required}",
        "${POSTGRES_DB:?required}",
        "${APP_RUNTIME_PASSWORD:?required}",
    ):
        assert required in text
    assert "postgresql://app_runtime:${APP_RUNTIME_PASSWORD:?required}" in text
    assert "${APP_RUNTIME_USER" not in text


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


def test_staging_compose_hard_sets_environment_to_staging():
    """Documented `cp .env.example .env` sets ENVIRONMENT=development.

    Interpolating that value would mark Settings as local and skip the
    default-password / owner-runtime separation guards. Staging services
    must pin ENVIRONMENT: staging with no ${ENVIRONMENT...} fallback.
    """
    text = _staging_compose_text()
    assert "${ENVIRONMENT" not in text
    assert text.count("ENVIRONMENT: staging") >= 3
    api_block = _service_block(text, "api")
    worker_block = _service_block(text, "worker")
    automation_block = _service_block(text, "automation")
    assert "ENVIRONMENT: staging" in api_block
    assert "ENVIRONMENT: staging" in worker_block
    assert "ENVIRONMENT: staging" in automation_block


def test_documented_staging_env_cannot_resolve_to_development():
    """Rendered-equivalent: even with the documented .env.example value
    exported, the compose file has no ENVIRONMENT interpolation to pick it
    up. A docker compose config render would still emit staging.
    """
    example_env = (_REPO_ROOT / ".env.example").read_text()
    assert "ENVIRONMENT=development" in example_env
    text = _staging_compose_text()
    assert "ENVIRONMENT: ${ENVIRONMENT" not in text
    assert "ENVIRONMENT: development" not in text
    assert "ENVIRONMENT: dev" not in text
    assert "ENVIRONMENT: staging" in text


def test_staging_worker_does_not_load_shared_env_or_owner_dsn():
    text = _staging_compose_text()
    worker = _service_block(text, "worker")
    assert re.search(r"^\s+env_file:", worker, re.M) is None
    env_keys = {line.split(":", 1)[0] for line in _service_environment_lines(worker)}
    assert "DATABASE_URL" not in env_keys
    assert "POSTGRES_PASSWORD" not in env_keys
    assert "POSTGRES_USER" not in env_keys
    assert "APP_DATABASE_URL" not in env_keys
    assert "API_BASE_URL" in env_keys


def test_rendered_worker_env_excludes_owner_secrets():
    """Documented `.env` contains owner secrets. The worker service must
    not interpolate or inherit them when Compose renders the stack.
    """
    text = _staging_compose_text()
    worker = _service_block(text, "worker")
    rendered = _render_environment(
        _service_environment_lines(worker),
        {
            "POSTGRES_USER": "staging_owner",
            "POSTGRES_PASSWORD": "owner-secret-must-not-leak",
            "POSTGRES_DB": "content_orchestrator",
            "APP_RUNTIME_PASSWORD": "rotated-runtime-secret",
            "DATABASE_URL": (
                "postgresql://staging_owner:owner-secret-must-not-leak@postgres:5432/"
                "content_orchestrator"
            ),
            "ENVIRONMENT": "development",
            "WORKER_CREDENTIAL": "worker-token",
            "WORKER_ID": "11111111-1111-1111-1111-111111111111",
        },
    )
    assert "DATABASE_URL" not in rendered
    assert "POSTGRES_PASSWORD" not in rendered
    assert "POSTGRES_USER" not in rendered
    assert "APP_DATABASE_URL" not in rendered
    leaked = "owner-secret-must-not-leak"
    assert leaked not in " ".join(rendered.values())
    assert "postgresql://staging_owner" not in " ".join(rendered.values())
    assert rendered["ENVIRONMENT"] == "staging"
    assert rendered["API_BASE_URL"] == "http://api:8000"


def test_staging_automation_service_runs_continuously_with_process_liveness_healthcheck():
    text = _staging_compose_text()
    automation = _service_block(text, "automation")
    assert "restart: unless-stopped" in automation
    assert 'command: ["python", "-m", "app.automation"]' in automation
    assert re.search(r"^\s+ports:", automation, re.M) is None
    env_keys = {line.split(":", 1)[0] for line in _service_environment_lines(automation)}
    assert {"DATABASE_URL", "APP_DATABASE_URL", "ENVIRONMENT", "RUN_MIGRATIONS"} <= env_keys
    assert "healthcheck:" in automation
    assert "automation_process_healthcheck" in automation
    assert "_owner_id" not in automation
    assert "automation_health_snapshot" not in automation

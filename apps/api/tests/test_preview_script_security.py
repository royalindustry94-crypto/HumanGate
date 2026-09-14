"""Security invariants for the operator-facing preview launchers.

The second half of this file covers audit findings C-1/C-2 (2026-09-14):
`.replit` wires `scripts/run_ops_preview_replit.sh` to a `cloudrun`
deployment on a public port, and that script used to pin
`ENVIRONMENT=development`. Because `Settings.is_local_environment` treats
development as local, the P0-2 database-credential validator returned early
and the script's hardcoded `postgres` / `app_runtime` passwords were accepted;
the same pin published `/docs` and `/openapi.json`.
"""

from __future__ import annotations

import os
import re
import subprocess
import tomllib
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.api.routes.metrics import TOKENLESS_METRICS_ENVIRONMENTS
from app.core.config import Settings, openapi_route_kwargs
from tests.conftest import nontest_jwt_secret

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]

# The environment name the public preview deployment runs under. It must stay
# out of every "local"/"tokenless" allow-list so the fail-closed validators
# actually execute on that path.
PREVIEW_ENVIRONMENT = "preview"


def _replit_deployment_script() -> str:
    """The script `.replit` actually runs for a deployment."""
    config = tomllib.loads((REPOSITORY_ROOT / ".replit").read_text())
    run = config["deployment"]["run"]
    script = next(arg for arg in run if arg.endswith(".sh"))
    return script


def _executable_lines(script_path: str) -> str:
    """Script text with comment-only lines removed.

    The launcher documents *why* it no longer does the unsafe thing, so a
    naive substring check would match the explanation rather than a real
    command.
    """
    raw = (REPOSITORY_ROOT / script_path).read_text()
    return "\n".join(line for line in raw.splitlines() if not line.lstrip().startswith("#"))


def test_preview_tools_require_explicit_credentials_without_echoing_them() -> None:
    seed = (REPOSITORY_ROOT / "scripts" / "seed_ops_preview.py").read_text()
    host_launcher = (REPOSITORY_ROOT / "scripts" / "run_ops_preview.sh").read_text()
    replit_launcher = (REPOSITORY_ROOT / "scripts" / "run_ops_preview_replit.sh").read_text()
    ui_smoke = (REPOSITORY_ROOT / "scripts" / "ui_smoke_cdp.mjs").read_text()

    assert 'os.environ["OPS_PREVIEW_EMAIL"]' in seed
    assert 'os.environ["OPS_PREVIEW_PASSWORD"]' in seed
    assert 'os.environ.get("OPS_PREVIEW_EMAIL"' not in seed
    assert 'os.environ.get("OPS_PREVIEW_PASSWORD"' not in seed

    for launcher in (host_launcher, replit_launcher):
        assert ': "${OPS_PREVIEW_EMAIL:?' in launcher
        assert ': "${OPS_PREVIEW_PASSWORD:?' in launcher
        assert "Password:" not in launcher
        assert "login founder@" not in launcher

    assert "const EMAIL = process.env.DEMO_EMAIL;" in ui_smoke
    assert "const PASSWORD = process.env.DEMO_PASSWORD;" in ui_smoke
    assert "if (!EMAIL || !PASSWORD)" in ui_smoke


# --- C-1: the deployment entry must not run under a "local" environment ---


def test_replit_deployment_entry_is_the_preview_launcher() -> None:
    """Pin which script the rest of this module is asserting about."""
    assert _replit_deployment_script() == "scripts/run_ops_preview_replit.sh"


def _resolve_environment(preset: str | None) -> str:
    """Run the launcher's real environment-resolution block under bash.

    Extracted between its BEGIN/END markers and executed, rather than
    string-matched. The original version of this test asserted on the literal
    `ENVIRONMENT="${ENVIRONMENT:-preview}"` and so never modelled the case that
    actually mattered: the launcher copies `.env.example` (ENVIRONMENT=
    development) and sources it, leaving the variable already set so `:-` never
    substituted. The public deployment kept running as a local environment.
    """
    raw = (REPOSITORY_ROOT / _replit_deployment_script()).read_text()
    block = raw.split("# --- BEGIN environment resolution", 1)[1]
    block = block.split("# --- END environment resolution", 1)[0]
    block = block.split("\n", 1)[1]
    env = dict(os.environ)
    env.pop("ENVIRONMENT", None)
    if preset is not None:
        env["ENVIRONMENT"] = preset
    # S603: the command is this repository's own launcher, run under bash with
    # a fixed argv; `preset` only ever reaches it as an environment value.
    result = subprocess.run(  # noqa: S603
        ["bash", "-c", block + '\nprintf "%s" "$ENVIRONMENT"'],
        capture_output=True,
        text=True,
        env=env,
        check=True,
    )
    return result.stdout.strip()


@pytest.mark.parametrize(
    "preset",
    [
        None,
        # The exact value .env.example ships, which defeated the previous fix.
        "development",
        "dev",
        "test",
        "local",
        "DEVELOPMENT",
    ],
)
def test_local_environment_values_are_discarded_by_the_deployment_launcher(preset) -> None:
    resolved = _resolve_environment(preset)
    assert resolved not in Settings._LOCAL_ENVIRONMENTS
    assert resolved not in TOKENLESS_METRICS_ENVIRONMENTS
    assert openapi_route_kwargs(resolved) == {
        "docs_url": None,
        "redoc_url": None,
        "openapi_url": None,
    }, f"ENVIRONMENT={resolved!r} would publish OpenAPI docs on a public deployment"


def test_a_deliberate_non_local_environment_is_still_honoured() -> None:
    """Forcing must not clobber a real operator override."""
    assert _resolve_environment("staging") == "staging"


def test_env_example_ships_a_local_environment_that_must_be_overridden() -> None:
    """Pins the precondition the fix exists for.

    If .env.example ever stops shipping a local ENVIRONMENT this test should be
    revisited -- but the launcher must keep forcing regardless, since the file
    is operator-editable.
    """
    env_example = (REPOSITORY_ROOT / ".env.example").read_text()
    shipped = re.search(r"^ENVIRONMENT=(\S+)", env_example, re.MULTILINE)
    assert shipped is not None
    assert shipped.group(1) in Settings._LOCAL_ENVIRONMENTS


def test_deployment_launcher_does_not_pin_a_local_environment() -> None:
    """A local ENVIRONMENT waives database-credential validation entirely."""
    chosen = _resolve_environment(None)

    assert chosen not in Settings._LOCAL_ENVIRONMENTS, (
        f"deployment launcher defaults ENVIRONMENT={chosen!r}, which "
        "Settings.is_local_environment treats as local and therefore skips "
        "_validate_database_credentials on a publicly reachable deployment"
    )
    assert chosen not in TOKENLESS_METRICS_ENVIRONMENTS, (
        f"ENVIRONMENT={chosen!r} waives the /metrics scrape token"
    )
    assert openapi_route_kwargs(chosen) == {
        "docs_url": None,
        "redoc_url": None,
        "openapi_url": None,
    }, f"ENVIRONMENT={chosen!r} publishes OpenAPI docs on a public deployment"


def test_deployment_launcher_does_not_hardcode_default_database_passwords() -> None:
    launcher = _executable_lines(_replit_deployment_script())
    for forbidden in (
        "postgresql://postgres:postgres@",
        "postgresql://app_runtime:app_runtime@",
    ):
        assert forbidden not in launcher, (
            f"deployment launcher hardcodes the known-default credential {forbidden!r}"
        )
    # It must generate them instead, and rotate the runtime role to match.
    assert "PG_OWNER_PASSWORD" in launcher
    assert "PG_RUNTIME_PASSWORD" in launcher
    assert "app.db.runtime_role" in launcher


def test_deployment_launcher_serves_a_built_bundle_not_the_dev_server() -> None:
    launcher = _executable_lines(_replit_deployment_script())
    assert "npm run dev" not in launcher, (
        "the public deployment must not serve the Vite dev server "
        "(unminified, source-mapped, HMR websocket exposed)"
    )
    assert "npm run build" in launcher
    assert "npm run preview" in launcher


def test_vite_preview_keeps_the_api_proxy() -> None:
    """`server.proxy` does not apply to `vite preview`; the built bundle needs
    its own proxy block or every /api call 404s on the deployment."""
    config = (REPOSITORY_ROOT / "apps" / "web" / "vite.config.ts").read_text()
    assert "preview:" in config
    assert config.count("proxy: apiProxy") == 2


# --- C-1/C-2: the behaviour those assertions depend on ---


def _preview_settings_kwargs(**overrides) -> dict:
    kwargs = {
        "database_url": "postgresql://postgres:rotated-owner-pw@127.0.0.1:5432/content_orchestrator",
        "app_database_url": (
            "postgresql://app_runtime:rotated-runtime-pw@127.0.0.1:5432/content_orchestrator"
        ),
        "supabase_jwt_secret": nontest_jwt_secret(),
        "environment": PREVIEW_ENVIRONMENT,
        "auth_mode": "local",
    }
    kwargs.update(overrides)
    return kwargs


def test_preview_environment_is_not_local() -> None:
    assert Settings(**_preview_settings_kwargs()).is_local_environment is False


@pytest.mark.parametrize(
    ("field", "dsn"),
    [
        ("database_url", "postgresql://postgres:postgres@127.0.0.1:5432/content_orchestrator"),
        (
            "app_database_url",
            "postgresql://app_runtime:app_runtime@127.0.0.1:5432/content_orchestrator",
        ),
    ],
)
def test_preview_environment_rejects_known_default_database_passwords(field: str, dsn: str) -> None:
    with pytest.raises(ValidationError, match="known default database password"):
        Settings(**_preview_settings_kwargs(**{field: dsn}))


def test_preview_environment_rejects_weak_jwt_secret() -> None:
    with pytest.raises(ValidationError, match="at least 32 bytes"):
        Settings(**_preview_settings_kwargs(supabase_jwt_secret="too-short"))


def test_preview_environment_suppresses_openapi_docs() -> None:
    assert Settings(**_preview_settings_kwargs()).openapi_docs_enabled is False

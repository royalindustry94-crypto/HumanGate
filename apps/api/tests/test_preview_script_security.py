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
import tempfile
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


def _resolve_environment(
    preset: str | None, *, tmp_path: Path | None = None, existing_env: bool = False
) -> str:
    """Run the launcher's REAL prefix -- .env handling included -- under bash.

    Everything from the top of the script through the END marker is executed,
    in a throwaway root seeded with the repository's own `.env.example`. That
    span, not the marked block alone, is what decides ENVIRONMENT on a real
    boot, and each narrower version of this helper hid a live bug:

      * asserting on the literal `ENVIRONMENT="${ENVIRONMENT:-preview}"` missed
        that the launcher sources `.env.example` (ENVIRONMENT=development)
        first, so `:-` never substituted and the public deployment ran as a
        local environment; then
      * executing only the marked block missed that `set -a; source .env`
        assigns every key in the template, clobbering an ENVIRONMENT=staging
        supplied by the deployment platform before the block ever sees it.

    Both passed a test while the shipped script did the wrong thing, so this
    runs the whole prefix instead.
    """
    raw = (REPOSITORY_ROOT / _replit_deployment_script()).read_text()
    prefix = raw.split("# --- END environment resolution", 1)[0]

    root = Path(tempfile.mkdtemp()) if tmp_path is None else tmp_path
    (root / "scripts").mkdir(parents=True, exist_ok=True)
    # ROOT is derived from `dirname "$0"/..`, so a stand-in script path here
    # points the launcher at this throwaway tree rather than the repository.
    stand_in = root / "scripts" / "run_ops_preview_replit.sh"
    stand_in.write_text(prefix + '\nprintf "%s" "$ENVIRONMENT"\n')

    # The real template with its credential blanks filled in. ENVIRONMENT is
    # left exactly as shipped -- that is the value under test -- but the
    # OPS_PREVIEW_ blanks must be populated or `set -a; source` assigns the
    # empty strings over the caller's and the `:?` guards abort the run before
    # the resolution block is ever reached.
    template = (REPOSITORY_ROOT / ".env.example").read_text()
    template = template.replace(
        "OPS_PREVIEW_EMAIL=", "OPS_PREVIEW_EMAIL=ops@example.invalid"
    ).replace("OPS_PREVIEW_PASSWORD=", "OPS_PREVIEW_PASSWORD=not-a-real-password")
    (root / ".env.example").write_text(template)
    if existing_env:
        # Steady state: the operator already has a .env (created from the
        # template, ENVIRONMENT left at its default). No copy happens, but
        # sourcing still assigns that ENVIRONMENT over the platform's.
        (root / ".env").write_text(template)

    env = dict(os.environ)
    env.pop("ENVIRONMENT", None)
    if preset is not None:
        env["ENVIRONMENT"] = preset
    # S603: the command is this repository's own launcher, run under bash with
    # a fixed argv; `preset` only ever reaches it as an environment value.
    result = subprocess.run(  # noqa: S603
        ["bash", str(stand_in)],
        capture_output=True,
        text=True,
        env=env,
        check=True,
    )
    return result.stdout.strip()


def _case_variants(value: str) -> list[str]:
    """Spellings the application would treat identically.

    Every environment comparison in app/core/config.py and
    app/api/routes/metrics.py normalises with `.strip().lower()`, so these are
    all equivalent to the app and must all be caught by the launcher.
    """
    return [value, value.upper(), value.capitalize(), f"  {value} "]


# Derived from the application's own constants rather than hardcoded: adding a
# new local or tokenless environment name to the app fails this test until the
# launcher's case list covers it.
LOCAL_OR_TOKENLESS = sorted(set(Settings._LOCAL_ENVIRONMENTS) | set(TOKENLESS_METRICS_ENVIRONMENTS))

_PRESETS = [None] + [variant for value in LOCAL_OR_TOKENLESS for variant in _case_variants(value)]


def test_the_derived_case_set_is_not_silently_empty() -> None:
    """Guards the guard: an empty derived set would make the sweep vacuous."""
    assert {"development", "dev", "test", "local", "ci"} <= set(LOCAL_OR_TOKENLESS)


@pytest.mark.parametrize("preset", _PRESETS)
def test_local_environment_values_are_discarded_by_the_deployment_launcher(preset) -> None:
    resolved = _resolve_environment(preset)
    normalized = resolved.strip().lower()
    assert normalized not in Settings._LOCAL_ENVIRONMENTS
    assert normalized not in TOKENLESS_METRICS_ENVIRONMENTS
    assert openapi_route_kwargs(resolved) == {
        "docs_url": None,
        "redoc_url": None,
        "openapi_url": None,
    }, f"ENVIRONMENT={resolved!r} would publish OpenAPI docs on a public deployment"


@pytest.mark.parametrize("override", ["staging", "production"])
@pytest.mark.parametrize("existing_env", [False, True], ids=["fresh-boot", "existing-dotenv"])
def test_a_deliberate_non_local_environment_is_still_honoured(
    override: str, existing_env: bool
) -> None:
    """Forcing must not clobber a real operator override.

    Run through the whole prefix, in both shapes the launcher can meet:

      * fresh boot -- no `.env`, so the template is copied and sourced; and
      * steady state -- a `.env` already exists (made from that template, its
        ENVIRONMENT left at the default) and is sourced as-is.

    The second is the one that actually bites a deployment: `set -a; source
    .env` assigns the file's ENVIRONMENT over the platform-supplied one, so a
    deliberate `staging` silently became `development` and then `preview`.
    """
    assert _resolve_environment(override, existing_env=existing_env) == override


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


# --- The refusal diagnostic must name only the controls actually waived ---


def _refusal_output(value: str) -> tuple[int, str]:
    """Run the launcher's real refuse-to-start block for one ENVIRONMENT."""
    raw = (REPOSITORY_ROOT / _replit_deployment_script()).read_text()
    block = raw.split("# Belt and braces", 1)[1].split("unset _hg_refuse_env", 1)[0]
    script = 'ENVIRONMENT="$1"\n# Belt and braces' + block
    # S603: this repository's own launcher fragment, fixed argv, `value` passed
    # as a positional argument rather than interpolated into the script.
    result = subprocess.run(  # noqa: S603
        ["bash", "-c", script, "bash", value],
        capture_output=True,
        text=True,
        check=False,
    )
    return result.returncode, result.stderr


_CREDENTIAL_LINE = "database-credential validation is skipped"
_DOCS_LINE = "/docs, /redoc and /openapi.json are published"
_METRICS_LINE = "the /metrics scrape token is not required"


@pytest.mark.parametrize("value", LOCAL_OR_TOKENLESS)
def test_refusal_names_exactly_the_controls_that_value_waives(value: str) -> None:
    """A refusal message that overclaims costs an operator real debugging time.

    The two allow-lists are different sets -- `local` and `ci` are tokenless for
    /metrics but are NOT local for credential validation or docs suppression,
    and `test` is the only value in both. The message used to assert credential
    validation and published docs for all five.
    """
    code, err = _refusal_output(value)
    assert code == 1, f"ENVIRONMENT={value!r} must refuse to start"

    is_local = value in Settings._LOCAL_ENVIRONMENTS
    is_tokenless = value in TOKENLESS_METRICS_ENVIRONMENTS

    assert (_CREDENTIAL_LINE in err) is is_local, (
        f"ENVIRONMENT={value!r}: credential-validation claim must match "
        f"Settings._LOCAL_ENVIRONMENTS membership ({is_local})"
    )
    assert (_DOCS_LINE in err) is is_local, (
        f"ENVIRONMENT={value!r}: published-docs claim must match "
        f"Settings._LOCAL_ENVIRONMENTS membership ({is_local})"
    )
    assert (_METRICS_LINE in err) is is_tokenless, (
        f"ENVIRONMENT={value!r}: metrics-token claim must match "
        f"TOKENLESS_METRICS_ENVIRONMENTS membership ({is_tokenless})"
    )


def test_a_non_local_environment_passes_the_refusal_gate() -> None:
    code, err = _refusal_output(PREVIEW_ENVIRONMENT)
    assert code == 0, f"ENVIRONMENT={PREVIEW_ENVIRONMENT!r} must be allowed to start: {err}"

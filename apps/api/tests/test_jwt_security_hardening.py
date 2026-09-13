"""Regression tests for P0-1 (2026-09-13 independent audit): JWT secret
strength/placeholder validation, issuer checking, and UUID `sub` enforcement.
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest
from jwt import encode as jwt_encode
from pydantic import ValidationError

from app.core import security as security_module
from app.core.config import LOCAL_JWT_ISSUER, REPOSITORY_TEST_JWT_SECRET, Settings
from tests.conftest import make_token, nontest_jwt_secret

_SUPABASE_ISSUER = "https://test-project.supabase.co/auth/v1"
_REPO_ROOT = Path(__file__).resolve().parents[3]


def _base_settings_kwargs(**overrides) -> dict:
    kwargs = {
        "database_url": "postgresql://postgres:rotated-owner-password@127.0.0.1:5432/content_orchestrator_test",
        "app_database_url": (
            "postgresql://app_runtime:rotated-test-password@127.0.0.1:5432/content_orchestrator_test"
        ),
        "supabase_jwt_secret": nontest_jwt_secret(),
        "supabase_jwt_issuer": _SUPABASE_ISSUER,
        "environment": "development",
        "auth_mode": "supabase",
    }
    kwargs.update(overrides)
    return kwargs


# --- Settings: SUPABASE_JWT_SECRET strength/placeholder validation ---


def test_blank_secret_rejected_outside_test():
    with pytest.raises(ValidationError, match="at least 32 bytes"):
        Settings(**_base_settings_kwargs(supabase_jwt_secret=""))


def test_short_secret_rejected_outside_test():
    with pytest.raises(ValidationError, match="at least 32 bytes"):
        Settings(**_base_settings_kwargs(supabase_jwt_secret="too-short"))


@pytest.mark.parametrize(
    "weak_secret",
    [
        "secret",
        "changeme",
        "password",
        # Long enough to pass the length check alone — must still be
        # rejected by name, not just by length.
        "super-secret-jwt-token-with-at-least-32-characters-long",
        "SUPER-SECRET-JWT-TOKEN-WITH-AT-LEAST-32-CHARACTERS-LONG",
        REPOSITORY_TEST_JWT_SECRET,
        "ci-test-supabase-jwt-secret",
        "ci-browser-smoke-supabase-jwt-secret",
        "".join(("HgCiBrowserSmokeJwt", "9f3a7c2e1b8d0465k4m2")),
    ],
)
def test_known_placeholder_secret_rejected(weak_secret: str):
    with pytest.raises(ValidationError, match="placeholder|32 bytes"):
        Settings(**_base_settings_kwargs(supabase_jwt_secret=weak_secret))


def test_repository_test_secret_rejected_in_production():
    with pytest.raises(ValidationError, match="placeholder"):
        Settings(
            **_base_settings_kwargs(
                environment="production",
                supabase_jwt_secret=REPOSITORY_TEST_JWT_SECRET,
            )
        )


def test_predictable_low_entropy_secret_rejected():
    with pytest.raises(ValidationError, match="predictable"):
        Settings(**_base_settings_kwargs(supabase_jwt_secret="a" * 40))


def test_real_random_secret_accepted():
    settings = Settings(**_base_settings_kwargs())
    assert settings.environment == "development"
    assert len(settings.supabase_jwt_secret.encode("utf-8")) >= 32
    assert settings.supabase_jwt_issuer == _SUPABASE_ISSUER


def test_weak_secret_exempt_under_environment_test():
    """tests/conftest.py itself relies on this exemption (its fixed
    41-byte string is fine, but a short/blank secret under
    ENVIRONMENT=test must not start failing this validator either)."""
    settings = Settings(**_base_settings_kwargs(environment="test", supabase_jwt_secret="x"))
    assert settings.environment == "test"


def test_production_also_enforces_secret_strength():
    with pytest.raises(ValidationError, match="at least 32 bytes"):
        Settings(**_base_settings_kwargs(environment="production", supabase_jwt_secret="short"))


# --- Settings: issuer required for non-test Supabase mode ---


def test_production_supabase_without_issuer_fails_startup():
    with pytest.raises(ValidationError, match="SUPABASE_JWT_ISSUER"):
        Settings(
            **_base_settings_kwargs(
                environment="production",
                auth_mode="supabase",
                supabase_jwt_issuer=None,
            )
        )


def test_development_supabase_blank_issuer_fails_startup():
    with pytest.raises(ValidationError, match="SUPABASE_JWT_ISSUER"):
        Settings(**_base_settings_kwargs(auth_mode="supabase", supabase_jwt_issuer=""))


def test_local_mode_defaults_to_local_issuer():
    settings = Settings(**_base_settings_kwargs(auth_mode="local", supabase_jwt_issuer=None))
    assert settings.supabase_jwt_issuer == LOCAL_JWT_ISSUER


def test_local_mode_rejects_mismatched_issuer():
    with pytest.raises(ValidationError, match="AUTH_MODE=local"):
        Settings(
            **_base_settings_kwargs(
                auth_mode="local",
                supabase_jwt_issuer="https://attacker.example/auth/v1",
            )
        )


def test_ci_browser_smoke_jwt_secret_is_ephemeral():
    workflow = (_REPO_ROOT / ".github/workflows/ci.yml").read_text()
    smoke = workflow.split("  browser-smoke:", 1)[1].split("\n  security:", 1)[0]
    assert "SUPABASE_JWT_SECRET:" not in smoke
    assert "token_urlsafe" in smoke
    assert "GITHUB_ENV" in smoke


# --- security.get_current_user: UUID `sub` enforcement ---


@pytest.mark.asyncio
async def test_non_uuid_sub_is_rejected(client):
    token = make_token(user_id="not-a-uuid")
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401
    assert "sub" in response.json()["detail"]


@pytest.mark.asyncio
async def test_valid_uuid_sub_is_accepted(client):
    token = make_token()
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200


# --- security._decode_supabase_jwt: fail-closed issuer enforcement ---


@pytest.mark.asyncio
async def test_issuer_mismatch_rejected(client, monkeypatch):
    settings = security_module.settings
    monkeypatch.setattr(settings, "supabase_jwt_issuer", "https://expected.example/auth/v1")
    payload = {
        "sub": "11111111-1111-1111-1111-111111111111",
        "email": "test@example.com",
        "aud": settings.supabase_jwt_audience,
        "exp": int(time.time()) + 3600,
        "role": "authenticated",
        "iss": LOCAL_JWT_ISSUER,
    }
    token = jwt_encode(
        payload, settings.supabase_jwt_secret, algorithm=settings.supabase_jwt_algorithm
    )
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_matching_supabase_issuer_accepted(client, monkeypatch):
    settings = security_module.settings
    monkeypatch.setattr(settings, "supabase_jwt_issuer", "https://expected.example/auth/v1")
    payload = {
        "sub": "11111111-1111-1111-1111-111111111111",
        "email": "test@example.com",
        "aud": settings.supabase_jwt_audience,
        "exp": int(time.time()) + 3600,
        "role": "authenticated",
        "iss": "https://expected.example/auth/v1",
    }
    token = jwt_encode(
        payload, settings.supabase_jwt_secret, algorithm=settings.supabase_jwt_algorithm
    )
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200


@pytest.mark.asyncio
async def test_matching_local_issuer_accepted(client):
    token = make_token()
    assert security_module.settings.supabase_jwt_issuer == LOCAL_JWT_ISSUER
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200


@pytest.mark.asyncio
async def test_issuerless_token_rejected(client):
    settings = security_module.settings
    payload = {
        "sub": "11111111-1111-1111-1111-111111111111",
        "email": "test@example.com",
        "aud": settings.supabase_jwt_audience,
        "exp": int(time.time()) + 3600,
        "role": "authenticated",
    }
    token = jwt_encode(
        payload, settings.supabase_jwt_secret, algorithm=settings.supabase_jwt_algorithm
    )
    response = await client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401

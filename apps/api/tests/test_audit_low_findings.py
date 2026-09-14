"""Regressions for the Low findings of the 2026-09-14 audit (L-1..L-7)."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.core.audit import _sensitive
from app.core.config import Settings, get_settings
from tests.conftest import nontest_jwt_secret

# --- L-2: sensitive-key matching was exact, so compound names slipped past ---


@pytest.mark.parametrize(
    "field",
    [
        "secret",
        "token",
        "authorization",
        # None of these matched the old exact-key set.
        "password",
        "api_key",
        "apikey",
        "stripe_secret_key",
        "access_token",
        "refresh_token",
        "worker_credential",
        "private_key",
        "SECRET_HASH",
    ],
)
def test_credential_shaped_field_names_are_refused(field):
    assert _sensitive([field]) == [field]


@pytest.mark.parametrize(
    "field",
    [
        # Identifiers, not values — this module's documented contract.
        "credential_id",
        "new_credential_id",
        "workspace_id",
        "worker_id",
        # Ordinary audit fields must stay loggable.
        "status",
        "outcome",
        "provider",
        "role",
    ],
)
def test_identifier_and_ordinary_fields_are_allowed(field):
    assert _sensitive([field]) == []


def test_audit_rejects_a_credential_field_at_the_call_site(monkeypatch):
    from app.core.audit import audit

    with pytest.raises(ValueError, match="refusing to audit-log sensitive fields"):
        audit(None, "test_event", stripe_secret_key="sk_live_whatever")


# --- L-7: a credentialed wildcard CORS origin must fail closed ---


def _settings_kwargs(**overrides) -> dict:
    kwargs = {
        "database_url": "postgresql://postgres:rotated-owner-pw@127.0.0.1:5432/co",
        "app_database_url": "postgresql://app_runtime:rotated-runtime-pw@127.0.0.1:5432/co",
        "supabase_jwt_secret": nontest_jwt_secret(),
        "environment": "preview",
        "auth_mode": "local",
    }
    kwargs.update(overrides)
    return kwargs


@pytest.mark.parametrize("origins", [["*"], ["http://localhost:5173", "*"], [" * "]])
def test_wildcard_cors_origin_is_rejected(origins):
    with pytest.raises(ValidationError, match="must not contain"):
        Settings(**_settings_kwargs(cors_allow_origins=origins))


def test_explicit_cors_origins_are_accepted():
    settings = Settings(**_settings_kwargs(cors_allow_origins=["https://app.example.com"]))
    assert settings.cors_allow_origins == ["https://app.example.com"]


# --- L-1: the readiness probe's role disclosure is gated outside local envs ---


@pytest.mark.asyncio
async def test_readiness_reports_db_role_in_local_environments(client):
    """ENVIRONMENT=test is local, so the deployment diagnostic stays visible."""
    response = await client.get("/health/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["database"] == "reachable"
    assert "db_user" in body


@pytest.mark.asyncio
async def test_readiness_withholds_db_role_without_the_metrics_token(client, monkeypatch):
    monkeypatch.setenv("ENVIRONMENT", "preview")
    monkeypatch.setenv("METRICS_SCRAPER_TOKEN", "scrape-me-please")
    # ENVIRONMENT=preview is non-local, so the P0-2 credential validator runs
    # and rejects the suite's default postgres/app_runtime DSNs. Supply rotated
    # ones; the DB engines were bound at import and are unaffected by this.
    monkeypatch.setenv("DATABASE_URL", "postgresql://postgres:rotated-owner-pw@127.0.0.1:5432/co")
    monkeypatch.setenv(
        "APP_DATABASE_URL",
        "postgresql://app_runtime:rotated-runtime-pw@127.0.0.1:5432/co",
    )
    # The suite's fixed secret is deny-listed outside ENVIRONMENT=test.
    monkeypatch.setenv("SUPABASE_JWT_SECRET", nontest_jwt_secret())
    get_settings.cache_clear()
    try:
        response = await client.get("/health/ready")
        assert response.status_code == 200
        body = response.json()
        # Probe contract is unchanged for load balancers...
        assert body["status"] == "ok"
        assert body["database"] == "reachable"
        # ...but the infrastructure detail is not handed to anonymous callers.
        assert "db_user" not in body

        authorized = await client.get(
            "/health/ready", headers={"Authorization": "Bearer scrape-me-please"}
        )
        assert "db_user" in authorized.json()

        wrong = await client.get("/health/ready", headers={"Authorization": "Bearer not-the-token"})
        assert "db_user" not in wrong.json()
    finally:
        get_settings.cache_clear()

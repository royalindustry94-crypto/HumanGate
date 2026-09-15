"""Regressions for the Low findings of the 2026-09-14 audit (L-1..L-7)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from pydantic import ValidationError

from app.core.audit import _sensitive
from app.core.config import Settings, get_settings
from app.db.session import get_db
from app.main import app
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


@pytest.mark.asyncio
async def test_non_local_unauthenticated_health_and_metrics_shapes(client, monkeypatch):
    from app.api.routes import health as health_routes
    from app.api.routes import metrics as metrics_routes

    metrics_token = "scrape-me-please"
    fake_automation_payload = {
        "status": "ok",
        "started_at": "2026-01-01T00:00:00+00:00",
        "tasks_running": ["scheduler"],
        "maintenance": {"status": "idle", "active": False},
        "outbox_relay": {"status": "idle", "active": False},
        "scheduler": {"status": "running", "active": True},
    }

    result = Mock()
    result.scalar_one.return_value = "app_runtime"
    session = AsyncMock()
    session.execute.return_value = result

    async def _fake_db():
        yield session

    async def _fake_automation_health_snapshot():
        return {}

    async def _fake_collect(_session):
        return "co_up 1\n"

    app.dependency_overrides[get_db] = _fake_db
    monkeypatch.setattr(
        health_routes,
        "automation_health_snapshot",
        _fake_automation_health_snapshot,
    )
    monkeypatch.setattr(
        health_routes,
        "public_automation_health_snapshot",
        lambda _snapshot: fake_automation_payload,
    )
    monkeypatch.setattr(metrics_routes, "_collect", _fake_collect)
    monkeypatch.setattr(
        health_routes,
        "get_settings",
        lambda: SimpleNamespace(
            is_local_environment=False,
            metrics_scraper_token=metrics_token,
        ),
    )
    monkeypatch.setattr(
        metrics_routes,
        "get_settings",
        lambda: SimpleNamespace(
            environment="preview",
            metrics_scraper_token=metrics_token,
        ),
    )
    try:
        live = await client.get("/health/live")
        assert live.status_code == 200
        assert live.json() == {"status": "ok"}

        ready = await client.get("/health/ready")
        assert ready.status_code == 200
        assert ready.json() == {"status": "ok", "database": "reachable"}

        automation = await client.get("/health/automation")
        assert automation.status_code == 200
        assert automation.json() == {
            "status": "ok",
            "maintenance": {"status": "idle", "active": False},
            "outbox_relay": {"status": "idle", "active": False},
            "scheduler": {"status": "running", "active": True},
        }
        metrics = await client.get("/metrics")
        assert metrics.status_code == 401
        assert metrics.json() == {"detail": "missing metrics bearer token"}

        operations_health = await client.get(
            f"/workspaces/{uuid.uuid4()}/operations/health"
        )
        assert operations_health.status_code == 401
    finally:
        app.dependency_overrides.pop(get_db, None)


# --- L-3 completion: the correlation id must reach the 500 response too ---


@pytest.mark.asyncio
async def test_unhandled_error_response_carries_the_request_id():
    """Starlette's ServerErrorMiddleware builds the 500 outside every user
    middleware, so RequestIDMiddleware could log the id but never attach it to
    the response. Both halves must hold on the failing path."""
    import httpx
    from fastapi import FastAPI
    from httpx import ASGITransport

    from app.core.audit import RequestIDMiddleware

    # The PRODUCTION handler, not a copy of it. A local re-implementation here
    # would keep passing if app.main stopped setting the header, which is the
    # only thing this test exists to catch.
    from app.main import _unhandled_exception_handler

    probe = FastAPI()
    probe.add_middleware(RequestIDMiddleware)
    probe.add_exception_handler(Exception, _unhandled_exception_handler)

    @probe.get("/boom")
    async def boom():
        raise RuntimeError("kaboom")

    @probe.get("/fine")
    async def fine():
        return {"ok": True}

    transport = ASGITransport(app=probe, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://probe") as client:
        ok = await client.get("/fine")
        assert ok.status_code == 200
        assert ok.headers.get("X-Request-ID")

        failed = await client.get("/boom")
        assert failed.status_code == 500
        assert failed.headers.get("X-Request-ID"), (
            "the 500 produced by an unhandled error must still be correlatable"
        )
        assert failed.headers["X-Request-ID"] != ok.headers["X-Request-ID"]


def test_app_registers_the_same_unhandled_exception_handler():
    """Pins that the real app wires the exact handler the probe exercises."""
    from app.main import _unhandled_exception_handler, app

    assert app.exception_handlers.get(Exception) is _unhandled_exception_handler


def test_exception_key_installs_the_server_error_handler_not_an_inner_one():
    """Settles a review claim: `Exception` here is the ServerErrorMiddleware key.

    A review argued that registering `_unhandled_exception_handler` for
    `Exception` makes FastAPI's inner `ExceptionMiddleware` consume the error so
    Starlette's outer `ServerErrorMiddleware` never re-raises it, losing the
    traceback, and that registering for status `500` instead would fix it.

    Starlette's `build_middleware_stack` buckets the two identically::

        for key, value in self.exception_handlers.items():
            if key in (500, Exception):
                error_handler = value
            else:
                exception_handlers[key] = value

    so `Exception` never reaches `ExceptionMiddleware`, and switching to `500`
    would be a no-op. Pinned here rather than argued, and the companion test
    below covers the re-raise the claim said was lost.
    """
    from starlette.middleware.errors import ServerErrorMiddleware

    from app.main import _unhandled_exception_handler, app

    app.build_middleware_stack()
    layer = app.middleware_stack
    assert isinstance(layer, ServerErrorMiddleware)
    assert layer.handler is _unhandled_exception_handler

    # And it is absent from the inner ExceptionMiddleware's table.
    inner = {k for k in app.exception_handlers if k not in (500, Exception)}
    assert Exception not in inner


@pytest.mark.asyncio
async def test_unhandled_error_still_propagates_for_server_logging():
    """The 500 response is sent AND the exception keeps propagating.

    `ServerErrorMiddleware` sends the handler's response and then re-raises
    ("We always continue to raise the exception. This allows servers to log the
    error"). So attaching X-Request-ID did not swallow the traceback: with
    `raise_app_exceptions=True` the original error must still reach the caller.
    """
    import httpx
    from fastapi import FastAPI
    from httpx import ASGITransport

    from app.core.audit import RequestIDMiddleware
    from app.main import _unhandled_exception_handler

    probe = FastAPI()
    probe.add_middleware(RequestIDMiddleware)
    probe.add_exception_handler(Exception, _unhandled_exception_handler)

    @probe.get("/boom")
    async def boom():
        raise RuntimeError("kaboom")

    transport = ASGITransport(app=probe, raise_app_exceptions=True)
    async with httpx.AsyncClient(transport=transport, base_url="http://probe") as client:
        with pytest.raises(RuntimeError, match="kaboom"):
            await client.get("/boom")

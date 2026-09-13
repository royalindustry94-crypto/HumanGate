"""Adversarial closure tests for M-F (local auth), M-G (metrics exposure)
and M-H (owner/service-role session tenant scoping).

All three findings concern controls that are enforced in application code
rather than by RLS, so each one is probed here from the outside via the HTTP
surface with a second tenant present.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select, text

from app.api.routes.metrics import (
    TOKENLESS_METRICS_ENVIRONMENTS,
    _authorize_metrics_scrape,
)
from app.core.config import get_settings
from app.db.session import AsyncSessionLocal, RuntimeSessionLocal
from app.models.local_auth import LocalAuthCredential
from app.services import local_auth
from tests.conftest import nontest_jwt_secret

STRONG_PASSWORD = "Correct-Horse-Battery-9!"
_ROTATED_OWNER_URL = (
    "postgresql://postgres:rotated-owner-password@127.0.0.1:5432/content_orchestrator_test"
)
_ROTATED_RUNTIME_URL = (
    "postgresql://app_runtime:rotated-test-password@127.0.0.1:5432/content_orchestrator_test"
)


def _non_local_settings_env(monkeypatch) -> None:
    """Non-local ENVIRONMENT values reject the test-process DB defaults."""
    monkeypatch.setenv("DATABASE_URL", _ROTATED_OWNER_URL)
    monkeypatch.setenv("APP_DATABASE_URL", _ROTATED_RUNTIME_URL)
    monkeypatch.setenv("SUPABASE_JWT_SECRET", nontest_jwt_secret())


# --- M-G: /metrics must fail closed outside explicit local environments ----


@pytest.mark.parametrize(
    "environment",
    ["staging", "preview", "demo", "beta", "unknown-env", ""],
)
def test_mg_metrics_requires_token_in_every_deployed_environment(monkeypatch, environment):
    """Non-production deployed environments were previously fail-open.

    ``production``/``prod`` are covered separately because the Settings model
    forbids AUTH_MODE=local there, which makes the whole settings object
    unloadable in this test process; the allow-list assertion below proves
    they are excluded regardless.
    """
    monkeypatch.delenv("METRICS_SCRAPER_TOKEN", raising=False)
    monkeypatch.setenv("ENVIRONMENT", environment)
    _non_local_settings_env(monkeypatch)
    get_settings.cache_clear()
    try:
        assert environment.strip().lower() not in TOKENLESS_METRICS_ENVIRONMENTS
        with pytest.raises(Exception) as exc:
            _authorize_metrics_scrape(None)
        assert getattr(exc.value, "status_code", None) == 401, (
            f"environment {environment!r} must not serve metrics without a token"
        )
    finally:
        monkeypatch.delenv("ENVIRONMENT", raising=False)
        get_settings.cache_clear()


@pytest.mark.parametrize("environment", ["production", "prod"])
def test_mg_production_is_never_tokenless(environment):
    assert environment not in TOKENLESS_METRICS_ENVIRONMENTS


def test_mg_metrics_token_is_required_and_compared_exactly(monkeypatch):
    monkeypatch.setenv("ENVIRONMENT", "staging")
    monkeypatch.setenv("METRICS_SCRAPER_TOKEN", "scrape-token-abcdef123456")
    _non_local_settings_env(monkeypatch)
    get_settings.cache_clear()
    try:
        # Correct token passes.
        _authorize_metrics_scrape("Bearer scrape-token-abcdef123456")
        for bad in (
            None,
            "",
            "scrape-token-abcdef123456",  # missing scheme
            "Bearer ",
            "Bearer scrape-token-abcdef12345",  # prefix
            "Bearer scrape-token-abcdef1234567",  # extension
            "Bearer SCRAPE-TOKEN-ABCDEF123456",  # case
        ):
            with pytest.raises(Exception) as exc:
                _authorize_metrics_scrape(bad)
            assert getattr(exc.value, "status_code", None) == 401, bad
    finally:
        monkeypatch.delenv("ENVIRONMENT", raising=False)
        monkeypatch.delenv("METRICS_SCRAPER_TOKEN", raising=False)
        get_settings.cache_clear()


@pytest.mark.asyncio
async def test_mg_metrics_endpoint_exposes_no_tenant_identifiers(client):
    """The test environment is tokenless by design; the response must still
    contain only aggregate series, never workspace ids or topics.
    """
    async with AsyncSessionLocal() as session:
        ws = str(uuid.uuid4())
        user = str(uuid.uuid4())
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :e)"),
            {"id": user, "e": f"{user}@x.com"},
        )
        await session.execute(
            text("INSERT INTO workspaces (id, name, created_by) VALUES (:id, :n, :u)"),
            {"id": ws, "n": "metrics-leak-probe", "u": user},
        )
        await session.commit()

    res = await client.get("/metrics")
    assert res.status_code == 200
    body = res.text
    assert ws not in body, "workspace id must never appear in metrics output"
    assert "metrics-leak-probe" not in body, "workspace name must not be exposed"


# --- M-F: local auth brute-force + enumeration controls -------------------


@pytest.fixture
def local_auth_on(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "local")
    get_settings.cache_clear()
    yield
    monkeypatch.delenv("AUTH_MODE", raising=False)
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_mf_repeated_bad_passwords_lock_the_account(client, local_auth_on):
    email = f"lockout-{uuid.uuid4().hex[:10]}@example.com"
    signup = await client.post("/auth/signup", json={"email": email, "password": STRONG_PASSWORD})
    assert signup.status_code == 201, signup.text

    for attempt in range(local_auth.MAX_FAILED_ATTEMPTS):
        res = await client.post(
            "/auth/login", json={"email": email, "password": "wrong-password-value"}
        )
        assert res.status_code == 401, f"attempt {attempt}: {res.text}"

    async with AsyncSessionLocal() as session:
        row = (
            await session.execute(
                select(LocalAuthCredential).where(LocalAuthCredential.email == email)
            )
        ).scalar_one()
        assert row.failed_attempts >= local_auth.MAX_FAILED_ATTEMPTS
        assert row.locked_until is not None, "account must be locked after the threshold"

    # The correct password is now refused while the lock holds — an attacker
    # cannot keep guessing, and the response is indistinguishable from a
    # normal failure.
    locked = await client.post("/auth/login", json={"email": email, "password": STRONG_PASSWORD})
    assert locked.status_code == 401
    assert locked.json()["detail"] == "invalid email or password"


@pytest.mark.asyncio
async def test_mf_login_responses_do_not_disclose_account_existence(client, local_auth_on):
    known = f"known-{uuid.uuid4().hex[:10]}@example.com"
    unknown = f"missing-{uuid.uuid4().hex[:10]}@example.com"
    created = await client.post("/auth/signup", json={"email": known, "password": STRONG_PASSWORD})
    assert created.status_code == 201

    a = await client.post("/auth/login", json={"email": known, "password": "wrong-password-value"})
    b = await client.post(
        "/auth/login", json={"email": unknown, "password": "wrong-password-value"}
    )
    assert a.status_code == b.status_code == 401
    assert a.json() == b.json(), "error bodies must be identical for both cases"


@pytest.mark.asyncio
async def test_mf_successful_login_clears_the_failure_counter(client, local_auth_on):
    email = f"reset-{uuid.uuid4().hex[:10]}@example.com"
    assert (
        await client.post("/auth/signup", json={"email": email, "password": STRONG_PASSWORD})
    ).status_code == 201

    for _ in range(3):
        assert (
            await client.post("/auth/login", json={"email": email, "password": "nope-nope-nope"})
        ).status_code == 401

    ok = await client.post("/auth/login", json={"email": email, "password": STRONG_PASSWORD})
    assert ok.status_code == 200, ok.text

    async with AsyncSessionLocal() as session:
        row = (
            await session.execute(
                select(LocalAuthCredential).where(LocalAuthCredential.email == email)
            )
        ).scalar_one()
        assert row.failed_attempts == 0
        assert row.locked_until is None


@pytest.mark.asyncio
async def test_mf_weak_passwords_are_rejected_at_signup(client, local_auth_on):
    """Rejected by the request schema (422) or by the service policy (400) --
    both layers carry the same floor, so a bypass of either still fails.
    """
    res = await client.post(
        "/auth/signup",
        json={"email": f"weak-{uuid.uuid4().hex[:8]}@example.com", "password": "short12"},
    )
    assert res.status_code in (400, 422), res.text

    # Service layer enforces the same rule directly, independent of the schema.
    async with AsyncSessionLocal() as session:
        with pytest.raises(local_auth.AuthError) as exc:
            await local_auth.signup(
                session,
                email=f"weak2-{uuid.uuid4().hex[:8]}@example.com",
                password="short12",
            )
        assert exc.value.code == "weak_password"
        assert str(local_auth.MIN_PASSWORD_LENGTH) in exc.value.message


@pytest.mark.asyncio
async def test_mf_local_auth_routes_are_absent_when_mode_is_supabase(client, monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "supabase")
    get_settings.cache_clear()
    try:
        for path in ("/auth/signup", "/auth/login"):
            res = await client.post(
                path,
                json={
                    "email": f"disabled-{uuid.uuid4().hex[:8]}@example.com",
                    "password": STRONG_PASSWORD,
                },
            )
            assert res.status_code == 404, f"{path} must be unreachable: {res.text}"
    finally:
        monkeypatch.delenv("AUTH_MODE", raising=False)
        get_settings.cache_clear()


# --- M-F2: runtime role must not read pre-auth credential hashes ------------


@pytest.mark.asyncio
async def test_mf_runtime_role_cannot_read_local_auth_credentials():
    """The RLS-bound product role has no legitimate pre-auth credential use."""
    async with AsyncSessionLocal() as owner_session:
        granted = await owner_session.scalar(
            text(
                "SELECT has_table_privilege("
                "'app_runtime', 'public.local_auth_credentials', 'SELECT')"
            )
        )
    assert granted is False

    async with RuntimeSessionLocal() as runtime_session:
        with pytest.raises(Exception, match="permission denied"):
            await runtime_session.execute(
                text("SELECT user_id, password_hash FROM local_auth_credentials LIMIT 1")
            )


# --- M-H: owner/service-role routes must still be tenant-scoped -----------


async def _bootstrap_tenant(client) -> dict:
    from tests.conftest import make_token

    user_id = str(uuid.uuid4())
    email = f"{user_id}@example.com"
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :e)"),
            {"id": user_id, "e": email},
        )
        await session.commit()
    headers = {"Authorization": f"Bearer {make_token(user_id=user_id, email=email)}"}
    created = await client.post(
        "/workspaces", headers=headers, json={"name": f"t-{uuid.uuid4().hex[:6]}"}
    )
    assert created.status_code == 201, created.text
    return {"user_id": user_id, "headers": headers, "workspace_id": created.json()["id"]}


OWNER_SESSION_GET_ROUTES = [
    "/workspaces/{ws}/billing",
    "/workspaces/{ws}/review-gates",
    "/workspaces/{ws}/concurrency",
    "/workspaces/{ws}/provider-budgets",
    "/workspaces/{ws}/operations/executive",
    "/workspaces/{ws}/operations/workers",
    "/workspaces/{ws}/operations/pipelines",
    "/workspaces/{ws}/operations/alerts",
    "/workspaces/{ws}/operations/spend",
    "/workspaces/{ws}/operations/activity",
    "/workspaces/{ws}/operations/health",
    "/workspaces/{ws}/operations/cost-control",
    "/workspaces/{ws}/operations/customers",
    "/workspaces/{ws}/operations/leads",
    "/workspaces/{ws}/workers",
]


@pytest.mark.asyncio
async def test_mh_owner_session_routes_reject_non_member_callers(client):
    """Every projection route that runs on the owner/service-role session
    bypasses RLS, so its HTTP guard is the only tenant boundary. A member of
    tenant B must not be able to read tenant A through any of them.
    """
    victim = await _bootstrap_tenant(client)
    attacker = await _bootstrap_tenant(client)

    failures = []
    for template in OWNER_SESSION_GET_ROUTES:
        path = template.format(ws=victim["workspace_id"])
        res = await client.get(path, headers=attacker["headers"])
        if res.status_code not in (403, 404):
            failures.append((path, res.status_code, res.text[:200]))
    assert not failures, f"owner-session routes leaked to a foreign tenant: {failures}"


@pytest.mark.asyncio
async def test_mh_owner_session_routes_reject_unauthenticated_callers(client):
    victim = await _bootstrap_tenant(client)
    failures = []
    for template in OWNER_SESSION_GET_ROUTES:
        path = template.format(ws=victim["workspace_id"])
        res = await client.get(path)
        if res.status_code not in (401, 403):
            failures.append((path, res.status_code, res.text[:200]))
    assert not failures, f"owner-session routes served anonymous callers: {failures}"


@pytest.mark.asyncio
async def test_mh_review_gate_decision_cannot_cross_workspaces(client):
    """The decision route loads the gate on the owner session. Passing a
    foreign workspace_id in the path must fail on the guard, and a gate id
    from another tenant must not be decidable inside one's own workspace.
    """
    victim = await _bootstrap_tenant(client)
    attacker = await _bootstrap_tenant(client)

    foreign_path = f"/workspaces/{victim['workspace_id']}/review-gates/{uuid.uuid4()}/decision"
    res = await client.post(
        foreign_path,
        headers=attacker["headers"],
        json={"approved": True, "expected_content_version_id": str(uuid.uuid4())},
    )
    assert res.status_code in (403, 404), res.text

    own_path = f"/workspaces/{attacker['workspace_id']}/review-gates/{uuid.uuid4()}/decision"
    res2 = await client.post(
        own_path,
        headers=attacker["headers"],
        json={"approved": True, "expected_content_version_id": str(uuid.uuid4())},
    )
    assert res2.status_code == 404, res2.text


@pytest.mark.asyncio
async def test_mh_content_job_creation_requires_membership(client):
    victim = await _bootstrap_tenant(client)
    attacker = await _bootstrap_tenant(client)
    res = await client.post(
        "/content-jobs",
        headers=attacker["headers"],
        json={"workspace_id": victim["workspace_id"], "topic": "cross tenant probe"},
    )
    assert res.status_code in (403, 404), res.text


@pytest.mark.asyncio
async def test_mf_locked_account_keeps_password_work_timing_equalized(local_auth_on, monkeypatch):
    """A locked known account must not return before the PBKDF work that an
    unknown account performs; otherwise lockout status becomes an account
    existence timing oracle.
    """
    email = f"locked-timing-{uuid.uuid4().hex[:10]}@example.com"
    async with AsyncSessionLocal() as session:
        token = await local_auth.signup(session, email=email, password=STRONG_PASSWORD)
        row = await session.get(LocalAuthCredential, token.user_id)
        assert row is not None
        row.locked_until = datetime.now(UTC) + timedelta(minutes=5)
        await session.commit()

    checked_hashes: list[str] = []

    def _verify(password: str, password_hash: str) -> bool:
        checked_hashes.append(password_hash)
        return False

    monkeypatch.setattr(local_auth, "verify_password", _verify)
    async with AsyncSessionLocal() as session:
        with pytest.raises(local_auth.AuthError) as exc:
            await local_auth.login(session, email=email, password="wrong-password-value")
        assert exc.value.code == "invalid_credentials"

    assert checked_hashes, "locked account must perform password work before failing"

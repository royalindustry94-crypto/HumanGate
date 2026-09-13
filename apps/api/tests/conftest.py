"""Shared fixtures for API tests.

Tests run against a real Postgres (the CI service container / local
docker-compose instance), through the actual `app_runtime` role, after
`alembic upgrade head` — see .github/workflows/ci.yml. This is
deliberate: mocking the DB would mean the RLS tests (the ones that
matter most here) test nothing real.
"""

from __future__ import annotations

import os
import secrets
import time
import uuid

# Force safe local defaults so Replit's injected DATABASE_URL (pointing at the
# managed database) never bleeds into tests. Release audits may explicitly
# select another freshly-created local database through TEST_* variables.
os.environ["DATABASE_URL"] = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql://postgres:postgres@127.0.0.1:5432/content_orchestrator_test",
)
os.environ["APP_DATABASE_URL"] = os.environ.get(
    "TEST_APP_DATABASE_URL",
    "postgresql://app_runtime:app_runtime@127.0.0.1:5432/content_orchestrator_test",
)
os.environ["SUPABASE_JWT_SECRET"] = "test-supabase-jwt-secret-0123456789abcdef"
os.environ["ENVIRONMENT"] = "test"
os.environ["AUTH_MODE"] = "local"

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport
from jwt import encode as jwt_encode
from sqlalchemy import text

from app.core.config import get_settings
from app.db.session import AsyncSessionLocal
from app.main import app

settings = get_settings()


def nontest_jwt_secret() -> str:
    """High-entropy JWT secret for Settings() constructions outside tests.

    Generated at runtime so the suite never commits a production-accepted
    signing credential that secret scanners would treat as live.
    """
    return secrets.token_urlsafe(48)


def make_token(user_id: str | None = None, email: str = "test@example.com") -> str:
    """Mint a Supabase-shaped access token signed with the test secret,
    matching what Supabase Auth would issue (sub, email, aud, exp).
    """
    sub = user_id or str(uuid.uuid4())
    payload = {
        "sub": sub,
        "email": email,
        "aud": settings.supabase_jwt_audience,
        "exp": int(time.time()) + 3600,
        "role": "authenticated",
        "iss": settings.supabase_jwt_issuer,
    }
    return jwt_encode(
        payload, settings.supabase_jwt_secret, algorithm=settings.supabase_jwt_algorithm
    )


@pytest_asyncio.fixture
async def client():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def new_user():
    """Creates an auth.users row (so the profile trigger fires) and
    returns (user_id, bearer_token, auth_headers).
    """
    user_id = str(uuid.uuid4())
    email = f"{user_id}@example.com"

    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :email)"),
            {"id": user_id, "email": email},
        )
        await session.commit()

    token = make_token(user_id=user_id, email=email)
    yield user_id, token, {"Authorization": f"Bearer {token}"}


@pytest.fixture(autouse=True)
def _isolate_test_db_state():
    # Each test creates its own users/workspaces via unique uuids, so no
    # blanket truncation is needed between tests — this fixture exists
    # as the single place to add cleanup if that assumption ever breaks.
    yield

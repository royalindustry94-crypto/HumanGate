"""P0-2: runtime role rotation after the local-default migration password."""

from __future__ import annotations

import os
import subprocess
import uuid
from pathlib import Path

import asyncpg
import pytest
from sqlalchemy.engine.url import make_url

from app.core.config import CANONICAL_RUNTIME_ROLE
from app.db.runtime_role import (
    ROLE_NAME_RE,
    RuntimeRoleError,
    parse_runtime_credentials,
    provision_runtime_role,
)

_API_ROOT = Path(__file__).resolve().parents[1]
_LOCAL_RUNTIME_PASSWORD = "app_runtime"


def test_parse_runtime_credentials_rejects_known_default_password():
    with pytest.raises(RuntimeRoleError, match="known default"):
        parse_runtime_credentials(
            "postgresql://app_runtime:app_runtime@127.0.0.1:5432/content_orchestrator_test"
        )


def test_parse_runtime_credentials_rejects_unsafe_role_name():
    with pytest.raises(RuntimeRoleError, match="safe Postgres identifier"):
        parse_runtime_credentials(
            "postgresql://app_runtime;drop+role+postgres:rotated@127.0.0.1:5432/db"
        )


def test_parse_runtime_credentials_rejects_non_canonical_role():
    with pytest.raises(RuntimeRoleError, match="canonical"):
        parse_runtime_credentials(
            "postgresql://other_runtime:rotated-runtime-secret@127.0.0.1:5432/db"
        )


def test_parse_runtime_credentials_accepts_rotated_secret():
    user, password = parse_runtime_credentials(
        "postgresql://app_runtime:rotated-runtime-secret@127.0.0.1:5432/db"
    )
    assert user == CANONICAL_RUNTIME_ROLE
    assert password == "rotated-runtime-secret"
    assert ROLE_NAME_RE.fullmatch(user)


async def test_provision_runtime_role_rejects_non_canonical_name_before_connect():
    async def _should_not_connect(*_args, **_kwargs):
        raise AssertionError("must not open a database connection for a non-canonical role")

    with pytest.raises(RuntimeRoleError, match="canonical"):
        await provision_runtime_role(
            owner_dsn="postgresql://postgres:postgres@127.0.0.1:5432/db",
            runtime_user="other_runtime",
            runtime_password="rotated-runtime-secret",
            connect=_should_not_connect,
        )


async def test_provision_runtime_role_rejects_unsafe_name_before_connect():
    async def _should_not_connect(*_args, **_kwargs):
        raise AssertionError("must not open a database connection for an unsafe role")

    with pytest.raises(RuntimeRoleError, match="safe Postgres identifier"):
        await provision_runtime_role(
            owner_dsn="postgresql://postgres:postgres@127.0.0.1:5432/db",
            runtime_user="app_runtime; DROP ROLE postgres",
            runtime_password="rotated-runtime-secret",
            connect=_should_not_connect,
        )


async def test_provisioned_app_runtime_can_run_rls_bound_query():
    """Migrations + entrypoint rotation must yield a usable app_runtime role.

    A fresh staging database creates app_runtime with the local default
    password, then the entrypoint rotates it. The configured role must
    retain FORCE-RLS application grants (not merely CONNECT) and must
    not have owner/BYPASSRLS privileges.
    """
    owner_dsn = os.environ["DATABASE_URL"]
    owner_url = make_url(owner_dsn)
    runtime_kwargs = {
        "host": owner_url.host,
        "port": owner_url.port or 5432,
        "database": owner_url.database,
        "user": CANONICAL_RUNTIME_ROLE,
    }
    rotated = f"rotated-{uuid.uuid4().hex}"
    user_id = uuid.uuid4()
    workspace_id = uuid.uuid4()
    membership_id = uuid.uuid4()
    email = f"{user_id}@example.com"

    subprocess.check_call(["alembic", "upgrade", "head"], cwd=_API_ROOT)

    owner = await asyncpg.connect(owner_dsn)
    try:
        await owner.execute(
            "INSERT INTO auth.users (id, email) VALUES ($1, $2)",
            user_id,
            email,
        )
        await owner.execute(
            "INSERT INTO profiles (id, email) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING",
            user_id,
            email,
        )
        await owner.execute(
            "INSERT INTO workspaces (id, name, created_by) VALUES ($1, $2, $3)",
            workspace_id,
            f"p02-{user_id}",
            user_id,
        )
        await owner.execute(
            "INSERT INTO workspace_memberships (id, workspace_id, user_id, role) "
            "VALUES ($1, $2, $3, 'admin')",
            membership_id,
            workspace_id,
            user_id,
        )

        await provision_runtime_role(
            owner_dsn=owner_dsn,
            runtime_user=CANONICAL_RUNTIME_ROLE,
            runtime_password=rotated,
        )

        with pytest.raises(asyncpg.InvalidPasswordError):
            await asyncpg.connect(**runtime_kwargs, password=_LOCAL_RUNTIME_PASSWORD)

        runtime = await asyncpg.connect(**runtime_kwargs, password=rotated)
        try:
            attrs = await runtime.fetchrow(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = $1",
                CANONICAL_RUNTIME_ROLE,
            )
            assert attrs is not None
            assert attrs["rolsuper"] is False
            assert attrs["rolbypassrls"] is False

            hidden = await runtime.fetchval(
                "SELECT count(*) FROM workspaces WHERE id = $1",
                workspace_id,
            )
            assert hidden == 0

            async with runtime.transaction():
                await runtime.execute(
                    "SELECT set_config('request.jwt.claim.sub', $1, true)",
                    str(user_id),
                )
                visible = await runtime.fetchval(
                    "SELECT count(*) FROM workspaces WHERE id = $1",
                    workspace_id,
                )
                assert visible == 1
        finally:
            await runtime.close()

        owner_seen = await owner.fetchval(
            "SELECT count(*) FROM workspaces WHERE id = $1",
            workspace_id,
        )
        assert owner_seen == 1
    finally:
        restore_sql = await owner.fetchval(
            "SELECT format('ALTER ROLE %I PASSWORD %L', $1::text, $2::text)",
            CANONICAL_RUNTIME_ROLE,
            _LOCAL_RUNTIME_PASSWORD,
        )
        await owner.execute(restore_sql)
        await owner.execute(
            "DELETE FROM workspace_memberships WHERE id = $1",
            membership_id,
        )
        await owner.execute("DELETE FROM workspaces WHERE id = $1", workspace_id)
        await owner.execute("DELETE FROM profiles WHERE id = $1", user_id)
        await owner.execute("DELETE FROM auth.users WHERE id = $1", user_id)
        await owner.close()

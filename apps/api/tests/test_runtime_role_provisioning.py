"""P0-2: runtime role rotation after the local-default migration password."""

from __future__ import annotations

import os
import uuid

import asyncpg
import pytest
from sqlalchemy.engine.url import make_url

from app.db.runtime_role import (
    ROLE_NAME_RE,
    RuntimeRoleError,
    parse_runtime_credentials,
    provision_runtime_role,
)


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


def test_parse_runtime_credentials_accepts_rotated_secret():
    user, password = parse_runtime_credentials(
        "postgresql://app_runtime:rotated-runtime-secret@127.0.0.1:5432/db"
    )
    assert user == "app_runtime"
    assert password == "rotated-runtime-secret"
    assert ROLE_NAME_RE.fullmatch(user)


async def test_provision_runtime_role_rotates_migration_default_password():
    """Simulate 0001 creating a role with password `app_runtime`, then the
    staging entrypoint rotating it to APP_RUNTIME_PASSWORD. A fresh
    database must accept the rotated runtime connection and reject the
    known default afterward.
    """
    owner_dsn = os.environ["DATABASE_URL"]
    owner_url = make_url(owner_dsn)
    role = f"p02_rt_{uuid.uuid4().hex[:16]}"
    assert ROLE_NAME_RE.fullmatch(role)
    rotated = f"rotated-{uuid.uuid4().hex}"
    runtime_kwargs = {
        "host": owner_url.host,
        "port": owner_url.port or 5432,
        "database": owner_url.database,
        "user": role,
    }
    owner = await asyncpg.connect(owner_dsn)
    try:
        create_sql = await owner.fetchval(
            "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER "
            "NOBYPASSRLS NOCREATEROLE NOCREATEDB', $1::text, $2::text)",
            role,
            "app_runtime",
        )
        await owner.execute(create_sql)
        default_conn = await asyncpg.connect(**runtime_kwargs, password="app_runtime")
        await default_conn.close()

        await provision_runtime_role(
            owner_dsn=owner_dsn,
            runtime_user=role,
            runtime_password=rotated,
        )

        with pytest.raises(asyncpg.InvalidPasswordError):
            await asyncpg.connect(**runtime_kwargs, password="app_runtime")

        rotated_conn = await asyncpg.connect(**runtime_kwargs, password=rotated)
        who = await rotated_conn.fetchval("SELECT current_user")
        await rotated_conn.close()
        assert who == role
    finally:
        exists = await owner.fetchval(
            "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)",
            role,
        )
        if exists:
            database = owner_url.database
            if database:
                revoke_sql = await owner.fetchval(
                    "SELECT format('REVOKE CONNECT ON DATABASE %I FROM %I', "
                    "$1::text, $2::text)",
                    database,
                    role,
                )
                await owner.execute(revoke_sql)
            drop_sql = await owner.fetchval(
                "SELECT format('DROP ROLE IF EXISTS %I', $1::text)",
                role,
            )
            await owner.execute(drop_sql)
        await owner.close()


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

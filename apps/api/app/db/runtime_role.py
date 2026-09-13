"""Provision/rotate the least-privileged runtime Postgres role.

Migration 0001 creates ``app_runtime`` with the local-only password
``app_runtime`` when the role is missing. Staging and every other
non-local environment must connect with a rotated secret. This module
runs from the API entrypoint after ``alembic upgrade head`` and applies
that password using PostgreSQL ``format(%I, %L)`` so the role name and
secret are never concatenated into raw SQL.

The role name must match ``ROLE_NAME_RE`` before any catalog statement
runs. Callers pass already-parsed credentials; this module does not read
secrets from argv.
"""

from __future__ import annotations

import asyncio
import os
import re
from collections.abc import Awaitable, Callable

import asyncpg
from sqlalchemy.engine.url import make_url

ROLE_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,62}$")
_KNOWN_DEFAULT_PASSWORDS = frozenset({"postgres", "app_runtime"})


class RuntimeRoleError(ValueError):
    """Fail-closed provisioning / rotation error."""


def parse_runtime_credentials(dsn: str) -> tuple[str, str]:
    """Return ``(username, password)`` from a Postgres DSN."""
    url = make_url(dsn.replace("postgresql+asyncpg://", "postgresql://", 1))
    username = (url.username or "").strip()
    password = url.password if url.password is not None else ""
    if not username:
        raise RuntimeRoleError("APP_DATABASE_URL is missing a role name")
    if not ROLE_NAME_RE.fullmatch(username):
        raise RuntimeRoleError("APP_DATABASE_URL role name is not a safe Postgres identifier")
    if not password:
        raise RuntimeRoleError("APP_DATABASE_URL is missing a password")
    if password.strip().lower() in _KNOWN_DEFAULT_PASSWORDS:
        raise RuntimeRoleError("APP_DATABASE_URL still uses a known default database password")
    return username, password


async def _formatted_sql(
    conn: asyncpg.Connection,
    template: str,
    *params: str,
) -> str:
    """Ask Postgres to quote identifiers/literals; never interpolate locally."""
    value_placeholders = ", ".join(f"${index}::text" for index in range(2, len(params) + 2))
    statement = await conn.fetchval(
        f"SELECT format($1, {value_placeholders})",
        template,
        *params,
    )
    if not statement or not isinstance(statement, str):
        raise RuntimeRoleError("Postgres format() did not return a statement")
    return statement


async def provision_runtime_role(
    *,
    owner_dsn: str,
    runtime_user: str,
    runtime_password: str,
    connect: Callable[..., Awaitable[asyncpg.Connection]] | None = None,
) -> None:
    """Create or rotate ``runtime_user`` using the owner connection."""
    if not ROLE_NAME_RE.fullmatch(runtime_user):
        raise RuntimeRoleError("runtime role name is not a safe Postgres identifier")
    if not runtime_password:
        raise RuntimeRoleError("runtime role password is required")
    if runtime_password.strip().lower() in _KNOWN_DEFAULT_PASSWORDS:
        raise RuntimeRoleError(
            "runtime role password is a known default; rotate it before starting"
        )

    connect_fn = connect or asyncpg.connect
    owner_url = owner_dsn.replace("postgresql+asyncpg://", "postgresql://", 1)
    conn = await connect_fn(owner_url)
    try:
        exists = await conn.fetchval(
            "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)",
            runtime_user,
        )
        if not exists:
            create_sql = await _formatted_sql(
                conn,
                "CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB",
                runtime_user,
                runtime_password,
            )
            await conn.execute(create_sql)
        alter_sql = await _formatted_sql(
            conn,
            "ALTER ROLE %I WITH LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB PASSWORD %L",
            runtime_user,
            runtime_password,
        )
        await conn.execute(alter_sql)
        database = await conn.fetchval("SELECT current_database()")
        if not database:
            raise RuntimeRoleError("owner connection has no current database")
        grant_sql = await _formatted_sql(
            conn,
            "GRANT CONNECT ON DATABASE %I TO %I",
            str(database),
            runtime_user,
        )
        await conn.execute(grant_sql)
    finally:
        await conn.close()


async def provision_from_env() -> None:
    owner_dsn = os.environ.get("DATABASE_URL", "").strip()
    runtime_dsn = os.environ.get("APP_DATABASE_URL", "").strip()
    if not owner_dsn or not runtime_dsn:
        raise RuntimeRoleError(
            "DATABASE_URL and APP_DATABASE_URL are required to provision the runtime role"
        )
    runtime_user, runtime_password = parse_runtime_credentials(runtime_dsn)
    owner_url = make_url(owner_dsn.replace("postgresql+asyncpg://", "postgresql://", 1))
    owner_user = (owner_url.username or "").strip()
    if owner_user.casefold() == runtime_user.casefold():
        raise RuntimeRoleError("runtime role must be distinct from the owner/migration role")
    await provision_runtime_role(
        owner_dsn=owner_dsn,
        runtime_user=runtime_user,
        runtime_password=runtime_password,
    )


def main() -> None:
    asyncio.run(provision_from_env())


if __name__ == "__main__":
    main()

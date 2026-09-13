"""Supabase JWT verification.

FastAPI verifies the JWT Supabase Auth issued; it never issues, refreshes,
or stores tokens itself. See docs/milestone-2-identity-and-access.md §1.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncGenerator
from dataclasses import dataclass

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jwt import InvalidTokenError
from jwt import decode as jwt_decode
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.db.session import rls_scoped_session

settings = get_settings()

_bearer_scheme = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class AuthenticatedUser:
    """Verified identity derived from a Supabase JWT's claims. Not a
    database row — callers that need the profile row query for it
    separately, keyed by `id`.
    """

    id: str  # Supabase auth.users.id / profiles.id (uuid as string)
    email: str | None


def _required_jwt_issuer() -> str:
    issuer = (settings.supabase_jwt_issuer or "").strip()
    if not issuer:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return issuer


def _decode_supabase_jwt(token: str) -> dict:
    # P0-1 Codex CHANGES_REQUESTED: issuer verification is unconditional
    # for accepted tokens. Settings already fail closed for non-test
    # Supabase mode without SUPABASE_JWT_ISSUER; this guard covers a
    # process that somehow loaded without one.
    try:
        return jwt_decode(
            token,
            settings.supabase_jwt_secret,
            algorithms=[settings.supabase_jwt_algorithm],
            audience=settings.supabase_jwt_audience,
            issuer=_required_jwt_issuer(),
        )
    except InvalidTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> AuthenticatedUser:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    claims = _decode_supabase_jwt(credentials.credentials)

    sub = claims.get("sub")
    if not sub:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="token missing 'sub' claim",
        )
    # P0-1 (2026-09-13 audit): `sub` used to be accepted as any non-empty
    # string. Every real user id in this system (Supabase auth.users.id /
    # profiles.id, minted by both AUTH_MODE=supabase and =local) is a UUID —
    # requiring that shape here rejects a token forged with an arbitrary
    # `sub` before it ever reaches a workspace-membership lookup.
    try:
        uuid.UUID(str(sub))
    except (ValueError, AttributeError, TypeError) as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="token 'sub' claim must be a valid UUID",
        ) from exc

    return AuthenticatedUser(id=sub, email=claims.get("email"))


async def get_current_session(
    user: AuthenticatedUser = Depends(get_current_user),
) -> AsyncGenerator[AsyncSession, None]:
    """RLS-scoped DB session for the authenticated caller. Every route
    that touches application data should depend on this (or on
    `app.core.authorization`'s guards, which depend on this) rather than
    on `app.db.session.get_db` directly.
    """
    async with rls_scoped_session(user.id) as session:
        yield session

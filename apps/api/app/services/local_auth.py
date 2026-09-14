"""Local email/password auth that mints Supabase-shaped JWTs.

Used when AUTH_MODE=local (Private Beta / staging without an external
Supabase project). Production may set AUTH_MODE=supabase and disable
these routes — JWT verification remains identical either way.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from jwt import encode as jwt_encode
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import LOCAL_JWT_ISSUER, get_settings
from app.models.local_auth import LocalAuthCredential

# M-F brute-force controls. Local auth has no external identity provider in
# front of it, so the API is the only throttle. State is persisted on the
# credential row (migration 0036) rather than in process memory so the limit
# holds across replicas and restarts.
MAX_FAILED_ATTEMPTS = 10
LOCKOUT_SECONDS = 900
FAILURE_WINDOW_SECONDS = 900
MIN_PASSWORD_LENGTH = 12
# A dummy hash of the same shape/cost as a real one. Verified when the email is
# unknown so a failed login costs the same time either way and does not leak
# account existence through response latency.
_TIMING_EQUALIZER_HASH = (
    "pbkdf2_sha256$200000$"
    "0000000000000000000000000000000000000000000000000000000000000000$"
    "0000000000000000000000000000000000000000000000000000000000000000"
)


class AuthError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class AuthToken:
    access_token: str
    token_type: str
    expires_in: int
    user_id: uuid.UUID
    email: str


def hash_password(password: str) -> str:
    """PBKDF2-SHA256 password hash (self-contained; no bcrypt backend drift)."""
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), 200_000
    ).hex()
    return f"pbkdf2_sha256$200000${salt}${digest}"


def verify_password(password: str, password_hash: str) -> bool:
    try:
        algo, rounds_s, salt, digest = password_hash.split("$", 3)
    except ValueError:
        return False
    if algo != "pbkdf2_sha256":
        return False
    try:
        rounds = int(rounds_s)
    except ValueError:
        return False
    candidate = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), rounds
    ).hex()
    return hmac.compare_digest(candidate, digest)


def mint_access_token(*, user_id: uuid.UUID, email: str, expires_in: int = 3600) -> str:
    settings = get_settings()
    payload = {
        "sub": str(user_id),
        "email": email,
        "aud": settings.supabase_jwt_audience,
        "exp": int(time.time()) + expires_in,
        "role": "authenticated",
        "iss": LOCAL_JWT_ISSUER,
    }
    return jwt_encode(
        payload,
        settings.supabase_jwt_secret,
        algorithm=settings.supabase_jwt_algorithm,
    )


async def signup(
    session: AsyncSession, *, email: str, password: str, full_name: str | None = None
) -> AuthToken:
    settings = get_settings()
    if settings.auth_mode != "local":
        raise AuthError("auth_disabled", "local signup is disabled when AUTH_MODE!=local")
    normalized = email.strip().lower()
    if len(password) < MIN_PASSWORD_LENGTH:
        raise AuthError(
            "weak_password",
            f"password must be at least {MIN_PASSWORD_LENGTH} characters",
        )

    existing = (
        await session.execute(
            select(LocalAuthCredential).where(LocalAuthCredential.email == normalized)
        )
    ).scalar_one_or_none()
    if existing is not None:
        raise AuthError("email_taken", "an account with this email already exists")

    user_id = uuid.uuid4()
    # AUTH_MODE=supabase relies on a database trigger on `auth.users` to
    # create the matching `profiles` row (see app/models/profile.py). Local
    # auth has no such trigger firing, so it inserts `profiles` directly
    # below — but writing to Supabase's real `auth.users` table itself has
    # no downstream reader in local mode (no FK references it; only
    # Supabase's own internal auth.* tables do) and requires ownership-level
    # grants on a schema this app does not otherwise touch. Skipped.
    await session.execute(
        text(
            "INSERT INTO profiles (id, email, full_name) VALUES (:id, :email, :full_name) "
            "ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email"
        ),
        {"id": str(user_id), "email": normalized, "full_name": full_name},
    )
    session.add(
        LocalAuthCredential(
            user_id=user_id,
            email=normalized,
            password_hash=hash_password(password),
        )
    )
    await session.flush()
    token = mint_access_token(user_id=user_id, email=normalized)
    return AuthToken(
        access_token=token,
        token_type="bearer",  # noqa: S106
        expires_in=3600,
        user_id=user_id,
        email=normalized,
    )


async def login(session: AsyncSession, *, email: str, password: str) -> AuthToken:
    settings = get_settings()
    if settings.auth_mode != "local":
        raise AuthError("auth_disabled", "local login is disabled when AUTH_MODE!=local")
    normalized = email.strip().lower()
    now = datetime.now(UTC)
    # Lock the credential row so concurrent guesses cannot race the counter.
    row = (
        await session.execute(
            select(LocalAuthCredential)
            .where(LocalAuthCredential.email == normalized)
            .with_for_update()
        )
    ).scalar_one_or_none()

    if row is None:
        # Equalize timing against the known-account path; same generic error.
        verify_password(password, _TIMING_EQUALIZER_HASH)
        raise AuthError("invalid_credentials", "invalid email or password")

    if row.locked_until is not None and row.locked_until > now:
        # Equalize against both unknown-account and ordinary wrong-password
        # failures. Returning before PBKDF work would turn a locked known
        # address into a measurable account-existence timing oracle.
        verify_password(password, row.password_hash)
        # Generic message: never disclose whether the account exists or why.
        raise AuthError(
            "invalid_credentials",
            "invalid email or password",
        )

    if not verify_password(password, row.password_hash):
        stale = row.last_failed_at is None or (now - row.last_failed_at) > timedelta(
            seconds=FAILURE_WINDOW_SECONDS
        )
        row.failed_attempts = 1 if stale else (row.failed_attempts or 0) + 1
        row.last_failed_at = now
        if row.failed_attempts >= MAX_FAILED_ATTEMPTS:
            row.locked_until = now + timedelta(seconds=LOCKOUT_SECONDS)
        await session.flush()
        raise AuthError("invalid_credentials", "invalid email or password")

    # Success clears the counter and any expired lock.
    row.failed_attempts = 0
    row.last_failed_at = None
    row.locked_until = None
    await session.flush()
    token = mint_access_token(user_id=row.user_id, email=row.email)
    return AuthToken(
        access_token=token,
        token_type="bearer",  # noqa: S106
        expires_in=3600,
        user_id=row.user_id,
        email=row.email,
    )

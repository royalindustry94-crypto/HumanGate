"""Per-IP request rate limiting.

`InMemoryRateLimiter` is the original fixed-window counter keyed by an
arbitrary string (here, client IP, optionally namespaced per limit
tier). It remains useful for direct unit tests and truly single-process
contexts, but live API traffic now uses `PostgresRateLimiter` so Vercel's
stateless multi-instance runtime shares one budget across replicas.

The in-memory limiter is not thread-safe in the general sense, but
doesn't need to be: Starlette middleware runs on one asyncio event loop
per process, so dict access here is never interrupted mid-mutation by
another concurrent request.
"""

from __future__ import annotations

import logging
import time
from collections import OrderedDict
from collections.abc import Awaitable
from dataclasses import dataclass
from inspect import isawaitable
from typing import Protocol

from fastapi import Request
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse
from starlette.types import ASGIApp

from app.core.audit import audit
from app.db.session import RuntimeSessionLocal

logger = logging.getLogger(__name__)


@dataclass
class _Window:
    started_at: float
    count: int


type RateLimitCheckResult = tuple[bool, float]


class RateLimiter(Protocol):
    def check(self, key: str) -> RateLimitCheckResult | Awaitable[RateLimitCheckResult]: ...


# Ceiling on simultaneously tracked keys. At ~100 bytes per entry this caps
# the limiter's own footprint in the low tens of MB even while every slot is
# live. Sized well above any plausible count of distinct client IPs in one
# window for the current single-process topology.
DEFAULT_MAX_TRACKED_KEYS = 100_000
DEFAULT_CLEANUP_BATCH_SIZE = 1_000

_RATE_LIMIT_UPSERT_SQL = text(
    """
    WITH now_cte AS (
        SELECT clock_timestamp() AS now
    )
    INSERT INTO request_rate_limits AS rl (
        bucket_key,
        window_started_at,
        expires_at,
        count,
        updated_at
    )
    SELECT
        :bucket_key,
        now_cte.now,
        now_cte.now + make_interval(secs => CAST(:window_seconds AS double precision)),
        1,
        now_cte.now
    FROM now_cte
    ON CONFLICT (bucket_key) DO UPDATE
    SET
        count = CASE
            WHEN rl.expires_at <= EXCLUDED.updated_at THEN 1
            ELSE rl.count + 1
        END,
        window_started_at = CASE
            WHEN rl.expires_at <= EXCLUDED.updated_at THEN EXCLUDED.window_started_at
            ELSE rl.window_started_at
        END,
        expires_at = CASE
            WHEN rl.expires_at <= EXCLUDED.updated_at THEN EXCLUDED.expires_at
            ELSE rl.expires_at
        END,
        updated_at = EXCLUDED.updated_at
    RETURNING
        count,
        EXTRACT(EPOCH FROM expires_at - updated_at)::double precision AS retry_after_seconds
    """
)

_RATE_LIMIT_CLEANUP_SQL = text(
    """
    WITH doomed AS (
        SELECT bucket_key
        FROM request_rate_limits
        WHERE expires_at <= clock_timestamp()
        ORDER BY expires_at
        LIMIT :batch_size
    )
    DELETE FROM request_rate_limits
    WHERE bucket_key IN (SELECT bucket_key FROM doomed)
    """
)
_RATE_LIMIT_CLEANUP_LOCK_SQL = text("SELECT pg_try_advisory_xact_lock(:lock_key)")


class InMemoryRateLimiter:
    """Fixed-window request counter. One window per key at a time; a key's
    window resets (count back to 1) once `window_seconds` has elapsed
    since that key's window started.

    The window map is bounded (audit H-2). It previously grew for the life of
    the process: an entry was created on a key's first request and only ever
    overwritten if that same key came back, so traffic across many source IPs
    -- ordinary churn, or an attacker rotating addresses -- grew it without
    limit, turning the limiter into its own memory-pressure vector. Expired
    windows are now swept at most once per window (amortized O(1) per
    request), with a hard key cap as the backstop.
    """

    def __init__(
        self,
        *,
        max_requests: int,
        window_seconds: float,
        max_tracked_keys: int = DEFAULT_MAX_TRACKED_KEYS,
    ) -> None:
        if max_requests < 1:
            raise ValueError("max_requests must be >= 1")
        if window_seconds <= 0:
            raise ValueError("window_seconds must be > 0")
        if max_tracked_keys < 1:
            raise ValueError("max_tracked_keys must be >= 1")
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.max_tracked_keys = max_tracked_keys
        # Ordered by window start: new keys append, a reset key is moved to the
        # end, so the oldest window is always the leftmost entry and eviction
        # is O(1) via popitem(last=False).
        self._windows: OrderedDict[str, _Window] = OrderedDict()
        self._next_sweep_at = 0.0

    @property
    def tracked_keys(self) -> int:
        """Live window count — the quantity H-2 requires stay bounded."""
        return len(self._windows)

    def _sweep(self, now: float) -> None:
        """Drop windows that have already expired.

        O(K), but rate-limited to once per window, so the amortized cost per
        request stays constant.
        """
        cutoff = now - self.window_seconds
        self._windows = OrderedDict(
            (key, window) for key, window in self._windows.items() if window.started_at > cutoff
        )
        self._next_sweep_at = now + self.window_seconds

    def _make_room(self, now: float) -> None:
        """Bound the map before admitting a new key.

        The periodic sweep reclaims expired windows. If every tracked window is
        still live we evict the oldest, which is O(1) on the ordered map: an
        evicted key simply gets a fresh budget, the safe direction to fail --
        the limiter must not start rejecting legitimate traffic because of its
        own bookkeeping.

        The sweep is deliberately NOT triggered by hitting the cap. Doing so
        rebuilt the whole map, and the overflow path then sorted every tracked
        key, on *every* admission once full -- O(K log K) per request against
        exactly the rotating-source-IP pattern this cap exists to survive.
        """
        if now >= self._next_sweep_at:
            self._sweep(now)
        while len(self._windows) >= self.max_tracked_keys:
            self._windows.popitem(last=False)

    def check(self, key: str, *, now: float | None = None) -> tuple[bool, float]:
        """Returns (allowed, retry_after_seconds). retry_after_seconds is
        0 when allowed; otherwise the seconds remaining until this key's
        window resets.
        """
        now = time.monotonic() if now is None else now
        window = self._windows.get(key)
        if window is None or (now - window.started_at) >= self.window_seconds:
            # Only this branch grows the map, so it is the only one that needs
            # to make room first.
            self._make_room(now)
            self._windows[key] = _Window(started_at=now, count=1)
            self._windows.move_to_end(key)
            return True, 0.0
        if window.count < self.max_requests:
            window.count += 1
            return True, 0.0
        retry_after = self.window_seconds - (now - window.started_at)
        return False, max(retry_after, 0.0)


class PostgresRateLimiter:
    """Fixed-window limiter with counters shared through PostgreSQL."""

    def __init__(
        self,
        *,
        max_requests: int,
        window_seconds: float,
        session_factory: async_sessionmaker[AsyncSession] = RuntimeSessionLocal,
        cleanup_interval_seconds: float | None = None,
        cleanup_batch_size: int = DEFAULT_CLEANUP_BATCH_SIZE,
    ) -> None:
        if max_requests < 1:
            raise ValueError("max_requests must be >= 1")
        if window_seconds <= 0:
            raise ValueError("window_seconds must be > 0")
        if cleanup_interval_seconds is not None and cleanup_interval_seconds <= 0:
            raise ValueError("cleanup_interval_seconds must be > 0")
        if cleanup_batch_size < 1:
            raise ValueError("cleanup_batch_size must be >= 1")
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._session_factory = session_factory
        self._cleanup_interval_seconds = cleanup_interval_seconds or window_seconds
        self._cleanup_batch_size = cleanup_batch_size
        self._next_cleanup_at = time.monotonic() + self._cleanup_interval_seconds

    async def check(self, key: str) -> tuple[bool, float]:
        now = time.monotonic()
        async with self._session_factory() as session:
            try:
                row = (
                    await session.execute(
                        _RATE_LIMIT_UPSERT_SQL,
                        {"bucket_key": key, "window_seconds": self.window_seconds},
                    )
                ).one()
                if now >= self._next_cleanup_at:
                    if await session.scalar(_RATE_LIMIT_CLEANUP_LOCK_SQL, {"lock_key": 9058}):
                        await session.execute(
                            _RATE_LIMIT_CLEANUP_SQL,
                            {"batch_size": self._cleanup_batch_size},
                        )
                        self._next_cleanup_at = now + self._cleanup_interval_seconds
                    else:
                        self._next_cleanup_at = now + min(1.0, self._cleanup_interval_seconds)
                await session.commit()
            except Exception:
                await session.rollback()
                raise

        retry_after = max(float(row.retry_after_seconds or 0.0), 0.0)
        allowed = int(row._mapping["count"]) <= self.max_requests
        return allowed, 0.0 if allowed else retry_after


def _client_ip(request: Request) -> str:
    # Deliberately not X-Forwarded-For: trusting a client-supplied header
    # here would let any caller bypass the limit by varying it. Revisit
    # only alongside a documented trusted-proxy deployment.
    client = request.client
    return client.host if client is not None else "unknown"


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Global per-IP limit for all routes, plus a stricter per-IP limit for
    `auth_path_prefixes` (credential-stuffing/signup-spam surface not
    covered by the existing per-credential lockout in `local_auth.py`).
    `exempt_paths` (health checks, metrics) are never limited.
    """

    def __init__(
        self,
        app: ASGIApp,
        *,
        global_limiter: RateLimiter,
        auth_limiter: RateLimiter,
        auth_path_prefixes: tuple[str, ...] = ("/auth/",),
        exempt_paths: frozenset[str] = frozenset(
            {"/health/live", "/health/ready", "/health/automation", "/metrics"}
        ),
    ) -> None:
        super().__init__(app)
        self._global_limiter = global_limiter
        self._auth_limiter = auth_limiter
        self._auth_path_prefixes = auth_path_prefixes
        self._exempt_paths = exempt_paths

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path in self._exempt_paths:
            return await call_next(request)

        ip = _client_ip(request)
        is_auth_path = any(path.startswith(prefix) for prefix in self._auth_path_prefixes)
        limiter = self._auth_limiter if is_auth_path else self._global_limiter
        tier = "auth" if is_auth_path else "global"

        try:
            result = limiter.check(f"{tier}:{ip}")
            if isawaitable(result):
                allowed, retry_after = await result
            else:
                allowed, retry_after = result
        except Exception:
            # The limiter is a security control, so a broken database/credential/migration
            # must fail CLOSED. Returning a controlled 503 is materially better than leaking
            # an implementation traceback as a generic 500 and makes the failure diagnosable.
            logger.exception(
                "rate_limit_backend_unavailable",
                extra={"request_path": path, "rate_limit_tier": tier},
            )
            return JSONResponse(
                status_code=503,
                content={
                    "detail": "service temporarily unavailable",
                    "error_code": "rate_limit_backend_unavailable",
                },
                headers={"Retry-After": "5"},
            )
        if not allowed:
            audit(
                request,
                "rate_limit_exceeded",
                client_ip=ip,
                path=path,
                tier=tier,
            )
            return JSONResponse(
                status_code=429,
                content={"detail": "rate limit exceeded"},
                headers={"Retry-After": str(int(retry_after) + 1)},
            )
        return await call_next(request)

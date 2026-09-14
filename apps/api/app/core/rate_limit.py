"""Dependency-free per-IP request rate limiting (TD-034).

`InMemoryRateLimiter` is a fixed-window counter keyed by an arbitrary
string (here, client IP, optionally namespaced per limit tier). It is
process-local by design — see `docs/work-packages/WP-P1-010-rate-limiting.md`
for why a shared/Redis-backed limiter isn't warranted by the current
single-process deployment topology.

Not thread-safe in the general sense, but doesn't need to be: Starlette
middleware runs on one asyncio event loop per process, so dict access
here is never interrupted mid-mutation by another concurrent request.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse
from starlette.types import ASGIApp

from app.core.audit import audit


@dataclass
class _Window:
    started_at: float
    count: int


# Ceiling on simultaneously tracked keys. At ~100 bytes per entry this caps
# the limiter's own footprint in the low tens of MB even while every slot is
# live. Sized well above any plausible count of distinct client IPs in one
# window for the current single-process topology.
DEFAULT_MAX_TRACKED_KEYS = 100_000


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
        self._windows: dict[str, _Window] = {}
        self._next_sweep_at = 0.0

    @property
    def tracked_keys(self) -> int:
        """Live window count — the quantity H-2 requires stay bounded."""
        return len(self._windows)

    def _sweep(self, now: float) -> None:
        """Drop windows that have already expired."""
        cutoff = now - self.window_seconds
        self._windows = {
            key: window for key, window in self._windows.items() if window.started_at > cutoff
        }
        self._next_sweep_at = now + self.window_seconds

    def _make_room(self, now: float) -> None:
        """Bound the map before admitting a new key.

        Sweeping runs at most once per window so the amortized cost per
        request stays constant. If every tracked window is still live we evict
        the oldest ones: an evicted key simply gets a fresh budget, which is
        the safe direction to fail — the limiter must not start rejecting
        legitimate traffic because of its own bookkeeping.
        """
        if now >= self._next_sweep_at or len(self._windows) >= self.max_tracked_keys:
            self._sweep(now)
        overflow = len(self._windows) - self.max_tracked_keys + 1
        if overflow > 0:
            oldest = sorted(self._windows.items(), key=lambda item: item[1].started_at)
            for key, _ in oldest[:overflow]:
                del self._windows[key]

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
            return True, 0.0
        if window.count < self.max_requests:
            window.count += 1
            return True, 0.0
        retry_after = self.window_seconds - (now - window.started_at)
        return False, max(retry_after, 0.0)


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
        global_limiter: InMemoryRateLimiter,
        auth_limiter: InMemoryRateLimiter,
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

        allowed, retry_after = limiter.check(f"{tier}:{ip}")
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

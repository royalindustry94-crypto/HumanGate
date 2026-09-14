"""TD-034 / P1-010: in-process per-IP rate limiting.

The limiter is unit-tested directly here, plus one standalone-app
integration test that force-attaches the middleware (the shared `app`
singleton never enables it under ENVIRONMENT=test — see
docs/work-packages/WP-P1-010-rate-limiting.md — so these tests build
their own tiny app rather than reusing the `client` fixture).
"""

from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI
from httpx import ASGITransport

from app.core.rate_limit import InMemoryRateLimiter, RateLimitMiddleware


def test_allows_up_to_the_limit_then_blocks():
    limiter = InMemoryRateLimiter(max_requests=3, window_seconds=60)
    for _ in range(3):
        allowed, retry_after = limiter.check("k")
        assert allowed
        assert retry_after == 0.0
    allowed, retry_after = limiter.check("k")
    assert not allowed
    assert retry_after > 0.0


def test_window_resets_after_expiry():
    limiter = InMemoryRateLimiter(max_requests=1, window_seconds=10)
    assert limiter.check("k", now=0.0) == (True, 0.0)
    allowed, _ = limiter.check("k", now=5.0)
    assert not allowed
    allowed, retry_after = limiter.check("k", now=10.0)
    assert allowed
    assert retry_after == 0.0


def test_keys_are_independent():
    limiter = InMemoryRateLimiter(max_requests=1, window_seconds=60)
    assert limiter.check("a") == (True, 0.0)
    allowed, _ = limiter.check("a")
    assert not allowed
    # A different key has its own budget, unaffected by "a" being exhausted.
    assert limiter.check("b") == (True, 0.0)


@pytest.mark.parametrize("bad_kwargs", [{"max_requests": 0}, {"window_seconds": 0}])
def test_rejects_invalid_construction(bad_kwargs):
    kwargs = {"max_requests": 5, "window_seconds": 60, **bad_kwargs}
    with pytest.raises(ValueError):
        InMemoryRateLimiter(**kwargs)


def _build_app(*, global_max: int = 2, auth_max: int = 1) -> FastAPI:
    app = FastAPI()

    @app.get("/thing")
    async def thing():
        return {"ok": True}

    @app.post("/auth/login")
    async def login():
        return {"ok": True}

    @app.get("/health/live")
    async def health():
        return {"ok": True}

    app.add_middleware(
        RateLimitMiddleware,
        global_limiter=InMemoryRateLimiter(max_requests=global_max, window_seconds=60),
        auth_limiter=InMemoryRateLimiter(max_requests=auth_max, window_seconds=60),
    )
    return app


@pytest.fixture
async def rl_client():
    app = _build_app()
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


async def test_global_limit_returns_429_with_retry_after(rl_client: httpx.AsyncClient):
    for _ in range(2):
        resp = await rl_client.get("/thing")
        assert resp.status_code == 200
    resp = await rl_client.get("/thing")
    assert resp.status_code == 429
    assert resp.json()["detail"] == "rate limit exceeded"
    assert int(resp.headers["Retry-After"]) >= 1


async def test_auth_paths_use_the_stricter_limiter(rl_client: httpx.AsyncClient):
    # auth_max=1: the first /auth/login is allowed even though the
    # (higher) global budget of 2 is untouched, proving the auth-specific
    # limiter — not the global one — governs this path.
    resp = await rl_client.post("/auth/login")
    assert resp.status_code == 200
    resp = await rl_client.post("/auth/login")
    assert resp.status_code == 429
    # The global limiter's separate budget is unaffected by the auth
    # limiter tripping.
    resp = await rl_client.get("/thing")
    assert resp.status_code == 200


async def test_exempt_paths_are_never_limited(rl_client: httpx.AsyncClient):
    for _ in range(5):
        resp = await rl_client.get("/health/live")
        assert resp.status_code == 200


def test_shared_app_does_not_attach_rate_limiting_under_test_env():
    """Locks in the WP-P1-010 design decision: the process-wide `app`
    singleton (imported once for the whole pytest session, per
    conftest.py) must never actually enforce rate limits, or the other
    ~340 tests in this suite would intermittently 429 each other.
    """
    from app.main import app as shared_app

    assert not any(m.cls is RateLimitMiddleware for m in shared_app.user_middleware)


async def test_distinct_client_ips_have_independent_budgets():
    app = _build_app(global_max=1)
    transport = ASGITransport(app=app, client=("1.1.1.1", 123))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac1:
        assert (await ac1.get("/thing")).status_code == 200
        assert (await ac1.get("/thing")).status_code == 429

    transport2 = ASGITransport(app=app, client=("2.2.2.2", 123))
    async with httpx.AsyncClient(transport=transport2, base_url="http://test") as ac2:
        # A different source IP has its own, untouched budget.
        assert (await ac2.get("/thing")).status_code == 200


# --- H-2: the window map must stay bounded ---


def test_expired_windows_are_swept_instead_of_accumulating():
    """Distinct keys across successive windows must not accumulate forever.

    Before the fix an entry was created on a key's first request and only
    overwritten if that same key returned, so a caller rotating source IPs
    grew the map for the life of the process.
    """
    limiter = InMemoryRateLimiter(max_requests=5, window_seconds=10)
    for index in range(500):
        limiter.check(f"ip-{index}", now=0.0)
    assert limiter.tracked_keys == 500

    # One key, a full window later: the 500 stale windows go with it.
    limiter.check("later", now=10.0)
    assert limiter.tracked_keys == 1


def test_sweep_keeps_windows_that_are_still_live():
    limiter = InMemoryRateLimiter(max_requests=5, window_seconds=10)
    limiter.check("old", now=0.0)
    limiter.check("fresh", now=9.0)
    # now=10.0 expires "old" (started_at 0.0) but not "fresh" (started_at 9.0).
    limiter.check("new", now=10.0)
    assert limiter.tracked_keys == 2
    # "fresh" kept its budget rather than being reset by the sweep.
    assert limiter._windows["fresh"].count == 1


def test_tracked_keys_never_exceed_the_cap_even_when_all_windows_are_live():
    """Hard backstop: every window live inside one window_seconds."""
    limiter = InMemoryRateLimiter(max_requests=5, window_seconds=1000, max_tracked_keys=50)
    for index in range(500):
        limiter.check(f"ip-{index}", now=float(index))
    assert limiter.tracked_keys <= 50


def test_eviction_frees_the_oldest_window_first():
    limiter = InMemoryRateLimiter(max_requests=5, window_seconds=1000, max_tracked_keys=2)
    limiter.check("oldest", now=0.0)
    limiter.check("middle", now=1.0)
    limiter.check("newest", now=2.0)
    assert "oldest" not in limiter._windows
    assert "newest" in limiter._windows


def test_evicted_key_is_admitted_rather_than_blocked():
    """Evicting a key must fail open — a dropped window means a fresh budget,
    never a rejection caused by the limiter's own bookkeeping."""
    limiter = InMemoryRateLimiter(max_requests=1, window_seconds=1000, max_tracked_keys=1)
    assert limiter.check("a", now=0.0) == (True, 0.0)
    # "a" is at its limit; admitting "b" evicts "a".
    assert limiter.check("b", now=1.0) == (True, 0.0)
    assert limiter.check("a", now=2.0) == (True, 0.0)


@pytest.mark.parametrize("bad_cap", [0, -1])
def test_rejects_nonsense_key_cap(bad_cap):
    with pytest.raises(ValueError, match="max_tracked_keys"):
        InMemoryRateLimiter(max_requests=1, window_seconds=1, max_tracked_keys=bad_cap)

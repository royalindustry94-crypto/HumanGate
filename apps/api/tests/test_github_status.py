"""Coverage for the live GitHub status projection (audit H-4 and L-6).

`github_status.py` had no test coverage at all despite being the only service
that makes outbound network calls. These tests pin the two H-4 fixes -- the
five GitHub calls are issued concurrently rather than in sequence, and results
are cached so a polled dashboard does not spend five API calls per view -- and
the fail-closed contract that a GitHub outage reports `available=False` rather
than fabricating commits, PRs, or Actions results.
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from app.core.config import get_settings
from app.services import github_status as gh


@pytest.fixture(autouse=True)
def _configured_github(monkeypatch):
    monkeypatch.setenv("GITHUB_TOKEN", "ghp_test_token")
    monkeypatch.setenv("GITHUB_REPOSITORY", "acme/widgets")
    monkeypatch.setenv("DEPLOYMENT_GIT_BRANCH", "main")
    get_settings.cache_clear()
    gh.reset_cache()
    yield
    get_settings.cache_clear()
    gh.reset_cache()


def _body_for(path: str) -> object:
    """Minimal well-formed GitHub payloads keyed by request path."""
    if path.endswith("/commits"):
        return [
            {
                "sha": "abc123",
                "html_url": "https://github.com/acme/widgets/commit/abc123",
                "commit": {
                    "message": "fix: a thing\n\nbody",
                    "author": {"name": "Dev", "date": "2026-09-14T10:00:00Z"},
                },
            }
        ]
    if path.endswith("/pulls"):
        return [
            {
                "number": 7,
                "title": "Open PR",
                "state": "open",
                "user": {"login": "dev"},
                "updated_at": "2026-09-14T10:00:00Z",
                "html_url": "https://github.com/acme/widgets/pull/7",
                "merged_at": None,
            }
        ]
    if path.endswith("/actions/runs"):
        return {
            "workflow_runs": [
                {
                    "id": 42,
                    "name": "ci",
                    "status": "completed",
                    "conclusion": "failure",
                    "head_branch": "main",
                    "updated_at": "2026-09-14T10:00:00Z",
                    "html_url": "https://github.com/acme/widgets/actions/runs/42",
                }
            ]
        }
    if "/branches/" in path:
        return {"name": "main", "commit": {"sha": "abc123"}, "protected": True}
    return []


def _install_transport(monkeypatch, handler):
    """Route the service's AsyncClient through a MockTransport."""
    real_client = httpx.AsyncClient

    def factory(**kwargs):
        kwargs.pop("transport", None)
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr(gh.httpx, "AsyncClient", factory)


@pytest.mark.asyncio
async def test_reports_unavailable_when_not_configured(monkeypatch):
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.delenv("GITHUB_API_TOKEN", raising=False)
    get_settings.cache_clear()
    gh.reset_cache()

    out = await gh.github_status()

    assert out.available is False
    assert "not configured" in (out.unavailable_reason or "")
    assert out.latest_commits == []
    assert out.failed_actions == []


@pytest.mark.asyncio
async def test_parses_a_successful_fetch(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    out = await gh.github_status()

    assert out.available is True
    assert [c.sha for c in out.latest_commits] == ["abc123"]
    # Only the first line of the commit message is kept.
    assert out.latest_commits[0].message == "fix: a thing"
    assert [p.number for p in out.open_pull_requests] == [7]
    assert [r.id for r in out.failed_actions] == [42]
    assert out.branch_status.protected is True


@pytest.mark.asyncio
async def test_the_five_calls_are_issued_concurrently(monkeypatch):
    """H-4: sequential calls made worst-case latency 5x the timeout.

    Each handler blocks until all five requests have arrived. If the service
    still awaited them one at a time this deadlocks and the test times out.
    """
    started = asyncio.Event()
    in_flight = 0
    all_five = asyncio.Event()

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal in_flight
        in_flight += 1
        started.set()
        if in_flight >= 5:
            all_five.set()
        await asyncio.wait_for(all_five.wait(), timeout=5)
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    out = await asyncio.wait_for(gh.github_status(), timeout=10)

    assert out.available is True
    assert in_flight == 5


@pytest.mark.asyncio
async def test_successful_results_are_cached(monkeypatch):
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    first = await gh.github_status()
    after_first = calls["n"]
    second = await gh.github_status()

    assert after_first == 5, "one fetch is five GitHub calls"
    assert calls["n"] == after_first, "second call must be served from cache"
    assert second.generated_at == first.generated_at


@pytest.mark.asyncio
async def test_cache_expiry_refetches(monkeypatch):
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    await gh.github_status()
    monkeypatch.setattr(gh, "_CACHE_TTL_SECONDS", -1.0)
    gh.reset_cache()
    await gh.github_status()

    assert calls["n"] == 10


@pytest.mark.asyncio
async def test_upstream_failure_reports_unavailable_without_fabricating(monkeypatch):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="upstream boom")

    _install_transport(monkeypatch, handler)
    out = await gh.github_status()

    assert out.available is False
    assert "GitHub API unavailable" in (out.unavailable_reason or "")
    assert out.latest_commits == []
    assert out.open_pull_requests == []
    assert out.recently_merged_pull_requests == []
    assert out.failed_actions == []
    # Falls back to deployment metadata rather than inventing branch state.
    assert out.branch_status.name == "main"
    assert out.branch_status.protected is None


@pytest.mark.asyncio
async def test_failures_are_cached_briefly_so_an_outage_is_not_amplified(monkeypatch):
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(500, text="boom")

    _install_transport(monkeypatch, handler)
    await gh.github_status()
    after_first = calls["n"]
    await gh.github_status()

    assert calls["n"] == after_first


@pytest.mark.asyncio
async def test_a_non_200_branch_response_does_not_fail_the_whole_fetch(monkeypatch):
    """Branch state is optional; the rest of the projection still renders."""

    def handler(request: httpx.Request) -> httpx.Response:
        if "/branches/" in request.url.path:
            return httpx.Response(404, json={"message": "Not Found"})
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    out = await gh.github_status()

    assert out.available is True
    assert [c.sha for c in out.latest_commits] == ["abc123"]
    assert out.branch_status.protected is None


@pytest.mark.asyncio
async def test_merged_pull_requests_require_a_merged_at(monkeypatch):
    """A closed-but-unmerged PR must not be reported as merged."""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/pulls") and request.url.params.get("state") == "closed":
            return httpx.Response(
                200,
                content=json.dumps(
                    [
                        {"number": 1, "title": "closed unmerged", "merged_at": None},
                        {
                            "number": 2,
                            "title": "really merged",
                            "merged_at": "2026-09-14T09:00:00Z",
                            "updated_at": "2026-09-14T09:00:00Z",
                            "user": {"login": "dev"},
                            "html_url": "https://github.com/acme/widgets/pull/2",
                        },
                    ]
                ),
                headers={"Content-Type": "application/json"},
            )
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    out = await gh.github_status()

    assert [p.number for p in out.recently_merged_pull_requests] == [2]
    assert out.recently_merged_pull_requests[0].state == "merged"


@pytest.mark.asyncio
async def test_one_transport_failure_does_not_strand_the_other_requests(monkeypatch):
    """A bare `gather` propagates the first failure while siblings are still in
    flight, and leaving the client context then closes it out from under them.
    All five must settle before the context exits."""
    started = 0
    finished = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal started, finished
        started += 1
        if request.url.path.endswith("/commits"):
            # Fail fast, while the others are still awaiting.
            raise httpx.ConnectError("boom", request=request)
        await asyncio.sleep(0.05)
        finished += 1
        return httpx.Response(200, json=_body_for(request.url.path))

    _install_transport(monkeypatch, handler)
    out = await gh.github_status()

    assert out.available is False
    assert "GitHub API unavailable" in (out.unavailable_reason or "")
    assert started == 5
    assert finished == 4, (
        "every non-failing request must complete before the client closes; "
        f"only {finished} of 4 finished"
    )

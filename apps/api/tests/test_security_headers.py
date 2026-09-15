"""TD-094: browser-facing security headers and session transport invariants."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[3]


@pytest.mark.asyncio
async def test_api_responses_include_browser_security_headers(client):
    response = await client.get("/health/live")
    assert response.status_code == 200
    assert response.headers.get("Content-Security-Policy") == (
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    )
    assert response.headers.get("Strict-Transport-Security") == (
        "max-age=31536000; includeSubDomains"
    )
    assert response.headers.get("X-Content-Type-Options") == "nosniff"
    assert response.headers.get("X-Frame-Options") == "DENY"
    assert response.headers.get("Referrer-Policy") == "strict-origin-when-cross-origin"


@pytest.mark.asyncio
async def test_auth_mode_response_does_not_set_session_cookie(client):
    response = await client.get("/auth/mode")
    assert response.status_code == 200
    assert "set-cookie" not in response.headers


def test_vercel_web_responses_define_browser_security_headers():
    config = json.loads((_REPO_ROOT / "vercel.json").read_text())
    headers = config.get("headers", [])
    assert headers, "vercel.json must declare headers for the web deployment"

    all_headers = {
        item["key"]: item["value"]
        for rule in headers
        for item in rule.get("headers", [])
        if isinstance(item, dict) and "key" in item and "value" in item
    }
    assert all_headers.get("X-Content-Type-Options") == "nosniff"
    assert all_headers.get("X-Frame-Options") == "DENY"
    assert all_headers.get("Referrer-Policy") == "strict-origin-when-cross-origin"
    assert all_headers.get("Strict-Transport-Security") == "max-age=31536000; includeSubDomains"
    csp = all_headers.get("Content-Security-Policy")
    assert csp is not None
    assert "frame-ancestors 'none'" in csp


def test_frontend_uses_bearer_header_and_tab_scoped_session_storage():
    api_client = (_REPO_ROOT / "apps/web/src/api.ts").read_text()
    app_shell = (_REPO_ROOT / "apps/web/src/App.tsx").read_text()

    assert "Authorization" in api_client
    assert "sessionStorage.setItem" in app_shell
    assert "sessionStorage.removeItem" in app_shell

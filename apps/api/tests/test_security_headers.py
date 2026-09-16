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


@pytest.mark.asyncio
async def test_not_found_responses_still_include_browser_security_headers(client):
    response = await client.get("/does-not-exist")
    assert response.status_code == 404
    assert response.headers.get("Content-Security-Policy") == (
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    )
    assert response.headers.get("X-Frame-Options") == "DENY"


@pytest.mark.asyncio
async def test_validation_error_responses_include_browser_security_headers(client):
    response = await client.post("/auth/login", json={})
    assert response.status_code == 422
    assert response.headers.get("Content-Security-Policy") == (
        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    )
    assert response.headers.get("X-Frame-Options") == "DENY"


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
    directives = {
        part.strip().split(" ", 1)[0]: part.strip() for part in csp.split(";") if part.strip()
    }
    assert directives["default-src"] == "default-src 'self'"
    assert directives["script-src"] == "script-src 'self'"
    assert directives["script-src-elem"] == "script-src-elem 'self'"
    assert directives["style-src"] == "style-src 'self' https://fonts.googleapis.com"
    assert directives["font-src"] == "font-src 'self' https://fonts.gstatic.com"
    assert directives["img-src"] == "img-src 'self' data:"
    assert directives["connect-src"] == (
        "connect-src 'self' https://content-orchestrator-api.vercel.app https://*.vercel.app"
    )
    assert directives["object-src"] == "object-src 'none'"
    assert directives["base-uri"] == "base-uri 'self'"
    assert directives["frame-ancestors"] == "frame-ancestors 'none'"
    assert directives["form-action"] == "form-action 'self'"

"""P0-3: automation wiring — consumers registered; health exposes loops."""

from __future__ import annotations

import pytest

from app.main import app
from app.orchestration import consumers
from app.orchestration.events.types import REVIEW_APPROVED, REVIEW_REJECTED
from app.orchestration.relay import _REGISTRY


def test_consumers_registered_at_import():
    consumers.register_all()
    assert "pipeline-controller" in _REGISTRY
    assert REVIEW_APPROVED in _REGISTRY["pipeline-controller"]
    assert REVIEW_REJECTED in _REGISTRY["pipeline-controller"]


@pytest.mark.asyncio
async def test_health_automation_endpoint(client):
    res = await client.get("/health/automation")
    assert res.status_code == 200
    body = res.json()
    assert "scheduler" in body
    assert "outbox_relay" in body
    assert "maintenance" in body
    assert body["scheduler"]["status"] == "idle"
    assert body["scheduler"]["owner_id"] is None
    # ENVIRONMENT=test => API process stays stateless
    assert body["tasks_running"] == []


def test_app_routes_exist():
    assert app.router.routes

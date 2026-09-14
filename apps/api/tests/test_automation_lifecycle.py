"""P0-3: automation wiring — consumers registered; health exposes loops."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.db.session import AsyncSessionLocal
from app.main import app
from app.models.automation import AutomationLease
from app.orchestration import consumers
from app.orchestration.events.types import REVIEW_APPROVED, REVIEW_REJECTED
from app.orchestration.relay import _REGISTRY


async def _reset_automation_rows() -> None:
    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                """
                UPDATE automation_leases
                SET owner_id = NULL,
                    owner_started_at = NULL,
                    lease_expires_at = NULL,
                    last_heartbeat_at = NULL,
                    last_ok_at = NULL,
                    last_error = NULL,
                    tick_count = 0,
                    work_count = 0
                """
            )
        )
        await session.commit()


@pytest_asyncio.fixture(autouse=True)
async def reset_automation_rows():
    await _reset_automation_rows()
    yield
    await _reset_automation_rows()


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
    assert body["scheduler"] == {"status": "idle", "active": False}
    assert body["outbox_relay"] == {"status": "idle", "active": False}
    assert body["maintenance"] == {"status": "idle", "active": False}
    # ENVIRONMENT=test => API process stays stateless
    assert body["tasks_running"] == []


@pytest.mark.asyncio
async def test_workspace_admin_can_view_sanitized_automation_diagnostics(client, new_user):
    _user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "Automation Ops"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        row = await session.get(AutomationLease, "scheduler")
        assert row is not None
        row.owner_id = "automation-owner"
        row.owner_started_at = now
        row.last_heartbeat_at = now
        row.lease_expires_at = now + timedelta(minutes=1)
        row.last_error = "RuntimeError"
        await session.commit()

    res = await client.get(f"/workspaces/{workspace_id}/operations/automation", headers=headers)
    assert res.status_code == 200
    body = res.json()
    assert body["scheduler"]["owner_id"] == "automation-owner"
    assert body["scheduler"]["last_error"] == "RuntimeError"
    assert body["scheduler"]["active"] is True


def test_app_routes_exist():
    assert app.router.routes

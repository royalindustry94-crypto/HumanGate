"""P0-3: dedicated automation runner owns the long-lived loops."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import text

from app.automation import (
    AutomationLoopSpec,
    AutomationService,
    automation_health_snapshot,
    release_owned_automation_loops,
    try_claim_automation_loop,
)
from app.db.session import AsyncSessionLocal
from app.models.automation import AutomationLease


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
                    last_error = NULL,
                    tick_count = 0,
                    work_count = 0
                """
            )
        )
        await session.commit()


@pytest.mark.asyncio
async def test_automation_service_starts_stops_and_releases_leases():
    await _reset_automation_rows()
    seen: dict[str, int] = {}

    async def tick(name: str) -> dict[str, int]:
        seen[name] = seen.get(name, 0) + 1
        return {"work_count": 1}

    service = AutomationService(
        owner_id="test-owner",
        loop_specs=(
            AutomationLoopSpec("maintenance", 0.05, lambda: tick("maintenance")),
            AutomationLoopSpec("outbox_relay", 0.05, lambda: tick("outbox_relay")),
            AutomationLoopSpec("scheduler", 0.05, lambda: tick("scheduler")),
        ),
        standby_poll_seconds=0.01,
    )
    stop_event = asyncio.Event()
    task = asyncio.create_task(service.run(stop_event))
    try:
        deadline = asyncio.get_running_loop().time() + 5.0
        while set(seen) != {"maintenance", "outbox_relay", "scheduler"}:
            if asyncio.get_running_loop().time() >= deadline:
                break
            await asyncio.sleep(0.1)
        assert set(seen) == {"maintenance", "outbox_relay", "scheduler"}
        while True:
            snapshot = await automation_health_snapshot()
            if snapshot["scheduler"]["ticks"] >= 1:
                break
            if asyncio.get_running_loop().time() >= deadline:
                break
            await asyncio.sleep(0.1)
        assert set(snapshot["tasks_running"]) == {"maintenance", "outbox_relay", "scheduler"}
        assert snapshot["scheduler"]["owner_id"] == "test-owner"
        assert snapshot["scheduler"]["ticks"] >= 1
    finally:
        stop_event.set()
        await asyncio.wait_for(task, timeout=5)

    snapshot = await automation_health_snapshot()
    assert snapshot["tasks_running"] == []
    assert snapshot["scheduler"]["owner_id"] is None


@pytest.mark.asyncio
async def test_competing_owners_are_deterministic_and_stale_owner_recovers():
    await _reset_automation_rows()
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        assert await try_claim_automation_loop(
            session,
            loop_name="scheduler",
            owner_id="owner-a",
            lease_seconds=5,
            now=now,
        )
        await session.commit()

    async with AsyncSessionLocal() as session:
        assert not await try_claim_automation_loop(
            session,
            loop_name="scheduler",
            owner_id="owner-b",
            lease_seconds=5,
            now=now + timedelta(seconds=1),
        )
        await session.rollback()

    async with AsyncSessionLocal() as session:
        assert await try_claim_automation_loop(
            session,
            loop_name="scheduler",
            owner_id="owner-b",
            lease_seconds=5,
            now=now + timedelta(seconds=6),
        )
        await session.commit()

    snapshot = await automation_health_snapshot()
    assert snapshot["scheduler"]["owner_id"] == "owner-b"
    assert snapshot["scheduler"]["status"] == "running"

    async with AsyncSessionLocal() as session:
        await release_owned_automation_loops(
            session, owner_id="owner-b", now=now + timedelta(seconds=7)
        )
        await session.commit()
        row = await session.get(AutomationLease, "scheduler")
        assert row is not None
        assert row.owner_id is None

    snapshot = await automation_health_snapshot()
    assert snapshot["scheduler"]["status"] == "idle"
    assert snapshot["scheduler"]["owner_id"] is None


@pytest.mark.asyncio
async def test_partial_automation_ownership_is_degraded():
    await _reset_automation_rows()
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        assert await try_claim_automation_loop(
            session,
            loop_name="scheduler",
            owner_id="owner-a",
            lease_seconds=5,
            now=now,
        )
        await session.commit()

    snapshot = await automation_health_snapshot()
    assert snapshot["status"] == "degraded"
    assert snapshot["tasks_running"] == ["scheduler"]
    assert snapshot["maintenance"]["status"] == "idle"

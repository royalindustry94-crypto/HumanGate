"""P0-3: dedicated automation runner owns the long-lived loops."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

import pytest
import pytest_asyncio
from sqlalchemy import text

from app import automation as automation_mod
from app.automation import (
    AutomationControlPlaneError,
    AutomationLoopSpec,
    AutomationService,
    automation_health_snapshot,
    automation_process_healthcheck,
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
                    last_ok_at = NULL,
                    last_error = NULL,
                    tick_count = 0,
                    work_count = 0
                """
            )
        )
        await session.commit()


async def _loop_row(loop_name: str) -> AutomationLease | None:
    async with AsyncSessionLocal() as session:
        return await session.get(AutomationLease, loop_name)


async def _wait_for(predicate, *, timeout: float = 5.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        if await predicate():
            return
        if asyncio.get_running_loop().time() >= deadline:
            raise AssertionError("condition not met before timeout")
        await asyncio.sleep(0.1)


@pytest_asyncio.fixture(autouse=True)
async def reset_automation_rows():
    await _reset_automation_rows()
    yield
    await _reset_automation_rows()


@pytest.mark.asyncio
async def test_automation_service_starts_stops_and_releases_leases():
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

        async def _all_seen() -> bool:
            return set(seen) == {"maintenance", "outbox_relay", "scheduler"}

        await _wait_for(_all_seen)
        snapshot = await automation_health_snapshot()

        async def _scheduler_recorded() -> bool:
            nonlocal snapshot
            snapshot = await automation_health_snapshot()
            return snapshot["scheduler"]["ticks"] >= 1

        await _wait_for(_scheduler_recorded)
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


@pytest.mark.asyncio
async def test_long_running_tick_renews_lease_and_blocks_takeover(monkeypatch):
    monkeypatch.setattr(automation_mod, "_lease_seconds", lambda _interval: 1)
    tick_started = asyncio.Event()
    release_tick = asyncio.Event()

    async def long_tick() -> dict[str, int]:
        tick_started.set()
        await release_tick.wait()
        return {"work_count": 1}

    service = AutomationService(
        owner_id="owner-a",
        loop_specs=(AutomationLoopSpec("scheduler", 0.2, long_tick),),
        standby_poll_seconds=0.01,
    )
    stop_event = asyncio.Event()
    task = asyncio.create_task(service.run(stop_event))
    try:
        original_expiry: datetime | None = None
        renewed_expiry: datetime | None = None

        async def _lease_renewed() -> bool:
            nonlocal original_expiry, renewed_expiry
            row = await _loop_row("scheduler")
            if row is None or row.owner_id != "owner-a" or row.lease_expires_at is None:
                return False
            if not tick_started.is_set():
                return False
            if original_expiry is None:
                original_expiry = row.lease_expires_at
                return False
            if row.lease_expires_at > original_expiry:
                renewed_expiry = row.lease_expires_at
                return True
            return False

        await _wait_for(_lease_renewed)
        assert original_expiry is not None
        assert renewed_expiry is not None

        async with AsyncSessionLocal() as session:
            claimed = await try_claim_automation_loop(
                session,
                loop_name="scheduler",
                owner_id="owner-b",
                lease_seconds=1,
                now=original_expiry + timedelta(milliseconds=100),
            )
            assert not claimed
            await session.rollback()
    finally:
        stop_event.set()
        release_tick.set()
        await asyncio.wait_for(task, timeout=5)


@pytest.mark.asyncio
async def test_lost_lease_cancels_inflight_tick_and_allows_takeover(monkeypatch):
    monkeypatch.setattr(automation_mod, "_lease_seconds", lambda _interval: 1)
    original_renew = automation_mod.renew_owned_automation_loop
    tick_started = asyncio.Event()
    tick_cancelled = asyncio.Event()
    takeover_complete = asyncio.Event()
    winner_ticked = asyncio.Event()
    owner_a_finished = False

    async def owner_a_tick() -> dict[str, int]:
        nonlocal owner_a_finished
        tick_started.set()
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            tick_cancelled.set()
            raise
        owner_a_finished = True
        return {"work_count": 1}

    async def owner_b_tick() -> dict[str, int]:
        winner_ticked.set()
        return {"work_count": 1}

    async def forced_loss(*args, owner_id: str, loop_name: str, lease_seconds: int, **kwargs):
        if owner_id != "owner-a":
            return await original_renew(
                *args,
                owner_id=owner_id,
                loop_name=loop_name,
                lease_seconds=lease_seconds,
                **kwargs,
            )
        async with AsyncSessionLocal() as session:
            row = await session.get(AutomationLease, loop_name, with_for_update=True)
            assert row is not None
            now = kwargs.get("now") or datetime.now(UTC)
            row.owner_id = "owner-b"
            row.owner_started_at = now
            row.last_heartbeat_at = now
            row.lease_expires_at = now + timedelta(seconds=lease_seconds)
            await session.commit()
        takeover_complete.set()
        return False

    monkeypatch.setattr(automation_mod, "renew_owned_automation_loop", forced_loss)
    stop_owner_a = asyncio.Event()
    stop_owner_b = asyncio.Event()
    service_a = AutomationService(
        owner_id="owner-a",
        loop_specs=(AutomationLoopSpec("scheduler", 0.2, owner_a_tick),),
        standby_poll_seconds=0.01,
    )
    service_b = AutomationService(
        owner_id="owner-b",
        loop_specs=(AutomationLoopSpec("scheduler", 0.5, owner_b_tick),),
        standby_poll_seconds=0.01,
    )
    task_a = asyncio.create_task(service_a.run(stop_owner_a))
    task_b = asyncio.create_task(service_b.run(stop_owner_b))
    try:
        async def _tick_started() -> bool:
            return tick_started.is_set()

        async def _takeover_complete() -> bool:
            return takeover_complete.is_set()

        async def _tick_cancelled() -> bool:
            return tick_cancelled.is_set()

        async def _winner_ticked() -> bool:
            return winner_ticked.is_set()

        async def _winner_recorded() -> bool:
            return (await automation_health_snapshot())["scheduler"]["ticks"] == 1

        await _wait_for(_tick_started)
        await _wait_for(_takeover_complete)
        await _wait_for(_tick_cancelled)
        await _wait_for(_winner_ticked)
        await _wait_for(_winner_recorded)
        snapshot = await automation_health_snapshot()
        assert snapshot["scheduler"]["owner_id"] == "owner-b"
        assert snapshot["scheduler"]["ticks"] == 1
        assert snapshot["scheduler"]["last_error"] is None
        assert owner_a_finished is False
    finally:
        stop_owner_a.set()
        stop_owner_b.set()
        await asyncio.wait_for(task_a, timeout=5)
        await asyncio.wait_for(task_b, timeout=5)


@pytest.mark.asyncio
async def test_claim_failures_restart_then_fail_closed(monkeypatch):
    async def failing_claim(*args, **kwargs):
        raise RuntimeError("database unavailable")

    async def noop_tick() -> None:
        return None

    monkeypatch.setattr(automation_mod, "try_claim_automation_loop", failing_claim)
    service = AutomationService(
        owner_id="owner-a",
        loop_specs=(AutomationLoopSpec("scheduler", 0.05, noop_tick),),
        standby_poll_seconds=0.01,
        max_supervision_failures=2,
        supervision_backoff_seconds=0.01,
    )

    with pytest.raises(
        AutomationControlPlaneError, match="scheduler automation loop failed 2 times"
    ):
        await asyncio.wait_for(service.run(asyncio.Event()), timeout=5)


@pytest.mark.asyncio
async def test_tick_errors_are_sanitized_in_health():
    tick_seen = asyncio.Event()

    async def failing_tick() -> dict[str, int]:
        tick_seen.set()
        raise RuntimeError("secret token should not leak")

    service = AutomationService(
        owner_id="owner-a",
        loop_specs=(AutomationLoopSpec("scheduler", 0.05, failing_tick),),
        standby_poll_seconds=0.01,
    )
    stop_event = asyncio.Event()
    task = asyncio.create_task(service.run(stop_event))
    try:

        async def _tick_seen() -> bool:
            return tick_seen.is_set()

        async def _error_recorded() -> bool:
            return (await automation_health_snapshot())["scheduler"]["last_error"] is not None

        await _wait_for(_tick_seen)
        await _wait_for(_error_recorded)
        snapshot = await automation_health_snapshot()
        assert snapshot["status"] == "degraded"
        assert snapshot["scheduler"]["last_error"] == "RuntimeError"
    finally:
        stop_event.set()
        await asyncio.wait_for(task, timeout=5)


@pytest.mark.asyncio
async def test_process_healthcheck_stays_ok_while_global_automation_is_degraded():
    now = datetime.now(UTC)
    async with AsyncSessionLocal() as session:
        scheduler = await session.get(AutomationLease, "scheduler")
        maintenance = await session.get(AutomationLease, "maintenance")
        assert scheduler is not None
        assert maintenance is not None
        scheduler.owner_id = "other-owner"
        scheduler.owner_started_at = now
        scheduler.last_heartbeat_at = now
        scheduler.lease_expires_at = now + timedelta(minutes=1)
        maintenance.owner_id = "stale-owner"
        maintenance.owner_started_at = now - timedelta(minutes=2)
        maintenance.last_heartbeat_at = now - timedelta(minutes=2)
        maintenance.lease_expires_at = now - timedelta(seconds=1)
        await session.commit()

    snapshot = await automation_health_snapshot()
    assert snapshot["status"] == "degraded"
    assert await automation_process_healthcheck(local_owner_id="local-owner") == {
        "status": "ok",
        "local_owner_id": "local-owner",
    }


@pytest.mark.asyncio
async def test_shutdown_release_only_clears_active_current_owner_rows():
    now = datetime.now(UTC)
    async with AsyncSessionLocal() as session:
        scheduler = await session.get(AutomationLease, "scheduler")
        maintenance = await session.get(AutomationLease, "maintenance")
        outbox = await session.get(AutomationLease, "outbox_relay")
        assert scheduler is not None
        assert maintenance is not None
        assert outbox is not None
        scheduler.owner_id = "owner-a"
        scheduler.owner_started_at = now
        scheduler.last_heartbeat_at = now
        scheduler.lease_expires_at = now + timedelta(minutes=1)
        maintenance.owner_id = "owner-a"
        maintenance.owner_started_at = now - timedelta(minutes=2)
        maintenance.last_heartbeat_at = now - timedelta(minutes=2)
        maintenance.lease_expires_at = now - timedelta(seconds=1)
        outbox.owner_id = "owner-b"
        outbox.owner_started_at = now
        outbox.last_heartbeat_at = now
        outbox.lease_expires_at = now + timedelta(minutes=1)
        await session.commit()

    async with AsyncSessionLocal() as session:
        await release_owned_automation_loops(session, owner_id="owner-a", now=now)
        await session.commit()

    scheduler = await _loop_row("scheduler")
    maintenance = await _loop_row("maintenance")
    outbox = await _loop_row("outbox_relay")
    assert scheduler is not None and scheduler.owner_id is None
    assert maintenance is not None and maintenance.owner_id == "owner-a"
    assert outbox is not None and outbox.owner_id == "owner-b"


def test_automation_service_rejects_empty_loop_specs():
    with pytest.raises(ValueError, match="requires at least one loop"):
        AutomationService(loop_specs=())


@pytest.mark.asyncio
async def test_same_configured_owner_prefix_still_fences_two_replicas():
    counts: dict[str, int] = {}
    stop_event = asyncio.Event()
    owner_a = automation_mod._replica_owner_id("shared-owner", hostname="replica-a", pid=1)
    owner_b = automation_mod._replica_owner_id("shared-owner", hostname="replica-b", pid=1)

    async def tick(name: str) -> dict[str, int]:
        counts[name] = counts.get(name, 0) + 1
        stop_event.set()
        return {"work_count": 1}

    service_a = AutomationService(
        owner_id=owner_a,
        loop_specs=(AutomationLoopSpec("scheduler", 0.2, lambda: tick("owner-a")),),
        standby_poll_seconds=0.01,
    )
    service_b = AutomationService(
        owner_id=owner_b,
        loop_specs=(AutomationLoopSpec("scheduler", 0.2, lambda: tick("owner-b")),),
        standby_poll_seconds=0.01,
    )

    await asyncio.wait_for(
        asyncio.gather(
            asyncio.create_task(service_a.run(stop_event)),
            asyncio.create_task(service_b.run(stop_event)),
        ),
        timeout=5,
    )

    assert owner_a != owner_b
    assert owner_a.startswith("shared-owner@")
    assert owner_b.startswith("shared-owner@")
    assert sum(counts.values()) == 1

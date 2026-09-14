"""Dedicated automation runner for scheduler / outbox / maintenance loops."""

from __future__ import annotations

import asyncio
import logging
import math
import os
import signal
import socket
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import NotRequired, TypedDict

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.core.logging import configure_logging
from app.db.session import AsyncSessionLocal
from app.models.automation import AutomationLease
from app.orchestration import consumers

settings = get_settings()
logger = logging.getLogger(__name__)

AUTOMATION_LOOP_NAMES: tuple[str, str, str] = ("maintenance", "outbox_relay", "scheduler")

# The relay needs its consumer registry even when the API process never starts
# the long-lived loops.
consumers.register_all()


@dataclass(frozen=True)
class AutomationLoopSpec:
    name: str
    interval_seconds: float
    tick: Callable[[], Awaitable[dict[str, int] | None]]


class AutomationLoopHealthSnapshot(TypedDict):
    status: str
    owner_id: str | None
    owner_started_at: str | None
    lease_expires_at: str | None
    last_heartbeat_at: str | None
    ticks: int
    last_ok_at: str | None
    last_error: str | None
    active: bool
    jobs_leased: NotRequired[int]


class PublicAutomationLoopHealthSnapshot(TypedDict):
    status: str
    active: bool


class AutomationHealthSnapshot(TypedDict):
    status: str
    started_at: str | None
    tasks_running: list[str]
    maintenance: AutomationLoopHealthSnapshot
    outbox_relay: AutomationLoopHealthSnapshot
    scheduler: AutomationLoopHealthSnapshot


class PublicAutomationHealthSnapshot(TypedDict):
    status: str
    started_at: str | None
    tasks_running: list[str]
    maintenance: PublicAutomationLoopHealthSnapshot
    outbox_relay: PublicAutomationLoopHealthSnapshot
    scheduler: PublicAutomationLoopHealthSnapshot


class AutomationControlPlaneError(RuntimeError):
    """Automation ownership or supervision failed in a way that must fail closed."""


class AutomationOwnershipLostError(AutomationControlPlaneError):
    """A loop lost its durable lease while still executing a tick."""


def _owner_id() -> str:
    configured = os.getenv("AUTOMATION_OWNER_ID") or f"{settings.service_name}-automation"
    return _replica_owner_id(configured)


def _replica_owner_id(
    configured_owner_id: str,
    *,
    hostname: str | None = None,
    pid: int | None = None,
) -> str:
    return (
        f"{configured_owner_id}@{hostname if hostname is not None else socket.gethostname()}:"
        f"{pid if pid is not None else os.getpid()}"
    )


def _lease_seconds(interval_seconds: float) -> int:
    return max(5, math.ceil(interval_seconds * 4))


def _sanitize_error(error: BaseException | str) -> str:
    if isinstance(error, BaseException):
        return error.__class__.__name__
    cleaned = " ".join(str(error).split())
    return cleaned[:200] if cleaned else "RuntimeError"


async def try_claim_automation_loop(
    session: AsyncSession,
    *,
    loop_name: str,
    owner_id: str,
    lease_seconds: int,
    now: datetime | None = None,
) -> bool:
    now = now or datetime.now(UTC)
    row = await session.get(AutomationLease, loop_name, with_for_update=True)
    if row is None:
        raise ValueError(f"automation loop {loop_name!r} missing from automation_leases")
    if (
        row.owner_id
        and row.owner_id != owner_id
        and row.lease_expires_at is not None
        and row.lease_expires_at > now
    ):
        return False
    if row.owner_id != owner_id:
        row.owner_started_at = now
    row.owner_id = owner_id
    row.last_heartbeat_at = now
    row.lease_expires_at = now + timedelta(seconds=lease_seconds)
    return True


async def renew_owned_automation_loop(
    session: AsyncSession,
    *,
    loop_name: str,
    owner_id: str,
    lease_seconds: int,
    now: datetime | None = None,
) -> bool:
    now = now or datetime.now(UTC)
    row = await session.get(AutomationLease, loop_name, with_for_update=True)
    if (
        row is None
        or row.owner_id != owner_id
        or row.lease_expires_at is None
        or row.lease_expires_at <= now
    ):
        return False
    row.last_heartbeat_at = now
    row.lease_expires_at = now + timedelta(seconds=lease_seconds)
    return True


async def release_owned_automation_loops(
    session: AsyncSession,
    *,
    owner_id: str,
    now: datetime | None = None,
) -> None:
    now = now or datetime.now(UTC)
    await session.execute(
        update(AutomationLease)
        .where(AutomationLease.owner_id == owner_id)
        .where(AutomationLease.lease_expires_at.is_not(None))
        .where(AutomationLease.lease_expires_at > now)
        .values(
            owner_id=None,
            owner_started_at=None,
            lease_expires_at=None,
            last_heartbeat_at=now,
        )
    )


async def _record_success(
    loop_name: str,
    *,
    owner_id: str,
    lease_seconds: int,
    work_count: int = 0,
    now: datetime | None = None,
) -> None:
    now = now or datetime.now(UTC)
    async with AsyncSessionLocal() as session:
        row = await session.get(AutomationLease, loop_name, with_for_update=True)
        if (
            row is not None
            and row.owner_id == owner_id
            and row.lease_expires_at is not None
            and row.lease_expires_at > now
        ):
            row.last_heartbeat_at = now
            row.lease_expires_at = now + timedelta(seconds=lease_seconds)
            row.last_ok_at = now
            row.last_error = None
            row.tick_count += 1
            row.work_count += work_count
            await session.commit()
        else:
            await session.rollback()


async def _record_error(
    loop_name: str,
    *,
    owner_id: str,
    lease_seconds: int,
    error: BaseException | str,
    now: datetime | None = None,
) -> None:
    now = now or datetime.now(UTC)
    async with AsyncSessionLocal() as session:
        row = await session.get(AutomationLease, loop_name, with_for_update=True)
        if (
            row is not None
            and row.owner_id == owner_id
            and row.lease_expires_at is not None
            and row.lease_expires_at > now
        ):
            row.last_heartbeat_at = now
            row.lease_expires_at = now + timedelta(seconds=lease_seconds)
            row.last_error = _sanitize_error(error)
            await session.commit()
        else:
            await session.rollback()


async def _outbox_relay_tick() -> dict[str, int]:
    from app.orchestration import relay

    async with AsyncSessionLocal() as session:
        try:
            dispatched = await relay.poll_and_dispatch(session)
            await session.commit()
            return {"work_count": dispatched}
        except Exception:
            await session.rollback()
            raise


async def _scheduler_tick() -> dict[str, int]:
    from app.orchestration import scheduler

    async with AsyncSessionLocal() as session:
        try:
            leased = await scheduler.poll_and_lease(
                session, batch_size=settings.scheduler_batch_size
            )
            for job in leased:
                await scheduler.process_leased_job(session, job)
            reaped = await scheduler.reap_expired_leases(session)
            await session.commit()
            if leased or reaped:
                logger.info(
                    "scheduler tick",
                    extra={"leased": len(leased), "reaped": reaped},
                )
            return {"work_count": len(leased)}
        except Exception:
            await session.rollback()
            raise


async def _maintenance_tick() -> dict[str, int]:
    from app.models.enums import RecoveryReason
    from app.orchestration.backpressure import evaluate_all_active_workspaces
    from app.orchestration.recovery import reap_expired_leases, reap_worker_assignments
    from app.services.workers import mark_stale_workers_offline

    async with AsyncSessionLocal() as session:
        try:
            flipped = await mark_stale_workers_offline(
                session, offline_after_seconds=settings.worker_offline_after_seconds
            )
            reaped_offline = 0
            for worker_id in flipped:
                outcomes = await reap_worker_assignments(
                    session, worker_id, reason=RecoveryReason.WORKER_OFFLINE
                )
                reaped_offline += len(outcomes)
            expired = await reap_expired_leases(session)
            bp_snapshots = await evaluate_all_active_workspaces(session)
            await session.commit()
        except Exception:
            await session.rollback()
            raise

    bp_changed = sum(1 for snapshot in bp_snapshots if snapshot.changed)
    if flipped or expired or reaped_offline or bp_changed:
        logger.info(
            "maintenance tick",
            extra={
                "workers_flipped": len(flipped),
                "assignments_reaped_offline": reaped_offline,
                "assignments_reaped_expired": len(expired),
                "backpressure_transitions": bp_changed,
            },
        )
    return {"work_count": len(flipped) + len(expired) + reaped_offline + bp_changed}


class AutomationService:
    def __init__(
        self,
        *,
        owner_id: str | None = None,
        loop_specs: Sequence[AutomationLoopSpec] | None = None,
        standby_poll_seconds: float = 0.5,
        max_supervision_failures: int = 3,
        supervision_backoff_seconds: float = 1.0,
    ) -> None:
        self.owner_id = owner_id or _owner_id()
        self.loop_specs = tuple(
            (
                AutomationLoopSpec(
                    "maintenance",
                    float(settings.assignment_reaper_interval_seconds),
                    _maintenance_tick,
                ),
                AutomationLoopSpec(
                    "outbox_relay",
                    float(settings.outbox_relay_interval_seconds),
                    _outbox_relay_tick,
                ),
                AutomationLoopSpec(
                    "scheduler",
                    float(settings.scheduler_interval_seconds),
                    _scheduler_tick,
                ),
            )
            if loop_specs is None
            else loop_specs
        )
        if not self.loop_specs:
            raise ValueError("AutomationService requires at least one loop")
        self.standby_poll_seconds = standby_poll_seconds
        self.max_supervision_failures = max_supervision_failures
        self.supervision_backoff_seconds = supervision_backoff_seconds

    async def run(self, stop_event: asyncio.Event) -> None:
        logger.info(
            "automation service starting",
            extra={"service": settings.service_name, "owner_id": self.owner_id},
        )
        tasks = {
            asyncio.create_task(
                self._supervise_loop(spec, stop_event), name=f"automation:{spec.name}"
            ): spec
            for spec in self.loop_specs
        }
        stop_waiter = asyncio.create_task(stop_event.wait(), name="automation:stop")
        fatal_error: BaseException | None = None
        try:
            await asyncio.wait([stop_waiter, *tasks], return_when=asyncio.FIRST_COMPLETED)
            for task, spec in tasks.items():
                if not task.done():
                    continue
                if task.cancelled():
                    continue
                exc = task.exception()
                if exc is not None:
                    fatal_error = exc
                    logger.error(
                        "automation loop terminated",
                        extra={"loop_name": spec.name, "owner_id": self.owner_id},
                    )
                    stop_event.set()
                    break
                if not stop_event.is_set():
                    fatal_error = AutomationControlPlaneError(
                        f"{spec.name} loop exited unexpectedly"
                    )
                    stop_event.set()
                    break
            await stop_event.wait()
        finally:
            stop_event.set()
            stop_waiter.cancel()
            timeout = max(spec.interval_seconds for spec in self.loop_specs) + 5.0
            try:
                await asyncio.wait_for(
                    asyncio.gather(*tasks, return_exceptions=True),
                    timeout=timeout,
                )
            except TimeoutError:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
            async with AsyncSessionLocal() as session:
                await release_owned_automation_loops(session, owner_id=self.owner_id)
                await session.commit()
            logger.info(
                "automation service stopped",
                extra={"service": settings.service_name, "owner_id": self.owner_id},
            )
        if fatal_error is not None:
            raise fatal_error

    async def _supervise_loop(self, spec: AutomationLoopSpec, stop_event: asyncio.Event) -> None:
        failures = 0
        while not stop_event.is_set():
            try:
                await self._run_loop(spec, stop_event)
                return
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                failures += 1
                logger.exception("%s automation loop crashed", spec.name)
                if failures >= self.max_supervision_failures:
                    stop_event.set()
                    raise AutomationControlPlaneError(
                        f"{spec.name} automation loop failed {failures} times"
                    ) from exc
                try:
                    await asyncio.wait_for(
                        stop_event.wait(),
                        timeout=self.supervision_backoff_seconds * failures,
                    )
                except TimeoutError:
                    continue

    async def _heartbeat_until_tick_completes(
        self,
        spec: AutomationLoopSpec,
        *,
        lease_seconds: int,
        tick_done: asyncio.Event,
    ) -> None:
        heartbeat_interval = min(
            max(spec.interval_seconds, 0.1),
            max(lease_seconds / 2, 0.5),
        )
        while not tick_done.is_set():
            try:
                await asyncio.wait_for(tick_done.wait(), timeout=heartbeat_interval)
                return
            except TimeoutError:
                async with AsyncSessionLocal() as session:
                    renewed = await renew_owned_automation_loop(
                        session,
                        loop_name=spec.name,
                        owner_id=self.owner_id,
                        lease_seconds=lease_seconds,
                    )
                    if not renewed:
                        await session.rollback()
                        raise AutomationOwnershipLostError(
                            f"{spec.name} automation lease lost during tick"
                        ) from None
                    await session.commit()

    async def _run_owned_tick(
        self,
        spec: AutomationLoopSpec,
        *,
        lease_seconds: int,
    ) -> None:
        async def _execute_tick() -> dict[str, int] | None:
            return await spec.tick()

        tick_done = asyncio.Event()
        tick_task: asyncio.Task[dict[str, int] | None] = asyncio.create_task(
            _execute_tick(),
            name=f"automation-tick:{spec.name}",
        )
        tick_task.add_done_callback(lambda _task: tick_done.set())
        heartbeat_task = asyncio.create_task(
            self._heartbeat_until_tick_completes(
                spec,
                lease_seconds=lease_seconds,
                tick_done=tick_done,
            ),
            name=f"automation-heartbeat:{spec.name}",
        )
        try:
            done, _pending = await asyncio.wait(
                {tick_task, heartbeat_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            if heartbeat_task in done:
                heartbeat_exc = heartbeat_task.exception()
                if heartbeat_exc is not None:
                    tick_task.cancel()
                    await asyncio.gather(tick_task, return_exceptions=True)
                    raise heartbeat_exc
            result = await tick_task
        except asyncio.CancelledError:
            tick_task.cancel()
            heartbeat_task.cancel()
            await asyncio.gather(tick_task, heartbeat_task, return_exceptions=True)
            raise
        except Exception as exc:  # noqa: BLE001
            tick_done.set()
            await heartbeat_task
            logger.exception("%s automation tick failed", spec.name)
            await _record_error(
                spec.name,
                owner_id=self.owner_id,
                lease_seconds=lease_seconds,
                error=exc,
            )
            return
        finally:
            tick_done.set()

        try:
            await heartbeat_task
        finally:
            if not heartbeat_task.done():
                heartbeat_task.cancel()
                await asyncio.gather(heartbeat_task, return_exceptions=True)

        await _record_success(
            spec.name,
            owner_id=self.owner_id,
            lease_seconds=lease_seconds,
            work_count=(result or {}).get("work_count", 0),
        )

    async def _run_loop(self, spec: AutomationLoopSpec, stop_event: asyncio.Event) -> None:
        lease_seconds = _lease_seconds(spec.interval_seconds)
        while not stop_event.is_set():
            acquired = False
            async with AsyncSessionLocal() as session:
                try:
                    acquired = await try_claim_automation_loop(
                        session,
                        loop_name=spec.name,
                        owner_id=self.owner_id,
                        lease_seconds=lease_seconds,
                    )
                    await session.commit()
                except Exception:
                    await session.rollback()
                    raise
            if acquired:
                try:
                    await self._run_owned_tick(spec, lease_seconds=lease_seconds)
                except AutomationOwnershipLostError:
                    logger.warning(
                        "%s automation lease lost; returning to standby",
                        spec.name,
                        extra={"owner_id": self.owner_id},
                    )
                    acquired = False
            try:
                await asyncio.wait_for(
                    stop_event.wait(),
                    timeout=spec.interval_seconds if acquired else self.standby_poll_seconds,
                )
            except TimeoutError:
                continue


def _row_status(row: AutomationLease | None, *, now: datetime) -> tuple[str, bool]:
    if row is None or row.owner_id is None or row.lease_expires_at is None:
        return "idle", False
    if row.lease_expires_at <= now:
        return "stale", False
    return "running", True


def _loop_payload(row: AutomationLease | None, *, now: datetime) -> AutomationLoopHealthSnapshot:
    status, active = _row_status(row, now=now)
    payload: AutomationLoopHealthSnapshot = {
        "status": status,
        "owner_id": row.owner_id if row else None,
        "owner_started_at": (
            row.owner_started_at.isoformat() if row and row.owner_started_at else None
        ),
        "lease_expires_at": (
            row.lease_expires_at.isoformat() if row and row.lease_expires_at else None
        ),
        "last_heartbeat_at": (
            row.last_heartbeat_at.isoformat() if row and row.last_heartbeat_at else None
        ),
        "ticks": row.tick_count if row else 0,
        "last_ok_at": row.last_ok_at.isoformat() if row and row.last_ok_at else None,
        "last_error": row.last_error if row else None,
        "active": active,
    }
    if row and row.loop_name == "scheduler":
        payload["jobs_leased"] = row.work_count
    return payload


async def automation_health_snapshot() -> AutomationHealthSnapshot:
    now = datetime.now(UTC)
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(AutomationLease).where(AutomationLease.loop_name.in_(AUTOMATION_LOOP_NAMES))
        )
        rows = {row.loop_name: row for row in result.scalars().all()}
    payloads = {name: _loop_payload(rows.get(name), now=now) for name in AUTOMATION_LOOP_NAMES}
    tasks_running = [name for name in AUTOMATION_LOOP_NAMES if payloads[name]["active"]]
    started: list[datetime] = []
    for name in AUTOMATION_LOOP_NAMES:
        row = rows.get(name)
        if row is not None and row.owner_started_at is not None and payloads[name]["active"]:
            started.append(row.owner_started_at)
    if any(payload["status"] == "stale" or payload["last_error"] for payload in payloads.values()):
        status = "degraded"
    elif tasks_running and len(tasks_running) == len(AUTOMATION_LOOP_NAMES):
        status = "ok"
    elif tasks_running:
        status = "degraded"
    else:
        status = "idle"
    return {
        "status": status,
        "started_at": min(started).isoformat() if started else None,
        "tasks_running": tasks_running,
        "maintenance": payloads["maintenance"],
        "outbox_relay": payloads["outbox_relay"],
        "scheduler": payloads["scheduler"],
    }


def public_automation_health_snapshot(
    snapshot: AutomationHealthSnapshot,
) -> PublicAutomationHealthSnapshot:
    def _public_loop(
        payload: AutomationLoopHealthSnapshot,
    ) -> PublicAutomationLoopHealthSnapshot:
        return {
            "status": payload["status"],
            "active": payload["active"],
        }

    return {
        "status": snapshot["status"],
        "started_at": snapshot["started_at"],
        "tasks_running": snapshot["tasks_running"],
        "maintenance": _public_loop(snapshot["maintenance"]),
        "outbox_relay": _public_loop(snapshot["outbox_relay"]),
        "scheduler": _public_loop(snapshot["scheduler"]),
    }


async def automation_process_healthcheck(
    *, local_owner_id: str | None = None
) -> dict[str, str]:
    async with AsyncSessionLocal() as session:
        await session.execute(select(1))
    payload: dict[str, str] = {"status": "ok"}
    if local_owner_id is not None:
        payload["local_owner_id"] = local_owner_id
    return payload


async def main() -> None:
    configure_logging(service_name=f"{settings.service_name}-automation", level=settings.log_level)
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop_event.set)
    await AutomationService().run(stop_event)


if __name__ == "__main__":
    asyncio.run(main())

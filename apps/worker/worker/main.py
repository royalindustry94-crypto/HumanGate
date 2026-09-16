"""Worker service entrypoint.

Bootstraps structured logging and a graceful-shutdown-aware run loop.
When WORKER_CREDENTIAL / WORKER_ID / API_BASE_URL are configured, the
loop registers, heartbeats, claims, and submits via the HTTP worker
protocol (WS1–WS3). Without credentials the process idles until signal
(local scaffold / CI without a provisioned worker).
"""

from __future__ import annotations

import asyncio
import logging
import signal
import uuid

import httpx

from worker.core.config import get_settings
from worker.core.logging import configure_logging

settings = get_settings()
configure_logging(service_name=settings.service_name, level=settings.log_level)
logger = logging.getLogger(__name__)


async def _run_http_loop(stop_event: asyncio.Event) -> None:
    from worker.client import ReferenceWorkerClient
    from worker.executors import draft_desk_executor

    credential = settings.worker_credential
    worker_id = settings.worker_id
    if not credential or not worker_id:
        logger.warning("worker credential/id not configured; idling until shutdown")
        await stop_event.wait()
        return

    async with httpx.AsyncClient(base_url=settings.api_base_url, timeout=30.0) as http:
        client = ReferenceWorkerClient(
            name=settings.worker_name,
            supported_stages=settings.supported_stages,
            http=http,
            credential=credential,
            worker_id=uuid.UUID(worker_id) if isinstance(worker_id, str) else worker_id,
            max_concurrency=settings.max_concurrency,
            heartbeat_interval_seconds=settings.heartbeat_interval_seconds,
            lease_seconds=settings.assignment_lease_seconds,
            executor=draft_desk_executor,
        )
        await client.register()
        logger.info("worker registered", extra={"worker_id": str(client.worker_id)})

        async def _heartbeat_loop() -> None:
            while not stop_event.is_set():
                try:
                    await client.heartbeat()
                except Exception:  # noqa: BLE001
                    logger.exception("heartbeat failed")
                try:
                    await asyncio.wait_for(
                        stop_event.wait(), timeout=client.heartbeat_interval_seconds
                    )
                except TimeoutError:
                    continue

        hb_task = asyncio.create_task(_heartbeat_loop())
        try:
            while not stop_event.is_set():
                try:
                    assignment = await client.claim_next()
                    if assignment is None:
                        try:
                            await asyncio.wait_for(stop_event.wait(), timeout=2.0)
                        except TimeoutError:
                            continue
                        break
                    await client.run_one(assignment=assignment)
                except Exception:  # noqa: BLE001
                    logger.exception("claim/execute cycle failed")
                    await asyncio.sleep(1.0)
        finally:
            hb_task.cancel()
            try:
                await client.drain()
                await client.deregister()
            except Exception:  # noqa: BLE001
                logger.exception("shutdown deregister failed")


async def main() -> None:
    stop_event = asyncio.Event()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop_event.set)

    logger.info(
        "worker starting",
        extra={"service": settings.service_name, "environment": settings.environment},
    )

    await _run_http_loop(stop_event)

    logger.info("worker shutting down", extra={"service": settings.service_name})


if __name__ == "__main__":
    asyncio.run(main())

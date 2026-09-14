"""P0-3: API lifespan stays stateless; automation runs elsewhere."""

from __future__ import annotations

import pytest
import pytest_asyncio
from sqlalchemy import text

from app import main as main_mod
from app.automation import automation_health_snapshot
from app.db.session import AsyncSessionLocal


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


@pytest.mark.asyncio
async def test_lifespan_does_not_start_automation_loops():
    previous = main_mod.settings.environment
    main_mod.settings.environment = "development"
    try:
        async with main_mod.lifespan(main_mod.app):
            snapshot = await automation_health_snapshot()
            assert snapshot["tasks_running"] == []
            assert snapshot["status"] == "idle"
        snapshot = await automation_health_snapshot()
        assert snapshot["tasks_running"] == []
    finally:
        main_mod.settings.environment = previous

"""P0-3: API lifespan stays stateless; automation runs elsewhere."""

from __future__ import annotations

import pytest

from app import main as main_mod
from app.automation import automation_health_snapshot


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

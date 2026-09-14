"""`cost_today_usd` must mean today (re-audit finding, 2026-09-14).

Both the strategy and research summaries reported a field named
`cost_today_usd` that was not scoped to any day: strategy summed every run
ever recorded for the workspace (so the figure also grew without bound), and
research summed whatever the last two runs cost, on any day. For a product
whose spend controls are a stated non-negotiable, a cost figure that does not
mean what it is named is a reporting defect, not a cosmetic one.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from app.db.session import AsyncSessionLocal
from app.models.research import ResearchRun
from app.models.strategy import StrategyRun
from app.services import research, strategy


async def _seed_run(model, *, workspace_id: str, cost: str, created_at: datetime, **extra) -> None:
    """Insert one completed run with an explicit created_at.

    Built through the ORM so every not-null column picks up its model default;
    `created_at` is set explicitly to place a run on a previous day.
    """
    async with AsyncSessionLocal() as session:
        run = model(
            id=uuid.uuid4(),
            workspace_id=uuid.UUID(workspace_id),
            deadline=created_at + timedelta(hours=1),
            max_provider_calls=1,
            max_tokens=1000,
            max_cost_usd=Decimal("10.0000"),
            max_attempts=1,
            status="succeeded",
            actual_cost_usd=Decimal(cost),
            created_at=created_at,
            updated_at=created_at,
            **extra,
        )
        session.add(run)
        await session.commit()


@pytest.mark.parametrize(
    ("model", "module", "extra"),
    [
        (StrategyRun, strategy, {"strategy_objective": "audit regression"}),
        (
            ResearchRun,
            research,
            {"research_objective": "audit regression", "max_searches": 1},
        ),
    ],
)
@pytest.mark.asyncio
async def test_cost_today_excludes_previous_days(client, new_user, model, module, extra):
    _user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "cost scope"})
    workspace_id = workspace.json()["id"]

    now = datetime.now(UTC)
    # Safely inside today (UTC) regardless of when the suite runs.
    today = now.replace(hour=12, minute=0, second=0, microsecond=0)
    if today > now:
        today = now
    await _seed_run(model, workspace_id=workspace_id, cost="1.25", created_at=today, **extra)
    await _seed_run(
        model,
        workspace_id=workspace_id,
        cost="99.00",
        created_at=now - timedelta(days=3),
        **extra,
    )

    async with AsyncSessionLocal() as session:
        summary = await module.summary(session, workspace_id=uuid.UUID(workspace_id))

    assert summary["cost_today_usd"] == Decimal("1.25"), (
        f"cost_today_usd must exclude runs from previous days; got {summary['cost_today_usd']}"
    )

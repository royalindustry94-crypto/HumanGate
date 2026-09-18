"""Workstream 4 acceptance tests: priority, back-pressure, budgets, spend lock.

Real PostgreSQL only. Concurrency tests use separate asyncpg connections.
RLS probes connect as ``app_runtime`` with ``request.jwt.claim.sub`` set.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError, ProgrammingError

from app.db.session import AsyncSessionLocal, RuntimeSessionLocal
from app.models.assignments import StageAssignment
from app.models.backpressure import ProviderConcurrencyBudget, WorkspaceBackpressureState
from app.models.config import SpendCap
from app.models.enums import (
    BackpressureState,
    ClaimOutcome,
    JobType,
    ReservationStatus,
    StageAssignmentStatus,
)
from app.models.events import OutboxEvent
from app.models.pipeline import PipelineRun
from app.models.scheduling import JobSchedule, WorkspaceConcurrencyLimit
from app.models.spend import SpendReservation
from app.orchestration import claiming, controller, scheduler
from app.orchestration.backpressure import (
    evaluate_workspace_backpressure,
    pending_depth,
)
from app.orchestration.events.types import BACKPRESSURE_CLEARED, BACKPRESSURE_ENTERED
from app.orchestration.priority import compute_age_boost, compute_effective_priority

STAGE = "scripting"


async def _make_user() -> dict:
    from tests.conftest import make_token

    user_id = str(uuid.uuid4())
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :e)"),
            {"id": user_id, "e": f"{user_id}@example.com"},
        )
        await session.commit()
    token = make_token(user_id=user_id)
    return {"user_id": user_id, "headers": {"Authorization": f"Bearer {token}"}}


async def _make_workspace(client, headers) -> str:
    r = await client.post("/workspaces", headers=headers, json={"name": "ws4"})
    assert r.status_code == 201, r.text
    return r.json()["id"]


async def _provision(client, headers, workspace_id, *, stages=None, max_concurrency=4):
    r = await client.post(
        f"/workspaces/{workspace_id}/workers",
        headers=headers,
        json={
            "name": f"w-{uuid.uuid4().hex[:8]}",
            "supported_stages": stages or [STAGE],
            "max_concurrency": max_concurrency,
        },
    )
    assert r.status_code == 201, r.text
    return r.json()


async def _bring_online(client, provisioned, *, current_load=0, max_concurrency=4):
    wh = {"Authorization": f"Bearer {provisioned['worker_secret']}"}
    await client.post(
        "/workers/register",
        headers=wh,
        json={
            "supported_stages": [STAGE],
            "capabilities": {"protocol_version": 1, "providers": [], "features": [STAGE]},
            "worker_version": "t",
            "max_concurrency": max_concurrency,
        },
    )
    await client.post(
        "/workers/heartbeat",
        headers=wh,
        json={"status": "online", "current_load": current_load},
    )
    return wh


async def _seed_assignment(
    workspace_id,
    *,
    stage=STAGE,
    priority=0,
    provider=None,
    created_at=None,
    status=StageAssignmentStatus.PENDING,
) -> uuid.UUID:
    async with AsyncSessionLocal() as session:
        item_id = str(uuid.uuid4())
        await session.execute(
            text("INSERT INTO content_items (id, workspace_id, topic) VALUES (:id,:ws,'t')"),
            {"id": item_id, "ws": workspace_id},
        )
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=uuid.UUID(workspace_id),
            content_item_id=uuid.UUID(item_id),
        )
        session.add(run)
        await session.flush()
        a = StageAssignment(
            id=uuid.uuid4(),
            workspace_id=uuid.UUID(workspace_id),
            pipeline_run_id=run.id,
            stage=stage,
            attempt_number=1,
            status=status,
            idempotency_key=f"{run.id}:{stage}:1:{uuid.uuid4().hex[:8]}",
            correlation_id=uuid.uuid4(),
            priority=priority,
            provider=provider,
        )
        if created_at is not None:
            a.created_at = created_at
        session.add(a)
        await session.commit()
        return a.id


@pytest_asyncio.fixture
async def ctx(client):
    u = await _make_user()
    ws = await _make_workspace(client, u["headers"])
    return {"client": client, "headers": u["headers"], "user_id": u["user_id"], "ws": ws}


# ---- priority helpers ----------------------------------------------------


def test_age_boost_math():
    now = datetime(2026, 7, 27, 12, 0, 0, tzinfo=UTC)
    created = now - timedelta(seconds=185)
    assert (
        compute_age_boost(created, now=now, interval_seconds=60, per_interval=1, boost_max=100) == 3
    )
    assert compute_effective_priority(10, created, now=now) == 10 + compute_age_boost(
        created, now=now
    )


# ---- claim priority ------------------------------------------------------


@pytest.mark.asyncio
async def test_claim_respects_priority_order(ctx):
    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)
    low = await _seed_assignment(ctx["ws"], priority=1)
    high = await _seed_assignment(ctx["ws"], priority=50)

    r = await ctx["client"].post("/workers/claim", headers=wh, json={})
    assert r.status_code == 200, r.text
    assert r.json()["outcome"] == "granted"
    assert r.json()["assignment"]["id"] == str(high)

    async with AsyncSessionLocal() as s:
        assert (await s.get(StageAssignment, high)).status == StageAssignmentStatus.DISPATCHED
        assert (await s.get(StageAssignment, low)).status == StageAssignmentStatus.PENDING


@pytest.mark.asyncio
async def test_age_boost_prevents_starvation(ctx):
    """Old low-priority eventually outranks fresh high-priority via age boost."""
    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)
    now = datetime.now(UTC)
    # 101 minutes old at priority 0 → boost capped at 100 → effective 100
    old_low = await _seed_assignment(ctx["ws"], priority=0, created_at=now - timedelta(minutes=101))
    # brand-new priority 99 → effective 99
    await _seed_assignment(ctx["ws"], priority=99, created_at=now)

    r = await ctx["client"].post("/workers/claim", headers=wh, json={})
    assert r.json()["outcome"] == "granted"
    assert r.json()["assignment"]["id"] == str(old_low)


@pytest.mark.asyncio
async def test_concurrent_claims_priority_skip_locked(ctx):
    """Two claimers cannot both get the same high-priority row."""
    prov_a = await _provision(ctx["client"], ctx["headers"], ctx["ws"], max_concurrency=1)
    prov_b = await _provision(ctx["client"], ctx["headers"], ctx["ws"], max_concurrency=1)
    wh_a = await _bring_online(ctx["client"], prov_a, max_concurrency=1)
    wh_b = await _bring_online(ctx["client"], prov_b, max_concurrency=1)
    high = await _seed_assignment(ctx["ws"], priority=100)
    await _seed_assignment(ctx["ws"], priority=1)

    results = await asyncio.gather(
        ctx["client"].post("/workers/claim", headers=wh_a, json={}),
        ctx["client"].post("/workers/claim", headers=wh_b, json={}),
    )
    bodies = [r.json() for r in results]
    granted = [b for b in bodies if b["outcome"] == "granted"]
    assert len(granted) == 2
    ids = {g["assignment"]["id"] for g in granted}
    assert str(high) in ids
    assert len(ids) == 2


# ---- provider budgets ----------------------------------------------------


@pytest.mark.asyncio
async def test_provider_budget_blocks_excess(ctx):
    r = await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/openai",
        headers=ctx["headers"],
        json={"max_concurrent": 1},
    )
    assert r.status_code == 200, r.text

    # One already in-flight for openai
    inflight = await _seed_assignment(
        ctx["ws"], provider="openai", status=StageAssignmentStatus.DISPATCHED
    )
    async with AsyncSessionLocal() as s:
        a = await s.get(StageAssignment, inflight)
        a.status = StageAssignmentStatus.DISPATCHED
        a.dispatched_at = datetime.now(UTC)
        await s.commit()

    pending = await _seed_assignment(ctx["ws"], provider="openai", priority=10)
    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)

    r = await ctx["client"].post("/workers/claim", headers=wh, json={})
    assert r.status_code == 200
    assert r.json()["outcome"] == "capacity"
    assert "provider budget" in r.json()["reason"]

    async with AsyncSessionLocal() as s:
        assert (await s.get(StageAssignment, pending)).status == StageAssignmentStatus.PENDING


@pytest.mark.asyncio
async def test_provider_budget_does_not_block_other_provider(ctx):
    await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/openai",
        headers=ctx["headers"],
        json={"max_concurrent": 1},
    )
    # Saturate openai
    inflight = await _seed_assignment(ctx["ws"], provider="openai")
    async with AsyncSessionLocal() as s:
        a = await s.get(StageAssignment, inflight)
        a.status = StageAssignmentStatus.DISPATCHED
        a.dispatched_at = datetime.now(UTC)
        await s.commit()

    other = await _seed_assignment(ctx["ws"], provider="anthropic", priority=5)
    await _seed_assignment(ctx["ws"], provider="openai", priority=99)

    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)
    r = await ctx["client"].post("/workers/claim", headers=wh, json={})
    assert r.json()["outcome"] == "granted"
    assert r.json()["assignment"]["id"] == str(other)


# ---- back-pressure -------------------------------------------------------


@pytest.mark.asyncio
async def test_backpressure_entered_and_cleared(ctx):
    async with AsyncSessionLocal() as s:
        s.add(
            WorkspaceConcurrencyLimit(
                id=uuid.uuid4(),
                workspace_id=uuid.UUID(ctx["ws"]),
                max_concurrent_assignments=50,
                max_per_scheduler_tick=4,
                queue_soft_limit=2,
                queue_hard_limit=4,
            )
        )
        await s.commit()

    a1 = await _seed_assignment(ctx["ws"])
    a2 = await _seed_assignment(ctx["ws"])

    async with AsyncSessionLocal() as s:
        snap = await evaluate_workspace_backpressure(s, uuid.UUID(ctx["ws"]))
        await s.commit()
        assert snap.state == BackpressureState.PRESSURED
        assert snap.changed is True
        assert snap.pending_depth == 2

    async with AsyncSessionLocal() as s:
        events = (
            (
                await s.execute(
                    select(OutboxEvent).where(
                        OutboxEvent.workspace_id == uuid.UUID(ctx["ws"]),
                        OutboxEvent.event_type == BACKPRESSURE_ENTERED,
                    )
                )
            )
            .scalars()
            .all()
        )
        assert len(events) == 1
        assert events[0].payload["state"] == "pressured"

    # Drop below soft → CLEARED
    async with AsyncSessionLocal() as s:
        for aid in (a1, a2):
            row = await s.get(StageAssignment, aid)
            row.status = StageAssignmentStatus.COMPLETED
        await s.commit()

    async with AsyncSessionLocal() as s:
        snap = await evaluate_workspace_backpressure(s, uuid.UUID(ctx["ws"]))
        await s.commit()
        assert snap.state == BackpressureState.NORMAL
        assert snap.changed is True

    async with AsyncSessionLocal() as s:
        cleared = (
            (
                await s.execute(
                    select(OutboxEvent).where(
                        OutboxEvent.workspace_id == uuid.UUID(ctx["ws"]),
                        OutboxEvent.event_type == BACKPRESSURE_CLEARED,
                    )
                )
            )
            .scalars()
            .all()
        )
        assert len(cleared) == 1


@pytest.mark.asyncio
async def test_throttled_scheduler_reduces_tick(ctx):
    async with AsyncSessionLocal() as s:
        # Isolate from shared-DB pollution
        await s.execute(
            text(
                "UPDATE job_schedule SET status = 'cancelled' WHERE status IN "
                "('pending'::job_schedule_status, 'leased'::job_schedule_status)"
            )
        )
        s.add(
            WorkspaceConcurrencyLimit(
                id=uuid.uuid4(),
                workspace_id=uuid.UUID(ctx["ws"]),
                max_concurrent_assignments=50,
                max_per_scheduler_tick=4,
                queue_soft_limit=1,
                queue_hard_limit=1,
            )
        )
        # Force THROTTLED state
        s.add(
            WorkspaceBackpressureState(
                workspace_id=uuid.UUID(ctx["ws"]),
                state=BackpressureState.THROTTLED,
                pending_depth=5,
                entered_at=datetime.now(UTC),
                updated_at=datetime.now(UTC),
            )
        )
        for _ in range(10):
            s.add(
                JobSchedule(
                    id=uuid.uuid4(),
                    workspace_id=uuid.UUID(ctx["ws"]),
                    job_type=JobType.STAGE_TIMEOUT,
                    ref_table="x",
                    ref_id=uuid.uuid4(),
                    run_after=datetime.now(UTC),
                    priority=0,
                )
            )
        await s.commit()

        leased = await scheduler.poll_and_lease(s, batch_size=100)
        await s.commit()
        # THROTTLED halves 4 → 2
        assert len(leased) == 2


@pytest.mark.asyncio
async def test_backpressure_never_drops_pending(ctx):
    async with AsyncSessionLocal() as s:
        s.add(
            WorkspaceConcurrencyLimit(
                id=uuid.uuid4(),
                workspace_id=uuid.UUID(ctx["ws"]),
                max_concurrent_assignments=10,
                max_per_scheduler_tick=5,
                queue_soft_limit=1,
                queue_hard_limit=1,
            )
        )
        await s.commit()

    ids = [await _seed_assignment(ctx["ws"]) for _ in range(3)]
    async with AsyncSessionLocal() as s:
        before = await pending_depth(s, uuid.UUID(ctx["ws"]))
        await evaluate_workspace_backpressure(s, uuid.UUID(ctx["ws"]))
        await s.commit()
        after = await pending_depth(s, uuid.UUID(ctx["ws"]))
        assert before == after == 3
        for aid in ids:
            assert (await s.get(StageAssignment, aid)).status == StageAssignmentStatus.PENDING


# ---- spend cap race ------------------------------------------------------


@pytest.mark.asyncio
async def test_spend_cap_concurrent_reservations(ctx):
    """Two concurrent reserve_spend calls for the last dollar → exactly one wins."""
    async with AsyncSessionLocal() as s:
        item_id = uuid.uuid4()
        await s.execute(
            text("INSERT INTO content_items (id, workspace_id, topic) VALUES (:id,:ws,'t')"),
            {"id": str(item_id), "ws": ctx["ws"]},
        )
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=uuid.UUID(ctx["ws"]),
            content_item_id=item_id,
            correlation_id=uuid.uuid4(),
        )
        s.add(run)
        s.add(
            SpendCap(
                id=uuid.uuid4(),
                workspace_id=uuid.UUID(ctx["ws"]),
                provider="openai",
                daily_cap_usd=Decimal("1.00"),
                monthly_cap_usd=Decimal("100.00"),
            )
        )
        await s.commit()
        run_id = run.id

    async def _race():
        async with AsyncSessionLocal() as session:
            run_row = await session.get(PipelineRun, run_id)
            result = await controller.reserve_spend(
                session,
                run=run_row,
                stage="scripting",
                provider="openai",
                estimated_cost_usd=Decimal("1.00"),
            )
            await session.commit()
            return result is not None

    outcomes = await asyncio.gather(_race(), _race())
    assert sum(1 for ok in outcomes if ok) == 1

    async with AsyncSessionLocal() as s:
        reserved = (
            (
                await s.execute(
                    select(SpendReservation).where(
                        SpendReservation.pipeline_run_id == run_id,
                        SpendReservation.status == ReservationStatus.RESERVED,
                    )
                )
            )
            .scalars()
            .all()
        )
        assert len(reserved) == 1


# ---- admin API authz -----------------------------------------------------


@pytest.mark.asyncio
async def test_admin_concurrency_api_authz(ctx):
    r = await ctx["client"].get(f"/workspaces/{ctx['ws']}/concurrency", headers=ctx["headers"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["backpressure_state"] == "normal"

    r = await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/concurrency",
        headers=ctx["headers"],
        json={"queue_soft_limit": 10, "queue_hard_limit": 20, "max_per_scheduler_tick": 3},
    )
    assert r.status_code == 200, r.text
    assert r.json()["queue_soft_limit"] == 10

    # Non-admin member cannot PUT
    editor = await _make_user()
    add = await ctx["client"].post(
        f"/workspaces/{ctx['ws']}/memberships",
        headers=ctx["headers"],
        json={"user_id": editor["user_id"], "role": "editor"},
    )
    assert add.status_code in (200, 201), add.text
    denied = await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/concurrency",
        headers=editor["headers"],
        json={"queue_soft_limit": 99},
    )
    assert denied.status_code == 403

    denied_budget = await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/x",
        headers=editor["headers"],
        json={"max_concurrent": 1},
    )
    assert denied_budget.status_code == 403

    # Admin can upsert then delete
    ok = await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/temp",
        headers=ctx["headers"],
        json={"max_concurrent": 3},
    )
    assert ok.status_code == 200
    listed = await ctx["client"].get(
        f"/workspaces/{ctx['ws']}/provider-budgets", headers=ctx["headers"]
    )
    assert listed.status_code == 200
    assert any(b["provider"] == "temp" for b in listed.json())
    deleted = await ctx["client"].delete(
        f"/workspaces/{ctx['ws']}/provider-budgets/temp", headers=ctx["headers"]
    )
    assert deleted.status_code == 204


@pytest.mark.asyncio
async def test_workspace_priority_tier_patch(ctx):
    r = await ctx["client"].patch(
        f"/workspaces/{ctx['ws']}",
        headers=ctx["headers"],
        json={"priority_tier": 5},
    )
    assert r.status_code == 200, r.text
    assert r.json()["priority_tier"] == 5


# ---- RLS adversarial -----------------------------------------------------


@pytest.mark.asyncio
async def test_provider_budgets_rls_adversarial(ctx):
    await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/openai",
        headers=ctx["headers"],
        json={"max_concurrent": 2},
    )
    outsider = await _make_user()
    async with RuntimeSessionLocal() as s:
        await s.execute(
            text("SELECT set_config('request.jwt.claim.sub', :sub, true)"),
            {"sub": outsider["user_id"]},
        )
        rows = (await s.execute(select(ProviderConcurrencyBudget))).scalars().all()
        assert rows == []
        # Direct INSERT must fail (no grant / no policy)
        try:
            await s.execute(
                text(
                    "INSERT INTO provider_concurrency_budgets "
                    "(id, workspace_id, provider, max_concurrent) "
                    "VALUES (:id, :ws, 'x', 1)"
                ),
                {"id": str(uuid.uuid4()), "ws": ctx["ws"]},
            )
            await s.commit()
            pytest.fail("runtime INSERT into provider_concurrency_budgets should fail")
        except (DBAPIError, ProgrammingError):
            await s.rollback()


@pytest.mark.asyncio
async def test_backpressure_state_rls_adversarial(ctx):
    async with AsyncSessionLocal() as s:
        s.add(
            WorkspaceBackpressureState(
                workspace_id=uuid.UUID(ctx["ws"]),
                state=BackpressureState.PRESSURED,
                pending_depth=3,
                entered_at=datetime.now(UTC),
                updated_at=datetime.now(UTC),
            )
        )
        await s.commit()

    # Member can SELECT
    async with RuntimeSessionLocal() as s:
        await s.execute(
            text("SELECT set_config('request.jwt.claim.sub', :sub, true)"),
            {"sub": ctx["user_id"]},
        )
        row = (
            await s.execute(
                select(WorkspaceBackpressureState).where(
                    WorkspaceBackpressureState.workspace_id == uuid.UUID(ctx["ws"])
                )
            )
        ).scalar_one()
        assert row.state == BackpressureState.PRESSURED

    outsider = await _make_user()
    async with RuntimeSessionLocal() as s:
        await s.execute(
            text("SELECT set_config('request.jwt.claim.sub', :sub, true)"),
            {"sub": outsider["user_id"]},
        )
        rows = (await s.execute(select(WorkspaceBackpressureState))).scalars().all()
        assert rows == []
        try:
            await s.execute(
                text(
                    "UPDATE workspace_backpressure_state SET pending_depth = 999 "
                    "WHERE workspace_id = :ws"
                ),
                {"ws": ctx["ws"]},
            )
            await s.commit()
            pytest.fail("runtime UPDATE of backpressure state should fail")
        except (DBAPIError, ProgrammingError):
            await s.rollback()


@pytest.mark.asyncio
async def test_migration_0029_roundtrip():
    async with AsyncSessionLocal() as s:
        cols = (
            await s.execute(
                text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'stage_assignments' "
                    "AND column_name IN ('priority', 'provider')"
                )
            )
        ).fetchall()
        assert {c[0] for c in cols} == {"priority", "provider"}
        tables = (
            await s.execute(
                text(
                    "SELECT tablename FROM pg_tables WHERE schemaname='public' "
                    "AND tablename IN ("
                    "'workspace_backpressure_state','provider_concurrency_budgets')"
                )
            )
        ).fetchall()
        assert {t[0] for t in tables} == {
            "workspace_backpressure_state",
            "provider_concurrency_budgets",
        }
        forced = (
            await s.execute(
                text(
                    "SELECT relname FROM pg_class "
                    "WHERE relname IN ("
                    "'workspace_backpressure_state','provider_concurrency_budgets') "
                    "AND relrowsecurity AND relforcerowsecurity"
                )
            )
        ).fetchall()
        assert len(forced) == 2


@pytest.mark.asyncio
async def test_direct_claim_priority_ordering(ctx):
    """Service-layer claim with clock injection honors age boost."""
    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    await _bring_online(ctx["client"], prov)
    now = datetime.now(UTC)
    old = await _seed_assignment(ctx["ws"], priority=0, created_at=now - timedelta(minutes=101))
    await _seed_assignment(ctx["ws"], priority=99, created_at=now)

    async with AsyncSessionLocal() as s:
        result = await claiming.claim_assignment(s, worker_id=uuid.UUID(prov["worker_id"]), now=now)
        await s.commit()
        assert result.outcome == ClaimOutcome.GRANTED
        assert result.assignment is not None
        assert result.assignment.id == old


@pytest.mark.asyncio
async def test_saturated_provider_retires_in_one_pass_not_row_by_row(ctx):
    """Audit M-2: a saturated provider must not exhaust the candidate budget.

    The claim loop used to exclude each skipped candidate by its own id, so a
    saturated provider was re-discovered once per row. With more saturated rows
    ahead of the eligible one than the loop had passes, it ran out of
    iterations and reported `capacity` even though a claimable assignment
    existed. The scan now excludes saturated (workspace, provider) pairs in
    SQL, so the eligible row is reached whatever the count.

    The row count is an explicit constant rather than the former
    `claim_candidate_batch_size` setting, which no longer exists: reading a
    knob the production path ignored made this test look configurable when it
    was not.
    """
    saturated_rows = 37

    await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/openai",
        headers=ctx["headers"],
        json={"max_concurrent": 1},
    )
    # Saturate openai with one in-flight assignment.
    inflight = await _seed_assignment(ctx["ws"], provider="openai")
    async with AsyncSessionLocal() as s:
        a = await s.get(StageAssignment, inflight)
        a.status = StageAssignmentStatus.DISPATCHED
        a.dispatched_at = datetime.now(UTC)
        await s.commit()

    # Many blocked-provider candidates, all ranked above the one claimable row.
    for _ in range(saturated_rows):
        await _seed_assignment(ctx["ws"], provider="openai", priority=99)
    claimable = await _seed_assignment(ctx["ws"], provider="anthropic", priority=1)

    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)
    r = await ctx["client"].post("/workers/claim", headers=wh, json={})

    assert r.json()["outcome"] == "granted", (
        "a claimable assignment existed behind a saturated provider; the loop "
        "must not spend its candidate budget re-skipping that provider row by row"
    )
    assert r.json()["assignment"]["id"] == str(claimable)


@pytest.mark.asyncio
async def test_null_provider_candidates_survive_saturated_pair_exclusion(ctx):
    """`NULL NOT IN (...)` is NULL, not true.

    A null-provider assignment always has capacity, so it must stay eligible
    once some other provider has been excluded -- a bare NOT IN would drop it.
    """
    await ctx["client"].put(
        f"/workspaces/{ctx['ws']}/provider-budgets/openai",
        headers=ctx["headers"],
        json={"max_concurrent": 1},
    )
    inflight = await _seed_assignment(ctx["ws"], provider="openai")
    async with AsyncSessionLocal() as s:
        a = await s.get(StageAssignment, inflight)
        a.status = StageAssignmentStatus.DISPATCHED
        a.dispatched_at = datetime.now(UTC)
        await s.commit()

    await _seed_assignment(ctx["ws"], provider="openai", priority=99)
    unbudgeted = await _seed_assignment(ctx["ws"], provider=None, priority=1)

    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)
    r = await ctx["client"].post("/workers/claim", headers=wh, json={})

    assert r.json()["outcome"] == "granted"
    assert r.json()["assignment"]["id"] == str(unbudgeted)


@pytest.mark.asyncio
@pytest.mark.parametrize("pair_count", [3, 9])
async def test_saturated_pair_cost_is_flat_and_the_claimable_row_is_still_reached(ctx, pair_count):
    """Copilot rounds 3-4 on M-2/C3: saturated pairs must cost nothing to skip.

    History of this one regression. Excluding whole (workspace, provider) pairs
    fixed the row-by-row rescan, but the scan then walked one pair per pass and
    fed the retired pairs back as `tuple_(...).notin_(saturated)`:

      * capped first at a configured batch size, then at a flat 10,000, it
        stopped with a claimable row still behind the retired pairs and
        reported `capacity` -- the false-capacity bug (C3); and
      * uncapped, the pair list was bounded by nothing but the data. Each pair
        contributes two bind parameters, so ~32k saturated pairs blow through
        PostgreSQL's 65535-parameter limit, and long before that every pair
        costs a candidate query plus a budget lock and an in-flight COUNT.

    Both die the same way: the exclusion moved into SQL, so nothing
    accumulates. This asserts that directly -- the statement count for a claim
    must not grow with the number of saturated pairs. Tripling the pairs while
    the cost stays flat is the observable difference between an in-SQL
    exclusion and any Python-side accumulation, at a size that runs fast.

    Against the accumulating implementation the statement count rises with
    `pair_count` and this fails; against any capped one, `granted` fails.
    """
    from sqlalchemy import event

    from app.db.session import AsyncSessionLocal as _Sessions

    providers = [f"prov{n}" for n in range(pair_count)]
    for provider in providers:
        r = await ctx["client"].put(
            f"/workspaces/{ctx['ws']}/provider-budgets/{provider}",
            headers=ctx["headers"],
            json={"max_concurrent": 1},
        )
        assert r.status_code == 200, r.text
        # Saturate it with one in-flight assignment...
        inflight = await _seed_assignment(ctx["ws"], provider=provider)
        async with AsyncSessionLocal() as s:
            a = await s.get(StageAssignment, inflight)
            a.status = StageAssignmentStatus.DISPATCHED
            a.dispatched_at = datetime.now(UTC)
            await s.commit()
        # ...and rank a blocked candidate above the claimable one.
        await _seed_assignment(ctx["ws"], provider=provider, priority=99)

    claimable = await _seed_assignment(ctx["ws"], provider="unbudgeted", priority=1)

    prov = await _provision(ctx["client"], ctx["headers"], ctx["ws"])
    wh = await _bring_online(ctx["client"], prov)

    statements: list[str] = []

    def _record(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    engine = _Sessions.kw["bind"].sync_engine
    event.listen(engine, "before_cursor_execute", _record)
    try:
        r = await ctx["client"].post("/workers/claim", headers=wh, json={})
    finally:
        event.remove(engine, "before_cursor_execute", _record)

    assert r.json()["outcome"] == "granted", (
        "with more saturated pairs than any plausible cap, the scan must still "
        "reach the claimable assignment instead of reporting capacity"
    )
    assert r.json()["assignment"]["id"] == str(claimable)

    candidate_scans = [st for st in statements if "FOR UPDATE" in st and "SKIP LOCKED" in st]
    assert len(candidate_scans) == 1, (
        f"{pair_count} saturated pairs produced {len(candidate_scans)} candidate "
        "scans; saturated pairs must be excluded in SQL, not walked one per pass"
    )
    # The exclusion must not be carried as a per-pair parameter list either.
    scan = candidate_scans[0]
    assert "NOT IN" not in scan.upper(), (
        "the candidate query still carries an IN-list of retired pairs, which "
        "grows two bind parameters per pair until it exceeds PostgreSQL's limit"
    )


def test_the_scan_threshold_is_not_used_as_a_loop_bound() -> None:
    """The diagnostic threshold must never bound iteration.

    A future edit could reintroduce `for _ in range(_CLAIM_SCAN_REPORT_AFTER)`,
    which would restore the C3 false-capacity case. Pin the shape.
    """
    import inspect

    from app.orchestration import claiming

    source = inspect.getsource(claiming.claim_assignment)
    assert "range(_CLAIM_SCAN_REPORT_AFTER)" not in source
    assert "while True:" in source

"""Query-count regression for the worker monitor (audit H-1, 2026-09-14).

`operations_dashboard.workers()` used to issue six queries per worker inside
its loop — `6W + 1` round-trips for W workers, on a route the dashboard
auto-refreshes on an interval. These tests pin both halves of the fix: the
round-trip count no longer grows with the worker set, and the aggregated
values still match what the per-worker queries produced.
"""

from __future__ import annotations

import uuid
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import event, text

from app.db.session import AsyncSessionLocal, engine
from app.services import operations_dashboard, operations_mission


@contextmanager
def count_queries():
    """Count SQL statements executed on the owner engine inside the block."""
    counter = {"n": 0}

    def _before(conn, cursor, statement, parameters, context, executemany):
        counter["n"] += 1

    event.listen(engine.sync_engine, "before_cursor_execute", _before)
    try:
        yield counter
    finally:
        event.remove(engine.sync_engine, "before_cursor_execute", _before)


async def _seed_worker(session, *, workspace_id: str, run_id: str, name: str) -> uuid.UUID:
    """One worker with a known assignment mix: 2 completed (1 today, 1 old),
    1 failed today, 1 pending, and 1 retry attempt among them."""
    worker_id = uuid.uuid4()
    now = datetime.now(UTC)
    await session.execute(
        text(
            """
            INSERT INTO worker_registry (
                id, workspace_id, name, supported_stages, status,
                max_concurrency, current_load, health_score,
                last_heartbeat_at, registered_at, instance_key, drain, capabilities
            ) VALUES (
                :id, :ws, :name, ARRAY['scripting'], 'busy'::worker_status,
                2, 1, 98, :now, :now, :instance_key, false, CAST('{}' AS jsonb)
            )
            """
        ),
        {
            "id": str(worker_id),
            "ws": workspace_id,
            "name": name,
            "now": now,
            "instance_key": f"qe-{worker_id}",
        },
    )

    async def _assignment(status: str, *, attempt: int, completed_at, updated_at) -> None:
        assignment_id = uuid.uuid4()
        await session.execute(
            text(
                """
                INSERT INTO stage_assignments (
                    id, workspace_id, pipeline_run_id, stage, attempt_number,
                    worker_id, status, idempotency_key, completed_at,
                    updated_at, priority, provider
                ) VALUES (
                    :id, :ws, :run, 'scripting'::content_stage, :attempt, :worker,
                    CAST(:status AS stage_assignment_status), :idem, :completed,
                    :updated, 0, 'draft_desk'
                )
                """
            ),
            {
                "id": str(assignment_id),
                "ws": workspace_id,
                "run": run_id,
                "attempt": attempt,
                "worker": str(worker_id),
                "status": status,
                "idem": f"qe-{assignment_id}",
                "completed": completed_at,
                "updated": updated_at,
            },
        )

    await _assignment("completed", attempt=1, completed_at=now, updated_at=now)
    old = now - timedelta(days=3)
    await _assignment("completed", attempt=2, completed_at=old, updated_at=old)
    await _assignment("failed", attempt=1, completed_at=None, updated_at=now)
    await _assignment("pending", attempt=1, completed_at=None, updated_at=now)
    return worker_id


async def _workspace_with_workers(client, headers, count: int) -> str:
    workspace = await client.post("/workspaces", headers=headers, json={"name": "QE"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]
    draft = await client.post(
        f"/workspaces/{workspace_id}/content-jobs",
        headers=headers,
        json={"topic": "qe", "script_body": "body"},
    )
    assert draft.status_code == 201, draft.text
    run_id = draft.json()["pipeline_run_id"]
    async with AsyncSessionLocal() as session:
        for index in range(count):
            await _seed_worker(
                session, workspace_id=workspace_id, run_id=run_id, name=f"qe-worker-{index}"
            )
        await session.commit()
    return workspace_id


@pytest.mark.asyncio
async def test_worker_monitor_query_count_does_not_grow_with_worker_count(client, new_user):
    """The whole point of H-1: 1 worker and 5 workers cost the same round-trips."""
    _user_id, _token, headers = new_user

    one = await _workspace_with_workers(client, headers, 1)
    many = await _workspace_with_workers(client, headers, 5)

    async with AsyncSessionLocal() as session:
        with count_queries() as counter:
            await operations_dashboard.workers(session, uuid.UUID(one))
        one_worker_queries = counter["n"]

        with count_queries() as counter:
            await operations_dashboard.workers(session, uuid.UUID(many))
        five_worker_queries = counter["n"]

    assert one_worker_queries == five_worker_queries, (
        f"worker monitor issued {one_worker_queries} queries for 1 worker and "
        f"{five_worker_queries} for 5 — the per-worker N+1 has regressed"
    )
    # Registrations + totals + active + pending-by-stage. Kept as an upper
    # bound so an added projection is a deliberate edit, not a silent 6W loop.
    assert five_worker_queries <= 5


@pytest.mark.asyncio
async def test_worker_monitor_values_match_the_seeded_assignment_mix(client, new_user):
    """Aggregate rewrite must not change what the dashboard reports."""
    _user_id, _token, headers = new_user
    workspace_id = await _workspace_with_workers(client, headers, 3)

    async with AsyncSessionLocal() as session:
        out = await operations_dashboard.workers(session, uuid.UUID(workspace_id))

    # The monitor deliberately also lists global workers (workspace_id IS NULL)
    # that other tests register, so assert against the ones seeded here.
    seeded = [row for row in out.workers if row.name.startswith("qe-worker-")]
    assert len(seeded) == 3
    for row in seeded:
        assert row.jobs_completed == 2, "one completed today + one completed days ago"
        assert row.jobs_completed_today == 1
        assert row.jobs_failed == 1
        assert row.jobs_failed_today == 1
        assert row.retry_count == 1, "exactly one assignment has attempt_number > 1"
        # Queue is workspace-wide pending depth for the stages this worker
        # supports: 3 workers each seeded one pending 'scripting' assignment.
        assert row.queue == 3
        assert row.lease_status == "none", "no dispatched/acknowledged assignment seeded"


@pytest.mark.asyncio
async def test_aggregate_helpers_short_circuit_on_an_empty_worker_set():
    """No worker ids must mean no query at all, not `IN ()`.

    Asserted on the helpers directly: `workers()` itself always sees the
    global (workspace_id IS NULL) registrations other tests leave behind, so
    an empty set is not reachable through the public projection.
    """
    async with AsyncSessionLocal() as session:
        with count_queries() as counter:
            totals = await operations_dashboard._worker_assignment_totals(
                session, uuid.uuid4(), [], day_start=datetime.now(UTC)
            )
            active = await operations_dashboard._worker_active_assignments(
                session, uuid.uuid4(), []
            )

    assert totals == {}
    assert active == {}
    assert counter["n"] == 0


@pytest.mark.asyncio
async def test_worker_timeline_query_count_does_not_grow_with_worker_count(client, new_user):
    """Mission worker timeline should remain set-based for assignment history."""
    _user_id, _token, headers = new_user

    one = await _workspace_with_workers(client, headers, 1)
    many = await _workspace_with_workers(client, headers, 5)
    large = await _workspace_with_workers(client, headers, 25)

    async with AsyncSessionLocal() as session:
        with count_queries() as counter:
            one_out = await operations_mission.worker_timeline(session, uuid.UUID(one))
        one_worker_queries = counter["n"]

        with count_queries() as counter:
            many_out = await operations_mission.worker_timeline(session, uuid.UUID(many))
        five_worker_queries = counter["n"]

        with count_queries() as counter:
            large_out = await operations_mission.worker_timeline(session, uuid.UUID(large))
        twenty_five_worker_queries = counter["n"]

    one_seeded = [row for row in one_out.workers if row.name.startswith("qe-worker-")]
    many_seeded = [row for row in many_out.workers if row.name.startswith("qe-worker-")]
    large_seeded = [row for row in large_out.workers if row.name.startswith("qe-worker-")]
    assert len(one_seeded) == 1
    assert len(many_seeded) == 5
    assert len(large_seeded) == 25
    assert all(worker.jobs for worker in one_seeded + many_seeded + large_seeded)

    assert one_worker_queries == five_worker_queries == twenty_five_worker_queries, (
        f"worker timeline issued {one_worker_queries} queries for 1 worker and "
        f"{five_worker_queries} for 5 and {twenty_five_worker_queries} for 25 "
        "— the per-worker N+1 has regressed"
    )
    assert twenty_five_worker_queries <= 2

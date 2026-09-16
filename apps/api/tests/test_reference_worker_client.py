"""End-to-end: provision a worker, claim via HTTP, ack/renew/submit —
proving the WS3 worker-side contract without any real generation logic.
"""

import asyncio
import os
import sys
import uuid
from pathlib import Path

os.environ.setdefault(
    "DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/content_orchestrator_test"
)
os.environ.setdefault(
    "APP_DATABASE_URL",
    "postgresql://app_runtime:app_runtime@localhost:5432/content_orchestrator_test",
)
os.environ.setdefault("SUPABASE_JWT_SECRET", "test-supabase-jwt-secret-0123456789abcdef")

# apps/worker isn't installed as a dependency of apps/api; reach it via a
# relative path so this integration test can import the reference client
# without requiring a separate package install step in CI.
sys.path.append(str(Path(__file__).resolve().parents[2] / "worker"))

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import select, text
from worker.client import ReferenceWorkerClient  # noqa: E402

from app.core.config import get_settings
from app.db.session import AsyncSessionLocal
from app.main import app
from app.models.assignments import StageAssignment
from app.models.enums import StageAssignmentStatus
from app.models.pipeline import PipelineRun
from app.models.workflow import WorkflowDefinition, WorkflowStage
from app.orchestration import controller, dispatcher
from app.orchestration.provider_effects import ensure_provider_effect_key
from tests.conftest import make_token


async def _make_workspace_item(session):
    ws, user, item = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    await session.execute(
        text("INSERT INTO auth.users (id, email) VALUES (:id, :e)"),
        {"id": user, "e": f"{user}@x.com"},
    )
    await session.execute(
        text("INSERT INTO workspaces (id, name, created_by) VALUES (:id, 'w', :u)"),
        {"id": ws, "u": user},
    )
    # reserve_spend now fails closed with no SpendCap row (2026-09-07 fix);
    # seed a permissive cap so these orchestration-mechanic tests remain
    # about claiming/dispatch/recovery, not spend enforcement.
    await session.execute(
        text(
            "INSERT INTO spend_caps (workspace_id, daily_cap_usd, monthly_cap_usd) "
            "VALUES (:ws, 999999, 999999)"
        ),
        {"ws": ws},
    )
    await session.execute(
        text(
            "INSERT INTO workspace_memberships (workspace_id, user_id, role) "
            "VALUES (:ws, :u, 'admin')"
        ),
        {"ws": ws, "u": user},
    )
    await session.execute(
        text("INSERT INTO content_items (id, workspace_id, topic) VALUES (:id, :ws, 't')"),
        {"id": item, "ws": ws},
    )
    return uuid.UUID(ws), uuid.UUID(item), user


@pytest.mark.asyncio
async def test_reference_worker_client_completes_a_stage_end_to_end():
    async with AsyncSessionLocal() as session:
        # Park all pre-existing online/busy workers so dispatch_stage sees no
        # eligible worker and creates the assignment as PENDING (not DISPATCHED).
        await session.execute(
            text(
                "UPDATE worker_registry SET status = 'offline'::worker_status "
                "WHERE status IN ('online'::worker_status, 'busy'::worker_status)"
            )
        )

        ws, item, admin_user = await _make_workspace_item(session)
        definition = WorkflowDefinition(
            id=uuid.uuid4(),
            workspace_id=ws,
            name="one-stage",
            version=1,
        )
        session.add(definition)
        await session.flush()
        session.add(
            WorkflowStage(
                id=uuid.uuid4(),
                workspace_id=ws,
                definition_id=definition.id,
                stage_key="scripting",
                ordinal=1,
                is_terminal=True,
            )
        )
        await session.flush()

        run = PipelineRun(id=uuid.uuid4(), workspace_id=ws, content_item_id=item)
        session.add(run)
        await session.flush()
        await controller.start_run(session, run=run, definition=definition)

        dispatched = await dispatcher.dispatch_stage(
            session,
            workspace_id=ws,
            pipeline_run_id=run.id,
            stage="scripting",
            attempt_number=1,
            correlation_id=run.correlation_id,
            trace_id=run.trace_id,
        )
        await session.commit()
        run_id = run.id
        assert dispatched.assignment is not None
        assignment_id = dispatched.assignment.id

    http = httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test")
    admin_headers = {"Authorization": f"Bearer {make_token(user_id=admin_user)}"}
    provision = await http.post(
        f"/workspaces/{ws}/workers",
        headers=admin_headers,
        json={"name": "ref-1", "supported_stages": ["scripting"], "max_concurrency": 1},
    )
    assert provision.status_code == 201, provision.text
    provisioned = provision.json()

    client = ReferenceWorkerClient(
        name="ref-1",
        supported_stages=["scripting"],
        http=http,
        credential=provisioned["worker_secret"],
        worker_id=provisioned["worker_id"],
    )
    await client.register()
    await client.heartbeat()

    # Retire stale PENDING assignments from prior test runs so claim_next
    # picks up THIS test's assignment.
    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                "UPDATE stage_assignments SET status = 'failed' "
                "WHERE status = 'pending' AND id != :id"
            ),
            {"id": str(assignment_id)},
        )
        await session.commit()

    claimed = await client.claim_next()
    assert claimed is not None
    assert claimed["id"] == str(assignment_id)
    await client.heartbeat()
    await client.run_one(assignment=claimed)

    await http.aclose()

    async with AsyncSessionLocal() as session:
        result = await session.execute(select(PipelineRun).where(PipelineRun.id == run_id))
        refreshed = result.scalar_one()
        assert refreshed.status == "succeeded"
        from app.models.assignments import StageAssignment

        a = await session.get(StageAssignment, assignment_id)
        assert a.status == StageAssignmentStatus.COMPLETED


@pytest.mark.asyncio
async def test_reference_worker_client_refuses_to_reexecute_after_crash_recovery():
    """Regression (2026-09-07 audit finding / TD-077, client-side half):
    if a *prior* attempt of this assignment already reserved the provider
    effect key (simulated here by inserting it directly, as recovery.py's
    crash/lease-expiry path would produce), the reference client must not
    call the executor again — it cannot know whether that prior attempt
    already triggered a real, billable provider call. It should submit an
    explicit failure instead of silently re-running or fabricating success.
    """
    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                "UPDATE worker_registry SET status = 'offline'::worker_status "
                "WHERE status IN ('online'::worker_status, 'busy'::worker_status)"
            )
        )
        ws, item, admin_user = await _make_workspace_item(session)
        definition = WorkflowDefinition(
            id=uuid.uuid4(),
            workspace_id=ws,
            name="one-stage-crash",
            version=1,
        )
        session.add(definition)
        await session.flush()
        session.add(
            WorkflowStage(
                id=uuid.uuid4(),
                workspace_id=ws,
                definition_id=definition.id,
                stage_key="scripting",
                ordinal=1,
                is_terminal=True,
            )
        )
        await session.flush()

        run = PipelineRun(id=uuid.uuid4(), workspace_id=ws, content_item_id=item)
        session.add(run)
        await session.flush()
        await controller.start_run(session, run=run, definition=definition)

        dispatched = await dispatcher.dispatch_stage(
            session,
            workspace_id=ws,
            pipeline_run_id=run.id,
            stage="scripting",
            attempt_number=1,
            correlation_id=run.correlation_id,
            trace_id=run.trace_id,
        )
        await session.commit()
        assert dispatched.assignment is not None
        assignment_id = dispatched.assignment.id

        # Simulate a prior attempt of this same assignment having already
        # reserved the provider effect key (e.g. it acked, triggered a real
        # provider call, then crashed before submit — recovery.py bumps
        # attempt_number and re-queues the same assignment for a new claim).
        await ensure_provider_effect_key(
            session,
            workspace_id=ws,
            assignment_id=assignment_id,
            attempt_number=1,
        )
        await session.commit()

    http = httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test")
    admin_headers = {"Authorization": f"Bearer {make_token(user_id=admin_user)}"}
    provision = await http.post(
        f"/workspaces/{ws}/workers",
        headers=admin_headers,
        json={"name": "ref-crash-1", "supported_stages": ["scripting"], "max_concurrency": 1},
    )
    assert provision.status_code == 201, provision.text
    provisioned = provision.json()

    client = ReferenceWorkerClient(
        name="ref-crash-1",
        supported_stages=["scripting"],
        http=http,
        credential=provisioned["worker_secret"],
        worker_id=provisioned["worker_id"],
    )
    await client.register()
    await client.heartbeat()

    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                "UPDATE stage_assignments SET status = 'failed' "
                "WHERE status = 'pending' AND id != :id"
            ),
            {"id": str(assignment_id)},
        )
        await session.commit()

    claimed = await client.claim_next()
    assert claimed is not None
    assert claimed["id"] == str(assignment_id)

    executed = False

    async def _executor_that_must_not_run(_context):
        nonlocal executed
        executed = True
        return True, {"should": "never happen"}, ""

    client.executor = _executor_that_must_not_run
    await client.run_one(assignment=claimed)
    await http.aclose()

    assert executed is False, "client executed the provider call despite a prior reservation"

    async with AsyncSessionLocal() as session:
        from app.models.assignments import StageAssignment
        from app.models.pipeline import PipelineStageRun

        a = await session.get(StageAssignment, assignment_id)
        assert a.status == StageAssignmentStatus.FAILED

        stage_run = (
            (
                await session.execute(
                    select(PipelineStageRun)
                    .where(PipelineStageRun.pipeline_run_id == a.pipeline_run_id)
                    .order_by(PipelineStageRun.completed_at.desc())
                )
            )
            .scalars()
            .first()
        )
        assert stage_run is not None
        assert stage_run.status == "failed"
        assert "prior attempt" in (stage_run.error_message or "")


@pytest.mark.asyncio
async def test_reference_worker_client_renews_lease_during_slow_execution():
    settings = get_settings()
    original_lease_seconds = settings.assignment_lease_seconds
    settings.assignment_lease_seconds = 1
    try:
        async with AsyncSessionLocal() as session:
            await session.execute(
                text(
                    "UPDATE worker_registry SET status = 'offline'::worker_status "
                    "WHERE status IN ('online'::worker_status, 'busy'::worker_status)"
                )
            )
            ws, item, admin_user = await _make_workspace_item(session)
            definition = WorkflowDefinition(
                id=uuid.uuid4(),
                workspace_id=ws,
                name="one-stage-slow",
                version=1,
            )
            session.add(definition)
            await session.flush()
            session.add(
                WorkflowStage(
                    id=uuid.uuid4(),
                    workspace_id=ws,
                    definition_id=definition.id,
                    stage_key="scripting",
                    ordinal=1,
                    is_terminal=True,
                )
            )
            await session.flush()

            run = PipelineRun(id=uuid.uuid4(), workspace_id=ws, content_item_id=item)
            session.add(run)
            await session.flush()
            await controller.start_run(session, run=run, definition=definition)

            dispatched = await dispatcher.dispatch_stage(
                session,
                workspace_id=ws,
                pipeline_run_id=run.id,
                stage="scripting",
                attempt_number=1,
                correlation_id=run.correlation_id,
                trace_id=run.trace_id,
            )
            await session.commit()
            run_id = run.id
            assert dispatched.assignment is not None
            assignment_id = dispatched.assignment.id

        admin_headers = {"Authorization": "Bearer " + make_token(user_id=admin_user)}
        async with httpx.AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as http:
            async def _provision_client(name: str) -> ReferenceWorkerClient:
                provision = await http.post(
                    f"/workspaces/{ws}/workers",
                    headers=admin_headers,
                    json={"name": name, "supported_stages": ["scripting"], "max_concurrency": 1},
                )
                assert provision.status_code == 201, provision.text
                provisioned = provision.json()
                client = ReferenceWorkerClient(
                    name=name,
                    supported_stages=["scripting"],
                    http=http,
                    credential=provisioned["worker_secret"],
                    worker_id=provisioned["worker_id"],
                    heartbeat_interval_seconds=1,
                    lease_seconds=1,
                )
                await client.register()
                await client.heartbeat()
                return client

            slow_client = await _provision_client("ref-slow-1")
            rival_client = await _provision_client("ref-slow-2")

            claimed = await slow_client.claim_next()
            assert claimed is not None
            assert claimed["id"] == str(assignment_id)

            started = asyncio.Event()
            finish = asyncio.Event()

            async def _slow_executor(_context):
                started.set()
                await finish.wait()
                return True, {"ok": True}, ""

            slow_client.executor = _slow_executor
            run_task = asyncio.create_task(slow_client.run_one(assignment=claimed))
            await asyncio.wait_for(started.wait(), timeout=5)

            await asyncio.sleep(1.3)

            async with AsyncSessionLocal() as session:
                reaped = await dispatcher.reap_expired_leases(session)
                await session.commit()
            assert assignment_id not in {result.assignment.id for result in reaped}

            rival_claim = await rival_client.claim_next()
            assert rival_claim is None

            finish.set()
            await asyncio.wait_for(run_task, timeout=5)

        async with AsyncSessionLocal() as session:
            refreshed = (
                await session.execute(select(PipelineRun).where(PipelineRun.id == run_id))
            ).scalar_one()
            assert refreshed.status == "succeeded"
            assignment = await session.get(StageAssignment, assignment_id)
            assert assignment.status == StageAssignmentStatus.COMPLETED
            assert assignment.attempt_number == 1
            assert assignment.claim_count == 1
    finally:
        settings.assignment_lease_seconds = original_lease_seconds

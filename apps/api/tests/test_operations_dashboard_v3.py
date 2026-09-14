"""Operations Dashboard V3: Mission Control real-data projections and actions."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import text

from app.db.session import AsyncSessionLocal


@pytest.mark.asyncio
async def test_mission_control_modules_and_actions(client, new_user):
    user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "Mission Control"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]

    draft = await client.post(
        f"/workspaces/{workspace_id}/content-jobs",
        headers=headers,
        json={"topic": "Mission topic", "script_body": "Real draft"},
    )
    assert draft.status_code == 201, draft.text
    run_id = draft.json()["pipeline_run_id"]
    content_id = draft.json()["content_item_id"]
    worker_id = uuid.uuid4()
    global_worker_id = uuid.uuid4()
    assignment_id = uuid.uuid4()
    dlq_id = uuid.uuid4()
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                """
                INSERT INTO worker_registry (
                    id, workspace_id, name, supported_stages, status,
                    max_concurrency, current_load, health_score,
                    last_heartbeat_at, registered_at, instance_key, drain
                ) VALUES (
                    :id, :ws, 'mission-worker', ARRAY['scripting'],
                    'online'::worker_status, 2, 0, 99, :heartbeat, :registered,
                    :instance_key, false
                )
                """
            ),
            {
                "id": str(worker_id),
                "ws": workspace_id,
                "heartbeat": now,
                "registered": now,
                "instance_key": f"mission-{worker_id}",
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO worker_registry (
                    id, workspace_id, name, supported_stages, status,
                    max_concurrency, current_load, health_score,
                    last_heartbeat_at, registered_at, instance_key, drain
                ) VALUES (
                    :id, NULL, 'global-platform-worker', ARRAY['scripting'],
                    'online'::worker_status, 2, 0, 99, :heartbeat, :registered,
                    :instance_key, false
                )
                """
            ),
            {
                "id": str(global_worker_id),
                "heartbeat": now,
                "registered": now,
                "instance_key": f"global-{global_worker_id}",
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO stage_assignments (
                    id, workspace_id, pipeline_run_id, stage, attempt_number,
                    worker_id, status, idempotency_key, lease_expires_at,
                    dispatched_at, completed_at, priority, provider
                ) VALUES (
                    :id, :ws, :run, 'scripting'::content_stage, 2, :worker,
                    'completed'::stage_assignment_status, :idem,
                    :lease, :dispatched, :completed, 0, 'draft_desk'
                )
                """
            ),
            {
                "id": str(assignment_id),
                "ws": workspace_id,
                "run": run_id,
                "worker": str(worker_id),
                "idem": f"mission-{assignment_id}",
                "lease": now + timedelta(minutes=5),
                "dispatched": now - timedelta(minutes=3),
                "completed": now - timedelta(minutes=1),
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO pipeline_stage_runs (
                    id, workspace_id, pipeline_run_id, content_item_id, stage,
                    attempt_number, status, provider, cost_usd, started_at, completed_at
                ) VALUES (
                    :id, :ws, :run, :content, 'scripting'::content_stage,
                    1, 'succeeded'::stage_run_status, 'openai', 2.5000,
                    :started, :completed
                )
                """
            ),
            {
                "id": str(uuid.uuid4()),
                "ws": workspace_id,
                "run": run_id,
                "content": content_id,
                "started": now - timedelta(minutes=3),
                "completed": now - timedelta(minutes=1),
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO outbox_events (
                    event_id, workspace_id, event_type, event_version,
                    aggregate_type, aggregate_id, correlation_id, sequence,
                    payload, status, occurred_at, produced_by
                ) VALUES (
                    :id, :ws, 'stage.completed', 1, 'pipeline_run', :run,
                    :corr, 999, CAST(:payload AS jsonb), 'dispatched'::outbox_event_status,
                    :occurred, 'test'
                )
                """
            ),
            {
                "id": str(uuid.uuid4()),
                "ws": workspace_id,
                "run": run_id,
                "corr": str(uuid.uuid4()),
                "payload": '{"stage":"scripting"}',
                "occurred": now,
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO dead_letter_jobs (
                    id, workspace_id, related_table, related_id, job_type,
                    payload, failure_reason, attempt_count, first_failed_at,
                    last_failed_at, status
                ) VALUES (
                    :id, :ws, 'scripting', :run, 'scripting',
                    CAST('{}' AS jsonb), 'timeout', 3, :failed, :failed,
                    'pending'::dead_letter_status
                )
                """
            ),
            {
                "id": str(dlq_id),
                "ws": workspace_id,
                "run": run_id,
                "failed": now,
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO spend_logs (
                    id, workspace_id, provider, cost_usd, occurred_at
                ) VALUES (
                    :id, :ws, 'openai', 2.5000, :occurred
                )
                """
            ),
            {"id": str(uuid.uuid4()), "ws": workspace_id, "occurred": now},
        )
        await session.commit()

    activity = await client.get(f"/workspaces/{workspace_id}/operations/activity", headers=headers)
    assert activity.status_code == 200
    titles = {item["title"] for item in activity.json()["items"]}
    assert "Worker completed job" in titles
    assert "Customer signup" in titles

    health = await client.get(f"/workspaces/{workspace_id}/operations/health", headers=headers)
    assert health.status_code == 200
    keys = {item["key"] for item in health.json()["indicators"]}
    expected = {
        "api",
        "database",
        "workers",
        "queue",
        "github_actions",
        "webhooks",
        "scheduler",
    }
    assert expected <= keys
    assert all(item["status"] in {"green", "amber", "red"} for item in health.json()["indicators"])

    cost = await client.get(f"/workspaces/{workspace_id}/operations/cost-control", headers=headers)
    assert cost.status_code == 200
    cost_body = cost.json()
    assert Decimal(cost_body["daily_ai_spend_usd"]) >= Decimal("2.5")
    assert Decimal(cost_body["projected_month_end_usd"]) >= Decimal("2.5")
    assert len(cost_body["top_expensive_jobs"]) >= 1

    timeline = await client.get(
        f"/workspaces/{workspace_id}/operations/worker-timeline", headers=headers
    )
    assert timeline.status_code == 200
    worker = next(item for item in timeline.json()["workers"] if item["name"] == "mission-worker")
    assert worker["average_execution_seconds"] is not None
    assert len(worker["jobs"]) >= 1

    content = await client.get(
        f"/workspaces/{workspace_id}/operations/content-command", headers=headers
    )
    assert content.status_code == 200
    assert content.json()["waiting_for_approval"] >= 1

    insights = await client.get(f"/workspaces/{workspace_id}/operations/insights", headers=headers)
    assert insights.status_code == 200
    assert insights.json()["suggested_next_action"]
    assert insights.json()["highest_risk"]

    pause = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/pause-workers",
        headers=headers,
    )
    assert pause.status_code == 200
    assert pause.json()["affected"] == 1
    async with AsyncSessionLocal() as session:
        global_drain = await session.scalar(
            text("SELECT drain FROM worker_registry WHERE id = :id"),
            {"id": str(global_worker_id)},
        )
    assert global_drain is False

    resume = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/resume-workers",
        headers=headers,
    )
    assert resume.status_code == 200

    retry = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/retry-failed-jobs",
        headers=headers,
    )
    assert retry.status_code == 200
    assert retry.json()["affected"] >= 1

    clear = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/clear-dead-letter",
        headers=headers,
    )
    assert clear.status_code == 200

    sync = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/sync-github",
        headers=headers,
    )
    assert sync.status_code == 200
    assert sync.json()["action"] == "sync_github"

    emergency = await client.post(
        f"/workspaces/{workspace_id}/operations/actions/emergency-stop",
        headers=headers,
    )
    assert emergency.status_code == 200
    async with AsyncSessionLocal() as session:
        global_state = (
            await session.execute(
                text("SELECT status::text, drain FROM worker_registry WHERE id = :id"),
                {"id": str(global_worker_id)},
            )
        ).one()
    assert global_state == ("online", False)

    async with AsyncSessionLocal() as session:
        audit_rows = (
            await session.execute(
                text(
                    "SELECT payload->>'action', payload->>'actor_id' "
                    "FROM outbox_events "
                    "WHERE workspace_id = :ws "
                    "AND event_type = 'operations.action.executed'"
                ),
                {"ws": workspace_id},
            )
        ).all()
    assert {row[0] for row in audit_rows} >= {
        "pause_workers",
        "resume_workers",
        "retry_failed_jobs",
        "clear_dead_letter_queue",
        "emergency_stop",
    }
    assert {row[1] for row in audit_rows} == {user_id}


@pytest.mark.asyncio
async def test_mission_control_requires_admin(client, new_user):
    _owner_id, _token, owner_headers = new_user
    workspace = await client.post(
        "/workspaces", headers=owner_headers, json={"name": "Private Mission"}
    )
    workspace_id = workspace.json()["id"]
    outsider = await client.post(
        "/auth/signup",
        json={"email": f"{uuid.uuid4()}@example.com", "password": "securepass1-beta"},
    )
    outsider_headers = {"Authorization": f"Bearer {outsider.json()['access_token']}"}
    for endpoint in (
        "activity",
        "health",
        "automation",
        "cost-control",
        "worker-timeline",
        "content-command",
        "insights",
    ):
        response = await client.get(
            f"/workspaces/{workspace_id}/operations/{endpoint}",
            headers=outsider_headers,
        )
        assert response.status_code == 403


@pytest.mark.asyncio
async def test_mission_control_scheduler_health_is_not_green_when_lease_is_stale(client, new_user):
    _user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "Stale Automation"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                """
                UPDATE automation_leases
                SET owner_id = 'stale-owner',
                    owner_started_at = :started,
                    last_heartbeat_at = :heartbeat,
                    lease_expires_at = :expired,
                    last_ok_at = :ok_at,
                    last_error = NULL
                WHERE loop_name = 'scheduler'
                """
            ),
            {
                "started": now - timedelta(minutes=5),
                "heartbeat": now - timedelta(minutes=3),
                "expired": now - timedelta(seconds=1),
                "ok_at": now,
            },
        )
        await session.commit()

    response = await client.get(f"/workspaces/{workspace_id}/operations/health", headers=headers)
    assert response.status_code == 200
    scheduler = next(item for item in response.json()["indicators"] if item["key"] == "scheduler")
    assert scheduler["status"] == "red"
    assert "stale" in scheduler["detail"].lower()


@pytest.mark.asyncio
async def test_retry_failed_jobs_never_mutates_a_foreign_workspaces_pipeline_run(client, new_user):
    """Defense-in-depth regression. `retry_failed_jobs` has two loops: the
    DLQ-replay loop resolves a PipelineRun from a related id and explicitly
    re-checks `run.workspace_id == workspace_id` before touching it; the
    second loop (assignments that failed without ever reaching the DLQ)
    resolves a PipelineRun the same way via `assignment.pipeline_run_id`
    but was missing that identical guard. Nothing in the schema ties a
    StageAssignment's workspace_id to its pipeline_run's workspace_id — that
    invariant is only caller discipline elsewhere in the code — so if it
    were ever violated, workspace A's admin could flip workspace B's
    pipeline run to RUNNING and enqueue a JobSchedule against it. Simulate
    that desync directly (bypassing the callers that currently prevent it)
    and confirm the guard now holds.
    """
    _uid, _tok, headers = new_user
    ws_a = (await client.post("/workspaces", headers=headers, json={"name": "Retry A"})).json()[
        "id"
    ]
    ws_b = (await client.post("/workspaces", headers=headers, json={"name": "Retry B"})).json()[
        "id"
    ]

    content_b = await client.post(
        f"/workspaces/{ws_b}/content-jobs",
        headers=headers,
        json={"topic": "Foreign run", "script_body": "draft"},
    )
    assert content_b.status_code == 201, content_b.text
    run_b_id = content_b.json()["pipeline_run_id"]

    async with AsyncSessionLocal() as session:
        # Force workspace B's run to FAILED so the workspace guard is what
        # stops the mutation, not the "already succeeded/cancelled" skip.
        await session.execute(
            text("UPDATE pipeline_runs SET status = 'failed' WHERE id = :id"),
            {"id": run_b_id},
        )
        await session.execute(
            text(
                """
                INSERT INTO stage_assignments (
                    id, workspace_id, pipeline_run_id, stage, attempt_number, status
                ) VALUES (
                    :id, :ws_a, :run_b, 'scripting'::content_stage, 1,
                    'failed'::stage_assignment_status
                )
                """
            ),
            {"id": str(uuid.uuid4()), "ws_a": ws_a, "run_b": run_b_id},
        )
        await session.commit()

    retry = await client.post(
        f"/workspaces/{ws_a}/operations/actions/retry-failed-jobs",
        headers=headers,
    )
    assert retry.status_code == 200, retry.text

    async with AsyncSessionLocal() as session:
        run_b_status = await session.scalar(
            text("SELECT status FROM pipeline_runs WHERE id = :id"), {"id": run_b_id}
        )
        assert run_b_status == "failed", (
            "workspace A's retry action must never flip workspace B's pipeline run to running"
        )
        leaked_schedule = await session.scalar(
            text("SELECT count(*) FROM job_schedule WHERE ref_id = :id AND job_type = 'retry'"),
            {"id": run_b_id},
        )
        assert leaked_schedule == 0, (
            "workspace A's retry action must never enqueue work against workspace B's pipeline run"
        )


@pytest.mark.asyncio
async def test_emergency_stop_never_revokes_a_foreign_workspaces_credential(client, new_user):
    """Defense-in-depth regression. `emergency_stop` revokes every ACTIVE
    WorkerCredential for each worker it targets, filtered only by
    worker_id — not by workspace_id, even though WorkerCredential has its
    own workspace_id column. `worker_credentials` is FORCE RLS with no
    policies (fully service-role-only), so this query is the only line of
    defense; today it's safe only because every credential-creation path
    always sets `credential.workspace_id = registration.workspace_id`.
    Simulate that invariant being violated directly and confirm the
    revocation query no longer reaches a foreign-workspace credential.
    """
    _uid, _tok, headers = new_user
    ws_a = (await client.post("/workspaces", headers=headers, json={"name": "Stop A"})).json()["id"]
    ws_b = (await client.post("/workspaces", headers=headers, json={"name": "Stop B"})).json()["id"]

    worker_a = await client.post(
        f"/workspaces/{ws_a}/workers",
        headers=headers,
        json={"name": "stop-a-worker", "supported_stages": ["scripting"]},
    )
    assert worker_a.status_code == 201, worker_a.text
    worker_a_id = worker_a.json()["worker_id"]

    foreign_credential_id = uuid.uuid4()
    async with AsyncSessionLocal() as session:
        # A credential row for worker A's own worker_id, but stamped with
        # workspace B — the scenario that would arise if the "credential
        # always inherits its worker's workspace" invariant were ever
        # violated upstream.
        await session.execute(
            text(
                """
                INSERT INTO worker_credentials (id, worker_id, workspace_id, secret_hash, status)
                VALUES (:id, :worker, :ws_b, 'test-hash-not-a-real-secret', 'active')
                """
            ),
            {"id": str(foreign_credential_id), "worker": worker_a_id, "ws_b": ws_b},
        )
        await session.commit()

    stop = await client.post(
        f"/workspaces/{ws_a}/operations/actions/emergency-stop",
        headers=headers,
    )
    assert stop.status_code == 200, stop.text

    async with AsyncSessionLocal() as session:
        status = await session.scalar(
            text("SELECT status FROM worker_credentials WHERE id = :id"),
            {"id": str(foreign_credential_id)},
        )
        assert status == "active", (
            "workspace A's emergency-stop must never revoke a credential "
            "stamped with a different workspace_id"
        )

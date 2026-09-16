"""Operations Dashboard V2: Founder Control Center real-data projections."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from sqlalchemy import text

from app.core.config import get_settings
from app.db.session import AsyncSessionLocal
from app.models.leads import Lead


@pytest.mark.asyncio
async def test_founder_control_center_modules(client, new_user, monkeypatch):
    user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "Founder Control"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]

    draft = await client.post(
        f"/workspaces/{workspace_id}/content-jobs",
        headers=headers,
        json={"topic": "Founder signal", "script_body": "Real draft"},
    )
    assert draft.status_code == 201, draft.text
    run_id = draft.json()["pipeline_run_id"]
    worker_id = uuid.uuid4()
    assignment_id = uuid.uuid4()
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                """
                INSERT INTO worker_registry (
                    id, workspace_id, name, supported_stages, status,
                    max_concurrency, current_load, health_score,
                    last_heartbeat_at, registered_at, instance_key, drain,
                    capabilities
                ) VALUES (
                    :id, :ws, 'founder-worker', ARRAY['scripting'],
                    'busy'::worker_status, 2, 1, 98, :heartbeat, :registered,
                    :instance_key, false,
                    CAST(:capabilities AS jsonb)
                )
                """
            ),
            {
                "id": str(worker_id),
                "ws": workspace_id,
                "heartbeat": now,
                "registered": now,
                "instance_key": f"test-{worker_id}",
                "capabilities": '{"cpu_percent": 42, "memory_percent": 61}',
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
                "idem": f"ops-v2-{assignment_id}",
                "lease": now + timedelta(minutes=5),
                "dispatched": now - timedelta(minutes=2),
                "completed": now,
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO spend_logs (
                    id, workspace_id, provider, cost_usd, occurred_at
                ) VALUES (
                    :id, :ws, 'openai', 1.2500, :occurred
                )
                """
            ),
            {
                "id": str(uuid.uuid4()),
                "ws": workspace_id,
                "occurred": now,
            },
        )
        await session.execute(
            text(
                """
                INSERT INTO workspace_billing (
                    workspace_id, plan, status, stripe_customer_id
                ) VALUES (
                    :ws, 'pro', 'trialing', :customer
                )
                ON CONFLICT (workspace_id) DO UPDATE
                SET plan = EXCLUDED.plan,
                    status = EXCLUDED.status,
                    stripe_customer_id = EXCLUDED.stripe_customer_id
                """
            ),
            {"ws": workspace_id, "customer": f"cus_{uuid.uuid4().hex[:10]}"},
        )
        await session.execute(
            text(
                """
                INSERT INTO billing_webhook_events (
                    id, stripe_event_id, event_type, workspace_id, processed_at, payload
                ) VALUES (
                    :id, :event_id, 'invoice.paid', :ws, :processed,
                    CAST(:payload AS jsonb)
                )
                """
            ),
            {
                "id": str(uuid.uuid4()),
                "event_id": f"evt_{uuid.uuid4().hex}",
                "ws": workspace_id,
                "processed": now,
                "payload": '{"data":{"object":{"amount_paid":4999}}}',
            },
        )
        await session.commit()

    lead = await client.post(
        f"/workspaces/{workspace_id}/operations/leads",
        headers=headers,
        json={
            "name": "Ada Founder",
            "company": "Lumora Labs",
            "email": "ada@example.com",
            "source": "inbound",
            "status": "new",
            "notes": "Asked about beta",
            "follow_up_date": "2026-08-10",
        },
    )
    assert lead.status_code == 201, lead.text
    lead_id = lead.json()["id"]

    leads = await client.get(
        f"/workspaces/{workspace_id}/operations/leads",
        headers=headers,
        params={"search": "Ada", "status": "new", "source": "inbound"},
    )
    assert leads.status_code == 200
    assert leads.json()["total"] == 1
    assert leads.json()["leads"][0]["email"] == "ada@example.com"

    patched = await client.patch(
        f"/workspaces/{workspace_id}/operations/leads/{lead_id}",
        headers=headers,
        json={"status": "contacted"},
    )
    assert patched.status_code == 200
    assert patched.json()["status"] == "contacted"

    workers = await client.get(f"/workspaces/{workspace_id}/operations/workers", headers=headers)
    assert workers.status_code == 200
    worker = next(item for item in workers.json()["workers"] if item["name"] == "founder-worker")
    assert worker["jobs_completed_today"] >= 1
    assert worker["cpu_percent"] == 42.0
    assert worker["memory_percent"] == 61.0
    assert worker["current_task"] is None or isinstance(worker["current_task"], str)

    spend = await client.get(f"/workspaces/{workspace_id}/operations/spend", headers=headers)
    assert spend.status_code == 200
    spend_body = spend.json()
    assert Decimal(spend_body["today_usd"]) >= Decimal("1.25")
    assert any(row["provider"] == "openai" for row in spend_body["by_provider"])
    assert spend_body["budget_remaining_daily_usd"] is not None

    customers = await client.get(
        f"/workspaces/{workspace_id}/operations/customers", headers=headers
    )
    assert customers.status_code == 200
    customers_body = customers.json()
    assert customers_body["trial_users"] >= 1
    assert Decimal(customers_body["revenue_mtd_usd"]) == Decimal("49.99")
    assert customers_body["customers"][0]["subscription_status"] == "trialing"

    pipelines = await client.get(
        f"/workspaces/{workspace_id}/operations/pipelines", headers=headers
    )
    assert pipelines.status_code == 200
    pipeline_body = pipelines.json()
    assert "jobs_completed" in pipeline_body
    assert "human_reviews_waiting" in pipeline_body
    assert "publishing_queue" in pipeline_body

    monkeypatch.delenv("GITHUB_TOKEN", raising=False)
    monkeypatch.delenv("GITHUB_API_TOKEN", raising=False)
    monkeypatch.delenv("GITHUB_REPOSITORY", raising=False)
    get_settings.cache_clear()
    github = await client.get(f"/workspaces/{workspace_id}/operations/github", headers=headers)
    assert github.status_code == 200
    assert github.json()["available"] is False
    assert github.json()["latest_commits"] == []

    notifications = await client.get(
        f"/workspaces/{workspace_id}/operations/notifications", headers=headers
    )
    assert notifications.status_code == 200
    keys = {item["key"] for item in notifications.json()["notifications"]}
    assert "new_lead" in keys
    assert "customer_signup" in keys
    assert "review_required" in keys


@pytest.mark.asyncio
async def test_founder_endpoints_require_admin(client, new_user):
    _owner_id, _token, owner_headers = new_user
    workspace = await client.post(
        "/workspaces", headers=owner_headers, json={"name": "Private Founder"}
    )
    workspace_id = workspace.json()["id"]
    outsider = await client.post(
        "/auth/signup",
        json={"email": f"{uuid.uuid4()}@example.com", "password": "securepass1-beta"},
    )
    outsider_headers = {"Authorization": f"Bearer {outsider.json()['access_token']}"}
    for endpoint in (
        "leads",
        "customers",
        "spend",
        "github",
        "notifications",
    ):
        response = await client.get(
            f"/workspaces/{workspace_id}/operations/{endpoint}",
            headers=outsider_headers,
        )
        assert response.status_code == 403


@pytest.mark.asyncio
async def test_leads_list_is_paginated_and_reports_full_total(client, new_user):
    _owner_id, _token, headers = new_user
    workspace = await client.post(
        "/workspaces", headers=headers, json={"name": "Founder Leads Paging"}
    )
    assert workspace.status_code == 201
    workspace_id = uuid.UUID(workspace.json()["id"])
    now = datetime.now(UTC)

    async with AsyncSessionLocal() as session:
        for i in range(260):
            session.add(
                Lead(
                    workspace_id=workspace_id,
                    name=f"Lead {i}",
                    email=f"lead-{i}@example.com",
                    source="inbound",
                    status="new",
                    created_at=now + timedelta(seconds=i),
                    updated_at=now + timedelta(seconds=i),
                )
            )
        await session.commit()

    first_page = await client.get(
        f"/workspaces/{workspace_id}/operations/leads",
        headers=headers,
    )
    assert first_page.status_code == 200, first_page.text
    first_body = first_page.json()
    assert first_body["total"] == 260
    assert len(first_body["leads"]) == 200
    first_indices = [int(row["name"].split(" ")[1]) for row in first_body["leads"]]
    assert first_indices == list(range(259, 59, -1))

    second_page = await client.get(
        f"/workspaces/{workspace_id}/operations/leads",
        headers=headers,
        params={"limit": 50, "offset": 200},
    )
    assert second_page.status_code == 200, second_page.text
    second_body = second_page.json()
    assert second_body["total"] == 260
    assert len(second_body["leads"]) == 50
    second_indices = [int(row["name"].split(" ")[1]) for row in second_body["leads"]]
    assert second_indices == list(range(59, 9, -1))

    first_ids = {row["id"] for row in first_body["leads"]}
    second_ids = {row["id"] for row in second_body["leads"]}
    assert first_ids.isdisjoint(second_ids)

"""Regression tests for PR #34 High findings H-1…H-4 (+ M-1/M-2/M-3)."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from pydantic import ValidationError
from sqlalchemy import select, text

from app.core.config import Settings
from app.db.session import AsyncSessionLocal
from app.models.billing import WorkspaceBilling
from app.models.content import ContentItem
from app.models.enums import (
    ContentStage,
    ContentStatus,
    JobScheduleStatus,
    JobType,
    PipelineRunStatus,
    ReservationStatus,
)
from app.models.operations import DeadLetterJob
from app.models.pipeline import PipelineRun
from app.models.scheduling import JobSchedule
from app.models.spend import SpendLog, SpendReservation
from app.models.workspace import Workspace
from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole
from app.orchestration import controller, dispatcher, scheduler
from app.services import billing as billing_service
from app.services.spend import ensure_default_spend_cap
from tests.conftest import nontest_jwt_secret


def _base_settings_kwargs(**overrides) -> dict:
    kwargs = {
        "database_url": "postgresql://postgres:postgres@127.0.0.1:5432/content_orchestrator_test",
        # Deliberately NOT the migration-default `app_runtime` password —
        # see test_c2_* below for that specific, isolated check. Using the
        # default here would make every production-environment test in
        # this module also trip the app_runtime-password guard.
        "app_database_url": (
            "postgresql://app_runtime:rotated-test-password@127.0.0.1:5432/content_orchestrator_test"
        ),
        "supabase_jwt_secret": nontest_jwt_secret(),
        "supabase_jwt_issuer": "https://test-project.supabase.co/auth/v1",
        "environment": "development",
        "auth_mode": "supabase",
    }
    kwargs.update(overrides)
    return kwargs


def test_h1_auth_mode_defaults_to_supabase():
    settings = Settings(**_base_settings_kwargs())
    assert settings.auth_mode == "supabase"


def test_h1_production_rejects_local_auth_without_override():
    with pytest.raises(ValidationError, match="AUTH_MODE=local is forbidden"):
        Settings(
            **_base_settings_kwargs(
                environment="production",
                auth_mode="local",
                allow_local_auth_in_production=False,
            )
        )


def test_h1_production_allows_local_with_explicit_override():
    settings = Settings(
        **_base_settings_kwargs(
            environment="production",
            auth_mode="local",
            allow_local_auth_in_production=True,
            supabase_jwt_issuer=None,
        )
    )
    assert settings.auth_mode == "local"


def test_c2_production_rejects_default_app_runtime_password():
    """Regression (2026-09-07 audit finding): migration 0001 creates the
    app_runtime Postgres role with the literal default password
    'app_runtime' when it doesn't already exist. Starting the API in
    production against a database where that default was never rotated
    must fail closed, mirroring the AUTH_MODE=local guard above.
    """
    with pytest.raises(ValidationError, match="app_runtime"):
        Settings(
            **_base_settings_kwargs(
                environment="production",
                app_database_url=(
                    "postgresql://app_runtime:app_runtime@127.0.0.1:5432/content_orchestrator_test"
                ),
            )
        )


def test_c2_production_allows_rotated_app_runtime_password():
    settings = Settings(**_base_settings_kwargs(environment="production"))
    assert settings.environment == "production"


def test_c2_default_password_is_fine_outside_production():
    settings = Settings(
        **_base_settings_kwargs(
            environment="development",
            app_database_url=(
                "postgresql://app_runtime:app_runtime@127.0.0.1:5432/content_orchestrator_test"
            ),
        )
    )
    assert settings.environment == "development"


async def _seed_workspace_item(session):
    user_id = uuid.uuid4()
    await session.execute(
        text("INSERT INTO auth.users (id, email) VALUES (:id, :email)"),
        {"id": str(user_id), "email": f"{user_id}@example.com"},
    )
    await session.execute(
        text("INSERT INTO profiles (id, email) VALUES (:id, :email) ON CONFLICT (id) DO NOTHING"),
        {"id": str(user_id), "email": f"{user_id}@example.com"},
    )
    ws = Workspace(id=uuid.uuid4(), name=f"pr34-{user_id}", created_by=user_id)
    session.add(ws)
    await session.flush()
    session.add(WorkspaceMembership(workspace_id=ws.id, user_id=user_id, role=WorkspaceRole.ADMIN))
    await ensure_default_spend_cap(session, workspace_id=ws.id, actor_id=user_id)
    item = ContentItem(
        id=uuid.uuid4(),
        workspace_id=ws.id,
        topic="pr34 highs",
        current_stage=ContentStage.SCRIPTING,
        status=ContentStatus.ACTIVE,
        created_by=user_id,
        updated_by=user_id,
    )
    session.add(item)
    await session.flush()
    return ws, user_id, item


@pytest.mark.asyncio
async def test_h3_commit_spend_clamps_actual_to_reserved():
    async with AsyncSessionLocal() as session:
        ws, _user_id, item = await _seed_workspace_item(session)
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            content_item_id=item.id,
            status=PipelineRunStatus.RUNNING,
            correlation_id=uuid.uuid4(),
        )
        session.add(run)
        await session.flush()
        reservation = await controller.reserve_spend(
            session,
            run=run,
            stage="scripting",
            provider="openai",
            estimated_cost_usd=Decimal("0.50"),
        )
        assert reservation is not None
        await controller.commit_spend(
            session,
            run=run,
            reservation=reservation,
            actual_cost_usd=Decimal("999.99"),
        )
        await session.commit()
        await session.refresh(reservation)
        assert reservation.status == ReservationStatus.COMMITTED
        log = (
            await session.execute(select(SpendLog).where(SpendLog.workspace_id == ws.id))
        ).scalar_one()
        assert Decimal(str(log.cost_usd)) == Decimal("0.50")


@pytest.mark.asyncio
async def test_h4_reserve_retry_releases_prior_open_reservation():
    async with AsyncSessionLocal() as session:
        ws, _user_id, item = await _seed_workspace_item(session)
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            content_item_id=item.id,
            status=PipelineRunStatus.RUNNING,
            correlation_id=uuid.uuid4(),
        )
        session.add(run)
        await session.flush()
        first = await controller.reserve_spend(
            session,
            run=run,
            stage="scripting",
            provider="openai",
            estimated_cost_usd=Decimal("0.10"),
        )
        assert first is not None
        second = await controller.reserve_spend(
            session,
            run=run,
            stage="scripting",
            provider="openai",
            estimated_cost_usd=Decimal("0.10"),
        )
        assert second is not None
        assert second.id != first.id
        await session.commit()
        await session.refresh(first)
        await session.refresh(second)
        assert first.status == ReservationStatus.RELEASED
        assert second.status == ReservationStatus.RESERVED
        open_rows = (
            (
                await session.execute(
                    select(SpendReservation).where(
                        SpendReservation.pipeline_run_id == run.id,
                        SpendReservation.stage == "scripting",
                        SpendReservation.status == ReservationStatus.RESERVED,
                    )
                )
            )
            .scalars()
            .all()
        )
        assert len(open_rows) == 1
        assert open_rows[0].id == second.id


@pytest.mark.asyncio
async def test_h2_checkout_does_not_entitle_without_subscription():
    workspace_id = uuid.uuid4()
    async with AsyncSessionLocal() as session:
        user_id = uuid.uuid4()
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :email)"),
            {"id": str(user_id), "email": f"{user_id}@ex.com"},
        )
        await session.execute(
            text(
                "INSERT INTO profiles (id, email) VALUES (:id, :email) ON CONFLICT (id) DO NOTHING"
            ),
            {"id": str(user_id), "email": f"{user_id}@ex.com"},
        )
        await session.execute(
            text("INSERT INTO workspaces (id, name, created_by) VALUES (:id, :name, :by)"),
            {"id": str(workspace_id), "name": "H2 WS", "by": str(user_id)},
        )
        await session.commit()

    event = {
        "id": f"evt_h2_{uuid.uuid4().hex}",
        "type": "checkout.session.completed",
        "data": {
            "object": {
                "customer": f"cus_h2_{uuid.uuid4().hex[:8]}",
                "subscription": f"sub_h2_{uuid.uuid4().hex[:8]}",
                "payment_status": "unpaid",
                "metadata": {"workspace_id": str(workspace_id)},
            }
        },
    }
    async with AsyncSessionLocal() as session:
        result = await billing_service.process_stripe_event(session, event=event)
        await session.commit()
    assert result["status"] == "processed"
    async with AsyncSessionLocal() as session:
        row = await session.get(WorkspaceBilling, workspace_id)
        assert row is not None
        assert row.plan == "none"
        assert row.status == "inactive"
        assert not billing_service.is_entitled(row, billing_enabled=True)


@pytest.mark.asyncio
async def test_m1_spend_hold_parks_job_without_dlq_or_attempt_burn():
    async with AsyncSessionLocal() as session:
        ws, _user_id, item = await _seed_workspace_item(session)
        await session.execute(
            text(
                "UPDATE spend_caps SET daily_cap_usd = 1000, monthly_cap_usd = 1 "
                "WHERE workspace_id = :ws"
            ),
            {"ws": str(ws.id)},
        )
        session.add(
            SpendLog(
                id=uuid.uuid4(),
                workspace_id=ws.id,
                provider="draft_desk",
                stage=ContentStage.SCRIPTING,
                cost_usd=Decimal("1.00"),
                occurred_at=datetime.now(UTC),
            )
        )
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            content_item_id=item.id,
            status=PipelineRunStatus.RUNNING,
            correlation_id=uuid.uuid4(),
        )
        session.add(run)
        await session.flush()
        job = JobSchedule(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            job_type=JobType.STAGE,
            ref_table="scripting",
            ref_id=run.id,
            run_after=datetime.now(UTC),
            status=JobScheduleStatus.LEASED,
            attempt=0,
            lease_owner="test",
            lease_expires_at=datetime.now(UTC) + timedelta(seconds=30),
            correlation_id=run.correlation_id,
        )
        session.add(job)
        await session.flush()

        dispatch = await dispatcher.dispatch_stage(
            session,
            workspace_id=ws.id,
            pipeline_run_id=run.id,
            stage="scripting",
            attempt_number=1,
            correlation_id=run.correlation_id,
            trace_id=None,
        )
        assert dispatch.outcome == dispatcher.DispatchOutcome.SPEND_HOLD

        await scheduler.process_leased_job(session, job)
        await session.commit()
        await session.refresh(job)
        await session.refresh(run)
        assert job.status == JobScheduleStatus.PENDING
        assert job.attempt == 0
        assert run.pause_reason == "spend_hold"
        dlq = (
            await session.execute(select(DeadLetterJob).where(DeadLetterJob.related_id == job.id))
        ).scalar_one_or_none()
        assert dlq is None


@pytest.mark.asyncio
async def test_c1_content_desk_cancels_orphan_stage_job_and_blocks_resurrection():
    """Orphan job_schedule must not resurrect a published run into a new gate."""
    from app.models.enums import JobScheduleStatus, ReviewGateStatus
    from app.models.review_gate import ReviewGate
    from app.models.scheduling import JobSchedule
    from app.orchestration import relay
    from app.services import content_desk

    async with AsyncSessionLocal() as session:
        ws, user_id, _item = await _seed_workspace_item(session)
        result = await content_desk.create_content_job(
            session,
            workspace_id=ws.id,
            actor_id=user_id,
            topic="c1-guard",
            script_body="script body",
        )
        await session.commit()
        run_id = result.pipeline_run_id
        gate_id = result.review_gate_id

        orphans = (
            (
                await session.execute(
                    select(JobSchedule).where(
                        JobSchedule.ref_id == run_id,
                        JobSchedule.status.in_(
                            [JobScheduleStatus.PENDING, JobScheduleStatus.LEASED]
                        ),
                    )
                )
            )
            .scalars()
            .all()
        )
        assert orphans == [], "Content Desk must cancel the start_run scripting job"

    async with AsyncSessionLocal() as session:
        gate_detail = await content_desk.get_review_gate(
            session, workspace_id=ws.id, gate_id=gate_id
        )
        assert gate_detail is not None
        await content_desk.decide_review_gate(
            session,
            workspace_id=ws.id,
            gate_id=gate_id,
            reviewer_id=user_id,
            approved=True,
            expected_content_version_id=gate_detail["content_version_id"],
            notes="ship it",
        )
        await session.commit()

    async with AsyncSessionLocal() as session:
        for _ in range(10):
            n = await relay.poll_and_dispatch(session)
            if not n:
                break
        await session.commit()
        run = await session.get(PipelineRun, run_id)
        assert run is not None
        assert run.status == PipelineRunStatus.SUCCEEDED

        # Stale stage-success must be ignored on terminal runs.
        await controller.handle_stage_success(
            session, run=run, stage="scripting", result_context={}
        )
        await session.commit()
        await session.refresh(run)
        assert run.status == PipelineRunStatus.SUCCEEDED
        awaiting = (
            (
                await session.execute(
                    select(ReviewGate).where(
                        ReviewGate.pipeline_run_id == run_id,
                        ReviewGate.status == ReviewGateStatus.AWAITING,
                    )
                )
            )
            .scalars()
            .all()
        )
        assert awaiting == []


@pytest.mark.asyncio
async def test_h4_commit_spend_is_idempotent():
    async with AsyncSessionLocal() as session:
        ws, _user_id, item = await _seed_workspace_item(session)
        run = PipelineRun(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            content_item_id=item.id,
            status=PipelineRunStatus.RUNNING,
            correlation_id=uuid.uuid4(),
        )
        session.add(run)
        await session.flush()
        reservation = await controller.reserve_spend(
            session,
            run=run,
            stage="scripting",
            provider="openai",
            estimated_cost_usd=Decimal("0.25"),
        )
        assert reservation is not None
        await controller.commit_spend(
            session, run=run, reservation=reservation, actual_cost_usd=Decimal("0.25")
        )
        await controller.commit_spend(
            session, run=run, reservation=reservation, actual_cost_usd=Decimal("0.25")
        )
        await session.commit()
        logs = (
            (await session.execute(select(SpendLog).where(SpendLog.workspace_id == ws.id)))
            .scalars()
            .all()
        )
        assert len(logs) == 1

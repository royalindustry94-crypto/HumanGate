import uuid
from decimal import Decimal

import pytest
from pydantic import ValidationError
from sqlalchemy import select, text

from app.core.security import rls_scoped_session
from app.db.session import AsyncSessionLocal
from app.models.production import ProductionJob, ProductionReadiness
from app.schemas.production import ProductionRunCreate
from app.services import content_department, production
from tests.test_content_department_v1 import _fixture_package, _tenant


async def _audited_package(user_id: uuid.UUID, workspace_id: uuid.UUID):
    _brief_id, package = await _fixture_package(user_id, workspace_id)
    async with rls_scoped_session(str(user_id)) as session:
        for auditor_type in ("language", "fact", "brand", "originality"):
            await content_department.record_fixture_audit(
                session,
                workspace_id=workspace_id,
                actor_id=user_id,
                package_id=package.id,
                auditor_type=auditor_type,
                state="pass",
                evidence=[{"source": "independent fixture", "status": "pass"}],
            )
        refreshed = await content_department.get_package(
            session, workspace_id=workspace_id, package_id=package.id
        )
        assert refreshed is not None
        assert refreshed.audit_gate_status == "pass"
        return refreshed


@pytest.mark.asyncio
async def test_audited_package_creates_truthful_provider_not_configured_run(client, new_user):
    user_id, headers, workspace_id = await _tenant(client, new_user, "Producer no provider")
    package = await _audited_package(user_id, workspace_id)
    response = await client.post(
        f"/workspaces/{workspace_id}/production/runs",
        headers=headers,
        json={"content_package_id": str(package.id)},
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["status"] == "blocked_provider_not_configured"
    assert body["provider_state"] == "not_configured"
    assert body["actual_cost_usd"] in (0, 0.0, "0", "0.0000")
    assert body["provider_calls_used"] == 0
    assert body["render_calls_used"] == 0
    summary = await client.get(f"/workspaces/{workspace_id}/production/summary", headers=headers)
    assert summary.status_code == 200
    assert summary.json()["final_artifacts"] == 0
    assert summary.json()["provider_cost_usd"] in (0, 0.0, "0", "0.0000")


@pytest.mark.asyncio
async def test_unaudited_package_is_denied_from_production(client, new_user):
    user_id, headers, workspace_id = await _tenant(client, new_user, "Producer blocked upstream")
    _brief_id, package = await _fixture_package(user_id, workspace_id)
    response = await client.post(
        f"/workspaces/{workspace_id}/production/runs",
        headers=headers,
        json={"content_package_id": str(package.id)},
    )
    assert response.status_code == 409
    assert "independent content audits" in response.json()["detail"]


@pytest.mark.asyncio
async def test_media_qa_is_independent_hash_bound_and_readiness_is_fail_closed(client, new_user):
    user_id, _headers, workspace_id = await _tenant(client, new_user, "Producer media QA")
    package = await _audited_package(user_id, workspace_id)
    async with rls_scoped_session(str(user_id)) as session:
        job = await production.create_production_run(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            content_package_id=package.id,
            target_platform="short_video",
            target_format="mp4",
            target_duration_seconds=30,
            max_provider_calls=1,
            max_render_calls=1,
            max_cost_usd=Decimal("0"),
            max_attempts=1,
            max_repair_cycles=1,
            timeout_seconds=300,
        )
        job.test_data = True
        artifact = await production.create_test_fixture_artifact(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            production_job_id=job.id,
            artifact_hash=production.fixture_hash("producer-media-qa"),
        )
        with pytest.raises(production.ProducerGateError, match="cannot create or approve"):
            await production.create_test_media_qa(
                session,
                workspace_id=workspace_id,
                actor_id=user_id,
                final_artifact_id=artifact.id,
                auditor_worker_id="producer-fixture",
                producer_worker_id="producer-fixture",
                status="pass",
            )
        qa = await production.create_test_media_qa(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            final_artifact_id=artifact.id,
            auditor_worker_id="media-qa-fixture",
            producer_worker_id="producer-fixture",
            status="pass",
        )
        assert qa.artifact_hash == artifact.artifact_hash
        readiness = await session.scalar(
            select(ProductionReadiness).where(ProductionReadiness.final_artifact_id == artifact.id)
        )
        assert readiness is not None
        assert readiness.status == "blocked"
        assert readiness.human_review_state == "blocked"


@pytest.mark.asyncio
async def test_artifact_invalidation_blocks_prior_readiness(client, new_user):
    user_id, _headers, workspace_id = await _tenant(client, new_user, "Producer invalidation")
    package = await _audited_package(user_id, workspace_id)
    async with rls_scoped_session(str(user_id)) as session:
        job = await production.create_production_run(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            content_package_id=package.id,
            target_platform=None,
            target_format=None,
            target_duration_seconds=None,
            max_provider_calls=1,
            max_render_calls=1,
            max_cost_usd=Decimal("0"),
            max_attempts=1,
            max_repair_cycles=1,
            timeout_seconds=300,
        )
        job.test_data = True
        artifact = await production.create_test_fixture_artifact(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            production_job_id=job.id,
            artifact_hash=production.fixture_hash("invalidation"),
        )
        await production.create_test_media_qa(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            final_artifact_id=artifact.id,
            auditor_worker_id="media-qa-fixture",
            producer_worker_id="producer-fixture",
            status="pass",
        )
        invalidation = await production.invalidate_artifact(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            final_artifact_id=artifact.id,
            reason="test revision requires re-audit",
        )
        assert invalidation.final_artifact_id == artifact.id
        readiness = await session.scalar(
            select(ProductionReadiness).where(ProductionReadiness.final_artifact_id == artifact.id)
        )
        assert readiness is not None
        assert readiness.media_qa_state == "invalidated"
        assert readiness.status == "blocked"


@pytest.mark.asyncio
async def test_production_records_are_hidden_by_api_and_direct_rls(client, new_user):
    user_id, headers, workspace_id = await _tenant(client, new_user, "Producer isolation")
    package = await _audited_package(user_id, workspace_id)
    response = await client.post(
        f"/workspaces/{workspace_id}/production/runs",
        headers=headers,
        json={"content_package_id": str(package.id)},
    )
    assert response.status_code == 201
    job_id = uuid.UUID(response.json()["id"])
    outsider_id = uuid.uuid4()
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :email)"),
            {"id": str(outsider_id), "email": f"{outsider_id}@example.com"},
        )
        await session.commit()
    async with rls_scoped_session(str(outsider_id)) as session:
        hidden = await session.scalar(
            select(ProductionJob.id).where(
                ProductionJob.workspace_id == workspace_id,
                ProductionJob.id == job_id,
            )
        )
        assert hidden is None
    denied = await client.get(
        f"/workspaces/{workspace_id}/production/runs/{job_id}",
        headers={"Authorization": "Bearer invalid"},
    )
    assert denied.status_code in {401, 403}


@pytest.mark.asyncio
async def test_duplicate_artifact_hash_is_conservative(client, new_user):
    user_id, _headers, workspace_id = await _tenant(client, new_user, "Producer duplicate hash")
    package = await _audited_package(user_id, workspace_id)
    async with rls_scoped_session(str(user_id)) as session:
        job = await production.create_production_run(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            content_package_id=package.id,
            target_platform=None,
            target_format=None,
            target_duration_seconds=None,
            max_provider_calls=1,
            max_render_calls=1,
            max_cost_usd=Decimal("0"),
            max_attempts=1,
            max_repair_cycles=1,
            timeout_seconds=300,
        )
        job.test_data = True
        artifact_hash = production.fixture_hash("duplicate")
        await production.create_test_fixture_artifact(
            session,
            workspace_id=workspace_id,
            actor_id=user_id,
            production_job_id=job.id,
            artifact_hash=artifact_hash,
        )
        with pytest.raises(production.ProducerGateError, match="identical final artifact hash"):
            await production.create_test_fixture_artifact(
                session,
                workspace_id=workspace_id,
                actor_id=user_id,
                production_job_id=job.id,
                artifact_hash=artifact_hash,
            )


def test_production_limits_are_strict():
    with pytest.raises(ValidationError):
        ProductionRunCreate(content_package_id=uuid.uuid4(), max_provider_calls=21)
    with pytest.raises(ValidationError):
        ProductionRunCreate(content_package_id=uuid.uuid4(), max_render_calls=9)
    with pytest.raises(ValidationError):
        ProductionRunCreate(content_package_id=uuid.uuid4(), max_repair_cycles=6)
    with pytest.raises(ValidationError):
        ProductionRunCreate(content_package_id=uuid.uuid4(), max_cost_usd=Decimal("500.01"))

"""Bounded Compliance and Chief Auditor V1 service.

Founder Preview has no policy, rights, or compliance provider configuration. Browser
requests therefore persist an accountable blocked/no-provider state; test helpers are
service-only and cannot be reached through the API.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.compliance import (
    ArtifactPublicationEligibility,
    ArtifactRightsEvidence,
    AuditGateManifest,
    ChiefAudit,
    ComplianceAudit,
    ComplianceInvalidation,
    HumanReviewPackage,
)
from app.models.production import FinalArtifact, MediaQaResult, ProductionReadiness
from app.orchestration import outbox


class ComplianceNotFoundError(Exception):
    pass


class ComplianceGateError(Exception):
    pass


DEFAULT_GATES = [
    "research_audit",
    "strategy_audit",
    "language_audit",
    "fact_audit",
    "brand_audit",
    "originality_audit",
    "media_qa",
    "compliance",
    "chief_audit",
    "human_review",
]


async def _artifact(
    session: AsyncSession, workspace_id: uuid.UUID, artifact_id: uuid.UUID
) -> FinalArtifact:
    artifact = (
        await session.execute(
            select(FinalArtifact).where(
                FinalArtifact.workspace_id == workspace_id,
                FinalArtifact.id == artifact_id,
            )
        )
    ).scalar_one_or_none()
    if artifact is None:
        raise ComplianceNotFoundError("final artifact not found")
    return artifact


async def create_compliance_run(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    final_artifact_id: uuid.UUID,
    target_platform: str,
    max_provider_calls: int,
    max_verification_calls: int,
    max_tokens: int,
    max_cost_usd: Decimal,
    max_attempts: int,
) -> ComplianceAudit:
    artifact = await _artifact(session, workspace_id, final_artifact_id)
    media_qa = (
        (
            await session.execute(
                select(MediaQaResult).where(
                    MediaQaResult.workspace_id == workspace_id,
                    MediaQaResult.final_artifact_id == artifact.id,
                    MediaQaResult.artifact_hash == artifact.artifact_hash,
                )
            )
        )
        .scalars()
        .first()
    )
    rights = (
        (
            await session.execute(
                select(ArtifactRightsEvidence).where(
                    ArtifactRightsEvidence.workspace_id == workspace_id,
                    ArtifactRightsEvidence.final_artifact_id == artifact.id,
                )
            )
        )
        .scalars()
        .first()
    )
    audit = ComplianceAudit(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        final_artifact_id=artifact.id,
        artifact_hash=artifact.artifact_hash,
        content_version_id=artifact.content_version_id,
        target_platform=target_platform,
        compliance_worker_id="compliance",
        input_snapshot={
            "artifact_hash": artifact.artifact_hash,
            "media_qa": media_qa.status if media_qa else "missing",
            "rights": rights.rights_status if rights else "missing",
            "max_provider_calls": max_provider_calls,
            "max_verification_calls": max_verification_calls,
            "max_tokens": max_tokens,
            "max_cost_usd": str(max_cost_usd),
            "max_attempts": max_attempts,
        },
        status="blocked_provider_not_configured",
        risk_level="unknown",
        rights_status=rights.rights_status if rights else "unverified",
        findings=[{"code": "compliance_provider_not_configured", "severity": "blocker"}],
        provider_state="not_configured",
        started_at=datetime.now(UTC),
        completed_at=datetime.now(UTC),
    )
    session.add(audit)
    await session.flush()
    await outbox.emit(
        session,
        event_type="compliance.requested.not_configured",
        workspace_id=workspace_id,
        aggregate_type="compliance_audit",
        aggregate_id=audit.id,
        correlation_id=uuid.uuid4(),
        payload={
            "final_artifact_id": str(artifact.id),
            "artifact_hash": artifact.artifact_hash,
        },
        produced_by="compliance_v1",
    )
    return audit


async def summary(session: AsyncSession, *, workspace_id: uuid.UUID) -> dict:
    """Workspace compliance rollup.

    Aggregated in SQL. This previously materialised three whole tables into
    Python purely to count and sum them, which is unbounded work on a summary
    endpoint (same class as audit H-3).
    """
    audits = (
        await session.execute(
            select(
                func.count(ComplianceAudit.id),
                func.count(ComplianceAudit.id).filter(ComplianceAudit.status == "pass"),
                func.coalesce(func.sum(ComplianceAudit.cost_usd), 0),
            ).where(ComplianceAudit.workspace_id == workspace_id)
        )
    ).one()
    total_audits = int(audits[0] or 0)
    passed = int(audits[1] or 0)
    package_count = (
        await session.execute(
            select(func.count(HumanReviewPackage.id)).where(
                HumanReviewPackage.workspace_id == workspace_id
            )
        )
    ).scalar_one()
    publication_eligible = (
        await session.execute(
            select(func.count(ArtifactPublicationEligibility.id))
            .where(ArtifactPublicationEligibility.workspace_id == workspace_id)
            .where(ArtifactPublicationEligibility.publication_eligible.is_(True))
        )
    ).scalar_one()
    return {
        "provider_state": "not_configured",
        "policy_state": "freshness_unverified",
        "compliance_audits": total_audits,
        "passed": passed,
        # Derived rather than a `status != 'pass'` predicate: SQL would drop
        # NULL statuses, where the previous Python comparison counted them.
        "blocked": total_audits - passed,
        "human_review_packages": int(package_count or 0),
        "publication_eligible": int(publication_eligible or 0),
        "provider_cost_usd": Decimal(audits[2] or 0),
        "real_provider_mode": False,
        "test_fixture_mode": False,
    }


async def list_audits(session: AsyncSession, *, workspace_id: uuid.UUID) -> list[ComplianceAudit]:
    return list(
        (
            await session.execute(
                select(ComplianceAudit)
                .where(ComplianceAudit.workspace_id == workspace_id)
                .order_by(ComplianceAudit.created_at.desc())
            )
        )
        .scalars()
        .all()
    )


async def list_chief_audits(session: AsyncSession, *, workspace_id: uuid.UUID) -> list[ChiefAudit]:
    return list(
        (
            await session.execute(
                select(ChiefAudit)
                .where(ChiefAudit.workspace_id == workspace_id)
                .order_by(ChiefAudit.created_at.desc())
            )
        )
        .scalars()
        .all()
    )


async def list_review_packages(
    session: AsyncSession, *, workspace_id: uuid.UUID
) -> list[HumanReviewPackage]:
    return list(
        (
            await session.execute(
                select(HumanReviewPackage)
                .where(HumanReviewPackage.workspace_id == workspace_id)
                .order_by(HumanReviewPackage.created_at.desc())
            )
        )
        .scalars()
        .all()
    )


async def create_test_manifest(
    session: AsyncSession, *, workspace_id: uuid.UUID, actor_id: uuid.UUID
) -> AuditGateManifest:
    existing = (
        await session.execute(
            select(AuditGateManifest).where(
                AuditGateManifest.workspace_id == workspace_id,
                AuditGateManifest.content_type == "short_form_media",
                AuditGateManifest.manifest_version == 1,
            )
        )
    ).scalar_one_or_none()
    if existing:
        return existing
    manifest = AuditGateManifest(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        content_type="short_form_media",
        manifest_version=1,
        required_gates=DEFAULT_GATES,
        requirements={"mode": "test_fixture"},
        created_by=actor_id,
        test_data=True,
    )
    session.add(manifest)
    await session.flush()
    return manifest


async def create_test_compliance_pass(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    final_artifact_id: uuid.UUID,
    worker_id: str = "compliance-fixture",
) -> ComplianceAudit:
    artifact = await _artifact(session, workspace_id, final_artifact_id)
    media = (
        (
            await session.execute(
                select(MediaQaResult).where(
                    MediaQaResult.workspace_id == workspace_id,
                    MediaQaResult.final_artifact_id == artifact.id,
                    MediaQaResult.artifact_hash == artifact.artifact_hash,
                )
            )
        )
        .scalars()
        .first()
    )
    if media is None or media.status != "pass":
        raise ComplianceGateError("Media QA pass bound to exact artifact hash is required")
    evidence = ArtifactRightsEvidence(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        final_artifact_id=artifact.id,
        origin="test_fixture",
        rights_status="verified",
        generation_record={"mode": "test_fixture"},
        modification_lineage={"artifact_hash": artifact.artifact_hash},
        created_by=actor_id,
        test_data=True,
    )
    session.add(evidence)
    await session.flush()
    audit = ComplianceAudit(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        final_artifact_id=artifact.id,
        artifact_hash=artifact.artifact_hash,
        content_version_id=artifact.content_version_id,
        target_platform="test",
        compliance_worker_id=worker_id,
        input_snapshot={"mode": "test_fixture"},
        status="pass",
        risk_level="low",
        rights_status="verified",
        findings=[],
        evidence=[{"mode": "test_fixture"}],
        required_disclosures=[],
        reused_content_risk="low",
        monetization_risk="low",
        provider_state="test_fixture",
        started_at=datetime.now(UTC),
        completed_at=datetime.now(UTC),
        test_data=True,
    )
    session.add(audit)
    await session.flush()
    return audit


async def run_test_chief_audit(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    final_artifact_id: uuid.UUID,
    worker_id: str = "chief-auditor-fixture",
) -> ChiefAudit:
    artifact = await _artifact(session, workspace_id, final_artifact_id)
    manifest = await create_test_manifest(session, workspace_id=workspace_id, actor_id=actor_id)
    media = (
        (
            await session.execute(
                select(MediaQaResult).where(
                    MediaQaResult.workspace_id == workspace_id,
                    MediaQaResult.final_artifact_id == artifact.id,
                    MediaQaResult.artifact_hash == artifact.artifact_hash,
                )
            )
        )
        .scalars()
        .first()
    )
    compliance = (
        (
            await session.execute(
                select(ComplianceAudit).where(
                    ComplianceAudit.workspace_id == workspace_id,
                    ComplianceAudit.final_artifact_id == artifact.id,
                    ComplianceAudit.artifact_hash == artifact.artifact_hash,
                )
            )
        )
        .scalars()
        .first()
    )
    blockers: list[str] = []
    if media is None or media.status != "pass":
        blockers.append("media_qa_missing_or_not_pass")
    if compliance is None or compliance.status != "pass":
        blockers.append("compliance_missing_or_not_pass")
    status = "pass_to_human_review" if not blockers else "blocked"
    audit = ChiefAudit(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        final_artifact_id=artifact.id,
        artifact_hash=artifact.artifact_hash,
        content_version_id=artifact.content_version_id,
        gate_manifest_id=manifest.id,
        chief_auditor_worker_id=worker_id,
        gate_snapshot={
            "required": DEFAULT_GATES,
            "media_qa": media.status if media else "missing",
            "compliance": compliance.status if compliance else "missing",
        },
        lineage_status="complete" if not blockers else "incomplete",
        version_integrity_status="complete" if not blockers else "incomplete",
        cost_reconciliation_status="complete" if not blockers else "incomplete",
        provider_reconciliation_status="complete" if not blockers else "incomplete",
        warnings=[],
        blockers=blockers,
        evidence=[{"mode": "test_fixture"}],
        status=status,
        started_at=datetime.now(UTC),
        completed_at=datetime.now(UTC),
        test_data=True,
    )
    session.add(audit)
    await session.flush()
    return audit


async def invalidate_compliance(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    compliance_audit_id: uuid.UUID,
    reason: str,
) -> ComplianceInvalidation:
    audit = (
        await session.execute(
            select(ComplianceAudit).where(
                ComplianceAudit.workspace_id == workspace_id,
                ComplianceAudit.id == compliance_audit_id,
            )
        )
    ).scalar_one_or_none()
    if audit is None:
        raise ComplianceNotFoundError("compliance audit not found")
    invalidation = ComplianceInvalidation(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        compliance_audit_id=audit.id,
        final_artifact_id=audit.final_artifact_id,
        reason=reason,
        affected_dimensions=["chief_audit", "human_review", "publication_eligibility"],
        created_by=actor_id,
    )
    session.add(invalidation)
    await session.flush()
    return invalidation


async def publication_eligibility(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    final_artifact_id: uuid.UUID,
    target_platform: str,
) -> ArtifactPublicationEligibility:
    artifact = await _artifact(session, workspace_id, final_artifact_id)
    chief = (
        (
            await session.execute(
                select(ChiefAudit).where(
                    ChiefAudit.workspace_id == workspace_id,
                    ChiefAudit.final_artifact_id == artifact.id,
                    ChiefAudit.artifact_hash == artifact.artifact_hash,
                )
            )
        )
        .scalars()
        .first()
    )
    blockers = ["external_publishing_disabled"]
    if chief is None or chief.status != "pass_to_human_review":
        blockers.insert(0, "chief_audit_not_passed")
    readiness = (
        await session.execute(
            select(ProductionReadiness).where(
                ProductionReadiness.workspace_id == workspace_id,
                ProductionReadiness.final_artifact_id == artifact.id,
            )
        )
    ).scalar_one_or_none()
    if readiness is None or readiness.media_qa_state != "pass":
        blockers.insert(0, "media_qa_not_passed")
    row = ArtifactPublicationEligibility(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        final_artifact_id=artifact.id,
        artifact_hash=artifact.artifact_hash,
        content_version_id=artifact.content_version_id,
        target_platform=target_platform,
        chief_audit_id=chief.id if chief else None,
        status="blocked",
        blocking_reasons=blockers,
        publication_eligible=False,
        created_by=actor_id,
        updated_by=actor_id,
    )
    session.add(row)
    await session.flush()
    return row

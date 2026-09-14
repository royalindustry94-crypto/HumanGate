"""Bounded Strategist and independent Strategy Auditor V1 service.

Live preview runs persist limits and return provider-not-configured truthfully. The
fixture path is test-only and is the only path that can create a Strategy Brief.
"""

from __future__ import annotations

import hashlib
import os
import re
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.content_profile import get_business_context_state
from app.models.research import Opportunity
from app.models.strategy import (
    StrategyAudit,
    StrategyBrief,
    StrategyBriefOpportunity,
    StrategyRun,
    StrategySchedule,
)
from app.orchestration.controller import utc_day_start
from app.orchestration.outbox import emit
from app.schemas.strategy import StrategyRunCreate
from app.services import research

_UNTRUSTED_INSTRUCTION = re.compile(
    r"(?i)(ignore\s+(all\s+)?previous|system\s+prompt|developer\s+message|"
    r"reveal\s+(?:secret|token|password)|bypass\s+(?:review|security)|"
    r"execute\s+this\s+instruction)"
)
_SECRET_PATTERN = re.compile(
    r"(?i)(api[_ -]?key|authorization|bearer|oauth|token|password)\s*[:=]\s*[^\s,;]+"
)
_OVERSTATED_PATTERN = re.compile(
    r"(?i)(will\s+go\s+viral|guaranteed\s+(?:winner|success)|expected\s+\d+[km]?\s+views)"
)


class StrategyGateError(ValueError):
    """Raised when a source opportunity has not passed Research Auditor."""


class StrategyAuditGateError(ValueError):
    """Raised when a brief has not passed independent Strategy Auditor."""


def _utcnow() -> datetime:
    return datetime.now(UTC)


def _safe_text(value: str, *, field: str, maximum: int = 10_000) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > maximum:
        raise ValueError(f"{field} must be non-empty and at most {maximum} characters")
    if _UNTRUSTED_INSTRUCTION.search(normalized):
        raise ValueError(f"{field} contains an untrusted instruction pattern")
    if _SECRET_PATTERN.search(normalized):
        raise ValueError(f"{field} contains a secret-like value")
    return normalized


def _structural_fingerprint(
    *,
    source_opportunity_ids: list[uuid.UUID],
    objective: str,
    target_platform: str | None,
    content_format: str | None,
    creative_angle: str | None,
    hook_direction: str | None,
    cta_direction: str | None,
    business_goal: str | None,
) -> str:
    material = "\n".join(
        [
            *sorted(str(item) for item in source_opportunity_ids),
            objective.strip().lower(),
            (target_platform or "").strip().lower(),
            (content_format or "").strip().lower(),
            (creative_angle or "").strip().lower(),
            (hook_direction or "").strip().lower(),
            (cta_direction or "").strip().lower(),
            (business_goal or "").strip().lower(),
        ]
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


async def _emit_run(
    session: AsyncSession, run: StrategyRun, event_type: str, payload: dict[str, Any]
) -> None:
    await emit(
        session,
        event_type=event_type,
        workspace_id=run.workspace_id,
        aggregate_type="strategy_run",
        aggregate_id=run.id,
        correlation_id=run.correlation_id,
        trace_id=run.trace_id,
        payload=payload,
        produced_by="strategist-service",
    )


async def get_run(
    session: AsyncSession, *, workspace_id: uuid.UUID, run_id: uuid.UUID
) -> StrategyRun | None:
    return (
        await session.execute(
            select(StrategyRun).where(
                StrategyRun.id == run_id, StrategyRun.workspace_id == workspace_id
            )
        )
    ).scalar_one_or_none()


async def list_runs(session: AsyncSession, *, workspace_id: uuid.UUID) -> list[StrategyRun]:
    return list(
        (
            await session.execute(
                select(StrategyRun)
                .where(StrategyRun.workspace_id == workspace_id)
                .order_by(StrategyRun.created_at.desc())
            )
        ).scalars()
    )


async def get_brief(
    session: AsyncSession, *, workspace_id: uuid.UUID, brief_id: uuid.UUID
) -> StrategyBrief | None:
    return (
        await session.execute(
            select(StrategyBrief).where(
                StrategyBrief.id == brief_id,
                StrategyBrief.workspace_id == workspace_id,
                StrategyBrief.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()


async def list_briefs(session: AsyncSession, *, workspace_id: uuid.UUID) -> list[StrategyBrief]:
    return list(
        (
            await session.execute(
                select(StrategyBrief)
                .where(
                    StrategyBrief.workspace_id == workspace_id,
                    StrategyBrief.deleted_at.is_(None),
                )
                .order_by(StrategyBrief.created_at.desc())
            )
        ).scalars()
    )


async def brief_opportunity_ids(
    session: AsyncSession, *, workspace_id: uuid.UUID, brief_id: uuid.UUID
) -> list[uuid.UUID]:
    return list(
        (
            await session.execute(
                select(StrategyBriefOpportunity.opportunity_id).where(
                    StrategyBriefOpportunity.workspace_id == workspace_id,
                    StrategyBriefOpportunity.strategy_brief_id == brief_id,
                )
            )
        ).scalars()
    )


async def latest_audit(
    session: AsyncSession, *, workspace_id: uuid.UUID, brief_id: uuid.UUID
) -> StrategyAudit | None:
    return (
        await session.execute(
            select(StrategyAudit)
            .where(
                StrategyAudit.workspace_id == workspace_id,
                StrategyAudit.strategy_brief_id == brief_id,
            )
            .order_by(StrategyAudit.checked_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()


async def _require_research_pass(
    session: AsyncSession, *, workspace_id: uuid.UUID, opportunity_ids: list[uuid.UUID]
) -> list[Opportunity]:
    opportunities: list[Opportunity] = []
    for opportunity_id in opportunity_ids:
        try:
            await research.strategist_gate(
                session, workspace_id=workspace_id, opportunity_id=opportunity_id
            )
        except research.ResearchGateError as exc:
            raise StrategyGateError(str(exc)) from exc
        opportunity = await research.get_opportunity(
            session, workspace_id=workspace_id, opportunity_id=opportunity_id
        )
        if opportunity is None:
            raise StrategyGateError("source opportunity not found")
        opportunities.append(opportunity)
    return opportunities


async def create_manual_run(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    payload: StrategyRunCreate,
) -> StrategyRun:
    """Persist a bounded live request without a provider, spend, or brief claim."""
    objective = _safe_text(payload.strategy_objective, field="strategy objective", maximum=1000)
    opportunity_ids = list(payload.source_opportunity_ids)
    await _require_research_pass(
        session, workspace_id=workspace_id, opportunity_ids=opportunity_ids
    )
    business_context_state = await get_business_context_state(session, workspace_id=workspace_id)
    last_error = "STRATEGY PROVIDER NOT CONFIGURED"
    if business_context_state != "complete":
        last_error += "; BUSINESS CONTEXT INCOMPLETE"
    now = _utcnow()
    run = StrategyRun(
        workspace_id=workspace_id,
        trigger="manual",
        strategy_objective=objective,
        source_opportunity_ids=[str(item) for item in opportunity_ids],
        started_at=now,
        deadline=now + timedelta(minutes=15),
        max_provider_calls=payload.max_provider_calls,
        max_tokens=payload.max_tokens,
        max_cost_usd=payload.max_cost_usd,
        max_attempts=payload.max_attempts,
        status="provider_not_configured",
        provider_state="not_configured",
        business_context_state=business_context_state,
        last_error=last_error,
        created_by=actor_id,
        updated_by=actor_id,
    )
    session.add(run)
    await session.flush()
    await _emit_run(
        session,
        run,
        "strategy.started",
        {
            "trigger": "manual",
            "source_opportunity_ids": [str(item) for item in opportunity_ids],
            "limits": {
                "max_provider_calls": run.max_provider_calls,
                "max_tokens": run.max_tokens,
                "max_cost_usd": str(run.max_cost_usd),
                "max_attempts": run.max_attempts,
            },
            "provider_state": run.provider_state,
            "business_context_state": run.business_context_state,
        },
    )
    await _emit_run(
        session,
        run,
        "strategy.provider_not_configured",
        {"detail": run.last_error},
    )
    return run


async def record_fixture_brief(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    objective: str,
    source_opportunity_ids: list[uuid.UUID],
    fixture_brief: dict[str, Any],
) -> tuple[StrategyRun, StrategyBrief, bool]:
    """Test-only persistence path for brief/audit state transitions."""
    if os.getenv("ENVIRONMENT") != "test":
        raise RuntimeError("fixture strategy execution is test-only")
    clean_objective = _safe_text(objective, field="strategy objective", maximum=1000)
    opportunities = await _require_research_pass(
        session, workspace_id=workspace_id, opportunity_ids=source_opportunity_ids
    )
    now = _utcnow()
    run = StrategyRun(
        workspace_id=workspace_id,
        trigger="manual_test_fixture",
        strategy_objective=clean_objective,
        source_opportunity_ids=[str(item) for item in source_opportunity_ids],
        started_at=now,
        deadline=now + timedelta(minutes=5),
        max_provider_calls=0,
        max_tokens=0,
        max_cost_usd=Decimal("0.00"),
        max_attempts=1,
        status="running",
        provider_state="fixture_test_only",
        business_context_state=str(fixture_brief.get("business_context_state", "complete")),
        created_by=actor_id,
        updated_by=actor_id,
        test_data=True,
    )
    session.add(run)
    await session.flush()
    await _emit_run(session, run, "strategy.started", {"trigger": run.trigger, "test_data": True})

    def optional(name: str) -> str | None:
        value = fixture_brief.get(name)
        return _safe_text(str(value), field=name) if value is not None else None

    brief_objective = _safe_text(
        str(fixture_brief.get("objective", clean_objective)), field="brief objective"
    )
    evidence_summary = _safe_text(
        str(fixture_brief.get("evidence_summary", opportunities[0].summary)),
        field="evidence summary",
    )
    reasoning = _safe_text(
        str(fixture_brief.get("reasoning", opportunities[0].proposed_angle)),
        field="reasoning",
    )
    fingerprint = _structural_fingerprint(
        source_opportunity_ids=source_opportunity_ids,
        objective=brief_objective,
        target_platform=fixture_brief.get("target_platform"),
        content_format=fixture_brief.get("content_format"),
        creative_angle=fixture_brief.get("creative_angle"),
        hook_direction=fixture_brief.get("hook_direction"),
        cta_direction=fixture_brief.get("cta_direction"),
        business_goal=fixture_brief.get("business_goal"),
    )
    existing = (
        await session.execute(
            select(StrategyBrief).where(
                StrategyBrief.workspace_id == workspace_id,
                StrategyBrief.structural_fingerprint == fingerprint,
                StrategyBrief.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        run.status = "duplicate"
        run.last_error = "DUPLICATE — REUSE OR REVISE"
        await _emit_run(
            session,
            run,
            "strategy.duplicate_detected",
            {"strategy_brief_id": str(existing.id), "test_data": True},
        )
        return run, existing, True

    brief = StrategyBrief(
        workspace_id=workspace_id,
        strategy_run_id=run.id,
        objective=brief_objective,
        target_audience=optional("target_audience"),
        target_platform=optional("target_platform"),
        content_format=optional("content_format"),
        creative_angle=optional("creative_angle"),
        core_message=optional("core_message"),
        hook_direction=optional("hook_direction"),
        cta_direction=optional("cta_direction"),
        business_goal=optional("business_goal"),
        success_metric=optional("success_metric"),
        commercial_goal=optional("commercial_goal"),
        estimated_complexity=str(fixture_brief.get("estimated_complexity", "low")),
        risk_level=str(fixture_brief.get("risk_level", "low")),
        evidence_summary=evidence_summary,
        reasoning=reasoning,
        confidence=Decimal(str(fixture_brief.get("confidence", "0.50"))),
        priority=str(fixture_brief.get("priority", "medium_priority")),
        component_scores=dict(fixture_brief.get("component_scores", {})),
        score_reasoning=dict(fixture_brief.get("score_reasoning", {})),
        recommended_length=optional("recommended_length"),
        recommended_posting_window=optional("recommended_posting_window"),
        required_assets=list(fixture_brief.get("required_assets", [])),
        production_requirements=list(fixture_brief.get("production_requirements", [])),
        rights_requirements=list(fixture_brief.get("rights_requirements", [])),
        compliance_requirements=list(fixture_brief.get("compliance_requirements", [])),
        estimated_provider_usage=dict(fixture_brief.get("estimated_provider_usage", {})),
        estimated_cost_range=dict(fixture_brief.get("estimated_cost_range", {})),
        cost_state=str(fixture_brief.get("cost_state", "known")),
        capability_state=str(fixture_brief.get("capability_state", "configured")),
        business_context_state=str(fixture_brief.get("business_context_state", "complete")),
        performance_data_state=str(fixture_brief.get("performance_data_state", "no_data")),
        structural_fingerprint=fingerprint,
        repetition_state=str(fixture_brief.get("repetition_state", "clear")),
        repetition_reasons=list(fixture_brief.get("repetition_reasons", [])),
        created_by_worker="strategist_fixture",
        status="draft",
        test_data=True,
        created_by=actor_id,
        updated_by=actor_id,
    )
    session.add(brief)
    await session.flush()
    for opportunity in opportunities:
        session.add(
            StrategyBriefOpportunity(
                workspace_id=workspace_id,
                strategy_brief_id=brief.id,
                opportunity_id=opportunity.id,
            )
        )
    run.briefs_created = 1
    run.status = "succeeded"
    await _emit_run(
        session,
        run,
        "strategy.brief_created",
        {"strategy_brief_id": str(brief.id), "test_data": True},
    )
    return run, brief, False


async def audit_brief(
    session: AsyncSession, *, workspace_id: uuid.UUID, brief_id: uuid.UUID
) -> StrategyAudit:
    """Independently inspect persisted brief inputs; never trust Strategist output."""
    brief = await get_brief(session, workspace_id=workspace_id, brief_id=brief_id)
    if brief is None:
        raise LookupError("strategy brief not found")
    source_ids = await brief_opportunity_ids(session, workspace_id=workspace_id, brief_id=brief_id)
    findings: list[dict[str, str]] = []
    warnings: list[str] = []
    blocked: list[str] = []
    if not source_ids:
        blocked.append("No source opportunities are linked to this Strategy Brief.")
    for opportunity_id in source_ids:
        opportunity_audit = await research.latest_audit(
            session, workspace_id=workspace_id, opportunity_id=opportunity_id
        )
        if opportunity_audit is None or opportunity_audit.state != "pass":
            blocked.append("Research Auditor PASS is required for every source opportunity.")
            break
        findings.append(
            {
                "opportunity_id": str(opportunity_id),
                "research_audit_state": opportunity_audit.state,
                "evidence_traceability": "pass",
            }
        )
    if brief.business_context_state != "complete" or not brief.business_goal:
        blocked.append("BUSINESS CONTEXT INCOMPLETE")
    if not brief.target_platform or not brief.content_format:
        blocked.append("Target platform and content format are required.")
    if brief.cost_state in {"unknown", "not_configured"}:
        blocked.append("COST UNKNOWN")
    if brief.capability_state != "configured":
        blocked.append("BLOCKED — REQUIRED CAPABILITY NOT CONFIGURED")
    if brief.repetition_state in {"duplicate", "require_differentiation", "blocked"}:
        blocked.extend(brief.repetition_reasons or ["Materially repetitive strategy."])
    if _OVERSTATED_PATTERN.search(brief.reasoning) or _OVERSTATED_PATTERN.search(
        brief.evidence_summary
    ):
        blocked.append("Unsupported performance or commercial guarantee detected.")
    if brief.performance_data_state != "no_data":
        warnings.append("Performance-data state requires a source-backed future check.")
    state = "blocked" if blocked else ("pass_with_warning" if warnings else "pass")
    snapshot = {
        "objective": brief.objective,
        "business_goal": brief.business_goal,
        "target_platform": brief.target_platform,
        "content_format": brief.content_format,
        "structural_fingerprint": brief.structural_fingerprint,
        "cost_state": brief.cost_state,
        "capability_state": brief.capability_state,
        "business_context_state": brief.business_context_state,
        "repetition_state": brief.repetition_state,
    }
    audit = StrategyAudit(
        workspace_id=workspace_id,
        strategy_brief_id=brief.id,
        strategy_run_id=brief.strategy_run_id,
        state=state,
        brief_snapshot=snapshot,
        findings=findings,
        warnings=warnings,
        blocked_reasons=blocked,
        test_data=brief.test_data,
    )
    session.add(audit)
    brief.audit_gate_status = state
    brief.writer_handoff_state = "eligible" if state == "pass" else "blocked"
    brief.status = "audited_passed" if state == "pass" else "audited_blocked"
    run = await get_run(session, workspace_id=workspace_id, run_id=brief.strategy_run_id)
    if run is not None:
        if state == "pass":
            run.briefs_passed += 1
        else:
            run.briefs_blocked += 1
    await session.flush()
    if run is not None:
        await _emit_run(
            session,
            run,
            f"strategy.audit.{state}",
            {
                "strategy_brief_id": str(brief.id),
                "audit_id": str(audit.id),
                "test_data": brief.test_data,
            },
        )
    return audit


async def writer_gate(
    session: AsyncSession, *, workspace_id: uuid.UUID, brief_id: uuid.UUID
) -> dict[str, Any]:
    brief = await get_brief(session, workspace_id=workspace_id, brief_id=brief_id)
    if brief is None:
        raise LookupError("strategy brief not found")
    audit = await latest_audit(session, workspace_id=workspace_id, brief_id=brief_id)
    if audit is None or audit.state != "pass":
        detail = "Independent Strategy Auditor PASS is required before Writer eligibility."
        run = await get_run(session, workspace_id=workspace_id, run_id=brief.strategy_run_id)
        if run is not None:
            await _emit_run(
                session,
                run,
                "strategy.writer_denied",
                {
                    "strategy_brief_id": str(brief.id),
                    "audit_state": audit.state if audit else "not_run",
                },
            )
        raise StrategyAuditGateError(detail)
    brief.writer_handoff_state = "eligible"
    run = await get_run(session, workspace_id=workspace_id, run_id=brief.strategy_run_id)
    if run is not None:
        await _emit_run(
            session,
            run,
            "strategy.writer_eligible",
            {"strategy_brief_id": str(brief.id), "test_data": brief.test_data},
        )
    return {
        "strategy_brief_id": brief.id,
        "eligible": True,
        "state": "eligible",
        "detail": (
            "Strategy is eligible for a future Writer handoff; no Writer provider is configured."
        ),
    }


async def summary(session: AsyncSession, *, workspace_id: uuid.UUID) -> dict[str, Any]:
    # Queried directly instead of scanning an unbounded `list_runs()`: the
    # summary only needs the in-flight run and the most recent one.
    current = (
        await session.execute(
            select(StrategyRun)
            .where(StrategyRun.workspace_id == workspace_id, StrategyRun.status == "running")
            .order_by(StrategyRun.created_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    last = (
        await session.execute(
            select(StrategyRun)
            .where(StrategyRun.workspace_id == workspace_id)
            .order_by(StrategyRun.created_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    counts = (
        await session.execute(
            select(
                func.count(StrategyBrief.id),
                func.count(StrategyBrief.id).filter(StrategyBrief.audit_gate_status == "pass"),
                func.count(StrategyBrief.id).filter(StrategyBrief.audit_gate_status != "pass"),
            ).where(
                StrategyBrief.workspace_id == workspace_id,
                StrategyBrief.deleted_at.is_(None),
            )
        )
    ).one()
    schedule = (
        await session.execute(
            select(StrategySchedule).where(StrategySchedule.workspace_id == workspace_id)
        )
    ).scalar_one_or_none()
    received = (
        await session.execute(
            select(func.count(Opportunity.id)).where(
                Opportunity.workspace_id == workspace_id,
                Opportunity.audit_gate_status == "pass",
                Opportunity.deleted_at.is_(None),
            )
        )
    ).scalar_one()
    # `cost_today_usd` now actually means today. It previously summed every
    # run ever recorded for the workspace, so the figure grew without bound
    # and never matched its own field name.
    cost = Decimal(
        (
            await session.execute(
                select(func.coalesce(func.sum(StrategyRun.actual_cost_usd), 0)).where(
                    StrategyRun.workspace_id == workspace_id,
                    StrategyRun.created_at >= utc_day_start(),
                )
            )
        ).scalar_one()
    )
    business_context_state = await get_business_context_state(session, workspace_id=workspace_id)
    current_or_last = current or last
    if current_or_last:
        last_error = current_or_last.last_error
    else:
        last_error = "STRATEGY PROVIDER NOT CONFIGURED"
        if business_context_state != "complete":
            last_error += "; BUSINESS CONTEXT INCOMPLETE"
    return {
        "provider_state": "not_configured",
        "status": current.status if current else (last.status if last else "not_run"),
        "current_strategy": current,
        "last_run": last,
        "next_run_at": schedule.next_run_at if schedule and schedule.enabled else None,
        "opportunities_received": int(received or 0),
        "briefs_created": int(counts[0] or 0),
        "briefs_passed": int(counts[1] or 0),
        "briefs_blocked": int(counts[2] or 0),
        "cost_today_usd": cost,
        "last_error": last_error,
        "schedule_enabled": bool(schedule.enabled) if schedule else False,
        "business_context_state": business_context_state,
        "performance_data_state": "no_data",
    }

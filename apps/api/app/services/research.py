"""Bounded Scout research and independent Research Auditor service.

The live Founder Preview intentionally has no external research provider. Manual
runs persist their limits and truthfully finish as ``provider_not_configured``.
The deterministic fixture path is test-only and exercises the same persistence,
provenance, deduplication, and independent audit gates without pretending to be
live web research.
"""

from __future__ import annotations

import hashlib
import ipaddress
import os
import re
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.research import (
    Opportunity,
    OpportunityEvidence,
    ResearchAudit,
    ResearchRun,
    ResearchSchedule,
    ResearchSource,
)
from app.orchestration.controller import utc_day_start
from app.orchestration.outbox import emit
from app.schemas.research import ResearchRunCreate

MAX_FIXTURE_EXCERPT = 6_000
_UNTRUSTED_INSTRUCTION = re.compile(
    r"(?i)(ignore\s+(all\s+)?previous|system\s+prompt|developer\s+message|"
    r"reveal\s+(?:secret|token|password)|bypass\s+(?:review|security)|"
    r"execute\s+this\s+instruction)"
)
_SECRET_PATTERN = re.compile(
    r"(?i)(api[_ -]?key|authorization|bearer|oauth|token|password)\s*[:=]\s*[^\s,;]+"
)


class ResearchGateError(ValueError):
    """Raised when an opportunity has not passed independent research audit."""


def _utcnow() -> datetime:
    return datetime.now(UTC)


def _canonical_url(value: str) -> str:
    parsed = urlsplit(value.strip())
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
    ):
        raise ValueError("source URL must be a public http(s) URL without embedded credentials")
    host = parsed.hostname.lower().rstrip(".")
    if host in {"localhost", "localhost.localdomain"}:
        raise ValueError("private or localhost source URL is not permitted")
    try:
        address = ipaddress.ip_address(host)
        if (
            address.is_private
            or address.is_loopback
            or address.is_link_local
            or address.is_reserved
        ):
            raise ValueError("private or local source URL is not permitted")
    except ValueError as exc:
        if "private or local" in str(exc):
            raise
    path = parsed.path.rstrip("/") or "/"
    return urlunsplit((parsed.scheme.lower(), host, path, parsed.query, ""))


def _safe_excerpt(value: str | None) -> tuple[str | None, str | None]:
    if value is None:
        return None, None
    bounded = value.strip()[:MAX_FIXTURE_EXCERPT]
    if _UNTRUSTED_INSTRUCTION.search(bounded):
        return None, "untrusted instruction pattern detected"
    return _SECRET_PATTERN.sub("[REDACTED_SECRET]", bounded), None


def _dedupe_key(
    *,
    topic: str,
    angle: str,
    platform: str | None,
    format_name: str | None,
    source_urls: list[str],
) -> str:
    material = "\n".join(
        [
            topic.strip().lower(),
            angle.strip().lower(),
            (platform or "").strip().lower(),
            (format_name or "").strip().lower(),
        ]
        + sorted(source_urls)
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _run_is_terminal(status: str) -> bool:
    return status in {
        "succeeded",
        "provider_not_configured",
        "budget_exhausted",
        "timeout",
        "source_limit_reached",
        "failed",
        "cancelled",
    }


async def _emit_run(
    session: AsyncSession, run: ResearchRun, event_type: str, payload: dict
) -> None:
    await emit(
        session,
        event_type=event_type,
        workspace_id=run.workspace_id,
        aggregate_type="research_run",
        aggregate_id=run.id,
        correlation_id=run.correlation_id,
        trace_id=run.trace_id,
        payload=payload,
        produced_by="scout-service",
    )


async def create_manual_run(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    payload: ResearchRunCreate,
) -> ResearchRun:
    """Persist a bounded manual run. No provider means no external call or spend."""
    now = _utcnow()
    # Runs are deliberately short-lived in this preview; a real provider later
    # must honor the persisted deadline rather than invent a longer horizon.
    deadline = now + timedelta(minutes=15)
    run = ResearchRun(
        workspace_id=workspace_id,
        trigger="manual",
        research_objective=payload.research_objective.strip(),
        permitted_sources=list(payload.permitted_sources),
        started_at=now,
        deadline=deadline,
        max_searches=payload.max_searches,
        max_provider_calls=payload.max_provider_calls,
        max_tokens=payload.max_tokens,
        max_cost_usd=payload.max_cost_usd,
        max_attempts=payload.max_attempts,
        status="provider_not_configured",
        provider_state="not_configured",
        last_error="RESEARCH PROVIDER NOT CONFIGURED",
        created_by=actor_id,
        updated_by=actor_id,
    )
    session.add(run)
    await session.flush()
    await _emit_run(
        session,
        run,
        "research.started",
        {
            "trigger": "manual",
            "limits": {
                "max_searches": run.max_searches,
                "max_provider_calls": run.max_provider_calls,
                "max_tokens": run.max_tokens,
                "max_cost_usd": str(run.max_cost_usd),
                "max_attempts": run.max_attempts,
            },
        },
    )
    await _emit_run(
        session,
        run,
        "research.provider_not_configured",
        {
            "detail": "No external research provider is configured in the Founder Preview.",
        },
    )
    return run


async def list_runs(
    session: AsyncSession, *, workspace_id: uuid.UUID, limit: int = 50
) -> list[ResearchRun]:
    result = await session.execute(
        select(ResearchRun)
        .where(ResearchRun.workspace_id == workspace_id)
        .order_by(ResearchRun.created_at.desc())
        .limit(limit)
    )
    return list(result.scalars().all())


async def get_run(
    session: AsyncSession, *, workspace_id: uuid.UUID, run_id: uuid.UUID
) -> ResearchRun | None:
    return (
        await session.execute(
            select(ResearchRun).where(
                ResearchRun.workspace_id == workspace_id, ResearchRun.id == run_id
            )
        )
    ).scalar_one_or_none()


async def list_opportunities(
    session: AsyncSession, *, workspace_id: uuid.UUID, limit: int = 100
) -> list[Opportunity]:
    result = await session.execute(
        select(Opportunity)
        .where(Opportunity.workspace_id == workspace_id, Opportunity.deleted_at.is_(None))
        .order_by(Opportunity.discovered_at.desc())
        .limit(limit)
    )
    return list(result.scalars().all())


async def get_opportunity(
    session: AsyncSession, *, workspace_id: uuid.UUID, opportunity_id: uuid.UUID
) -> Opportunity | None:
    return (
        await session.execute(
            select(Opportunity).where(
                Opportunity.workspace_id == workspace_id,
                Opportunity.id == opportunity_id,
                Opportunity.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()


async def opportunity_evidence(
    session: AsyncSession, *, workspace_id: uuid.UUID, opportunity_id: uuid.UUID
) -> list[tuple[OpportunityEvidence, ResearchSource]]:
    result = await session.execute(
        select(OpportunityEvidence, ResearchSource)
        .join(ResearchSource, ResearchSource.id == OpportunityEvidence.source_id)
        .where(
            OpportunityEvidence.workspace_id == workspace_id,
            OpportunityEvidence.opportunity_id == opportunity_id,
            ResearchSource.workspace_id == workspace_id,
        )
        .order_by(ResearchSource.retrieved_at.desc())
    )
    return list(result.tuples().all())


async def latest_audit(
    session: AsyncSession, *, workspace_id: uuid.UUID, opportunity_id: uuid.UUID
) -> ResearchAudit | None:
    return (
        await session.execute(
            select(ResearchAudit)
            .where(
                ResearchAudit.workspace_id == workspace_id,
                ResearchAudit.opportunity_id == opportunity_id,
            )
            .order_by(ResearchAudit.checked_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()


async def summary(session: AsyncSession, *, workspace_id: uuid.UUID) -> dict[str, Any]:
    runs = await list_runs(session, workspace_id=workspace_id, limit=2)
    current = next((run for run in runs if not _run_is_terminal(run.status)), None)
    last = runs[0] if runs else None
    counts = (
        await session.execute(
            select(
                func.count(Opportunity.id),
                func.count(Opportunity.id).filter(Opportunity.audit_gate_status != "not_run"),
                func.count(Opportunity.id).filter(Opportunity.audit_gate_status == "blocked"),
            ).where(
                Opportunity.workspace_id == workspace_id,
                Opportunity.deleted_at.is_(None),
            )
        )
    ).one()
    schedule = (
        await session.execute(
            select(ResearchSchedule).where(ResearchSchedule.workspace_id == workspace_id)
        )
    ).scalar_one_or_none()
    # Scoped to the current UTC day to match the field name; it previously
    # summed whatever the last two runs cost, on any day.
    cost = Decimal(
        (
            await session.execute(
                select(func.coalesce(func.sum(ResearchRun.actual_cost_usd), 0)).where(
                    ResearchRun.workspace_id == workspace_id,
                    ResearchRun.created_at >= utc_day_start(),
                )
            )
        ).scalar_one()
    )
    current_or_last = current or last
    return {
        "provider_state": "not_configured",
        "status": current.status if current else (last.status if last else "not_run"),
        "current_research": current,
        "last_run": last,
        "next_run_at": schedule.next_run_at if schedule and schedule.enabled else None,
        "opportunities_found": int(counts[0] or 0),
        "audited_opportunities": int(counts[1] or 0),
        "blocked_findings": int(counts[2] or 0),
        "cost_today_usd": cost,
        "last_error": current_or_last.last_error
        if current_or_last
        else "RESEARCH PROVIDER NOT CONFIGURED",
        "schedule_enabled": bool(schedule.enabled) if schedule else False,
        "research_data_state": "not_connected",
    }


async def record_fixture_run(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    objective: str,
    fixture_sources: list[dict[str, Any]],
    fixture_opportunity: dict[str, Any],
) -> tuple[ResearchRun, Opportunity | None]:
    """Test-only real persistence path. Never callable in preview/production."""
    if os.getenv("ENVIRONMENT") != "test":
        raise RuntimeError("fixture research execution is test-only")
    now = _utcnow()
    run = ResearchRun(
        workspace_id=workspace_id,
        trigger="manual_test_fixture",
        research_objective=objective,
        permitted_sources=[str(source.get("url", "")) for source in fixture_sources],
        started_at=now,
        deadline=now + timedelta(minutes=5),
        max_searches=max(1, len(fixture_sources)),
        max_provider_calls=0,
        max_tokens=0,
        max_cost_usd=Decimal("0.00"),
        max_attempts=1,
        status="running",
        provider_state="fixture_test_only",
        created_by=actor_id,
        updated_by=actor_id,
        test_data=True,
    )
    session.add(run)
    await session.flush()
    await _emit_run(
        session,
        run,
        "research.started",
        {"trigger": "manual_test_fixture", "test_data": True},
    )

    accepted: list[ResearchSource] = []
    for item in fixture_sources[: run.max_searches]:
        try:
            url = _canonical_url(str(item.get("url", "")))
        except ValueError as exc:
            await _emit_run(
                session,
                run,
                "research.source_rejected",
                {"reason": str(exc), "test_data": True},
            )
            continue
        excerpt, rejection_reason = _safe_excerpt(item.get("excerpt"))
        source = ResearchSource(
            workspace_id=workspace_id,
            research_run_id=run.id,
            canonical_url=url,
            source_type=str(item.get("source_type", "fixture")),
            retrieved_at=now,
            published_at=item.get("published_at"),
            publisher=item.get("publisher"),
            author=item.get("author"),
            claim_supported=item.get("claim_supported"),
            freshness=str(item.get("freshness", "test")),
            confidence=Decimal(str(item.get("confidence", "0.50"))),
            content_digest=hashlib.sha256(
                (excerpt or rejection_reason or url).encode("utf-8")
            ).hexdigest(),
            safe_excerpt=excerpt,
            handling_state="rejected" if rejection_reason else "accepted",
            rejection_reason=rejection_reason,
            test_data=True,
        )
        session.add(source)
        await session.flush()
        if rejection_reason:
            await _emit_run(
                session,
                run,
                "research.source_rejected",
                {
                    "source_id": str(source.id),
                    "reason": rejection_reason,
                    "test_data": True,
                },
            )
        else:
            accepted.append(source)
            await _emit_run(
                session,
                run,
                "research.source_recorded",
                {"source_id": str(source.id), "test_data": True},
            )

    run.searches_used = len(fixture_sources)
    if not accepted:
        run.status = "failed"
        run.last_error = "No safe evidence sources were accepted"
        await _emit_run(
            session,
            run,
            "research.failed",
            {"reason": run.last_error, "test_data": True},
        )
        return run, None

    topic = str(fixture_opportunity["topic"]).strip()
    angle = str(fixture_opportunity["proposed_angle"]).strip()
    platform = fixture_opportunity.get("target_platform")
    format_name = fixture_opportunity.get("suggested_format")
    key = _dedupe_key(
        topic=topic,
        angle=angle,
        platform=platform,
        format_name=format_name,
        source_urls=[s.canonical_url for s in accepted],
    )
    existing = (
        await session.execute(
            select(Opportunity).where(
                Opportunity.workspace_id == workspace_id,
                Opportunity.dedupe_key == key,
                Opportunity.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if existing is None:
        opportunity = Opportunity(
            workspace_id=workspace_id,
            research_run_id=run.id,
            title=str(fixture_opportunity["title"]).strip(),
            topic=topic,
            summary=str(fixture_opportunity["summary"]).strip(),
            proposed_angle=angle,
            target_audience=fixture_opportunity.get("target_audience"),
            target_platform=platform,
            suggested_format=format_name,
            freshness=str(fixture_opportunity.get("freshness", "test")),
            source_count=len(accepted),
            confidence=Decimal(str(fixture_opportunity.get("confidence", "0.50"))),
            risk=str(fixture_opportunity.get("risk", "low")),
            status="active",
            created_by_worker="scout_fixture",
            component_scores=dict(fixture_opportunity.get("component_scores", {})),
            score_reasoning=dict(fixture_opportunity.get("score_reasoning", {})),
            dedupe_key=key,
            test_data=True,
            created_by=actor_id,
            updated_by=actor_id,
        )
        session.add(opportunity)
        await session.flush()
        event_type = "opportunity.created"
    else:
        opportunity = existing
        opportunity.source_count += len(accepted)
        opportunity.updated_by = actor_id
        event_type = "opportunity.duplicate_detected"

    for source in accepted:
        link = OpportunityEvidence(
            workspace_id=workspace_id,
            opportunity_id=opportunity.id,
            source_id=source.id,
            claim_supported=source.claim_supported or opportunity.summary,
            relevance=Decimal("0.80"),
            contradiction_flag=False,
        )
        session.add(link)
    await session.flush()
    await _emit_run(
        session,
        run,
        event_type,
        {"opportunity_id": str(opportunity.id), "test_data": True},
    )
    run.opportunity_count = 1 if existing is None else 0
    run.status = "succeeded"
    await _emit_run(
        session,
        run,
        "research.completed",
        {"opportunity_id": str(opportunity.id), "test_data": True},
    )
    return run, opportunity


async def audit_opportunity(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    opportunity_id: uuid.UUID,
) -> ResearchAudit:
    """Independently evaluate stored source properties, never Scout’s conclusion."""
    opportunity = await get_opportunity(
        session, workspace_id=workspace_id, opportunity_id=opportunity_id
    )
    if opportunity is None:
        raise LookupError("opportunity not found")
    evidence = await opportunity_evidence(
        session, workspace_id=workspace_id, opportunity_id=opportunity_id
    )
    sources = [source for _, source in evidence]
    findings: list[dict[str, str]] = []
    warnings: list[str] = []
    blocked: list[str] = []
    accepted = [source for source in sources if source.handling_state == "accepted"]
    rejected = [source for source in sources if source.handling_state == "rejected"]
    if not accepted:
        blocked.append("No accepted source provenance supports this opportunity.")
    if rejected:
        blocked.append("At least one source was rejected as unsafe or unverified.")
    if len({source.content_digest for source in accepted}) < len(accepted):
        blocked.append("Duplicate source content detected.")
    if len(accepted) == 1 and not blocked:
        warnings.append("Only one independent accepted source is available.")
    if any(source.freshness in {"stale", "unknown"} for source in accepted) and not blocked:
        warnings.append("At least one accepted source has stale or unknown freshness.")
    for source in accepted:
        findings.append(
            {
                "source_id": str(source.id),
                "legitimacy": "accepted_provenance",
                "freshness": source.freshness,
                "claim_support": "present" if source.claim_supported else "missing",
            }
        )
    state = "blocked" if blocked else ("pass_with_warning" if warnings else "pass")
    snapshot = {
        "title": opportunity.title,
        "topic": opportunity.topic,
        "summary": opportunity.summary,
        "proposed_angle": opportunity.proposed_angle,
        "source_count": opportunity.source_count,
        "component_scores": opportunity.component_scores,
    }
    audit = ResearchAudit(
        workspace_id=workspace_id,
        opportunity_id=opportunity.id,
        research_run_id=opportunity.research_run_id,
        state=state,
        scout_snapshot=snapshot,
        findings=findings,
        warnings=warnings,
        blocked_reasons=blocked,
        test_data=opportunity.test_data,
    )
    session.add(audit)
    opportunity.audit_gate_status = state
    if state == "blocked":
        opportunity.status = "blocked"
    run = await get_run(session, workspace_id=workspace_id, run_id=opportunity.research_run_id)
    if run is not None:
        run.audited_opportunity_count += 1
        if state == "blocked":
            run.blocked_opportunity_count += 1
    await session.flush()
    if run is not None:
        await _emit_run(
            session,
            run,
            f"research.audit.{state}",
            {
                "opportunity_id": str(opportunity.id),
                "audit_id": str(audit.id),
                "test_data": opportunity.test_data,
            },
        )
    return audit


async def strategist_gate(
    session: AsyncSession, *, workspace_id: uuid.UUID, opportunity_id: uuid.UUID
) -> dict[str, Any]:
    opportunity = await get_opportunity(
        session, workspace_id=workspace_id, opportunity_id=opportunity_id
    )
    if opportunity is None:
        raise LookupError("opportunity not found")
    audit = await latest_audit(session, workspace_id=workspace_id, opportunity_id=opportunity_id)
    if audit is None or audit.state != "pass":
        detail = "Independent Research Auditor PASS is required before Strategist eligibility."
        run = await get_run(session, workspace_id=workspace_id, run_id=opportunity.research_run_id)
        if run is not None:
            await _emit_run(
                session,
                run,
                "opportunity.strategist_denied",
                {
                    "opportunity_id": str(opportunity.id),
                    "audit_state": audit.state if audit else "not_run",
                },
            )
        raise ResearchGateError(detail)
    opportunity.strategist_state = "eligible"
    run = await get_run(session, workspace_id=workspace_id, run_id=opportunity.research_run_id)
    if run is not None:
        await _emit_run(
            session,
            run,
            "opportunity.strategist_eligible",
            {"opportunity_id": str(opportunity.id)},
        )
    return {
        "opportunity_id": opportunity.id,
        "eligible": True,
        "state": "eligible",
        "detail": (
            "Approved intelligence is eligible for a future Strategist handoff; "
            "no Strategist provider is configured."
        ),
    }

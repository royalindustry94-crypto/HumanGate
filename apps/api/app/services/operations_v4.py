"""Integrated Mission Control V4 projections."""

from __future__ import annotations

import re
import uuid
from datetime import UTC, datetime

from sqlalchemy import Text, cast, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.automation import AutomationHealthSnapshot
from app.models.assignments import StageAssignment
from app.models.billing import BillingWebhookEvent
from app.models.content import ContentItem
from app.models.delivery import Asset
from app.models.enums import (
    AssetStatus,
    AssetType,
    PipelineRunStatus,
    ReviewGateStatus,
    StageAssignmentStatus,
    WorkerStatus,
)
from app.models.leads import Lead
from app.models.pipeline import PipelineRun
from app.models.review_gate import ReviewGate
from app.models.worker_logs import WorkerLog
from app.models.workers import WorkerRegistration
from app.models.workspace import Workspace
from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole
from app.schemas.operations_mission import ActivityItem
from app.schemas.operations_v4 import (
    AssistantAnswerOut,
    ExecutiveModeOut,
    GlobalSearchOut,
    LiveLogsOut,
    SearchResult,
    UniversalTimelineOut,
    WorkerLogOut,
)
from app.services import github_status, operations_dashboard, operations_mission


def _value(value: object) -> str:
    return str(value.value if hasattr(value, "value") else value)


async def global_search(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    admin_user_id: uuid.UUID,
    query: str,
    limit: int = 50,
) -> GlobalSearchOut:
    now = datetime.now(UTC)
    q = query.strip()
    pattern = f"%{q}%"
    results: list[SearchResult] = []

    # Scoped to the current workspace only, matching every other entity
    # type searched below (leads, pipelines, workers, content, jobs,
    # reviews, videos, logs) and this endpoint's own URL contract
    # (/workspaces/{workspace_id}/operations/search). Previously matched
    # against every workspace the caller administers, so a search inside
    # workspace A's Mission Control could surface workspace B's name/id as
    # a "customer" hit (2026-09-08 audit finding, part of the same bug as
    # the executive-mode/insights revenue and most-active-customer fixes
    # above).
    customers = (
        (
            await session.execute(
                select(Workspace)
                .join(
                    WorkspaceMembership,
                    WorkspaceMembership.workspace_id == Workspace.id,
                )
                .where(
                    Workspace.id == workspace_id,
                    WorkspaceMembership.user_id == admin_user_id,
                    WorkspaceMembership.role == WorkspaceRole.ADMIN,
                    or_(
                        Workspace.name.ilike(pattern),
                        cast(Workspace.id, Text).ilike(pattern),
                    ),
                )
                .order_by(Workspace.updated_at.desc())
                .limit(10)
            )
        )
        .scalars()
        .all()
    )
    results.extend(
        SearchResult(
            type="customer",
            id=str(row.id),
            title=row.name,
            subtitle="Workspace customer",
            status=None,
            occurred_at=row.updated_at,
        )
        for row in customers
    )

    leads = (
        (
            await session.execute(
                select(Lead)
                .where(
                    Lead.workspace_id == workspace_id,
                    or_(
                        Lead.name.ilike(pattern),
                        Lead.company.ilike(pattern),
                        Lead.email.ilike(pattern),
                        Lead.notes.ilike(pattern),
                    ),
                )
                .order_by(Lead.updated_at.desc())
                .limit(10)
            )
        )
        .scalars()
        .all()
    )
    results.extend(
        SearchResult(
            type="lead",
            id=str(row.id),
            title=row.name,
            subtitle=f"{row.company or 'No company'} · {row.email}",
            status=row.status,
            occurred_at=row.updated_at,
        )
        for row in leads
    )

    pipelines = (
        await session.execute(
            select(PipelineRun, ContentItem.topic)
            .join(ContentItem, ContentItem.id == PipelineRun.content_item_id)
            .where(
                PipelineRun.workspace_id == workspace_id,
                or_(
                    ContentItem.topic.ilike(pattern),
                    cast(PipelineRun.id, Text).ilike(pattern),
                    cast(PipelineRun.status, Text).ilike(pattern),
                    cast(PipelineRun.current_stage, Text).ilike(pattern),
                ),
            )
            .order_by(PipelineRun.updated_at.desc())
            .limit(10)
        )
    ).all()
    results.extend(
        SearchResult(
            type="pipeline",
            id=str(run.id),
            title=topic,
            subtitle=f"Stage {_value(run.current_stage)}",
            status=_value(run.status),
            occurred_at=run.updated_at,
        )
        for run, topic in pipelines
    )

    workers = (
        (
            await session.execute(
                select(WorkerRegistration)
                .where(
                    WorkerRegistration.deregistered_at.is_(None),
                    or_(
                        WorkerRegistration.workspace_id == workspace_id,
                        WorkerRegistration.workspace_id.is_(None),
                    ),
                    or_(
                        WorkerRegistration.name.ilike(pattern),
                        cast(WorkerRegistration.id, Text).ilike(pattern),
                        cast(WorkerRegistration.status, Text).ilike(pattern),
                    ),
                )
                .order_by(WorkerRegistration.name)
                .limit(10)
            )
        )
        .scalars()
        .all()
    )
    results.extend(
        SearchResult(
            type="worker",
            id=str(row.id),
            title=row.name,
            subtitle=f"Load {row.current_load}/{row.max_concurrency}",
            status=_value(row.status),
            occurred_at=row.last_heartbeat_at,
        )
        for row in workers
    )

    jobs = (
        (
            await session.execute(
                select(StageAssignment)
                .where(
                    StageAssignment.workspace_id == workspace_id,
                    or_(
                        cast(StageAssignment.id, Text).ilike(pattern),
                        cast(StageAssignment.pipeline_run_id, Text).ilike(pattern),
                        cast(StageAssignment.stage, Text).ilike(pattern),
                        cast(StageAssignment.status, Text).ilike(pattern),
                        StageAssignment.provider.ilike(pattern),
                    ),
                )
                .order_by(StageAssignment.updated_at.desc())
                .limit(10)
            )
        )
        .scalars()
        .all()
    )
    results.extend(
        SearchResult(
            type="job",
            id=str(row.id),
            title=f"{_value(row.stage)} job",
            subtitle=f"Pipeline {row.pipeline_run_id}",
            status=_value(row.status),
            occurred_at=row.updated_at,
        )
        for row in jobs
    )

    content = (
        (
            await session.execute(
                select(ContentItem)
                .where(
                    ContentItem.workspace_id == workspace_id,
                    ContentItem.deleted_at.is_(None),
                    or_(
                        ContentItem.topic.ilike(pattern),
                        cast(ContentItem.id, Text).ilike(pattern),
                        cast(ContentItem.current_stage, Text).ilike(pattern),
                        cast(ContentItem.status, Text).ilike(pattern),
                    ),
                )
                .order_by(ContentItem.updated_at.desc())
                .limit(10)
            )
        )
        .scalars()
        .all()
    )
    results.extend(
        SearchResult(
            type="content",
            id=str(row.id),
            title=row.topic,
            subtitle=f"Stage {_value(row.current_stage)}",
            status=_value(row.status),
            occurred_at=row.updated_at,
        )
        for row in content
    )

    reviews = (
        await session.execute(
            select(ReviewGate, ContentItem.topic)
            .join(PipelineRun, PipelineRun.id == ReviewGate.pipeline_run_id)
            .join(ContentItem, ContentItem.id == PipelineRun.content_item_id)
            .where(
                ReviewGate.workspace_id == workspace_id,
                or_(
                    ContentItem.topic.ilike(pattern),
                    cast(ReviewGate.id, Text).ilike(pattern),
                    cast(ReviewGate.status, Text).ilike(pattern),
                ),
            )
            .order_by(ReviewGate.updated_at.desc())
            .limit(10)
        )
    ).all()
    results.extend(
        SearchResult(
            type="review",
            id=str(gate.id),
            title=topic,
            subtitle=f"Review gate · {_value(gate.stage)}",
            status=_value(gate.status),
            occurred_at=gate.updated_at,
        )
        for gate, topic in reviews
    )

    videos = (
        await session.execute(
            select(Asset, ContentItem.topic)
            .join(ContentItem, ContentItem.id == Asset.content_item_id)
            .where(
                Asset.workspace_id == workspace_id,
                Asset.deleted_at.is_(None),
                Asset.type.in_([AssetType.VISUAL, AssetType.RENDER]),
                or_(
                    ContentItem.topic.ilike(pattern),
                    cast(Asset.id, Text).ilike(pattern),
                    cast(Asset.status, Text).ilike(pattern),
                    Asset.storage_object_key.ilike(pattern),
                ),
            )
            .order_by(Asset.updated_at.desc())
            .limit(10)
        )
    ).all()
    results.extend(
        SearchResult(
            type="video",
            id=str(asset.id),
            title=topic,
            subtitle=f"{_value(asset.type)} asset",
            status=_value(asset.status),
            occurred_at=asset.updated_at,
            url=asset.url,
        )
        for asset, topic in videos
    )

    logs = (
        await session.execute(
            select(WorkerLog, WorkerRegistration.name)
            .join(WorkerRegistration, WorkerRegistration.id == WorkerLog.worker_id)
            .where(
                WorkerLog.workspace_id == workspace_id,
                or_(
                    WorkerLog.message.ilike(pattern),
                    cast(WorkerLog.id, Text).ilike(pattern),
                    cast(WorkerLog.pipeline_run_id, Text).ilike(pattern),
                    cast(WorkerLog.assignment_id, Text).ilike(pattern),
                    WorkerRegistration.name.ilike(pattern),
                    WorkerLog.severity.ilike(pattern),
                ),
            )
            .order_by(WorkerLog.occurred_at.desc())
            .limit(10)
        )
    ).all()
    results.extend(
        SearchResult(
            type="log",
            id=str(log.id),
            title=log.message,
            subtitle=f"{worker_name} · {log.severity}",
            status=log.severity,
            occurred_at=log.occurred_at,
        )
        for log, worker_name in logs
    )

    github = await github_status.github_status()
    if github.available:
        lowered = q.lower()
        for commit in github.latest_commits:
            if lowered in commit.message.lower() or lowered in commit.sha.lower():
                results.append(
                    SearchResult(
                        type="github",
                        id=commit.sha,
                        title=commit.message,
                        subtitle=f"Commit · {commit.author or 'unknown'}",
                        status="commit",
                        occurred_at=commit.committed_at,
                        url=commit.url,
                    )
                )
        for pr in [
            *github.open_pull_requests,
            *github.recently_merged_pull_requests,
        ]:
            if lowered in pr.title.lower() or lowered in str(pr.number):
                results.append(
                    SearchResult(
                        type="github",
                        id=str(pr.number),
                        title=f"#{pr.number} {pr.title}",
                        subtitle=f"Pull request · {pr.author or 'unknown'}",
                        status=pr.state,
                        occurred_at=pr.merged_at or pr.updated_at,
                        url=pr.url,
                    )
                )

    results.sort(
        key=lambda row: row.occurred_at or datetime.min.replace(tzinfo=UTC),
        reverse=True,
    )
    return GlobalSearchOut(
        query=q,
        results=results[:limit],
        total=len(results),
        generated_at=now,
    )


async def live_logs(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    worker_id: uuid.UUID | None = None,
    pipeline_id: uuid.UUID | None = None,
    job_id: uuid.UUID | None = None,
    severity: str | None = None,
    limit: int = 200,
) -> LiveLogsOut:
    stmt = (
        select(WorkerLog, WorkerRegistration.name)
        .join(WorkerRegistration, WorkerRegistration.id == WorkerLog.worker_id)
        .where(WorkerLog.workspace_id == workspace_id)
    )
    if worker_id:
        stmt = stmt.where(WorkerLog.worker_id == worker_id)
    if pipeline_id:
        stmt = stmt.where(WorkerLog.pipeline_run_id == pipeline_id)
    if job_id:
        stmt = stmt.where(WorkerLog.assignment_id == job_id)
    if severity:
        stmt = stmt.where(WorkerLog.severity == severity)
    rows = (await session.execute(stmt.order_by(WorkerLog.occurred_at.desc()).limit(limit))).all()
    return LiveLogsOut(
        logs=[
            WorkerLogOut(
                id=log.id,
                workspace_id=log.workspace_id,
                worker_id=log.worker_id,
                worker_name=name,
                pipeline_run_id=log.pipeline_run_id,
                assignment_id=log.assignment_id,
                severity=log.severity,
                message=log.message,
                context=log.context,
                occurred_at=log.occurred_at,
                received_at=log.received_at,
            )
            for log, name in rows
        ],
        generated_at=datetime.now(UTC),
    )


async def universal_timeline(
    session: AsyncSession, workspace_id: uuid.UUID, *, limit: int = 100
) -> UniversalTimelineOut:
    now = datetime.now(UTC)
    base = await operations_mission.activity_feed(session, workspace_id, limit=limit)
    items = list(base.items)

    logs = await live_logs(session, workspace_id, limit=min(limit, 50))
    for log in logs.logs:
        severity = "critical" if log.severity in {"error", "critical"} else "info"
        items.append(
            ActivityItem(
                id=f"worker-log:{log.id}",
                kind="worker.log",
                title=f"{log.worker_name} · {log.severity}",
                detail=log.message,
                severity=severity,
                occurred_at=log.occurred_at,
                source="worker_logs",
            )
        )

    assets = (
        await session.execute(
            select(Asset, ContentItem.topic)
            .join(ContentItem, ContentItem.id == Asset.content_item_id)
            .where(
                Asset.workspace_id == workspace_id,
                Asset.deleted_at.is_(None),
            )
            .order_by(Asset.updated_at.desc())
            .limit(50)
        )
    ).all()
    labels = {
        AssetType.SCRIPT: "Script generated",
        AssetType.AUDIO: "Voice created",
        AssetType.VISUAL: "Visual created",
        AssetType.RENDER: "Video rendering",
    }
    for asset, topic in assets:
        title = labels.get(asset.type, "Asset updated")
        if asset.type == AssetType.RENDER and asset.status == AssetStatus.READY:
            title = "Video rendered"
        items.append(
            ActivityItem(
                id=f"asset:{asset.id}",
                kind=f"asset.{_value(asset.type)}.{_value(asset.status)}",
                title=title,
                detail=topic,
                severity=("critical" if asset.status == AssetStatus.FAILED else "info"),
                occurred_at=asset.updated_at,
                source="assets",
            )
        )

    upgrades = (
        (
            await session.execute(
                select(BillingWebhookEvent)
                .where(
                    BillingWebhookEvent.workspace_id == workspace_id,
                    BillingWebhookEvent.event_type == "customer.subscription.updated",
                )
                .order_by(BillingWebhookEvent.processed_at.desc())
                .limit(20)
            )
        )
        .scalars()
        .all()
    )
    for event in upgrades:
        items.append(
            ActivityItem(
                id=f"billing-upgrade:{event.id}",
                kind="customer.upgraded",
                title="Customer upgraded",
                detail=event.stripe_event_id,
                severity="info",
                occurred_at=event.processed_at,
                source="billing_webhook_events",
            )
        )

    items.sort(key=lambda item: item.occurred_at, reverse=True)
    return UniversalTimelineOut(items=items[:limit], generated_at=now)


async def executive_mode(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    admin_user_id: uuid.UUID,
    automation: AutomationHealthSnapshot | None,
) -> ExecutiveModeOut:
    now = datetime.now(UTC)
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    health = await operations_mission.system_health(session, workspace_id, automation=automation)
    customers = await operations_dashboard.customers(
        session, admin_user_id=admin_user_id, workspace_id=workspace_id
    )
    spend = await operations_dashboard.spend(session, workspace_id)
    workers = await operations_dashboard.workers(session, workspace_id)
    pipelines = await operations_dashboard.pipelines(session, workspace_id)
    executive = await operations_dashboard.executive(session, workspace_id)
    alerts = await operations_dashboard.notifications(session, workspace_id)
    insights = await operations_mission.executive_insights(
        session, workspace_id, admin_user_id=admin_user_id
    )
    failed_today = await session.execute(
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.FAILED,
            StageAssignment.updated_at >= day_start,
        )
    )
    new_customers = await session.execute(
        select(func.count(WorkspaceMembership.id)).where(
            WorkspaceMembership.workspace_id == workspace_id,
            WorkspaceMembership.created_at >= day_start,
        )
    )
    return ExecutiveModeOut(
        health=health.indicators,
        revenue_mtd_usd=customers.revenue_mtd_usd,
        spend_today_usd=spend.today_usd,
        spend_month_usd=spend.month_usd,
        workers_online=sum(
            1
            for worker in workers.workers
            if worker.status
            in {
                WorkerStatus.ONLINE.value,
                WorkerStatus.BUSY.value,
            }
        ),
        workers_total=len(workers.workers),
        jobs_running=executive.jobs_running,
        jobs_waiting=pipelines.jobs_waiting,
        jobs_failed_today=int(failed_today.scalar_one() or 0),
        critical_alerts=sum(
            alert.count for alert in alerts.notifications if alert.severity == "critical"
        ),
        reviews_waiting=pipelines.human_reviews_waiting,
        new_customers_today=int(new_customers.scalar_one() or 0),
        todays_summary=[
            *insights.todays_achievements,
            *insights.todays_failures,
            f"Highest risk: {insights.highest_risk}",
        ],
        generated_at=now,
    )


async def assistant_answer(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    admin_user_id: uuid.UUID,
    question: str,
) -> AssistantAnswerOut:
    now = datetime.now(UTC)
    q = question.strip()
    lowered = q.lower()
    facts: list[dict] = []

    if "spend" in lowered or "cost" in lowered:
        spend = await operations_dashboard.spend(session, workspace_id)
        intent = "spend"
        facts = [
            {"label": "today_usd", "value": str(spend.today_usd)},
            {"label": "month_usd", "value": str(spend.month_usd)},
            {
                "label": "monthly_budget_remaining_usd",
                "value": (
                    str(spend.budget_remaining_monthly_usd)
                    if spend.budget_remaining_monthly_usd is not None
                    else None
                ),
            },
        ]
        answer = (
            f"Today’s committed AI spend is ${spend.today_usd}; "
            f"month-to-date is ${spend.month_usd}."
        )
    elif "review" in lowered or "blocked" in lowered:
        review_rows = (
            await session.execute(
                select(ReviewGate, ContentItem.topic)
                .join(PipelineRun, PipelineRun.id == ReviewGate.pipeline_run_id)
                .join(ContentItem, ContentItem.id == PipelineRun.content_item_id)
                .where(
                    ReviewGate.workspace_id == workspace_id,
                    ReviewGate.status == ReviewGateStatus.AWAITING,
                )
                .order_by(ReviewGate.requested_at)
            )
        ).all()
        intent = "blocked_reviews"
        facts = [
            {
                "review_id": str(gate.id),
                "topic": topic,
                "requested_at": gate.requested_at.isoformat(),
            }
            for gate, topic in review_rows
        ]
        answer = f"{len(review_rows)} review gate(s) are waiting for a human decision."
    elif "failed pipeline" in lowered or ("pipeline" in lowered and "fail" in lowered):
        pipeline_rows = (
            await session.execute(
                select(PipelineRun, ContentItem.topic)
                .join(ContentItem, ContentItem.id == PipelineRun.content_item_id)
                .where(
                    PipelineRun.workspace_id == workspace_id,
                    PipelineRun.status == PipelineRunStatus.FAILED,
                )
                .order_by(PipelineRun.updated_at.desc())
                .limit(20)
            )
        ).all()
        intent = "failed_pipelines"
        facts = [
            {
                "pipeline_id": str(run.id),
                "topic": topic,
                "stage": _value(run.current_stage),
                "updated_at": run.updated_at.isoformat(),
            }
            for run, topic in pipeline_rows
        ]
        answer = f"{len(pipeline_rows)} failed pipeline(s) are currently recorded."
    elif "idle" in lowered and "worker" in lowered:
        timelines = await operations_mission.worker_timeline(session, workspace_id)
        token = re.search(r"worker\s+([a-zA-Z0-9_-]+)", lowered, flags=re.IGNORECASE)
        needle = token.group(1) if token else ""
        intent = "worker_idle"
        if needle:
            # A specific worker was named — answer about that one worker.
            worker = next(
                (
                    row
                    for row in timelines.workers
                    if needle in row.name.lower() or str(row.worker_id).lower().startswith(needle)
                ),
                None,
            )
            if worker is None:
                answer = f"No worker matching “{needle}” is visible in this workspace."
            else:
                reason = (
                    "it has no active assignment"
                    if worker.current_task is None
                    else f"it is currently assigned to {worker.current_task}"
                )
                answer = (
                    f"{worker.name} reports {worker.status}; {reason}. "
                    f"Last heartbeat: {worker.last_heartbeat_at or 'never'}."
                )
                facts = [
                    {
                        "worker_id": str(worker.worker_id),
                        "status": worker.status,
                        "current_task": worker.current_task,
                        "last_heartbeat_at": (
                            worker.last_heartbeat_at.isoformat()
                            if worker.last_heartbeat_at
                            else None
                        ),
                    }
                ]
        else:
            # No specific worker named ("are any workers idle?") — an empty
            # needle used to substring-match every worker's name, silently
            # answering about whichever worker sorted first rather than
            # actually identifying idle ones. Report the real idle set.
            idle = [row for row in timelines.workers if row.current_task is None]
            if not idle:
                answer = (
                    "No idle workers right now — every registered worker has an active assignment."
                )
            else:
                shown = [row.name for row in idle[:10]]
                names = ", ".join(shown)
                remainder = len(idle) - len(shown)
                if remainder > 0:
                    names += f", and {remainder} more"
                answer = f"{len(idle)} worker(s) are idle: {names}."
            facts = [
                {
                    "worker_id": str(row.worker_id),
                    "status": row.status,
                    "current_task": row.current_task,
                    "last_heartbeat_at": (
                        row.last_heartbeat_at.isoformat() if row.last_heartbeat_at else None
                    ),
                }
                for row in idle
            ]
    elif "fail" in lowered:
        day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        assignments = int(
            (
                await session.execute(
                    select(func.count(StageAssignment.id)).where(
                        StageAssignment.workspace_id == workspace_id,
                        StageAssignment.status == StageAssignmentStatus.FAILED,
                        StageAssignment.updated_at >= day_start,
                    )
                )
            ).scalar_one()
            or 0
        )
        pipelines = int(
            (
                await session.execute(
                    select(func.count(PipelineRun.id)).where(
                        PipelineRun.workspace_id == workspace_id,
                        PipelineRun.status == PipelineRunStatus.FAILED,
                        PipelineRun.updated_at >= day_start,
                    )
                )
            ).scalar_one()
            or 0
        )
        error_logs = await live_logs(session, workspace_id, severity="error", limit=10)
        intent = "failures_today"
        facts = [
            {"label": "failed_jobs", "value": assignments},
            {"label": "failed_pipelines", "value": pipelines},
            {"label": "error_logs", "value": len(error_logs.logs)},
        ]
        answer = (
            f"Today: {assignments} failed job(s), {pipelines} failed "
            f"pipeline(s), and {len(error_logs.logs)} recent error log(s)."
        )
    else:
        executive = await operations_mission.executive_insights(
            session, workspace_id, admin_user_id=admin_user_id
        )
        intent = "executive_summary"
        facts = [
            {"label": "highest_risk", "value": executive.highest_risk},
            {
                "label": "suggested_next_action",
                "value": executive.suggested_next_action,
            },
        ]
        answer = (
            f"Highest risk: {executive.highest_risk}. "
            f"Suggested next action: {executive.suggested_next_action}."
        )

    return AssistantAnswerOut(
        question=q,
        intent=intent,
        answer=answer,
        facts=facts,
        generated_at=now,
    )

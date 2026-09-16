"""Operations Dashboard V3 — Founder Mission Control projections and actions.

All values come from durable tables, process health state, or live upstream
APIs when configured. Missing sources are reported as unavailable / amber /
red — never fabricated.
"""

from __future__ import annotations

import calendar
import logging
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import cast

from sqlalchemy import CursorResult, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.automation import AutomationHealthSnapshot
from app.core.config import get_settings
from app.models.assignments import StageAssignment
from app.models.billing import BillingWebhookEvent
from app.models.config import SpendCap
from app.models.content import ContentItem
from app.models.delivery import PublishJob
from app.models.enums import (
    ContentStage,
    ContentStatus,
    DeadLetterStatus,
    JobScheduleStatus,
    JobType,
    PipelineRunStatus,
    PublishJobStatus,
    RecoveryReason,
    ReviewGateStatus,
    StageAssignmentStatus,
    WebhookStatus,
    WorkerCredentialStatus,
    WorkerStatus,
)
from app.models.events import OutboxEvent
from app.models.leads import Lead
from app.models.operations import DeadLetterJob, WebhookEvent
from app.models.pipeline import PipelineRun, PipelineStageRun
from app.models.review_gate import ReviewGate
from app.models.scheduling import JobSchedule, WorkspaceConcurrencyLimit
from app.models.spend import SpendLog
from app.models.workers import WorkerCredential, WorkerRegistration
from app.models.workspace_membership import WorkspaceMembership
from app.orchestration.events import types as event_types
from app.orchestration.outbox import emit
from app.orchestration.recovery import reap_worker_assignments
from app.schemas.operations_mission import (
    ActivityFeedOut,
    ActivityItem,
    ContentCommandCenterOut,
    CostControlOut,
    CostProviderRow,
    ExecutiveInsightsOut,
    ExpensiveJobRow,
    HealthIndicator,
    QuickActionResult,
    SystemHealthOut,
    WorkerJobRow,
    WorkerTimelineOut,
    WorkerTimelineRow,
)
from app.services import github_status, operations_dashboard

# Shared with operations_dashboard rather than redefined here: both modules
# previously carried their own copies, which could drift silently and report
# inconsistent dashboard numbers (audit M-6).
from app.services.operations_dashboard import count_rows, enum_value
from app.services.workers import compute_liveness

logger = logging.getLogger(__name__)

_EVENT_LABELS = {
    event_types.STAGE_ASSIGNED: ("Worker started job", "info"),
    event_types.STAGE_COMPLETED: ("Worker completed job", "info"),
    event_types.STAGE_FAILED: ("Job failed", "critical"),
    event_types.REVIEW_REQUESTED: ("Human review requested", "warning"),
    event_types.PUBLISH_COMPLETED: ("Publish completed", "info"),
    event_types.PIPELINE_FAILED: ("Pipeline failed", "critical"),
    event_types.PIPELINE_SUCCEEDED: ("Pipeline succeeded", "info"),
    "operations.action.executed": ("Mission Control action executed", "warning"),
}


def _day_bounds(now: datetime) -> tuple[datetime, datetime, datetime]:
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    month_start = day_start.replace(day=1)
    return day_start, month_start, now


async def activity_feed(
    session: AsyncSession, workspace_id: uuid.UUID, *, limit: int = 75
) -> ActivityFeedOut:
    now = datetime.now(UTC)
    items: list[ActivityItem] = []

    events = (
        (
            await session.execute(
                select(OutboxEvent)
                .where(OutboxEvent.workspace_id == workspace_id)
                .order_by(OutboxEvent.occurred_at.desc())
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )
    for event in events:
        label, severity = _EVENT_LABELS.get(
            event.event_type, (event.event_type.replace(".", " ").title(), "info")
        )
        payload = event.payload or {}
        detail = None
        if isinstance(payload, dict):
            detail = (
                payload.get("stage")
                or payload.get("topic")
                or payload.get("failure_reason")
                or str(event.aggregate_id)
            )
            if detail is not None:
                detail = str(detail)
        items.append(
            ActivityItem(
                id=f"outbox:{event.event_id}",
                kind=event.event_type,
                title=label,
                detail=detail,
                severity=severity,
                occurred_at=event.occurred_at,
                source="outbox_events",
            )
        )

    for lead in (
        (
            await session.execute(
                select(Lead)
                .where(Lead.workspace_id == workspace_id)
                .order_by(Lead.created_at.desc())
                .limit(20)
            )
        )
        .scalars()
        .all()
    ):
        items.append(
            ActivityItem(
                id=f"lead:{lead.id}",
                kind="lead.created",
                title="New lead",
                detail=f"{lead.name} · {lead.email}",
                severity="info",
                occurred_at=lead.created_at,
                source="leads",
            )
        )

    for membership in (
        (
            await session.execute(
                select(WorkspaceMembership)
                .where(WorkspaceMembership.workspace_id == workspace_id)
                .order_by(WorkspaceMembership.created_at.desc())
                .limit(20)
            )
        )
        .scalars()
        .all()
    ):
        items.append(
            ActivityItem(
                id=f"signup:{membership.id}",
                kind="customer.signup",
                title="Customer signup",
                detail=f"member {membership.user_id} · role {enum_value(membership.role)}",
                severity="info",
                occurred_at=membership.created_at,
                source="workspace_memberships",
            )
        )

    for payment in (
        (
            await session.execute(
                select(BillingWebhookEvent)
                .where(
                    BillingWebhookEvent.workspace_id == workspace_id,
                    BillingWebhookEvent.event_type.in_(
                        ["invoice.paid", "invoice.payment_succeeded"]
                    ),
                )
                .order_by(BillingWebhookEvent.processed_at.desc())
                .limit(20)
            )
        )
        .scalars()
        .all()
    ):
        amount = operations_dashboard.revenue_from_payload(payment.payload or {})
        items.append(
            ActivityItem(
                id=f"payment:{payment.id}",
                kind="payment.received",
                title="Payment received",
                detail=f"${amount} · {payment.event_type}",
                severity="info",
                occurred_at=payment.processed_at,
                source="billing_webhook_events",
            )
        )

    alerts = await operations_dashboard.build_alerts(session, workspace_id)
    for alert in alerts:
        items.append(
            ActivityItem(
                id=f"alert:{alert.key}",
                kind="alert.created",
                title="Alert created",
                detail=f"{alert.title}: {alert.message}",
                severity=alert.severity,
                occurred_at=alert.occurred_at or now,
                source="operations_alerts",
            )
        )

    github = await github_status.github_status()
    if github.available:
        for pr in github.recently_merged_pull_requests:
            if pr.merged_at is None:
                continue
            items.append(
                ActivityItem(
                    id=f"github-merge:{pr.number}",
                    kind="github.merge",
                    title="GitHub merge",
                    detail=f"#{pr.number} {pr.title}",
                    severity="info",
                    occurred_at=pr.merged_at,
                    source="github_api",
                )
            )

    items.sort(key=lambda item: item.occurred_at, reverse=True)
    return ActivityFeedOut(items=items[:limit], generated_at=now)


async def system_health(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    automation: AutomationHealthSnapshot | None = None,
) -> SystemHealthOut:
    now = datetime.now(UTC)
    settings = get_settings()
    indicators: list[HealthIndicator] = []

    # API health: this request is serving → green
    indicators.append(
        HealthIndicator(
            key="api",
            label="API Health",
            status="green",
            detail="Operations API responding",
        )
    )

    try:
        await session.execute(select(1))
        indicators.append(
            HealthIndicator(
                key="database",
                label="Database Health",
                status="green",
                detail="SELECT 1 succeeded",
            )
        )
    except Exception as exc:
        logger.exception("mission_control_db_health_failed")
        indicators.append(
            HealthIndicator(
                key="database",
                label="Database Health",
                status="red",
                detail=f"Database unreachable: {exc}",
            )
        )

    workers = (
        (
            await session.execute(
                select(WorkerRegistration).where(
                    WorkerRegistration.deregistered_at.is_(None),
                    or_(
                        WorkerRegistration.workspace_id == workspace_id,
                        WorkerRegistration.workspace_id.is_(None),
                    ),
                )
            )
        )
        .scalars()
        .all()
    )
    if not workers:
        indicators.append(
            HealthIndicator(
                key="workers",
                label="Worker Health",
                status="amber",
                detail="No workers registered",
            )
        )
    else:
        offline = sum(
            1
            for worker in workers
            if compute_liveness(
                worker.last_heartbeat_at,
                suspect_after_seconds=settings.worker_suspect_after_seconds,
                offline_after_seconds=settings.worker_offline_after_seconds,
            )
            == "dead"
        )
        ratio = offline / len(workers)
        status = "green" if ratio == 0 else ("amber" if ratio < 0.5 else "red")
        indicators.append(
            HealthIndicator(
                key="workers",
                label="Worker Health",
                status=status,
                detail=f"{len(workers) - offline}/{len(workers)} workers live",
            )
        )

    queue = await count_rows(
        session,
        select(func.count(JobSchedule.id)).where(
            JobSchedule.workspace_id == workspace_id,
            JobSchedule.status == JobScheduleStatus.PENDING,
        ),
    )
    soft = (
        await session.execute(
            select(WorkspaceConcurrencyLimit.queue_soft_limit).where(
                WorkspaceConcurrencyLimit.workspace_id == workspace_id
            )
        )
    ).scalar_one_or_none() or settings.queue_soft_limit_default
    hard = (
        await session.execute(
            select(WorkspaceConcurrencyLimit.queue_hard_limit).where(
                WorkspaceConcurrencyLimit.workspace_id == workspace_id
            )
        )
    ).scalar_one_or_none() or settings.queue_hard_limit_default
    if queue >= hard:
        q_status, q_detail = "red", f"Queue depth {queue} at/above hard limit {hard}"
    elif queue >= soft:
        q_status, q_detail = "amber", f"Queue depth {queue} at/above soft limit {soft}"
    else:
        q_status, q_detail = "green", f"Queue depth {queue} (soft {soft})"
    indicators.append(
        HealthIndicator(key="queue", label="Queue Health", status=q_status, detail=q_detail)
    )

    deploy = operations_dashboard.deployment_info()
    github = await github_status.github_status()
    if deploy.ci_status in {"success", "passing", "green"}:
        gh_status, gh_detail = "green", f"Deploy CI {deploy.ci_status}"
    elif deploy.ci_status == "unavailable" and not github.available:
        gh_status, gh_detail = "amber", "GitHub Actions metadata unavailable"
    elif github.available and github.failed_actions:
        gh_status, gh_detail = "red", f"{len(github.failed_actions)} failed Actions run(s)"
    elif deploy.ci_status not in {"unavailable", "success", "passing", "green"}:
        gh_status, gh_detail = "red", f"Deploy CI {deploy.ci_status}"
    else:
        gh_status, gh_detail = "green", "No failed Actions detected"
    indicators.append(
        HealthIndicator(
            key="github_actions",
            label="GitHub Actions",
            status=gh_status,
            detail=gh_detail,
        )
    )

    failed_webhooks = await count_rows(
        session,
        select(func.count(WebhookEvent.id)).where(
            WebhookEvent.workspace_id == workspace_id,
            WebhookEvent.status == WebhookStatus.FAILED,
            WebhookEvent.updated_at >= now - timedelta(days=1),
        ),
    )
    failed_webhooks += await count_rows(
        session,
        select(func.count(BillingWebhookEvent.id)).where(
            BillingWebhookEvent.workspace_id == workspace_id,
            BillingWebhookEvent.event_type == "invoice.payment_failed",
            BillingWebhookEvent.processed_at >= now - timedelta(days=1),
        ),
    )
    wh_status = "green" if failed_webhooks == 0 else ("amber" if failed_webhooks < 3 else "red")
    indicators.append(
        HealthIndicator(
            key="webhooks",
            label="Webhook Health",
            status=wh_status,
            detail=f"{failed_webhooks} failure(s) in last 24h",
        )
    )

    auto: AutomationHealthSnapshot = automation or cast(
        AutomationHealthSnapshot,
        {
            "status": "idle",
            "started_at": None,
            "tasks_running": [],
            "maintenance": {
                "status": "idle",
                "owner_id": None,
                "owner_started_at": None,
                "lease_expires_at": None,
                "last_heartbeat_at": None,
                "ticks": 0,
                "last_ok_at": None,
                "last_error": None,
                "active": False,
            },
            "outbox_relay": {
                "status": "idle",
                "owner_id": None,
                "owner_started_at": None,
                "lease_expires_at": None,
                "last_heartbeat_at": None,
                "ticks": 0,
                "last_ok_at": None,
                "last_error": None,
                "active": False,
            },
            "scheduler": {
                "status": "idle",
                "owner_id": None,
                "owner_started_at": None,
                "lease_expires_at": None,
                "last_heartbeat_at": None,
                "ticks": 0,
                "last_ok_at": None,
                "last_error": None,
                "active": False,
                "jobs_leased": 0,
            },
        },
    )
    scheduler = auto.get("scheduler") or {}
    if scheduler.get("last_error"):
        sch_status, sch_detail = "red", str(scheduler["last_error"])
    elif scheduler.get("status") == "running" and scheduler.get("active"):
        sch_status, sch_detail = "green", f"Scheduler ticks={scheduler.get('ticks', 0)}"
    elif scheduler.get("status") == "stale":
        sch_status, sch_detail = "red", "Scheduler lease stale"
    elif settings.environment == "test":
        sch_status, sch_detail = "amber", "Scheduler idle in test environment"
    elif auto.get("tasks_running") and "scheduler" in auto.get("tasks_running", []):
        sch_status, sch_detail = "amber", "Scheduler transitioning"
    else:
        sch_status, sch_detail = "amber", "Scheduler state unavailable"
    indicators.append(
        HealthIndicator(
            key="scheduler",
            label="Scheduler Health",
            status=sch_status,
            detail=sch_detail,
        )
    )

    return SystemHealthOut(indicators=indicators, generated_at=now)


async def cost_control(session: AsyncSession, workspace_id: uuid.UUID) -> CostControlOut:
    now = datetime.now(UTC)
    day_start, month_start, _ = _day_bounds(now)
    spend = await operations_dashboard.spend(session, workspace_id)
    days_in_month = calendar.monthrange(now.year, now.month)[1]
    day_of_month = max(now.day, 1)
    projected = (spend.month_usd / Decimal(day_of_month)) * Decimal(days_in_month)
    projected = projected.quantize(Decimal("0.0001"))

    top: list[ExpensiveJobRow] = []
    expensive_rows = (
        await session.execute(
            select(
                PipelineStageRun.pipeline_run_id,
                PipelineStageRun.content_item_id,
                ContentItem.topic,
                PipelineStageRun.stage,
                PipelineStageRun.provider,
                PipelineStageRun.cost_usd,
                PipelineStageRun.completed_at,
            )
            .join(ContentItem, ContentItem.id == PipelineStageRun.content_item_id)
            .where(
                PipelineStageRun.workspace_id == workspace_id,
                PipelineStageRun.cost_usd.is_not(None),
                PipelineStageRun.cost_usd > 0,
            )
            .order_by(PipelineStageRun.cost_usd.desc())
            .limit(10)
        )
    ).all()
    if expensive_rows:
        top = [
            ExpensiveJobRow(
                pipeline_run_id=row[0],
                content_item_id=row[1],
                topic=row[2],
                stage=enum_value(row[3]) if row[3] is not None else None,
                provider=row[4],
                cost_usd=Decimal(str(row[5] or 0)),
                completed_at=row[6],
            )
            for row in expensive_rows
        ]
    else:
        spend_rows = (
            await session.execute(
                select(
                    SpendLog.content_item_id,
                    ContentItem.topic,
                    SpendLog.stage,
                    SpendLog.provider,
                    func.sum(SpendLog.cost_usd),
                    func.max(SpendLog.occurred_at),
                )
                .outerjoin(ContentItem, ContentItem.id == SpendLog.content_item_id)
                .where(
                    SpendLog.workspace_id == workspace_id,
                    SpendLog.occurred_at >= month_start,
                )
                .group_by(
                    SpendLog.content_item_id,
                    ContentItem.topic,
                    SpendLog.stage,
                    SpendLog.provider,
                )
                .order_by(func.sum(SpendLog.cost_usd).desc())
                .limit(10)
            )
        ).all()
        top = [
            ExpensiveJobRow(
                pipeline_run_id=None,
                content_item_id=row[0],
                topic=row[1],
                stage=enum_value(row[2]) if row[2] is not None else None,
                provider=row[3],
                cost_usd=Decimal(str(row[4] or 0)),
                completed_at=row[5],
            )
            for row in spend_rows
        ]

    return CostControlOut(
        daily_ai_spend_usd=spend.today_usd,
        monthly_ai_spend_usd=spend.month_usd,
        budget_remaining_daily_usd=spend.budget_remaining_daily_usd,
        budget_remaining_monthly_usd=spend.budget_remaining_monthly_usd,
        by_provider=[
            CostProviderRow(
                provider=row.provider,
                today_usd=row.today_usd,
                month_usd=row.month_usd,
            )
            for row in spend.by_provider
        ],
        top_expensive_jobs=top,
        projected_month_end_usd=projected,
        generated_at=now,
    )


async def _recent_assignments_by_worker(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    worker_ids: list[uuid.UUID],
    *,
    per_worker_limit: int = 20,
) -> dict[uuid.UUID, list[StageAssignment]]:
    if not worker_ids:
        return {}
    ranked = (
        select(
            StageAssignment.id.label("id"),
            func.row_number()
            .over(
                partition_by=StageAssignment.worker_id,
                order_by=(StageAssignment.updated_at.desc(), StageAssignment.id.desc()),
            )
            .label("row_number"),
        )
        .where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.worker_id.in_(worker_ids),
        )
        .subquery()
    )
    assignments = (
        (
            await session.execute(
                select(StageAssignment)
                .join(ranked, StageAssignment.id == ranked.c.id)
                .where(ranked.c.row_number <= per_worker_limit)
                .order_by(StageAssignment.worker_id, StageAssignment.updated_at.desc())
            )
        )
        .scalars()
        .all()
    )
    grouped: dict[uuid.UUID, list[StageAssignment]] = {}
    for assignment in assignments:
        grouped.setdefault(assignment.worker_id, []).append(assignment)
    return grouped


async def worker_timeline(session: AsyncSession, workspace_id: uuid.UUID) -> WorkerTimelineOut:
    settings = get_settings()
    now = datetime.now(UTC)
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
                )
                .order_by(WorkerRegistration.name)
            )
        )
        .scalars()
        .all()
    )
    assignments_by_worker = await _recent_assignments_by_worker(
        session,
        workspace_id,
        [worker.id for worker in workers],
    )
    rows: list[WorkerTimelineRow] = []
    for worker in workers:
        assignments = assignments_by_worker.get(worker.id, [])
        durations: list[float] = []
        failed = 0
        retried = 0
        job_rows: list[WorkerJobRow] = []
        for assignment in assignments:
            duration = None
            if assignment.completed_at and assignment.dispatched_at:
                duration = (assignment.completed_at - assignment.dispatched_at).total_seconds()
                if duration >= 0:
                    durations.append(duration)
            if assignment.status == StageAssignmentStatus.FAILED:
                failed += 1
            if assignment.attempt_number > 1:
                retried += 1
            job_rows.append(
                WorkerJobRow(
                    assignment_id=assignment.id,
                    pipeline_run_id=assignment.pipeline_run_id,
                    stage=enum_value(assignment.stage),
                    status=enum_value(assignment.status),
                    attempt_number=assignment.attempt_number,
                    dispatched_at=assignment.dispatched_at,
                    completed_at=assignment.completed_at,
                    duration_seconds=duration,
                )
            )
        active = next(
            (
                a
                for a in assignments
                if a.status
                in {
                    StageAssignmentStatus.DISPATCHED,
                    StageAssignmentStatus.ACKNOWLEDGED,
                }
            ),
            None,
        )
        total = len(assignments) or 1
        liveness = compute_liveness(
            worker.last_heartbeat_at,
            suspect_after_seconds=settings.worker_suspect_after_seconds,
            offline_after_seconds=settings.worker_offline_after_seconds,
        )
        display = (
            WorkerStatus.OFFLINE.value
            if liveness == "dead"
            else ("suspect" if liveness == "suspect" else enum_value(worker.status))
        )
        rows.append(
            WorkerTimelineRow(
                worker_id=worker.id,
                name=worker.name,
                status=display,
                current_task=(
                    f"{enum_value(active.stage)} · {active.pipeline_run_id}" if active else None
                ),
                last_heartbeat_at=worker.last_heartbeat_at,
                average_execution_seconds=(
                    round(sum(durations) / len(durations), 3) if durations else None
                ),
                failure_percent=round(100.0 * failed / total, 2),
                retry_percent=round(100.0 * retried / total, 2),
                jobs=job_rows,
            )
        )
    return WorkerTimelineOut(workers=rows, generated_at=now)


async def content_command_center(
    session: AsyncSession, workspace_id: uuid.UUID
) -> ContentCommandCenterOut:
    now = datetime.now(UTC)

    async def stage_count(*stages: ContentStage) -> int:
        return await count_rows(
            session,
            select(func.count(ContentItem.id)).where(
                ContentItem.workspace_id == workspace_id,
                ContentItem.deleted_at.is_(None),
                ContentItem.current_stage.in_(stages),
                ContentItem.status == ContentStatus.ACTIVE,
            ),
        )

    ideas = await stage_count(ContentStage.IDEA)
    scripts = await stage_count(ContentStage.SCRIPTING)
    voiceovers = await stage_count(ContentStage.VOICEOVER)
    videos_rendering = await stage_count(ContentStage.VISUALS, ContentStage.RENDERING)
    ready_for_review = await stage_count(ContentStage.REVIEW, ContentStage.SEO)
    waiting = await count_rows(
        session,
        select(func.count(ReviewGate.id)).where(
            ReviewGate.workspace_id == workspace_id,
            ReviewGate.status == ReviewGateStatus.AWAITING,
        ),
    )
    publishing = await count_rows(
        session,
        select(func.count(PublishJob.id)).where(
            PublishJob.workspace_id == workspace_id,
            PublishJob.deleted_at.is_(None),
            PublishJob.status.in_([PublishJobStatus.PENDING, PublishJobStatus.PUBLISHING]),
        ),
    )
    published = await stage_count(ContentStage.PUBLISHED, ContentStage.SCHEDULED)
    published += await count_rows(
        session,
        select(func.count(PublishJob.id)).where(
            PublishJob.workspace_id == workspace_id,
            PublishJob.deleted_at.is_(None),
            PublishJob.status == PublishJobStatus.PUBLISHED,
        ),
    )
    failed = await count_rows(
        session,
        select(func.count(ContentItem.id)).where(
            ContentItem.workspace_id == workspace_id,
            ContentItem.deleted_at.is_(None),
            ContentItem.status == ContentStatus.FAILED,
        ),
    )
    failed += await count_rows(
        session,
        select(func.count(PipelineRun.id)).where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status == PipelineRunStatus.FAILED,
        ),
    )
    return ContentCommandCenterOut(
        ideas=ideas,
        scripts=scripts,
        voiceovers=voiceovers,
        videos_rendering=videos_rendering,
        ready_for_review=ready_for_review,
        waiting_for_approval=waiting,
        publishing=publishing,
        published=published,
        failed=failed,
        generated_at=now,
    )


async def _record_quick_action(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    action: str,
    affected: int,
    details: dict[str, int] | None = None,
) -> None:
    """Persist a mutable Mission Control action in the append-only outbox."""
    payload: dict[str, object] = {
        "action": action,
        "actor_id": str(actor_id),
        "affected": affected,
    }
    if details:
        payload.update(details)
    await emit(
        session,
        event_type="operations.action.executed",
        workspace_id=workspace_id,
        aggregate_type="workspace",
        aggregate_id=workspace_id,
        correlation_id=uuid.uuid4(),
        payload=payload,
        produced_by="mission_control",
    )


async def _workspace_workers(
    session: AsyncSession, workspace_id: uuid.UUID
) -> list[WorkerRegistration]:
    return list(
        (
            await session.execute(
                select(WorkerRegistration).where(
                    WorkerRegistration.deregistered_at.is_(None),
                    # Tenant quick actions may mutate only workers explicitly
                    # pinned to that tenant. Global workers are shared
                    # platform infrastructure and require service-operator
                    # control; including them here lets one workspace admin
                    # drain/revoke/reap work belonging to every tenant.
                    WorkerRegistration.workspace_id == workspace_id,
                )
            )
        )
        .scalars()
        .all()
    )


async def pause_workers(
    session: AsyncSession, workspace_id: uuid.UUID, *, actor_id: uuid.UUID
) -> QuickActionResult:
    workers = await _workspace_workers(session, workspace_id)
    for worker in workers:
        worker.drain = True
    await _record_quick_action(
        session,
        workspace_id=workspace_id,
        actor_id=actor_id,
        action="pause_workers",
        affected=len(workers),
    )
    await session.commit()
    return QuickActionResult(
        action="pause_workers",
        ok=True,
        affected=len(workers),
        message=f"Set drain=true on {len(workers)} worker(s)",
    )


async def resume_workers(
    session: AsyncSession, workspace_id: uuid.UUID, *, actor_id: uuid.UUID
) -> QuickActionResult:
    workers = await _workspace_workers(session, workspace_id)
    for worker in workers:
        worker.drain = False
    await _record_quick_action(
        session,
        workspace_id=workspace_id,
        actor_id=actor_id,
        action="resume_workers",
        affected=len(workers),
    )
    await session.commit()
    return QuickActionResult(
        action="resume_workers",
        ok=True,
        affected=len(workers),
        message=f"Cleared drain on {len(workers)} worker(s)",
    )


async def emergency_stop(
    session: AsyncSession, workspace_id: uuid.UUID, *, actor_id: uuid.UUID
) -> QuickActionResult:
    workers = await _workspace_workers(session, workspace_id)
    revoked = 0
    for worker in workers:
        await session.get(WorkerRegistration, worker.id, with_for_update=True)
        # Bulk UPDATE rather than SELECT-then-mutate: this route runs over
        # the admin's RLS-scoped session, which is deliberately granted
        # UPDATE on `status` only (never SELECT on `secret_hash`) — an ORM
        # `select(WorkerCredential)` would hydrate every column, including
        # the hash, and fail against that narrower grant.
        result = await session.execute(
            update(WorkerCredential)
            .where(
                WorkerCredential.worker_id == worker.id,
                WorkerCredential.workspace_id == workspace_id,
                WorkerCredential.status == WorkerCredentialStatus.ACTIVE,
            )
            .values(status=WorkerCredentialStatus.REVOKED)
        )
        revoked += cast(CursorResult, result).rowcount or 0
        await reap_worker_assignments(session, worker.id, reason=RecoveryReason.WORKER_REVOKED)
        worker.status = WorkerStatus.OFFLINE
        worker.current_load = 0
        worker.drain = True
    await _record_quick_action(
        session,
        workspace_id=workspace_id,
        actor_id=actor_id,
        action="emergency_stop",
        affected=revoked,
        details={"workers": len(workers)},
    )
    await session.commit()
    return QuickActionResult(
        action="emergency_stop",
        ok=True,
        affected=revoked,
        message=f"Revoked {revoked} credential(s) across {len(workers)} worker(s)",
        details={"workers": len(workers)},
    )


async def retry_failed_jobs(
    session: AsyncSession, workspace_id: uuid.UUID, *, actor_id: uuid.UUID
) -> QuickActionResult:
    now = datetime.now(UTC)
    dlq = (
        (
            await session.execute(
                select(DeadLetterJob).where(
                    DeadLetterJob.workspace_id == workspace_id,
                    DeadLetterJob.status == DeadLetterStatus.PENDING,
                )
            )
        )
        .scalars()
        .all()
    )
    enqueued = 0
    for entry in dlq:
        run = await session.get(PipelineRun, entry.related_id)
        stage_key = entry.job_type
        if run is None:
            # related_id may be an assignment; resolve via assignment → run
            assignment = await session.get(StageAssignment, entry.related_id)
            if assignment is None:
                continue
            run = await session.get(PipelineRun, assignment.pipeline_run_id)
            stage_key = enum_value(assignment.stage)
        if run is None or run.workspace_id != workspace_id:
            continue
        if run.status in {PipelineRunStatus.SUCCEEDED, PipelineRunStatus.CANCELLED}:
            entry.status = DeadLetterStatus.RESOLVED
            continue
        if run.status == PipelineRunStatus.FAILED:
            run.status = PipelineRunStatus.RUNNING
        session.add(
            JobSchedule(
                id=uuid.uuid4(),
                workspace_id=workspace_id,
                job_type=JobType.RETRY,
                ref_table=stage_key,
                ref_id=run.id,
                run_after=now,
                attempt=entry.attempt_count,
                priority=0,
                correlation_id=run.correlation_id,
                trace_id=run.trace_id,
            )
        )
        entry.status = DeadLetterStatus.RESOLVED
        enqueued += 1

    # Also re-queue failed assignments that never reached DLQ.
    failed_assignments = (
        (
            await session.execute(
                select(StageAssignment)
                .where(
                    StageAssignment.workspace_id == workspace_id,
                    StageAssignment.status == StageAssignmentStatus.FAILED,
                    StageAssignment.updated_at >= now - timedelta(days=7),
                )
                .limit(50)
            )
        )
        .scalars()
        .all()
    )
    for assignment in failed_assignments:
        existing = await count_rows(
            session,
            select(func.count(JobSchedule.id)).where(
                JobSchedule.workspace_id == workspace_id,
                JobSchedule.ref_id == assignment.pipeline_run_id,
                JobSchedule.job_type == JobType.RETRY,
                JobSchedule.status == JobScheduleStatus.PENDING,
            ),
        )
        if existing:
            continue
        run = await session.get(PipelineRun, assignment.pipeline_run_id)
        if run is None or run.workspace_id != workspace_id:
            continue
        if run.status in {
            PipelineRunStatus.SUCCEEDED,
            PipelineRunStatus.CANCELLED,
        }:
            continue
        if run.status == PipelineRunStatus.FAILED:
            run.status = PipelineRunStatus.RUNNING
        session.add(
            JobSchedule(
                id=uuid.uuid4(),
                workspace_id=workspace_id,
                job_type=JobType.RETRY,
                ref_table=enum_value(assignment.stage),
                ref_id=assignment.pipeline_run_id,
                run_after=now,
                attempt=assignment.attempt_number,
                priority=0,
                correlation_id=run.correlation_id,
                trace_id=run.trace_id,
            )
        )
        enqueued += 1

    await _record_quick_action(
        session,
        workspace_id=workspace_id,
        actor_id=actor_id,
        action="retry_failed_jobs",
        affected=enqueued,
    )
    await session.commit()
    return QuickActionResult(
        action="retry_failed_jobs",
        ok=True,
        affected=enqueued,
        message=f"Enqueued {enqueued} retry job(s)",
    )


async def clear_dead_letter_queue(
    session: AsyncSession, workspace_id: uuid.UUID, *, actor_id: uuid.UUID
) -> QuickActionResult:
    pending = (
        (
            await session.execute(
                select(DeadLetterJob).where(
                    DeadLetterJob.workspace_id == workspace_id,
                    DeadLetterJob.status == DeadLetterStatus.PENDING,
                )
            )
        )
        .scalars()
        .all()
    )
    for entry in pending:
        entry.status = DeadLetterStatus.DISCARDED
    await _record_quick_action(
        session,
        workspace_id=workspace_id,
        actor_id=actor_id,
        action="clear_dead_letter_queue",
        affected=len(pending),
    )
    await session.commit()
    return QuickActionResult(
        action="clear_dead_letter_queue",
        ok=True,
        affected=len(pending),
        message=f"Discarded {len(pending)} dead-letter job(s)",
    )


async def sync_github() -> QuickActionResult:
    status = await github_status.github_status()
    return QuickActionResult(
        action="sync_github",
        ok=status.available,
        affected=len(status.latest_commits) + len(status.open_pull_requests),
        message=(
            f"Synced {status.repository}"
            if status.available
            else (status.unavailable_reason or "GitHub unavailable")
        ),
        details={
            "available": status.available,
            "repository": status.repository,
            "commits": len(status.latest_commits),
            "open_prs": len(status.open_pull_requests),
            "failed_actions": len(status.failed_actions),
        },
    )


async def executive_insights(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    admin_user_id: uuid.UUID,
) -> ExecutiveInsightsOut:
    now = datetime.now(UTC)
    day_start, _, _ = _day_bounds(now)
    achievements: list[str] = []
    failures: list[str] = []

    completed = await count_rows(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.COMPLETED,
            or_(
                StageAssignment.completed_at >= day_start,
                StageAssignment.updated_at >= day_start,
            ),
        ),
    )
    if completed:
        achievements.append(f"{completed} stage job(s) completed today")

    published = await count_rows(
        session,
        select(func.count(PublishJob.id)).where(
            PublishJob.workspace_id == workspace_id,
            PublishJob.status == PublishJobStatus.PUBLISHED,
            PublishJob.updated_at >= day_start,
        ),
    )
    if published:
        achievements.append(f"{published} publish job(s) completed today")

    new_leads = await count_rows(
        session,
        select(func.count(Lead.id)).where(
            Lead.workspace_id == workspace_id,
            Lead.created_at >= day_start,
        ),
    )
    if new_leads:
        achievements.append(f"{new_leads} new lead(s) today")

    failed = await count_rows(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.FAILED,
            StageAssignment.updated_at >= day_start,
        ),
    )
    if failed:
        failures.append(f"{failed} stage job(s) failed today")

    pipeline_failed = await count_rows(
        session,
        select(func.count(PipelineRun.id)).where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status == PipelineRunStatus.FAILED,
            PipelineRun.updated_at >= day_start,
        ),
    )
    if pipeline_failed:
        failures.append(f"{pipeline_failed} pipeline(s) failed today")

    reviews = await count_rows(
        session,
        select(func.count(ReviewGate.id)).where(
            ReviewGate.workspace_id == workspace_id,
            ReviewGate.status == ReviewGateStatus.AWAITING,
        ),
    )
    dlq = await count_rows(
        session,
        select(func.count(DeadLetterJob.id)).where(
            DeadLetterJob.workspace_id == workspace_id,
            DeadLetterJob.status == DeadLetterStatus.PENDING,
        ),
    )

    if dlq:
        highest_risk = f"{dlq} dead-letter job(s) pending"
        next_action = "Retry failed jobs or clear the dead-letter queue"
    elif reviews:
        highest_risk = f"{reviews} Human Review Gate(s) waiting"
        next_action = "Open review gates and approve or reject waiting content"
    elif failed or pipeline_failed:
        highest_risk = "Elevated failure rate today"
        next_action = "Inspect AI Pipeline failures and retry recoverable jobs"
    else:
        spend_today, _ = await operations_dashboard.spend_totals(session, workspace_id)
        cap = (
            await session.execute(
                select(SpendCap).where(
                    SpendCap.workspace_id == workspace_id,
                    SpendCap.provider.is_(None),
                )
            )
        ).scalar_one_or_none()
        if (
            cap is not None
            and Decimal(str(cap.daily_cap_usd)) > 0
            and spend_today >= Decimal(str(cap.daily_cap_usd)) * Decimal("0.8")
        ):
            highest_risk = "Daily spend approaching cap"
            next_action = "Review Cost Control and pause non-critical workers if needed"
        else:
            highest_risk = "No critical risks detected"
            next_action = "Review Live Activity Feed and Content Command Center"

    biggest = (
        await session.execute(
            select(
                SpendLog.provider,
                func.sum(SpendLog.cost_usd),
            )
            .where(
                SpendLog.workspace_id == workspace_id,
                SpendLog.occurred_at >= day_start,
            )
            .group_by(SpendLog.provider)
            .order_by(func.sum(SpendLog.cost_usd).desc())
            .limit(1)
        )
    ).first()
    biggest_cost = Decimal(str(biggest[1])) if biggest else Decimal("0")
    biggest_label = str(biggest[0]) if biggest else None

    worker_activity = (
        await session.execute(
            select(
                WorkerRegistration.name,
                func.count(StageAssignment.id),
            )
            .join(
                StageAssignment,
                StageAssignment.worker_id == WorkerRegistration.id,
            )
            .where(
                StageAssignment.workspace_id == workspace_id,
                StageAssignment.updated_at >= day_start,
            )
            .group_by(WorkerRegistration.name)
            .order_by(func.count(StageAssignment.id).desc())
            .limit(1)
        )
    ).first()
    most_active_worker = worker_activity[0] if worker_activity else None

    customers = await operations_dashboard.customers(
        session, admin_user_id=admin_user_id, workspace_id=workspace_id
    )
    most_active_customer = None
    if customers.customers:
        most_active_customer = max(customers.customers, key=lambda row: row.member_count).name

    if not achievements:
        achievements.append("No completed achievements recorded yet today")
    if not failures:
        failures.append("No failures recorded yet today")

    return ExecutiveInsightsOut(
        todays_achievements=achievements,
        todays_failures=failures,
        highest_risk=highest_risk,
        suggested_next_action=next_action,
        biggest_cost_today_usd=biggest_cost,
        biggest_cost_today_label=biggest_label,
        most_active_worker=most_active_worker,
        most_active_customer=most_active_customer,
        generated_at=now,
    )

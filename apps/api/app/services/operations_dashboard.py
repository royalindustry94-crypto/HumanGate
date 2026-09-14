"""Workspace-scoped Operations Dashboard projections (V1 + V2).

All values come from durable tables, Stripe webhook receipts, or live
upstream APIs when configured. Missing sources are unavailable — never
fabricated.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.models.assignments import StageAssignment
from app.models.billing import BillingWebhookEvent, WorkspaceBilling
from app.models.config import SpendCap
from app.models.delivery import PublishJob
from app.models.enums import (
    DeadLetterStatus,
    JobScheduleStatus,
    JobType,
    PipelineRunStatus,
    PublishJobStatus,
    ReviewGateStatus,
    StageAssignmentStatus,
    WebhookStatus,
    WorkerStatus,
)
from app.models.leads import Lead
from app.models.operations import DeadLetterJob, WebhookEvent
from app.models.pipeline import PipelineRun
from app.models.review_gate import ReviewGate
from app.models.scheduling import JobSchedule, WorkspaceConcurrencyLimit
from app.models.spend import SpendLog
from app.models.workers import WorkerRegistration
from app.models.workspace import Workspace
from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole
from app.schemas.operations_dashboard import (
    AlertsOut,
    CustomerRow,
    CustomersOut,
    DeploymentInfo,
    ExecutiveDashboardOut,
    LeadCreate,
    LeadOut,
    LeadsOut,
    LeadUpdate,
    NotificationsOut,
    OperationsAlert,
    PipelineMonitorOut,
    PipelineRow,
    SpendOut,
    SpendProviderRow,
    WorkerMonitorOut,
    WorkerMonitorRow,
)
from app.services.workers import compute_liveness

logger = logging.getLogger(__name__)

LEAD_STATUSES = frozenset(
    {"new", "contacted", "qualified", "negotiation", "won", "lost", "nurturing"}
)


def _enum_value(value: object) -> str:
    return str(value.value if hasattr(value, "value") else value)


def _deployment_info() -> DeploymentInfo:
    settings = get_settings()
    deployed_at = None
    if settings.deployment_at:
        try:
            deployed_at = datetime.fromisoformat(settings.deployment_at.replace("Z", "+00:00"))
        except ValueError:
            logger.warning(
                "operations_invalid_deployment_at",
                extra={"deployment_at": settings.deployment_at},
            )
    return DeploymentInfo(
        ci_status=(settings.deployment_ci_status or "unavailable").strip().lower(),
        ci_url=settings.deployment_ci_url,
        git_branch=settings.deployment_git_branch,
        commit_sha=settings.deployment_commit_sha,
        deployed_at=deployed_at,
    )


async def _count(session: AsyncSession, stmt) -> int:
    value = (await session.execute(stmt)).scalar_one()
    return int(value or 0)


_WORKER_TOTAL_FIELDS = (
    "jobs_completed",
    "jobs_failed",
    "retry_count",
    "completed_today",
    "failed_today",
)
# Shared read-only default for a worker with no assignments in this workspace.
_EMPTY_WORKER_TOTALS: dict[str, int] = dict.fromkeys(_WORKER_TOTAL_FIELDS, 0)


async def _worker_assignment_totals(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    worker_ids: list[uuid.UUID],
    *,
    day_start: datetime,
) -> dict[uuid.UUID, dict[str, int]]:
    """Per-worker assignment tallies for the whole worker set in one query.

    Conditional aggregates (`COUNT(...) FILTER (WHERE ...)`) replace what used
    to be five separate per-worker queries inside the `workers()` loop.
    """
    if not worker_ids:
        return {}
    completed = StageAssignment.status == StageAssignmentStatus.COMPLETED
    failed = StageAssignment.status == StageAssignmentStatus.FAILED
    stmt = (
        select(
            StageAssignment.worker_id.label("worker_id"),
            func.count(StageAssignment.id).filter(completed).label("jobs_completed"),
            func.count(StageAssignment.id).filter(failed).label("jobs_failed"),
            func.count(StageAssignment.id)
            .filter(StageAssignment.attempt_number > 1)
            .label("retry_count"),
            func.count(StageAssignment.id)
            .filter(
                completed,
                or_(
                    StageAssignment.completed_at >= day_start,
                    StageAssignment.updated_at >= day_start,
                ),
            )
            .label("completed_today"),
            func.count(StageAssignment.id)
            .filter(failed, StageAssignment.updated_at >= day_start)
            .label("failed_today"),
        )
        .where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.worker_id.in_(worker_ids),
        )
        .group_by(StageAssignment.worker_id)
    )
    return {
        row.worker_id: {field: int(getattr(row, field) or 0) for field in _WORKER_TOTAL_FIELDS}
        for row in (await session.execute(stmt)).all()
    }


async def _worker_active_assignments(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    worker_ids: list[uuid.UUID],
) -> dict[uuid.UUID, StageAssignment]:
    """Most recently updated in-flight assignment per worker, in one query.

    `DISTINCT ON (worker_id)` with a matching leading ORDER BY term is the
    Postgres form of "top row per group"; this service is Postgres-only.
    """
    if not worker_ids:
        return {}
    stmt = (
        select(StageAssignment)
        .where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.worker_id.in_(worker_ids),
            StageAssignment.status.in_(
                [
                    StageAssignmentStatus.DISPATCHED,
                    StageAssignmentStatus.ACKNOWLEDGED,
                ]
            ),
        )
        .order_by(StageAssignment.worker_id, StageAssignment.updated_at.desc())
        .distinct(StageAssignment.worker_id)
    )
    return {
        assignment.worker_id: assignment
        for assignment in (await session.execute(stmt)).scalars().all()
        if assignment.worker_id is not None
    }


async def _pending_counts_by_stage(
    session: AsyncSession, workspace_id: uuid.UUID
) -> dict[str, int]:
    """Pending assignment depth per stage, summed per worker by the caller.

    Stages partition the pending set, so summing a worker's supported stages
    is equivalent to the old per-worker `stage.in_(supported_stages)` count.
    """
    stmt = (
        select(StageAssignment.stage, func.count(StageAssignment.id))
        .where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.PENDING,
        )
        .group_by(StageAssignment.stage)
    )
    return {
        _enum_value(stage): int(count or 0) for stage, count in (await session.execute(stmt)).all()
    }


def _resource_percent(capabilities: dict | None, *keys: str) -> float | None:
    if not isinstance(capabilities, dict):
        return None
    for key in keys:
        value = capabilities.get(key)
        if value is None and isinstance(capabilities.get("resources"), dict):
            value = capabilities["resources"].get(key)
        if value is None:
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if 0 <= number <= 100:
            return number
    return None


async def _spend_totals(session: AsyncSession, workspace_id: uuid.UUID) -> tuple[Decimal, Decimal]:
    now = datetime.now(UTC)
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    month_start = day_start.replace(day=1)
    row = (
        await session.execute(
            select(
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= day_start), 0
                ),
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= month_start),
                    0,
                ),
            ).where(SpendLog.workspace_id == workspace_id)
        )
    ).one()
    return Decimal(str(row[0])), Decimal(str(row[1]))


async def executive(session: AsyncSession, workspace_id: uuid.UUID) -> ExecutiveDashboardOut:
    active_assignments = [
        StageAssignmentStatus.DISPATCHED,
        StageAssignmentStatus.ACKNOWLEDGED,
    ]
    workers = await session.execute(
        select(WorkerRegistration.status, func.count(WorkerRegistration.id))
        .where(
            WorkerRegistration.deregistered_at.is_(None),
            or_(
                WorkerRegistration.workspace_id == workspace_id,
                WorkerRegistration.workspace_id.is_(None),
            ),
        )
        .group_by(WorkerRegistration.status)
    )
    worker_counts = {_enum_value(status): int(count) for status, count in workers.all()}
    jobs_running = await _count(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status.in_(active_assignments),
        ),
    )
    jobs_queued = await _count(
        session,
        select(func.count(JobSchedule.id)).where(
            JobSchedule.workspace_id == workspace_id,
            JobSchedule.status == JobScheduleStatus.PENDING,
        ),
    )
    jobs_failed = await _count(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.FAILED,
        ),
    )
    reviews = await _count(
        session,
        select(func.count(ReviewGate.id)).where(
            ReviewGate.workspace_id == workspace_id,
            ReviewGate.status == ReviewGateStatus.AWAITING,
        ),
    )
    spend_today, spend_month = await _spend_totals(session, workspace_id)
    workspace_exists = await _count(
        session,
        select(func.count(Workspace.id)).where(Workspace.id == workspace_id),
    )
    return ExecutiveDashboardOut(
        workers_online=worker_counts.get(WorkerStatus.ONLINE.value, 0),
        workers_busy=worker_counts.get(WorkerStatus.BUSY.value, 0),
        jobs_running=jobs_running,
        jobs_queued=jobs_queued,
        jobs_failed=jobs_failed,
        human_reviews_waiting=reviews,
        spend_today_usd=spend_today,
        spend_month_usd=spend_month,
        active_workspaces=workspace_exists,
        deployment=_deployment_info(),
        generated_at=datetime.now(UTC),
    )


async def workers(session: AsyncSession, workspace_id: uuid.UUID) -> WorkerMonitorOut:
    settings = get_settings()
    result = await session.execute(
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
    registrations = list(result.scalars().all())
    now = datetime.now(UTC)
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)

    # Three set-wide queries instead of six per worker. The previous shape was
    # 6W+1 round-trips for W workers on a route the dashboard auto-refreshes.
    worker_ids = [worker.id for worker in registrations]
    totals = await _worker_assignment_totals(session, workspace_id, worker_ids, day_start=day_start)
    active_by_worker = await _worker_active_assignments(session, workspace_id, worker_ids)
    pending_by_stage = await _pending_counts_by_stage(session, workspace_id)

    rows: list[WorkerMonitorRow] = []
    for worker in registrations:
        tally = totals.get(worker.id, _EMPTY_WORKER_TOTALS)
        active = active_by_worker.get(worker.id)
        # dict.fromkeys dedupes while preserving order: a stage listed twice in
        # supported_stages must not be counted twice, which the previous
        # `stage.in_(...)` form gave for free.
        queue = sum(
            pending_by_stage.get(stage, 0) for stage in dict.fromkeys(worker.supported_stages or ())
        )
        lease_status = "none"
        if active is not None and active.lease_expires_at is not None:
            lease_status = "expired" if active.lease_expires_at <= now else "active"
        liveness = compute_liveness(
            worker.last_heartbeat_at,
            suspect_after_seconds=settings.worker_suspect_after_seconds,
            offline_after_seconds=settings.worker_offline_after_seconds,
        )
        display_status = (
            WorkerStatus.OFFLINE.value
            if liveness == "dead"
            else ("suspect" if liveness == "suspect" else _enum_value(worker.status))
        )
        current = (
            f"{_enum_value(active.stage)} · {active.pipeline_run_id}"
            if active is not None
            else None
        )
        rows.append(
            WorkerMonitorRow(
                id=worker.id,
                name=worker.name,
                status=display_status,
                current_job=current,
                current_task=current,
                queue=queue,
                last_heartbeat_at=worker.last_heartbeat_at,
                retry_count=tally["retry_count"],
                jobs_completed=tally["jobs_completed"],
                jobs_failed=tally["jobs_failed"],
                jobs_completed_today=tally["completed_today"],
                jobs_failed_today=tally["failed_today"],
                cpu_percent=_resource_percent(
                    worker.capabilities, "cpu_percent", "cpu", "cpu_usage"
                ),
                memory_percent=_resource_percent(
                    worker.capabilities, "memory_percent", "memory", "mem_percent"
                ),
                lease_status=lease_status,
            )
        )
    return WorkerMonitorOut(workers=rows, generated_at=datetime.now(UTC))


async def pipelines(session: AsyncSession, workspace_id: uuid.UUID) -> PipelineMonitorOut:
    active_statuses = [
        PipelineRunStatus.CREATED,
        PipelineRunStatus.RUNNING,
        PipelineRunStatus.PAUSED,
        PipelineRunStatus.COMPENSATING,
    ]
    active = await _count(
        session,
        select(func.count(PipelineRun.id)).where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status.in_(active_statuses),
        ),
    )
    failed = await _count(
        session,
        select(func.count(PipelineRun.id)).where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status == PipelineRunStatus.FAILED,
        ),
    )
    queued = await _count(
        session,
        select(func.count(JobSchedule.id)).where(
            JobSchedule.workspace_id == workspace_id,
            JobSchedule.status == JobScheduleStatus.PENDING,
        ),
    )
    retrying = await _count(
        session,
        select(func.count(func.distinct(JobSchedule.ref_id))).where(
            JobSchedule.workspace_id == workspace_id,
            JobSchedule.job_type == JobType.RETRY,
            JobSchedule.status.in_([JobScheduleStatus.PENDING, JobScheduleStatus.LEASED]),
        ),
    )
    dlq = await _count(
        session,
        select(func.count(DeadLetterJob.id)).where(
            DeadLetterJob.workspace_id == workspace_id,
            DeadLetterJob.status == DeadLetterStatus.PENDING,
        ),
    )
    reviews = await _count(
        session,
        select(func.count(ReviewGate.id)).where(
            ReviewGate.workspace_id == workspace_id,
            ReviewGate.status == ReviewGateStatus.AWAITING,
        ),
    )
    publish_queue = await _count(
        session,
        select(func.count(PublishJob.id)).where(
            PublishJob.workspace_id == workspace_id,
            PublishJob.deleted_at.is_(None),
            PublishJob.status.in_([PublishJobStatus.PENDING, PublishJobStatus.PUBLISHING]),
        ),
    )
    jobs_completed = await _count(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.COMPLETED,
        ),
    )
    jobs_failed = await _count(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.FAILED,
        ),
    )
    result = await session.execute(
        select(PipelineRun)
        .where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status.in_([*active_statuses, PipelineRunStatus.FAILED]),
        )
        .order_by(PipelineRun.updated_at.desc())
        .limit(100)
    )
    rows = [
        PipelineRow(
            id=run.id,
            status=_enum_value(run.status),
            current_stage=_enum_value(run.current_stage),
            pause_reason=run.pause_reason,
            created_at=run.created_at,
            updated_at=run.updated_at,
        )
        for run in result.scalars().all()
    ]
    return PipelineMonitorOut(
        active_pipelines=active,
        queue_depth=queued,
        failed_pipelines=failed,
        retrying_pipelines=retrying,
        dead_letter_queue=dlq,
        review_gates=reviews,
        publish_queue=publish_queue,
        jobs_completed=jobs_completed,
        jobs_waiting=queued,
        jobs_failed=jobs_failed,
        human_reviews_waiting=reviews,
        publishing_queue=publish_queue,
        pipelines=rows,
        generated_at=datetime.now(UTC),
    )


def _lead_out(lead: Lead) -> LeadOut:
    return LeadOut(
        id=lead.id,
        workspace_id=lead.workspace_id,
        name=lead.name,
        company=lead.company,
        email=lead.email,
        source=lead.source,
        status=lead.status,
        notes=lead.notes,
        follow_up_date=lead.follow_up_date,
        created_at=lead.created_at,
        updated_at=lead.updated_at,
    )


async def list_leads(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    *,
    search: str | None = None,
    status: str | None = None,
    source: str | None = None,
) -> LeadsOut:
    stmt = select(Lead).where(Lead.workspace_id == workspace_id)
    if status:
        stmt = stmt.where(Lead.status == status)
    if source:
        stmt = stmt.where(Lead.source == source)
    if search:
        pattern = f"%{search.strip()}%"
        stmt = stmt.where(
            or_(
                Lead.name.ilike(pattern),
                Lead.email.ilike(pattern),
                Lead.company.ilike(pattern),
                Lead.notes.ilike(pattern),
            )
        )
    stmt = stmt.order_by(Lead.updated_at.desc())
    rows = list((await session.execute(stmt)).scalars().all())
    return LeadsOut(
        leads=[_lead_out(row) for row in rows],
        total=len(rows),
        generated_at=datetime.now(UTC),
    )


async def create_lead(
    session: AsyncSession, workspace_id: uuid.UUID, payload: LeadCreate
) -> LeadOut:
    status = payload.status.strip().lower()
    if status not in LEAD_STATUSES:
        raise ValueError(f"invalid lead status: {payload.status}")
    email = str(payload.email).strip().lower()
    if "@" not in email or email.startswith("@") or email.endswith("@"):
        raise ValueError("invalid lead email")
    lead = Lead(
        workspace_id=workspace_id,
        name=payload.name.strip(),
        company=payload.company.strip() if payload.company else None,
        email=email,
        source=payload.source.strip(),
        status=status,
        notes=payload.notes,
        follow_up_date=payload.follow_up_date,
    )
    session.add(lead)
    # The request-scoped RLS session owns the transaction and commits after
    # the route returns. Committing here would clear transaction-local
    # `request.jwt.claim.sub` before refresh, causing FORCE RLS to hide the
    # row from the same caller.
    await session.flush()
    await session.refresh(lead)
    return _lead_out(lead)


async def update_lead(
    session: AsyncSession,
    workspace_id: uuid.UUID,
    lead_id: uuid.UUID,
    payload: LeadUpdate,
) -> LeadOut | None:
    lead = await session.get(Lead, lead_id)
    if lead is None or lead.workspace_id != workspace_id:
        return None
    data = payload.model_dump(exclude_unset=True)
    if "status" in data and data["status"] is not None:
        status = str(data["status"]).strip().lower()
        if status not in LEAD_STATUSES:
            raise ValueError(f"invalid lead status: {data['status']}")
        data["status"] = status
    if "email" in data and data["email"] is not None:
        data["email"] = str(data["email"]).strip().lower()
    if "name" in data and data["name"] is not None:
        data["name"] = str(data["name"]).strip()
    if "company" in data and data["company"] is not None:
        data["company"] = str(data["company"]).strip() or None
    if "source" in data and data["source"] is not None:
        data["source"] = str(data["source"]).strip()
    for key, value in data.items():
        setattr(lead, key, value)
    await session.flush()
    await session.refresh(lead)
    return _lead_out(lead)


def _revenue_from_payload(payload: dict) -> Decimal:
    """Extract paid amount from a Stripe invoice webhook payload (cents → USD)."""
    obj = payload.get("data", {}).get("object", {}) if isinstance(payload, dict) else {}
    if not isinstance(obj, dict):
        return Decimal("0")
    for key in ("amount_paid", "amount_due", "total"):
        raw = obj.get(key)
        if raw is None:
            continue
        try:
            cents = Decimal(str(raw))
        except Exception:
            continue
        if cents >= 0:
            return (cents / Decimal("100")).quantize(Decimal("0.01"))
    return Decimal("0")


async def customers(
    session: AsyncSession,
    *,
    admin_user_id: uuid.UUID,
    workspace_id: uuid.UUID | None = None,
) -> CustomersOut:
    """Customers = workspaces the caller administers, with billing + members.

    ``workspace_id=None`` (the `/customers` route's own use) is the
    intentional cross-workspace "portfolio" view: every workspace the
    caller administers. Pass an explicit ``workspace_id`` when calling
    from a report whose own contract is a single workspace (executive
    insights, executive mode, search) — omitting it there was a real bug
    (2026-09-08 audit finding): those reports silently blended in
    billing/revenue/member data from every *other* workspace the caller
    also happens to administer, mislabeled as belonging to the one
    workspace in the URL.
    """
    now = datetime.now(UTC)
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    admin_ws_conditions = [
        WorkspaceMembership.user_id == admin_user_id,
        WorkspaceMembership.role == WorkspaceRole.ADMIN,
    ]
    if workspace_id is not None:
        admin_ws_conditions.append(Workspace.id == workspace_id)
    admin_ws = (
        (
            await session.execute(
                select(Workspace)
                .join(
                    WorkspaceMembership,
                    WorkspaceMembership.workspace_id == Workspace.id,
                )
                .where(*admin_ws_conditions)
                .order_by(Workspace.created_at.desc())
            )
        )
        .scalars()
        .all()
    )
    workspace_ids = [ws.id for ws in admin_ws]
    rows: list[CustomerRow] = []
    beta_users = active_users = paying_users = trial_users = 0
    revenue = Decimal("0")

    if workspace_ids:
        billing_map = {
            row.workspace_id: row
            for row in (
                await session.execute(
                    select(WorkspaceBilling).where(WorkspaceBilling.workspace_id.in_(workspace_ids))
                )
            )
            .scalars()
            .all()
        }
        member_counts = {
            wid: int(count)
            for wid, count in (
                await session.execute(
                    select(
                        WorkspaceMembership.workspace_id,
                        func.count(WorkspaceMembership.id),
                    )
                    .where(WorkspaceMembership.workspace_id.in_(workspace_ids))
                    .group_by(WorkspaceMembership.workspace_id)
                )
            ).all()
        }
        for ws in admin_ws:
            billing = billing_map.get(ws.id)
            plan = billing.plan if billing else "none"
            status = billing.status if billing else "inactive"
            members = member_counts.get(ws.id, 0)
            if status == "trialing" and plan == "pro":
                trial_users += members
                active_users += members
            elif status == "active" and plan == "pro":
                paying_users += members
                active_users += members
            else:
                beta_users += members
            rows.append(
                CustomerRow(
                    workspace_id=ws.id,
                    name=ws.name,
                    plan=plan,
                    subscription_status=status,
                    member_count=members,
                    stripe_customer_id=billing.stripe_customer_id if billing else None,
                    current_period_end=billing.current_period_end if billing else None,
                    cancel_at_period_end=(billing.cancel_at_period_end if billing else False),
                    created_at=ws.created_at,
                )
            )
        events = (
            (
                await session.execute(
                    select(BillingWebhookEvent).where(
                        BillingWebhookEvent.workspace_id.in_(workspace_ids),
                        BillingWebhookEvent.event_type.in_(
                            ["invoice.paid", "invoice.payment_succeeded"]
                        ),
                        BillingWebhookEvent.processed_at >= month_start,
                    )
                )
            )
            .scalars()
            .all()
        )
        for event in events:
            revenue += _revenue_from_payload(event.payload or {})

    return CustomersOut(
        beta_users=beta_users,
        active_users=active_users,
        paying_users=paying_users,
        trial_users=trial_users,
        revenue_mtd_usd=revenue,
        revenue_source="stripe_invoice_webhooks",
        customers=rows,
        generated_at=now,
    )


async def spend(session: AsyncSession, workspace_id: uuid.UUID) -> SpendOut:
    now = datetime.now(UTC)
    day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = day_start - timedelta(days=day_start.weekday())
    month_start = day_start.replace(day=1)
    totals = (
        await session.execute(
            select(
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= day_start),
                    0,
                ),
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= week_start),
                    0,
                ),
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= month_start),
                    0,
                ),
            ).where(SpendLog.workspace_id == workspace_id)
        )
    ).one()
    today = Decimal(str(totals[0]))
    week = Decimal(str(totals[1]))
    month = Decimal(str(totals[2]))
    by_provider_rows = (
        await session.execute(
            select(
                SpendLog.provider,
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= day_start),
                    0,
                ),
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= week_start),
                    0,
                ),
                func.coalesce(
                    func.sum(SpendLog.cost_usd).filter(SpendLog.occurred_at >= month_start),
                    0,
                ),
            )
            .where(SpendLog.workspace_id == workspace_id)
            .group_by(SpendLog.provider)
            .order_by(SpendLog.provider)
        )
    ).all()
    by_provider = [
        SpendProviderRow(
            provider=provider,
            today_usd=Decimal(str(t)),
            week_usd=Decimal(str(w)),
            month_usd=Decimal(str(m)),
        )
        for provider, t, w, m in by_provider_rows
    ]
    cap = (
        await session.execute(
            select(SpendCap).where(
                SpendCap.workspace_id == workspace_id,
                SpendCap.provider.is_(None),
            )
        )
    ).scalar_one_or_none()
    daily_cap = Decimal(str(cap.daily_cap_usd)) if cap else None
    monthly_cap = Decimal(str(cap.monthly_cap_usd)) if cap else None
    return SpendOut(
        today_usd=today,
        week_usd=week,
        month_usd=month,
        by_provider=by_provider,
        daily_cap_usd=daily_cap,
        monthly_cap_usd=monthly_cap,
        budget_remaining_daily_usd=(
            max(daily_cap - today, Decimal("0")) if daily_cap is not None else None
        ),
        budget_remaining_monthly_usd=(
            max(monthly_cap - month, Decimal("0")) if monthly_cap is not None else None
        ),
        generated_at=now,
    )


async def _build_alerts(session: AsyncSession, workspace_id: uuid.UUID) -> list[OperationsAlert]:
    now = datetime.now(UTC)
    since = now - timedelta(days=1)
    items: list[OperationsAlert] = []

    worker_result = await session.execute(
        select(WorkerRegistration).where(
            WorkerRegistration.deregistered_at.is_(None),
            or_(
                WorkerRegistration.workspace_id == workspace_id,
                WorkerRegistration.workspace_id.is_(None),
            ),
        )
    )
    settings = get_settings()
    offline_count = sum(
        1
        for worker in worker_result.scalars().all()
        if compute_liveness(
            worker.last_heartbeat_at,
            suspect_after_seconds=settings.worker_suspect_after_seconds,
            offline_after_seconds=settings.worker_offline_after_seconds,
        )
        == "dead"
    )
    failed_jobs = await _count(
        session,
        select(func.count(StageAssignment.id)).where(
            StageAssignment.workspace_id == workspace_id,
            StageAssignment.status == StageAssignmentStatus.FAILED,
            StageAssignment.updated_at >= since,
        ),
    )
    pipeline_failed = await _count(
        session,
        select(func.count(PipelineRun.id)).where(
            PipelineRun.workspace_id == workspace_id,
            PipelineRun.status == PipelineRunStatus.FAILED,
            PipelineRun.updated_at >= since,
        ),
    )
    reviews = await _count(
        session,
        select(func.count(ReviewGate.id)).where(
            ReviewGate.workspace_id == workspace_id,
            ReviewGate.status == ReviewGateStatus.AWAITING,
        ),
    )
    queue = await _count(
        session,
        select(func.count(JobSchedule.id)).where(
            JobSchedule.workspace_id == workspace_id,
            JobSchedule.status == JobScheduleStatus.PENDING,
        ),
    )
    soft_limit = (
        await session.execute(
            select(WorkspaceConcurrencyLimit.queue_soft_limit).where(
                WorkspaceConcurrencyLimit.workspace_id == workspace_id
            )
        )
    ).scalar_one_or_none() or settings.queue_soft_limit_default
    failed_webhooks = await _count(
        session,
        select(func.count(WebhookEvent.id)).where(
            WebhookEvent.workspace_id == workspace_id,
            WebhookEvent.status == WebhookStatus.FAILED,
            WebhookEvent.updated_at >= since,
        ),
    )
    failed_webhooks += await _count(
        session,
        select(func.count(BillingWebhookEvent.id)).where(
            BillingWebhookEvent.workspace_id == workspace_id,
            BillingWebhookEvent.event_type == "invoice.payment_failed",
            BillingWebhookEvent.processed_at >= since,
        ),
    )
    new_leads = await _count(
        session,
        select(func.count(Lead.id)).where(
            Lead.workspace_id == workspace_id,
            Lead.created_at >= since,
        ),
    )
    customer_signups = await _count(
        session,
        select(func.count(WorkspaceMembership.id)).where(
            WorkspaceMembership.workspace_id == workspace_id,
            WorkspaceMembership.created_at >= since,
        ),
    )
    spend_today, spend_month = await _spend_totals(session, workspace_id)
    cap = (
        await session.execute(
            select(SpendCap).where(
                SpendCap.workspace_id == workspace_id,
                SpendCap.provider.is_(None),
            )
        )
    ).scalar_one_or_none()
    spend_warning = False
    spend_message = "No workspace-wide spend cap configured"
    if cap is not None:
        daily_cap = Decimal(str(cap.daily_cap_usd))
        monthly_cap = Decimal(str(cap.monthly_cap_usd))
        spend_warning = (daily_cap > 0 and spend_today >= daily_cap * Decimal("0.8")) or (
            monthly_cap > 0 and spend_month >= monthly_cap * Decimal("0.8")
        )
        spend_message = (
            f"${spend_today:.4f} today / ${daily_cap:.4f} cap; "
            f"${spend_month:.4f} month / ${monthly_cap:.4f} cap"
        )

    def add(
        key: str,
        title: str,
        count: int,
        message: str,
        severity: str,
        occurred_at: datetime | None = None,
    ) -> None:
        if count > 0:
            items.append(
                OperationsAlert(
                    key=key,
                    severity=severity,
                    title=title,
                    count=count,
                    message=message,
                    occurred_at=occurred_at or now,
                )
            )

    add(
        "worker_offline",
        "Worker Offline",
        offline_count,
        f"{offline_count} registered worker(s) are offline",
        "critical",
    )
    add(
        "pipeline_failed",
        "Pipeline Failed",
        pipeline_failed,
        f"{pipeline_failed} pipeline run(s) failed in the last 24 hours",
        "critical",
    )
    add(
        "failed_jobs",
        "Failed Jobs",
        failed_jobs,
        f"{failed_jobs} assignment(s) failed in the last 24 hours",
        "critical",
    )
    ci = _deployment_info()
    ci_failed = int(ci.ci_status not in {"success", "passing", "green", "unavailable"})
    add(
        "failed_ci",
        "CI Failed",
        ci_failed,
        f"Deployment CI status is {ci.ci_status}",
        "critical",
    )
    add(
        "spend_warning",
        "Spend Warning",
        int(spend_warning),
        spend_message,
        "warning",
    )
    add(
        "review_required",
        "Review Required",
        reviews,
        f"{reviews} Human Review Gate(s) are waiting",
        "warning",
    )
    # Keep V1 key for compatibility with existing clients/tests.
    add(
        "review_waiting",
        "Review Waiting",
        reviews,
        f"{reviews} Human Review Gate(s) are waiting",
        "warning",
    )
    add(
        "queue_backlog",
        "Queue Backlog",
        queue if queue >= soft_limit else 0,
        f"Queue depth {queue} reached soft limit {soft_limit}",
        "warning",
    )
    add(
        "failed_webhooks",
        "Failed Webhooks",
        failed_webhooks,
        f"{failed_webhooks} webhook failure(s) in the last 24 hours",
        "critical",
    )
    add(
        "new_lead",
        "New Lead",
        new_leads,
        f"{new_leads} lead(s) created in the last 24 hours",
        "info",
    )
    add(
        "customer_signup",
        "Customer Signup",
        customer_signups,
        f"{customer_signups} membership signup(s) in the last 24 hours",
        "info",
    )
    return items


async def alerts(session: AsyncSession, workspace_id: uuid.UUID) -> AlertsOut:
    now = datetime.now(UTC)
    items = await _build_alerts(session, workspace_id)
    # V1 clients expect the original alert set without the V2 aliases/info noise.
    v1_keys = {
        "worker_offline",
        "failed_jobs",
        "failed_ci",
        "spend_warning",
        "review_waiting",
        "queue_backlog",
        "failed_webhooks",
    }
    return AlertsOut(
        alerts=[item for item in items if item.key in v1_keys],
        generated_at=now,
    )


async def notifications(session: AsyncSession, workspace_id: uuid.UUID) -> NotificationsOut:
    now = datetime.now(UTC)
    items = await _build_alerts(session, workspace_id)
    # Prefer the V2 review key in the notification center.
    filtered = [item for item in items if item.key != "review_waiting"]
    return NotificationsOut(notifications=filtered, generated_at=now)

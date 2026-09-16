"""Workspace-admin Operations Dashboard APIs (V1–V3 Mission Control)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.automation import AutomationHealthSnapshot, automation_health_snapshot
from app.core.authorization import require_workspace_admin
from app.core.security import AuthenticatedUser, get_current_session, get_current_user
from app.models.workspace_membership import WorkspaceMembership
from app.schemas.operations_dashboard import (
    AlertsOut,
    CustomersOut,
    ExecutiveDashboardOut,
    GitHubOut,
    LeadCreate,
    LeadOut,
    LeadsOut,
    LeadUpdate,
    NotificationsOut,
    PipelineMonitorOut,
    SpendOut,
    WorkerMonitorOut,
)
from app.schemas.operations_mission import (
    ActivityFeedOut,
    ContentCommandCenterOut,
    CostControlOut,
    ExecutiveInsightsOut,
    QuickActionResult,
    SystemHealthOut,
    WorkerTimelineOut,
)
from app.schemas.operations_v4 import (
    AssistantAnswerOut,
    AssistantQuestionIn,
    ExecutiveModeOut,
    GlobalSearchOut,
    LiveLogsOut,
    UniversalTimelineOut,
)
from app.services import (
    github_status,
    operations_dashboard,
    operations_mission,
    operations_v4,
)

router = APIRouter(
    prefix="/workspaces/{workspace_id}/operations",
    tags=["operations-dashboard"],
)


@router.get("/executive", response_model=ExecutiveDashboardOut)
async def executive_dashboard(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> ExecutiveDashboardOut:
    return await operations_dashboard.executive(db, workspace_id)


@router.get("/workers", response_model=WorkerMonitorOut)
async def worker_monitor(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> WorkerMonitorOut:
    return await operations_dashboard.workers(db, workspace_id)


@router.get("/pipelines", response_model=PipelineMonitorOut)
async def pipeline_monitor(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> PipelineMonitorOut:
    return await operations_dashboard.pipelines(db, workspace_id)


@router.get("/alerts", response_model=AlertsOut)
async def alerts(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> AlertsOut:
    return await operations_dashboard.alerts(db, workspace_id)


@router.get("/notifications", response_model=NotificationsOut)
async def notifications(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> NotificationsOut:
    return await operations_dashboard.notifications(db, workspace_id)


@router.get("/leads", response_model=LeadsOut)
async def list_leads(
    workspace_id: uuid.UUID,
    search: str | None = Query(default=None, max_length=200),
    status_filter: str | None = Query(default=None, alias="status", max_length=32),
    source: str | None = Query(default=None, max_length=100),
    limit: int = Query(default=200, ge=1, le=500),
    offset: int = Query(default=0, ge=0, le=10_000),
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> LeadsOut:
    return await operations_dashboard.list_leads(
        db,
        workspace_id,
        search=search,
        status=status_filter,
        source=source,
        limit=limit,
        offset=offset,
    )


@router.post("/leads", response_model=LeadOut, status_code=status.HTTP_201_CREATED)
async def create_lead(
    workspace_id: uuid.UUID,
    payload: LeadCreate,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> LeadOut:
    try:
        return await operations_dashboard.create_lead(db, workspace_id, payload)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc


@router.patch("/leads/{lead_id}", response_model=LeadOut)
async def update_lead(
    workspace_id: uuid.UUID,
    lead_id: uuid.UUID,
    payload: LeadUpdate,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> LeadOut:
    try:
        lead = await operations_dashboard.update_lead(db, workspace_id, lead_id, payload)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
        ) from exc
    if lead is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="lead not found")
    return lead


@router.get("/customers", response_model=CustomersOut)
async def customers(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> CustomersOut:
    del workspace_id  # authz scoped; customers are admin-owned workspaces
    return await operations_dashboard.customers(db, admin_user_id=uuid.UUID(user.id))


@router.get("/spend", response_model=SpendOut)
async def spend_dashboard(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> SpendOut:
    return await operations_dashboard.spend(db, workspace_id)


@router.get("/github", response_model=GitHubOut)
async def github_dashboard(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
) -> GitHubOut:
    del workspace_id, membership
    return await github_status.github_status()


# --- V3 Mission Control -------------------------------------------------


@router.get("/activity", response_model=ActivityFeedOut)
async def activity_feed(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> ActivityFeedOut:
    return await operations_mission.activity_feed(db, workspace_id)


@router.get("/health", response_model=SystemHealthOut)
async def system_health(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> SystemHealthOut:
    automation = await automation_health_snapshot()
    return await operations_mission.system_health(db, workspace_id, automation=automation)


@router.get("/cost-control", response_model=CostControlOut)
async def cost_control(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> CostControlOut:
    return await operations_mission.cost_control(db, workspace_id)


@router.get("/worker-timeline", response_model=WorkerTimelineOut)
async def worker_timeline(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> WorkerTimelineOut:
    return await operations_mission.worker_timeline(db, workspace_id)


@router.get("/content-command", response_model=ContentCommandCenterOut)
async def content_command(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> ContentCommandCenterOut:
    return await operations_mission.content_command_center(db, workspace_id)


@router.get("/insights", response_model=ExecutiveInsightsOut)
async def executive_insights(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> ExecutiveInsightsOut:
    return await operations_mission.executive_insights(
        db, workspace_id, admin_user_id=uuid.UUID(user.id)
    )


@router.post("/actions/pause-workers", response_model=QuickActionResult)
async def action_pause_workers(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> QuickActionResult:
    return await operations_mission.pause_workers(db, workspace_id, actor_id=uuid.UUID(user.id))


@router.post("/actions/resume-workers", response_model=QuickActionResult)
async def action_resume_workers(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> QuickActionResult:
    return await operations_mission.resume_workers(db, workspace_id, actor_id=uuid.UUID(user.id))


@router.post("/actions/emergency-stop", response_model=QuickActionResult)
async def action_emergency_stop(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> QuickActionResult:
    return await operations_mission.emergency_stop(db, workspace_id, actor_id=uuid.UUID(user.id))


@router.post("/actions/retry-failed-jobs", response_model=QuickActionResult)
async def action_retry_failed_jobs(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> QuickActionResult:
    return await operations_mission.retry_failed_jobs(db, workspace_id, actor_id=uuid.UUID(user.id))


@router.post("/actions/clear-dead-letter", response_model=QuickActionResult)
async def action_clear_dead_letter(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> QuickActionResult:
    return await operations_mission.clear_dead_letter_queue(
        db, workspace_id, actor_id=uuid.UUID(user.id)
    )


@router.post("/actions/sync-github", response_model=QuickActionResult)
async def action_sync_github(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
) -> QuickActionResult:
    del workspace_id, membership
    return await operations_mission.sync_github()


# --- V4 integrated Mission Control --------------------------------------


@router.get("/search", response_model=GlobalSearchOut)
async def global_search(
    workspace_id: uuid.UUID,
    q: str = Query(min_length=1, max_length=200),
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> GlobalSearchOut:
    return await operations_v4.global_search(
        db,
        workspace_id,
        admin_user_id=uuid.UUID(user.id),
        query=q,
    )


@router.get("/timeline", response_model=UniversalTimelineOut)
async def universal_timeline(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> UniversalTimelineOut:
    return await operations_v4.universal_timeline(db, workspace_id)


@router.get("/logs", response_model=LiveLogsOut)
async def live_logs(
    workspace_id: uuid.UUID,
    worker_id: uuid.UUID | None = None,
    pipeline_id: uuid.UUID | None = None,
    job_id: uuid.UUID | None = None,
    severity: str | None = Query(default=None, pattern="^(debug|info|warning|error|critical)$"),
    limit: int = Query(default=200, ge=1, le=1000),
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> LiveLogsOut:
    return await operations_v4.live_logs(
        db,
        workspace_id,
        worker_id=worker_id,
        pipeline_id=pipeline_id,
        job_id=job_id,
        severity=severity,
        limit=limit,
    )


async def _automation_payload() -> AutomationHealthSnapshot:
    return await automation_health_snapshot()


@router.get("/automation")
async def automation_diagnostics(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
) -> AutomationHealthSnapshot:
    del workspace_id, membership
    return await _automation_payload()


@router.get("/executive-mode", response_model=ExecutiveModeOut)
async def executive_mode(
    workspace_id: uuid.UUID,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> ExecutiveModeOut:
    return await operations_v4.executive_mode(
        db,
        workspace_id,
        admin_user_id=uuid.UUID(user.id),
        automation=await _automation_payload(),
    )


@router.post("/assistant", response_model=AssistantAnswerOut)
async def assistant(
    workspace_id: uuid.UUID,
    payload: AssistantQuestionIn,
    membership: WorkspaceMembership = Depends(require_workspace_admin),
    user: AuthenticatedUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_current_session),
) -> AssistantAnswerOut:
    return await operations_v4.assistant_answer(
        db,
        workspace_id,
        admin_user_id=uuid.UUID(user.id),
        question=payload.question,
    )

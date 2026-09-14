"""Workspace data export and deletion endpoints (admin only).

Both routes are workspace-scoped and admin-guarded. They run on the RLS-bound
runtime session, so the tenant boundary is enforced by the database as well as
by the HTTP guard, and both emit an audit event recording the outcome.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.audit import audit
from app.core.authorization import require_workspace_admin
from app.core.security import AuthenticatedUser, get_current_session, get_current_user
from app.models.workspace_membership import WorkspaceMembership
from app.services import data_governance

router = APIRouter(prefix="/workspaces/{workspace_id}/data", tags=["data-governance"])


class DeletionRequestIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # The caller must restate the workspace id, so a mis-routed request cannot
    # delete the wrong workspace's content.
    confirm_workspace_id: uuid.UUID


@router.get("/export")
async def export_workspace_data(
    workspace_id: uuid.UUID,
    request: Request,
    user: AuthenticatedUser = Depends(get_current_user),
    _membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> dict:
    bundle = await data_governance.export_workspace(db, workspace_id=workspace_id)
    audit(
        request,
        "workspace_data_exported",
        workspace_id=str(workspace_id),
        actor=user.id,
        table_count=len(bundle.tables),
        excluded_table_count=len(bundle.excluded_tables),
        truncated_table_count=len(bundle.truncated_tables),
    )
    return {
        "workspace_id": str(bundle.workspace_id),
        "generated_at": bundle.generated_at.isoformat(),
        "row_counts": bundle.row_counts,
        "excluded_tables": list(bundle.excluded_tables),
        "exclusion_reason": bundle.exclusion_reason,
        "unattributable_tables": list(bundle.unattributable_tables),
        "unattributable_reason": bundle.unattributable_reason,
        "tables": bundle.tables,
        "truncated_tables": list(bundle.truncated_tables),
        "truncation_reason": bundle.truncation_reason,
    }


@router.post("/deletion-requests", status_code=status.HTTP_200_OK)
async def request_workspace_deletion(
    workspace_id: uuid.UUID,
    payload: DeletionRequestIn,
    request: Request,
    user: AuthenticatedUser = Depends(get_current_user),
    _membership: WorkspaceMembership = Depends(require_workspace_admin),
    db: AsyncSession = Depends(get_current_session),
) -> dict:
    try:
        outcome = await data_governance.delete_workspace_content(
            db,
            workspace_id=workspace_id,
            confirm_workspace_id=payload.confirm_workspace_id,
        )
    except data_governance.DataGovernanceError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=exc.message) from exc
    await db.commit()
    audit(
        request,
        "workspace_data_deletion_executed",
        workspace_id=str(workspace_id),
        actor=user.id,
        withdrawn_row_total=sum(outcome.soft_deleted_counts.values()),
        erased_row_total=sum(outcome.hard_deleted_counts.values()),
        retained_table_count=len(outcome.retained_tables),
    )
    return {
        "workspace_id": str(outcome.workspace_id),
        "executed_at": outcome.executed_at.isoformat(),
        "withdrawn_counts": outcome.soft_deleted_counts,
        "erased_counts": outcome.hard_deleted_counts,
        "retained_content_history_tables": list(outcome.retained_content_history_tables),
        "retained_tables": list(outcome.retained_tables),
        "retention_reason": outcome.retention_reason,
    }

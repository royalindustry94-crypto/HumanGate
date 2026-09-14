"""Spend bootstrap and query helpers for workspace spend controls."""

from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.models.config import SpendCap
from app.models.enums import ReservationStatus
from app.models.spend import SpendLog, SpendReservation
from app.orchestration import controller


async def ensure_default_spend_cap(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    daily_cap_usd: float | None = None,
    monthly_cap_usd: float | None = None,
) -> SpendCap:
    """Idempotently seed the workspace-wide (provider=NULL) spend cap."""
    existing = (
        await session.execute(
            select(SpendCap).where(
                SpendCap.workspace_id == workspace_id,
                SpendCap.provider.is_(None),
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        return existing

    settings = get_settings()
    cap = SpendCap(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        provider=None,
        daily_cap_usd=(
            daily_cap_usd if daily_cap_usd is not None else settings.default_daily_spend_cap_usd
        ),
        monthly_cap_usd=(
            monthly_cap_usd
            if monthly_cap_usd is not None
            else settings.default_monthly_spend_cap_usd
        ),
        created_by=actor_id,
        updated_by=actor_id,
    )
    session.add(cap)
    # Concurrent first-touch of the same workspace both miss the SELECT above
    # and race to INSERT. `uq_spend_caps_workspace_provider` (migration 0003)
    # keeps that from creating a duplicate cap, but the loser used to surface
    # as an unhandled IntegrityError -> HTTP 500 (audit L-4). Re-read the row
    # the winner committed instead: the caller wanted the cap to exist, and it
    # now does.
    try:
        async with session.begin_nested():
            await session.flush()
    except IntegrityError:
        existing = await get_workspace_spend_cap(session, workspace_id=workspace_id)
        if existing is None:  # pragma: no cover - unique violation implies a row
            raise
        return existing
    return cap


async def get_workspace_spend_cap(
    session: AsyncSession, *, workspace_id: uuid.UUID
) -> SpendCap | None:
    return (
        await session.execute(
            select(SpendCap).where(
                SpendCap.workspace_id == workspace_id,
                SpendCap.provider.is_(None),
            )
        )
    ).scalar_one_or_none()


async def update_workspace_spend_cap(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    actor_id: uuid.UUID,
    daily_cap_usd: Decimal | None = None,
    monthly_cap_usd: Decimal | None = None,
) -> SpendCap:
    cap = await ensure_default_spend_cap(session, workspace_id=workspace_id, actor_id=actor_id)
    if daily_cap_usd is not None:
        cap.daily_cap_usd = daily_cap_usd
    if monthly_cap_usd is not None:
        cap.monthly_cap_usd = monthly_cap_usd
    cap.updated_by = actor_id
    await session.flush()
    return cap


async def spend_snapshot(session: AsyncSession, *, workspace_id: uuid.UUID) -> dict:
    cap = await get_workspace_spend_cap(session, workspace_id=workspace_id)
    daily_used = await controller.spend_committed_plus_reserved(
        session,
        workspace_id=workspace_id,
        provider=None,
        since=controller.utc_day_start(),
    )
    monthly_used = await controller.spend_committed_plus_reserved(
        session,
        workspace_id=workspace_id,
        provider=None,
        since=controller.utc_month_start(),
    )
    # Summed in SQL, not in Python. This previously fetched every open
    # reservation row with no LIMIT and added them up in application memory --
    # unbounded work on a spend-control path (audit H-3).
    reserved_total = Decimal(
        (
            await session.execute(
                select(func.coalesce(func.sum(SpendReservation.estimated_cost_usd), 0)).where(
                    SpendReservation.workspace_id == workspace_id,
                    SpendReservation.status == ReservationStatus.RESERVED,
                )
            )
        ).scalar_one()
    )
    log_count = (
        await session.execute(
            select(SpendLog.id).where(SpendLog.workspace_id == workspace_id).limit(1)
        )
    ).first()
    return {
        "workspace_id": workspace_id,
        "daily_cap_usd": float(cap.daily_cap_usd) if cap else None,
        "monthly_cap_usd": float(cap.monthly_cap_usd) if cap else None,
        "daily_used_usd": float(daily_used),
        "monthly_used_usd": float(monthly_used),
        "reserved_usd": float(reserved_total),
        "has_spend_history": log_count is not None,
        "cap_id": cap.id if cap else None,
    }

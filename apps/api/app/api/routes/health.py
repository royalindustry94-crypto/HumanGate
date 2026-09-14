"""Health check endpoint.

Distinguishes liveness (process is up) from readiness (dependencies are
reachable) — a load balancer or orchestrator needs both, and collapsing
them into one always-200 endpoint hides real outages.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.automation import automation_health_snapshot
from app.db.session import get_db

logger = logging.getLogger(__name__)

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def liveness() -> dict[str, str]:
    """Process is running. Does not touch the database."""
    return {"status": "ok"}


@router.get("/health/ready")
async def readiness(db: AsyncSession = Depends(get_db)) -> dict[str, str]:
    """Process is running AND its dependencies (currently: Postgres) are reachable.

    Returns 503 (not 200 with an error body) on failure so load balancers
    and orchestrators treat it as a real readiness failure. Reports the
    connected role so a DATABASE_URL misconfiguration (wrong role, not
    just wrong host) is visible here instead of only surfacing downstream
    as a permission error on a specific table.
    """
    try:
        result = await db.execute(text("SELECT current_user"))
        db_user = result.scalar_one()
    except Exception as exc:
        logger.exception("readiness check failed: database unreachable")
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="database unreachable",
        ) from exc
    return {"status": "ok", "database": "reachable", "db_user": db_user}


@router.get("/health/automation")
async def automation_health() -> dict:
    """Expose database-backed automation ownership/health for ops."""
    return await automation_health_snapshot()

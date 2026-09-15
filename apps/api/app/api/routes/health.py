"""Health check endpoint.

Distinguishes liveness (process is up) from readiness (dependencies are
reachable) — a load balancer or orchestrator needs both, and collapsing
them into one always-200 endpoint hides real outages.
"""

from __future__ import annotations

import hmac
import logging

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.automation import (
    automation_health_snapshot,
    public_automation_health_snapshot,
)
from app.core.config import get_settings
from app.db.session import get_db

logger = logging.getLogger(__name__)

router = APIRouter(tags=["health"])


def _may_see_db_role(authorization: str | None) -> bool:
    """Local environments always; elsewhere only with the metrics token.

    Reuses METRICS_SCRAPER_TOKEN rather than inventing a second operator
    credential — it already gates the other unauthenticated operational
    telemetry endpoint.
    """
    settings = get_settings()
    if settings.is_local_environment:
        return True
    expected = (settings.metrics_scraper_token or "").strip()
    if not expected or not authorization:
        return False
    if not authorization.lower().startswith("bearer "):
        return False
    presented = authorization[7:].strip()
    return len(presented) == len(expected) and hmac.compare_digest(presented, expected)


@router.get("/health/live")
async def liveness() -> dict[str, str]:
    """Process is running. Does not touch the database."""
    return {"status": "ok"}


@router.get("/health/ready")
async def readiness(
    db: AsyncSession = Depends(get_db),
    authorization: str | None = Header(default=None),
) -> dict[str, str]:
    """Process is running AND its dependencies (currently: Postgres) are reachable.

    Returns 503 (not 200 with an error body) on failure so load balancers
    and orchestrators treat it as a real readiness failure.

    The connected role is reported so a DATABASE_URL misconfiguration (wrong
    role, not just wrong host) is visible here rather than surfacing downstream
    as a permission error on a specific table. That is a genuinely useful
    deployment diagnostic, but it is also unauthenticated infrastructure
    detail, so outside local environments it is disclosed only to a caller
    holding METRICS_SCRAPER_TOKEN (audit L-1). `status` and `database` are
    unconditional, so probe behaviour is unchanged for load balancers.
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

    payload = {"status": "ok", "database": "reachable"}
    if _may_see_db_role(authorization):
        payload["db_user"] = db_user
    return payload


@router.get("/health/automation")
async def automation_health(
    authorization: str | None = Header(default=None),
) -> dict[str, object]:
    """Expose coarse automation health; gate runtime metadata outside local envs."""
    payload = public_automation_health_snapshot(await automation_health_snapshot())
    if _may_see_db_role(authorization):
        return payload
    return {
        "status": payload["status"],
        "maintenance": payload["maintenance"],
        "outbox_relay": payload["outbox_relay"],
        "scheduler": payload["scheduler"],
    }

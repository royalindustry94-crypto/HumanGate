"""Retry framework: backoff+jitter, permanent-failure classification, and
dead-letter routing (design doc §6).
"""

from __future__ import annotations

import random
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.operations import DeadLetterJob


def compute_backoff_seconds(
    attempt: int, *, base_seconds: int, multiplier: float, max_seconds: int
) -> float:
    """Exponential backoff with full jitter (design doc §6.1):
    delay = min(base * multiplier^(attempt-1), max), then
    actual = uniform(0, delay). Full jitter avoids synchronized retry
    storms when many jobs fail around the same time (e.g. a shared
    dependency outage).
    """
    if attempt < 1:
        attempt = 1
    raw = base_seconds * (multiplier ** (attempt - 1))
    capped = min(raw, max_seconds)
    # Retry jitter only -- spreading reconnects, not generating a secret.
    return random.uniform(0, capped)  # noqa: S311


def next_run_after(
    attempt: int, *, base_seconds: int, multiplier: float, max_seconds: int
) -> datetime:
    delay = compute_backoff_seconds(
        attempt, base_seconds=base_seconds, multiplier=multiplier, max_seconds=max_seconds
    )
    return datetime.now(UTC) + timedelta(seconds=delay)


# Known-transient error markers. Classification defaults to "not
# retryable" for anything unrecognized — fail safe rather than retrying
# an unknown-permanent error forever (design doc §6.3).
_RETRYABLE_MARKERS = (
    "timeout",
    "timed out",
    "connection reset",
    "connection refused",
    "temporarily unavailable",
    "lease lost",
    "rate limit",
    "503",
    "429",
)


def is_retryable(error_message: str) -> bool:
    lowered = error_message.lower()
    return any(marker in lowered for marker in _RETRYABLE_MARKERS)


async def route_to_dead_letter(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    related_table: str,
    related_id: uuid.UUID,
    job_type: str,
    payload: dict | None,
    failure_reason: str,
    attempt_count: int,
    first_failed_at: datetime,
) -> DeadLetterJob:
    """Write the DLQ record for an exhausted-retry or permanent failure.
    Does not commit — part of the caller's transaction, alongside the
    run/stage status change and the pipeline.failed event.
    """
    entry = DeadLetterJob(
        id=uuid.uuid4(),
        workspace_id=workspace_id,
        related_table=related_table,
        related_id=related_id,
        job_type=job_type,
        payload=payload,
        failure_reason=failure_reason,
        attempt_count=attempt_count,
        first_failed_at=first_failed_at,
        last_failed_at=datetime.now(UTC),
    )
    session.add(entry)
    await session.flush()
    return entry

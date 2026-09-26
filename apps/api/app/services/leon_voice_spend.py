"""Fail-closed spend gating for Leon's voice provider calls.

Reuses the workspace-scoped spend_caps/spend_reservations/spend_logs ledger
(app/models/config.py, app/models/spend.py) and its cap-check semantics --
the same tables app.orchestration.controller.reserve_spend/commit_spend/
release_spend use for the async content pipeline. A voice turn has no
pipeline run, no content item, and no authenticated workspace member to
scope a reservation to; migration 0059 seeds one fixed system profile +
workspace + spend cap for this route to reserve/commit/release against
instead of inventing a parallel ledger.

Like every other trusted background-system caller of this ledger (worker
dispatch only ever runs from the automation loop's AsyncSessionLocal-scoped
sessions -- see app/automation.py, app/orchestration/scheduler.py -- never
from a per-request RLS-scoped session), this uses the owner connection
(AsyncSessionLocal), not the RLS-scoped runtime session: spend_caps/
spend_reservations/spend_logs' RLS policies only grant SELECT to
authenticated workspace members (0003_workspace_config.py, 0010_spend.py),
which the voice route's shared-app-token auth can never satisfy -- there is
no Supabase-authenticated user or workspace membership behind it.

Each provider call reserves its own conservative worst-case cost before the
network call, commits the real cost (clamped to the reservation, same
fail-closed-against-overage rule as controller.commit_spend) on success, and
releases the reservation on failure. Getting a per-unit price estimate
slightly wrong does not defeat the cap -- it only makes the dollar ceiling
track usage a bit loosely -- so these are documented, operator-tunable
settings rather than a hardcoded pricing table.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.db.session import AsyncSessionLocal
from app.models.config import SpendCap
from app.models.enums import ReservationStatus
from app.models.spend import SpendLog, SpendReservation
from app.orchestration.controller import (
    spend_committed_plus_reserved,
    utc_day_start,
    utc_month_start,
)

# Must match migration 0059_leon_voice_spend.py's LEON_SYSTEM_WORKSPACE_ID.
LEON_SYSTEM_WORKSPACE_ID = uuid.UUID("00000000-0000-4000-8000-000000000002")

# Worst-case bound for STT reservation sizing. There is no hard audio
# *duration* cap today (only MAX_AUDIO_BYTES), and duration cannot be
# derived from compressed file size alone -- this bounds the reservation to
# a generous multiple of what a real conversational turn ever runs, so the
# cap is still meaningful rather than reserving an unbounded amount.
_STT_RESERVE_MAX_MINUTES = Decimal("12")
# Rough, deliberately conservative chars-per-token heuristic for bounding an
# LLM reservation before the real Anthropic token usage is known.
_CHARS_PER_TOKEN_ESTIMATE = 4
# Must match app.services.leon_voice._MAX_REPLY_TOKENS (the max_tokens sent
# to the Messages API) -- the hard ceiling on one reply's output tokens.
_LLM_RESERVE_MAX_OUTPUT_TOKENS = 300


class LeonSpendExceeded(RuntimeError):
    """A reservation was refused: the voice route's cap has no room left."""


async def _load_cap_for_update(session: AsyncSession) -> SpendCap | None:
    result = await session.execute(
        select(SpendCap)
        .where(SpendCap.workspace_id == LEON_SYSTEM_WORKSPACE_ID, SpendCap.provider.is_(None))
        .with_for_update()
    )
    return result.scalar_one_or_none()


async def reserve(*, provider: str, estimated_cost_usd: Decimal) -> uuid.UUID:
    """Reserve estimated cost for one provider call before making it.

    Fail-closed: raises LeonSpendExceeded rather than returning a
    reservation the caller could ignore, if there is no cap row (not
    seeded/migrated yet) or the reservation would exceed the daily or
    monthly cap. Returns the reservation id to commit or release later.
    """
    async with AsyncSessionLocal() as session:
        cap = await _load_cap_for_update(session)
        if cap is None:
            raise LeonSpendExceeded("voice spend cap is not configured")

        daily = await spend_committed_plus_reserved(
            session,
            workspace_id=LEON_SYSTEM_WORKSPACE_ID,
            provider=None,
            since=utc_day_start(),
        )
        monthly = await spend_committed_plus_reserved(
            session,
            workspace_id=LEON_SYSTEM_WORKSPACE_ID,
            provider=None,
            since=utc_month_start(),
        )
        daily_cap = Decimal(str(cap.daily_cap_usd))
        monthly_cap = Decimal(str(cap.monthly_cap_usd))
        if daily + estimated_cost_usd > daily_cap:
            raise LeonSpendExceeded("daily voice spend cap reached")
        if monthly + estimated_cost_usd > monthly_cap:
            raise LeonSpendExceeded("monthly voice spend cap reached")

        reservation = SpendReservation(
            id=uuid.uuid4(),
            workspace_id=LEON_SYSTEM_WORKSPACE_ID,
            content_item_id=None,
            pipeline_run_id=None,
            provider=provider,
            stage=None,
            estimated_cost_usd=estimated_cost_usd,
            status=ReservationStatus.RESERVED,
        )
        session.add(reservation)
        await session.commit()
        return reservation.id


async def commit(*, reservation_id: uuid.UUID, actual_cost_usd: Decimal) -> None:
    """Idempotent: a reservation already committed/released is left alone."""
    async with AsyncSessionLocal() as session:
        reservation = await session.get(SpendReservation, reservation_id)
        if reservation is None or reservation.status != ReservationStatus.RESERVED:
            return
        reserved = Decimal(str(reservation.estimated_cost_usd))
        actual = actual_cost_usd if actual_cost_usd >= 0 else Decimal("0")
        # Caps are enforced at reserve time; commit is fail-closed against a
        # provider-reported cost that somehow exceeds what was reserved.
        if actual > reserved:
            actual = reserved
        reservation.status = ReservationStatus.COMMITTED
        session.add(
            SpendLog(
                id=uuid.uuid4(),
                workspace_id=LEON_SYSTEM_WORKSPACE_ID,
                content_item_id=None,
                provider=reservation.provider,
                stage=None,
                cost_usd=actual,
                occurred_at=datetime.now(UTC),
            )
        )
        await session.commit()


async def release(*, reservation_id: uuid.UUID) -> None:
    """Idempotent: a reservation already committed/released is left alone."""
    async with AsyncSessionLocal() as session:
        reservation = await session.get(SpendReservation, reservation_id)
        if reservation is None or reservation.status != ReservationStatus.RESERVED:
            return
        reservation.status = ReservationStatus.RELEASED
        await session.commit()


# --- Per-call cost estimation -------------------------------------------


def stt_reserve_estimate_usd() -> Decimal:
    rate = Decimal(str(get_settings().leon_stt_cost_usd_per_minute))
    return (_STT_RESERVE_MAX_MINUTES * rate).quantize(Decimal("0.0001"))


def stt_actual_cost_usd(duration_seconds: float | None) -> Decimal:
    if duration_seconds is None or duration_seconds < 0:
        # Unknown real duration: charge the full reservation rather than
        # guess low, matching the fail-closed-against-overage clamp anyway.
        return stt_reserve_estimate_usd()
    rate = Decimal(str(get_settings().leon_stt_cost_usd_per_minute))
    minutes = Decimal(str(duration_seconds)) / Decimal("60")
    return (minutes * rate).quantize(Decimal("0.0001"))


def llm_reserve_estimate_usd(*, history: list[dict[str, str]], user_text: str) -> Decimal:
    settings = get_settings()
    input_chars = len(user_text) + sum(len(turn.get("content", "")) for turn in history)
    input_tokens = Decimal(input_chars) / _CHARS_PER_TOKEN_ESTIMATE
    output_tokens = Decimal(_LLM_RESERVE_MAX_OUTPUT_TOKENS)
    input_cost = input_tokens / 1000 * Decimal(str(settings.leon_llm_input_cost_usd_per_1k_tokens))
    output_cost = (
        output_tokens / 1000 * Decimal(str(settings.leon_llm_output_cost_usd_per_1k_tokens))
    )
    return (input_cost + output_cost).quantize(Decimal("0.0001"))


def llm_actual_cost_usd(*, input_tokens: int, output_tokens: int) -> Decimal:
    settings = get_settings()
    input_cost = (
        Decimal(max(input_tokens, 0))
        / 1000
        * Decimal(str(settings.leon_llm_input_cost_usd_per_1k_tokens))
    )
    output_cost = (
        Decimal(max(output_tokens, 0))
        / 1000
        * Decimal(str(settings.leon_llm_output_cost_usd_per_1k_tokens))
    )
    return (input_cost + output_cost).quantize(Decimal("0.0001"))


def tts_cost_usd(text: str) -> Decimal:
    rate = Decimal(str(get_settings().leon_tts_cost_usd_per_1k_chars))
    return (Decimal(len(text)) / 1000 * rate).quantize(Decimal("0.0001"))

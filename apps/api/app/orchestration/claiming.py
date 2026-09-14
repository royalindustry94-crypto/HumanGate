"""Atomic worker claiming (Milestone 4 Workstream 2 + WS4 priority/budgets).

A worker pulls at most one eligible stage assignment per claim. The whole
operation is one transaction on the service-role session (workers use
machine auth, not user JWT, so RLS user-scoping does not apply; workspace
scoping is enforced in the query predicate). Locking:

- the worker's own registry row is locked ``FOR UPDATE`` so its capacity
  math is serialized (two concurrent claims by the same worker cannot both
  read load=N);
- candidate assignments are locked ``FOR UPDATE SKIP LOCKED`` so N
  workers polling concurrently each grab *different* pending rows — the
  guarantee that two workers cannot claim one job.

WS4: candidates are ordered by effective priority (base + age boost)
descending, then ``created_at`` ascending. Rows whose provider concurrency
budget is exhausted are skipped so one saturated provider cannot block
another stage.

Every attempt returns a ``ClaimResult`` and is recorded in
``stage_claim_audit``. Only ``GRANTED`` hands out work; capacity / stale /
offline / no-work are normal, audited non-grants — never silent failures.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from sqlalchemy import or_, select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.models.assignments import StageAssignment
from app.models.claim_audit import StageClaimAudit
from app.models.enums import (
    ClaimOutcome,
    ReservationStatus,
    StageAssignmentStatus,
    WorkerStatus,
)
from app.models.spend import SpendReservation
from app.models.workers import WorkerRegistration
from app.orchestration.events.envelope import child_span
from app.orchestration.events.types import STAGE_ASSIGNED
from app.orchestration.outbox import emit
from app.orchestration.priority import effective_priority_expr
from app.orchestration.provider_budgets import has_provider_capacity

logger = logging.getLogger(__name__)

# Runaway guard on the candidate scan. Not the termination condition -- the
# loop ends when the pair-excluding query finds nothing -- so this only
# trips on a bug, and is sized far above any real provider count.
_CLAIM_SCAN_GUARD = 10_000

# Back-compat module aliases; prefer Settings at call sites.
CLAIM_HEARTBEAT_MAX_AGE_SECONDS = 90
CLAIM_LEASE_SECONDS = 60


def _heartbeat_max_age() -> int:
    return get_settings().worker_offline_after_seconds


def _claim_lease_seconds() -> int:
    return get_settings().assignment_lease_seconds


@dataclass(frozen=True)
class ClaimResult:
    assignment: StageAssignment | None
    outcome: ClaimOutcome
    reason: str


async def _record(
    session: AsyncSession,
    *,
    worker: WorkerRegistration,
    outcome: ClaimOutcome,
    reason: str,
    assignment: StageAssignment | None,
    stage: str | None,
    request_workspace_id: uuid.UUID | None = None,
) -> None:
    workspace_id = (
        assignment.workspace_id
        if assignment is not None
        else worker.workspace_id or request_workspace_id
    )
    if workspace_id is None:
        return
    session.add(
        StageClaimAudit(
            id=uuid.uuid4(),
            workspace_id=workspace_id,
            assignment_id=assignment.id if assignment is not None else None,
            worker_id=worker.id,
            outcome=outcome,
            stage=stage,
            detail=reason,
            correlation_id=assignment.correlation_id if assignment is not None else None,
        )
    )


async def claim_assignment(
    session: AsyncSession,
    *,
    worker_id: uuid.UUID,
    now: datetime | None = None,
    claim_token: uuid.UUID | None = None,
    request_workspace_id: uuid.UUID | None = None,
) -> ClaimResult:
    """Claim one eligible assignment for ``worker_id`` inside the caller's
    transaction. ``now`` is injectable for clock-controlled tests.

    ``claim_token`` makes a retried request idempotent: if the worker
    already holds an assignment (DISPATCHED, claimed by it) whose
    idempotency short-circuit matches, that same assignment is returned
    rather than consuming a second row.
    """
    now = now or datetime.now(UTC)

    # 1. Lock the worker row — serializes this worker's capacity accounting.
    worker = await session.get(WorkerRegistration, worker_id, with_for_update=True)
    if worker is None:
        # Credential authenticated but the registry row is gone: ineligible.
        return ClaimResult(None, ClaimOutcome.INELIGIBLE, "worker not found")

    # 1a. Idempotent replay: return the assignment already held under this token.
    if claim_token is not None:
        held_where = [
            StageAssignment.claimed_by == worker.id,
            StageAssignment.claim_token == claim_token,
            StageAssignment.status == StageAssignmentStatus.DISPATCHED,
        ]
        if worker.workspace_id is not None:
            held_where.append(StageAssignment.workspace_id == worker.workspace_id)
        held = await session.execute(select(StageAssignment).where(*held_where))
        existing = held.scalar_one_or_none()
        if existing is not None:
            await _record(
                session,
                worker=worker,
                outcome=ClaimOutcome.GRANTED,
                reason="idempotent replay",
                assignment=existing,
                stage=existing.stage,
                request_workspace_id=request_workspace_id,
            )
            return ClaimResult(existing, ClaimOutcome.GRANTED, "idempotent replay")

    # 2. Worker eligibility (status / heartbeat freshness / capacity / drain).
    if worker.drain:
        reason = "worker is draining"
        await _record(
            session,
            worker=worker,
            outcome=ClaimOutcome.INELIGIBLE,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, ClaimOutcome.INELIGIBLE, reason)

    if worker.deregistered_at is not None or worker.status != WorkerStatus.ONLINE:
        reason = f"worker status is {worker.status.value}, not online"
        await _record(
            session,
            worker=worker,
            outcome=ClaimOutcome.INELIGIBLE,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, ClaimOutcome.INELIGIBLE, reason)

    if worker.last_heartbeat_at is None or (
        (now - worker.last_heartbeat_at).total_seconds() >= _heartbeat_max_age()
    ):
        reason = "heartbeat is stale"
        await _record(
            session,
            worker=worker,
            outcome=ClaimOutcome.INELIGIBLE,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, ClaimOutcome.INELIGIBLE, reason)

    if worker.current_load >= worker.max_concurrency:
        reason = "worker at maximum concurrency"
        await _record(
            session,
            worker=worker,
            outcome=ClaimOutcome.CAPACITY,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, ClaimOutcome.CAPACITY, reason)

    # 3. Select eligible PENDING assignments ordered by effective priority
    #    (WS4), then created_at. One row at a time under SAVEPOINT so an
    #    over-budget skip releases its lock and does not starve other
    #    claimers (a batch FOR UPDATE would hold every candidate until
    #    commit). SKIP LOCKED so concurrent claimers never contend.
    if not worker.supported_stages:
        reason = "worker supports no stages"
        await _record(
            session,
            worker=worker,
            outcome=ClaimOutcome.NO_WORK,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, ClaimOutcome.NO_WORK, reason)

    from sqlalchemy import text as sa_text

    # The scan runs until the pair-excluding candidate query is exhausted,
    # rather than for a fixed number of passes.
    #
    # Each pass either claims, finds nothing, or retires exactly one
    # (workspace_id, provider) pair -- and the candidate query excludes every
    # retired pair, so the candidate set strictly shrinks and the loop
    # terminates. A fixed bound cannot do this safely: `range(batch)` stopped
    # after 32 pairs and reported `capacity` with a claimable row still behind
    # them, and deriving the bound from a COUNT of provider_concurrency_budgets
    # only moved the problem -- that count is a READ COMMITTED snapshot taken
    # before the scan, so budgets committed mid-claim could still outrun it,
    # and for a global worker it scanned every tenant's budgets on every poll,
    # including no-work polls.
    #
    # `_CLAIM_SCAN_GUARD` is a runaway guard, not the termination condition; it
    # is only reachable if a retired pair somehow fails to be excluded, which
    # would be a bug worth seeing in the logs rather than looping on.
    effective = effective_priority_expr(
        StageAssignment.priority, StageAssignment.created_at, now=now
    )
    # Saturated (workspace_id, provider) pairs discovered during this claim. A
    # candidate is skipped precisely because its provider budget is exhausted,
    # and that verdict applies to every sibling row sharing the pair, so
    # excluding the pair retires all of them at once instead of re-discovering
    # the same full provider once per pending row (audit M-2).
    saturated: list[tuple[uuid.UUID, str]] = []
    assignment: StageAssignment | None = None
    saw_provider_budget_block = False

    for _ in range(_CLAIM_SCAN_GUARD):
        await session.execute(sa_text("SAVEPOINT claim_candidate"))
        where = [
            StageAssignment.status == StageAssignmentStatus.PENDING,
            StageAssignment.stage.in_(list(worker.supported_stages)),
        ]
        if worker.workspace_id is not None:
            where.append(StageAssignment.workspace_id == worker.workspace_id)
        if saturated:
            # `provider IS NULL` is checked first and kept: has_provider_capacity
            # always grants capacity to a null/blank provider, so such rows are
            # never saturated -- and a bare NOT IN would silently drop them,
            # since `NULL NOT IN (...)` is NULL, not true.
            where.append(
                or_(
                    StageAssignment.provider.is_(None),
                    tuple_(StageAssignment.workspace_id, StageAssignment.provider).notin_(
                        saturated
                    ),
                )
            )
        candidate = await session.execute(
            select(StageAssignment)
            .where(*where)
            .order_by(effective.desc(), StageAssignment.created_at.asc())
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        row = candidate.scalar_one_or_none()
        if row is None:
            await session.execute(sa_text("RELEASE SAVEPOINT claim_candidate"))
            break
        # Scope the budget check to the candidate assignment's own workspace
        # (always set — StageAssignment is workspace-scoped) rather than the
        # worker's, since worker_registry.workspace_id is a nullable pin
        # (None for a worker serving all workspaces), not a guarantee.
        if not await has_provider_capacity(
            session, workspace_id=row.workspace_id, provider=row.provider
        ):
            saw_provider_budget_block = True
            # Read both before the rollback below, so nothing depends on the
            # ORM state of `row` surviving it.
            #
            # has_provider_capacity grants capacity unconditionally for a
            # null/blank provider, so reaching here means row.provider is set.
            # Narrowed rather than asserted so an unexpected null stops the
            # scan instead of looping over the same row until `batch` runs out.
            blocked_pair = (row.workspace_id, row.provider)
            # Release the assignment (+ budget) lock so other claimers can
            # proceed on sibling pending rows.
            await session.execute(sa_text("ROLLBACK TO SAVEPOINT claim_candidate"))
            await session.execute(sa_text("RELEASE SAVEPOINT claim_candidate"))
            blocked_workspace_id, blocked_provider = blocked_pair
            if not blocked_provider:
                break
            saturated.append((blocked_workspace_id, blocked_provider))
            continue
        assignment = row
        await session.execute(sa_text("RELEASE SAVEPOINT claim_candidate"))
        break
    else:
        # Unreachable unless a retired pair failed to be excluded. Surface it
        # rather than silently reporting no work.
        logger.warning(
            "claim candidate scan hit the runaway guard",
            extra={
                "worker_id": str(worker.id),
                "saturated_pairs": len(saturated),
                "guard": _CLAIM_SCAN_GUARD,
            },
        )

    if assignment is None:
        reason = (
            "provider budget exhausted" if saw_provider_budget_block else "no eligible assignment"
        )
        outcome = ClaimOutcome.CAPACITY if saw_provider_budget_block else ClaimOutcome.NO_WORK
        await _record(
            session,
            worker=worker,
            outcome=outcome,
            reason=reason,
            assignment=None,
            stage=None,
            request_workspace_id=request_workspace_id,
        )
        return ClaimResult(None, outcome, reason)

    # 3a. Spend gate at ownership transfer. dispatch_stage only reserves when
    #     it assigns a worker directly; a PENDING row claimed here has no
    #     reservation yet, so the cap must be enforced now. Fail closed: if the
    #     workspace is over budget the claim is a normal audited non-grant and
    #     the assignment stays PENDING (reserve_spend pauses the run).
    from app.models.pipeline import PipelineRun as _PipelineRun
    from app.orchestration import controller as _controller

    run_for_spend = await session.get(_PipelineRun, assignment.pipeline_run_id)
    if run_for_spend is not None:
        open_reservation = (
            await session.execute(
                select(SpendReservation).where(
                    SpendReservation.pipeline_run_id == assignment.pipeline_run_id,
                    SpendReservation.stage == assignment.stage,
                    SpendReservation.status == ReservationStatus.RESERVED,
                )
            )
        ).scalar_one_or_none()
        if open_reservation is None:
            reservation = await _controller.reserve_spend(
                session,
                run=run_for_spend,
                stage=assignment.stage,
                provider=assignment.provider or "draft_desk",
                estimated_cost_usd=Decimal(str(get_settings().default_stage_estimate_usd)),
            )
            if reservation is None:
                reason = "workspace spend cap reached"
                await _record(
                    session,
                    worker=worker,
                    outcome=ClaimOutcome.CAPACITY,
                    reason=reason,
                    assignment=assignment,
                    stage=assignment.stage,
                    request_workspace_id=request_workspace_id,
                )
                return ClaimResult(None, ClaimOutcome.CAPACITY, reason)

    # 4. Mutate assignment + worker load in the SAME transaction.
    lease_seconds = _claim_lease_seconds()
    trace_id, span_id = child_span(assignment.trace_id)
    assignment.status = StageAssignmentStatus.DISPATCHED
    assignment.worker_id = worker.id
    assignment.claimed_by = worker.id
    assignment.claimed_at = now
    assignment.dispatched_at = now
    assignment.lease_expires_at = now + timedelta(seconds=lease_seconds)
    assignment.lease_started_at = now
    assignment.lease_extension_count = 0
    assignment.claim_count = (assignment.claim_count or 0) + 1
    assignment.claim_token = claim_token
    assignment.trace_id = trace_id

    worker.current_load += 1
    if worker.current_load >= worker.max_concurrency:
        worker.status = WorkerStatus.BUSY

    await _record(
        session,
        worker=worker,
        outcome=ClaimOutcome.GRANTED,
        reason="claimed",
        assignment=assignment,
        stage=assignment.stage,
        request_workspace_id=request_workspace_id,
    )
    await emit(
        session,
        event_type=STAGE_ASSIGNED,
        workspace_id=assignment.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=assignment.pipeline_run_id,
        correlation_id=assignment.correlation_id or uuid.uuid4(),
        trace_id=trace_id,
        span_id=span_id,
        payload={
            "stage": assignment.stage,
            "attempt_number": assignment.attempt_number,
            "worker_id": str(worker.id),
            "assignment_id": str(assignment.id),
            "via": "claim",
            "priority": assignment.priority,
            "provider": assignment.provider,
        },
        produced_by="claiming",
    )
    await session.flush()
    return ClaimResult(assignment, ClaimOutcome.GRANTED, "claimed")

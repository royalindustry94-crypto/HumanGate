"""Execution controller: advances pipeline runs through their workflow
definition, handles failures/retries, the human review gate, and spend
protection hooks (design doc §2, §8, §9).

This is the one place cross-cutting policy decisions are made; stage
execution itself belongs to workers (out of scope this milestone).
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.config import SpendCap
from app.models.content import ContentItem
from app.models.enums import (
    ContentStage,
    JobType,
    PauseReason,
    PipelineRunStatus,
    ReservationStatus,
    ReviewGateStatus,
    StageAssignmentStatus,
    WorkflowTransitionTrigger,
)
from app.models.events import OutboxEvent
from app.models.pipeline import PipelineRun, PipelineStageRun
from app.models.review_gate import ReviewGate
from app.models.scheduling import JobSchedule
from app.models.spend import SpendLog, SpendReservation
from app.models.workflow import WorkflowDefinition, WorkflowStage, WorkflowTransition
from app.models.workspace import Workspace
from app.orchestration.events.envelope import child_span
from app.orchestration.events.types import (
    PIPELINE_CANCELLED,
    PIPELINE_FAILED,
    PIPELINE_SUCCEEDED,
    REVIEW_ESCALATED,
    REVIEW_REQUESTED,
    REVIEW_TIMED_OUT,
    SPEND_BUDGET_EXCEEDED,
    SPEND_COMMITTED,
    SPEND_RELEASED,
    SPEND_RESERVED,
    STAGE_FAILED,
)
from app.orchestration.outbox import emit
from app.orchestration.priority import base_priority_for_tier
from app.orchestration.retry import compute_backoff_seconds, is_retryable, route_to_dead_letter

logger = logging.getLogger(__name__)

DEFAULT_REVIEW_TIMEOUT_HOURS = 48


# --- condition DSL (design doc §2.4) -----------------------------------
# Restricted, whitelisted — never arbitrary code. A condition is either
# None (unconditional) or {"field": "<dotted path in context>",
# "op": "eq"|"ne"|"gt"|"gte"|"lt"|"lte"|"in", "value": <json>}.
_OPS = {
    "eq": lambda a, b: a == b,
    "ne": lambda a, b: a != b,
    "gt": lambda a, b: a is not None and a > b,
    "gte": lambda a, b: a is not None and a >= b,
    "lt": lambda a, b: a is not None and a < b,
    "lte": lambda a, b: a is not None and a <= b,
    "in": lambda a, b: a in b,
}


def evaluate_condition(condition: dict | None, context: dict) -> bool:
    if condition is None:
        return True
    field_path = condition["field"].split(".")
    value: Any = context
    for part in field_path:
        value = value.get(part) if isinstance(value, dict) else None
    op = _OPS.get(condition["op"])
    if op is None:
        raise ValueError(f"unsupported condition operator: {condition['op']}")
    return op(value, condition["value"])


def _correlation_id(run: PipelineRun) -> uuid.UUID:
    """`correlation_id` is nullable at the schema level but is always set,
    once, in `start_run()`. The fallback here only matters for the same
    edge case the pre-existing `run.correlation_id if run else uuid.uuid4()`
    call sites already guarded against — it never masks a real bug, it just
    keeps `None` from reaching `emit()`, which requires a real UUID.
    """
    return run.correlation_id or uuid.uuid4()


def _definition_id(run: PipelineRun) -> uuid.UUID:
    """`definition_id` is pinned once in `start_run()` and never cleared.
    A run reaching any post-start call site without one is a genuine
    invariant violation, not a normal transient state — fail loudly rather
    than silently substituting a value that can't be invented.
    """
    if run.definition_id is None:
        raise ValueError(f"pipeline run {run.id} has no definition_id pinned")
    return run.definition_id


async def _find_transition(
    session: AsyncSession,
    *,
    definition_id: uuid.UUID,
    from_stage: str,
    trigger: WorkflowTransitionTrigger,
    context: dict,
) -> WorkflowTransition | None:
    result = await session.execute(
        select(WorkflowTransition)
        .where(
            WorkflowTransition.definition_id == definition_id,
            WorkflowTransition.from_stage == from_stage,
            WorkflowTransition.trigger == trigger,
        )
        .order_by(WorkflowTransition.priority.asc())
    )
    for transition in result.scalars().all():
        if evaluate_condition(transition.condition, context):
            return transition
    return None


async def _get_stage_def(
    session: AsyncSession, *, definition_id: uuid.UUID, stage_key: str
) -> WorkflowStage:
    result = await session.execute(
        select(WorkflowStage).where(
            WorkflowStage.definition_id == definition_id, WorkflowStage.stage_key == stage_key
        )
    )
    stage = result.scalar_one_or_none()
    if stage is None:
        raise ValueError(
            f"workflow_stages has no row for stage {stage_key} in definition {definition_id}"
        )
    return stage


async def enqueue_stage(
    session: AsyncSession,
    *,
    run: PipelineRun,
    stage_key: str,
    attempt: int = 0,
    run_after: datetime | None = None,
) -> JobSchedule:
    workspace = await session.get(Workspace, run.workspace_id)
    priority = base_priority_for_tier(workspace.priority_tier if workspace is not None else 0)
    job = JobSchedule(
        id=uuid.uuid4(),
        workspace_id=run.workspace_id,
        job_type=JobType.STAGE if attempt == 0 else JobType.RETRY,
        # stage jobs carry the stage_key here (see scheduler.process_leased_job)
        ref_table=stage_key,
        ref_id=run.id,
        run_after=run_after or datetime.now(UTC),
        attempt=attempt,
        priority=priority,
        correlation_id=_correlation_id(run),
        trace_id=run.trace_id,
    )
    session.add(job)
    await session.flush()
    return job


# --- run lifecycle -------------------------------------------------------


async def start_run(
    session: AsyncSession,
    *,
    run: PipelineRun,
    definition: WorkflowDefinition,
) -> None:
    """Kick off a pipeline run: pin the definition, enqueue the first
    stage. Definition is pinned by FK at run creation — later edits to
    the named workflow (new versions) never affect this run, which is
    what preserves deterministic replay (amendment 4).
    """
    run.definition_id = definition.id
    if run.correlation_id is None:
        run.correlation_id = uuid.uuid4()
    stages = await session.execute(
        select(WorkflowStage)
        .where(WorkflowStage.definition_id == definition.id)
        .order_by(WorkflowStage.ordinal)
    )
    first_stage = stages.scalars().first()
    if first_stage is None:
        raise ValueError(f"workflow_definitions {definition.id} has no stages")
    run.current_stage = first_stage.stage_key
    run.started_at = datetime.now(UTC)
    await enqueue_stage(session, run=run, stage_key=first_stage.stage_key)


async def _advance_or_finish(
    session: AsyncSession,
    *,
    run: PipelineRun,
    from_stage: str,
    trigger: WorkflowTransitionTrigger,
    context: dict,
) -> None:
    transition = await _find_transition(
        session,
        definition_id=_definition_id(run),
        from_stage=from_stage,
        trigger=trigger,
        context=context,
    )
    if transition is None:
        # No outgoing transition — check whether the current stage is itself
        # terminal (single-stage or last-stage workflow with no explicit
        # outgoing edge). If so, the run is complete; otherwise it's a
        # workflow-definition authoring error.
        current_stage_def = await _get_stage_def(
            session, definition_id=_definition_id(run), stage_key=from_stage
        )
        if current_stage_def.is_terminal:
            trace_id, span_id = child_span(run.trace_id)
            run.trace_id = trace_id
            run.status = PipelineRunStatus.SUCCEEDED
            run.completed_at = datetime.now(UTC)
            run.current_stage = ContentStage(from_stage)
            await emit(
                session,
                event_type=PIPELINE_SUCCEEDED,
                workspace_id=run.workspace_id,
                aggregate_type="pipeline_run",
                aggregate_id=run.id,
                correlation_id=_correlation_id(run),
                trace_id=trace_id,
                span_id=span_id,
                payload={"final_stage": from_stage},
                produced_by="controller",
            )
            return
        raise ValueError(
            f"no matching transition from stage={from_stage} trigger={trigger} "
            f"for definition {run.definition_id}"
        )
    to_stage_def = await _get_stage_def(
        session, definition_id=_definition_id(run), stage_key=transition.to_stage
    )
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    run.current_stage = transition.to_stage

    if to_stage_def.is_terminal:
        run.status = PipelineRunStatus.SUCCEEDED
        run.completed_at = datetime.now(UTC)
        await emit(
            session,
            event_type=PIPELINE_SUCCEEDED,
            workspace_id=run.workspace_id,
            aggregate_type="pipeline_run",
            aggregate_id=run.id,
            correlation_id=_correlation_id(run),
            trace_id=trace_id,
            span_id=span_id,
            payload={"final_stage": transition.to_stage},
            produced_by="controller",
        )
    elif to_stage_def.is_review_gate:
        await pause_for_review(
            session,
            run=run,
            stage_key=to_stage_def.stage_key,
            timeout_seconds=to_stage_def.timeout_seconds,
        )
    else:
        await enqueue_stage(session, run=run, stage_key=to_stage_def.stage_key)


async def handle_stage_success(
    session: AsyncSession,
    *,
    run: PipelineRun,
    stage: str,
    result_context: dict | None = None,
) -> None:
    # Terminal / cancelled runs must not re-enter the workflow graph —
    # orphan job_schedule or stale worker submits must not resurrect gates.
    status = run.status.value if hasattr(run.status, "value") else str(run.status)
    if status in {
        PipelineRunStatus.SUCCEEDED.value,
        PipelineRunStatus.FAILED.value,
        PipelineRunStatus.CANCELLED.value,
    }:
        logger.warning(
            "stage_success_ignored_terminal_run",
            extra={
                "pipeline_run_id": str(run.id),
                "status": status,
                "stage": stage,
            },
        )
        return
    await _advance_or_finish(
        session,
        run=run,
        from_stage=stage,
        trigger=WorkflowTransitionTrigger.ON_SUCCESS,
        context=result_context or {},
    )


async def handle_stage_failure(
    session: AsyncSession,
    *,
    run: PipelineRun,
    stage: str,
    attempt_number: int,
    error_message: str,
) -> None:
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=STAGE_FAILED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={"stage": stage, "attempt_number": attempt_number, "error": error_message},
        produced_by="controller",
    )

    stage_def = await _get_stage_def(session, definition_id=_definition_id(run), stage_key=stage)
    retryable = is_retryable(error_message)
    if retryable and attempt_number < stage_def.max_attempts:
        delay = compute_backoff_seconds(
            attempt_number + 1,
            base_seconds=stage_def.backoff_base_seconds,
            multiplier=stage_def.backoff_multiplier,
            max_seconds=stage_def.backoff_max_seconds,
        )
        await enqueue_stage(
            session,
            run=run,
            stage_key=stage,
            attempt=attempt_number,
            run_after=datetime.now(UTC) + timedelta(seconds=delay),
        )
        return

    # Exhausted retries or permanent — dead-letter and fail the run.
    await route_to_dead_letter(
        session,
        workspace_id=run.workspace_id,
        related_table="pipeline_runs",
        related_id=run.id,
        job_type=f"stage:{stage}",
        payload={"stage": stage, "attempt_number": attempt_number},
        failure_reason=error_message,
        attempt_count=attempt_number,
        first_failed_at=run.started_at or datetime.now(UTC),
    )
    await _fail_run(session, run=run, reason=error_message)
    if stage_def.compensation_stage_key is not None:
        await _trigger_compensation(session, run=run)


async def handle_stage_timeout(
    session: AsyncSession, *, pipeline_run_id: uuid.UUID, stage: str
) -> None:
    run = await session.get(PipelineRun, pipeline_run_id)
    if run is None or run.current_stage != stage or run.status not in ("running",):
        return  # already advanced past this stage — stale timeout, no-op
    result = await session.execute(
        select(func.count(PipelineStageRun.id)).where(
            PipelineStageRun.pipeline_run_id == pipeline_run_id, PipelineStageRun.stage == stage
        )
    )
    attempt_number = (result.scalar_one() or 0) + 1
    await handle_stage_failure(
        session, run=run, stage=stage, attempt_number=attempt_number, error_message="timeout"
    )


async def _fail_run(session: AsyncSession, *, run: PipelineRun, reason: str) -> None:
    run.status = PipelineRunStatus.FAILED
    run.completed_at = datetime.now(UTC)
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=PIPELINE_FAILED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={"reason": reason},
        produced_by="controller",
    )
    await release_all_reservations(session, run=run)


# --- cancellation ---------------------------------------------------------


async def cancel_run(session: AsyncSession, *, run: PipelineRun) -> None:
    if run.status in ("succeeded", "failed", "cancelled"):
        return  # idempotent — already terminal
    from app.models.assignments import StageAssignment

    open_assignments = await session.execute(
        select(StageAssignment).where(
            StageAssignment.pipeline_run_id == run.id,
            StageAssignment.status.in_(
                [
                    StageAssignmentStatus.PENDING,
                    StageAssignmentStatus.DISPATCHED,
                    StageAssignmentStatus.ACKNOWLEDGED,
                ]
            ),
        )
    )
    for assignment in open_assignments.scalars().all():
        assignment.status = StageAssignmentStatus.CANCELLED

    run.status = PipelineRunStatus.CANCELLED
    run.completed_at = datetime.now(UTC)
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await release_all_reservations(session, run=run)
    await emit(
        session,
        event_type=PIPELINE_CANCELLED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={},
        produced_by="controller",
    )


# --- compensation (design doc §2.10) ---------------------------------------


async def _trigger_compensation(session: AsyncSession, *, run: PipelineRun) -> None:
    """Walk completed stages backwards and enqueue compensation stages for
    any that declared one. M4 has exactly one concrete compensation
    (releasing spend reservations, already done in _fail_run); this
    mechanism is otherwise designed-for and exercised by tests, ready for
    external-effect compensations once publishing exists.
    """
    run.status = PipelineRunStatus.COMPENSATING
    completed = await session.execute(
        select(PipelineStageRun)
        .where(PipelineStageRun.pipeline_run_id == run.id, PipelineStageRun.status == "succeeded")
        .order_by(PipelineStageRun.completed_at.desc())
    )
    for stage_run in completed.scalars().all():
        stage_def = await _get_stage_def(
            session, definition_id=_definition_id(run), stage_key=stage_run.stage
        )
        if stage_def.compensation_stage_key is not None:
            job = JobSchedule(
                id=uuid.uuid4(),
                workspace_id=run.workspace_id,
                job_type=JobType.COMPENSATION,
                ref_table=stage_def.compensation_stage_key,
                ref_id=run.id,
                run_after=datetime.now(UTC),
                correlation_id=_correlation_id(run),
                trace_id=run.trace_id,
            )
            session.add(job)
    # Compensation enqueued; run remains terminally failed once done.
    run.status = PipelineRunStatus.FAILED
    await session.flush()


async def run_compensation_stage(
    session: AsyncSession, *, pipeline_run_id: uuid.UUID, stage: str
) -> None:
    """Placeholder-free by design: with no external-effect stages defined
    yet (publishing etc. is out of scope), the only real compensation
    action available is spend release, already performed in _fail_run.
    This function exists so job_schedule's COMPENSATION job_type has a
    real handler to call, and logs the no-op explicitly rather than
    silently dropping the job.
    """
    import logging

    logging.getLogger(__name__).info(
        "compensation stage executed (no external effects to compensate this milestone)",
        extra={"pipeline_run_id": str(pipeline_run_id), "stage": stage},
    )


# --- human review gate (design doc §8) -------------------------------------


async def pause_for_review(
    session: AsyncSession,
    *,
    run: PipelineRun,
    stage_key: str,
    timeout_seconds: int,
) -> ReviewGate:
    status = run.status.value if hasattr(run.status, "value") else str(run.status)
    if status in {
        PipelineRunStatus.SUCCEEDED.value,
        PipelineRunStatus.FAILED.value,
        PipelineRunStatus.CANCELLED.value,
    }:
        raise ValueError(f"cannot open review gate on terminal run status={status}")
    existing_awaiting = (
        await session.execute(
            select(ReviewGate).where(
                ReviewGate.pipeline_run_id == run.id,
                ReviewGate.status == ReviewGateStatus.AWAITING,
            )
        )
    ).scalar_one_or_none()
    if existing_awaiting is not None:
        logger.info(
            "review_gate_already_awaiting",
            extra={
                "pipeline_run_id": str(run.id),
                "gate_id": str(existing_awaiting.id),
            },
        )
        return existing_awaiting

    item = await session.get(ContentItem, run.content_item_id)
    if item is None:
        raise ValueError(f"pipeline run {run.id} has no content item")

    run.status = PipelineRunStatus.PAUSED
    run.pause_reason = PauseReason.REVIEW_GATE.value
    timeout_at = datetime.now(UTC) + timedelta(seconds=timeout_seconds)
    gate = ReviewGate(
        id=uuid.uuid4(),
        workspace_id=run.workspace_id,
        pipeline_run_id=run.id,
        content_version_id=item.current_version_id,
        stage=stage_key,
        requested_at=datetime.now(UTC),
        timeout_at=timeout_at,
    )
    session.add(gate)
    await session.flush()
    session.add(
        JobSchedule(
            id=uuid.uuid4(),
            workspace_id=run.workspace_id,
            job_type=JobType.REVIEW_TIMEOUT,
            ref_table="review_gates",
            ref_id=gate.id,
            run_after=timeout_at,
            correlation_id=_correlation_id(run),
            trace_id=run.trace_id,
        )
    )
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=REVIEW_REQUESTED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={"stage": stage_key, "review_gate_id": str(gate.id)},
        produced_by="controller",
    )
    return gate


async def resume_from_review(session: AsyncSession, *, gate: ReviewGate, approved: bool) -> None:
    if gate.status != ReviewGateStatus.AWAITING:
        return  # idempotent — already decided
    run = await session.get(PipelineRun, gate.pipeline_run_id)
    if run is None:
        raise ValueError(f"review_gate {gate.id} has no pipeline_run")
    gate.status = ReviewGateStatus.APPROVED if approved else ReviewGateStatus.REJECTED
    gate.decided_at = datetime.now(UTC)
    if not approved:
        # Private Beta / product rule: rejection without an explicit
        # on_review_rejected edge fails the run loudly (no silent publish,
        # no fake "succeeded" terminal).
        reject_transition = await _find_transition(
            session,
            definition_id=_definition_id(run),
            from_stage=gate.stage,
            trigger=WorkflowTransitionTrigger.ON_REVIEW_REJECTED,
            context={},
        )
        if reject_transition is None:
            run.pause_reason = None
            await _fail_run(session, run=run, reason="review_rejected")
            return
    run.status = PipelineRunStatus.RUNNING
    run.pause_reason = None
    trigger = (
        WorkflowTransitionTrigger.ON_REVIEW_APPROVED
        if approved
        else WorkflowTransitionTrigger.ON_REVIEW_REJECTED
    )
    await _advance_or_finish(session, run=run, from_stage=gate.stage, trigger=trigger, context={})


async def handle_review_timeout(session: AsyncSession, *, review_gate_id: uuid.UUID) -> None:
    gate = await session.get(ReviewGate, review_gate_id)
    if gate is None or gate.status != ReviewGateStatus.AWAITING:
        return  # already decided — stale timeout, no-op
    run = await session.get(PipelineRun, gate.pipeline_run_id)
    trace_id, span_id = child_span(run.trace_id if run else None)
    if gate.escalation_level == 0:
        gate.status = ReviewGateStatus.ESCALATED
        gate.escalation_level += 1
        # Re-arm a second, longer timeout before falling through to a
        # terminal decision (design doc §8.6).
        new_timeout = datetime.now(UTC) + timedelta(hours=DEFAULT_REVIEW_TIMEOUT_HOURS)
        gate.timeout_at = new_timeout
        session.add(
            JobSchedule(
                id=uuid.uuid4(),
                workspace_id=gate.workspace_id,
                job_type=JobType.REVIEW_TIMEOUT,
                ref_table="review_gates",
                ref_id=gate.id,
                run_after=new_timeout,
            )
        )
        await emit(
            session,
            event_type=REVIEW_ESCALATED,
            workspace_id=gate.workspace_id,
            aggregate_type="pipeline_run",
            aggregate_id=gate.pipeline_run_id,
            correlation_id=_correlation_id(run) if run else uuid.uuid4(),
            trace_id=trace_id,
            span_id=span_id,
            payload={"review_gate_id": str(gate.id), "escalation_level": gate.escalation_level},
            produced_by="controller",
        )
    else:
        gate.status = ReviewGateStatus.TIMED_OUT
        await emit(
            session,
            event_type=REVIEW_TIMED_OUT,
            workspace_id=gate.workspace_id,
            aggregate_type="pipeline_run",
            aggregate_id=gate.pipeline_run_id,
            correlation_id=_correlation_id(run) if run else uuid.uuid4(),
            trace_id=trace_id,
            span_id=span_id,
            payload={"review_gate_id": str(gate.id)},
            produced_by="controller",
        )
        if run is not None:
            await _fail_run(session, run=run, reason="review_gate_timed_out")


# --- spend protection (design doc §9) ---------------------------------------


def utc_day_start(now: datetime | None = None) -> datetime:
    now = now or datetime.now(UTC)
    return now.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)


def utc_month_start(now: datetime | None = None) -> datetime:
    now = now or datetime.now(UTC)
    now = now.astimezone(UTC)
    return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


async def spend_committed_plus_reserved(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    provider: str | None,
    since: datetime | None = None,
) -> Decimal:
    """Sum committed spend logs + open reservations.

    When ``since`` is set, only logs with ``occurred_at >= since`` count.
    Open reservations always count (they are in-flight against the budget).
    """
    log_q = select(func.coalesce(func.sum(SpendLog.cost_usd), 0)).where(
        SpendLog.workspace_id == workspace_id
    )
    if since is not None:
        log_q = log_q.where(SpendLog.occurred_at >= since)
    res_q = select(func.coalesce(func.sum(SpendReservation.estimated_cost_usd), 0)).where(
        SpendReservation.workspace_id == workspace_id,
        SpendReservation.status == ReservationStatus.RESERVED,
    )
    if provider is not None:
        log_q = log_q.where(SpendLog.provider == provider)
        res_q = res_q.where(SpendReservation.provider == provider)
    committed = (await session.execute(log_q)).scalar_one()
    reserved = (await session.execute(res_q)).scalar_one()
    return Decimal(committed) + Decimal(reserved)


async def _load_spend_cap_for_update(
    session: AsyncSession, *, workspace_id: uuid.UUID, provider: str
) -> SpendCap | None:
    cap_result = await session.execute(
        select(SpendCap)
        .where(
            SpendCap.workspace_id == workspace_id,
            SpendCap.provider == provider,
        )
        .with_for_update()
    )
    cap = cap_result.scalar_one_or_none()
    if cap is not None:
        return cap
    cap_result = await session.execute(
        select(SpendCap)
        .where(
            SpendCap.workspace_id == workspace_id,
            SpendCap.provider.is_(None),
        )
        .with_for_update()
    )
    return cap_result.scalar_one_or_none()


async def reserve_spend(
    session: AsyncSession,
    *,
    run: PipelineRun,
    stage: str,
    provider: str,
    estimated_cost_usd: Decimal,
) -> SpendReservation | None:
    """Reserve estimated cost before dispatch. Returns None (and pauses
    the run) if the reservation would exceed the workspace daily OR monthly
    cap — closes the check-then-spend race (design doc §9).

    WS4: the spend_caps row is locked ``FOR UPDATE`` so concurrent
    reservations serialize and cannot both slip under the remaining budget.
    """
    # One open reservation per (run, stage). Release priors before the cap
    # check so retry/recovery does not double-count the same stage against
    # the budget, and so submit never sees MultipleResultsFound (H-4).
    prior = (
        (
            await session.execute(
                select(SpendReservation).where(
                    SpendReservation.pipeline_run_id == run.id,
                    SpendReservation.stage == stage,
                    SpendReservation.status == ReservationStatus.RESERVED,
                )
            )
        )
        .scalars()
        .all()
    )
    for old in prior:
        await release_spend(session, run=run, reservation=old)

    cap = await _load_spend_cap_for_update(
        session, workspace_id=run.workspace_id, provider=provider
    )

    exceeded: str | None = None
    payload: dict[str, object] = {"provider": provider, "stage": stage}

    if cap is None:
        # Fail closed: no SpendCap row means spend is unauthorized, not
        # unbounded. Every workspace created via POST /workspaces seeds a
        # default cap in the same transaction, so this should not be
        # reachable in practice — but a workspace provisioned any other
        # way (backfill, ops tooling, future admin path) must still be
        # blocked rather than silently spending with no limit.
        exceeded = "missing_cap"
        payload["attempted_usd"] = str(estimated_cost_usd)
    else:
        # Workspace-wide caps (provider IS NULL) must aggregate ALL providers.
        # Provider-specific caps only count that provider's spend. Filtering
        # by the reservation provider against a workspace-wide cap would
        # allow cross-provider monthly/daily bypass.
        usage_provider = None if cap.provider is None else provider
        daily = await spend_committed_plus_reserved(
            session,
            workspace_id=run.workspace_id,
            provider=usage_provider,
            since=utc_day_start(),
        )
        monthly = await spend_committed_plus_reserved(
            session,
            workspace_id=run.workspace_id,
            provider=usage_provider,
            since=utc_month_start(),
        )
        daily_cap = Decimal(str(cap.daily_cap_usd))
        monthly_cap = Decimal(str(cap.monthly_cap_usd))
        if daily + estimated_cost_usd > daily_cap:
            exceeded = "daily"
        elif monthly + estimated_cost_usd > monthly_cap:
            exceeded = "monthly"
        if exceeded is not None:
            payload.update(
                {
                    "attempted_usd": str(estimated_cost_usd),
                    "daily_used_usd": str(daily),
                    "monthly_used_usd": str(monthly),
                    "daily_cap_usd": str(daily_cap),
                    "monthly_cap_usd": str(monthly_cap),
                }
            )

    if exceeded is not None:
        run.status = PipelineRunStatus.PAUSED
        run.pause_reason = PauseReason.SPEND_HOLD.value
        if run.correlation_id is None:
            run.correlation_id = uuid.uuid4()
        trace_id, span_id = child_span(run.trace_id)
        run.trace_id = trace_id
        payload["cap_kind"] = exceeded
        await emit(
            session,
            event_type=SPEND_BUDGET_EXCEEDED,
            workspace_id=run.workspace_id,
            aggregate_type="pipeline_run",
            aggregate_id=run.id,
            correlation_id=_correlation_id(run),
            trace_id=trace_id,
            span_id=span_id,
            payload=payload,
            produced_by="controller",
        )
        return None

    reservation = SpendReservation(
        id=uuid.uuid4(),
        workspace_id=run.workspace_id,
        content_item_id=None,
        pipeline_run_id=run.id,
        provider=provider,
        stage=stage,
        estimated_cost_usd=estimated_cost_usd,
        status=ReservationStatus.RESERVED,
    )
    session.add(reservation)
    await session.flush()
    if run.correlation_id is None:
        run.correlation_id = uuid.uuid4()
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=SPEND_RESERVED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={"reservation_id": str(reservation.id), "estimated_usd": str(estimated_cost_usd)},
        produced_by="controller",
    )
    return reservation


async def commit_spend(
    session: AsyncSession,
    *,
    run: PipelineRun,
    reservation: SpendReservation,
    actual_cost_usd: Decimal,
) -> None:
    if reservation.status == ReservationStatus.COMMITTED:
        logger.info(
            "spend_commit_idempotent",
            extra={
                "reservation_id": str(reservation.id),
                "pipeline_run_id": str(run.id),
            },
        )
        return
    if reservation.status != ReservationStatus.RESERVED:
        raise ValueError(
            f"cannot commit reservation in status {reservation.status!r}; expected reserved"
        )
    reserved = Decimal(str(reservation.estimated_cost_usd))
    actual = Decimal(str(actual_cost_usd))
    if actual < 0:
        raise ValueError("actual_cost_usd must be >= 0")
    # Worker-reported cost must not exceed the reserved estimate — caps are
    # enforced at reserve time; commit is fail-closed against overage.
    if actual > reserved:
        logger.warning(
            "spend_commit_clamped",
            extra={
                "workspace_id": str(run.workspace_id),
                "pipeline_run_id": str(run.id),
                "reservation_id": str(reservation.id),
                "reserved_usd": str(reserved),
                "reported_usd": str(actual),
            },
        )
        actual = reserved
    reservation.status = ReservationStatus.COMMITTED
    session.add(
        SpendLog(
            id=uuid.uuid4(),
            workspace_id=run.workspace_id,
            content_item_id=None,
            provider=reservation.provider,
            stage=reservation.stage,
            cost_usd=actual,
            occurred_at=datetime.now(UTC),
        )
    )
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=SPEND_COMMITTED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={
            "reservation_id": str(reservation.id),
            "actual_usd": str(actual),
            "reserved_usd": str(reserved),
        },
        produced_by="controller",
    )


async def release_spend(
    session: AsyncSession, *, run: PipelineRun, reservation: SpendReservation
) -> None:
    if reservation.status != ReservationStatus.RESERVED:
        return
    reservation.status = ReservationStatus.RELEASED
    trace_id, span_id = child_span(run.trace_id)
    run.trace_id = trace_id
    await emit(
        session,
        event_type=SPEND_RELEASED,
        workspace_id=run.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=run.id,
        correlation_id=_correlation_id(run),
        trace_id=trace_id,
        span_id=span_id,
        payload={"reservation_id": str(reservation.id)},
        produced_by="controller",
    )


async def release_all_reservations(session: AsyncSession, *, run: PipelineRun) -> None:
    """Release only THIS run's open reservations — scoped by
    pipeline_run_id (migration 0020), not the whole workspace, so a
    failed/cancelled run never releases another run's active reservation.
    """
    result = await session.execute(
        select(SpendReservation).where(
            SpendReservation.pipeline_run_id == run.id,
            SpendReservation.status == ReservationStatus.RESERVED,
        )
    )
    for reservation in result.scalars().all():
        await release_spend(session, run=run, reservation=reservation)


async def submit_review_decision(
    session: AsyncSession,
    *,
    gate: ReviewGate,
    reviewer_id: uuid.UUID,
    approved: bool,
    notes: str | None = None,
) -> OutboxEvent:
    """The orchestration hook a future review API calls: records the M3
    review_decisions row and emits review.approved/review.rejected in the
    SAME transaction, then commits are the caller's. This is the write
    side; app.orchestration.consumers wires the emitted event to
    resume_from_review — genuinely bus-mediated because the reviewer is a
    separate actor with no direct call into the controller.
    """
    from app.models.enums import ReviewDecisionValue
    from app.models.history import ReviewDecision
    from app.orchestration.events.types import REVIEW_APPROVED, REVIEW_REJECTED

    if gate.status != ReviewGateStatus.AWAITING:
        # This function's contract is to always return the OutboxEvent it
        # emitted for the decision; there is no prior event to hand back
        # here without a payload query that could itself pick the wrong
        # historical gate. The one current caller (content_desk.submit_
        # review_decision) already guards against calling this on a
        # non-AWAITING gate, so reaching here is a genuine invariant
        # violation — fail loudly instead of returning None against the
        # declared return type, which would crash ambiguously downstream.
        raise ValueError(f"review_gate {gate.id} is not awaiting a decision")

    run = await session.get(PipelineRun, gate.pipeline_run_id)
    if run is None or run.content_item_id is None:
        raise ValueError(f"review_gate {gate.id} missing pipeline run / content item")
    gate.decided_by = reviewer_id
    decision = ReviewDecision(
        id=uuid.uuid4(),
        workspace_id=gate.workspace_id,
        content_item_id=run.content_item_id,
        reviewer_id=reviewer_id,
        decision=ReviewDecisionValue.APPROVED if approved else ReviewDecisionValue.REJECTED,
        notes=notes,
    )
    session.add(decision)
    await session.flush()

    trace_id, span_id = child_span(run.trace_id if run else None)
    return await emit(
        session,
        event_type=REVIEW_APPROVED if approved else REVIEW_REJECTED,
        workspace_id=gate.workspace_id,
        aggregate_type="pipeline_run",
        aggregate_id=gate.pipeline_run_id,
        correlation_id=(run.correlation_id if run.correlation_id else uuid.uuid4()),
        trace_id=trace_id,
        span_id=span_id,
        payload={"review_gate_id": str(gate.id), "decision_id": str(decision.id)},
        produced_by="review_hook",
    )

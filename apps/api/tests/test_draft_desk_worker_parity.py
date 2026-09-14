"""Regression: the API and worker packages each maintain an independent
Draft Desk generator (`app.services.draft_desk.execute_stage` and
`worker.executors.draft_desk.draft_desk_executor`) because the worker
can't import the API package — see both modules' own docstrings, which
say "keep outputs aligned" as a manually-maintained invariant with no
test enforcing it (2026-09-07 audit finding, INFO). This test makes that
invariant a real, enforced regression instead of a comment: a future edit
to one generator without the other now fails CI instead of silently
drifting.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

# apps/worker isn't installed as a dependency of apps/api; reach it via a
# relative path, matching tests/test_reference_worker_client.py.
sys.path.append(str(Path(__file__).resolve().parents[2] / "worker"))

from worker.executors.draft_desk import draft_desk_executor  # noqa: E402

from app.services.draft_desk import execute_stage


@pytest.mark.parametrize(
    "context",
    [
        {"stage": "scripting", "topic": "cold email outreach"},
        {"stage": "idea", "topic": "cold email outreach", "target_length_seconds": 45},
        {"stage": "scripting", "topic": "  extra   whitespace   topic  "},
        {"stage": "review", "topic": "anything"},
    ],
)
@pytest.mark.asyncio
async def test_api_and_worker_draft_desk_generators_agree(context):
    api_success, api_result, api_error = execute_stage(dict(context))
    worker_success, worker_result, worker_error = await draft_desk_executor(dict(context))

    assert api_success == worker_success
    assert api_error == worker_error
    assert api_result == worker_result


@pytest.mark.parametrize(
    "context",
    [
        {"stage": "some_other_stage", "topic": "topic here"},
        {"stage": "some_other_stage", "topic": ""},
        {"stage": "", "topic": "topic here"},
    ],
)
@pytest.mark.asyncio
async def test_api_and_worker_draft_desk_reject_unsupported_stages(context):
    api_success, api_result, api_error = execute_stage(dict(context))
    worker_success, worker_result, worker_error = await draft_desk_executor(dict(context))

    assert api_success is False
    assert worker_success is False
    assert api_result is None
    assert worker_result is None
    assert api_error == worker_error
    assert "unsupported" in api_error.lower()


@pytest.mark.asyncio
async def test_api_and_worker_draft_desk_generators_agree_on_missing_topic():
    context = {"stage": "scripting"}
    api_success, api_result, api_error = execute_stage(dict(context))
    worker_success, worker_result, worker_error = await draft_desk_executor(dict(context))

    assert api_success is False
    assert worker_success is False
    assert api_result is None
    assert worker_result is None
    assert api_error == worker_error

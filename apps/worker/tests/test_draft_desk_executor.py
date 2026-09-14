"""P0-3: worker Draft Desk executor produces real artifacts."""

from __future__ import annotations

import pytest

from worker.executors.draft_desk import draft_desk_executor


@pytest.mark.asyncio
async def test_scripting_generates_non_empty_draft():
    ok, result, err = await draft_desk_executor({"stage": "scripting", "topic": "agency retention"})
    assert ok is True
    assert err == ""
    assert result is not None
    assert result["provider"] == "draft_desk"
    assert "agency retention" in result["script_body"]
    assert result["script_body"].strip() != ""


@pytest.mark.asyncio
async def test_scripting_requires_topic():
    ok, result, err = await draft_desk_executor({"stage": "scripting"})
    assert ok is False
    assert result is None
    assert "topic" in err


@pytest.mark.asyncio
async def test_review_stage_rejected():
    ok, _result, err = await draft_desk_executor({"stage": "review", "topic": "x"})
    assert ok is False
    assert "human" in err.lower() or "review" in err.lower()


@pytest.mark.asyncio
async def test_unknown_stage_rejected():
    ok, result, err = await draft_desk_executor({"stage": "voiceover", "topic": "x"})
    assert ok is False
    assert result is None
    assert "unsupported" in err.lower()

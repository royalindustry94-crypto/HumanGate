"""Regression (2026-09-07, TD-078): claim_next() must retry a claim whose
HTTP response is lost in transit using the SAME claim_token, so the
server's existing idempotent-replay path (app.orchestration.claiming) is
actually exercised instead of silently stranding a granted assignment.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import httpx
import pytest

from worker.client import ReferenceWorkerClient


def _granted_response(assignment_id: str = "11111111-1111-1111-1111-111111111111"):
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json = MagicMock(
        return_value={"outcome": "granted", "assignment": {"id": assignment_id}}
    )
    return response


def _make_client(http) -> ReferenceWorkerClient:
    return ReferenceWorkerClient(
        name="w",
        supported_stages=["scripting"],
        http=http,
        credential="cred-id.secret",
        worker_id="22222222-2222-2222-2222-222222222222",
    )


@pytest.mark.asyncio
async def test_claim_next_retries_with_same_token_after_lost_response():
    http = MagicMock()
    http.post = AsyncMock(
        side_effect=[
            httpx.ConnectTimeout("boom"),
            _granted_response(),
        ]
    )
    client = _make_client(http)

    assignment = await client.claim_next()

    assert assignment == {"id": "11111111-1111-1111-1111-111111111111"}
    assert client.current_load == 1
    assert http.post.call_count == 2
    sent_tokens = {call.kwargs["json"]["claim_token"] for call in http.post.call_args_list}
    assert len(sent_tokens) == 1, "each retry must reuse the same claim_token"


@pytest.mark.asyncio
async def test_claim_next_gives_up_after_bounded_retries():
    http = MagicMock()
    http.post = AsyncMock(side_effect=httpx.ConnectTimeout("boom"))
    client = _make_client(http)

    with pytest.raises(httpx.ConnectTimeout):
        await client.claim_next()

    assert client.current_load == 0
    assert http.post.call_count == 3  # _CLAIM_NETWORK_RETRY_ATTEMPTS


@pytest.mark.asyncio
async def test_claim_next_does_not_retry_http_error_status():
    response = MagicMock()

    def _raise():
        raise httpx.HTTPStatusError("nope", request=MagicMock(), response=MagicMock())

    response.raise_for_status = MagicMock(side_effect=_raise)
    http = MagicMock()
    http.post = AsyncMock(return_value=response)
    client = _make_client(http)

    with pytest.raises(httpx.HTTPStatusError):
        await client.claim_next()

    assert http.post.call_count == 1


@pytest.mark.asyncio
async def test_register_advertises_only_executable_supported_stages():
    response = MagicMock()
    response.raise_for_status = MagicMock()
    http = MagicMock()
    http.post = AsyncMock(return_value=response)
    client = ReferenceWorkerClient(
        name="w",
        supported_stages=["scripting", "voiceover", "review", "idea", "scripting"],
        http=http,
        credential="cred-id.secret",
        worker_id="22222222-2222-2222-2222-222222222222",
    )

    await client.register()

    assert client.supported_stages == ["scripting", "idea"]
    assert http.post.call_args.kwargs["json"]["supported_stages"] == ["scripting", "idea"]
    assert http.post.call_args.kwargs["json"]["capabilities"]["features"] == ["scripting", "idea"]

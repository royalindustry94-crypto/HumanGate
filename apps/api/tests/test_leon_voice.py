"""Coverage for the Leon Android companion's voice turn endpoint.

Exercises the route end-to-end (auth, request validation, the three
upstream calls) with the real ASGI app and a mocked httpx transport for
Anthropic/OpenAI -- these tests must never reach the real internet.
"""

from __future__ import annotations

import base64
import json
import logging
import struct

import httpx
import pytest
from sqlalchemy import text
from sqlalchemy.exc import ProgrammingError

from app.core.config import get_settings
from app.db.session import AsyncSessionLocal
from app.main import app
from app.services import leon_voice as lv
from app.services import leon_voice_spend

_APP_TOKEN = "test-leon-app-token"


@pytest.fixture(autouse=True)
def _configured_providers(monkeypatch):
    monkeypatch.setenv("anthkey", "test-anthropic-key")
    monkeypatch.setenv("ANTHTOPIC_APO_KEY", "test-openai-key")
    monkeypatch.setenv("LEON_VOICE_APP_TOKEN", _APP_TOKEN)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {_APP_TOKEN}"}


def _install_transport(monkeypatch, handler):
    real_client = httpx.AsyncClient

    def factory(**kwargs):
        kwargs.pop("transport", None)
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr(lv.httpx, "AsyncClient", factory)


def _happy_path_handler(request: httpx.Request) -> httpx.Response:
    if "audio/transcriptions" in str(request.url):
        return httpx.Response(200, json={"text": "hello leon"})
    if "audio/speech" in str(request.url):
        return httpx.Response(
            200, content=b"fake-mp3-bytes", headers={"content-type": "audio/mpeg"}
        )
    if "messages" in str(request.url):
        return httpx.Response(
            200,
            json={"content": [{"type": "text", "text": "hey there"}]},
        )
    raise AssertionError(f"unexpected request: {request.url}")


@pytest.mark.asyncio
async def test_requires_authentication(client):
    response = await client.post("/leon/voice-turn", data={"text": "hi"})
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_rejects_the_wrong_app_token(client):
    response = await client.post(
        "/leon/voice-turn",
        headers={"Authorization": "Bearer wrong-token"},
        data={"text": "hi"},
    )
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_reports_unavailable_when_no_app_token_is_configured(monkeypatch, client):
    monkeypatch.delenv("LEON_VOICE_APP_TOKEN", raising=False)
    get_settings.cache_clear()

    response = await client.post("/leon/voice-turn", headers=_auth_headers(), data={"text": "hi"})
    assert response.status_code == 503


@pytest.mark.asyncio
async def test_rejects_a_turn_with_neither_audio_nor_text(client):
    response = await client.post("/leon/voice-turn", headers=_auth_headers(), data={})
    assert response.status_code == 400


@pytest.mark.asyncio
async def test_rejects_text_over_the_length_bound(client):
    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={"text": "x" * (lv.MAX_TEXT_CHARS + 1)},
    )
    assert response.status_code == 400


@pytest.mark.asyncio
async def test_rejects_audio_over_the_size_bound(client):
    oversized = b"0" * (lv.MAX_AUDIO_BYTES + 1)
    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={},
        files={"audio": ("clip.m4a", oversized, "audio/m4a")},
    )
    assert response.status_code == 413


class _UnboundedStream:
    """An UploadFile-like stream that fails the test if asked to read past
    the caller's own hard bound -- proves the route never materializes an
    unbounded body before the size check, not just that it eventually
    responds 413.
    """

    def __init__(self, total_bytes: int):
        self._remaining = total_bytes
        self.max_single_read = 0
        self.total_read = 0

    async def read(self, size: int = -1) -> bytes:
        assert size > 0, "route must always request a bounded chunk size"
        assert self.total_read + size <= lv.MAX_AUDIO_BYTES + 1, (
            "route requested more than MAX_AUDIO_BYTES + 1 bytes total"
        )
        self.max_single_read = max(self.max_single_read, size)
        take = min(size, self._remaining)
        self._remaining -= take
        self.total_read += take
        return b"0" * take


@pytest.mark.asyncio
async def test_oversized_audio_is_never_fully_materialized(monkeypatch):
    from fastapi import HTTPException

    from app.api.routes.leon_voice import _read_audio_bounded

    huge = _UnboundedStream(total_bytes=lv.MAX_AUDIO_BYTES * 10)
    with pytest.raises(HTTPException) as exc_info:
        await _read_audio_bounded(huge)  # type: ignore[arg-type]

    assert exc_info.value.status_code == 413
    assert huge.total_read <= lv.MAX_AUDIO_BYTES + 1


@pytest.mark.asyncio
async def test_reports_a_safe_error_when_no_provider_keys_are_configured(monkeypatch, client):
    monkeypatch.delenv("anthkey", raising=False)
    monkeypatch.delenv("ANTHTOPIC_APO_KEY", raising=False)
    get_settings.cache_clear()

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 502
    detail = response.json()["detail"]
    assert "not configured" in detail["message"]
    assert detail["stage"] in {"llm", "openai"}
    assert detail["error_code"] == "voice_upstream_failure"


@pytest.mark.asyncio
async def test_voice_status_is_authenticated_and_secret_free(client):
    response = await client.get("/leon/voice-status", headers=_auth_headers())

    assert response.status_code == 200
    body = response.json()
    assert body["configured"] is True
    assert body["anthropic_configured"] is True
    assert body["openai_configured"] is True
    assert body["request_id"]
    assert "key" not in json.dumps(body).lower()
    assert _APP_TOKEN not in json.dumps(body)


@pytest.mark.asyncio
async def test_malformed_provider_json_becomes_controlled_502(monkeypatch, client):
    def handler(request: httpx.Request) -> httpx.Response:
        if "messages" in str(request.url):
            return httpx.Response(200, content=b"not-json")
        return _happy_path_handler(request)

    _install_transport(monkeypatch, handler)
    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 502
    detail = response.json()["detail"]
    assert detail["stage"] == "llm"
    assert detail["error_code"] == "voice_upstream_failure"


@pytest.mark.asyncio
async def test_empty_tts_audio_becomes_controlled_502(monkeypatch, client):
    def handler(request: httpx.Request) -> httpx.Response:
        if "audio/speech" in str(request.url):
            return httpx.Response(
                200,
                content=b"",
                headers={"content-type": "audio/mpeg"},
            )
        return _happy_path_handler(request)

    _install_transport(monkeypatch, handler)
    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 502
    detail = response.json()["detail"]
    assert detail["stage"] == "tts"
    assert detail["error_code"] == "voice_upstream_failure"


@pytest.mark.asyncio
async def test_a_text_turn_returns_a_reply_and_speech_audio(monkeypatch, client):
    _install_transport(monkeypatch, _happy_path_handler)

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["user_text"] == "hi leon"
    assert body["reply_text"] == "hey there"
    assert base64.b64decode(body["reply_audio_base64"]) == b"fake-mp3-bytes"
    assert body["reply_audio_mime_type"] == "audio/mpeg"


@pytest.mark.asyncio
async def test_an_audio_turn_is_transcribed_before_replying(monkeypatch, client):
    _install_transport(monkeypatch, _happy_path_handler)

    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={},
        files={"audio": ("clip.m4a", b"pretend-audio-bytes", "audio/m4a")},
    )

    assert response.status_code == 200
    assert response.json()["user_text"] == "hello leon"


@pytest.mark.asyncio
async def test_conversation_history_is_forwarded_to_the_llm(monkeypatch, client):
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if "messages" in str(request.url):
            captured["body"] = json.loads(request.content)
            return httpx.Response(
                200, json={"content": [{"type": "text", "text": "sure, I remember"}]}
            )
        return _happy_path_handler(request)

    _install_transport(monkeypatch, handler)

    history = [
        {"role": "user", "content": "my name is Sam"},
        {"role": "assistant", "content": "nice to meet you, Sam"},
    ]
    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={"text": "what's my name?", "history": json.dumps(history)},
    )

    assert response.status_code == 200
    sent_messages = captured["body"]["messages"]
    assert sent_messages[0] == history[0]
    assert sent_messages[1] == history[1]
    assert sent_messages[2] == {"role": "user", "content": "what's my name?"}


@pytest.mark.asyncio
async def test_malformed_history_is_rejected(client):
    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={"text": "hi", "history": "not-json"},
    )
    assert response.status_code == 400


@pytest.fixture
async def _tiny_leon_spend_cap():
    """Lowers the seeded Leon voice spend cap to effectively zero for one
    test, then restores it -- the cap row is a fixed singleton (migration
    0059), not per-test data, so it must not leak into other tests.
    """
    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                "UPDATE spend_caps SET daily_cap_usd = 0.00001 "
                "WHERE workspace_id = :ws AND provider IS NULL"
            ),
            {"ws": str(leon_voice_spend.LEON_SYSTEM_WORKSPACE_ID)},
        )
        await session.commit()
    try:
        yield
    finally:
        async with AsyncSessionLocal() as session:
            await session.execute(
                text(
                    "UPDATE spend_caps SET daily_cap_usd = 2.0 "
                    "WHERE workspace_id = :ws AND provider IS NULL"
                ),
                {"ws": str(leon_voice_spend.LEON_SYSTEM_WORKSPACE_ID)},
            )
            await session.commit()


@pytest.mark.asyncio
async def test_exhausted_daily_budget_fails_closed_with_402(
    monkeypatch, client, _tiny_leon_spend_cap
):
    _install_transport(monkeypatch, _happy_path_handler)

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 402
    detail = response.json()["detail"]
    assert detail["stage"] == "spend"
    assert detail["error_code"] == "voice_spend_cap_reached"


@pytest.mark.asyncio
async def test_a_provider_failure_releases_its_reservation_not_the_budget(monkeypatch, client):
    """A failed call must not permanently consume the reservation it made --
    release() must run on the failure path so the next turn can still spend
    that budget, not just that the caller gets a clean error.
    """

    # One handler installed for the whole test (see _install_transport: each
    # call re-wraps the *current* patched httpx.AsyncClient rather than
    # replacing it, so calling it twice in one test nests instead of
    # swapping) -- a mutable flag switches its behavior between the two
    # requests below instead of re-installing the transport.
    should_fail = {"value": True}

    def switchable_handler(request: httpx.Request) -> httpx.Response:
        if should_fail["value"] and "messages" in str(request.url):
            return httpx.Response(200, content=b"not-json")
        return _happy_path_handler(request)

    _install_transport(monkeypatch, switchable_handler)

    async with AsyncSessionLocal() as session:
        before = (
            await session.execute(
                text(
                    "SELECT count(*) FROM spend_reservations "
                    "WHERE workspace_id = :ws AND status = 'released'"
                ),
                {"ws": str(leon_voice_spend.LEON_SYSTEM_WORKSPACE_ID)},
            )
        ).scalar_one()

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )
    assert response.status_code == 502

    async with AsyncSessionLocal() as session:
        after = (
            await session.execute(
                text(
                    "SELECT count(*) FROM spend_reservations "
                    "WHERE workspace_id = :ws AND status = 'released'"
                ),
                {"ws": str(leon_voice_spend.LEON_SYSTEM_WORKSPACE_ID)},
            )
        ).scalar_one()
    assert after == before + 1

    # And the budget that reservation held is free again -- a subsequent
    # turn must still be able to spend it, not find it stuck as "reserved".
    should_fail["value"] = False
    retry = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "try again"}
    )
    assert retry.status_code == 200


@pytest.mark.asyncio
async def test_an_upstream_failure_becomes_a_safe_502(monkeypatch, client):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            500,
            text="internal provider error with secrets sk-abc123",
        )

    _install_transport(monkeypatch, handler)

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi leon"}
    )

    assert response.status_code == 502
    assert "sk-abc123" not in response.text
    detail = response.json()["detail"]
    assert detail["error_code"] == "voice_upstream_failure"
    assert detail["stage"] in {"stt", "llm", "tts"}


def _wav_16k_mono(samples: int = 1600) -> bytes:
    """A real canonical WAV, the shape LeonVoiceRecorder produces (16 kHz, mono, PCM 16-bit)."""
    data = b"\x10\x00" * samples
    fmt = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
    return (
        b"RIFF"
        + struct.pack("<I", 36 + len(data))
        + b"WAVE"
        + b"fmt "
        + struct.pack("<I", len(fmt))
        + fmt
        + b"data"
        + struct.pack("<I", len(data))
        + data
    )


@pytest.mark.asyncio
async def test_an_unexpected_failure_is_an_explicit_500_naming_its_stage(monkeypatch):
    # Reproduced before the fix: a database error inside the spend reservation (for example a
    # deployment missing migration 0059) escaped to the global handler as a bare
    # {"detail": "internal server error"}, which the phone could only show as "code 500".
    async def broken_reserve(**_):
        raise ProgrammingError(
            "SELECT * FROM spend_caps WHERE workspace_id = $1",
            {},
            Exception('relation "spend_caps" does not exist'),
        )

    monkeypatch.setattr(leon_voice_spend, "reserve", broken_reserve)
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi"}
        )

    assert response.status_code == 500
    detail = response.json()["detail"]
    assert detail["error_code"] == "voice_internal_error"
    assert detail["stage"] == "voice_turn"
    assert detail["error_type"] == "ProgrammingError"
    assert detail["request_id"] == response.headers["x-request-id"]
    assert detail["received"]["has_text"] is True
    # The exception's own message can carry SQL, hostnames or provider text: never returned.
    assert "spend_caps" not in response.text
    assert "relation" not in response.text


@pytest.mark.asyncio
async def test_the_received_audio_shape_is_logged_without_its_content(monkeypatch, client, caplog):
    _install_transport(monkeypatch, _happy_path_handler)
    wav = _wav_16k_mono()
    caplog.set_level(logging.INFO, logger="app.api.routes.leon_voice")

    response = await client.post(
        "/leon/voice-turn",
        headers=_auth_headers(),
        data={"history": json.dumps([{"role": "user", "content": "my private words"}])},
        files={"audio": ("speech.wav", wav, "audio/wav")},
    )

    assert response.status_code == 200
    records = [r for r in caplog.records if r.getMessage() == "leon_voice_turn_received"]
    assert len(records) == 1
    record = records[0].__dict__
    assert record["request_content_type"] == "multipart/form-data"
    assert record["audio_part_filename"] == "speech.wav"
    assert record["audio_part_content_type"] == "audio/wav"
    assert record["audio_container"] == "wav"
    assert record["audio_bytes"] == len(wav)
    assert record["audio_magic_hex"] == wav[:12].hex()
    assert record["wav_sample_rate_hz"] == 16000
    assert record["wav_channels"] == 1
    assert record["wav_bits_per_sample"] == 16
    logged = repr(record)
    assert _APP_TOKEN not in logged
    assert "my private words" not in logged
    assert wav[12:40].hex() not in logged

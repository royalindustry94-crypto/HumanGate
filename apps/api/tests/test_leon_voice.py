"""Coverage for the Leon Android companion's voice turn endpoint.

Exercises the route end-to-end (auth, request validation, the three
upstream calls) with the real ASGI app and a mocked httpx transport for
Anthropic/OpenAI -- these tests must never reach the real internet.
"""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from app.core.config import get_settings
from app.services import leon_voice as lv

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
            json={
                "content": [
                    {"type": "text", "text": "hey there, good to hear from you"}
                ]
            },
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

    response = await client.post(
        "/leon/voice-turn", headers=_auth_headers(), data={"text": "hi"}
    )
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


@pytest.mark.asyncio
async def test_reports_a_safe_error_when_no_provider_keys_are_configured(
    monkeypatch, client
):
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
                200, content=b"", headers={"content-type": "audio/mpeg"}
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
    assert body["reply_text"] == "hey there, good to hear from you"
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


@pytest.mark.asyncio
async def test_an_upstream_failure_becomes_a_safe_502(monkeypatch, client):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            500, text="internal provider error with secrets sk-abc123"
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

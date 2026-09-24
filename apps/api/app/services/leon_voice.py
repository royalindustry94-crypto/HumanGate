"""Leon's voice turn: speech in, an LLM reply, speech out.

Three upstream calls, each behind its own clear failure mode rather than a
generic 500 — a caller (the Android app) needs to tell "you're not
configured yet" apart from "the provider rejected this audio" apart from
"the provider is down right now".

Deliberately stateless and DB-free: no workspace, no spend-cap reservation,
no job orchestration. Those exist elsewhere in this service for the async
content-production pipeline, which is not what a synchronous voice turn is
— see the PR description for why they were not reused here. The safety net
for this route is the request-level bounds below (audio size, reply length)
plus the caller's own provider-account spending limits, not a workspace
spend cap.
"""

from __future__ import annotations

import base64
import logging

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)

_ANTHROPIC_API = "https://api.anthropic.com/v1/messages"
_OPENAI_TRANSCRIPTION_API = "https://api.openai.com/v1/audio/transcriptions"
_OPENAI_SPEECH_API = "https://api.openai.com/v1/audio/speech"
_ANTHROPIC_VERSION = "2023-06-01"

_HTTP_TIMEOUT_SECONDS = 30.0

# Bounds worst-case cost and latency per turn. A phone mic clip for one
# conversational turn is seconds long; 15MB is generous headroom over that
# while still rejecting someone uploading an arbitrary large file.
MAX_AUDIO_BYTES = 15 * 1024 * 1024
MAX_TEXT_CHARS = 4000
MAX_HISTORY_TURNS = 8
# Keeps Leon's replies conversational (a few sentences), not essay-length,
# and bounds the token cost of every single turn regardless of what the
# user says.
_MAX_REPLY_TOKENS = 300

LEON_SYSTEM_PROMPT = (
    "You are Leon, an animated companion who lives as an overlay on the "
    "user's Android screen. You are having a spoken conversation — your "
    "reply is converted to speech and read aloud, so write the way a "
    "person actually talks: short sentences, no markdown, no bullet "
    "lists, no headings, nothing that only makes sense written down. "
    "Keep replies brief, warm, and conversational, generally one to "
    "three sentences unless the user clearly wants more detail."
)


class LeonVoiceError(RuntimeError):
    """A provider call failed or is not configured.

    `detail` and `stage` are safe to return to the client; neither contains
    credentials or raw provider payloads.
    """

    def __init__(self, detail: str, *, stage: str):
        super().__init__(detail)
        self.detail = detail
        self.stage = stage


def _safe_json(response: httpx.Response, *, stage: str, fallback: str) -> dict:
    """Decode a provider JSON object without letting malformed 2xx bodies become raw 500s."""
    try:
        body = response.json()
    except ValueError as exc:
        logger.warning(
            "leon_voice_provider_invalid_json",
            extra={"provider_stage": stage, "status_code": response.status_code},
        )
        raise LeonVoiceError(fallback, stage=stage) from exc
    if not isinstance(body, dict):
        logger.warning(
            "leon_voice_provider_invalid_shape",
            extra={"provider_stage": stage, "status_code": response.status_code},
        )
        raise LeonVoiceError(fallback, stage=stage)
    return body


def _require_anthropic_key() -> str:
    key = get_settings().anthropic_api_key
    if not key:
        raise LeonVoiceError(
            "voice conversation is not configured (missing Anthropic key)",
            stage="llm",
        )
    return key


def _require_openai_key() -> str:
    key = get_settings().openai_api_key
    if not key:
        raise LeonVoiceError(
            "voice conversation is not configured (missing OpenAI key)",
            stage="openai",
        )
    return key


async def transcribe_audio(audio_bytes: bytes, *, filename: str, content_type: str) -> str:
    """Speech to text via OpenAI's Whisper API."""
    key = _require_openai_key()
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            response = await client.post(
                _OPENAI_TRANSCRIPTION_API,
                headers={"Authorization": f"Bearer {key}"},
                data={"model": "whisper-1"},
                files={"file": (filename, audio_bytes, content_type or "audio/m4a")},
            )
    except httpx.HTTPError as exc:
        logger.warning("leon_voice_stt_transport_error", extra={"error": str(exc)})
        raise LeonVoiceError("speech recognition is unavailable right now", stage="stt") from exc

    if response.status_code >= 400:
        logger.warning(
            "leon_voice_stt_failed",
            extra={"status_code": response.status_code, "body": response.text[:200]},
        )
        raise LeonVoiceError("could not understand that audio", stage="stt")

    body = _safe_json(
        response,
        stage="stt",
        fallback="speech recognition returned an unexpected response",
    )
    text = body.get("text")
    if not isinstance(text, str) or not text.strip():
        raise LeonVoiceError("didn't catch that — try again", stage="stt")
    text = text.strip()
    return text


async def generate_reply(user_text: str, *, history: list[dict[str, str]]) -> str:
    """Leon's reply to one turn, via the Claude Messages API."""
    key = _require_anthropic_key()
    messages = [*history, {"role": "user", "content": user_text}]
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            response = await client.post(
                _ANTHROPIC_API,
                headers={
                    "x-api-key": key,
                    "anthropic-version": _ANTHROPIC_VERSION,
                    "content-type": "application/json",
                },
                json={
                    "model": get_settings().leon_anthropic_model,
                    "max_tokens": _MAX_REPLY_TOKENS,
                    "system": LEON_SYSTEM_PROMPT,
                    "messages": messages,
                },
            )
    except httpx.HTTPError as exc:
        logger.warning("leon_voice_llm_transport_error", extra={"error": str(exc)})
        raise LeonVoiceError("Leon's brain is unavailable right now", stage="llm") from exc

    if response.status_code >= 400:
        logger.warning(
            "leon_voice_llm_failed",
            extra={"status_code": response.status_code, "body": response.text[:200]},
        )
        raise LeonVoiceError("Leon couldn't think of a reply just now", stage="llm")

    body = response.json()
    parts = [
        block.get("text", "") for block in body.get("content", []) if block.get("type") == "text"
    ]
    reply = "".join(parts).strip()
    if not reply:
        raise LeonVoiceError("Leon couldn't think of a reply just now")
    return reply


async def synthesize_speech(text: str) -> bytes:
    """Text to speech via OpenAI's TTS API. Returns MP3 bytes."""
    key = _require_openai_key()
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            response = await client.post(
                _OPENAI_SPEECH_API,
                headers={"Authorization": f"Bearer {key}"},
                json={
                    "model": "tts-1",
                    "voice": get_settings().leon_openai_tts_voice,
                    "input": text,
                    "response_format": "mp3",
                },
            )
    except httpx.HTTPError as exc:
        logger.warning("leon_voice_tts_transport_error", extra={"error": str(exc)})
        raise LeonVoiceError("Leon's voice is unavailable right now", stage="tts") from exc

    if response.status_code >= 400:
        logger.warning(
            "leon_voice_tts_failed",
            extra={"status_code": response.status_code, "body": response.text[:200]},
        )
        raise LeonVoiceError("Leon's voice is unavailable right now", stage="tts")
    if not response.content:
        raise LeonVoiceError("Leon's voice returned empty audio", stage="tts")
    content_type = response.headers.get("content-type", "").lower()
    if content_type.startswith("application/json"):
        logger.warning(
            "leon_voice_tts_unexpected_content_type",
            extra={"status_code": response.status_code, "content_type": content_type[:80]},
        )
        raise LeonVoiceError("Leon's voice returned an unexpected response", stage="tts")
    return response.content


async def run_voice_turn(
    *,
    user_text: str | None,
    audio_bytes: bytes | None,
    audio_filename: str,
    audio_content_type: str,
    history: list[dict[str, str]],
) -> tuple[str, str, bytes]:
    """Full turn: transcribe (if audio given), reply, synthesize.

    Returns (user_text, reply_text, reply_audio_mp3_bytes).
    """
    resolved_text = (user_text or "").strip()
    if not resolved_text:
        if not audio_bytes:
            raise LeonVoiceError("no speech or text was provided", stage="input")
        resolved_text = await transcribe_audio(
            audio_bytes, filename=audio_filename, content_type=audio_content_type
        )
    reply_text = await generate_reply(resolved_text, history=history)
    reply_audio = await synthesize_speech(reply_text)
    return resolved_text, reply_text, reply_audio


def encode_audio_base64(audio_bytes: bytes) -> str:
    return base64.b64encode(audio_bytes).decode("ascii")

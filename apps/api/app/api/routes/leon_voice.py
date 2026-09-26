"""Leon Android companion's voice conversation endpoint.

One synchronous turn: speech or text in, a spoken reply out. The companion
is a single-user overlay app with no account system, so this is gated by a
fixed shared secret (LEON_VOICE_APP_TOKEN) the Android app presents as a
bearer token -- the same pattern app/api/routes/health.py already uses for
METRICS_SCRAPER_TOKEN -- rather than the Supabase/local JWT auth the rest
of this API uses for actual multi-tenant end-user routes. This still keeps
the provider keys from being an open, unauthenticated proxy.
"""

from __future__ import annotations

import hmac
import json

from fastapi import APIRouter, Form, Header, HTTPException, Request, UploadFile, status

from app.core.audit import audit
from app.core.config import get_settings
from app.schemas.leon_voice import LeonVoiceTurnOut
from app.services.leon_voice import (
    MAX_AUDIO_BYTES,
    MAX_HISTORY_TURNS,
    MAX_TEXT_CHARS,
    LeonVoiceError,
    encode_audio_base64,
    run_voice_turn,
)

router = APIRouter(prefix="/leon", tags=["leon-voice"])

_ALLOWED_HISTORY_ROLES = {"user", "assistant"}
# Read in bounded slices so a caller that lies about Content-Length (or a
# proxy that doesn't enforce one) can never make this handler materialize
# more than MAX_AUDIO_BYTES + 1 bytes before the size check below runs.
_AUDIO_READ_CHUNK_BYTES = 1024 * 1024


async def _read_audio_bounded(audio: UploadFile) -> bytes:
    limit = MAX_AUDIO_BYTES + 1
    chunks: list[bytes] = []
    total = 0
    while total <= MAX_AUDIO_BYTES:
        chunk = await audio.read(min(_AUDIO_READ_CHUNK_BYTES, limit - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
    if total > MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail=f"audio must be at most {MAX_AUDIO_BYTES} bytes",
        )
    return b"".join(chunks)


@router.get("/voice-status")
async def voice_status(
    request: Request,
    authorization: str | None = Header(default=None),
) -> dict[str, object]:
    """Authenticated, secret-free runtime configuration check for the companion.

    This intentionally does not make cost-bearing provider calls. It answers whether
    the deployed API has the three pieces a voice turn requires, while the global
    fail-closed rate limiter separately proves the runtime DB path is usable.
    """
    _require_app_token(authorization)
    settings = get_settings()
    return {
        "configured": bool(
            settings.leon_voice_app_token and settings.anthropic_api_key and settings.openai_api_key
        ),
        "anthropic_configured": bool(settings.anthropic_api_key),
        "openai_configured": bool(settings.openai_api_key),
        "request_id": getattr(request.state, "request_id", None),
    }


def _require_app_token(authorization: str | None) -> None:
    expected = (get_settings().leon_voice_app_token or "").strip()
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="voice conversation is not configured",
        )
    presented = authorization or ""
    if presented.lower().startswith("bearer "):
        presented = presented[7:]
    presented = presented.strip()
    if (
        not presented
        or len(presented) != len(expected)
        or not hmac.compare_digest(presented, expected)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid or missing app token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def _parse_history(raw: str | None) -> list[dict[str, str]]:
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="history must be a JSON array",
        ) from exc
    if not isinstance(parsed, list):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="history must be a JSON array",
        )
    turns: list[dict[str, str]] = []
    for item in parsed[-MAX_HISTORY_TURNS:]:
        if not isinstance(item, dict):
            continue
        role = item.get("role")
        content = item.get("content")
        if role not in _ALLOWED_HISTORY_ROLES or not isinstance(content, str):
            continue
        turns.append({"role": role, "content": content[:MAX_TEXT_CHARS]})
    return turns


@router.post("/voice-turn", response_model=LeonVoiceTurnOut)
async def voice_turn(
    request: Request,
    text: str | None = Form(default=None),
    history: str | None = Form(default=None),
    audio: UploadFile | None = None,
    authorization: str | None = Header(default=None),
) -> LeonVoiceTurnOut:
    _require_app_token(authorization)

    if text is not None and len(text) > MAX_TEXT_CHARS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"text must be at most {MAX_TEXT_CHARS} characters",
        )

    audio_bytes: bytes | None = None
    if audio is not None:
        audio_bytes = await _read_audio_bounded(audio)

    if not audio_bytes and not (text or "").strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="either audio or text is required",
        )

    try:
        user_text, reply_text, reply_audio = await run_voice_turn(
            user_text=text,
            audio_bytes=audio_bytes,
            audio_filename=(audio.filename if audio else None) or "speech.m4a",
            audio_content_type=(audio.content_type if audio else None) or "audio/m4a",
            history=_parse_history(history),
        )
    except LeonVoiceError as exc:
        audit(
            request,
            "leon_voice_turn_failed",
            had_audio=audio_bytes is not None,
            failure_stage=exc.stage,
        )
        if exc.stage == "spend":
            raise HTTPException(
                status_code=status.HTTP_402_PAYMENT_REQUIRED,
                detail={
                    "message": exc.detail,
                    "stage": exc.stage,
                    "error_code": "voice_spend_cap_reached",
                },
            ) from exc
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={
                "message": exc.detail,
                "stage": exc.stage,
                "error_code": "voice_upstream_failure",
            },
        ) from exc

    audit(
        request,
        "leon_voice_turn_completed",
        had_audio=audio_bytes is not None,
        user_text_length=len(user_text),
        reply_text_length=len(reply_text),
    )
    return LeonVoiceTurnOut(
        user_text=user_text,
        reply_text=reply_text,
        reply_audio_base64=encode_audio_base64(reply_audio),
    )

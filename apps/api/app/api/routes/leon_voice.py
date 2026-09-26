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
import logging
from collections.abc import AsyncIterable
from dataclasses import dataclass
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request, status
from starlette.datastructures import FormData, UploadFile
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.formparsers import MultiPartException

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
logger = logging.getLogger(__name__)

_ALLOWED_HISTORY_ROLES = {"user", "assistant"}
# Read in bounded slices so a caller that lies about Content-Length (or a
# proxy that doesn't enforce one) can never make this handler materialize
# more than MAX_AUDIO_BYTES + 1 bytes before the size check below runs.
_AUDIO_READ_CHUNK_BYTES = 1024 * 1024
_FORM_CONTENT_TYPES = {"application/x-www-form-urlencoded", "multipart/form-data"}
_CONTENT_TYPE_TO_AUDIO_FORMAT = {
    "audio/flac": "flac",
    "audio/m4a": "m4a",
    "audio/mp3": "mp3",
    "audio/mp4": "mp4",
    "audio/mpeg": "mp3",
    "audio/ogg": "ogg",
    "audio/wav": "wav",
    "audio/webm": "webm",
    "audio/wave": "wav",
    "audio/x-flac": "flac",
    "audio/x-m4a": "m4a",
    "audio/x-wav": "wav",
    "application/octet-stream": None,
    "video/mp4": "mp4",
    "video/ogg": "ogg",
    "video/webm": "webm",
}
_EXTENSION_TO_AUDIO_FORMAT = {
    ".flac": "flac",
    ".m4a": "m4a",
    ".mp3": "mp3",
    ".mp4": "mp4",
    ".oga": "ogg",
    ".ogg": "ogg",
    ".wav": "wav",
    ".wave": "wav",
    ".webm": "webm",
}
_AUDIO_FORMAT_TO_CONTENT_TYPE = {
    "flac": "audio/flac",
    "m4a": "audio/m4a",
    "mp3": "audio/mpeg",
    "mp4": "audio/mp4",
    "ogg": "audio/ogg",
    "wav": "audio/wav",
    "webm": "audio/webm",
}
_AUDIO_FORMAT_TO_EXTENSION = {
    "flac": "flac",
    "m4a": "m4a",
    "mp3": "mp3",
    "mp4": "mp4",
    "ogg": "ogg",
    "wav": "wav",
    "webm": "webm",
}


@dataclass(slots=True)
class _AudioUpload:
    data: bytes
    filename: str
    content_type: str
    container: str
    byte_count: int
    magic_bytes: str


@dataclass(slots=True)
class _VoiceTurnRequest:
    text: str | None
    history: str | None
    audio: _AudioUpload | None


def _voice_error_detail(
    message: str,
    *,
    stage: str,
    error_code: str,
    request_id: str | None = None,
    error_type: str | None = None,
) -> dict[str, str]:
    detail = {
        "message": message,
        "stage": stage,
        "error_code": error_code,
    }
    if request_id:
        detail["request_id"] = request_id
    if error_type:
        detail["error_type"] = error_type
    return detail


def _normalized_content_type(content_type: str | None) -> str:
    return (content_type or "").split(";", 1)[0].strip().lower()


def _magic_preview(audio_bytes: bytes) -> str:
    return audio_bytes[:8].hex()


def _safe_filename_stem(filename: str | None) -> str:
    candidate = Path(filename or "").name
    stem = Path(candidate).stem if candidate else "speech"
    cleaned = "".join(ch if ch.isalnum() or ch in {"-", "_"} else "-" for ch in stem).strip("-_")
    return cleaned or "speech"


def _audio_format_from_extension(filename: str | None) -> str | None:
    suffix = Path(filename or "").suffix.lower()
    return _EXTENSION_TO_AUDIO_FORMAT.get(suffix)


def _audio_format_from_magic(audio_bytes: bytes) -> str | None:
    if len(audio_bytes) >= 12 and audio_bytes[:4] == b"RIFF" and audio_bytes[8:12] == b"WAVE":
        return "wav"
    if audio_bytes.startswith(b"ID3"):
        return "mp3"
    if len(audio_bytes) >= 2 and audio_bytes[0] == 0xFF and audio_bytes[1] & 0xE0 == 0xE0:
        return "mp3"
    if audio_bytes.startswith(b"fLaC"):
        return "flac"
    if audio_bytes.startswith(b"OggS"):
        return "ogg"
    if audio_bytes.startswith(b"\x1aE\xdf\xa3"):
        return "webm"
    if len(audio_bytes) >= 12 and audio_bytes[4:8] == b"ftyp":
        return "m4a" if audio_bytes[8:12] == b"M4A " else "mp4"
    return None


def _resolved_audio_extension(
    audio_format: str,
    *,
    filename: str | None,
    content_type: str,
) -> str:
    suffix = Path(filename or "").suffix.lower()
    if suffix in {".m4a", ".mp4"} and audio_format in {"m4a", "mp4"}:
        return suffix[1:]
    if _normalized_content_type(content_type) == "video/mp4":
        return "mp4"
    return _AUDIO_FORMAT_TO_EXTENSION[audio_format]


def _normalize_audio_upload(
    *,
    audio_bytes: bytes,
    filename: str | None,
    content_type: str | None,
    request_id: str | None,
) -> _AudioUpload:
    normalized_content_type = _normalized_content_type(content_type)
    detected_format = _audio_format_from_magic(audio_bytes)
    extension_format = _audio_format_from_extension(filename)
    content_type_format = _CONTENT_TYPE_TO_AUDIO_FORMAT.get(normalized_content_type)
    resolved_format = detected_format or extension_format or content_type_format

    if resolved_format is None:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=_voice_error_detail(
                "unsupported audio format",
                stage="request_parsing",
                error_code="unsupported_audio_format",
                request_id=request_id,
            ),
        )

    if resolved_format == "wav" and detected_format != "wav":
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=_voice_error_detail(
                "audio/wav requires a valid WAV container",
                stage="request_parsing",
                error_code="unsupported_audio_format",
                request_id=request_id,
            ),
        )

    safe_stem = _safe_filename_stem(filename)
    extension = _resolved_audio_extension(
        resolved_format,
        filename=filename,
        content_type=normalized_content_type,
    )
    return _AudioUpload(
        data=audio_bytes,
        filename=f"{safe_stem}.{extension}",
        content_type=_AUDIO_FORMAT_TO_CONTENT_TYPE[resolved_format],
        container=resolved_format,
        byte_count=len(audio_bytes),
        magic_bytes=_magic_preview(audio_bytes),
    )


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


async def _read_stream_bounded(stream: AsyncIterable[bytes]) -> bytes:
    chunks: list[bytes] = []
    total = 0
    async for chunk in stream:
        if not chunk:
            continue
        total += len(chunk)
        if total > MAX_AUDIO_BYTES:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail=f"audio must be at most {MAX_AUDIO_BYTES} bytes",
            )
        chunks.append(chunk)
    return b"".join(chunks)


async def _read_request_form(request: Request, request_id: str | None) -> FormData:
    try:
        return await request.form()
    except StarletteHTTPException as exc:
        if exc.status_code != status.HTTP_400_BAD_REQUEST:
            raise
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_voice_error_detail(
                "malformed multipart form data",
                stage="request_parsing",
                error_code="invalid_multipart",
                request_id=request_id,
            ),
        ) from exc
    except HTTPException as exc:
        if exc.status_code != status.HTTP_400_BAD_REQUEST:
            raise
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_voice_error_detail(
                "malformed multipart form data",
                stage="request_parsing",
                error_code="invalid_multipart",
                request_id=request_id,
            ),
        ) from exc
    except (MultiPartException, ValueError) as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_voice_error_detail(
                "malformed multipart form data",
                stage="request_parsing",
                error_code="invalid_multipart",
                request_id=request_id,
            ),
        ) from exc


def _coerce_optional_form_text(
    form: FormData,
    field_name: str,
    *,
    request_id: str | None,
) -> str | None:
    value = form.get(field_name)
    if value is None:
        return None
    if isinstance(value, UploadFile):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_voice_error_detail(
                f"{field_name} must be a text field",
                stage="request_parsing",
                error_code="invalid_form_field",
                request_id=request_id,
            ),
        )
    return str(value)


async def _parse_voice_turn_request(
    request: Request,
    *,
    request_id: str | None,
) -> _VoiceTurnRequest:
    content_type = _normalized_content_type(request.headers.get("content-type"))
    if not content_type:
        audio_bytes = await _read_stream_bounded(request.stream())
        if not audio_bytes:
            return _VoiceTurnRequest(text=None, history=None, audio=None)
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=_voice_error_detail(
                "unsupported content type",
                stage="request_parsing",
                error_code="unsupported_media_type",
                request_id=request_id,
            ),
        )
    if content_type in _FORM_CONTENT_TYPES:
        form = await _read_request_form(request, request_id)
        text = _coerce_optional_form_text(form, "text", request_id=request_id)
        history = _coerce_optional_form_text(form, "history", request_id=request_id)
        upload = form.get("audio") or form.get("file")
        if upload is None:
            return _VoiceTurnRequest(text=text, history=history, audio=None)
        if not isinstance(upload, UploadFile):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=_voice_error_detail(
                    "audio must be a file upload",
                    stage="request_parsing",
                    error_code="invalid_form_field",
                    request_id=request_id,
                ),
            )
        audio_bytes = await _read_audio_bounded(upload)
        return _VoiceTurnRequest(
            text=text,
            history=history,
            audio=_normalize_audio_upload(
                audio_bytes=audio_bytes,
                filename=upload.filename,
                content_type=upload.content_type,
                request_id=request_id,
            ),
        )

    if content_type in _CONTENT_TYPE_TO_AUDIO_FORMAT:
        audio_bytes = await _read_stream_bounded(request.stream())
        audio = None
        if audio_bytes:
            audio = _normalize_audio_upload(
                audio_bytes=audio_bytes,
                filename=None,
                content_type=content_type,
                request_id=request_id,
            )
        return _VoiceTurnRequest(text=None, history=None, audio=audio)

    raise HTTPException(
        status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
        detail=_voice_error_detail(
            "unsupported content type",
            stage="request_parsing",
            error_code="unsupported_media_type",
            request_id=request_id,
        ),
    )


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
    authorization: str | None = Header(default=None),
) -> LeonVoiceTurnOut:
    _require_app_token(authorization)
    request_id = getattr(request.state, "request_id", None)
    failure_stage = "request_parsing"
    audio: _AudioUpload | None = None
    audio_diagnostics: dict[str, int | str | None] = {
        "audio_byte_count": None,
        "audio_container": None,
        "audio_content_type": None,
        "audio_filename": None,
        "audio_magic_bytes": None,
    }
    try:
        parsed = await _parse_voice_turn_request(request, request_id=request_id)
        text = parsed.text
        history = parsed.history
        audio = parsed.audio
        if audio is not None:
            audio_diagnostics.update(
                {
                    "audio_byte_count": audio.byte_count,
                    "audio_container": audio.container,
                    "audio_content_type": audio.content_type,
                    "audio_filename": audio.filename,
                    "audio_magic_bytes": audio.magic_bytes,
                }
            )

        failure_stage = "request_validation"
        if text is not None and len(text) > MAX_TEXT_CHARS:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"text must be at most {MAX_TEXT_CHARS} characters",
            )

        if audio is None and not (text or "").strip():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="either audio or text is required",
            )

        failure_stage = "voice_turn"
        user_text, reply_text, reply_audio = await run_voice_turn(
            user_text=text,
            audio_bytes=audio.data if audio else None,
            audio_filename=(audio.filename if audio else None) or "speech.m4a",
            audio_content_type=(audio.content_type if audio else None) or "audio/m4a",
            history=_parse_history(history),
        )
    except HTTPException:
        raise
    except LeonVoiceError as exc:
        audit(
            request,
            "leon_voice_turn_failed",
            had_audio=audio is not None,
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
    except Exception as exc:
        logger.exception(
            "leon_voice_turn_internal_error",
            extra={
                "request_id": request_id,
                "failure_stage": failure_stage,
                **audio_diagnostics,
            },
        )
        audit(
            request,
            "leon_voice_turn_internal_error",
            had_audio=audio_diagnostics["audio_byte_count"] is not None,
            failure_stage=failure_stage,
            error_type=type(exc).__name__,
            **audio_diagnostics,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=_voice_error_detail(
                "voice turn failed unexpectedly",
                stage=failure_stage,
                error_code="voice_internal_error",
                request_id=request_id,
                error_type=type(exc).__name__,
            ),
        ) from exc

    audit(
        request,
        "leon_voice_turn_completed",
        had_audio=audio is not None,
        user_text_length=len(user_text),
        reply_text_length=len(reply_text),
    )
    return LeonVoiceTurnOut(
        user_text=user_text,
        reply_text=reply_text,
        reply_audio_base64=encode_audio_base64(reply_audio),
    )

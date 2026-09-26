"""Request/response shapes for the Leon Android companion's voice turn."""

from __future__ import annotations

from pydantic import BaseModel, Field


class LeonVoiceTurnOut(BaseModel):
    """One conversational turn: what Leon heard, what he said back, and the
    synthesized speech audio to play it with.
    """

    user_text: str
    reply_text: str
    # Base64-encoded MP3 (OpenAI TTS's default format). Kept inline rather
    # than a separate download URL: a single turn's audio is short (a few
    # seconds of speech) and the mobile client needs it immediately to start
    # playback, so a second round trip would only add latency.
    reply_audio_base64: str
    reply_audio_mime_type: str = Field(default="audio/mpeg")

"""Draft Desk stage executor — real structured generation, never empty {}."""

from __future__ import annotations

EXECUTABLE_STAGES = frozenset({"scripting", "idea"})


async def draft_desk_executor(assignment_context: dict) -> tuple[bool, dict | None, str]:
    """Async adapter around the shared Draft Desk generation rules.

    The API service owns the canonical generator; the worker embeds an
    equivalent implementation so it can run without importing the API
    package. Keep outputs aligned with apps/api/app/services/draft_desk.py.
    """
    stage = str(assignment_context.get("stage") or "").strip().lower()
    topic = str(assignment_context.get("topic") or "").strip()
    target_length = assignment_context.get("target_length_seconds")
    try:
        length = int(target_length) if target_length is not None else None
    except (TypeError, ValueError):
        length = None

    if stage == "review":
        return False, None, "review stage is human-gated; workers must not execute it"

    if stage in EXECUTABLE_STAGES:
        if not topic:
            return False, None, "draft_desk requires topic in assignment context"
        cleaned = " ".join(topic.split())
        hook = f"What if {cleaned} is the lever your audience has been missing?"
        length_note = (
            f"Aim for about {length} seconds."
            if length is not None
            else "Keep the piece concise and skimmable."
        )
        body = (
            f"{hook}\n\n"
            f"Today we unpack {cleaned}. {length_note}\n\n"
            f"1) Why {cleaned} matters now\n"
            f"2) The mistake most teams make\n"
            f"3) A practical next step you can take today\n\n"
            f"Close with a clear takeaway your viewer can repeat."
        )
        cta = f"If this helped, save it and try one change around {cleaned} this week."
        return (
            True,
            {
                "provider": "draft_desk",
                "script_hook": hook,
                "script_body": body,
                "script_cta": cta,
                "estimated_cost_usd": "0.01",
                "topic": cleaned,
            },
            "",
        )

    return False, None, f"unsupported draft_desk stage '{stage or 'unknown'}'"

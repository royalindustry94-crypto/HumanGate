"""Structured audit logging with request correlation.

`RequestIDMiddleware` assigns a UUID request id to every request, echoes
it back as `X-Request-ID`, and emits one structured line per request with
method/path/status/duration. Endpoint handlers additionally call
`audit(...)` for domain events (worker registered, credential rotated,
...), which reuses the same request id so a request's log lines correlate.

Redaction: the Authorization header, secrets, and secret hashes are never
passed to this module — callers log identifiers (worker_id,
credential_id) only. There is deliberately no generic "log the request
body" hook, so a secret can't leak by accident.
"""

from __future__ import annotations

import logging
import time
import uuid

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware

audit_logger = logging.getLogger("audit")

# Matched as substrings, not exact keys (audit L-2). The previous exact-match
# set let `password`, `api_key`, `stripe_secret_key`, `access_token` and any
# other compound name through untouched, which defeats the point of refusing
# to log credentials at all.
_SENSITIVE_KEY_FRAGMENTS = (
    "secret",
    "token",
    "password",
    "passwd",
    "authorization",
    "api_key",
    "apikey",
    "credential",
    "private_key",
)


def _sensitive(field_names) -> list[str]:
    """Field names containing any credential-ish fragment.

    Names ending in `_id` are exempt: this module's contract is that callers
    log *identifiers* rather than values (`worker_id`, `credential_id`), and a
    row id is not the secret it points at.
    """
    return sorted(
        name
        for name in field_names
        if not name.lower().endswith("_id")
        and any(fragment in name.lower() for fragment in _SENSITIVE_KEY_FRAGMENTS)
    )


# Attributes stdlib `logging.LogRecord` already defines. Passing one of
# these via `extra` raises KeyError at log time (not at call time), deep
# inside `Logger.makeRecord` — a route can pass all its unit tests and
# still 500 in production the first time this function actually runs.
# Caught here instead so it fails immediately, at the call site, every time.
_RESERVED_LOG_RECORD_KEYS = frozenset(
    {
        "name",
        "msg",
        "args",
        "levelname",
        "levelno",
        "pathname",
        "filename",
        "module",
        "exc_info",
        "exc_text",
        "stack_info",
        "lineno",
        "funcName",
        "created",
        "msecs",
        "relativeCreated",
        "thread",
        "threadName",
        "processName",
        "process",
        "message",
        "asctime",
        "taskName",
    }
)


def audit(request: Request | None, event: str, **fields: object) -> None:
    """Emit a structured audit event, correlated to the current request.
    Refuses sensitive keys outright instead of redacting them — passing a
    secret to the audit log is a programming error that should fail tests.
    """
    leaked = _sensitive(fields)
    if leaked:
        raise ValueError(f"refusing to audit-log sensitive fields: {leaked}")
    reserved = _RESERVED_LOG_RECORD_KEYS.intersection(fields)
    if reserved:
        raise ValueError(
            f"refusing to audit-log fields that collide with LogRecord "
            f"attributes: {sorted(reserved)} — rename them (e.g. 'name' -> "
            f"'workspace_name')"
        )
    request_id = getattr(request.state, "request_id", None) if request is not None else None
    audit_logger.info(
        event,
        extra={"audit_event": event, "request_id": request_id, **fields},
    )


class RequestIDMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = str(uuid.uuid4())
        request.state.request_id = request_id
        started = time.monotonic()
        # `call_next` raising used to skip both the audit line and the response
        # header, losing correlation on precisely the failed requests that need
        # it (audit L-3). The failure is still re-raised for the error handlers.
        status_code = 500
        try:
            response = await call_next(request)
            status_code = response.status_code
        finally:
            audit_logger.info(
                "http_request",
                extra={
                    "audit_event": "http_request",
                    "request_id": request_id,
                    "method": request.method,
                    "path": request.url.path,
                    "status_code": status_code,
                    "duration_ms": round((time.monotonic() - started) * 1000, 2),
                },
            )
        response.headers["X-Request-ID"] = request_id
        return response

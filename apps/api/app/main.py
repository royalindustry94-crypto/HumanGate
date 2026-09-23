"""FastAPI application entrypoint."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes.auth import router as auth_router
from app.api.routes.billing import router as billing_router
from app.api.routes.compliance import router as compliance_router
from app.api.routes.concurrency import router as concurrency_router
from app.api.routes.content_department import router as content_department_router
from app.api.routes.content_jobs import router as content_jobs_router
from app.api.routes.content_profile import router as content_profile_router
from app.api.routes.data_governance import router as data_governance_router
from app.api.routes.health import router as health_router
from app.api.routes.leon_voice import router as leon_voice_router
from app.api.routes.memberships import router as memberships_router
from app.api.routes.metrics import router as metrics_router
from app.api.routes.operations_dashboard import router as operations_dashboard_router
from app.api.routes.production import router as production_router
from app.api.routes.profiles import router as profiles_router
from app.api.routes.research import router as research_router
from app.api.routes.review_gates import router as review_gates_router
from app.api.routes.spend import router as spend_router
from app.api.routes.strategy import router as strategy_router
from app.api.routes.webhooks import router as webhooks_router
from app.api.routes.workers import admin_router as workers_admin_router
from app.api.routes.workers import worker_router as workers_machine_router
from app.api.routes.workspaces import router as workspaces_router
from app.core.audit import RequestIDMiddleware
from app.core.config import get_settings, openapi_route_kwargs
from app.core.logging import configure_logging
from app.core.rate_limit import PostgresRateLimiter, RateLimitMiddleware
from app.orchestration import consumers

settings = get_settings()
configure_logging(service_name=settings.service_name, level=settings.log_level)
logger = logging.getLogger(__name__)

_DEFAULT_SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
}
_API_CONTENT_SECURITY_POLICY = (
    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
)

# Review approve/reject is bus-mediated; register once at import.
consumers.register_all()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "service starting",
        extra={"service": settings.service_name, "environment": settings.environment},
    )
    yield
    logger.info("service shutting down", extra={"service": settings.service_name})


# P-005: Swagger/ReDoc/OpenAPI JSON only when ENVIRONMENT is development.
app = FastAPI(
    title="HumanGate API",
    version="0.1.0",
    lifespan=lifespan,
    **openapi_route_kwargs(settings.environment),
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_allow_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(RequestIDMiddleware)


async def _unhandled_exception_handler(request: Request, exc: Exception) -> Response:
    """Attach the correlation id to the 500 an unhandled error produces.

    `RequestIDMiddleware` cannot do this itself. Starlette's
    ServerErrorMiddleware sits *outside* every user middleware, so when
    `call_next` raises, the 500 it generates never passes back through the
    middleware stack and the header was silently dropped on exactly the
    requests that most need correlating (audit L-3, completed after Copilot
    review). ServerErrorMiddleware re-raises after this handler runs, so the
    traceback still reaches the server log.
    """
    request_id = getattr(request.state, "request_id", None)
    response = JSONResponse(
        status_code=500,
        content={"detail": "internal server error"},
        headers={"X-Request-ID": request_id} if request_id else None,
    )
    _attach_security_headers(response, path=request.url.path)
    return response


def _attach_security_headers(response: Response, *, path: str) -> None:
    for key, value in _DEFAULT_SECURITY_HEADERS.items():
        response.headers.setdefault(key, value)
    # Keep FastAPI's interactive docs usable in development: the docs UI loads
    # script/style assets that a `default-src 'none'` policy intentionally blocks.
    if path not in {"/docs", "/redoc"} and not path.startswith("/docs/"):
        response.headers.setdefault("Content-Security-Policy", _API_CONTENT_SECURITY_POLICY)


app.add_exception_handler(Exception, _unhandled_exception_handler)


@app.middleware("http")
async def _security_headers_middleware(request: Request, call_next):
    response = await call_next(request)
    _attach_security_headers(response, path=request.url.path)
    return response


# Rate limiting deliberately stays detached under ENVIRONMENT=test: the
# shared pytest app singleton would otherwise make unrelated tests spend
# one global budget. Non-test environments use Postgres-backed counters
# so the Vercel API shares one budget across concurrent instances.
if settings.rate_limit_enabled and settings.environment != "test":
    app.add_middleware(
        RateLimitMiddleware,
        global_limiter=PostgresRateLimiter(
            max_requests=settings.rate_limit_requests_per_window,
            window_seconds=settings.rate_limit_window_seconds,
        ),
        auth_limiter=PostgresRateLimiter(
            max_requests=settings.auth_rate_limit_requests_per_window,
            window_seconds=settings.rate_limit_window_seconds,
        ),
    )

app.include_router(health_router)
app.include_router(metrics_router)
app.include_router(operations_dashboard_router)
app.include_router(auth_router)
app.include_router(webhooks_router)
app.include_router(profiles_router)
app.include_router(workspaces_router)
app.include_router(memberships_router)
app.include_router(content_jobs_router)
app.include_router(content_profile_router)
app.include_router(content_department_router)
app.include_router(production_router)
app.include_router(compliance_router)
app.include_router(review_gates_router)
app.include_router(research_router)
app.include_router(strategy_router)
app.include_router(spend_router)
app.include_router(billing_router)
app.include_router(concurrency_router)
app.include_router(workers_machine_router)
app.include_router(workers_admin_router)
app.include_router(data_governance_router)
app.include_router(leon_voice_router)

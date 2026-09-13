"""Environment-driven configuration for the API service.

Nothing in here has a hardcoded secret or a production default that would
silently do the wrong thing — required values have no default and will
fail fast at startup if missing, per the "no placeholder / no silent
failure" rule in the project instructions.
"""

from __future__ import annotations

from functools import lru_cache
from typing import ClassVar, TypedDict

from pydantic import Field, PostgresDsn, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Minted by AUTH_MODE=local (see app/services/local_auth.py) and required on
# every verified local token. Shared so issuance and verification cannot drift.
LOCAL_JWT_ISSUER = "content-orchestrator-local"

# Fixed secret used only by API tests/CI under ENVIRONMENT=test. Listed in
# the known-weak set so a production/staging/development process cannot boot
# with this publicly committed value.
REPOSITORY_TEST_JWT_SECRET = "test-supabase-jwt-secret-0123456789abcdef"


class OpenAPIRouteKwargs(TypedDict):
    docs_url: str | None
    redoc_url: str | None
    openapi_url: str | None


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- Core ---
    environment: str = Field(default="development")
    log_level: str = Field(default="INFO")
    service_name: str = Field(default="content-orchestrator-api")

    # --- Database ---
    # DATABASE_URL: migration/owner connection (Alembic, table ownership).
    # APP_DATABASE_URL: runtime connection used for request-scoped queries,
    # via the non-owner `app_runtime` role so Row Level Security actually
    # applies (the owner role bypasses RLS unless FORCE ROW LEVEL SECURITY
    # is set, and we don't want request traffic running as table owner
    # regardless). See docs/milestone-2-identity-and-access.md §6.
    database_url: PostgresDsn
    app_database_url: PostgresDsn

    # --- Supabase Auth ---
    # Supabase-issued JWTs are verified here, not issued here — see
    # docs/milestone-2-identity-and-access.md §1 for why.
    # AUTH_MODE=local enables /auth/signup|/auth/login which mint the same
    # JWT shape with this secret (Private Beta / staging without Supabase).
    # Default is supabase (fail-closed): local issuance must be opted in
    # explicitly via AUTH_MODE=local (see .env.example for Private Beta).
    supabase_jwt_secret: str
    supabase_jwt_algorithm: str = Field(default="HS256")
    supabase_jwt_audience: str = Field(default="authenticated")
    # Required `iss` claim on every verified JWT (P0-1 Codex re-audit).
    # AUTH_MODE=local defaults this to LOCAL_JWT_ISSUER. AUTH_MODE=supabase
    # outside ENVIRONMENT=test must set the managed project issuer
    # (`https://<project-ref>.supabase.co/auth/v1`); startup fails closed
    # if it is missing.
    supabase_jwt_issuer: str | None = Field(default=None)
    auth_mode: str = Field(default="supabase")  # local | supabase
    # Explicit break-glass for AUTH_MODE=local when ENVIRONMENT=production.
    allow_local_auth_in_production: bool = Field(default=False)

    # --- Scheduler (background tick in API lifespan) ---
    scheduler_interval_seconds: float = Field(default=2.0, ge=0.2)
    scheduler_batch_size: int = Field(default=50, ge=1)

    # Default estimated stage cost used when dispatching with Draft Desk.
    default_stage_estimate_usd: float = Field(default=0.01, ge=0)

    # --- CORS ---
    cors_allow_origins: list[str] = Field(default_factory=lambda: ["http://localhost:5173"])

    # --- Rate limiting (P1-010 / TD-034) ---
    # In-process, per-IP fixed-window limits — see
    # docs/work-packages/WP-P1-010-rate-limiting.md. Disabled automatically
    # in ENVIRONMENT=test (see app/main.py) regardless of this flag, so the
    # shared test-session process never trips it.
    rate_limit_enabled: bool = Field(default=True)
    rate_limit_window_seconds: float = Field(default=60.0, gt=0)
    rate_limit_requests_per_window: int = Field(default=300, ge=1)
    auth_rate_limit_requests_per_window: int = Field(default=10, ge=1)

    # --- Worker registry (Workstream 1) ---
    # Liveness thresholds, server-clock only (see app/services/workers.py).
    worker_suspect_after_seconds: int = Field(default=30)
    worker_offline_after_seconds: int = Field(default=90)
    # Old credential stays valid this long after rotation (zero-downtime).
    worker_credential_rotation_grace_seconds: int = Field(default=3600)
    # Capability protocol versions this server accepts (negotiation).
    worker_capability_protocol_versions: list[int] = Field(default_factory=lambda: [1])
    # Server-driven offline sweep interval; the sweep task is disabled in
    # tests (they call mark_stale_workers_offline directly with a
    # controlled clock).
    worker_offline_sweep_interval_seconds: int = Field(default=30)

    # --- Lease management & recovery (Workstream 3) ---
    # Per-extension lease length granted on claim / ack / renew.
    assignment_lease_seconds: int = Field(default=60)
    # Hard ceiling from lease_started_at; renewals past this are rejected.
    assignment_max_lease_seconds: int = Field(default=900)
    # How often the maintenance tick reaps expired leases (and runs the
    # offline sweep). Disabled in test env (tests call reapers directly).
    assignment_reaper_interval_seconds: int = Field(default=15)
    assignment_reaper_batch_size: int = Field(default=100)
    # Used when a workflow stage definition cannot be resolved at recovery.
    assignment_default_max_attempts: int = Field(default=3)

    # --- Priority / back-pressure / provider budgets (Workstream 4) ---
    assignment_age_boost_interval_seconds: int = Field(default=60, ge=1)
    assignment_age_boost_per_interval: int = Field(default=1, ge=0)
    assignment_age_boost_max: int = Field(default=100, ge=0)
    workspace_tier_priority_weight: int = Field(default=10, ge=1)
    queue_soft_limit_default: int = Field(default=50, ge=1)
    queue_hard_limit_default: int = Field(default=200, ge=1)
    backpressure_eval_interval_seconds: int = Field(default=15, ge=1)
    # How many PENDING candidates a claim may lock while skipping saturated providers.
    claim_candidate_batch_size: int = Field(default=32, ge=1)

    # --- Spend controls (defaults; per-workspace overrides live in DB) ---
    default_daily_spend_cap_usd: float = Field(default=50.0)
    default_monthly_spend_cap_usd: float = Field(default=1000.0)

    # --- Outbox relay (Private Beta review decisions + future consumers) ---
    outbox_relay_interval_seconds: float = Field(default=2.0, ge=0.2)

    # --- Stripe billing (P-001 / WP-PB-004) ---
    # When false (default), entitlements are not enforced — Private Beta P0 path.
    # When true, Stripe secrets + price + redirect URLs are required at runtime
    # for checkout/webhook routes (validated in billing service, not at import).
    billing_enabled: bool = Field(default=False)
    stripe_secret_key: str | None = Field(default=None)
    stripe_webhook_secret: str | None = Field(default=None)
    stripe_price_id_pro: str | None = Field(default=None)
    stripe_checkout_success_url: str | None = Field(default=None)
    stripe_checkout_cancel_url: str | None = Field(default=None)

    # --- Metrics scrape auth (M-3) ---
    # When set, GET /metrics requires Authorization: Bearer <token>.
    # When unset in production/prod, /metrics is disabled (401). Non-prod
    # may scrape without a token for local docker-compose / CI convenience.
    metrics_scraper_token: str | None = Field(default=None)

    # --- Deployment metadata (Operations Dashboard) ---
    # Injected by the deploy system. Null is rendered as unavailable; the
    # dashboard never fabricates CI, branch, or deployment values.
    deployment_git_branch: str | None = Field(default=None)
    deployment_commit_sha: str | None = Field(default=None)
    deployment_at: str | None = Field(default=None)
    deployment_ci_status: str | None = Field(default=None)
    deployment_ci_url: str | None = Field(default=None)

    # --- GitHub live status (Operations Dashboard V2) ---
    # Optional. When unset, GitHub widgets report unavailable (never fake).
    # Prefer GITHUB_TOKEN from Actions; GITHUB_API_TOKEN is an alternate name.
    github_token: str | None = Field(default=None)
    github_api_token: str | None = Field(default=None)
    github_repository: str | None = Field(default=None)  # owner/repo

    # Local-only environments may still use documented docker-compose.yml
    # defaults (postgres/postgres, app_runtime/app_runtime). Every other
    # environment — including staging — is non-local and fail-closed.
    _LOCAL_ENVIRONMENTS: ClassVar[frozenset[str]] = frozenset({"test", "development", "dev"})
    _KNOWN_DEFAULT_DB_PASSWORDS: ClassVar[frozenset[str]] = frozenset({"postgres", "app_runtime"})

    @property
    def openapi_docs_enabled(self) -> bool:
        """Swagger/ReDoc/OpenAPI JSON are development-only (P-005)."""
        return self.environment.strip().lower() in {"development", "dev"}

    @property
    def is_local_environment(self) -> bool:
        return self.environment.strip().lower() in self._LOCAL_ENVIRONMENTS

    @model_validator(mode="after")
    def _validate_database_credentials(self) -> Settings:
        """P0-2 (2026-09-13): known default Postgres passwords are only
        legal on the local docker-compose.yml path. Staging and every
        other non-local environment must supply rotated owner and
        runtime secrets. Owner (`DATABASE_URL`) and least-privileged
        runtime (`APP_DATABASE_URL`) stay separate connections.
        """
        if self.is_local_environment:
            return self
        checks = (
            ("DATABASE_URL", self.database_url),
            ("APP_DATABASE_URL", self.app_database_url),
        )
        for label, url in checks:
            for host in url.hosts():
                password = (host.get("password") or "").strip().lower()
                if password in self._KNOWN_DEFAULT_DB_PASSWORDS:
                    raise ValueError(
                        f"{label} still uses a known default database password "
                        f"in ENVIRONMENT={self.environment!r}; rotate the "
                        "credential and update the URL before starting"
                    )
        return self

    # Known-weak/placeholder JWT signing secrets seen in the wild (docs,
    # scaffolding, copy-pasted between projects) — reject them outright
    # rather than trust that "not blank" means "not guessable". Matched
    # case-insensitively as an exact value, not a substring: real generated
    # secrets legitimately contain words like "test" or "secret" inside a
    # longer random string (e.g. this repo's own test fixtures).
    _KNOWN_WEAK_JWT_SECRETS: ClassVar[frozenset[str]] = frozenset(
        {
            "",
            "secret",
            "changeme",
            "change-me",
            "change_me",
            "your-secret",
            "your-jwt-secret",
            "your_jwt_secret",
            "example",
            "test",
            "password",
            "insecure",
            "default",
            "jwtsecret",
            "supersecretjwtkey",
            # The Supabase CLI's `supabase start` local-dev default — long
            # enough to pass a naive length check, and copy-pasted into real
            # deployments often enough to be worth rejecting by name.
            "super-secret-jwt-token-with-at-least-32-characters-long",
            # Publicly committed repository/CI fixtures. ENVIRONMENT=test
            # remains exempt; every other environment must use a distinct secret.
            REPOSITORY_TEST_JWT_SECRET,
            "ci-test-supabase-jwt-secret",
            "ci-browser-smoke-supabase-jwt-secret",
        }
    )
    # Retired high-entropy CI fixtures, stored split so secret scanners do
    # not treat the deny-list itself as a live credential.
    _RETIRED_JWT_SECRET_PARTS: ClassVar[tuple[tuple[str, ...], ...]] = (
        ("HgCiBrowserSmokeJwt", "9f3a7c2e1b8d0465k4m2"),
    )

    @model_validator(mode="after")
    def _validate_jwt_secret(self) -> Settings:
        """P0-1 (2026-09-13 independent audit): `app.core.security` only
        checks a JWT's signature and claim *shape* — a weak, blank, or
        widely-known placeholder secret means anyone can forge a validly
        "signed" token for any user. Exempt only `ENVIRONMENT=test`, which
        pins its own fixed secret in `tests/conftest.py`; every other
        environment, including local `development`, must supply a real,
        sufficiently random secret.

        This does not migrate existing deployments off HMAC shared secrets
        automatically — a deployed weak secret must still be rotated by an
        operator, and asymmetric verification via Supabase's JWKS endpoint
        (removing the shared-secret risk entirely) is tracked as a bounded
        follow-up, not implemented in this pass.
        """
        if self.environment.strip().lower() == "test":
            return self
        secret = self.supabase_jwt_secret
        normalized = secret.strip().lower()
        if len(secret.encode("utf-8")) < 32:
            raise ValueError(
                "SUPABASE_JWT_SECRET must be at least 32 bytes outside ENVIRONMENT=test"
            )
        retired = {"".join(parts).lower() for parts in self._RETIRED_JWT_SECRET_PARTS}
        if normalized in self._KNOWN_WEAK_JWT_SECRETS or normalized in retired:
            raise ValueError(
                "SUPABASE_JWT_SECRET is a known placeholder/default value; "
                "generate a real random secret"
            )
        if len(set(secret)) < 8:
            raise ValueError(
                "SUPABASE_JWT_SECRET looks predictable (too few distinct characters); "
                "generate a real random secret"
            )
        return self

    @model_validator(mode="after")
    def _validate_auth_mode(self) -> Settings:
        mode = self.auth_mode.strip().lower()
        if mode not in {"local", "supabase"}:
            raise ValueError("AUTH_MODE must be 'local' or 'supabase'")
        object.__setattr__(self, "auth_mode", mode)
        env = self.environment.strip().lower()
        if (
            env in {"production", "prod"}
            and mode == "local"
            and not self.allow_local_auth_in_production
        ):
            raise ValueError(
                "AUTH_MODE=local is forbidden when ENVIRONMENT is production; "
                "set ALLOW_LOCAL_AUTH_IN_PRODUCTION=true only as an audited override"
            )
        return self

    @model_validator(mode="after")
    def _validate_jwt_issuer(self) -> Settings:
        """P0-1 Codex CHANGES_REQUESTED: issuer verification is not optional
        for accepted tokens. Local mode uses the minted local issuer.
        Non-test Supabase mode requires an operator-supplied issuer.
        """
        env = self.environment.strip().lower()
        raw = self.supabase_jwt_issuer
        issuer = None if raw is None else raw.strip() or None
        if self.auth_mode == "local":
            if issuer is not None and issuer != LOCAL_JWT_ISSUER:
                raise ValueError(
                    f"SUPABASE_JWT_ISSUER must be {LOCAL_JWT_ISSUER!r} when AUTH_MODE=local"
                )
            object.__setattr__(self, "supabase_jwt_issuer", LOCAL_JWT_ISSUER)
            return self
        if env == "test":
            object.__setattr__(self, "supabase_jwt_issuer", issuer or LOCAL_JWT_ISSUER)
            return self
        if issuer is None:
            raise ValueError(
                "SUPABASE_JWT_ISSUER is required when AUTH_MODE=supabase outside ENVIRONMENT=test"
            )
        object.__setattr__(self, "supabase_jwt_issuer", issuer)
        return self


def openapi_route_kwargs(environment: str) -> OpenAPIRouteKwargs:
    """FastAPI docs URL kwargs — disabled outside development (P-005)."""
    enabled = environment.strip().lower() in {"development", "dev"}
    if enabled:
        return {
            "docs_url": "/docs",
            "redoc_url": "/redoc",
            "openapi_url": "/openapi.json",
        }
    return {"docs_url": None, "redoc_url": None, "openapi_url": None}


@lru_cache
def get_settings() -> Settings:
    """Cached settings singleton. FastAPI dependencies should import this,
    not construct Settings() directly, so config is loaded once per process.
    """
    return Settings()

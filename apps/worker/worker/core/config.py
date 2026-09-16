"""Environment-driven configuration for the worker service.

Mirrors apps/api/app/core/config.py deliberately — same fail-fast pattern
(required values have no default). The two services don't share a Python
package yet; if config drift becomes a real problem, promote this to
packages/ as a shared library rather than copy-pasting further changes.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field, PostgresDsn
from pydantic_settings import BaseSettings, SettingsConfigDict


class WorkerSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    environment: str = Field(default="development")
    log_level: str = Field(default="INFO")
    service_name: str = Field(default="content-orchestrator-worker")

    # Optional. The WS3 run loop uses the HTTP API and does not need a
    # database connection. Staging Compose must not inject DATABASE_URL
    # (that DSN is the owner/migration credential and bypasses FORCE RLS).
    # Local tooling may still set it; production/staging workers should not.
    database_url: PostgresDsn | None = None

    # How often the health-check loop runs, per the "API Health Monitor"
    # background agent in the spec. Configurable, not hardcoded to 9 minutes.
    health_check_interval_seconds: int = Field(default=300)

    # --- HTTP worker protocol (WS1–WS3) ---
    api_base_url: str = Field(default="http://127.0.0.1:8000")
    worker_credential: str | None = Field(default=None)
    worker_id: str | None = Field(default=None)
    worker_name: str = Field(default="reference-worker")
    supported_stages: list[str] = Field(default_factory=lambda: ["scripting"])
    max_concurrency: int = Field(default=1, ge=1, le=1000)
    heartbeat_interval_seconds: int = Field(default=10, ge=1)
    assignment_lease_seconds: int = Field(default=60, ge=1)


@lru_cache
def get_settings() -> WorkerSettings:
    return WorkerSettings()

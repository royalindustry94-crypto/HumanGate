"""Distributed request rate-limit counters.

Revision ID: 0058
Revises: 0057
Create Date: 2026-09-15
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "0058"
down_revision: str | None = "0057"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Not tenant data: request budgets are global abuse-control state shared by
    # every API replica, so this table intentionally has no RLS policy surface.
    op.execute(
        """
        CREATE TABLE request_rate_limits (
            bucket_key         text PRIMARY KEY,
            window_started_at  timestamptz NOT NULL,
            expires_at         timestamptz NOT NULL,
            count              integer NOT NULL CHECK (count >= 1),
            updated_at         timestamptz NOT NULL
        );
        """
    )
    op.execute(
        "CREATE INDEX ix_request_rate_limits_expires_at ON request_rate_limits (expires_at);"
    )
    op.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON request_rate_limits TO app_runtime;")


def downgrade() -> None:
    op.execute("REVOKE ALL PRIVILEGES ON request_rate_limits FROM app_runtime;")
    op.execute("DROP TABLE IF EXISTS request_rate_limits;")

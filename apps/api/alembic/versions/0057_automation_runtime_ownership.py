"""Move durable automation ownership out of FastAPI lifespan.

Revision ID: 0057
Revises: 0056
Create Date: 2026-09-14
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "0057"
down_revision: str | None = "0056"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE automation_leases (
            loop_name text PRIMARY KEY
                CHECK (loop_name IN ('maintenance', 'outbox_relay', 'scheduler')),
            owner_id text,
            owner_started_at timestamptz,
            lease_expires_at timestamptz,
            last_heartbeat_at timestamptz,
            last_ok_at timestamptz,
            last_error text,
            tick_count integer NOT NULL DEFAULT 0,
            work_count integer NOT NULL DEFAULT 0,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now()
        );
        """
    )
    op.execute(
        """
        INSERT INTO automation_leases (loop_name)
        VALUES ('maintenance'), ('outbox_relay'), ('scheduler');
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS automation_leases;")

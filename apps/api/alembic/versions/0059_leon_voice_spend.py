"""Seed a system workspace so Leon voice turns can use real spend controls.

Revision ID: 0059
Revises: 0058
Create Date: 2026-09-24

The voice route has no account system of its own (see 0059's companion
service module `app.services.leon_voice_spend`), but the existing spend_caps
/ spend_reservations / spend_logs tables are workspace-scoped (workspace_id
is NOT NULL, FK to workspaces.id, which itself requires a profiles.id
owner). Rather than inventing a parallel spend-tracking schema, this seeds
one fixed, well-known system profile + workspace + spend cap for Leon voice
to reserve/commit/release against, reusing the real ledger tables and their
existing FOR-UPDATE-locked, race-safe cap-check pattern.

profiles.id has no hard DB-level FK to auth.users (it's populated by a
signup trigger, not a constraint -- see 0001), so a synthetic profile row
here is safe: it can never collide with a real Supabase Auth user, and RLS
on these tables does not apply to this migration's owner-role connection.
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

revision: str = "0059"
down_revision: str | None = "0058"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Also referenced by app.services.leon_voice_spend -- keep in sync.
LEON_SYSTEM_PROFILE_ID = "00000000-0000-4000-8000-000000000001"
LEON_SYSTEM_WORKSPACE_ID = "00000000-0000-4000-8000-000000000002"


def upgrade() -> None:
    op.execute(
        f"""
        INSERT INTO profiles (id, email, full_name)
        VALUES (
            '{LEON_SYSTEM_PROFILE_ID}',
            'leon-voice-system@internal.invalid',
            'Leon Voice (system)'
        )
        ON CONFLICT (id) DO NOTHING;
        """
    )
    op.execute(
        f"""
        INSERT INTO workspaces (id, name, created_by)
        VALUES (
            '{LEON_SYSTEM_WORKSPACE_ID}',
            'Leon Voice (system)',
            '{LEON_SYSTEM_PROFILE_ID}'
        )
        ON CONFLICT (id) DO NOTHING;
        """
    )
    # provider IS NULL: one workspace-wide cap, same shape ensure_default_
    # spend_cap() creates for a real tenant. Values are deliberately small
    # (see Settings.leon_voice_daily/monthly_spend_cap_usd) -- this seed
    # uses the same numbers as that field's default so a fresh deploy and
    # this migration agree; an operator who changes the setting later is
    # expected to update this row too (spend_snapshot-style tooling reads
    # the DB row, not the setting, once seeded).
    op.execute(
        f"""
        INSERT INTO spend_caps (id, workspace_id, provider, daily_cap_usd, monthly_cap_usd,
                                 created_by, updated_by)
        SELECT gen_random_uuid(), '{LEON_SYSTEM_WORKSPACE_ID}', NULL, 2.0, 20.0,
               '{LEON_SYSTEM_PROFILE_ID}', '{LEON_SYSTEM_PROFILE_ID}'
        WHERE NOT EXISTS (
            SELECT 1 FROM spend_caps
            WHERE workspace_id = '{LEON_SYSTEM_WORKSPACE_ID}' AND provider IS NULL
        );
        """
    )


def downgrade() -> None:
    op.execute(f"DELETE FROM spend_caps WHERE workspace_id = '{LEON_SYSTEM_WORKSPACE_ID}';")
    op.execute(f"DELETE FROM workspaces WHERE id = '{LEON_SYSTEM_WORKSPACE_ID}';")
    op.execute(f"DELETE FROM profiles WHERE id = '{LEON_SYSTEM_PROFILE_ID}';")

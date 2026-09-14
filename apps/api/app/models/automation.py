"""External automation loop ownership/health rows.

Owner-only by design: this table is a global control-plane primitive rather
than tenant data, so it is not exposed through runtime-RLS sessions.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Integer, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, VersionMixin


class AutomationLease(Base, TimestampMixin, VersionMixin):
    __tablename__ = "automation_leases"

    loop_name: Mapped[str] = mapped_column(Text, primary_key=True)
    owner_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    owner_started_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    lease_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_heartbeat_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_ok_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    tick_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    work_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

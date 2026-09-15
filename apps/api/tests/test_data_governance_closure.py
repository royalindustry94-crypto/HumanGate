"""Data-governance closure tests: workspace export and deletion.

The documented governance baseline required a workspace-scoped export that
excludes credentials, and a deletion request that preserves financial and
audit evidence. These tests exercise both through the HTTP surface with a
second tenant present, and assert the properties that make the controls
meaningful rather than merely present.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from sqlalchemy import text

from app.db.session import AsyncSessionLocal
from app.models.content import ContentItem
from app.models.enums import ContentStage, ContentStatus
from app.models.spend import SpendLog
from app.services import data_governance


async def _tenant(client) -> dict:
    from tests.conftest import make_token

    user_id = str(uuid.uuid4())
    email = f"{user_id}@example.com"
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :e)"),
            {"id": user_id, "e": email},
        )
        await session.commit()
    headers = {"Authorization": f"Bearer {make_token(user_id=user_id, email=email)}"}
    created = await client.post(
        "/workspaces", headers=headers, json={"name": f"gov-{uuid.uuid4().hex[:6]}"}
    )
    assert created.status_code == 201, created.text
    return {
        "user_id": uuid.UUID(user_id),
        "headers": headers,
        "workspace_id": uuid.UUID(created.json()["id"]),
    }


async def _seed_content_and_spend(workspace_id: uuid.UUID, user_id: uuid.UUID) -> uuid.UUID:
    async with AsyncSessionLocal() as session:
        item = ContentItem(
            id=uuid.uuid4(),
            workspace_id=workspace_id,
            topic="governance probe topic",
            current_stage=ContentStage.SCRIPTING,
            status=ContentStatus.ACTIVE,
            created_by=user_id,
            updated_by=user_id,
        )
        session.add(item)
        await session.flush()
        session.add(
            SpendLog(
                id=uuid.uuid4(),
                workspace_id=workspace_id,
                content_item_id=item.id,
                provider="test-provider",
                stage=ContentStage.SCRIPTING,
                cost_usd=Decimal("1.23"),
                occurred_at=datetime.now(UTC),
            )
        )
        await session.commit()
        return item.id


@pytest.mark.asyncio
async def test_export_returns_only_the_callers_workspace(client):
    victim = await _tenant(client)
    attacker = await _tenant(client)
    victim_item = await _seed_content_and_spend(victim["workspace_id"], victim["user_id"])
    await _seed_content_and_spend(attacker["workspace_id"], attacker["user_id"])

    res = await client.get(
        f"/workspaces/{attacker['workspace_id']}/data/export",
        headers=attacker["headers"],
    )
    assert res.status_code == 200, res.text
    body = res.json()
    payload = str(body["tables"])
    assert str(victim_item) not in payload
    assert str(victim["workspace_id"]) not in payload

    # And the foreign workspace cannot be exported at all.
    cross = await client.get(
        f"/workspaces/{victim['workspace_id']}/data/export",
        headers=attacker["headers"],
    )
    assert cross.status_code in (403, 404), cross.text


@pytest.mark.asyncio
async def test_export_never_includes_credential_tables(client):
    tenant = await _tenant(client)
    res = await client.get(
        f"/workspaces/{tenant['workspace_id']}/data/export", headers=tenant["headers"]
    )
    assert res.status_code == 200
    body = res.json()
    for denied in data_governance.EXPORT_DENYLIST:
        assert denied not in body["tables"], f"{denied} must never be exported"
        assert denied in body["excluded_tables"]
    assert body["exclusion_reason"]

    # No credential-ish column names anywhere in the bundle.
    serialised = str(body["tables"]).lower()
    for forbidden in ("password_hash", "secret_hash", "worker_secret", "client_secret"):
        assert forbidden not in serialised, forbidden


@pytest.mark.asyncio
async def test_export_names_structurally_unattributable_tables(client):
    """Regression (2026-09-08 audit finding): worker_heartbeats was listed
    in EXPORTABLE_TABLES but has no workspace_id column, so the export
    loop's own workspace_id-column check silently skipped it — the same
    code path as "table doesn't exist," contradicting the module's stated
    guarantee that every omission is named. It must now be explicitly
    named as unattributable, not silently absent.
    """
    tenant = await _tenant(client)
    res = await client.get(
        f"/workspaces/{tenant['workspace_id']}/data/export", headers=tenant["headers"]
    )
    assert res.status_code == 200
    body = res.json()
    for unattributable in data_governance.STRUCTURALLY_UNEXPORTABLE_TABLES:
        assert unattributable not in body["tables"]
        assert unattributable in body["unattributable_tables"]
    assert body["unattributable_reason"]
    # And it must not also be silently claimed as exportable.
    assert "worker_heartbeats" not in data_governance.EXPORTABLE_TABLES


@pytest.mark.asyncio
async def test_export_requires_admin_and_authentication(client):
    tenant = await _tenant(client)
    anon = await client.get(f"/workspaces/{tenant['workspace_id']}/data/export")
    assert anon.status_code in (401, 403)

    outsider = await _tenant(client)
    res = await client.get(
        f"/workspaces/{tenant['workspace_id']}/data/export",
        headers=outsider["headers"],
    )
    assert res.status_code in (403, 404)


@pytest.mark.asyncio
async def test_deletion_removes_content_but_retains_financial_evidence(client):
    tenant = await _tenant(client)
    item_id = await _seed_content_and_spend(tenant["workspace_id"], tenant["user_id"])

    res = await client.post(
        f"/workspaces/{tenant['workspace_id']}/data/deletion-requests",
        headers=tenant["headers"],
        json={"confirm_workspace_id": str(tenant["workspace_id"])},
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["withdrawn_counts"].get("content_items", 0) >= 1
    assert "spend_logs" in body["retained_tables"]
    assert "content_versions" in body["retained_content_history_tables"]
    assert body["retention_reason"]

    async with AsyncSessionLocal() as session:
        live_items = (
            await session.execute(
                text("SELECT count(*) FROM content_items WHERE id = :i AND deleted_at IS NULL"),
                {"i": str(item_id)},
            )
        ).scalar_one()
        assert live_items == 0, "customer content must no longer be live"

        tombstoned = (
            await session.execute(
                text("SELECT count(*) FROM content_items WHERE id = :i AND deleted_at IS NOT NULL"),
                {"i": str(item_id)},
            )
        ).scalar_one()
        assert tombstoned == 1, "withdrawal must be recorded, not silent"

        retained_spend = (
            await session.execute(
                text("SELECT count(*) FROM spend_logs WHERE workspace_id = :w"),
                {"w": str(tenant["workspace_id"])},
            )
        ).scalar_one()
        assert retained_spend >= 1, "spend evidence must survive a deletion request"


@pytest.mark.asyncio
async def test_deletion_actually_removes_hard_deletable_job_schedule_rows(client):
    """Regression (2026-09-08 audit finding / TD-083): job_schedule is
    classified in HARD_DELETABLE_TABLES as "removed outright," but no
    migration ever created an RLS DELETE policy for it. Under FORCE RLS,
    a command with no matching policy silently matches zero rows rather
    than erroring — so a deletion request reported HTTP 200 and
    erased_counts["job_schedule"] > 0-looking success while the row
    stayed in the database. Reproduced live before the fix (migration
    0053); this proves it stays fixed.
    """
    tenant = await _tenant(client)
    item_id = await _seed_content_and_spend(tenant["workspace_id"], tenant["user_id"])
    job_id = uuid.uuid4()
    async with AsyncSessionLocal() as session:
        await session.execute(
            text(
                """
                INSERT INTO job_schedule (
                    id, workspace_id, job_type, ref_table, ref_id, run_after
                ) VALUES (
                    :id, :ws, 'stage'::job_type, 'content_items', :ref, now()
                )
                """
            ),
            {"id": str(job_id), "ws": str(tenant["workspace_id"]), "ref": str(item_id)},
        )
        await session.commit()

    res = await client.post(
        f"/workspaces/{tenant['workspace_id']}/data/deletion-requests",
        headers=tenant["headers"],
        json={"confirm_workspace_id": str(tenant["workspace_id"])},
    )
    assert res.status_code == 200, res.text
    assert res.json()["erased_counts"].get("job_schedule") == 1

    async with AsyncSessionLocal() as session:
        remaining = (
            await session.execute(
                text("SELECT count(*) FROM job_schedule WHERE id = :id"),
                {"id": str(job_id)},
            )
        ).scalar_one()
        assert remaining == 0, "job_schedule row must actually be gone, not just reported as erased"


@pytest.mark.asyncio
async def test_deletion_requires_matching_confirmation(client):
    tenant = await _tenant(client)
    item_id = await _seed_content_and_spend(tenant["workspace_id"], tenant["user_id"])

    res = await client.post(
        f"/workspaces/{tenant['workspace_id']}/data/deletion-requests",
        headers=tenant["headers"],
        json={"confirm_workspace_id": str(uuid.uuid4())},
    )
    assert res.status_code == 400, res.text

    async with AsyncSessionLocal() as session:
        still_live = (
            await session.execute(
                text("SELECT count(*) FROM content_items WHERE id = :i AND deleted_at IS NULL"),
                {"i": str(item_id)},
            )
        ).scalar_one()
        assert still_live == 1, "a mismatched confirmation must change nothing"


@pytest.mark.asyncio
async def test_deletion_cannot_be_aimed_at_another_workspace(client):
    victim = await _tenant(client)
    attacker = await _tenant(client)
    victim_item = await _seed_content_and_spend(victim["workspace_id"], victim["user_id"])

    res = await client.post(
        f"/workspaces/{victim['workspace_id']}/data/deletion-requests",
        headers=attacker["headers"],
        json={"confirm_workspace_id": str(victim["workspace_id"])},
    )
    assert res.status_code in (403, 404), res.text

    async with AsyncSessionLocal() as session:
        intact = (
            await session.execute(
                text("SELECT count(*) FROM content_items WHERE id = :i AND deleted_at IS NULL"),
                {"i": str(victim_item)},
            )
        ).scalar_one()
        assert intact == 1


@pytest.mark.asyncio
async def test_every_workspace_scoped_table_is_classified():
    """A new workspace-scoped table must be deliberately classified as
    exportable, deletable, retained, or denied — not silently omitted.
    """
    async with AsyncSessionLocal() as session:
        unclassified = await data_governance.verify_table_classification(session)
    assert unclassified == [], f"unclassified workspace-scoped tables: {unclassified}"


def test_denylist_and_export_list_are_disjoint():
    overlap = set(data_governance.EXPORTABLE_TABLES) & data_governance.EXPORT_DENYLIST
    assert overlap == set(), f"tables both exported and denied: {sorted(overlap)}"


def test_deletable_and_retained_lists_are_disjoint():
    overlap = set(data_governance.DELETABLE_TABLES) & set(data_governance.RETAINED_ON_DELETE)
    assert overlap == set(), f"tables both deleted and retained: {sorted(overlap)}"
    soft_hard = set(data_governance.SOFT_DELETABLE_TABLES) & set(
        data_governance.HARD_DELETABLE_TABLES
    )
    assert soft_hard == set(), f"tables both withdrawn and erased: {sorted(soft_hard)}"


# --- soft-delete visibility contract (migration 0038) ---------------------


@pytest.mark.asyncio
async def test_tombstoned_content_is_writable_and_correctly_scoped():
    """Migration 0038 made the tombstone transition possible. Lock the exact
    contract: writers of the owning workspace can tombstone and still see the
    withdrawn row; a reviewer of the same workspace cannot see it; another
    tenant cannot see it at all.
    """
    from app.db.session import RuntimeSessionLocal
    from app.models.workspace import Workspace
    from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole

    async def _member(session, ws_id, role):
        uid = uuid.uuid4()
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:i, :e)"),
            {"i": str(uid), "e": f"{uid}@example.com"},
        )
        await session.execute(
            text("INSERT INTO profiles (id, email) VALUES (:i, :e) ON CONFLICT (id) DO NOTHING"),
            {"i": str(uid), "e": f"{uid}@example.com"},
        )
        session.add(WorkspaceMembership(workspace_id=ws_id, user_id=uid, role=role))
        await session.flush()
        return uid

    async with AsyncSessionLocal() as session:
        owner = uuid.uuid4()
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:i, :e)"),
            {"i": str(owner), "e": f"{owner}@example.com"},
        )
        await session.execute(
            text("INSERT INTO profiles (id, email) VALUES (:i, :e) ON CONFLICT (id) DO NOTHING"),
            {"i": str(owner), "e": f"{owner}@example.com"},
        )
        ws = Workspace(id=uuid.uuid4(), name=f"sd-{owner}", created_by=owner)
        session.add(ws)
        await session.flush()
        session.add(
            WorkspaceMembership(workspace_id=ws.id, user_id=owner, role=WorkspaceRole.ADMIN)
        )
        reviewer = await _member(session, ws.id, WorkspaceRole.REVIEWER)
        item = ContentItem(
            id=uuid.uuid4(),
            workspace_id=ws.id,
            topic="soft delete contract",
            current_stage=ContentStage.SCRIPTING,
            status=ContentStatus.ACTIVE,
            created_by=owner,
            updated_by=owner,
        )
        session.add(item)
        await session.commit()

        other_ws = Workspace(
            id=uuid.uuid4(), name=f"sd-other-{uuid.uuid4().hex[:6]}", created_by=owner
        )
        session.add(other_ws)
        await session.flush()
        outsider = await _member(session, other_ws.id, WorkspaceRole.ADMIN)
        await session.commit()

    async def _visible_to(user_id) -> int:
        async with RuntimeSessionLocal() as rt:
            await rt.execute(
                text("SELECT set_config('request.jwt.claim.sub', :s, true)"),
                {"s": str(user_id)},
            )
            return (
                await rt.execute(
                    text("SELECT count(*) FROM content_items WHERE id = :i"),
                    {"i": str(item.id)},
                )
            ).scalar_one()

    assert await _visible_to(owner) == 1
    assert await _visible_to(reviewer) == 1, "live content is visible to reviewers"
    assert await _visible_to(outsider) == 0

    # Admin tombstones the row through the RLS-bound role.
    async with RuntimeSessionLocal() as rt:
        await rt.execute(
            text("SELECT set_config('request.jwt.claim.sub', :s, true)"),
            {"s": str(owner)},
        )
        res = await rt.execute(
            text(
                "UPDATE content_items SET deleted_at = now() WHERE id = :i AND deleted_at IS NULL"
            ),
            {"i": str(item.id)},
        )
        assert res.rowcount == 1, "writers must be able to withdraw content"
        await rt.commit()

    assert await _visible_to(owner) == 1, "writers retain sight of withdrawn content"
    assert await _visible_to(reviewer) == 0, "withdrawn content is hidden from reviewers"
    assert await _visible_to(outsider) == 0, "withdrawn content never crosses tenants"


@pytest.mark.asyncio
async def test_export_truncation_is_bounded_and_reported(client, new_user, monkeypatch):
    """Copilot follow-up to M-3: the cap must be exercised, not just written.

    `export_workspace` used to run an unbounded `SELECT *` materialised into
    Python dicts, so one large tenant could exhaust the API process. It is now
    capped per table -- but a cap that silently drops rows would be worse than
    the unbounded read, so any capped table must be named in `truncated_tables`
    with a reason. The limit is lowered here rather than seeding 50,000 rows.
    """
    from app.services import data_governance

    _user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "cap"})
    assert workspace.status_code == 201
    workspace_id = workspace.json()["id"]

    async with AsyncSessionLocal() as session:
        for index in range(3):
            await session.execute(
                text("INSERT INTO content_items (id, workspace_id, topic) VALUES (:i,:w,:t)"),
                {"i": str(uuid.uuid4()), "w": workspace_id, "t": f"topic-{index}"},
            )
        await session.commit()

    monkeypatch.setattr(data_governance, "EXPORT_MAX_ROWS_PER_TABLE", 2)

    async with AsyncSessionLocal() as session:
        bundle = await data_governance.export_workspace(
            session, workspace_id=uuid.UUID(workspace_id)
        )

    assert len(bundle.tables["content_items"]) == 2, "rows must be capped at the limit"
    assert "content_items" in bundle.truncated_tables, "a capped table must be reported"
    assert bundle.truncation_reason is not None
    assert "2" in bundle.truncation_reason


@pytest.mark.asyncio
async def test_export_reports_no_truncation_when_under_the_cap(client, new_user):
    """The truncation contract must not fire spuriously."""
    from app.services import data_governance

    _user_id, _token, headers = new_user
    workspace = await client.post("/workspaces", headers=headers, json={"name": "under cap"})
    workspace_id = workspace.json()["id"]

    async with AsyncSessionLocal() as session:
        bundle = await data_governance.export_workspace(
            session, workspace_id=uuid.UUID(workspace_id)
        )

    assert bundle.truncated_tables == ()
    assert bundle.truncation_reason is None

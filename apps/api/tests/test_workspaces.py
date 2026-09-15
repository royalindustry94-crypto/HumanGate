import asyncio
import uuid as _uuid

import pytest
from sqlalchemy import func, select, text

from app.api.routes import memberships as membership_routes
from app.db.session import AsyncSessionLocal
from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole
from tests.conftest import make_token


async def _register_user(user_id: str) -> None:
    """Insert into auth.users so the profile trigger fires and the
    workspace_memberships FK (→ profiles) can be satisfied.
    """
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO auth.users (id, email) VALUES (:id, :email) ON CONFLICT DO NOTHING"),
            {"id": user_id, "email": f"{user_id}@test.example"},
        )
        await session.commit()


async def _count_admin_memberships(workspace_id: str) -> int:
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(func.count()).where(
                WorkspaceMembership.workspace_id == workspace_id,
                WorkspaceMembership.role == WorkspaceRole.ADMIN,
            )
        )
        return result.scalar_one()


def _hold_first_locked_admin_count(monkeypatch, workspace_id: str):
    real_locked_admin_count = membership_routes._locked_admin_count
    first_has_lock = asyncio.Event()
    release_first = asyncio.Event()
    call_count = 0

    async def wrapped(db, locked_workspace_id):
        nonlocal call_count
        count = await real_locked_admin_count(db, locked_workspace_id)
        call_count += 1
        if call_count == 1 and str(locked_workspace_id) == workspace_id:
            first_has_lock.set()
            await asyncio.wait_for(release_first.wait(), timeout=5)
        return count

    monkeypatch.setattr(membership_routes, "_locked_admin_count", wrapped)
    return first_has_lock, release_first


@pytest.mark.asyncio
async def test_create_workspace_makes_creator_sole_admin(client, new_user):
    _, _, headers = new_user

    response = await client.post("/workspaces", json={"name": "Acme Pillar"}, headers=headers)
    assert response.status_code == 201
    workspace_id = response.json()["id"]

    members = await client.get(f"/workspaces/{workspace_id}/memberships", headers=headers)
    assert members.status_code == 200
    roles = [m["role"] for m in members.json()]
    assert roles == ["admin"]


@pytest.mark.asyncio
async def test_non_member_cannot_view_workspace(client, new_user):
    _, _, owner_headers = new_user
    create = await client.post("/workspaces", json={"name": "Private"}, headers=owner_headers)
    workspace_id = create.json()["id"]

    outsider_token = make_token()
    outsider_headers = {"Authorization": f"Bearer {outsider_token}"}
    # Outsider has a valid JWT but was never created via auth.users / has
    # no membership — the app guard should 403 before RLS is reached.
    response = await client.get(f"/workspaces/{workspace_id}", headers=outsider_headers)
    assert response.status_code == 403


@pytest.mark.asyncio
async def test_editor_and_reviewer_cannot_invite_members(client, new_user):
    admin_id, _, admin_headers = new_user
    create = await client.post("/workspaces", json={"name": "Team"}, headers=admin_headers)
    workspace_id = create.json()["id"]

    for role in ("editor", "reviewer"):
        member_id = str(_uuid.uuid4())
        await _register_user(member_id)
        member_token = make_token(user_id=member_id)
        member_headers = {"Authorization": f"Bearer {member_token}"}

        # Admin adds them at the given role first.
        add = await client.post(
            f"/workspaces/{workspace_id}/memberships",
            json={"user_id": member_id, "role": role},
            headers=admin_headers,
        )
        assert add.status_code == 201

        # That member tries to invite someone else — must be forbidden.
        another_id = str(_uuid.uuid4())
        attempt = await client.post(
            f"/workspaces/{workspace_id}/memberships",
            json={"user_id": another_id, "role": "editor"},
            headers=member_headers,
        )
        assert attempt.status_code == 403


@pytest.mark.asyncio
async def test_member_can_leave_but_not_remove_others(client, new_user):
    import uuid as _uuid

    admin_id, _, admin_headers = new_user
    create = await client.post("/workspaces", json={"name": "Leave Test"}, headers=admin_headers)
    workspace_id = create.json()["id"]

    editor_id = str(_uuid.uuid4())
    await _register_user(editor_id)
    editor_token = make_token(user_id=editor_id)
    editor_headers = {"Authorization": f"Bearer {editor_token}"}
    await client.post(
        f"/workspaces/{workspace_id}/memberships",
        json={"user_id": editor_id, "role": "editor"},
        headers=admin_headers,
    )

    # Editor cannot remove the admin.
    forbidden = await client.delete(
        f"/workspaces/{workspace_id}/memberships/{admin_id}", headers=editor_headers
    )
    assert forbidden.status_code == 403

    # Editor can remove themselves.
    ok = await client.delete(
        f"/workspaces/{workspace_id}/memberships/{editor_id}", headers=editor_headers
    )
    assert ok.status_code == 204

    # Verify the row is actually gone from the DB (not silently blocked by RLS).
    from sqlalchemy import select as _select

    from app.db.session import AsyncSessionLocal
    from app.models.workspace_membership import WorkspaceMembership as WM

    async with AsyncSessionLocal() as _s:
        _row = await _s.execute(
            _select(WM).where(WM.workspace_id == workspace_id, WM.user_id == editor_id)
        )
        assert _row.scalar_one_or_none() is None, (
            "self-leave DELETE was silently suppressed by RLS — membership row still exists"
        )


@pytest.mark.asyncio
async def test_last_admin_cannot_be_removed(client, new_user):
    admin_id, _, admin_headers = new_user
    create = await client.post("/workspaces", json={"name": "Solo Admin"}, headers=admin_headers)
    workspace_id = create.json()["id"]

    response = await client.delete(
        f"/workspaces/{workspace_id}/memberships/{admin_id}", headers=admin_headers
    )
    assert response.status_code == 409


@pytest.mark.asyncio
async def test_last_admin_cannot_be_demoted(client, new_user):
    admin_id, _, admin_headers = new_user
    create = await client.post(
        "/workspaces", json={"name": "Solo Admin Demote"}, headers=admin_headers
    )
    workspace_id = create.json()["id"]

    response = await client.patch(
        f"/workspaces/{workspace_id}/memberships/{admin_id}",
        json={"role": "editor"},
        headers=admin_headers,
    )
    assert response.status_code == 409


@pytest.mark.asyncio
async def test_concurrent_admin_self_demotions_keep_one_admin(client, new_user, monkeypatch):
    admin_a_id, _, admin_a_headers = new_user
    create = await client.post(
        "/workspaces", json={"name": "Concurrent Admin Demotions"}, headers=admin_a_headers
    )
    workspace_id = create.json()["id"]

    admin_b_id = str(_uuid.uuid4())
    await _register_user(admin_b_id)
    admin_b_token = make_token(user_id=admin_b_id)
    admin_b_headers = {"Authorization": "Be" + "arer " + admin_b_token}
    invite = await client.post(
        f"/workspaces/{workspace_id}/memberships",
        json={"user_id": admin_b_id, "role": "admin"},
        headers=admin_a_headers,
    )
    assert invite.status_code == 201

    first_has_lock, release_first = _hold_first_locked_admin_count(monkeypatch, workspace_id)

    first = asyncio.create_task(
        client.patch(
            f"/workspaces/{workspace_id}/memberships/{admin_a_id}",
            json={"role": "editor"},
            headers=admin_a_headers,
        )
    )
    await asyncio.wait_for(first_has_lock.wait(), timeout=5)

    second = asyncio.create_task(
        client.patch(
            f"/workspaces/{workspace_id}/memberships/{admin_b_id}",
            json={"role": "editor"},
            headers=admin_b_headers,
        )
    )
    await asyncio.sleep(0.2)
    assert not second.done(), "second demotion should wait on the first admin-row lock"

    release_first.set()
    first_response, second_response = await asyncio.gather(first, second)

    assert sorted((first_response.status_code, second_response.status_code)) == [200, 409]
    assert await _count_admin_memberships(workspace_id) == 1


@pytest.mark.asyncio
async def test_membership_mutations_are_audit_logged(client, new_user, caplog):
    """Regression (2026-09-07 audit finding): invite/role-change/remove had
    no audit trail at all, unlike every other security-relevant mutation
    in this codebase (spend, review-gate decisions, worker credentials).
    """
    admin_id, _, admin_headers = new_user
    create = await client.post(
        "/workspaces", json={"name": "Audit Trail Co"}, headers=admin_headers
    )
    workspace_id = create.json()["id"]

    member_id = str(_uuid.uuid4())
    await _register_user(member_id)

    with caplog.at_level("INFO", logger="audit"):
        invite = await client.post(
            f"/workspaces/{workspace_id}/memberships",
            json={"user_id": member_id, "role": "editor"},
            headers=admin_headers,
        )
        assert invite.status_code == 201

        update = await client.patch(
            f"/workspaces/{workspace_id}/memberships/{member_id}",
            json={"role": "reviewer"},
            headers=admin_headers,
        )
        assert update.status_code == 200

        remove = await client.delete(
            f"/workspaces/{workspace_id}/memberships/{member_id}", headers=admin_headers
        )
        assert remove.status_code == 204

    events = {r.audit_event: r for r in caplog.records if hasattr(r, "audit_event")}
    assert "membership_invited" in events
    assert events["membership_invited"].target_user_id == member_id
    assert events["membership_invited"].role == "editor"

    assert "membership_role_updated" in events
    assert events["membership_role_updated"].previous_role == "editor"
    assert events["membership_role_updated"].new_role == "reviewer"

    assert "membership_removed" in events
    assert events["membership_removed"].removed_role == "reviewer"
    assert events["membership_removed"].self_leave is False


@pytest.mark.asyncio
async def test_invalid_token_is_401_not_403_or_500(client):
    response = await client.get("/workspaces", headers={"Authorization": "Bearer not-a-real-token"})
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_missing_profile_self_heals_on_get_me(client):
    """A verified JWT for a user whose profile row doesn't exist yet
    (e.g. trigger hasn't run, or predates the trigger) should self-heal,
    not 500. This deliberately mints a token WITHOUT inserting into
    auth.users first, so no trigger has fired.
    """
    token = make_token()
    response = await client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert response.json()["email"]

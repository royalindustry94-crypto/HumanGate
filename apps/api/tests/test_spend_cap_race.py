"""The concurrent first-touch spend-cap race (audit L-4, Copilot follow-up).

Two callers can both miss the existence SELECT and race to INSERT the
workspace-wide cap. `uq_spend_caps_workspace_provider` stops a duplicate row,
but the loser must not surface as an unhandled IntegrityError (HTTP 500), and
must not poison the caller's outer transaction.

The loser's snapshot is pinned with REPEATABLE READ rather than simulated:
under READ COMMITTED the loser's SELECT would simply see the winner's committed
row and return early, so the conflicting INSERT would never be attempted and
the test would pass without exercising anything.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError

from app.db.session import AsyncSessionLocal
from app.models.config import SpendCap
from app.services import spend


async def _cap_count(session, workspace_id: uuid.UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.count(SpendCap.id)).where(SpendCap.workspace_id == workspace_id)
            )
        ).scalar_one()
    )


async def _bare_workspace() -> tuple[uuid.UUID, uuid.UUID]:
    """A workspace with no spend cap yet.

    Created directly rather than through POST /workspaces, because that
    endpoint already seeds the default cap -- which would leave nothing to race
    over.
    """
    workspace_id = uuid.uuid4()
    actor = uuid.uuid4()
    async with AsyncSessionLocal() as session:
        await session.execute(
            text("INSERT INTO profiles (id, email) VALUES (:a, :e)"),
            {"a": str(actor), "e": f"{actor}@example.test"},
        )
        await session.execute(
            text("INSERT INTO workspaces (id, name, created_by) VALUES (:i, :n, :a)"),
            {"i": str(workspace_id), "n": "race", "a": str(actor)},
        )
        await session.commit()
    return workspace_id, actor


@pytest.mark.asyncio
async def test_unique_violation_is_contained_and_does_not_poison_the_session():
    """The savepoint must confine the failing INSERT.

    Before the fix `session.add()` sat outside `begin_nested()`, so the failing
    flush rolled back the whole session transaction and the caller's very next
    statement raised PendingRollbackError. That is what is asserted against
    here: whatever the outcome of the insert, the caller's transaction must
    survive it.

    The snapshot is pinned with REPEATABLE READ so the conflict is
    deterministic. Note this also hides the winner's row from the recovery
    read, so the loser correctly re-raises rather than adopting a row it cannot
    see. Production runs READ COMMITTED, where that recovery read gets a fresh
    snapshot and returns the winner's cap instead -- covered by
    test_read_committed_caller_adopts_an_existing_cap below.
    """
    workspace_id, actor = await _bare_workspace()

    async with AsyncSessionLocal() as loser:
        await loser.connection(execution_options={"isolation_level": "REPEATABLE READ"})
        assert await _cap_count(loser, workspace_id) == 0, "snapshot must predate the winner"

        async with AsyncSessionLocal() as winner:
            await spend.ensure_default_spend_cap(winner, workspace_id=workspace_id, actor_id=actor)
            await winner.commit()

        with pytest.raises(IntegrityError):
            await spend.ensure_default_spend_cap(loser, workspace_id=workspace_id, actor_id=actor)

        # The assertion that matters: the outer transaction is still alive.
        # This raised PendingRollbackError before the fix.
        assert await _cap_count(loser, workspace_id) == 0, (
            "session must still be usable after the contained unique violation"
        )

    async with AsyncSessionLocal() as check:
        assert await _cap_count(check, workspace_id) == 1, "no duplicate cap left behind"


@pytest.mark.asyncio
async def test_read_committed_caller_adopts_an_existing_cap():
    """The ordinary path: a later caller sees the committed cap and reuses it."""
    workspace_id, actor = await _bare_workspace()

    async with AsyncSessionLocal() as first:
        created = await spend.ensure_default_spend_cap(
            first, workspace_id=workspace_id, actor_id=actor
        )
        created_id = created.id
        await first.commit()

    async with AsyncSessionLocal() as second:
        again = await spend.ensure_default_spend_cap(
            second, workspace_id=workspace_id, actor_id=actor
        )
        assert again.id == created_id
        await second.commit()

    async with AsyncSessionLocal() as check:
        assert await _cap_count(check, workspace_id) == 1


@pytest.mark.asyncio
async def test_concurrent_read_committed_race_recovers_and_leaves_both_sessions_usable(
    monkeypatch,
):
    """The production recovery branch, under the isolation the app actually uses.

    The two tests above between them never execute `except IntegrityError ->
    return existing`: one expects the raise (pinned snapshot hides the winner),
    the other never conflicts at all. A regression in the recovery path or in
    post-savepoint session state would pass both.

    `get_workspace_spend_cap` is called *only* in the recovery branch, so
    counting it is an exact probe for "the race actually happened" -- this test
    refuses to pass vacuously.
    """
    real_lookup = spend.get_workspace_spend_cap
    recoveries = {"n": 0}

    async def counting_lookup(session, *, workspace_id):
        recoveries["n"] += 1
        return await real_lookup(session, workspace_id=workspace_id)

    monkeypatch.setattr(spend, "get_workspace_spend_cap", counting_lookup)

    async def racer(workspace_id: uuid.UUID, actor: uuid.UUID):
        async with AsyncSessionLocal() as session:
            cap = await spend.ensure_default_spend_cap(
                session, workspace_id=workspace_id, actor_id=actor
            )
            cap_id = cap.id
            # Must still be usable: proves the unique violation stayed inside
            # the savepoint rather than aborting this transaction.
            count = await _cap_count(session, workspace_id)
            await session.commit()
            return cap_id, count

    # Both coroutines yield at their first await, so both reach the existence
    # SELECT before either flushes. Retried a bounded number of times because
    # scheduling is not contractually guaranteed.
    for _attempt in range(10):
        workspace_id, actor = await _bare_workspace()
        results = await asyncio.gather(racer(workspace_id, actor), racer(workspace_id, actor))
        if recoveries["n"]:
            break
    else:
        pytest.skip("could not schedule the concurrent race in this environment")

    (first_id, first_count), (second_id, second_count) = results
    assert first_id == second_id, "both callers must end up with the same cap"
    assert first_count == second_count == 1, "sessions stayed usable; exactly one cap row"

    async with AsyncSessionLocal() as check:
        assert await _cap_count(check, workspace_id) == 1, "no duplicate cap committed"

"""Reference worker client — implements the registration/heartbeat/claim/
ack/renew/submit protocol over HTTP against the API's worker endpoints,
authenticated with a per-worker credential (Workstream 1–3).

NO generation logic: `execute_stage` is the one method a real worker
subclasses/injects, and this reference implementation's default just
returns a canned success — enough to exercise the whole contract in
tests without pretending to do AI generation.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Awaitable, Callable

import httpx

logger = logging.getLogger("worker.client")

CAPABILITY_PROTOCOL_VERSION = 1
EXECUTABLE_STAGES = frozenset({"scripting", "idea"})

# Bounded retries for a claim whose HTTP response is lost in transit
# (timeout/connection reset) — not for HTTP error statuses, which
# propagate via raise_for_status as before. See claim_next().
_CLAIM_NETWORK_RETRY_ATTEMPTS = 3

# Type for the pluggable stage-execution function a real worker provides.
# Returns (success, result_dict_or_None, error_message).
StageExecutor = Callable[[dict], Awaitable[tuple[bool, dict | None, str]]]


async def _default_executor(assignment_context: dict) -> tuple[bool, dict | None, str]:
    """Default executor is Draft Desk (real structured output, never {})."""
    from worker.executors.draft_desk import draft_desk_executor

    return await draft_desk_executor(assignment_context)


class ReferenceWorkerClient:
    def __init__(
        self,
        *,
        name: str,
        supported_stages: list[str],
        http: httpx.AsyncClient,
        credential: str,
        worker_id: uuid.UUID | str,
        max_concurrency: int = 1,
        workspace_id: uuid.UUID | None = None,
        executor: StageExecutor = _default_executor,
        heartbeat_interval_seconds: int = 10,
        lease_seconds: int = 60,
        worker_version: str = "reference-0.4.0",
    ) -> None:
        """`http` is an httpx.AsyncClient pointed at the API (tests inject
        an ASGI-transport client); `credential` is the
        `<credential_id>.<secret>` string issued at provisioning, and
        `worker_id` the provisioned identity.
        """
        self.name = name
        self.supported_stages = _supported_executable_stages(supported_stages)
        self.max_concurrency = max_concurrency
        self.workspace_id = workspace_id
        self.executor = executor
        self.heartbeat_interval_seconds = heartbeat_interval_seconds
        self.lease_seconds = lease_seconds
        self.worker_version = worker_version
        self._http = http
        self._auth_headers = {"Authorization": f"Bearer {credential}"}
        self.worker_id: uuid.UUID = (
            worker_id if isinstance(worker_id, uuid.UUID) else uuid.UUID(worker_id)
        )
        self.current_load = 0
        self._draining = False

    async def register(self) -> uuid.UUID:
        response = await self._http.post(
            "/workers/register",
            headers=self._auth_headers,
            json={
                "supported_stages": self.supported_stages,
                "capabilities": {
                    "protocol_version": CAPABILITY_PROTOCOL_VERSION,
                    "providers": [],
                    "features": [],
                },
                "worker_version": self.worker_version,
                "max_concurrency": self.max_concurrency,
            },
        )
        response.raise_for_status()
        return self.worker_id

    async def heartbeat(self) -> None:
        status = "draining" if self._draining else ("busy" if self.current_load else "online")
        response = await self._http.post(
            "/workers/heartbeat",
            headers=self._auth_headers,
            json={"status": status, "current_load": self.current_load},
        )
        response.raise_for_status()

    async def log(
        self,
        severity: str,
        message: str,
        *,
        pipeline_run_id: uuid.UUID | str | None = None,
        assignment_id: uuid.UUID | str | None = None,
        context: dict | None = None,
    ) -> dict:
        """Submit one durable Mission Control log event."""
        response = await self._http.post(
            "/workers/logs",
            headers=self._auth_headers,
            json={
                "severity": severity,
                "message": message,
                "pipeline_run_id": (str(pipeline_run_id) if pipeline_run_id is not None else None),
                "assignment_id": (str(assignment_id) if assignment_id is not None else None),
                "context": context or {},
            },
        )
        response.raise_for_status()
        return response.json()

    async def drain(self) -> None:
        """Graceful shutdown: stop accepting new work, let in-flight
        assignments finish naturally (their leases aren't touched)."""
        self._draining = True
        await self.heartbeat()

    async def deregister(self) -> None:
        response = await self._http.post("/workers/deregister", headers=self._auth_headers)
        response.raise_for_status()

    async def claim_next(self, session=None):
        """Pull-mode claim via HTTP (WS2/WS3). ``session`` is accepted for
        back-compat with older call sites and ignored — work transport is
        no longer direct-DB.

        Generates one ``claim_token`` for this claim attempt and reuses it
        across a bounded number of retries when the HTTP response itself
        is lost in transit (timeout/connection reset) — 2026-09-07 fix,
        see `docs/TECHNICAL_DEBT_REGISTER.md` TD-078. Without this, a lost
        response left the server holding a granted assignment the worker
        didn't know about, stranding that capacity slot until the lease
        expired (~60s, self-healing, but wasteful): the server has always
        supported idempotent replay via `claim_token`
        (`app.orchestration.claiming.claim_assignment`); this reference
        client just never sent one. HTTP error statuses (4xx/5xx) still
        propagate immediately via `raise_for_status`, unretried.
        """
        del session  # unused; HTTP path only
        if self._draining:
            return None
        claim_token = str(uuid.uuid4())
        last_exc: httpx.TransportError | None = None
        for _ in range(_CLAIM_NETWORK_RETRY_ATTEMPTS):
            try:
                response = await self._http.post(
                    "/workers/claim",
                    headers=self._auth_headers,
                    json={"claim_token": claim_token},
                )
            except httpx.TransportError as exc:
                last_exc = exc
                continue
            response.raise_for_status()
            body = response.json()
            if body.get("outcome") != "granted" or body.get("assignment") is None:
                return None
            self.current_load += 1
            return body["assignment"]
        assert last_exc is not None
        raise last_exc

    async def ack(self, assignment_id: uuid.UUID | str) -> dict:
        response = await self._http.post(
            f"/workers/assignments/{assignment_id}/ack",
            headers=self._auth_headers,
        )
        response.raise_for_status()
        return response.json()

    async def renew(self, assignment_id: uuid.UUID | str, session=None) -> dict:
        """HTTP lease renew. ``session`` ignored (back-compat)."""
        del session
        response = await self._http.post(
            f"/workers/assignments/{assignment_id}/renew",
            headers=self._auth_headers,
        )
        response.raise_for_status()
        return response.json()

    async def submit(
        self,
        assignment_id: uuid.UUID | str,
        *,
        success: bool,
        result: dict | None = None,
        error_message: str = "",
        provider_effect_key: str | None = None,
    ) -> dict:
        payload: dict = {
            "success": success,
            "result": result,
            "error_message": error_message,
        }
        if provider_effect_key is not None:
            payload["provider_effect_key"] = provider_effect_key
        response = await self._http.post(
            f"/workers/assignments/{assignment_id}/submit",
            headers=self._auth_headers,
            json=payload,
        )
        response.raise_for_status()
        if self.current_load > 0:
            self.current_load -= 1
        return response.json()

    async def run_one(self, session=None, assignment=None) -> None:
        """Execute (via the pluggable executor) and submit the result —
        the full worker-side half of the contract. ``assignment`` is the
        dict returned by ``claim_next`` (HTTP). ``session`` is unused.

        Protocol: ack (reserves provider effect key) → renew (keep lease
        alive across execution) → execute → submit. Real workers with
        long provider calls should renew on an interval; the reference
        client renews once immediately before submit as a minimal
        heartbeat-extend.

        Does NOT synthesize its own provider effect key (2026-09-07 fix —
        see `docs/TECHNICAL_DEBT_REGISTER.md` TD-077): the server derives
        the same stable, attempt-independent key at both ack and submit
        when no explicit override is sent, so they naturally agree. A
        real provider-calling executor that already has its own
        provider-issued idempotency key should pass it through
        ``context``/its own submit call instead of this reference client
        inventing one.

        If ``ack``'s response reports ``provider_effect_created=False``,
        a *prior* attempt of this same assignment already reserved this
        effect key — meaning that attempt may have already triggered a
        real, billable provider call before crashing or losing its lease.
        Whether that call succeeded is unknown and unverifiable from here,
        so this reference implementation refuses to execute again (which
        could double-charge or double-generate) and instead submits an
        explicit failure for operator/retry-policy attention, rather than
        silently re-running or fabricating a success it cannot confirm.
        """
        del session
        if assignment is None:
            return
        assignment_id = assignment["id"] if isinstance(assignment, dict) else assignment.id
        stage = assignment["stage"] if isinstance(assignment, dict) else assignment.stage
        ack_response = await self.ack(assignment_id)
        if ack_response.get("provider_effect_created") is False:
            await self.submit(
                assignment_id,
                success=False,
                result=None,
                error_message=(
                    "provider effect already reserved by a prior attempt of this "
                    "assignment; refusing to repeat a possibly-billable side effect"
                ),
            )
            return
        # Renew before side effects so a slow executor does not race the reaper.
        await self.renew(assignment_id)
        context = {
            "stage": stage,
            "assignment_id": str(assignment_id),
        }
        if isinstance(assignment, dict):
            for key in (
                "topic",
                "content_item_id",
                "workspace_id",
                "target_length_seconds",
                "provider",
                "pipeline_run_id",
                "attempt_number",
            ):
                if key in assignment and assignment[key] is not None:
                    context[key] = assignment[key]
        success, result, error = await self.executor(context)
        await self.submit(
            assignment_id,
            success=success,
            result=result,
            error_message=error,
        )


def _supported_executable_stages(stages: list[str]) -> list[str]:
    normalized: list[str] = []
    seen: set[str] = set()
    for stage in stages:
        candidate = str(stage or "").strip().lower()
        if candidate in EXECUTABLE_STAGES and candidate not in seen:
            normalized.append(candidate)
            seen.add(candidate)
    return normalized

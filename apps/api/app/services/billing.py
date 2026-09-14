"""Stripe Checkout + workspace entitlement service.

When BILLING_ENABLED=false the product path behaves as Private Beta (P0).
When true, Checkout creates a Stripe session and webhooks mirror subscription
state into workspace_billing; content-jobs require an active/trialing Pro plan.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import stripe
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.models.billing import BillingWebhookEvent, WorkspaceBilling

logger = logging.getLogger(__name__)

ACTIVE_STATUSES = frozenset({"active", "trialing"})
PRO_PLAN = "pro"


class BillingError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class CheckoutResult:
    checkout_url: str
    session_id: str
    workspace_id: uuid.UUID


@dataclass(frozen=True)
class Entitlement:
    workspace_id: uuid.UUID
    plan: str
    status: str
    entitled: bool
    billing_enabled: bool
    stripe_customer_id: str | None
    stripe_subscription_id: str | None
    current_period_end: datetime | None
    cancel_at_period_end: bool


def _require_billing_config(settings: Settings) -> None:
    if not settings.billing_enabled:
        raise BillingError("billing_disabled", "billing is not enabled")
    missing = [
        name
        for name, value in (
            ("STRIPE_SECRET_KEY", settings.stripe_secret_key),
            ("STRIPE_WEBHOOK_SECRET", settings.stripe_webhook_secret),
            ("STRIPE_PRICE_ID_PRO", settings.stripe_price_id_pro),
            ("STRIPE_CHECKOUT_SUCCESS_URL", settings.stripe_checkout_success_url),
            ("STRIPE_CHECKOUT_CANCEL_URL", settings.stripe_checkout_cancel_url),
        )
        if not value
    ]
    if missing:
        raise BillingError(
            "billing_misconfigured",
            f"billing enabled but missing required settings: {', '.join(missing)}",
        )


def _configure_stripe(settings: Settings) -> None:
    _require_billing_config(settings)
    stripe.api_key = settings.stripe_secret_key


async def ensure_workspace_billing(
    session: AsyncSession, *, workspace_id: uuid.UUID, for_update: bool = False
) -> WorkspaceBilling:
    """``for_update=True`` locks the row for the rest of this transaction —
    required before any read-modify-write of subscription state, since
    Stripe does not guarantee ordered or single-threaded webhook delivery
    (2026-09-08 fix; see ``_apply_subscription``, the one caller that
    actually mutates entitlement-bearing fields from webhook data).
    """
    row = await session.get(WorkspaceBilling, workspace_id, with_for_update=for_update)
    if row is not None:
        return row
    row = WorkspaceBilling(
        workspace_id=workspace_id,
        plan="none",
        status="inactive",
    )
    session.add(row)
    await session.flush()
    return row


def is_entitled(row: WorkspaceBilling | None, *, billing_enabled: bool) -> bool:
    if not billing_enabled:
        return True
    if row is None:
        return False
    return row.plan == PRO_PLAN and row.status in ACTIVE_STATUSES


async def get_entitlement(session: AsyncSession, *, workspace_id: uuid.UUID) -> Entitlement:
    settings = get_settings()
    row = await ensure_workspace_billing(session, workspace_id=workspace_id)
    return Entitlement(
        workspace_id=workspace_id,
        plan=row.plan,
        status=row.status,
        entitled=is_entitled(row, billing_enabled=settings.billing_enabled),
        billing_enabled=settings.billing_enabled,
        stripe_customer_id=row.stripe_customer_id,
        stripe_subscription_id=row.stripe_subscription_id,
        current_period_end=row.current_period_end,
        cancel_at_period_end=row.cancel_at_period_end,
    )


async def require_entitlement_for_workspace(
    session: AsyncSession, *, workspace_id: uuid.UUID
) -> None:
    """Raise BillingError when billing is on and the workspace is not entitled."""
    settings = get_settings()
    if not settings.billing_enabled:
        return
    row = await session.get(WorkspaceBilling, workspace_id)
    if not is_entitled(row, billing_enabled=True):
        raise BillingError(
            "not_entitled",
            "workspace requires an active Pro subscription to create content jobs",
        )


async def create_checkout_session(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    customer_email: str | None,
) -> CheckoutResult:
    settings = get_settings()
    _configure_stripe(settings)
    # _configure_stripe() -> _require_billing_config() already raised BillingError
    # if any of these were unset; these bindings carry that guarantee through to
    # the type checker. Written as a real check rather than `assert` because
    # `python -O` strips asserts, which would let None reach the Stripe call.
    price_id = settings.stripe_price_id_pro
    success_url = settings.stripe_checkout_success_url
    cancel_url = settings.stripe_checkout_cancel_url
    if price_id is None or success_url is None or cancel_url is None:
        raise BillingError(
            "billing_misconfigured",
            "billing enabled but Stripe price or redirect URLs are unset",
        )
    billing = await ensure_workspace_billing(session, workspace_id=workspace_id)

    if is_entitled(billing, billing_enabled=True):
        raise BillingError("already_entitled", "workspace already has an active Pro plan")

    if not billing.stripe_customer_id:
        # Deterministic per-workspace key: a retry after a network-level
        # ambiguous failure (e.g. the request succeeded but the response was
        # lost) reuses the same Stripe Customer instead of orphaning a
        # duplicate. Safe to reuse across genuinely distinct attempts too,
        # since a workspace only ever wants one Customer object.
        idempotency_key = f"workspace-customer-{workspace_id}"
        metadata = {"workspace_id": str(workspace_id)}
        try:
            # Stripe's Customer object treats a missing email as unset, not an
            # error — omit the keyword entirely rather than send an explicit
            # null when the caller (JWT with no email claim) has none.
            if customer_email:
                customer = stripe.Customer.create(
                    email=customer_email,
                    metadata=metadata,
                    idempotency_key=idempotency_key,
                )
            else:
                customer = stripe.Customer.create(
                    metadata=metadata,
                    idempotency_key=idempotency_key,
                )
        except stripe.StripeError as exc:
            raise BillingError(
                "stripe_unavailable", f"Stripe customer creation failed: {exc}"
            ) from exc
        billing.stripe_customer_id = customer["id"]
        await session.flush()

    try:
        checkout = stripe.checkout.Session.create(
            mode="subscription",
            customer=billing.stripe_customer_id,
            line_items=[{"price": price_id, "quantity": 1}],
            success_url=success_url,
            cancel_url=cancel_url,
            client_reference_id=str(workspace_id),
            metadata={"workspace_id": str(workspace_id)},
            subscription_data={"metadata": {"workspace_id": str(workspace_id)}},
        )
    except stripe.StripeError as exc:
        raise BillingError(
            "stripe_unavailable", f"Stripe checkout session creation failed: {exc}"
        ) from exc
    url = checkout.get("url")
    if not url:
        raise BillingError("checkout_failed", "Stripe Checkout session missing url")
    logger.info(
        "stripe_checkout_created",
        extra={
            "workspace_id": str(workspace_id),
            "session_id": checkout["id"],
            "customer_id": billing.stripe_customer_id,
        },
    )
    return CheckoutResult(
        checkout_url=url,
        session_id=checkout["id"],
        workspace_id=workspace_id,
    )


def _period_end_from_subscription(sub: dict) -> datetime | None:
    raw = sub.get("current_period_end")
    if raw is None:
        return None
    return datetime.fromtimestamp(int(raw), tz=UTC)


async def _latest_applied_event_created(
    session: AsyncSession, *, workspace_id: uuid.UUID, exclude_event_id: str
) -> int | None:
    """Max Stripe `created` timestamp among entitlement-affecting webhook
    receipts already stored for this workspace, excluding the event
    currently being applied (its own receipt is flushed before this is
    called, so it would otherwise tie with itself on a brand-new
    subscription and be mistaken for an already-applied prior event).
    Includes invoice.payment_failed: it mutates billing.status directly
    (see process_stripe_event) via a path that bypasses _apply_subscription,
    so without it here a delayed-but-actually-older "active" subscription
    event could appear newest and overwrite a payment failure's "past_due"."""
    rows = (
        (
            await session.execute(
                select(BillingWebhookEvent.payload).where(
                    BillingWebhookEvent.workspace_id == workspace_id,
                    BillingWebhookEvent.stripe_event_id != exclude_event_id,
                    BillingWebhookEvent.event_type.in_(
                        (
                            "customer.subscription.created",
                            "customer.subscription.updated",
                            "customer.subscription.deleted",
                            "invoice.payment_failed",
                        )
                    ),
                )
            )
        )
        .scalars()
        .all()
    )
    created_values = [
        created
        for row in rows
        if isinstance(row, dict) and isinstance((created := row.get("created")), int)
    ]
    return max(created_values) if created_values else None


async def _apply_subscription(
    session: AsyncSession,
    *,
    workspace_id: uuid.UUID,
    subscription: dict,
    event_id: str,
    event_created: int | None = None,
) -> None:
    billing = await ensure_workspace_billing(session, workspace_id=workspace_id, for_update=True)
    status = str(subscription.get("status") or "inactive")
    if event_created is not None:
        latest = await _latest_applied_event_created(
            session, workspace_id=workspace_id, exclude_event_id=event_id
        )
        # Stripe `created` timestamps are second-granularity, so two distinct
        # events can genuinely tie. An unambiguously (strictly) older event
        # must always be rejected regardless of direction: a `past_due`/
        # `unpaid`/`incomplete` status via subscription.updated is a
        # *reversible* sub-state of an ongoing subscription, not terminal
        # the way subscription.deleted's "canceled" is (once really
        # canceled, nothing legitimately supersedes it for that subscription
        # id) — so a strictly older downgrade arriving late must not
        # overwrite a newer, already-applied active state any more than a
        # strictly older active event may resurrect a newer downgrade. A
        # genuine *tie* is the only case kept ambiguous enough to fail
        # closed: it still favors a downgrade over an entitlement grant,
        # since whichever was merely delivered/processed last would
        # otherwise decide the outcome.
        is_entitling = status in ACTIVE_STATUSES
        stale = latest is not None and event_created < latest
        tied = latest is not None and event_created == latest
        if stale or (tied and is_entitling):
            logger.info(
                "stripe_webhook_stale_event_skipped",
                extra={
                    "workspace_id": str(workspace_id),
                    "event_created": event_created,
                    "latest_applied_created": latest,
                },
            )
            return
    billing.stripe_subscription_id = subscription.get("id") or billing.stripe_subscription_id
    customer = subscription.get("customer")
    if isinstance(customer, str):
        billing.stripe_customer_id = customer
    elif isinstance(customer, dict) and customer.get("id"):
        billing.stripe_customer_id = customer["id"]

    billing.status = status
    if status in ACTIVE_STATUSES:
        billing.plan = PRO_PLAN
    elif status == "canceled":
        billing.plan = "none"
    elif status in {"past_due", "unpaid", "incomplete"}:
        # Keep Pro plan marker so ops can see what lapsed; entitlement is false.
        billing.plan = PRO_PLAN
    billing.current_period_end = _period_end_from_subscription(subscription)
    billing.cancel_at_period_end = bool(subscription.get("cancel_at_period_end") or False)
    await session.flush()


async def _workspace_id_from_metadata(obj: dict) -> uuid.UUID | None:
    meta = obj.get("metadata") or {}
    raw = meta.get("workspace_id") or obj.get("client_reference_id")
    if not raw:
        return None
    try:
        return uuid.UUID(str(raw))
    except ValueError:
        return None


async def process_stripe_event(session: AsyncSession, *, event: dict) -> dict:
    """Apply one verified Stripe event. Idempotent on stripe_event_id."""
    event_id = event.get("id")
    event_type = event.get("type")
    if not event_id or not event_type:
        raise BillingError("invalid_event", "Stripe event missing id or type")

    existing = (
        await session.execute(
            select(BillingWebhookEvent).where(BillingWebhookEvent.stripe_event_id == event_id)
        )
    ).scalar_one_or_none()
    if existing is not None:
        logger.info(
            "stripe_webhook_duplicate",
            extra={"stripe_event_id": event_id, "event_type": event_type},
        )
        return {"status": "duplicate", "event_id": event_id, "event_type": event_type}

    data_object = (event.get("data") or {}).get("object") or {}
    workspace_id = await _workspace_id_from_metadata(data_object)

    # Claim unique stripe_event_id BEFORE mutations (H-5). The savepoint
    # wraps insert + apply so a mapping error rolls back the receipt and
    # Stripe can retry; a concurrent winner's IntegrityError is duplicate.
    try:
        async with session.begin_nested():
            session.add(
                BillingWebhookEvent(
                    id=uuid.uuid4(),
                    stripe_event_id=event_id,
                    event_type=event_type,
                    workspace_id=workspace_id,
                    payload=event,
                )
            )
            await session.flush()

            if event_type == "checkout.session.completed":
                # Linkage only — never grant entitlement here.
                if workspace_id is None:
                    raise BillingError("missing_workspace", "checkout session missing workspace_id")
                sub_id = data_object.get("subscription")
                customer_id = data_object.get("customer")
                billing = await ensure_workspace_billing(session, workspace_id=workspace_id)
                if isinstance(customer_id, str):
                    billing.stripe_customer_id = customer_id
                if isinstance(sub_id, str):
                    billing.stripe_subscription_id = sub_id
                await session.flush()
                logger.info(
                    "stripe_checkout_linked",
                    extra={
                        "workspace_id": str(workspace_id),
                        "customer_id": billing.stripe_customer_id,
                        "subscription_id": billing.stripe_subscription_id,
                        "payment_status": data_object.get("payment_status"),
                    },
                )
            elif event_type in {
                "customer.subscription.created",
                "customer.subscription.updated",
                "customer.subscription.deleted",
            }:
                if workspace_id is None:
                    sub_id = data_object.get("id")
                    cust = data_object.get("customer")
                    row = None
                    if isinstance(sub_id, str):
                        row = (
                            await session.execute(
                                select(WorkspaceBilling).where(
                                    WorkspaceBilling.stripe_subscription_id == sub_id
                                )
                            )
                        ).scalar_one_or_none()
                    if row is None and isinstance(cust, str):
                        row = (
                            await session.execute(
                                select(WorkspaceBilling).where(
                                    WorkspaceBilling.stripe_customer_id == cust
                                )
                            )
                        ).scalar_one_or_none()
                    if row is None:
                        raise BillingError(
                            "unknown_subscription",
                            "subscription event could not be mapped to a workspace",
                        )
                    workspace_id = row.workspace_id
                raw_created = event.get("created")
                await _apply_subscription(
                    session,
                    workspace_id=workspace_id,
                    subscription=data_object,
                    event_id=event_id,
                    event_created=raw_created if isinstance(raw_created, int) else None,
                )
            elif event_type == "invoice.payment_failed":
                sub = data_object.get("subscription")
                if isinstance(sub, str):
                    found_workspace_id = (
                        await session.execute(
                            select(WorkspaceBilling.workspace_id).where(
                                WorkspaceBilling.stripe_subscription_id == sub
                            )
                        )
                    ).scalar_one_or_none()
                    if found_workspace_id is not None:
                        workspace_id = found_workspace_id
                        # Lock the row (same lock _apply_subscription takes)
                        # BEFORE reading freshness, not after: a plain,
                        # unlocked read here would let a concurrent,
                        # genuinely parallel transaction (a newer
                        # subscription.updated) still be uncommitted when
                        # this reads "latest applied event", so this event
                        # could wrongly conclude it's not stale, decide to
                        # write, and then only *incidentally* block at
                        # flush() on the other transaction's lock — meaning
                        # the decision was made on stale data even though
                        # the write itself is correctly serialized. Locking
                        # first forces this read to happen only once the
                        # concurrent transaction has actually committed.
                        row = await ensure_workspace_billing(
                            session, workspace_id=workspace_id, for_update=True
                        )
                        # Unlike a subscription-status transition (the
                        # current, authoritative state of the subscription
                        # as of that event), a payment-failure notification
                        # only describes one invoice attempt at a point in
                        # time — it can be superseded by a later successful
                        # renewal. Stripe retries webhook delivery for days,
                        # so a strictly older, delayed failure must not
                        # overwrite an already-applied newer event (e.g. the
                        # customer fixed their card and renewed). A genuine
                        # timestamp tie is kept ambiguous enough to fail
                        # closed in this action's own (downgrading)
                        # direction, same as _apply_subscription's tie
                        # handling — this action only ever revokes
                        # entitlement, so applying it on a tie is the safe
                        # choice, not overwriting a competing state.
                        raw_created = event.get("created")
                        latest = None
                        if isinstance(raw_created, int):
                            latest = await _latest_applied_event_created(
                                session,
                                workspace_id=workspace_id,
                                exclude_event_id=event_id,
                            )
                        if (
                            isinstance(raw_created, int)
                            and latest is not None
                            and raw_created < latest
                        ):
                            logger.info(
                                "stripe_webhook_stale_payment_failure_skipped",
                                extra={
                                    "workspace_id": str(workspace_id),
                                    "event_created": raw_created,
                                    "latest_applied_created": latest,
                                },
                            )
                        else:
                            row.status = "past_due"
                        await session.flush()
            else:
                logger.info(
                    "stripe_webhook_ignored",
                    extra={"stripe_event_id": event_id, "event_type": event_type},
                )

            if workspace_id is not None:
                receipt = (
                    await session.execute(
                        select(BillingWebhookEvent).where(
                            BillingWebhookEvent.stripe_event_id == event_id
                        )
                    )
                ).scalar_one()
                if receipt.workspace_id is None:
                    receipt.workspace_id = workspace_id
                    await session.flush()
    except IntegrityError:
        logger.info(
            "stripe_webhook_duplicate_race",
            extra={"stripe_event_id": event_id, "event_type": event_type},
        )
        return {"status": "duplicate", "event_id": event_id, "event_type": event_type}

    logger.info(
        "stripe_webhook_processed",
        extra={
            "stripe_event_id": event_id,
            "event_type": event_type,
            "workspace_id": str(workspace_id) if workspace_id else None,
        },
    )
    return {
        "status": "processed",
        "event_id": event_id,
        "event_type": event_type,
        "workspace_id": str(workspace_id) if workspace_id else None,
    }


def construct_stripe_event(*, payload: bytes, sig_header: str) -> dict:
    settings = get_settings()
    _configure_stripe(settings)
    # Real check, not `assert`: stripped under `python -O`, a None secret
    # would reach signature verification on the webhook ingress path.
    webhook_secret = settings.stripe_webhook_secret
    if webhook_secret is None:
        raise BillingError(
            "billing_misconfigured",
            "billing enabled but STRIPE_WEBHOOK_SECRET is unset",
        )
    try:
        event = stripe.Webhook.construct_event(payload, sig_header, webhook_secret)
    except stripe.SignatureVerificationError as exc:
        raise BillingError("invalid_signature", "invalid Stripe webhook signature") from exc
    except Exception as exc:  # noqa: BLE001
        raise BillingError("invalid_payload", f"invalid Stripe webhook payload: {exc}") from exc
    if hasattr(event, "to_dict"):
        return event.to_dict()
    return dict(event)

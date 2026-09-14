# All ORM models are imported here so they register on Base.metadata,
# which Alembic autogenerate compares against. Models are schema mappings
# only (columns, FKs, relationships) — no business logic.

from app.models.assignments import StageAssignment  # noqa: F401
from app.models.automation import AutomationLease  # noqa: F401
from app.models.backpressure import (  # noqa: F401
    ProviderConcurrencyBudget,
    WorkspaceBackpressureState,
)
from app.models.billing import BillingWebhookEvent, WorkspaceBilling  # noqa: F401
from app.models.claim_audit import StageClaimAudit  # noqa: F401
from app.models.compliance import (  # noqa: F401
    ArtifactPublicationEligibility,
    ArtifactRightsEvidence,
    AuditGateManifest,
    ChiefAudit,
    ChiefAuditInvalidation,
    ComplianceAudit,
    ComplianceInvalidation,
    HumanReviewPackage,
    PlatformPolicySource,
)

# Milestone 3 content domain
from app.models.config import ContentPillar, ProviderCredential, SpendCap  # noqa: F401
from app.models.content import ContentItem, ContentLineage, ContentVersion  # noqa: F401
from app.models.content_department import (  # noqa: F401
    ContentAudit,
    ContentAuditInvalidation,
    ContentClaim,
    ContentDepartmentRun,
    ContentPackage,
    CreativeDirection,
    OriginalityFingerprint,
)
from app.models.delivery import Asset, PublishJob  # noqa: F401
from app.models.events import ConsumerCheckpoint, EventConsumer, OutboxEvent  # noqa: F401
from app.models.history import AnalyticsSnapshot, ProviderUsage, ReviewDecision  # noqa: F401
from app.models.leads import Lead  # noqa: F401
from app.models.local_auth import LocalAuthCredential  # noqa: F401
from app.models.operations import DeadLetterJob, WebhookEvent  # noqa: F401
from app.models.pipeline import PipelineRun, PipelineStageRun  # noqa: F401
from app.models.production import (  # noqa: F401
    ArtifactInvalidation,
    FinalArtifact,
    MediaQaResult,
    ProductionAsset,
    ProductionJob,
    ProductionReadiness,
    ProductionRepair,
)
from app.models.profile import Profile  # noqa: F401
from app.models.provider_effects import ProviderEffectKey  # noqa: F401
from app.models.publication_policy import PublicationEligibility  # noqa: F401
from app.models.recovery_audit import StageRecoveryAudit  # noqa: F401
from app.models.research import (  # noqa: F401
    Opportunity,
    OpportunityEvidence,
    ResearchAudit,
    ResearchRun,
    ResearchSchedule,
    ResearchSource,
)
from app.models.review_gate import ReviewGate  # noqa: F401
from app.models.scheduling import JobSchedule, WorkspaceConcurrencyLimit  # noqa: F401
from app.models.spend import SpendLog, SpendReservation  # noqa: F401
from app.models.strategy import (  # noqa: F401
    StrategyAudit,
    StrategyBrief,
    StrategyBriefOpportunity,
    StrategyRun,
    StrategySchedule,
)
from app.models.worker_logs import WorkerLog  # noqa: F401
from app.models.workers import WorkerHeartbeat, WorkerRegistration  # noqa: F401

# Milestone 4 orchestration
from app.models.workflow import WorkflowDefinition, WorkflowStage, WorkflowTransition  # noqa: F401
from app.models.workspace import Workspace  # noqa: F401
from app.models.workspace_membership import WorkspaceMembership, WorkspaceRole  # noqa: F401

__all__ = [
    "Profile",
    "Workspace",
    "WorkspaceMembership",
    "WorkspaceRole",
    "ContentPillar",
    "ProviderCredential",
    "SpendCap",
    "ContentItem",
    "ContentVersion",
    "ContentLineage",
    "ArtifactPublicationEligibility",
    "ArtifactRightsEvidence",
    "AuditGateManifest",
    "ChiefAudit",
    "ChiefAuditInvalidation",
    "ComplianceAudit",
    "ComplianceInvalidation",
    "HumanReviewPackage",
    "PlatformPolicySource",
    "ContentDepartmentRun",
    "CreativeDirection",
    "ContentPackage",
    "ContentClaim",
    "ContentAudit",
    "ContentAuditInvalidation",
    "OriginalityFingerprint",
    "PipelineRun",
    "PipelineStageRun",
    "Asset",
    "PublishJob",
    "AnalyticsSnapshot",
    "ProviderUsage",
    "ReviewDecision",
    "SpendLog",
    "SpendReservation",
    "DeadLetterJob",
    "WebhookEvent",
    "WorkflowDefinition",
    "WorkflowStage",
    "WorkflowTransition",
    "OutboxEvent",
    "EventConsumer",
    "ConsumerCheckpoint",
    "JobSchedule",
    "WorkspaceConcurrencyLimit",
    "WorkerRegistration",
    "WorkerHeartbeat",
    "StageAssignment",
    "AutomationLease",
    "StageClaimAudit",
    "StageRecoveryAudit",
    "ProviderEffectKey",
    "ProductionJob",
    "ProductionAsset",
    "FinalArtifact",
    "MediaQaResult",
    "ProductionRepair",
    "ArtifactInvalidation",
    "ProductionReadiness",
    "WorkspaceBackpressureState",
    "ProviderConcurrencyBudget",
    "ReviewGate",
    "Lead",
    "BillingWebhookEvent",
    "WorkspaceBilling",
    "WorkerLog",
    "PublicationEligibility",
    "ResearchRun",
    "ResearchSource",
    "Opportunity",
    "OpportunityEvidence",
    "ResearchAudit",
    "ResearchSchedule",
    "StrategyRun",
    "StrategyBrief",
    "StrategyBriefOpportunity",
    "StrategyAudit",
    "StrategySchedule",
]

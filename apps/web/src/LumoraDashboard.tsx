import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import ErrorBoundary from "./ErrorBoundary";
import { BusinessManagerMark } from "./BusinessManagerMark";
import { BusinessProfileSettings, ContentSetupWizard } from "./ContentSetupWizard";
import { useDialogFocus } from "./useDialogFocus";
import {
  auditOpportunity,
  createComplianceRun,
  createContentDepartmentRun,
  createLead,
  createProductionRun,
  createResearchRun,
  createStrategyRun,
  decideReviewGate,
  editReviewGateContent,
  getActivityFeed,
  getContentCommand,
  getContentProfile,
  listChiefAudits,
  getComplianceSummary,
  getContentDepartmentSummary,
  getContentPackageDetail,
  getCostControl,
  getCustomers,
  getExecutiveDashboard,
  getExecutiveInsights,
  getExecutiveMode,
  getGitHubStatus,
  getLeads,
  getLiveLogs,
  getNotifications,
  getOpportunityDetail,
  getResearchSummary,
  getOperationsAlerts,
  getPipelineMonitor,
  getSpendDashboard,
  getSystemHealth,
  getProducerGate,
  getProductionSummary,
  getStrategyBriefDetail,
  getStrategySummary,
  listProductionRuns,
  getUniversalTimeline,
  getWorkerMonitor,
  getWorkerTimeline,
  listComplianceAudits,
  listContentPackages,
  listHumanReviewPackages,
  listOpportunities,
  listReviewGates,
  listStrategyBriefs,
  listWorkspaces,
  sendOpportunityToStrategist,
  sendStrategyBriefToWriter,
  auditStrategyBrief,
  updateLead,
  type ActivityFeed,
  type ChiefAudit,
  type ComplianceAudit,
  type ComplianceSummary,
  type ContentAudit,
  type ContentCommand,
  type ContentDepartmentSummary,
  type ContentProfile,
  type ContentPackage,
  type ContentPackageDetail,
  type CostControl,
  type Customers,
  type ExecutiveDashboard,
  type ExecutiveInsights,
  type ExecutiveMode,
  type HumanReviewPackage,
  type GitHubOut,
  type ProducerGate,
  type ProductionRun,
  type ProductionSummary,
  type Leads,
  type LiveLogs,
  type Notifications,
  type Opportunity,
  type OpportunityDetail,
  type ResearchAudit,
  type ResearchSummary,
  type PipelineMonitor,
  type ReviewGate,
  type SpendDashboard,
  type SystemHealth,
  type StrategyAudit,
  type StrategyBrief,
  type StrategyBriefDetail,
  type StrategySummary,
  type WorkerMonitor,
  type WorkerMonitorRow,
  type WorkerTimeline,
  type Workspace,
} from "./api";
import {
  ActivityFeedView,
  ContentCommandView,
  CostControlView,
  InsightsView,
  QuickActionsView,
  SystemHealthView,
  WorkerTimelineView,
} from "./MissionControlPanels";
import {
  AssistantPanel,
  CommandPalette,
  ExecutiveModeView,
  GlobalSearchBar,
  LiveLogsView,
  UniversalTimelineView,
} from "./MissionControlV4";
import {
  HEALTH_COPY,
  aggregateHealth,
  isActivityFeed,
  isAnalyticsData,
  isBillingData,
  isContentCommand,
  isCustomers,
  isDashboardData,
  isExecutiveMode,
  isLeads,
  isLiveLogs,
  isPipelineMonitor,
  isSettingsData,
  isWorkersData,
  type DashboardData,
} from "./dashboardModel";

type NavKey =
  | "dashboard"
  | "ask"
  | "mission"
  | "review"
  | "pipelines"
  | "workers"
  | "customers"
  | "leads"
  | "research"
  | "strategy"
  | "content_department"
  | "producer"
  | "compliance"
  | "analytics"
  | "billing"
  | "settings";

type MissionTab = "overview" | "timeline" | "logs" | "assistant" | "content";

type Props = {
  token: string;
  workspaceId: string;
  email: string;
  onWorkspaceChange: (workspaceId: string) => void;
  onSignOut: () => void;
};

type IconName =
  | "dashboard"
  | "mission"
  | "review"
  | "pipelines"
  | "workers"
  | "customers"
  | "leads"
  | "analytics"
  | "billing"
  | "settings"
  | "search"
  | "bell"
  | "chevron"
  | "menu"
  | "close"
  | "arrow"
  | "refresh"
  | "activity"
  | "check"
  | "alert";

const PATHS: Record<IconName, ReactNode> = {
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="2" /><rect x="14" y="3" width="7" height="7" rx="2" /><rect x="3" y="14" width="7" height="7" rx="2" /><rect x="14" y="14" width="7" height="7" rx="2" /></>,
  mission: <><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3" /></>,
  review: <><path d="M9 11l2 2 4-4" /><path d="M20 12a8 8 0 11-4.8-7.3" /><path d="M16 3h5v5" /></>,
  pipelines: <><path d="M4 5h16M4 12h10M4 19h16" /><circle cx="17" cy="12" r="3" /></>,
  workers: <><rect x="3" y="8" width="18" height="12" rx="3" /><path d="M8 8V5h8v3M8 14h.01M16 14h.01M9 18h6" /></>,
  customers: <><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75" /></>,
  leads: <><path d="M4 20h16M6 17V8M12 17V4M18 17v-6" /></>,
  analytics: <><path d="M3 3v18h18" /><path d="M7 15l4-4 3 3 5-7" /></>,
  billing: <><rect x="2" y="5" width="20" height="14" rx="3" /><path d="M2 10h20M6 15h3" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 00.34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0015 19.4a1.7 1.7 0 00-1 .6 1.7 1.7 0 00-.4 1.1V21H9v-.09A1.7 1.7 0 007.6 19.4a1.7 1.7 0 00-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 003.6 15a1.7 1.7 0 00-1.6-1H2V10h.09A1.7 1.7 0 003.6 8.6a1.7 1.7 0 00-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 008 4.6a1.7 1.7 0 001-1.6V3h4v.09a1.7 1.7 0 001.4 1.51 1.7 1.7 0 001.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0020.4 9c.3.61.91 1 1.6 1h.09v4H22a1.7 1.7 0 00-1.6 1z" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></>,
  bell: <><path d="M18 8a6 6 0 00-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></>,
  chevron: <path d="M9 18l6-6-6-6" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  arrow: <path d="M5 12h14M13 6l6 6-6 6" />,
  refresh: <><path d="M20 11a8 8 0 10-2.3 5.7" /><path d="M20 4v7h-7" /></>,
  activity: <path d="M3 12h4l2-7 4 14 2-7h6" />,
  check: <path d="M5 12l4 4L19 6" />,
  alert: <><path d="M12 3l10 18H2L12 3z" /><path d="M12 9v5M12 18h.01" /></>,
};

function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      className="ui-icon"
      fill="none"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.8"
    >
      {PATHS[name]}
    </svg>
  );
}

const NAV: Array<{ id: NavKey; label: string; icon: IconName }> = [
  { id: "dashboard", label: "Home", icon: "dashboard" },
  { id: "ask", label: "Ask", icon: "mission" },
  { id: "research", label: "Opportunities", icon: "leads" },
  { id: "strategy", label: "Strategy", icon: "mission" },
  { id: "content_department", label: "Content Department", icon: "pipelines" },
  { id: "producer", label: "Producer", icon: "workers" },
  { id: "compliance", label: "Compliance", icon: "review" },
  { id: "pipelines", label: "Content", icon: "pipelines" },
  { id: "review", label: "Human Review", icon: "review" },
  { id: "workers", label: "Workforce", icon: "workers" },
  { id: "billing", label: "Money", icon: "billing" },
  { id: "analytics", label: "Insights", icon: "analytics" },
  { id: "customers", label: "Audience", icon: "customers" },
  { id: "mission", label: "Connections", icon: "mission" },
  { id: "settings", label: "Settings", icon: "settings" },
];

const AUTO_REFRESH_MS = 20_000;

function formatDate(value: string | null | undefined): string {
  if (!value) return "Unavailable";
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function relativeTime(value: string): string {
  const seconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  if (Math.abs(seconds) < 60) return formatter.format(seconds, "second");
  const minutes = Math.round(seconds / 60);
  if (Math.abs(minutes) < 60) return formatter.format(minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) return formatter.format(hours, "hour");
  return formatter.format(Math.round(hours / 24), "day");
}

function money(value: string | number | null | undefined): string {
  if (value == null) return "Unavailable";
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 2,
  }).format(Number(value));
}

function Loading() {
  return (
    <div aria-busy="true" aria-label="Loading" className="loading-grid">
      {Array.from({ length: 6 }, (_, index) => <div className="skeleton" key={index} />)}
    </div>
  );
}

function ErrorState({ error, retry }: { error: string; retry: () => void }) {
  return (
    <div className="error-state" role="alert">
      <Icon name="alert" size={24} />
      <h3>We couldn&apos;t load this view</h3>
      <p>{error}</p>
      <button className="button button--primary" onClick={retry} type="button">Try again</button>
    </div>
  );
}

function EmptyState({
  icon = "activity",
  title,
  message,
}: {
  icon?: IconName;
  title: string;
  message: string;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon"><Icon name={icon} size={28} /></span>
      <h3>{title}</h3>
      <p>{message}</p>
    </div>
  );
}


function Status({ value }: { value: string }) {
  const normalized = value.toLowerCase();
  const tone = ["online", "healthy", "green", "active", "completed", "published", "won", "trialing"].includes(normalized)
    ? "good"
    : ["failed", "offline", "red", "critical", "lost", "cancelled"].includes(normalized)
      ? "bad"
      : "warn";
  return <span className={`status status--${tone}`}>{value.replaceAll("_", " ")}</span>;
}

function SectionHeader({
  title,
  detail,
  action,
}: {
  title: string;
  detail?: string;
  action?: ReactNode;
}) {
  return (
    <header className="section-header">
      <div>
        <h3>{title}</h3>
        {detail ? <p>{detail}</p> : null}
      </div>
      {action}
    </header>
  );
}

function isMissionAssistant(nav: NavKey, missionTab: MissionTab) {
  return nav === "mission" && missionTab === "assistant";
}

/**
 * There is no per-department capability field on the backend yet (TD-041:
 * provider wiring, tracked separately) — the only real, already-fetched
 * signal for a department card is whether a worker process is actually
 * registered under that department's name. Truthfully derive the card state
 * from that registration + liveness rather than a hardcoded literal, so this
 * stops being correct by accident once workers are eventually registered
 * under department names.
 */
function departmentCardState(
  name: string,
  workers: readonly WorkerMonitorRow[],
): { label: string; detail: string } {
  const match = workers.find((worker) => worker.name.trim().toLowerCase() === name.trim().toLowerCase());
  if (!match) {
    return {
      label: "Not configured",
      detail: "No workspace role binding or executable capability is configured.",
    };
  }
  const isLive = ["online", "busy"].includes(match.status.toLowerCase());
  return {
    label: isLive ? "Live" : "Registered",
    detail: isLive
      ? `Bound to worker process "${match.name}" (${match.status}).`
      : `Bound to worker process "${match.name}", currently ${match.status}.`,
  };
}

function DashboardHome({
  data,
  token,
  workspaceId,
  navigate,
  onRefresh,
  refreshing,
}: {
  data: DashboardData;
  token: string;
  workspaceId: string;
  navigate: (key: NavKey) => void;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const [askNotice, setAskNotice] = useState<string | null>(null);
  const [contentProfile, setContentProfile] = useState<ContentProfile | null | undefined>(undefined);
  const [setupWizardOpen, setSetupWizardOpen] = useState(false);

  useEffect(() => {
    let active = true;
    setContentProfile(undefined);
    getContentProfile(token, workspaceId)
      .then((profile) => {
        if (active) setContentProfile(profile);
      })
      .catch(() => {
        // Non-fatal: leave it undefined so the banner stays hidden rather
        // than misreading a failed fetch as "no profile saved yet."
      });
    return () => {
      active = false;
    };
  }, [token, workspaceId]);

  const priority = { critical: 0, warning: 1, info: 2 } as const;
  const decisionTargets: Record<string, NavKey> = {
    review_required: "review",
    review_waiting: "review",
    failed_jobs: "pipelines",
    pipeline_failed: "pipelines",
    spend_warning: "billing",
    worker_offline: "workers",
    failed_webhooks: "mission",
    queue_backlog: "pipelines",
  };
  const decisions = [...data.alerts.alerts]
    .filter((alert) => ["critical", "warning"].includes(alert.severity))
    .sort((left, right) => priority[left.severity] - priority[right.severity]);
  const departments = [
    ["Scout", "Research and opportunity discovery"],
    ["Strategist", "Business and content recommendations"],
    ["Writer", "Scripts, copy, and content packages"],
    ["Producer", "Generation and render orchestration"],
    ["Compliance", "Policy, rights, and originality checks"],
    ["Chief Auditor", "Independent audit-chain verification"],
    ["Analyst", "Outcome and performance learning"],
  ] as const;
  const realWorkers = data.workers.workers;
  const activeWorkerCount = realWorkers.filter((worker) => ["online", "busy"].includes(worker.status.toLowerCase())).length;

  return (
    <div className="dashboard-home business-home">
      <section className="business-home__intro">
        <div>
          <p className="page-kicker">The Business Manager</p>
          <h2>Home</h2>
          <p>What happened, what it cost, what it made, and what needs your decision.</p>
        </div>
        <div className="view-actions">
          <span className="live-indicator"><i /> Workspace-backed data</span>
          <button
            aria-label="Refresh dashboard data"
            className="icon-button refresh-button"
            disabled={refreshing}
            onClick={onRefresh}
            type="button"
          >
            <Icon name="refresh" />
          </button>
        </div>
      </section>

      {contentProfile !== undefined && !contentProfile?.is_complete ? (
        <section className="setup-banner" aria-label="Business setup">
          <div>
            <h3>{contentProfile ? "Finish setting up your business" : "Get your business set up"}</h3>
            <p>
              {contentProfile
                ? "A few details are still missing — finish in under a minute."
                : "Four quick steps: business, audience, brand voice, and content plan. Everything you enter is saved to this workspace and used as the default for new content."}
            </p>
          </div>
          <div className="setup-banner__actions">
            <button className="button button--primary" onClick={() => setSetupWizardOpen(true)} type="button">
              {contentProfile ? "Finish setup" : "Quick Setup"}
            </button>
            <button className="button button--ghost" onClick={() => navigate("settings")} type="button">
              Set up manually
            </button>
          </div>
        </section>
      ) : null}

      {setupWizardOpen ? (
        <ContentSetupWizard
          token={token}
          workspaceId={workspaceId}
          initialProfile={contentProfile ?? null}
          onClose={() => setSetupWizardOpen(false)}
          onSaved={(profile) => {
            setContentProfile(profile);
            setSetupWizardOpen(false);
          }}
        />
      ) : null}

      <section className="financial-overview" aria-label="Business performance">
        <header className="financial-overview__header">
          <div>
            <p className="financial-overview__eyebrow">Business performance</p>
            <h3>Bankroll</h3>
          </div>
          <p>Connect a financial source to see verified business performance.</p>
        </header>
        <div className="financial-overview__circle-grid">
          {(["Revenue", "Spending", "Net profit", "Profit margin"] as const).map((label) => (
            <article className="financial-overview__circle-card" key={label}>
              <span>{label}</span>
              <div className="financial-overview__circle" aria-label={`${label}: financial source not connected`}>
                <div>
                  <strong>Not connected</strong>
                  <small>Source-backed data required</small>
                </div>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="business-section what-needs-you" id="active-alerts">
        <SectionHeader
          title="What needs you now"
          detail={decisions.length ? "High-value human decisions and interventions, ordered by severity." : "No high-value human decisions are currently reported by the backend."}
          action={decisions.length ? <button className="text-button" onClick={() => navigate("review")} type="button">Open Human Review</button> : undefined}
        />
        {decisions.length === 0 ? (
          <EmptyState icon="check" title="Nothing needs your decision" message="No review, failure, spend, or connection condition currently requires a founder action." />
        ) : (
          <div className="decision-list">
            {decisions.map((alert) => (
              <button className={`decision-card decision-card--${alert.severity}`} key={alert.key} onClick={() => navigate(decisionTargets[alert.key] ?? "mission")} type="button">
                <span className={`severity-tag severity-tag--${alert.severity}`}>{alert.severity}</span>
                <span className="decision-card__copy"><strong>{alert.title}</strong><small>{alert.message}</small></span>
                {alert.count > 1 ? <b>{alert.count}</b> : <Icon name="arrow" size={16} />}
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="ask-business" aria-labelledby="ask-business-title">
        <div>
          <p className="ask-business__eyebrow">Ask My Business</p>
          <h3 id="ask-business-title">What do you want sorted?</h3>
          <p>Describe the outcome. The appropriate worker, controls, and audit path will be selected once this command layer is connected.</p>
        </div>
        <form className="ask-business__form" onSubmit={(event) => { event.preventDefault(); setAskNotice("Ask My Business is not connected in this Founder Preview."); }}>
          <input aria-label="What do you want sorted?" placeholder="Prepare a week’s content, explain a profit drop, or find opportunities…" />
          <button className="button button--primary" type="submit">Ask</button>
        </form>
        {askNotice ? <p className="ask-business__notice" role="status">{askNotice}</p> : null}
      </section>

      <section className="business-section workforce-summary">
        <SectionHeader
          title="AI Workforce"
          detail={`${realWorkers.length} registered worker process${realWorkers.length === 1 ? "" : "es"}; ${activeWorkerCount} currently live. Department capability is shown only when configured.`}
          action={<button className="text-button" onClick={() => navigate("workers")} type="button">Open workforce</button>}
        />
        <div className="department-grid">
          {departments.map(([name, responsibility]) => {
            const state = departmentCardState(name, realWorkers);
            return (
              <article className="department-card" key={name}>
                <span className="department-card__state">{state.label}</span>
                <h4>{name}</h4>
                <p>{responsibility}</p>
                <small>{state.detail}</small>
              </article>
            );
          })}
        </div>
        <div className="workforce-telemetry">
          <div><span>Registered processes</span><strong>{realWorkers.length}</strong></div>
          <div><span>Live processes</span><strong>{activeWorkerCount}</strong></div>
          <div><span>Queue depth</span><strong>{data.pipelines.queue_depth}</strong></div>
          <div><span>Retries recorded</span><strong>{realWorkers.reduce((total, worker) => total + worker.retry_count, 0)}</strong></div>
        </div>
      </section>

      <div className="business-home__signals">
        <section className="surface activity-surface">
          <SectionHeader title="What happened" detail="Backend-recorded activity in this workspace" action={<button className="text-button" onClick={() => navigate("analytics")} type="button">Open activity</button>} />
          <ActivityFeedView data={{ ...data.activity, items: data.activity.items.slice(0, 5) }} />
        </section>
        <section className="surface health-surface">
          <SectionHeader title="System signals" detail="Advanced operational detail" />
          {data.health.indicators.length === 0 ? (
            <EmptyState
              icon="activity"
              title="System status unavailable"
              message="No health indicators were returned for this workspace."
            />
          ) : (
            <div className="health-list">
              {data.health.indicators.map((indicator) => (
                <div className="health-row" key={indicator.key}>
                  <span className={`health-dot health-dot--${indicator.status}`} />
                  <div><strong>{indicator.label}</strong><small>{indicator.detail}</small></div>
                  <Status value={indicator.status} />
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      <section className="surface quick-surface business-home__controls">
        <SectionHeader title="Advanced operator controls" detail="Existing audited controls remain available; destructive actions require explicit confirmation." />
        <QuickActionsView token={token} workspaceId={workspaceId} />
      </section>
    </div>
  );
}

function ReviewQueue({
  gates,
  approvedGates,
  workspaceName,
  busy,
  onDecision,
  onEdit,
}: {
  gates: ReviewGate[];
  approvedGates: ReviewGate[];
  workspaceName: string;
  busy: string | null;
  onDecision: (gate: ReviewGate, approved: boolean) => Promise<void>;
  onEdit: (gate: ReviewGate, edits: { script_hook?: string; script_body?: string; script_cta?: string }) => Promise<void>;
}) {
  const [selected, setSelected] = useState<ReviewGate | null>(null);
  const [editing, setEditing] = useState(false);
  const [draftHook, setDraftHook] = useState("");
  const [draftBody, setDraftBody] = useState("");
  const [draftCta, setDraftCta] = useState("");
  const [editingVersionId, setEditingVersionId] = useState<string | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  const closeDrawer = () => {
    setSelected(null);
    setEditing(false);
    setEditError(null);
  };
  const drawerRef = useDialogFocus<HTMLElement>(selected !== null, closeDrawer);
  useEffect(() => {
    if (selected) {
      const fresh = [...gates, ...approvedGates].find((gate) => gate.id === selected.id);
      if (fresh) setSelected(fresh);
    }
    // Deliberately not syncing draftHook/Body/Cta/editingVersionId here:
    // the background auto-refresh interval can update `gates` while an
    // edit form is open, and if it silently re-pinned the save to the
    // now-current version, a stale draft would pass the version check
    // while still submitting stale text — exactly defeating the
    // lost-update protection. The captured editingVersionId (and draft
    // text) only change when the user explicitly starts editing again.
  }, [gates, approvedGates]);

  const startEditing = (gate: ReviewGate) => {
    setDraftHook(gate.script_hook ?? "");
    setDraftBody(gate.script_body ?? "");
    setDraftCta(gate.script_cta ?? "");
    setEditingVersionId(gate.content_version_id);
    setEditError(null);
    setEditing(true);
  };

  const saveEdits = async () => {
    if (!selected) return;
    setEditError(null);
    try {
      await onEdit(
        { ...selected, content_version_id: editingVersionId },
        { script_hook: draftHook, script_body: draftBody, script_cta: draftCta },
      );
      setEditing(false);
    } catch (cause) {
      setEditError(cause instanceof Error ? cause.message : "Unable to save the edit.");
    }
  };

  return (
    <>
      <div className="review-summary">
        <div><strong>{gates.length}</strong><span>Awaiting review</span></div>
        <p>Human Review Gate keeps every publish decision in your control.</p>
      </div>
      {gates.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon"><Icon name="check" size={28} /></span>
          <h3>You&apos;re all caught up</h3>
          <p>No content is waiting at the Human Review Gate.</p>
        </div>
      ) : (
        <div className="review-grid">
          {gates.map((gate) => (
            <article className="review-card" key={gate.id}>
              <header>
                <Status value={gate.status} />
                <time dateTime={gate.requested_at}>{relativeTime(gate.requested_at)}</time>
              </header>
              <h3>{gate.topic}</h3>
              <div className="review-meta">
                <span><b>Pipeline</b><code>{gate.pipeline_run_id.slice(0, 8)}</code></span>
                <span><b>Workspace</b>{workspaceName}</span>
                <span><b>Stage</b>{gate.stage.replaceAll("_", " ")}</span>
              </div>
              <footer>
                <button className="button button--approve" disabled={busy === gate.id} onClick={() => void onDecision(gate, true)} type="button">
                  <Icon name="check" size={15} /> Approve
                </button>
                <button className="button button--reject" disabled={busy === gate.id} onClick={() => void onDecision(gate, false)} type="button">
                  <Icon name="close" size={15} /> Reject
                </button>
                <button className="button button--open" onClick={() => setSelected(gate)} type="button">Open <Icon name="arrow" size={14} /></button>
              </footer>
            </article>
          ))}
        </div>
      )}
      <div className="review-summary review-summary--ready">
        <div><strong>{approvedGates.length}</strong><span>Ready to publish</span></div>
        <p>Approved content waiting on a configured publishing provider. No external publishing occurs automatically.</p>
      </div>
      {approvedGates.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon"><Icon name="pipelines" size={28} /></span>
          <h3>Nothing ready yet</h3>
          <p>Content approved at the Human Review Gate will appear here.</p>
        </div>
      ) : (
        <div className="review-grid">
          {approvedGates.map((gate) => (
            <article className="review-card review-card--approved" key={gate.id}>
              <header>
                <Status value={gate.status} />
                <time dateTime={gate.decided_at ?? gate.requested_at}>{relativeTime(gate.decided_at ?? gate.requested_at)}</time>
              </header>
              <h3>{gate.topic}</h3>
              <div className="review-meta">
                <span><b>Pipeline</b><code>{gate.pipeline_run_id.slice(0, 8)}</code></span>
                <span><b>Workspace</b>{workspaceName}</span>
              </div>
              <footer>
                <button className="button button--open" onClick={() => setSelected(gate)} type="button">Open <Icon name="arrow" size={14} /></button>
              </footer>
            </article>
          ))}
        </div>
      )}
      {selected ? (
        <div className="drawer-backdrop" onMouseDown={closeDrawer} role="presentation">
          <aside aria-label="Review details" aria-modal="true" className="review-drawer" onMouseDown={(event) => event.stopPropagation()} ref={drawerRef} role="dialog" tabIndex={-1}>
            <header className="drawer-header">
              <div><p>Human Review Gate</p><h2>{selected.topic}</h2></div>
              <button aria-label="Close review details" onClick={closeDrawer} type="button"><Icon name="close" /></button>
            </header>
            <div className="drawer-meta">
              <span><b>Status</b><Status value={selected.status} /></span>
              <span><b>Created</b>{formatDate(selected.requested_at)}</span>
              <span><b>Pipeline</b><code>{selected.pipeline_run_id}</code></span>
              <span><b>Workspace</b>{workspaceName}</span>
            </div>
            {editing ? (
              <div className="content-preview content-preview--editing">
                <label>Hook<textarea onChange={(event) => setDraftHook(event.target.value)} rows={2} value={draftHook} /></label>
                <label>Script<textarea onChange={(event) => setDraftBody(event.target.value)} rows={8} value={draftBody} /></label>
                <label>Call to action<textarea onChange={(event) => setDraftCta(event.target.value)} rows={2} value={draftCta} /></label>
                {editError ? <p className="error" role="alert">{editError}</p> : null}
                <div className="drawer-actions">
                  <button className="button" onClick={() => { setEditing(false); setEditError(null); }} type="button">Cancel</button>
                  <button className="button button--approve" disabled={busy === selected.id} onClick={() => void saveEdits()} type="button">Save changes</button>
                </div>
              </div>
            ) : (
              <div className="content-preview">
                {selected.script_hook ? <section><h4>Hook</h4><p>{selected.script_hook}</p></section> : null}
                {selected.script_body ? <section><h4>Script</h4><p>{selected.script_body}</p></section> : null}
                {selected.script_cta ? <section><h4>Call to action</h4><p>{selected.script_cta}</p></section> : null}
                {!selected.script_hook && !selected.script_body && !selected.script_cta ? <p>No text content was attached to this review.</p> : null}
              </div>
            )}
            {selected.status === "awaiting" && !editing ? (
              <footer className="drawer-actions">
                <button className="button" onClick={() => startEditing(selected)} type="button">Edit content</button>
                <button className="button button--reject" disabled={busy === selected.id} onClick={() => void onDecision(selected, false)} type="button">Reject</button>
                <button className="button button--approve" disabled={busy === selected.id} onClick={() => void onDecision(selected, true)} type="button">Approve content</button>
              </footer>
            ) : null}
          </aside>
        </div>
      ) : null}
    </>
  );
}

function PipelinesView({ data }: { data: PipelineMonitor }) {
  return (
    <>
      <div className="compact-metrics">
        <article><span>Active</span><strong>{data.active_pipelines}</strong></article>
        <article><span>Waiting</span><strong>{data.jobs_waiting}</strong></article>
        <article><span>Completed</span><strong>{data.jobs_completed}</strong></article>
        <article><span>Failed</span><strong>{data.jobs_failed}</strong></article>
      </div>
      <div className="data-table">
        <div className="data-row data-row--head"><span>Pipeline</span><span>Status</span><span>Current stage</span><span>Last update</span></div>
        {data.pipelines.map((pipeline) => (
          <div className="data-row" key={pipeline.id}>
            <span><code>{pipeline.id.slice(0, 12)}</code></span>
            <span><Status value={pipeline.status} /></span>
            <span>{pipeline.current_stage.replaceAll("_", " ")}</span>
            <span>{relativeTime(pipeline.updated_at)}</span>
          </div>
        ))}
      </div>
    </>
  );
}

function WorkersView({ data }: { data: WorkerMonitor }) {
  return (
    <div className="worker-grid">
      {data.workers.map((worker) => (
        <article className="worker-card" key={worker.id}>
          <header>
            <span className="worker-avatar"><Icon name="workers" /></span>
            <div><h3>{worker.name}</h3><Status value={worker.status} /></div>
          </header>
          <div className="worker-stats">
            <span><b>{worker.queue}</b>Queue</span>
            <span><b>{worker.jobs_completed_today}</b>Completed</span>
            <span><b>{worker.jobs_failed_today}</b>Failed</span>
          </div>
          <p><span>Current task</span>{worker.current_task ?? worker.current_job ?? "Idle"}</p>
          <small>Heartbeat {formatDate(worker.last_heartbeat_at)}</small>
        </article>
      ))}
    </div>
  );
}

function CustomersView({ data }: { data: Customers }) {
  return (
    <>
      <div className="compact-metrics">
        <article><span>Active</span><strong>{data.active_users}</strong></article>
        <article><span>Paying</span><strong>{data.paying_users}</strong></article>
        <article><span>Trial</span><strong>{data.trial_users}</strong></article>
        <article><span>Revenue MTD</span><strong>{money(data.revenue_mtd_usd)}</strong></article>
      </div>
      <div className="data-table">
        <div className="data-row data-row--head"><span>Workspace</span><span>Plan</span><span>Status</span><span>Members</span></div>
        {data.customers.map((customer) => (
          <div className="data-row" key={customer.workspace_id}>
            <span><strong>{customer.name}</strong></span>
            <span>{customer.plan}</span>
            <span><Status value={customer.subscription_status} /></span>
            <span>{customer.member_count}</span>
          </div>
        ))}
      </div>
    </>
  );
}

const LEAD_STATUSES = ["new", "contacted", "qualified", "negotiation", "won", "lost", "nurturing"];

function LeadsView({
  data,
  token,
  workspaceId,
  refresh,
}: {
  data: Leads;
  token: string;
  workspaceId: string;
  refresh: () => void;
}) {
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [form, setForm] = useState({ name: "", company: "", email: "", source: "manual" });
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      await createLead(token, workspaceId, form);
      setShowForm(false);
      setForm({ name: "", company: "", email: "", source: "manual" });
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to create the lead.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="view-actions">
        <p>{data.total} leads in this workspace</p>
        <button className="button button--primary" onClick={() => setShowForm((value) => !value)} type="button">{showForm ? "Cancel" : "Add lead"}</button>
      </div>
      {showForm ? (
        <form className="inline-form" onSubmit={(event) => void submit(event)}>
          <input aria-label="Lead name" onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="Name" required value={form.name} />
          <input aria-label="Lead company" onChange={(event) => setForm({ ...form, company: event.target.value })} placeholder="Company" value={form.company} />
          <input aria-label="Lead email" onChange={(event) => setForm({ ...form, email: event.target.value })} placeholder="Email" required type="email" value={form.email} />
          <input aria-label="Lead source" onChange={(event) => setForm({ ...form, source: event.target.value })} placeholder="Source" value={form.source} />
          <button className="button button--primary" disabled={busy} type="submit">{busy ? "Saving…" : "Save lead"}</button>
        </form>
      ) : null}
      {actionError ? <p className="error" role="alert">{actionError}</p> : null}
      {data.leads.length === 0 ? (
        <EmptyState icon="leads" title="No leads yet" message="Add your first lead or connect a source to start tracking your pipeline." />
      ) : (
      <div className="data-table leads-table">
        <div className="data-row data-row--head"><span>Lead</span><span>Company</span><span>Source</span><span>Status</span></div>
        {data.leads.map((lead) => (
          <div className="data-row" key={lead.id}>
            <span><strong>{lead.name}</strong><small>{lead.email}</small></span>
            <span>{lead.company ?? "—"}</span>
            <span>{lead.source}</span>
            <span>
              <select
                aria-label={`Status for ${lead.name}`}
                onChange={(event) => {
                  setActionError(null);
                  void updateLead(token, workspaceId, lead.id, { status: event.target.value })
                    .then(refresh)
                    .catch((cause: unknown) => {
                      setActionError(cause instanceof Error ? cause.message : "Unable to update the lead.");
                    });
                }}
                value={lead.status}
              >
                {LEAD_STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
              </select>
            </span>
          </div>
        ))}
      </div>
      )}
    </>
  );
}

function ResearchView({
  data,
  token,
  workspaceId,
  refresh,
}: {
  data: { summary: ResearchSummary; opportunities: Opportunity[] };
  token: string;
  workspaceId: string;
  refresh: () => void;
}) {
  const [objective, setObjective] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [selected, setSelected] = useState<OpportunityDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<string | null>(null);

  const runResearch = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const run = await createResearchRun(token, workspaceId, {
        research_objective: objective,
        max_searches: 5,
        max_provider_calls: 5,
        max_tokens: 4000,
        max_cost_usd: "0.00",
        max_attempts: 3,
      });
      setObjective("");
      setNotice(run.last_error ?? "Research run recorded.");
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to create the research run.");
    } finally {
      setBusy(false);
    }
  };

  const openEvidence = async (opportunity: Opportunity) => {
    setLoadingDetail(opportunity.id);
    setActionError(null);
    try {
      setSelected(await getOpportunityDetail(token, workspaceId, opportunity.id));
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to load evidence.");
    } finally {
      setLoadingDetail(null);
    }
  };

  const runAudit = async (opportunity: Opportunity) => {
    setBusy(true);
    setActionError(null);
    try {
      const audit: ResearchAudit = await auditOpportunity(token, workspaceId, opportunity.id);
      setNotice(`Research Auditor: ${audit.state.replaceAll("_", " ")}.`);
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to run the Research Auditor.");
    } finally {
      setBusy(false);
    }
  };

  const sendToStrategist = async (opportunity: Opportunity) => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await sendOpportunityToStrategist(token, workspaceId, opportunity.id);
      setNotice(result.detail);
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Strategist handoff is unavailable.");
    } finally {
      setBusy(false);
    }
  };

  const current = data.summary.current_research ?? data.summary.last_run;
  const executionUnavailable = data.summary.provider_state === "not_configured";
  return (
    <div className="research-view stack">
      <section className="research-hero surface">
        <div>
          <p className="page-kicker">Scout + Research Auditor</p>
          <h2>Evidence-backed opportunities</h2>
          <p>Scout records bounded research evidence. Research Auditor independently checks provenance before any future Strategist handoff.</p>
        </div>
        <Status value={data.summary.provider_state === "not_configured" ? "Research provider not configured" : data.summary.provider_state} />
      </section>

      <section className="research-command surface">
        <div>
          <SectionHeader title="Run research" detail="Manual only. Daily and custom schedules remain disabled in this Founder Preview." />
          <p className="research-limits">Default limits: 5 searches · 5 provider calls · 4,000 tokens · $0.00 preview budget · 3 attempts.</p>
        </div>
        <form className="research-command__form" onSubmit={(event) => void runResearch(event)}>
          <input aria-label="Research objective" disabled={executionUnavailable} maxLength={1000} onChange={(event) => setObjective(event.target.value)} placeholder="Describe the opportunity or demand signal to investigate" required value={objective} />
          <button className="button button--primary" disabled={busy || executionUnavailable} type="submit">{busy ? "Recording…" : "Run research"}</button>
        </form>
        {data.summary.provider_state === "not_configured" ? <p className="research-not-configured" role="status">RESEARCH PROVIDER NOT CONFIGURED — no external research call, spend, or fabricated opportunity will be created.</p> : null}
        {notice ? <p className="research-notice" role="status">{notice}</p> : null}
        {actionError ? <p className="error" role="alert">{actionError}</p> : null}
      </section>

      <section className="research-status-grid" aria-label="Scout status">
        <article><span>Current research</span><strong>{current ? current.status.replaceAll("_", " ") : "Not run"}</strong><small>{current ? current.research_objective : "No manual research run has been created."}</small></article>
        <article><span>Opportunities found</span><strong>{data.summary.opportunities_found}</strong><small>Only evidence-backed opportunity records are counted.</small></article>
        <article><span>Audited findings</span><strong>{data.summary.audited_opportunities}</strong><small>{data.summary.blocked_findings} blocked by independent audit.</small></article>
        <article><span>Cost today</span><strong>{money(data.summary.cost_today_usd)}</strong><small>Provider usage is attributable only when a provider is configured.</small></article>
      </section>

      <section className="surface research-opportunities">
        <SectionHeader title="Opportunities" detail="Auditable observations, not automatic content instructions." />
        {data.opportunities.length === 0 ? (
          <EmptyState icon="leads" title="No opportunities yet" message="Connect a research provider or run the explicit test path in automated validation; the preview will not invent trends or demand signals." />
        ) : (
          <div className="research-opportunity-grid">
            {data.opportunities.map((opportunity) => (
              <article className="research-opportunity" key={opportunity.id}>
                <header><Status value={opportunity.audit_gate_status} /><span>{opportunity.test_data ? "TEST DATA" : opportunity.freshness}</span></header>
                <h3>{opportunity.title}</h3>
                <p>{opportunity.summary}</p>
                <dl>
                  <div><dt>Evidence</dt><dd>{opportunity.source_count} source{opportunity.source_count === 1 ? "" : "s"}</dd></div>
                  <div><dt>Confidence</dt><dd>{Number(opportunity.confidence).toLocaleString(undefined, { style: "percent", maximumFractionDigits: 0 })}</dd></div>
                  <div><dt>Performance</dt><dd>{opportunity.performance_data_state.replaceAll("_", " ")}</dd></div>
                </dl>
                <footer>
                  <button className="button button--open" disabled={loadingDetail === opportunity.id} onClick={() => void openEvidence(opportunity)} type="button">{loadingDetail === opportunity.id ? "Loading…" : "Inspect evidence"}</button>
                  <button className="button button--secondary" disabled={busy} onClick={() => void runAudit(opportunity)} type="button">Run auditor</button>
                  <button className="text-button" disabled={busy || opportunity.audit_gate_status !== "pass"} onClick={() => void sendToStrategist(opportunity)} type="button">Send to Strategist</button>
                </footer>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="research-boundaries surface">
        <SectionHeader title="Research boundaries" detail="The system remains fail-closed where evidence, providers, schedules, or performance data are absent." />
        <div className="research-boundaries__grid">
          <p><strong>Sources</strong> Provenance, freshness, publisher, author, claim support, and rejection reason are inspectable per opportunity.</p>
          <p><strong>Auditor</strong> Scout cannot approve its own work. Only an independent <code>pass</code> can make a future Strategist handoff eligible.</p>
          <p><strong>Performance</strong> NO PERFORMANCE DATA is retained until a real workspace source is configured.</p>
          <p><strong>Scheduling</strong> {data.summary.schedule_enabled ? "Enabled by an explicit future policy." : "Disabled by default; no autonomous Scout cycle is running."}</p>
        </div>
      </section>

      {selected ? (
        <section aria-label="Opportunity evidence" className="research-evidence surface">
          <SectionHeader action={<button className="text-button" onClick={() => setSelected(null)} type="button">Close</button>} detail="Immutable source provenance and the latest independent Research Auditor decision." title={selected.opportunity.title} />
          <div className="research-evidence__sources">
            {selected.evidence.length ? selected.evidence.map((item) => (
              <article key={item.source.id}>
                <Status value={item.source.handling_state} />
                <a href={item.source.canonical_url} rel="noreferrer" target="_blank">{item.source.publisher ?? item.source.canonical_url}</a>
                <p>{item.claim_supported}</p>
                <small>Retrieved {formatDate(item.source.retrieved_at)} · {item.source.freshness} · confidence {Number(item.source.confidence).toLocaleString(undefined, { style: "percent", maximumFractionDigits: 0 })}</small>
              </article>
            )) : <p>No source evidence is available.</p>}
          </div>
          <div className="research-evidence__audit">
            <h4>Research Auditor</h4>
            {selected.latest_audit ? <><Status value={selected.latest_audit.state} /><p>{selected.latest_audit.blocked_reasons.join(" ") || selected.latest_audit.warnings.join(" ") || "Independent audit passed without warnings."}</p></> : <p>NOT RUN — this opportunity is not eligible for Strategist handoff.</p>}
          </div>
        </section>
      ) : null}
    </div>
  );
}

function StrategyView({
  data,
  token,
  workspaceId,
  refresh,
}: {
  data: { summary: StrategySummary; briefs: StrategyBrief[]; opportunities: Opportunity[] };
  token: string;
  workspaceId: string;
  refresh: () => void;
}) {
  const [objective, setObjective] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [selected, setSelected] = useState<StrategyBriefDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<string | null>(null);
  const eligibleOpportunities = data.opportunities.filter((item) => item.audit_gate_status === "pass");
  const current = data.summary.current_strategy ?? data.summary.last_run;
  const executionUnavailable = (
    data.summary.provider_state === "not_configured"
    || data.summary.business_context_state !== "complete"
  );

  const toggleOpportunity = (opportunityId: string) => {
    setSelectedIds((currentIds) => currentIds.includes(opportunityId)
      ? currentIds.filter((item) => item !== opportunityId)
      : [...currentIds, opportunityId].slice(0, 5));
  };

  const runStrategy = async (event: FormEvent) => {
    event.preventDefault();
    if (selectedIds.length === 0) {
      setActionError("Select a Research Auditor PASS opportunity before recording a strategy request.");
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const run = await createStrategyRun(token, workspaceId, {
        strategy_objective: objective,
        source_opportunity_ids: selectedIds,
        max_provider_calls: 5,
        max_tokens: 4000,
        max_cost_usd: "0.00",
        max_attempts: 3,
      });
      setObjective("");
      setNotice(run.last_error ?? "Strategy request recorded.");
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to record the strategy request.");
    } finally {
      setBusy(false);
    }
  };

  const openBrief = async (brief: StrategyBrief) => {
    setLoadingDetail(brief.id);
    setActionError(null);
    try {
      setSelected(await getStrategyBriefDetail(token, workspaceId, brief.id));
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to load the Strategy Brief.");
    } finally {
      setLoadingDetail(null);
    }
  };

  const runAudit = async (brief: StrategyBrief) => {
    setBusy(true);
    setActionError(null);
    try {
      const audit: StrategyAudit = await auditStrategyBrief(token, workspaceId, brief.id);
      setNotice(`Strategy Auditor: ${audit.state.replaceAll("_", " ")}.`);
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to run the Strategy Auditor.");
    } finally {
      setBusy(false);
    }
  };

  const sendToWriter = async (brief: StrategyBrief) => {
    setBusy(true);
    setActionError(null);
    try {
      const result = await sendStrategyBriefToWriter(token, workspaceId, brief.id);
      setNotice(result.detail);
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Writer handoff is unavailable.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="strategy-view stack">
      <section className="strategy-hero surface">
        <div>
          <p className="page-kicker">Strategist + Strategy Auditor</p>
          <h2>Evidence-led strategy, not invented predictions</h2>
          <p>Only opportunities with a Research Auditor PASS can enter Strategist. An independent Strategy Auditor must PASS before any future Writer eligibility.</p>
        </div>
        <Status value={data.summary.provider_state === "not_configured" ? "Strategy provider not configured" : data.summary.provider_state} />
      </section>

      <section className="strategy-command surface">
        <div>
          <SectionHeader title="Record a strategy request" detail="Manual only. A configured provider, Business Brain, and capability profile are required before a real Strategy Brief can be created." />
          <p className="research-limits">Default limits: up to 5 approved opportunities · 5 provider calls · 4,000 tokens · $0.00 preview budget · 3 attempts.</p>
        </div>
        <form className="strategy-command__form" onSubmit={(event) => void runStrategy(event)}>
          <input aria-label="Strategy objective" disabled={executionUnavailable} maxLength={1000} onChange={(event) => setObjective(event.target.value)} placeholder="What business outcome should this strategy support?" required value={objective} />
          <fieldset className="strategy-opportunity-picker" disabled={executionUnavailable}>
            <legend>Research Auditor PASS opportunities</legend>
            {eligibleOpportunities.length ? eligibleOpportunities.map((opportunity) => (
              <label key={opportunity.id}>
                <input checked={selectedIds.includes(opportunity.id)} onChange={() => toggleOpportunity(opportunity.id)} type="checkbox" />
                <span>{opportunity.title}</span>
              </label>
            )) : <p>No Research Auditor PASS opportunities are available in this workspace.</p>}
          </fieldset>
          <button className="button button--primary" disabled={busy || executionUnavailable || eligibleOpportunities.length === 0} type="submit">{busy ? "Recording…" : "Record strategy request"}</button>
        </form>
        {data.summary.provider_state === "not_configured" ? <p className="research-not-configured" role="status">STRATEGY PROVIDER NOT CONFIGURED — no external strategy call, spend, prediction, or fabricated brief will be created.</p> : null}
        {data.summary.business_context_state !== "complete" ? <p className="strategy-context" role="status">BUSINESS CONTEXT INCOMPLETE — no workspace objective, audience rules, or capability profile is configured.</p> : null}
        {notice ? <p className="research-notice" role="status">{notice}</p> : null}
        {actionError ? <p className="error" role="alert">{actionError}</p> : null}
      </section>

      <section className="research-status-grid" aria-label="Strategist status">
        <article><span>Current strategy</span><strong>{current ? current.status.replaceAll("_", " ") : "Not run"}</strong><small>{current ? current.strategy_objective : "No strategy request has been recorded."}</small></article>
        <article><span>Approved intelligence</span><strong>{data.summary.opportunities_received}</strong><small>Only Research Auditor PASS opportunities are counted.</small></article>
        <article><span>Strategy Briefs</span><strong>{data.summary.briefs_created}</strong><small>{data.summary.briefs_passed} passed · {data.summary.briefs_blocked} blocked by independent audit.</small></article>
        <article><span>Cost today</span><strong>{money(data.summary.cost_today_usd)}</strong><small>{data.summary.performance_data_state === "no_data" ? "NO DATA for performance attribution." : "Source-backed state required."}</small></article>
      </section>

      <section className="surface strategy-briefs">
        <SectionHeader title="Strategy Briefs" detail="Structured recommendations with source opportunity links, feasibility state, and independent audit results." />
        {data.briefs.length === 0 ? (
          <EmptyState icon="mission" title="No Strategy Briefs yet" message="The preview will not invent briefs. Configure approved intelligence, Business Brain, provider capability, and spend controls before live strategy generation." />
        ) : (
          <div className="strategy-brief-grid">
            {data.briefs.map((brief) => (
              <article className="strategy-brief" key={brief.id}>
                <header><Status value={brief.audit_gate_status} /><span>{brief.test_data ? "TEST DATA" : brief.priority.replaceAll("_", " ")}</span></header>
                <h3>{brief.objective}</h3>
                <p>{brief.evidence_summary}</p>
                <dl>
                  <div><dt>Source state</dt><dd>{brief.audit_gate_status.replaceAll("_", " ")}</dd></div>
                  <div><dt>Cost</dt><dd>{brief.cost_state.replaceAll("_", " ")}</dd></div>
                  <div><dt>Capability</dt><dd>{brief.capability_state.replaceAll("_", " ")}</dd></div>
                </dl>
                <footer>
                  <button className="button button--open" disabled={loadingDetail === brief.id} onClick={() => void openBrief(brief)} type="button">{loadingDetail === brief.id ? "Loading…" : "Inspect brief"}</button>
                  <button className="button button--secondary" disabled={busy} onClick={() => void runAudit(brief)} type="button">Run auditor</button>
                  <button className="text-button" disabled={busy || brief.audit_gate_status !== "pass"} onClick={() => void sendToWriter(brief)} type="button">Check Writer eligibility</button>
                </footer>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="research-boundaries surface">
        <SectionHeader title="Strategy boundaries" detail="The system remains fail-closed when intelligence, business context, cost, capability, or independent review is incomplete." />
        <div className="research-boundaries__grid">
          <p><strong>Intelligence</strong> Only Research Auditor <code>pass</code> opportunities can enter a bounded Strategy run.</p>
          <p><strong>Business Brain</strong> {data.summary.business_context_state === "complete" ? "Configured state reported by the backend." : "BUSINESS CONTEXT INCOMPLETE — no goal, audience, or rule is assumed."}</p>
          <p><strong>Auditor</strong> Strategy Auditor independently checks provenance, feasibility, repetition, and unsupported claims before Writer eligibility.</p>
          <p><strong>Scheduling</strong> {data.summary.schedule_enabled ? "Enabled by an explicit future policy." : "Disabled by default; no autonomous strategy cycle is running."}</p>
        </div>
      </section>

      {selected ? (
        <section aria-label="Strategy Brief detail" className="strategy-detail surface">
          <SectionHeader action={<button className="text-button" onClick={() => setSelected(null)} type="button">Close</button>} detail="Stored brief fields and the latest independent Strategy Auditor result." title={selected.brief.objective} />
          <div className="strategy-detail__grid">
            <p><strong>Audience</strong>{selected.brief.target_audience ?? "Not configured"}</p>
            <p><strong>Platform / format</strong>{selected.brief.target_platform ?? "Not configured"} · {selected.brief.content_format ?? "Not configured"}</p>
            <p><strong>Angle</strong>{selected.brief.creative_angle ?? "Not configured"}</p>
            <p><strong>Business goal</strong>{selected.brief.business_goal ?? "BUSINESS CONTEXT INCOMPLETE"}</p>
            <p><strong>Source opportunities</strong>{selected.source_opportunity_ids.length}</p>
            <p><strong>Writer handoff</strong>{selected.brief.writer_handoff_state.replaceAll("_", " ")}</p>
          </div>
          <div className="research-evidence__audit">
            <h4>Strategy Auditor</h4>
            {selected.latest_audit ? <><Status value={selected.latest_audit.state} /><p>{selected.latest_audit.blocked_reasons.join(" ") || selected.latest_audit.warnings.join(" ") || "Independent audit passed without warnings."}</p></> : <p>NOT RUN — Writer handoff remains blocked.</p>}
          </div>
        </section>
      ) : null}
    </div>
  );
}

function ContentDepartmentView({
  data,
  token,
  workspaceId,
  refresh,
}: {
  data: { summary: ContentDepartmentSummary; packages: ContentPackage[]; briefs: StrategyBrief[] };
  token: string;
  workspaceId: string;
  refresh: () => void;
}) {
  const [selectedBriefId, setSelectedBriefId] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ContentPackageDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<string | null>(null);
  const approvedBriefs = data.briefs.filter((brief) => brief.audit_gate_status === "pass");
  const current = data.summary.current_run ?? data.summary.last_run;
  const executionUnavailable = (
    data.summary.provider_state === "not_configured"
    || data.summary.business_context_state !== "complete"
  );

  const runContentDepartment = async (event: FormEvent) => {
    event.preventDefault();
    if (!selectedBriefId) {
      setActionError("Select a Strategy Auditor PASS brief before recording a Content Department request.");
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const run = await createContentDepartmentRun(token, workspaceId, {
        strategy_brief_id: selectedBriefId,
        max_provider_calls: 5,
        max_tokens: 4000,
        max_cost_usd: "0.00",
        max_attempts: 3,
        timeout_seconds: 900,
      });
      setNotice(run.last_error ?? "Content Department request recorded.");
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to record the Content Department request.");
    } finally {
      setBusy(false);
    }
  };

  const openPackage = async (pkg: ContentPackage) => {
    setLoadingDetail(pkg.id);
    setActionError(null);
    try {
      setSelected(await getContentPackageDetail(token, workspaceId, pkg.id));
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to load the Creative Package.");
    } finally {
      setLoadingDetail(null);
    }
  };

  const checkProducer = async (pkg: ContentPackage) => {
    setBusy(true);
    setActionError(null);
    try {
      const gate: ProducerGate = await getProducerGate(token, workspaceId, pkg.id);
      setNotice(gate.detail);
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Producer eligibility remains blocked.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="content-department-view stack">
      <section className="content-department-hero surface">
        <div>
          <p className="page-kicker">Creative Director + Writer + Independent Auditors</p>
          <h2>Build complete creative packages, not unreviewed drafts</h2>
          <p>Only Strategy Auditor PASS briefs may enter. Every Content Version is immutable; Language, Fact, Brand, and Originality audits must independently pass before future Producer eligibility.</p>
        </div>
        <Status value={data.summary.provider_state === "not_configured" ? "Content provider not configured" : data.summary.provider_state} />
      </section>

      <section className="content-department-command surface">
        <div>
          <SectionHeader title="Record a content package request" detail="Manual only. A configured content provider, complete Business Brain context, and explicit capability policy are required before a real Creative Direction or Content Version can be created." />
          <p className="research-limits">Default limits: 1 approved Strategy Brief · 5 provider calls · 4,000 tokens · $0.00 preview budget · 3 attempts.</p>
        </div>
        <form className="content-department-command__form" onSubmit={(event) => void runContentDepartment(event)}>
          <label>
            <span>Strategy Auditor PASS brief</span>
            <select aria-label="Strategy Auditor PASS brief" disabled={executionUnavailable} onChange={(event) => setSelectedBriefId(event.target.value)} value={selectedBriefId}>
              <option value="">Select an approved strategy brief</option>
              {approvedBriefs.map((brief) => <option key={brief.id} value={brief.id}>{brief.objective}</option>)}
            </select>
          </label>
          <button className="button button--primary" disabled={busy || executionUnavailable || approvedBriefs.length === 0} type="submit">{busy ? "Recording…" : "Record content request"}</button>
        </form>
        {data.summary.provider_state === "not_configured" ? <p className="research-not-configured" role="status">CONTENT PROVIDER NOT CONFIGURED — no Creative Direction, content version, claim, audit, provider cost, or fabricated package will be created.</p> : null}
        {data.summary.business_context_state !== "complete" ? <p className="strategy-context" role="status">BUSINESS CONTEXT INCOMPLETE — brand rules, audience constraints, and capability policy are not assumed.</p> : null}
        {notice ? <p className="research-notice" role="status">{notice}</p> : null}
        {actionError ? <p className="error" role="alert">{actionError}</p> : null}
      </section>

      <section className="research-status-grid" aria-label="Content Department status">
        <article><span>Current request</span><strong>{current ? current.status.replaceAll("_", " ") : "Not run"}</strong><small>{current?.last_error ?? "No Content Department request has been recorded."}</small></article>
        <article><span>Creative directions</span><strong>{data.summary.creative_directions}</strong><small>Derived only from Strategy Auditor PASS briefs.</small></article>
        <article><span>Creative packages</span><strong>{data.summary.packages_ready}</strong><small>{data.summary.packages_in_progress} awaiting audit · {data.summary.packages_blocked} blocked.</small></article>
        <article><span>Claims awaiting evidence</span><strong>{data.summary.claims_unverified}</strong><small>{money(data.summary.cost_today_usd)} provider cost today.</small></article>
      </section>

      <section className="surface content-package-list">
        <SectionHeader title="Creative Packages" detail="Each package links its Strategy Brief, Creative Direction, immutable Content Version, structured claims, audit evidence, originality record, and producer state." />
        {data.packages.length === 0 ? (
          <EmptyState icon="pipelines" title="No Creative Packages yet" message="The preview will not invent content. Configure approved content capability, Business Brain, evidence rules, and spend policy before live generation." />
        ) : (
          <div className="content-package-grid">
            {data.packages.map((pkg) => (
              <article className="content-package" key={pkg.id}>
                <header><Status value={pkg.audit_gate_status} /><span>{pkg.test_data ? "TEST DATA" : pkg.status.replaceAll("_", " ")}</span></header>
                <h3>{String(pkg.package_fields.title ?? "Creative Package")}</h3>
                <p>Writer: {pkg.writer_worker_id.replaceAll("_", " ")} · Version {pkg.content_version_id.slice(0, 8)}</p>
                <dl>
                  <div><dt>Audits</dt><dd>{pkg.audit_gate_status.replaceAll("_", " ")}</dd></div>
                  <div><dt>Originality</dt><dd>{pkg.status === "audited_blocked" ? "Requires differentiation" : "Not run"}</dd></div>
                  <div><dt>Producer</dt><dd>{pkg.producer_handoff_state.replaceAll("_", " ")}</dd></div>
                </dl>
                <footer>
                  <button className="button button--open" disabled={loadingDetail === pkg.id} onClick={() => void openPackage(pkg)} type="button">{loadingDetail === pkg.id ? "Loading…" : "Inspect package"}</button>
                  <button className="text-button" disabled={busy || pkg.audit_gate_status !== "pass"} onClick={() => void checkProducer(pkg)} type="button">Check Producer eligibility</button>
                </footer>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="research-boundaries surface">
        <SectionHeader title="Content Department boundaries" detail="Content creation is evidence-led, versioned, independently audited, and never self-approved." />
        <div className="research-boundaries__grid">
          <p><strong>Creative Direction</strong> Only Strategy Auditor <code>pass</code> briefs may initiate a bounded content request.</p>
          <p><strong>Claims</strong> Number, comparative, product, quote, price, and health/finance/legal-style claims require independent evidence; Writer text is never its own verification source.</p>
          <p><strong>Auditors</strong> Language, Fact, Brand, and Originality operate independently. Any block, error, or missing audit stops the package.</p>
          <p><strong>Human Review</strong> Producer readiness never publishes. The existing Human Review Gate must still approve the exact current Content Version before any publication policy can pass.</p>
          <p><strong>Scheduling</strong> {data.summary.schedule_enabled ? "Enabled by an explicit future policy." : "Disabled by default; no autonomous content cycle is running."}</p>
        </div>
      </section>

      {selected ? (
        <section aria-label="Creative Package detail" className="content-package-detail surface">
          <SectionHeader action={<button className="text-button" onClick={() => setSelected(null)} type="button">Close</button>} detail="Immutable Creative Direction, version-linked claims, independent audit evidence, originality status, and revision invalidation history." title={String(selected.package.package_fields.title ?? "Creative Package")} />
          <div className="content-package-detail__grid">
            <p><strong>Strategy Brief</strong>{selected.package.strategy_brief_id.slice(0, 8)}</p>
            <p><strong>Creative concept</strong>{selected.direction.creative_concept}</p>
            <p><strong>Hook direction</strong>{selected.direction.hook_direction ?? "Not configured"}</p>
            <p><strong>Version</strong>{selected.package.content_version_id.slice(0, 8)}</p>
            <p><strong>Revision lineage</strong>{selected.package.prior_content_version_id ? `Revision of ${selected.package.prior_content_version_id.slice(0, 8)}` : "Original version"}</p>
            <p><strong>Invalidations</strong>{selected.invalidation_count}</p>
          </div>
          <div className="content-package-detail__panels">
            <article><h4>Claims</h4>{selected.claims.length ? selected.claims.map((claim) => <p key={claim.id}><Status value={claim.verification_status} /> {claim.claim_text} <small>{claim.claim_type} · {claim.risk}</small></p>) : <p>No claims were extracted.</p>}</article>
            <article><h4>Independent audits</h4>{selected.audits.length ? selected.audits.map((audit: ContentAudit) => <p key={audit.id}><Status value={audit.state} /> {audit.auditor_type} · {audit.blocked_reasons.join(" ") || audit.warnings.join(" ") || "No findings recorded."}</p>) : <p>NOT RUN — Producer remains blocked.</p>}</article>
            <article><h4>Originality</h4><p>{selected.originality ? selected.originality.state.replaceAll("_", " ") : "NOT RUN"}</p><small>{selected.originality?.similarity_findings.length ? "Similarity findings require differentiation." : "No originality evaluation has been recorded."}</small></article>
          </div>
        </section>
      ) : null}
    </div>
  );
}

function BillingView({ spend, cost }: { spend: SpendDashboard; cost: CostControl }) {
  return (
    <>
      <div className="compact-metrics">
        <article><span>Spend today</span><strong>{money(spend.today_usd)}</strong></article>
        <article><span>Spend this month</span><strong>{money(spend.month_usd)}</strong></article>
        <article><span>Daily remaining</span><strong>{money(spend.budget_remaining_daily_usd)}</strong></article>
        <article><span>Projected month end</span><strong>{money(cost.projected_month_end_usd)}</strong></article>
      </div>
      <section className="surface"><SectionHeader title="Cost control" detail="Committed spend and budget safeguards" /><CostControlView data={cost} /></section>
    </>
  );
}

function GitHubSummary({ data }: { data: GitHubOut }) {
  return (
    <section className="surface">
      <SectionHeader
        title="Engineering delivery"
        detail={data.available ? data.repository ?? "Connected repository" : data.unavailable_reason ?? "GitHub unavailable"}
      />
      <div className="compact-metrics">
        <article><span>Open pull requests</span><strong>{data.open_pull_requests.length}</strong></article>
        <article><span>Failed actions</span><strong>{data.failed_actions.length}</strong></article>
        <article><span>Recent commits</span><strong>{data.latest_commits.length}</strong></article>
        <article><span>Branch CI</span><strong><Status value={data.branch_status.ci_status} /></strong></article>
      </div>
    </section>
  );
}

function ProducerView({
  data,
  token,
  workspaceId,
  refresh,
}: {
  data: { summary: ProductionSummary; jobs: ProductionRun[]; packages: ContentPackage[] };
  token: string;
  workspaceId: string;
  refresh: () => void;
}) {
  const [selectedPackageId, setSelectedPackageId] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const auditedPackages = data.packages.filter((item) => item.audit_gate_status === "pass");
  const currentJob = data.jobs[0] ?? null;
  const executionUnavailable = data.summary.provider_state === "not_configured";

  const runProducer = async (event: FormEvent) => {
    event.preventDefault();
    if (!selectedPackageId) return;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const run = await createProductionRun(token, workspaceId, {
        content_package_id: selectedPackageId,
        max_provider_calls: 5,
        max_render_calls: 2,
        max_cost_usd: "0.00",
        max_attempts: 3,
        max_repair_cycles: 2,
        timeout_seconds: 900,
      });
      setNotice(run.last_error ?? "Production request recorded.");
      refresh();
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : "Unable to create the production request.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="producer-view stack">
      <section className="producer-hero surface">
        <div>
          <p className="page-kicker">Producer + Media QA</p>
          <h2>Bounded, auditable production</h2>
          <p>Producer accepts only independently audited Content Packages. Media QA binds its checks to the exact final artifact hash before future Compliance, Chief Auditor, and Human Review gates.</p>
        </div>
        <Status value={data.summary.provider_state === "not_configured" ? "Production provider not configured" : data.summary.provider_state} />
      </section>

      <section className="producer-command surface">
        <div>
          <SectionHeader title="Request production" detail="Manual only. Provider calls, render calls, cost, attempts, repair cycles, and timeouts remain bounded." />
          <p className="producer-limits">Default limits: 5 provider calls · 2 render calls · $0.00 preview budget · 3 attempts · 2 repair cycles.</p>
        </div>
        <form className="producer-command__form" onSubmit={(event) => void runProducer(event)}>
          <select aria-label="Audited Content Package" disabled={executionUnavailable} onChange={(event) => setSelectedPackageId(event.target.value)} value={selectedPackageId}>
            <option value="">{auditedPackages.length ? "Select an independently audited package" : "No independently audited packages available"}</option>
            {auditedPackages.map((item) => <option key={item.id} value={item.id}>Package {item.id.slice(0, 8)} · {item.status.replaceAll("_", " ")}</option>)}
          </select>
          <button className="button button--primary" disabled={busy || executionUnavailable || !selectedPackageId} type="submit">{busy ? "Recording…" : "Request production"}</button>
        </form>
        {data.summary.provider_state === "not_configured" ? <p className="producer-not-configured" role="status">PRODUCTION PROVIDER NOT CONFIGURED — no asset generation, media rendering, external storage write, spend, callback, or fabricated artifact will be created.</p> : null}
        {notice ? <p className="producer-notice" role="status">{notice}</p> : null}
        {actionError ? <p className="error" role="alert">{actionError}</p> : null}
      </section>

      <section className="producer-status-grid" aria-label="Producer status">
        <article><span>Current production</span><strong>{currentJob ? currentJob.status.replaceAll("_", " ") : "Not run"}</strong><small>{currentJob ? `Package ${currentJob.content_package_id.slice(0, 8)}` : "No audited package has been submitted."}</small></article>
        <article><span>Final artifacts</span><strong>{data.summary.final_artifacts}</strong><small>Only immutable, hash-recorded artifacts are counted.</small></article>
        <article><span>Media QA</span><strong>{data.summary.media_qa_passed} passed</strong><small>{data.summary.media_qa_blocked} blocked · {data.summary.repair_required} repair required.</small></article>
        <article><span>Provider cost</span><strong>{money(data.summary.provider_cost_usd)}</strong><small>Cost is recorded only when a provider is configured.</small></article>
      </section>

      <section className="surface producer-artifacts">
        <SectionHeader title="Artifacts and Media QA" detail="Every generated component, final artifact, provider request, hash, QA result, repair, and invalidation must remain attributable to the exact Content Version." />
        {data.jobs.length === 0 ? (
          <EmptyState icon="pipelines" title="No production jobs yet" message="An independently audited Content Package and an approved production provider are required before a real artifact can exist. This preview will not invent media or QA passes." />
        ) : (
          <div className="producer-job-grid">
            {data.jobs.map((job) => (
              <article className="producer-job" key={job.id}>
                <header><Status value={job.status} /><span>{job.provider_state.replaceAll("_", " ")}</span></header>
                <h3>Production job {job.id.slice(0, 8)}</h3>
                <dl>
                  <div><dt>Content Version</dt><dd><code>{job.content_version_id.slice(0, 12)}</code></dd></div>
                  <div><dt>Provider / render calls</dt><dd>{job.provider_calls_used} / {job.render_calls_used}</dd></div>
                  <div><dt>Repair cycles</dt><dd>{job.repair_cycles_used} / {job.max_repair_cycles}</dd></div>
                  <div><dt>Cost</dt><dd>{money(job.actual_cost_usd)}</dd></div>
                </dl>
                <small>{job.last_error ?? "No provider outcome has been recorded."}</small>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="producer-boundaries surface">
        <SectionHeader title="Production and Media QA gates" detail="Production remains fail-closed when any provider, artifact, audit, policy, or human gate is absent." />
        <div className="producer-boundaries__grid">
          <p><strong>Lineage</strong> Components and final artifacts must reference the package, content item, exact Content Version, provider job, immutable hash, storage reference, and cost.</p>
          <p><strong>Media QA</strong> An independent worker checks the exact artifact hash for visual, audio, subtitle, script, platform, and package alignment before any future Compliance handoff.</p>
          <p><strong>Repairs</strong> Repairs are bounded to recorded QA findings and can never overwrite an immutable artifact or clear prior findings.</p>
          <p><strong>Readiness</strong> Media QA, Compliance, Chief Auditor, and exact-version Human Review are all required; no production record can publish automatically.</p>
        </div>
      </section>
    </div>
  );
}

function ComplianceView({
  data,
  refresh,
  token,
  workspaceId,
}: {
  data: { summary: ComplianceSummary; audits: ComplianceAudit[]; chiefAudits: ChiefAudit[]; reviewPackages: HumanReviewPackage[] };
  refresh: () => void;
  token: string;
  workspaceId: string;
}) {
  const [artifactId, setArtifactId] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const run = async (event: FormEvent) => {
    event.preventDefault();
    if (!artifactId.trim()) {
      setNotice("Select an independently audited final artifact before requesting Compliance.");
      return;
    }
    setBusy(true);
    try {
      const audit = await createComplianceRun(token, workspaceId, {
        final_artifact_id: artifactId.trim(),
        target_platform: "short_video",
      });
      setNotice(`${audit.status.replaceAll("_", " ")} — no provider call or cost was created.`);
      refresh();
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Compliance request could not be created.");
    } finally { setBusy(false); }
  };
  return <div className="compliance-view stack">
    <section className="compliance-hero surface">
      <p className="eyebrow">Final machine audit</p>
      <h2>Compliance & Chief Auditor</h2>
      <p>Exact-artifact policy, rights, disclosure, audit-chain, cost, and Human Review readiness. A pass can only hand the same artifact to Human Review; it cannot publish.</p>
      <div className="compliance-status-grid" aria-label="Compliance status">
        <div><span>Provider</span><strong>{data.summary.provider_state.replaceAll("_", " ")}</strong></div>
        <div><span>Policy</span><strong>{data.summary.policy_state.replaceAll("_", " ")}</strong></div>
        <div><span>Compliance passes</span><strong>{data.summary.passed}</strong></div>
        <div><span>Chief audit packages</span><strong>{data.summary.human_review_packages}</strong></div>
      </div>
    </section>
    <section className="compliance-command surface">
      <SectionHeader title="Request Compliance" detail="Manual, bounded request: 5 provider calls · 5 verification calls · 4,000 tokens · $0.00 preview budget · 3 attempts." />
      <form className="producer-command__form" onSubmit={(event) => void run(event)}>
        <input aria-label="Final artifact ID" placeholder="Final artifact ID from audited Producer output" value={artifactId} onChange={(event) => setArtifactId(event.target.value)} />
        <button className="primary-action" disabled={busy} type="submit">{busy ? "Requesting…" : "Request Compliance"}</button>
      </form>
      <p className="compliance-not-configured" role="status">COMPLIANCE PROVIDER NOT CONFIGURED — no policy retrieval, external rights verification, provider spend, remediation, publication, or fabricated compliance result will be created.</p>
      {notice ? <p className="producer-notice" role="status">{notice}</p> : null}
    </section>
    <section className="surface">
      <SectionHeader title="Audit chain" detail="Every required machine check must match the exact final artifact hash. Any missing, stale, invalidated, blocked, or errored evidence blocks progression." />
      <div className="compliance-chain-grid">
        <div><span>Media QA</span><strong>{data.chiefAudits.length ? "Recorded" : "Not run"}</strong></div>
        <div><span>Compliance</span><strong>{data.audits.length ? data.audits[0].status.replaceAll("_", " ") : "Not run"}</strong></div>
        <div><span>Chief Auditor</span><strong>{data.chiefAudits.length ? data.chiefAudits[0].status.replaceAll("_", " ") : "Not run"}</strong></div>
        <div><span>Human Review</span><strong>{data.reviewPackages.length ? "Package ready" : "Blocked"}</strong></div>
      </div>
    </section>
    <section className="surface compliance-evidence">
      <SectionHeader title="Evidence and downstream readiness" detail="Policy source, rights evidence, required disclosures, cost reconciliation, and Human Review package evidence appear only when persisted by the backend." />
      {data.audits.length === 0 && data.chiefAudits.length === 0 ? <EmptyState icon="review" title="No compliance evidence yet" message="Provider and Business Context are not configured, and no final artifact has completed the audited production chain." /> : <div className="producer-job-grid">
        {data.audits.map((audit) => <article className="producer-job" key={audit.id}><p className="eyebrow">Compliance</p><h3>{audit.status.replaceAll("_", " ")}</h3><p>Rights: {audit.rights_status.replaceAll("_", " ")} · Risk: {audit.risk_level}</p><p>Artifact hash: {audit.artifact_hash.slice(0, 16)}…</p></article>)}
        {data.chiefAudits.map((audit) => <article className="producer-job" key={audit.id}><p className="eyebrow">Chief Auditor</p><h3>{audit.status.replaceAll("_", " ")}</h3><p>Lineage: {audit.lineage_status} · Cost: {audit.cost_reconciliation_status}</p><p>{audit.blockers.length ? audit.blockers.join(" · ") : "No stored blockers"}</p></article>)}
      </div>}
    </section>
  </div>;
}

type ViewData =
  | DashboardData
  | ExecutiveMode
  | ActivityFeed
  | LiveLogs
  | ContentCommand
  | ReviewGate[]
  | PipelineMonitor
  | { monitor: WorkerMonitor; timeline: WorkerTimeline }
  | Customers
  | Leads
  | { summary: ResearchSummary; opportunities: Opportunity[] }
  | { summary: StrategySummary; briefs: StrategyBrief[]; opportunities: Opportunity[] }
  | { summary: ContentDepartmentSummary; packages: ContentPackage[]; briefs: StrategyBrief[] }
  | { summary: ProductionSummary; jobs: ProductionRun[]; packages: ContentPackage[] }
  | { summary: ComplianceSummary; audits: ComplianceAudit[]; chiefAudits: ChiefAudit[]; reviewPackages: HumanReviewPackage[] }
  | { insights: ExecutiveInsights; activity: ActivityFeed; github: GitHubOut }
  | { spend: SpendDashboard; cost: CostControl }
  | { health: SystemHealth; executive: ExecutiveDashboard }
  | null;

function isResearchViewData(value: ViewData): value is { summary: ResearchSummary; opportunities: Opportunity[] } {
  return Boolean(value && "summary" in value && "opportunities" in value && "research_data_state" in value.summary);
}

function isStrategyViewData(value: ViewData): value is { summary: StrategySummary; briefs: StrategyBrief[]; opportunities: Opportunity[] } {
  return Boolean(value && "summary" in value && "briefs" in value && "opportunities" in value && "business_context_state" in value.summary);
}

function isContentDepartmentViewData(value: ViewData): value is { summary: ContentDepartmentSummary; packages: ContentPackage[]; briefs: StrategyBrief[] } {
  return Boolean(value && "summary" in value && "packages" in value && "briefs" in value && "claims_unverified" in value.summary);
}

function isProducerViewData(value: ViewData): value is { summary: ProductionSummary; jobs: ProductionRun[]; packages: ContentPackage[] } {
  return Boolean(value && "summary" in value && "jobs" in value && "packages" in value && "production_jobs" in value.summary);
}

function isComplianceViewData(value: ViewData): value is { summary: ComplianceSummary; audits: ComplianceAudit[]; chiefAudits: ChiefAudit[]; reviewPackages: HumanReviewPackage[] } {
  return Boolean(value && "summary" in value && "audits" in value && "chiefAudits" in value && "reviewPackages" in value && "policy_state" in value.summary);
}

export default function LumoraDashboard({
  token,
  workspaceId,
  email,
  onWorkspaceChange,
  onSignOut,
}: Props) {
  const [nav, setNav] = useState<NavKey>("dashboard");
  const [missionTab, setMissionTab] = useState<MissionTab>("overview");
  const [data, setData] = useState<ViewData>(null);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [notifications, setNotifications] = useState<Notifications | null>(null);
  const [reviewCount, setReviewCount] = useState(0);
  const [health, setHealth] = useState<SystemHealth | null>(null);
  const [healthWorkspaceId, setHealthWorkspaceId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [notificationsError, setNotificationsError] = useState<string | null>(null);
  const [notificationsLoading, setNotificationsLoading] = useState(true);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [notificationOpen, setNotificationOpen] = useState(false);
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false);
  const [reviewBusy, setReviewBusy] = useState<string | null>(null);
  const [reviewActionError, setReviewActionError] = useState<string | null>(null);
  const requestId = useRef(0);
  const dataRef = useRef<ViewData>(null);
  const loadedKeyRef = useRef<string | null>(null);
  const loadInFlightRef = useRef(false);
  const mobileNavRef = useDialogFocus<HTMLElement>(mobileOpen, () => setMobileOpen(false));

  const currentWorkspace = workspaces.find((workspace) => workspace.id === workspaceId);
  const viewKey = `${workspaceId}:${nav}:${missionTab}`;

  useEffect(() => {
    dataRef.current = data;
    loadedKeyRef.current = loadedKey;
  }, [data, loadedKey]);

  useEffect(() => {
    let active = true;
    void listWorkspaces(token)
      .then((rows) => { if (active) setWorkspaces(rows); })
      .catch(() => { if (active) setWorkspaces([]); });
    return () => { active = false; };
  }, [token]);

  useEffect(() => {
    // In-flight flags are closures scoped to this effect run, not shared
    // refs: a workspace switch discards them entirely along with the rest
    // of this closure, so a hung request from the previous workspace can
    // never block the new workspace's own fetches or poll ticks.
    let active = true;
    let notificationsInFlight = false;
    let gatesInFlight = false;

    const refreshNotifications = () => {
      if (notificationsInFlight) return;
      notificationsInFlight = true;
      getNotifications(token, workspaceId)
        .then((value) => {
          if (!active) return;
          setNotifications(value);
          setNotificationsError(null);
        })
        .catch((cause) => {
          if (!active) return;
          setNotifications(null);
          setNotificationsError(cause instanceof Error ? cause.message : "Unable to refresh notifications.");
        })
        .finally(() => {
          notificationsInFlight = false;
          if (active) setNotificationsLoading(false);
        });
    };

    const refreshGates = () => {
      if (gatesInFlight) return;
      gatesInFlight = true;
      listReviewGates(token, workspaceId)
        .then((rows) => {
          if (!active) return;
          setReviewCount(rows.length);
          // Deliberately not cleared on failure: Human Review is a safety-critical
          // gate, so a stale-but-real count is safer than a false "0 waiting" that
          // could read as "nothing pending" when the refresh simply failed.
        })
        .catch(() => {})
        .finally(() => {
          gatesInFlight = false;
        });
    };

    setNotificationsLoading(true);
    refreshNotifications();
    refreshGates();
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        refreshNotifications();
        refreshGates();
      }
    }, AUTO_REFRESH_MS);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [token, workspaceId]);

  useEffect(() => {
    // Dashboard and Settings already fetch health as part of their atomic page
    // load. Other routes fetch it here for the persistent shell footer.
    if (nav === "dashboard" || nav === "settings") {
      setHealthError(null);
      return;
    }
    let active = true;
    let healthInFlight = false;
    const refreshShellHealth = async () => {
      if (healthInFlight) return;
      healthInFlight = true;
      try {
        const value = await getSystemHealth(token, workspaceId);
        if (active) {
          setHealth(value);
          setHealthWorkspaceId(workspaceId);
          setHealthError(null);
        }
      } catch (cause) {
        if (active) {
          setHealth(null);
          setHealthWorkspaceId(workspaceId);
          setHealthError(cause instanceof Error ? cause.message : "Unable to refresh system health.");
        }
      } finally {
        healthInFlight = false;
      }
    };
    void refreshShellHealth();
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        void refreshShellHealth();
      }
    }, AUTO_REFRESH_MS);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [nav, token, workspaceId]);

  const load = useCallback(async (options: { background?: boolean } = {}) => {
    const currentViewKey = `${workspaceId}:${nav}:${missionTab}`;
    const hasCurrentData = loadedKeyRef.current === currentViewKey && dataRef.current !== null;
    const currentRequest = requestId.current + 1;
    requestId.current = currentRequest;
    const isCurrent = () => requestId.current === currentRequest;
    loadInFlightRef.current = true;
    if (options.background && hasCurrentData) {
      setRefreshing(true);
    } else {
      setLoading(true);
      setError(null);
    }
    try {
      let next: ViewData;
      if (nav === "dashboard") {
        const [executive, pipelines, alerts, activity, health, customers, workers] = await Promise.all([
          getExecutiveDashboard(token, workspaceId),
          getPipelineMonitor(token, workspaceId),
          getOperationsAlerts(token, workspaceId),
          getActivityFeed(token, workspaceId),
          getSystemHealth(token, workspaceId),
          getCustomers(token, workspaceId),
          getWorkerMonitor(token, workspaceId),
        ]);
        next = { executive, pipelines, alerts, activity, health, customers, workers };
      } else if (nav === "ask") {
        next = null;
      } else if (nav === "mission") {
        if (missionTab === "overview") next = await getExecutiveMode(token, workspaceId);
        else if (missionTab === "timeline") next = await getUniversalTimeline(token, workspaceId);
        else if (missionTab === "logs") next = await getLiveLogs(token, workspaceId);
        else if (missionTab === "content") next = await getContentCommand(token, workspaceId);
        else next = null;
      } else if (nav === "review") {
        const [awaitingGates, approvedGates] = await Promise.all([
          listReviewGates(token, workspaceId, "awaiting"),
          listReviewGates(token, workspaceId, "approved", { limit: 50 }),
        ]);
        next = [...awaitingGates, ...approvedGates];
      }
      else if (nav === "pipelines") next = await getPipelineMonitor(token, workspaceId);
      else if (nav === "workers") {
        const [monitor, timeline] = await Promise.all([
          getWorkerMonitor(token, workspaceId),
          getWorkerTimeline(token, workspaceId),
        ]);
        next = { monitor, timeline };
      }
      else if (nav === "customers") next = await getCustomers(token, workspaceId);
      else if (nav === "leads") next = await getLeads(token, workspaceId);
      else if (nav === "research") {
        const [summary, opportunities] = await Promise.all([
          getResearchSummary(token, workspaceId),
          listOpportunities(token, workspaceId),
        ]);
        next = { summary, opportunities };
      } else if (nav === "strategy") {
        const [summary, briefs, opportunities] = await Promise.all([
          getStrategySummary(token, workspaceId),
          listStrategyBriefs(token, workspaceId),
          listOpportunities(token, workspaceId),
        ]);
        next = { summary, briefs, opportunities };
      } else if (nav === "content_department") {
        const [summary, packages, briefs] = await Promise.all([
          getContentDepartmentSummary(token, workspaceId),
          listContentPackages(token, workspaceId),
          listStrategyBriefs(token, workspaceId),
        ]);
        next = { summary, packages, briefs };
      } else if (nav === "producer") {
        const [summary, jobs, packages] = await Promise.all([
          getProductionSummary(token, workspaceId),
          listProductionRuns(token, workspaceId),
          listContentPackages(token, workspaceId),
        ]);
        next = { summary, jobs, packages };
      } else if (nav === "compliance") {
        const [summary, audits, chiefAudits, reviewPackages] = await Promise.all([
          getComplianceSummary(token, workspaceId),
          listComplianceAudits(token, workspaceId),
          listChiefAudits(token, workspaceId),
          listHumanReviewPackages(token, workspaceId),
        ]);
        next = { summary, audits, chiefAudits, reviewPackages };
      } else if (nav === "analytics") {
        const [insights, activity, github] = await Promise.all([
          getExecutiveInsights(token, workspaceId),
          getActivityFeed(token, workspaceId),
          getGitHubStatus(token, workspaceId),
        ]);
        next = { insights, activity, github };
      } else if (nav === "billing") {
        const [spend, cost] = await Promise.all([getSpendDashboard(token, workspaceId), getCostControl(token, workspaceId)]);
        next = { spend, cost };
      } else {
        const [healthData, executive] = await Promise.all([getSystemHealth(token, workspaceId), getExecutiveDashboard(token, workspaceId)]);
        next = { health: healthData, executive };
      }
      if (!isCurrent()) return;
      setData(next);
      setLoadedKey(currentViewKey);
      setRefreshError(null);
      if (nav === "dashboard" && next && "health" in next) {
        setHealth((next as DashboardData).health);
        setHealthWorkspaceId(workspaceId);
        setHealthError(null);
      } else if (nav === "settings" && next && "health" in next) {
        setHealth((next as { health: SystemHealth }).health);
        setHealthWorkspaceId(workspaceId);
        setHealthError(null);
      }
    } catch (cause) {
      if (!isCurrent()) return;
      const message = cause instanceof Error ? cause.message : "Unable to load this view";
      if (options.background && hasCurrentData) {
        setRefreshError(message);
      } else {
        setData(null);
        setLoadedKey(currentViewKey);
        setError(message);
      }
    } finally {
      if (isCurrent()) {
        setLoading(false);
        setRefreshing(false);
        loadInFlightRef.current = false;
      }
    }
  }, [nav, missionTab, token, workspaceId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (nav === "ask" || isMissionAssistant(nav, missionTab)) return;
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible" && !loadInFlightRef.current) {
        void load({ background: true });
      }
    }, AUTO_REFRESH_MS);
    return () => window.clearInterval(interval);
  }, [load, missionTab, nav]);

  const navigate = (next: NavKey) => {
    if (next !== nav) {
      requestId.current += 1;
      setData(null);
      setLoadedKey(null);
      setError(null);
      setRefreshError(null);
      setLoading(true);
      setMissionTab("overview");
    }
    setNav(next);
    setMobileOpen(false);
  };

  const changeMissionTab = (next: MissionTab) => {
    if (next !== missionTab) {
      requestId.current += 1;
      setData(null);
      setLoadedKey(null);
      setError(null);
      setRefreshError(null);
      setLoading(true);
      setMissionTab(next);
    }
  };

  const navigateToMissionTab = (next: MissionTab) => {
    requestId.current += 1;
    setData(null);
    setLoadedKey(null);
    setError(null);
    setRefreshError(null);
    setLoading(next !== "assistant");
    setNav("mission");
    setMissionTab(next);
    setMobileOpen(false);
  };

  const title = NAV.find((item) => item.id === nav)?.label ?? "Dashboard";
  const awaitingReviews = Array.isArray(data) ? data.filter((gate) => gate.status === "awaiting") : [];
  const approvedReviews = Array.isArray(data) ? data.filter((gate) => gate.status === "approved") : [];
  const notificationCount = notifications?.notifications.length ?? 0;
  const displayedHealth = healthWorkspaceId === workspaceId ? health : null;
  const healthLevel = aggregateHealth(displayedHealth);
  const healthStatusCopy = healthError
    ? "System status refresh failed."
    : displayedHealth
      ? `Updated ${relativeTime(displayedHealth.generated_at)}`
      : "Checking service status…";

  const decide = async (gate: ReviewGate, approved: boolean) => {
    if (approved && !gate.content_version_id) {
      setReviewActionError("This review gate has no content version on record and cannot be approved.");
      return;
    }
    setReviewBusy(gate.id);
    setReviewActionError(null);
    try {
      await decideReviewGate(token, workspaceId, gate.id, approved, gate.content_version_id);
      await load();
    } catch (cause) {
      setReviewActionError(cause instanceof Error ? cause.message : "Unable to save the review decision.");
    } finally {
      setReviewBusy(null);
    }
  };

  const editGateContent = async (
    gate: ReviewGate,
    edits: { script_hook?: string; script_body?: string; script_cta?: string },
  ) => {
    if (!gate.content_version_id) {
      throw new Error("This review gate has no content version on record; refresh and try again.");
    }
    setReviewBusy(gate.id);
    setReviewActionError(null);
    try {
      await editReviewGateContent(token, workspaceId, gate.id, gate.content_version_id, edits);
      // Background reload: a foreground load() sets `loading`, which
      // unmounts ReviewQueue via the `loading` guard in renderView() and
      // wipes its `selected`/`editing` state — the drawer must stay open
      // after a save so the Founder can review the edit and then Approve.
      await load({ background: true });
    } finally {
      setReviewBusy(null);
    }
  };

  const renderView = () => {
    if (error) return <ErrorState error={error} retry={() => void load()} />;

    // Review Queue drives its own list off the shared review fetch and always
    // renders (loading gate below only applies to data-backed views).
    if (nav === "review") {
      if (loading || loadedKey !== viewKey) return <Loading />;
      return (
        <>
          {reviewActionError ? <p className="error" role="alert">{reviewActionError}</p> : null}
          <ReviewQueue approvedGates={approvedReviews} busy={reviewBusy} gates={awaitingReviews} onDecision={decide} onEdit={editGateContent} workspaceName={currentWorkspace?.name ?? "Current workspace"} />
        </>
      );
    }

    // Ask My Business and the legacy assistant manage their own request lifecycle.
    if (nav === "ask") {
      return <AssistantPanel token={token} workspaceId={workspaceId} />;
    }
    if (nav === "mission" && missionTab === "assistant") {
      return <AssistantPanel token={token} workspaceId={workspaceId} />;
    }

    // Every other view is data-backed: show the loading gate until the fetch
    // for the *current* route resolves with a matching payload shape. This is
    // what prevents stale-data crashes on navigation.
    if (loading) return <Loading />;
    if (loadedKey !== viewKey) return <Loading />;

    if (nav === "dashboard") {
      if (isDashboardData(data)) {
        return (
          <DashboardHome
            data={data}
            navigate={navigate}
            onRefresh={() => void load({ background: true })}
            refreshing={refreshing}
            token={token}
            workspaceId={workspaceId}
          />
        );
      }
      return <Loading />;
    }
    if (nav === "mission") {
      if (missionTab === "overview") return isExecutiveMode(data) ? <ExecutiveModeView data={data} /> : <Loading />;
      if (missionTab === "timeline") return isActivityFeed(data) ? <UniversalTimelineView data={data} /> : <Loading />;
      if (missionTab === "logs") return isLiveLogs(data) ? <LiveLogsView initial={data} token={token} workspaceId={workspaceId} /> : <Loading />;
      if (missionTab === "content") return isContentCommand(data) ? <ContentCommandView data={data} /> : <Loading />;
      return <Loading />;
    }
    if (nav === "pipelines") {
      if (!isPipelineMonitor(data)) return <Loading />;
      if (data.pipelines.length === 0) {
        return (
          <div className="stack">
            <PipelinesView data={data} />
            <EmptyState icon="pipelines" title="No active pipelines" message="Pipeline runs will appear here once content jobs start moving through the system." />
          </div>
        );
      }
      return <PipelinesView data={data} />;
    }
    if (nav === "workers") {
      if (!isWorkersData(data)) return <Loading />;
      if (data.monitor.workers.length === 0) {
        return <EmptyState icon="workers" title="No workers registered" message="Connect a worker to this workspace to start processing jobs." />;
      }
      return (
        <div className="stack">
          <WorkersView data={data.monitor} />
          <section className="surface">
            <SectionHeader title="Worker timeline" detail="Execution history, failures and retry performance" />
            <WorkerTimelineView data={data.timeline} />
          </section>
        </div>
      );
    }
    if (nav === "customers") {
      if (!isCustomers(data)) return <Loading />;
      if (data.customers.length === 0) {
        return <EmptyState icon="customers" title="No customers yet" message="Customer workspaces will appear here as they sign up and subscribe." />;
      }
      return <CustomersView data={data} />;
    }
    if (nav === "leads") {
      if (!isLeads(data)) return <Loading />;
      return <LeadsView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "research") {
      if (!isResearchViewData(data)) return <Loading />;
      return <ResearchView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "strategy") {
      if (!isStrategyViewData(data)) return <Loading />;
      return <StrategyView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "content_department") {
      if (!isContentDepartmentViewData(data)) return <Loading />;
      return <ContentDepartmentView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "producer") {
      if (!isProducerViewData(data)) return <Loading />;
      return <ProducerView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "compliance") {
      if (!isComplianceViewData(data)) return <Loading />;
      return <ComplianceView data={data} refresh={() => void load()} token={token} workspaceId={workspaceId} />;
    }
    if (nav === "analytics") {
      if (!isAnalyticsData(data)) return <Loading />;
      return (
        <div className="stack">
          <InsightsView data={data.insights} />
          <section className="surface"><SectionHeader title="Activity stream" /><ActivityFeedView data={data.activity} /></section>
          <GitHubSummary data={data.github} />
        </div>
      );
    }
    if (nav === "billing") {
      if (!isBillingData(data)) return <Loading />;
      return <BillingView cost={data.cost} spend={data.spend} />;
    }
    if (nav === "settings") {
      if (!isSettingsData(data)) return <Loading />;
      return (
        <div className="settings-grid">
          <BusinessProfileSettings token={token} workspaceId={workspaceId} />
          <section className="surface"><SectionHeader title="System health" detail="Environment and service readiness" /><SystemHealthView data={data.health} /></section>
          <section className="surface deployment-card">
            <SectionHeader title="Deployment" detail="Current release metadata" />
            <dl>
              <div><dt>CI status</dt><dd><Status value={data.executive.deployment.ci_status} /></dd></div>
              <div><dt>Branch</dt><dd><code>{data.executive.deployment.git_branch ?? "Unavailable"}</code></dd></div>
              <div><dt>Commit</dt><dd><code>{data.executive.deployment.commit_sha?.slice(0, 12) ?? "Unavailable"}</code></dd></div>
              <div><dt>Deployed</dt><dd>{formatDate(data.executive.deployment.deployed_at)}</dd></div>
            </dl>
          </section>
        </div>
      );
    }
    return <Loading />;
  };

  return (
    <div className="lumora-shell">
      <CommandPalette navigate={(target) => {
        if (target === "logs") {
          navigateToMissionTab("logs");
          return;
        }
        const mapping: Record<string, NavKey> = {
          actions: "dashboard",
          executive: "dashboard",
          pipelines: "pipelines",
          workers: "workers",
          "worker-timeline": "workers",
          customers: "customers",
          leads: "leads",
          spend: "billing",
          alerts: "mission",
        };
        navigate(mapping[target] ?? "mission");
      }} token={token} workspaceId={workspaceId} />
      <aside
        aria-label={mobileOpen ? "Mobile navigation" : undefined}
        aria-modal={mobileOpen ? "true" : undefined}
        className={mobileOpen ? "lumora-sidebar lumora-sidebar--open" : "lumora-sidebar"}
        ref={mobileNavRef}
        role={mobileOpen ? "dialog" : undefined}
        tabIndex={-1}
      >
        <div className="brand">
          <BusinessManagerMark className="brand-mark" />
          <div className="brand-copy"><strong>The Business Manager</strong><small>Business Operating System</small></div>
          <button aria-label="Close navigation" className="mobile-close" onClick={() => setMobileOpen(false)} type="button"><Icon name="close" /></button>
        </div>
        <nav aria-label="Primary navigation">
          <p>Business</p>
          {NAV.slice(0, 3).map((item) => (
            <button className={nav === item.id ? "side-link side-link--active" : "side-link"} key={item.id} onClick={() => navigate(item.id)} type="button">
              <Icon name={item.icon} /><span>{item.label}</span>
            </button>
          ))}
          <p>Content &amp; workforce</p>
          {NAV.slice(3, 6).map((item) => (
            <button className={nav === item.id ? "side-link side-link--active" : "side-link"} key={item.id} onClick={() => navigate(item.id)} type="button">
              <Icon name={item.icon} /><span>{item.label}</span>
              {item.id === "review" && reviewCount > 0 ? <b>{reviewCount}</b> : null}
            </button>
          ))}
          <p>Money &amp; insights</p>
          {NAV.slice(6, 9).map((item) => (
            <button className={nav === item.id ? "side-link side-link--active" : "side-link"} key={item.id} onClick={() => navigate(item.id)} type="button">
              <Icon name={item.icon} /><span>{item.label}</span>
            </button>
          ))}
          <p>System</p>
          {NAV.slice(9).map((item) => (
            <button className={nav === item.id ? "side-link side-link--active" : "side-link"} key={item.id} onClick={() => navigate(item.id)} type="button">
              <Icon name={item.icon} /><span>{item.label}</span>
            </button>
          ))}
        </nav>
        <button
          className="sidebar-foot"
          onClick={() => navigate("settings")}
          title="View system health"
          type="button"
        >
          <span className={`status-orb status-orb--${HEALTH_COPY[healthLevel].orb}`} />
          <div>
            <strong>{HEALTH_COPY[healthLevel].label}</strong>
            <small>{healthStatusCopy}</small>
          </div>
        </button>
      </aside>
      {mobileOpen ? <button aria-label="Close navigation backdrop" className="mobile-backdrop" onClick={() => setMobileOpen(false)} type="button" /> : null}

      <div className="lumora-workspace">
        <header className="topbar">
          <div className="topbar-identity">
            <button aria-label="Open navigation" className="mobile-menu" onClick={() => setMobileOpen(true)} type="button"><Icon name="menu" /></button>
            <div className="mobile-brand" aria-label="The Business Manager">
              <BusinessManagerMark className="mobile-brand__mark" />
              <span><strong>The Business Manager</strong><small>Business Operating System</small></span>
            </div>
          </div>
          <div className="topbar-utilities">
            <label className="workspace-switcher">
              <span className="workspace-avatar">{(currentWorkspace?.name ?? "W").slice(0, 1).toUpperCase()}</span>
              <select aria-label="Workspace" onChange={(event) => onWorkspaceChange(event.target.value)} value={workspaceId}>
                {workspaces.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}
              </select>
            </label>
          <div className={mobileSearchOpen ? "topbar-search topbar-search--open" : "topbar-search"}>
            <Icon name="search" size={17} />
            <GlobalSearchBar
              onOpen={(type) => {
                setMobileSearchOpen(false);
                if (type === "log") {
                  navigateToMissionTab("logs");
                  return;
                }
                if (type === "content") {
                  navigateToMissionTab("content");
                  return;
                }
                const mapping: Record<string, NavKey> = { customer: "customers", lead: "leads", pipeline: "pipelines", worker: "workers", review: "review", job: "pipelines" };
                navigate(mapping[type] ?? "mission");
              }}
              token={token}
              workspaceId={workspaceId}
            />
          </div>
          <div className="topbar-actions">
            <button
              aria-label="Search"
              aria-expanded={mobileSearchOpen}
              className="icon-button mobile-search-btn"
              onClick={() => setMobileSearchOpen((value) => !value)}
              type="button"
            >
              <Icon name={mobileSearchOpen ? "close" : "search"} />
            </button>
            <div className="popover-anchor">
              <button aria-label="Notifications" className="icon-button" onClick={() => setNotificationOpen((value) => !value)} type="button">
                <Icon name="bell" />
                {notificationCount ? <i>{notificationCount}</i> : null}
              </button>
              {notificationOpen ? (
                <div className="top-popover notifications-popover">
                  <SectionHeader title="Notifications" />
                  {notificationsLoading ? <p>Loading notifications…</p> : null}
                  {notificationsError ? <p className="error" role="alert">Notifications unavailable: {notificationsError}</p> : null}
                  {!notificationsLoading && !notificationsError ? notifications?.notifications.slice(0, 4).map((item) => (
                    <button key={item.key} onClick={() => { setNotificationOpen(false); navigate("mission"); }} type="button">
                      <span className={`health-dot health-dot--${item.severity === "critical" ? "red" : "amber"}`} />
                      <span><strong>{item.title}</strong><small>{item.message}</small></span>
                    </button>
                  )) : null}
                  {!notificationsLoading && !notificationsError && !notificationCount ? <p>No active notifications.</p> : null}
                </div>
              ) : null}
            </div>
            <div className="popover-anchor">
              <button aria-label={`Open profile menu for ${email}`} className="profile-button" onClick={() => setProfileOpen((value) => !value)} type="button">
                <span>{email.slice(0, 1).toUpperCase()}</span>
                <div><strong>{email.split("@")[0]}</strong><small>{email}</small></div>
                <Icon name="chevron" size={14} />
              </button>
              {profileOpen ? (
                <div className="top-popover profile-popover">
                  <strong>{email}</strong>
                  <button onClick={() => navigate("settings")} type="button">Account settings</button>
                  <button onClick={onSignOut} type="button">Sign out</button>
                </div>
              ) : null}
            </div>
          </div>
          </div>
        </header>

        <main className="lumora-main">
          {nav !== "dashboard" ? (
            <header className="view-header">
              <div><p>The Business Manager / {title}</p><h1>{title}</h1></div>
              <button
                aria-label="Refresh data"
                className="icon-button refresh-button"
                disabled={loading || refreshing}
                onClick={() => void load({ background: true })}
                type="button"
              >
                <Icon name="refresh" />
              </button>
            </header>
          ) : null}
          {refreshError ? (
            <p className="error" role="alert">
              Live refresh failed: {refreshError}. Showing the last successful data.
            </p>
          ) : null}
          {nav === "mission" ? (
            <div className="view-tabs" role="tablist">
              {([
                ["overview", "Overview"],
                ["timeline", "Timeline"],
                ["logs", "Live logs"],
                ["assistant", "AI assistant"],
                ["content", "Content"],
              ] as Array<[MissionTab, string]>).map(([id, label]) => (
                <button
                  aria-controls={`mission-panel-${id}`}
                  aria-selected={missionTab === id}
                  className={missionTab === id ? "view-tab view-tab--active" : "view-tab"}
                  id={`mission-tab-${id}`}
                  key={id}
                  onClick={() => changeMissionTab(id)}
                  role="tab"
                  type="button"
                >
                  {label}
                </button>
              ))}
            </div>
          ) : null}
          <div
            aria-labelledby={nav === "mission" ? `mission-tab-${missionTab}` : undefined}
            id={nav === "mission" ? `mission-panel-${missionTab}` : undefined}
            role={nav === "mission" ? "tabpanel" : undefined}
          >
            <ErrorBoundary
              resetKeys={[nav, missionTab, workspaceId]}
              fallback={(boundaryError, reset) => (
                <ErrorState
                  error={boundaryError.message || "This screen ran into a problem."}
                  retry={() => { reset(); void load(); }}
                />
              )}
            >
              {renderView()}
            </ErrorBoundary>
          </div>
        </main>
      </div>
    </div>
  );
}

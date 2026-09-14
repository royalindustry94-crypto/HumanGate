// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import LumoraDashboard from "./LumoraDashboard";
import { QuickActionsView } from "./MissionControlPanels";

/**
 * Navigation + mobile smoke tests.
 *
 * These exercise the exact failure the audit flagged: navigating between
 * routes used to render a previous screen's data into the next view and crash
 * the whole shell (blank screen). They assert that every route renders safely
 * and that the health footer reflects real backend status.
 */

const executive = {
  workers_online: 0,
  workers_busy: 0,
  jobs_running: 3,
  jobs_queued: 0,
  jobs_failed: 1,
  human_reviews_waiting: 2,
  spend_today_usd: "0",
  spend_month_usd: "0",
  active_workspaces: 1,
  deployment: {
    ci_status: "success",
    ci_url: null,
    git_branch: "main",
    commit_sha: "abc123def456",
    deployed_at: "2026-08-07T00:00:00Z",
  },
  generated_at: "2026-08-07T00:00:00Z",
};

const pipelines = {
  active_pipelines: 0,
  queue_depth: 0,
  failed_pipelines: 0,
  retrying_pipelines: 0,
  dead_letter_queue: 0,
  review_gates: 0,
  publish_queue: 0,
  jobs_completed: 12,
  jobs_waiting: 0,
  jobs_failed: 0,
  human_reviews_waiting: 0,
  publishing_queue: 0,
  pipelines: [],
  generated_at: "2026-08-07T00:00:00Z",
};

const alerts = {
  alerts: [
    { key: "a1", severity: "critical", title: "Worker Offline", count: 8, message: "8 workers offline" },
    { key: "a2", severity: "warning", title: "Review Waiting", count: 2, message: "2 reviews waiting" },
  ],
  generated_at: "2026-08-07T00:00:00Z",
};

const health = {
  indicators: [
    { key: "api", label: "API Health", status: "green", detail: "ok" },
    { key: "worker", label: "Worker Health", status: "red", detail: "0/8 workers live" },
  ],
  generated_at: "2026-08-07T00:00:00Z",
};

const customers = {
  beta_users: 0,
  active_users: 0,
  paying_users: 0,
  trial_users: 0,
  revenue_mtd_usd: "0",
  revenue_source: "stripe",
  customers: [],
  generated_at: "2026-08-07T00:00:00Z",
};

const executiveMode = {
  health: health.indicators,
  revenue_mtd_usd: "0",
  spend_today_usd: "0",
  spend_month_usd: "0",
  workers_online: 0,
  workers_total: 8,
  jobs_running: 3,
  jobs_waiting: 0,
  jobs_failed_today: 1,
  critical_alerts: 1,
  reviews_waiting: 2,
  new_customers_today: 0,
  todays_summary: ["Everything nominal"],
  generated_at: "2026-08-07T00:00:00Z",
};

vi.mock("./api", () => ({
  getExecutiveDashboard: vi.fn(async () => executive),
  getContentProfile: vi.fn(async () => null),
  saveContentProfile: vi.fn(async () => ({})),
  getPipelineMonitor: vi.fn(async () => pipelines),
  getOperationsAlerts: vi.fn(async () => alerts),
  getActivityFeed: vi.fn(async () => ({ items: [], generated_at: "" })),
  getSystemHealth: vi.fn(async () => health),
  getCustomers: vi.fn(async () => customers),
  getExecutiveMode: vi.fn(async () => executiveMode),
  getUniversalTimeline: vi.fn(async () => ({ items: [], generated_at: "" })),
  getLiveLogs: vi.fn(async () => ({ logs: [], generated_at: "" })),
  getContentCommand: vi.fn(async () => ({
    ideas: 0, scripts: 0, voiceovers: 0, videos_rendering: 0, ready_for_review: 0,
    waiting_for_approval: 0, publishing: 0, published: 0, failed: 0, generated_at: "",
  })),
  getWorkerMonitor: vi.fn(async () => ({ workers: [], generated_at: "" })),
  getWorkerTimeline: vi.fn(async () => ({ workers: [], generated_at: "" })),
  getLeads: vi.fn(async () => ({ leads: [], total: 0, generated_at: "" })),
  getResearchSummary: vi.fn(async () => ({
    provider_state: "not_configured", status: "not_run", current_research: null, last_run: null,
    next_run_at: null, opportunities_found: 0, audited_opportunities: 0, blocked_findings: 0,
    cost_today_usd: "0", last_error: "RESEARCH PROVIDER NOT CONFIGURED", schedule_enabled: false,
    research_data_state: "not_connected",
  })),
  getStrategySummary: vi.fn(async () => ({
    provider_state: "not_configured", status: "not_run", current_strategy: null, last_run: null,
    next_run_at: null, opportunities_received: 0, briefs_created: 0, briefs_passed: 0,
    briefs_blocked: 0, cost_today_usd: "0", last_error: "STRATEGY PROVIDER NOT CONFIGURED",
    schedule_enabled: false, business_context_state: "incomplete", performance_data_state: "no_data",
  })),
  createStrategyRun: vi.fn(async () => ({})),
  listStrategyBriefs: vi.fn(async () => []),
  getStrategyBriefDetail: vi.fn(async () => ({})),
  auditStrategyBrief: vi.fn(async () => ({})),
  sendStrategyBriefToWriter: vi.fn(async () => ({})),
  getContentDepartmentSummary: vi.fn(async () => ({
    provider_state: "not_configured", status: "not_run", current_run: null, last_run: null,
    creative_directions: 0, packages_ready: 0, packages_blocked: 0, packages_in_progress: 0,
    claims_unverified: 0, cost_today_usd: "0", last_error: "CONTENT PROVIDER NOT CONFIGURED",
    schedule_enabled: false, business_context_state: "incomplete", performance_data_state: "no_data",
  })),
  createContentDepartmentRun: vi.fn(async () => ({})),
  listContentPackages: vi.fn(async () => []),
  getContentPackageDetail: vi.fn(async () => ({})),
  getProducerGate: vi.fn(async () => ({})),
  getProductionSummary: vi.fn(async () => ({
    provider_state: "not_configured", production_jobs: 0, active_jobs: 0, final_artifacts: 0,
    media_qa_passed: 0, media_qa_blocked: 0, repair_required: 0, compliance_ready: 0,
    provider_cost_usd: "0", last_error: "PRODUCTION PROVIDER NOT CONFIGURED",
    real_provider_mode: false, test_fixture_mode: true,
  })),
  createProductionRun: vi.fn(async () => ({})),
  listProductionRuns: vi.fn(async () => []),
  listOpportunities: vi.fn(async () => []),
  createResearchRun: vi.fn(async () => ({})),
  getOpportunityDetail: vi.fn(async () => ({})),
  auditOpportunity: vi.fn(async () => ({})),
  sendOpportunityToStrategist: vi.fn(async () => ({})),
  getSpendDashboard: vi.fn(async () => ({
    today_usd: "0", week_usd: "0", month_usd: "0", by_provider: [],
    daily_cap_usd: null, monthly_cap_usd: null, budget_remaining_daily_usd: null,
    budget_remaining_monthly_usd: null, generated_at: "",
  })),
  getCostControl: vi.fn(async () => ({
    daily_ai_spend_usd: "0", monthly_ai_spend_usd: "0", budget_remaining_daily_usd: null,
    budget_remaining_monthly_usd: null, by_provider: [], top_expensive_jobs: [],
    projected_month_end_usd: "0", generated_at: "",
  })),
  getExecutiveInsights: vi.fn(async () => ({
    todays_achievements: [], todays_failures: [], highest_risk: "None",
    suggested_next_action: "Keep going", biggest_cost_today_usd: "0",
    biggest_cost_today_label: null, most_active_worker: null, most_active_customer: null,
    generated_at: "",
  })),
  getGitHubStatus: vi.fn(async () => ({
    available: false, unavailable_reason: "n/a", repository: null, latest_commits: [],
    open_pull_requests: [], failed_actions: [], branch_status: { name: null, sha: null, protected: null, ci_status: "unknown" },
    generated_at: "",
  })),
  getNotifications: vi.fn(async () => ({ notifications: [], generated_at: "" })),
  listReviewGates: vi.fn(async () => []),
  listWorkspaces: vi.fn(async () => [{ id: "ws-1", name: "Lumora HQ" }]),
  decideReviewGate: vi.fn(async () => ({})),
  createLead: vi.fn(async () => ({})),
  updateLead: vi.fn(async () => ({})),
  globalSearch: vi.fn(async () => ({ query: "", results: [], total: 0, generated_at: "" })),
  askMissionAssistant: vi.fn(async () => ({ question: "", intent: "", answer: "", facts: [], generated_at: "" })),
  postMissionAction: vi.fn(async () => ({ action: "", ok: true, affected: 0, message: "", details: {} })),
  createContentJob: vi.fn(async () => ({})),
  createWorkspace: vi.fn(async () => ({ id: "ws-2", name: "New" })),
}));

function renderShell() {
  return render(
    <LumoraDashboard
      token="t"
      workspaceId="ws-1"
      email="founder@lumora.local"
      onWorkspaceChange={() => {}}
      onSignOut={() => {}}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("Mission Control destructive-action interlock", () => {
  it("requires a second explicit click before discarding dead-letter evidence", async () => {
    render(<QuickActionsView token="t" workspaceId="ws-1" />);
    const api = await import("./api");
    const clear = screen.getByRole("button", { name: "Clear Dead Letter Queue" });

    fireEvent.click(clear);
    expect(api.postMissionAction).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Confirm Clear Dead Letter Queue" })).toBeDefined();

    fireEvent.click(screen.getByRole("button", { name: "Confirm Clear Dead Letter Queue" }));
    await waitFor(() =>
      expect(api.postMissionAction).toHaveBeenCalledWith("t", "ws-1", "clear-dead-letter"),
    );
  });

  it("requires a second explicit click before emergency stop", async () => {
    render(<QuickActionsView token="t" workspaceId="ws-1" />);
    const api = await import("./api");
    const stop = screen.getByRole("button", { name: "Emergency Stop" });

    fireEvent.click(stop);
    expect(api.postMissionAction).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm Emergency Stop" }));
    await waitFor(() =>
      expect(api.postMissionAction).toHaveBeenCalledWith("t", "ws-1", "emergency-stop"),
    );
  });

  it("prevents duplicate requests when a quick action is clicked repeatedly", async () => {
    const api = await import("./api");
    let resolveAction!: (value: { action: string; ok: boolean; affected: number; message: string; details: Record<string, unknown> }) => void;
    (api.postMissionAction as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveAction = resolve;
      }),
    );

    render(<QuickActionsView token="t" workspaceId="ws-1" />);
    const pause = screen.getByRole("button", { name: "Pause Workers" });
    fireEvent.click(pause);
    fireEvent.click(pause);

    expect(api.postMissionAction).toHaveBeenCalledTimes(1);

    resolveAction({
      action: "pause-workers",
      ok: true,
      affected: 3,
      message: "Paused 3 workers.",
      details: {},
    });

    expect(await screen.findByText("Paused 3 workers.")).toBeDefined();
    expect(await screen.findByText("3 affected")).toBeDefined();
  });
});

describe("dashboard navigation smoke test", () => {
  it("loads Home with a truthful health footer (not a fake 'operational')", async () => {
    renderShell();
    expect(await screen.findByRole("heading", { name: "Home" })).toBeDefined();
    // Health footer must reflect the red worker indicator — never "operational".
    expect(await screen.findByText(/Service disruption detected/i)).toBeDefined();
    expect(screen.queryByText("All systems operational")).toBeNull();
  });

  it("shows real founder decisions without a synthetic alert-count metric", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    expect(await screen.findByText("What needs you now")).toBeDefined();
    expect(await screen.findByText("Worker Offline")).toBeDefined();
    expect(await screen.findByText("Review Waiting")).toBeDefined();
  });

  it("renders the requested primary navigation and summary from existing backend fields", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    for (const label of [
      "Home", "Ask", "Opportunities", "Content", "Human Review", "Workforce",
      "Money", "Insights", "Audience", "Connections", "Settings",
    ]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    for (const label of [
      "Bankroll", "Connect a financial source to see verified business performance.",
      "Revenue", "Spending", "Net profit", "Profit margin", "What needs you now", "What do you want sorted?", "AI Workforce",
    ]) {
      // AI Workforce intentionally appears in both primary navigation and the Home section.
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
    expect(screen.getAllByText("Not connected").length).toBe(4);
    expect(screen.getAllByText("Source-backed data required").length).toBe(4);
    expect(screen.queryByText(/\$0/)).toBeNull();
  });

  it("keeps the newest global-search response when an older request resolves late", async () => {
    const api = await import("./api");
    let resolveOld!: (value: unknown) => void;
    const oldRequest = new Promise((resolve) => { resolveOld = resolve; });
    (api.globalSearch as unknown as ReturnType<typeof vi.fn>).mockImplementation(
      (_token: string, _workspace: string, query: string) => query === "ab"
        ? oldRequest
        : Promise.resolve({
            query,
            total: 1,
            generated_at: "",
            results: [{ type: "lead", id: "new", title: "Newest result", subtitle: null, status: null, occurred_at: null, url: null }],
          }),
    );
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    const input = screen.getByRole("textbox", { name: /global search/i });

    fireEvent.change(input, { target: { value: "ab" } });
    await new Promise((resolve) => setTimeout(resolve, 300));
    fireEvent.change(input, { target: { value: "abc" } });
    expect(await screen.findByText("Newest result")).toBeDefined();

    resolveOld({
      query: "ab",
      total: 1,
      generated_at: "",
      results: [{ type: "lead", id: "old", title: "Stale result", subtitle: null, status: null, occurred_at: null, url: null }],
    });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByText("Stale result")).toBeNull();
    expect(screen.getByText("Newest result")).toBeDefined();
  });

  it("opens the keyboard command palette and closes it with Escape", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    expect(await screen.findByRole("dialog", { name: /command palette/i })).toBeDefined();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /command palette/i })).toBeNull());
  });

  it("routes command palette deep links to the intended destination", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const palette = await screen.findByRole("dialog", { name: /command palette/i });
    fireEvent.click(within(palette).getByRole("button", { name: /open logs/i }));
    expect(await screen.findByRole("tabpanel", { name: /live logs/i })).toBeDefined();
    expect(await screen.findByText(/No worker logs match/i)).toBeDefined();
  });

  it("loads every Connections view tab safely", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    const nav = screen.getByRole("navigation", { name: /primary navigation/i });
    fireEvent.click(within(nav).getByRole("button", { name: /^Connections$/i }));
    await screen.findByText(/Today's summary/i);

    const tabs: Array<[string, RegExp]> = [
      ["Timeline", /No timeline events recorded/i],
      ["Live logs", /No worker logs match/i],
      ["AI assistant", /Ask My Business/i],
      ["Content", /No content activity has been recorded/i],
      ["Overview", /Today's summary/i],
    ];
    for (const [label, expected] of tabs) {
      fireEvent.click(screen.getByRole("tab", { name: label }));
      expect(await screen.findByText(expected)).toBeDefined();
      expect(screen.queryByText(/The Business Manager hit an unexpected error/i)).toBeNull();
    }
  });

  it("navigates across every route without a blank-screen crash", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });

    const routes: Array<[string, RegExp]> = [
      ["Ask", /Ask My Business/i],
      ["Opportunities", /No opportunities yet/i],
      ["Content", /No active pipelines/i],
      ["Human Review", /keeps every publish decision/i],
      ["Workforce", /No workers registered/i],
      ["Money", /Cost control/i],
      ["Insights", /Engineering delivery/i],
      ["Audience", /No customers yet/i],
      ["Connections", /Today's summary/i],
      ["Settings", /Deployment/i],
      ["Home", /Connect a financial source to see verified business performance/i],
    ];

    for (const [label, expected] of routes) {
      const nav = screen.getByRole("navigation", { name: /primary navigation/i });
      fireEvent.click(within(nav).getByRole("button", { name: new RegExp(`^${label}$`, "i") }));
      await waitFor(() => expect(screen.getByText(expected)).toBeDefined());
      // The shell must survive: brand is always present, crash fallback is not.
      expect(screen.getAllByText("The Business Manager").length).toBeGreaterThan(0);
      expect(screen.queryByText(/The Business Manager hit an unexpected error/i)).toBeNull();
    }
  });

  it("surfaces a retryable error state instead of crashing when a route fails", async () => {
    const api = await import("./api");
    // Reject a route-only endpoint so the initial dashboard load still succeeds.
    (api.getResearchSummary as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("503: backend unavailable"),
    );
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    const nav = screen.getByRole("navigation", { name: /primary navigation/i });
    fireEvent.click(within(nav).getByRole("button", { name: /^Opportunities$/i }));
    expect(await screen.findByText(/We couldn’t load this view|We couldn't load this view/i)).toBeDefined();
    // Shell still intact.
    expect(screen.getAllByText("The Business Manager").length).toBeGreaterThan(0);
  });

  it("disables unavailable execution actions so the UI matches fail-closed backend state", async () => {
    const api = await import("./api");
    (api.listOpportunities as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: "opp-1",
        research_run_id: "run-1",
        title: "Opportunity",
        topic: "Evidence-backed planning",
        summary: "Summary",
        proposed_angle: "Angle",
        target_audience: "operators",
        target_platform: "short_video",
        suggested_format: "explainer",
        discovered_at: "2026-08-07T00:00:00Z",
        source_count: 2,
        confidence: "0.70",
        freshness: "fresh",
        risk: "low",
        status: "pending_audit",
        created_by_worker: "scout",
        component_scores: {},
        score_reasoning: {},
        strategist_state: "eligible",
        audit_gate_status: "pass",
        performance_data_state: "no_data",
        test_data: true,
      },
    ]);
    (api.listStrategyBriefs as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: "brief-1",
        strategy_run_id: "run-1",
        objective: "Objective",
        target_audience: "operators",
        target_platform: "short_video",
        content_format: "explainer",
        creative_angle: "Angle",
        core_message: "Message",
        hook_direction: "Hook",
        cta_direction: "CTA",
        business_goal: "Goal",
        success_metric: "Metric",
        commercial_goal: "Commercial",
        estimated_complexity: "low",
        risk_level: "low",
        evidence_summary: "Evidence",
        reasoning: "Reasoning",
        confidence: "0.60",
        priority: "medium_priority",
        component_scores: {},
        score_reasoning: {},
        recommended_length: null,
        recommended_posting_window: null,
        required_assets: [],
        production_requirements: [],
        rights_requirements: [],
        compliance_requirements: [],
        estimated_provider_usage: {},
        estimated_cost_range: {},
        cost_state: "known",
        capability_state: "configured",
        business_context_state: "complete",
        performance_data_state: "no_data",
        structural_fingerprint: "fp",
        repetition_state: "clear",
        repetition_reasons: [],
        audit_gate_status: "pass",
        writer_handoff_state: "eligible",
        created_by_worker: "writer",
        status: "pass",
        test_data: true,
      },
    ]);
    (api.listContentPackages as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: "pkg-1",
        content_department_run_id: "cdr-1",
        creative_direction_id: "dir-1",
        strategy_brief_id: "brief-1",
        content_item_id: "item-1",
        content_version_id: "ver-1",
        prior_content_version_id: null,
        revision_reason: null,
        writer_worker_id: "writer",
        provider: "not_configured",
        model: null,
        prompt_version: "writer-v1",
        input_references: {},
        package_fields: {},
        status: "writer_provider_not_configured",
        audit_gate_status: "pass",
        producer_handoff_state: "eligible",
        invalidated_at: null,
        test_data: true,
      },
    ]);

    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    const nav = screen.getByRole("navigation", { name: /primary navigation/i });

    fireEvent.click(within(nav).getByRole("button", { name: /^Opportunities$/i }));
    expect((await screen.findByRole("button", { name: "Run research" })).hasAttribute("disabled")).toBe(true);

    fireEvent.click(within(nav).getByRole("button", { name: /^Strategy$/i }));
    expect((await screen.findByRole("button", { name: "Record strategy request" })).hasAttribute("disabled")).toBe(true);

    fireEvent.click(within(nav).getByRole("button", { name: /^Content Department$/i }));
    expect((await screen.findByRole("button", { name: "Record content request" })).hasAttribute("disabled")).toBe(true);

    fireEvent.click(within(nav).getByRole("button", { name: /^Producer$/i }));
    expect((await screen.findByRole("button", { name: "Request production" })).hasAttribute("disabled")).toBe(true);
  });

  it("auto-refreshes the current dashboard view on an interval", async () => {
    const api = await import("./api");
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    const intervals: Array<TimerHandler> = [];
    const setIntervalSpy = vi.spyOn(window, "setInterval").mockImplementation(((handler: TimerHandler) => {
      intervals.push(handler);
      return intervals.length as unknown as number;
    }) as typeof window.setInterval);
    const clearIntervalSpy = vi.spyOn(window, "clearInterval").mockImplementation(() => {});

    renderShell();
    expect(await screen.findByRole("heading", { name: "Home" })).toBeDefined();
    expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(1);

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }

    await waitFor(() => expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(2));
    setIntervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();
  });

  it("skips a polling tick while the previous background refresh is still in flight", async () => {
    const api = await import("./api");
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    const intervals: Array<TimerHandler> = [];
    const setIntervalSpy = vi.spyOn(window, "setInterval").mockImplementation(((handler: TimerHandler) => {
      intervals.push(handler);
      return intervals.length as unknown as number;
    }) as typeof window.setInterval);
    const clearIntervalSpy = vi.spyOn(window, "clearInterval").mockImplementation(() => {});

    renderShell();
    expect(await screen.findByRole("heading", { name: "Home" })).toBeDefined();
    expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(1);

    let resolveSlowLoad: (() => void) | undefined;
    (api.getExecutiveDashboard as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(
      () => new Promise((resolve) => { resolveSlowLoad = () => resolve(executive); }),
    );

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }
    await waitFor(() => expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(2));

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }
    expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(2);

    resolveSlowLoad?.();
    await waitFor(() => expect(screen.getByRole("heading", { name: "Home" })).toBeDefined());

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }
    await waitFor(() => expect(api.getExecutiveDashboard).toHaveBeenCalledTimes(3));

    setIntervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();
  });

  it("shows a visible stale-data warning when a background refresh fails", async () => {
    const api = await import("./api");
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    const intervals: Array<TimerHandler> = [];
    const setIntervalSpy = vi.spyOn(window, "setInterval").mockImplementation(((handler: TimerHandler) => {
      intervals.push(handler);
      return intervals.length as unknown as number;
    }) as typeof window.setInterval);
    const clearIntervalSpy = vi.spyOn(window, "clearInterval").mockImplementation(() => {});

    renderShell();
    expect(await screen.findByRole("heading", { name: "Home" })).toBeDefined();
    (api.getExecutiveDashboard as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("503: backend unavailable"),
    );

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }

    expect(await screen.findByText(/Live refresh failed: 503: backend unavailable/i)).toBeDefined();
    expect(screen.getByText(/Connect a financial source to see verified business performance/i)).toBeDefined();
    setIntervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();
  });

  it("clears the notification badge instead of showing a stale count when a refresh fails", async () => {
    const api = await import("./api");
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    const intervals: Array<TimerHandler> = [];
    const setIntervalSpy = vi.spyOn(window, "setInterval").mockImplementation(((handler: TimerHandler) => {
      intervals.push(handler);
      return intervals.length as unknown as number;
    }) as typeof window.setInterval);
    const clearIntervalSpy = vi.spyOn(window, "clearInterval").mockImplementation(() => {});

    (api.getNotifications as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      notifications: [{ key: "n1", title: "Job failed", message: "Retry needed", severity: "critical", count: 1 }],
      generated_at: "2026-09-09T00:00:00Z",
    });

    renderShell();
    expect(await screen.findByRole("heading", { name: "Home" })).toBeDefined();
    await waitFor(() => expect(screen.getByLabelText("Notifications").textContent).toContain("1"));

    (api.getNotifications as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error("503: backend unavailable"),
    );

    for (const handler of intervals) {
      if (typeof handler === "function") handler();
    }

    await waitFor(() => expect(screen.getByLabelText("Notifications").textContent).not.toContain("1"));
    setIntervalSpy.mockRestore();
    clearIntervalSpy.mockRestore();
  });

  it("ignores a late response from a route that is no longer active", async () => {
    const api = await import("./api");
    renderShell();
    await screen.findByRole("heading", { name: "Home" });

    let resolvePipeline!: (value: typeof pipelines) => void;
    const delayedPipeline = new Promise<typeof pipelines>((resolve) => {
      resolvePipeline = resolve;
    });
    (api.getPipelineMonitor as unknown as ReturnType<typeof vi.fn>)
      .mockImplementationOnce(() => delayedPipeline);

    const nav = screen.getByRole("navigation", { name: /primary navigation/i });

    fireEvent.click(within(nav).getByRole("button", { name: /^Content$/i }));
    fireEvent.click(within(nav).getByRole("button", { name: /^Workforce$/i }));
    expect(await screen.findByText(/No workers registered/i)).toBeDefined();

    resolvePipeline(pipelines);
    await waitFor(() => expect(screen.getByText(/No workers registered/i)).toBeDefined());
    expect(screen.queryByText(/No active pipelines/i)).toBeNull();
    expect(screen.queryByText(/The Business Manager hit an unexpected error/i)).toBeNull();
  });
});

describe("mobile smoke test", () => {
  it("opens the mobile navigation drawer and reveals a search affordance", async () => {
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    // Mobile menu + search buttons exist for small screens.
    expect(screen.getByRole("button", { name: /open navigation/i })).toBeDefined();
    const searchToggle = screen.getByRole("button", { name: /^Search$/i });
    fireEvent.click(searchToggle);
    expect(searchToggle.getAttribute("aria-expanded")).toBe("true");
  });
});

describe("business setup wizard", () => {
  it("shows the quick-setup banner for a workspace with no saved profile", async () => {
    renderShell();
    expect(await screen.findByText("Get your business set up")).toBeDefined();
    expect(screen.getByRole("button", { name: "Quick Setup" })).toBeDefined();
    expect(screen.getByRole("button", { name: "Set up manually" })).toBeDefined();
  });

  it("shows a 'finish setup' banner instead when a partial profile exists", async () => {
    const api = await import("./api");
    (api.getContentProfile as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      workspace_id: "ws-1",
      business_name: "Acme Studio",
      offer: null,
      target_audience: null,
      brand_voice: null,
      target_platform: null,
      content_goal: null,
      updated_at: "2026-09-09T00:00:00Z",
      is_complete: false,
    });
    renderShell();
    expect(await screen.findByText("Finish setting up your business")).toBeDefined();
    expect(screen.getByRole("button", { name: "Finish setup" })).toBeDefined();
  });

  it("hides the banner once the profile is complete", async () => {
    const api = await import("./api");
    (api.getContentProfile as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      workspace_id: "ws-1",
      business_name: "Acme Studio",
      offer: "Video production",
      target_audience: "Restaurant owners",
      brand_voice: "Warm and direct",
      target_platform: "instagram",
      content_goal: "book more tastings",
      updated_at: "2026-09-09T00:00:00Z",
      is_complete: true,
    });
    renderShell();
    await screen.findByRole("heading", { name: "Home" });
    expect(screen.queryByText("Get your business set up")).toBeNull();
    expect(screen.queryByText("Finish setting up your business")).toBeNull();
  });

  it("walks the four steps and saves the full payload on finish", async () => {
    const api = await import("./api");
    renderShell();

    fireEvent.click(await screen.findByRole("button", { name: "Quick Setup" }));
    expect(await screen.findByRole("heading", { name: "Your business" })).toBeDefined();

    fireEvent.change(screen.getByLabelText("Business name"), { target: { value: "Acme Studio" } });
    fireEvent.change(screen.getByLabelText("What do you offer?"), {
      target: { value: "Short-form video for restaurants" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByRole("heading", { name: "Who you're for" })).toBeDefined();
    fireEvent.change(screen.getByLabelText("Who is this content for?"), {
      target: { value: "Restaurant owners in mid-size US cities" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByRole("heading", { name: "Brand voice" })).toBeDefined();
    fireEvent.change(screen.getByLabelText("How should your content sound?"), {
      target: { value: "Warm, direct, a little playful" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByRole("heading", { name: "Content plan" })).toBeDefined();
    fireEvent.change(screen.getByLabelText("Primary platform"), { target: { value: "instagram" } });
    fireEvent.change(screen.getByLabelText("What's the goal?"), {
      target: { value: "book more tastings" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Finish setup" }));

    await waitFor(() =>
      expect(api.saveContentProfile).toHaveBeenCalledWith("t", "ws-1", {
        business_name: "Acme Studio",
        offer: "Short-form video for restaurants",
        target_audience: "Restaurant owners in mid-size US cities",
        brand_voice: "Warm, direct, a little playful",
        target_platform: "instagram",
        content_goal: "book more tastings",
      }),
    );
  });

  it("closes without saving when Skip for now is clicked on the first step", async () => {
    const api = await import("./api");
    renderShell();

    fireEvent.click(await screen.findByRole("button", { name: "Quick Setup" }));
    await screen.findByRole("heading", { name: "Your business" });
    fireEvent.click(screen.getByRole("button", { name: "Skip for now" }));

    await waitFor(() => expect(screen.queryByRole("heading", { name: "Your business" })).toBeNull());
    expect(api.saveContentProfile).not.toHaveBeenCalled();
  });
});

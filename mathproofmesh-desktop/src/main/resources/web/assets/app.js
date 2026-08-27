"use strict";

const state = {
  bootstrap: null,
  selectedRunId: null,
  activeRunId: null,
  activeLifecycle: null,
  eventSource: null,
  elapsedTimer: null,
  elapsedStartedAt: null,
  activityCount: 0,
  activityItems: new Map(),
  contextRunId: null,
  contextTopologyNode: null,
  workspaceView: "detail",
  topology: null,
  reasoningSource: null,
  reasoningRunId: null,
  reasoningTaskId: null,
  reasoningNodeKey: null,
  reasoningCalls: new Map(),
  reasoningSeenRecords: new Set(),
  reasoningCharacters: 0,
  reasoningRequestToken: 0,
  reasoningResize: null,
  inspectorMode: null,
  computationRefreshTimer: null,
  clarificationRequest: null,
  clarificationSelectedIndex: null,
};

const elements = {};

window.__mathproofmeshContextMenuAt = (clientX, clientY) => {
  const target = document.elementFromPoint(clientX, clientY);
  if (!target) {
    return false;
  }
  target.dispatchEvent(
    new MouseEvent("contextmenu", {
      bubbles: true,
      cancelable: true,
      clientX,
      clientY,
      button: 2,
      buttons: 2,
      view: window,
    }),
  );
  return true;
};

const lifecycleLabels = {
  queued: "排队中",
  running: "运行中",
  awaiting_confirmation: "等待题意确认",
  completed: "已完成",
  partial: "部分完成",
  incomplete: "未完成",
  failed: "失败",
  cancelled: "已停止",
  interrupted: "可恢复",
};

const statusLabels = {
  verified: "已验证",
  unverified: "未验证",
  budget_exhausted: "预算耗尽",
  paused_external_failure: "外部中断",
  failed: "失败",
  completed: "已完成",
  network_interrupted: "网络中断",
  inconclusive: "未定",
  refuted: "已否证",
};

document.addEventListener("DOMContentLoaded", () => {
  cacheElements();
  initializeTopology();
  bindEvents();
  bootstrapApplication();
});

function cacheElements() {
  const ids = [
    "app",
    "version-label",
    "service-status",
    "open-data-button",
    "settings-button",
    "new-run-button",
    "refresh-runs-button",
    "run-list",
    "workspace",
    "workspace-title",
    "workspace-subtitle",
    "workspace-view-switcher",
    "detail-view-button",
    "topology-view-button",
    "workspace-detail-view",
    "topology-view",
    "topology-viewport",
    "topology-scene",
    "topology-edges",
    "topology-nodes",
    "topology-empty",
    "topology-summary",
    "topology-follow-toggle",
    "topology-zoom-out-button",
    "topology-zoom-in-button",
    "topology-fit-button",
    "topology-selection",
    "topology-selection-title",
    "topology-selection-detail",
    "topology-locate-button",
    "topology-body",
    "reasoning-dock",
    "reasoning-resizer",
    "reasoning-expand-button",
    "reasoning-collapse-button",
    "reasoning-close-button",
    "reasoning-options",
    "reasoning-follow-toggle",
    "reasoning-node-title",
    "reasoning-node-meta",
    "reasoning-status",
    "reasoning-authority",
    "reasoning-character-count",
    "reasoning-content",
    "resume-button",
    "cancel-button",
    "open-run-button",
    "editor-panel",
    "profile-budget",
    "profile-select",
    "run-id-input",
    "problem-input",
    "problem-count",
    "problem-file-input",
    "start-button",
    "current-stage",
    "elapsed-time",
    "progress-bar",
    "activity-list",
    "answer-view",
    "report-view",
    "problem-view",
    "run-status-badge",
    "run-status-detail",
    "metric-calls",
    "metric-tokens",
    "metric-cost",
    "metric-profile",
    "key-health",
    "docker-health",
    "clarification-dialog",
    "clarification-original",
    "clarification-reasons",
    "clarification-confidence",
    "clarification-candidates",
    "clarification-statement",
    "cancel-clarification-button",
    "confirm-clarification-button",
    "settings-dialog",
    "close-settings-button",
    "settings-profile-select",
    "sandbox-toggle",
    "docker-setting-note",
    "credential-summary",
    "probe-button",
    "credential-fields",
    "remember-toggle",
    "probe-results",
    "data-root-value",
    "settings-open-data-button",
    "clear-credentials-button",
    "save-settings-button",
    "run-context-menu",
    "delete-run-menu-button",
    "topology-context-menu",
    "show-reasoning-menu-button",
    "show-computation-menu-button",
    "toast-region",
  ];
  for (const id of ids) {
    elements[id] = document.getElementById(id);
  }
}

function initializeTopology() {
  state.topology = new window.MathProofMeshTopology({
    viewport: elements["topology-viewport"],
    scene: elements["topology-scene"],
    edgeLayer: elements["topology-edges"],
    nodeLayer: elements["topology-nodes"],
    empty: elements["topology-empty"],
    summary: elements["topology-summary"],
    selection: elements["topology-selection"],
    selectionTitle: elements["topology-selection-title"],
    selectionDetail: elements["topology-selection-detail"],
    locateButton: elements["topology-locate-button"],
    onLocate: locateActivityInTimeline,
    onContextMenu: openTopologyContextMenu,
  });
}

function bindEvents() {
  elements["problem-input"].addEventListener("input", updateProblemCount);
  elements["problem-file-input"].addEventListener("change", importProblemFile);
  elements["profile-select"].addEventListener("change", updateProfileSummary);
  elements["start-button"].addEventListener("click", startRun);
  elements["cancel-button"].addEventListener("click", cancelRun);
  elements["resume-button"].addEventListener("click", resumeRun);
  elements["open-run-button"].addEventListener("click", () => openPath("run"));
  elements["open-data-button"].addEventListener("click", () => openPath("data"));
  elements["settings-open-data-button"].addEventListener("click", () => openPath("data"));
  elements["new-run-button"].addEventListener("click", showNewRun);
  elements["refresh-runs-button"].addEventListener("click", refreshRuns);
  elements["settings-button"].addEventListener("click", openSettings);
  elements["save-settings-button"].addEventListener("click", saveSettings);
  elements["clear-credentials-button"].addEventListener("click", clearCredentials);
  elements["probe-button"].addEventListener("click", probeCredentials);
  elements["confirm-clarification-button"].addEventListener(
    "click",
    confirmGoalClarification,
  );
  elements["cancel-clarification-button"].addEventListener(
    "click",
    cancelGoalClarification,
  );
  elements["clarification-statement"].addEventListener(
    "input",
    trackCustomClarification,
  );
  elements["clarification-dialog"].addEventListener("cancel", (event) => {
    event.preventDefault();
  });
  elements["delete-run-menu-button"].addEventListener("click", deleteContextRun);
  elements["show-reasoning-menu-button"].addEventListener(
    "click",
    showContextNodeReasoning,
  );
  elements["show-computation-menu-button"].addEventListener(
    "click",
    showContextNodeComputation,
  );
  elements["detail-view-button"].addEventListener("click", () => {
    setWorkspaceView("detail");
  });
  elements["topology-view-button"].addEventListener("click", () => {
    setWorkspaceView("topology");
  });
  elements["topology-follow-toggle"].addEventListener("change", () => {
    state.topology.setAutoFollow(elements["topology-follow-toggle"].checked);
  });
  elements["topology-zoom-out-button"].addEventListener("click", () => {
    state.topology.zoomBy(0.82);
  });
  elements["topology-zoom-in-button"].addEventListener("click", () => {
    state.topology.zoomBy(1.22);
  });
  elements["topology-fit-button"].addEventListener("click", () => {
    state.topology.fit();
  });
  elements["reasoning-collapse-button"].addEventListener(
    "click",
    collapseReasoningPanel,
  );
  elements["reasoning-expand-button"].addEventListener(
    "click",
    expandReasoningPanel,
  );
  elements["reasoning-close-button"].addEventListener(
    "click",
    closeReasoningPanel,
  );
  bindReasoningResizer();
  document.addEventListener("click", () => {
    closeRunContextMenu();
    closeTopologyContextMenu();
  });
  document.addEventListener("contextmenu", (event) => {
    if (!event.target.closest(".run-item")) {
      closeRunContextMenu();
    }
    if (!event.target.closest(".topology-node-card")) {
      closeTopologyContextMenu();
    }
  });
  window.addEventListener("blur", () => {
    closeRunContextMenu();
    closeTopologyContextMenu();
  });
  window.addEventListener("resize", () => {
    closeRunContextMenu();
    closeTopologyContextMenu();
    if (state.workspaceView === "topology") {
      elements["topology-body"].style.removeProperty("--reasoning-pane-size");
      refitTopologyAfterDockChange();
    }
  });
  elements["run-list"].addEventListener("scroll", closeRunContextMenu);
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => selectTab(tab.dataset.tab));
  });
}

async function bootstrapApplication() {
  try {
    const data = await api("/api/bootstrap");
    state.bootstrap = data;
    elements["version-label"].textContent = `Desktop ${data.version}`;
    elements["data-root-value"].textContent = data.data_root;
    populateProfiles(data.profiles);
    populateCredentialFields(data.credential_status);
    applySettingsToDialog(data.settings);
    updateEnvironmentHealth();
    renderRunList(data.runs);
    setServiceStatus("connected", "本地服务已连接");
    elements.app.setAttribute("aria-busy", "false");
    if (data.active_run) {
      attachActiveRun(data.active_run);
    } else {
      showNewRun();
    }
  } catch (error) {
    setServiceStatus("error", "本地服务不可用");
    showToast(error.message, true);
  }
}

async function api(path, options = {}) {
  const request = {
    method: options.method || "GET",
    headers: { ...(options.headers || {}) },
    credentials: "same-origin",
  };
  if (options.body !== undefined) {
    request.headers["Content-Type"] = "application/json";
    request.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, request);
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const payload = await response.json();
      if (payload.detail) {
        detail = payload.detail;
      }
    } catch {
      // Keep the HTTP status when a response is not JSON.
    }
    throw new Error(detail);
  }
  return response.json();
}

function populateProfiles(profiles) {
  const selects = [elements["profile-select"], elements["settings-profile-select"]];
  for (const select of selects) {
    select.replaceChildren();
    for (const profile of profiles) {
      const option = document.createElement("option");
      option.value = profile.id;
      option.textContent = profile.label;
      select.append(option);
    }
  }
  const selected = state.bootstrap.settings.selected_profile;
  elements["profile-select"].value = selected;
  elements["settings-profile-select"].value = selected;
  updateProfileSummary();
}

function updateProfileSummary() {
  if (!state.bootstrap) {
    return;
  }
  const profileId = elements["profile-select"].value;
  const profile = state.bootstrap.profiles.find((item) => item.id === profileId);
  if (!profile) {
    return;
  }
  const tokenText = profile.max_tokens
    ? `${formatCompact(profile.max_tokens)} tokens`
    : "未限制 tokens";
  const costText = profile.max_cost_usd
    ? `上限 $${Number(profile.max_cost_usd).toFixed(2)}`
    : "未设置费用上限";
  elements["profile-budget"].textContent =
    `${profile.max_calls} 次调用 · ${tokenText} · ${costText}`;
  elements["metric-profile"].textContent = profile.label;
}

function renderRunList(runs) {
  elements["run-list"].replaceChildren();
  if (!runs.length) {
    const empty = document.createElement("div");
    empty.className = "empty-list";
    empty.textContent = "尚无运行记录";
    elements["run-list"].append(empty);
    return;
  }
  for (const run of runs) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "run-item";
    button.dataset.runId = run.run_id;
    if (run.run_id === state.selectedRunId) {
      button.classList.add("active");
    }

    const title = document.createElement("div");
    title.className = "run-item-title";
    title.textContent = run.title;

    const meta = document.createElement("div");
    meta.className = "run-item-meta";
    const runState = document.createElement("span");
    runState.className = `run-state ${run.lifecycle}`;
    runState.textContent = lifecycleLabels[run.lifecycle] || run.lifecycle;
    const time = document.createElement("span");
    time.textContent = formatShortDate(run.updated_at);
    meta.append(runState, time);
    button.append(title, meta);
    button.addEventListener("click", () => loadRun(run.run_id));
    button.addEventListener("contextmenu", (event) => {
      openRunContextMenu(event, run);
    });
    elements["run-list"].append(button);
  }
}

async function refreshRuns() {
  try {
    const data = await api("/api/runs");
    state.bootstrap.runs = data.runs;
    renderRunList(data.runs);
  } catch (error) {
    showToast(error.message, true);
  }
}

function openRunContextMenu(event, run) {
  event.preventDefault();
  event.stopPropagation();
  state.contextRunId = run.run_id;
  const menu = elements["run-context-menu"];
  const deleteButton = elements["delete-run-menu-button"];
  const isActive =
    run.run_id === state.activeRunId ||
    ["queued", "running"].includes(run.lifecycle);
  deleteButton.disabled = isActive;
  deleteButton.title = isActive ? "正在运行的任务不能删除" : "";
  menu.classList.remove("hidden");

  const padding = 8;
  const bounds = menu.getBoundingClientRect();
  const left = Math.max(
    padding,
    Math.min(event.clientX, window.innerWidth - bounds.width - padding),
  );
  const top = Math.max(
    padding,
    Math.min(event.clientY, window.innerHeight - bounds.height - padding),
  );
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  deleteButton.focus();
}

function closeRunContextMenu() {
  elements["run-context-menu"].classList.add("hidden");
  state.contextRunId = null;
}

async function deleteContextRun(event) {
  event.preventDefault();
  event.stopPropagation();
  const runId = state.contextRunId;
  const run = state.bootstrap?.runs.find((item) => item.run_id === runId);
  elements["run-context-menu"].classList.add("hidden");
  state.contextRunId = null;
  if (!runId) {
    return;
  }
  if (runId === state.activeRunId) {
    showToast("正在运行的任务不能删除，请先停止任务", true);
    return;
  }
  const title = run?.title || runId;
  const confirmed = window.confirm(
    `将“${title}”删除到 Windows 回收站？`,
  );
  if (!confirmed) {
    return;
  }
  try {
    const data = await api(`/api/runs/${encodeURIComponent(runId)}`, {
      method: "DELETE",
    });
    state.bootstrap.runs = data.runs;
    if (state.selectedRunId === runId) {
      if (state.activeRunId) {
        await loadRun(state.activeRunId);
      } else {
        showNewRun();
      }
    } else {
      renderRunList(data.runs);
    }
    showToast("运行记录已移至 Windows 回收站");
  } catch (error) {
    showToast(error.message, true);
  }
}

function openTopologyContextMenu(payload) {
  closeRunContextMenu();
  state.contextTopologyNode = payload;
  const menu = elements["topology-context-menu"];
  const computation = isComputationActivity(payload.event || {});
  const reasoningButton = elements["show-reasoning-menu-button"];
  const computationButton = elements["show-computation-menu-button"];
  reasoningButton.classList.toggle("hidden", computation);
  computationButton.classList.toggle("hidden", !computation);
  menu.classList.remove("hidden");
  positionContextMenu(menu, payload.clientX, payload.clientY);
  (computation ? computationButton : reasoningButton).focus();
}

function closeTopologyContextMenu() {
  elements["topology-context-menu"].classList.add("hidden");
  state.contextTopologyNode = null;
}

function positionContextMenu(menu, clientX, clientY) {
  const padding = 8;
  const bounds = menu.getBoundingClientRect();
  const left = Math.max(
    padding,
    Math.min(clientX, window.innerWidth - bounds.width - padding),
  );
  const top = Math.max(
    padding,
    Math.min(clientY, window.innerHeight - bounds.height - padding),
  );
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
}

function showContextNodeReasoning(event) {
  event.preventDefault();
  event.stopPropagation();
  const node = state.contextTopologyNode;
  closeTopologyContextMenu();
  if (node) {
    openReasoningForNode(node);
  }
}

function showContextNodeComputation(event) {
  event.preventDefault();
  event.stopPropagation();
  const node = state.contextTopologyNode;
  closeTopologyContextMenu();
  if (node) {
    openComputationForNode(node);
  }
}

function isComputationActivity(activity) {
  const taskId = String(activity.task_id || "");
  const initialType = String(
    activity.initial_event_type || activity.event_type || "",
  );
  return (
    taskId.startsWith("computation:") ||
    [
      "python_experiment",
      "computation_experiment",
      "computation_decision",
      "experiment_completed",
    ].includes(initialType)
  );
}

async function openReasoningForNode(node) {
  const runId = state.selectedRunId || state.activeRunId;
  const reasoningNode = resolveReasoningNode(node);
  const taskId = String(reasoningNode.event?.task_id || "");
  if (!runId || !taskId) {
    showToast("该节点没有可查询的任务标识", true);
    return;
  }

  const requestToken = state.reasoningRequestToken + 1;
  state.reasoningRequestToken = requestToken;
  closeReasoningStream();
  clearComputationRefresh();
  state.reasoningRunId = runId;
  state.reasoningTaskId = taskId;
  state.reasoningNodeKey = reasoningNode.key;
  state.inspectorMode = "reasoning";
  state.reasoningCalls.clear();
  state.reasoningSeenRecords.clear();
  state.reasoningCharacters = 0;

  const body = elements["topology-body"];
  body.classList.add("reasoning-open");
  body.classList.remove("reasoning-collapsed");
  elements["reasoning-node-title"].textContent =
    reasoningNode.event?.title ||
    reasoningNode.event?.event_type ||
    "节点思维链";
  elements["reasoning-expand-button"].textContent = "CoT";
  elements["reasoning-expand-button"].title = "展开思维链";
  elements["reasoning-options"].classList.remove("hidden");
  elements["reasoning-authority"].classList.remove("hidden");
  elements["reasoning-authority"].textContent = "未验证推理";
  elements["reasoning-authority"].title =
    "模型原始推理，不是检查点、Broker Fact 或独立验证结论";
  elements["reasoning-node-meta"].textContent = [
    reasoningNode.event?.agent_id,
    reasoningNode.event?.stage,
  ].filter(Boolean).join(" · ");
  setReasoningStatus("正在读取", "waiting");
  updateReasoningCharacterCount();
  showReasoningEmpty("正在读取该节点的模型推理记录...");
  refitTopologyAfterDockChange();

  try {
    const snapshot = await api(
      `/api/runs/${encodeURIComponent(runId)}/nodes/` +
        `${encodeURIComponent(taskId)}/reasoning`,
    );
    if (requestToken !== state.reasoningRequestToken) {
      return;
    }
    renderReasoningSnapshot(snapshot);
    if (snapshot.recordable && snapshot.run_active) {
      connectReasoningStream(runId, taskId, snapshot.cursor || 0);
    }
  } catch (error) {
    if (requestToken !== state.reasoningRequestToken) {
      return;
    }
    setReasoningStatus("读取失败", "failed");
    showReasoningEmpty(error.message || "无法读取该节点的思维链");
  }
}

function resolveReasoningNode(node) {
  const event = node?.event || {};
  const taskId = String(event.task_id || "");
  if (
    event.agent_id ||
    taskId.startsWith("agent:") ||
    event.initial_event_type === "agent_call"
  ) {
    return node;
  }
  const candidates = [...(state.topology?.events?.entries() || [])]
    .map(([key, child]) => ({ key, event: child }))
    .filter((candidate) => {
      const child = candidate.event || {};
      return (
        (child.parent_task_id === taskId || child.stage === event.stage) &&
        (child.agent_id || String(child.task_id || "").startsWith("agent:"))
      );
    })
    .sort(
      (left, right) =>
        Number(right.event?.started_elapsed_ms || 0) -
        Number(left.event?.started_elapsed_ms || 0),
    );
  return candidates[0] || node;
}

async function openComputationForNode(node) {
  const runId = state.selectedRunId || state.activeRunId;
  const taskId = String(node.event?.task_id || "");
  if (!runId || !taskId) {
    showToast("该节点没有可查询的任务标识", true);
    return;
  }

  const requestToken = state.reasoningRequestToken + 1;
  state.reasoningRequestToken = requestToken;
  closeReasoningStream();
  clearComputationRefresh();
  state.reasoningRunId = runId;
  state.reasoningTaskId = taskId;
  state.reasoningNodeKey = node.key;
  state.inspectorMode = "computation";
  state.reasoningCalls.clear();
  state.reasoningSeenRecords.clear();
  state.reasoningCharacters = 0;

  const body = elements["topology-body"];
  body.classList.add("reasoning-open");
  body.classList.remove("reasoning-collapsed");
  elements["reasoning-options"].classList.add("hidden");
  elements["reasoning-authority"].classList.add("hidden");
  elements["reasoning-expand-button"].textContent = "执行";
  elements["reasoning-expand-button"].title = "展开执行记录";
  elements["reasoning-node-title"].textContent =
    node.event?.title || "计算执行记录";
  elements["reasoning-node-meta"].textContent = [
    node.event?.agent_id,
    node.event?.stage,
  ].filter(Boolean).join(" · ");
  setReasoningStatus("正在读取", "waiting");
  showReasoningEmpty("正在读取该节点的执行记录...");
  refitTopologyAfterDockChange();
  await refreshComputationSnapshot(runId, taskId, requestToken);
}

async function refreshComputationSnapshot(runId, taskId, requestToken) {
  clearComputationRefresh();
  try {
    const snapshot = await api(
      `/api/runs/${encodeURIComponent(runId)}/nodes/` +
        `${encodeURIComponent(taskId)}/computation`,
    );
    if (
      requestToken !== state.reasoningRequestToken ||
      state.inspectorMode !== "computation" ||
      state.reasoningRunId !== runId ||
      state.reasoningTaskId !== taskId
    ) {
      return;
    }
    renderComputationSnapshot(snapshot);
    if (snapshot.running) {
      state.computationRefreshTimer = window.setTimeout(
        () => refreshComputationSnapshot(runId, taskId, requestToken),
        800,
      );
    }
  } catch (error) {
    if (requestToken !== state.reasoningRequestToken) {
      return;
    }
    setReasoningStatus("读取失败", "failed");
    showReasoningEmpty(error.message || "无法读取该节点的执行记录");
  }
}

function renderComputationSnapshot(snapshot) {
  const activity = snapshot.activity || {};
  elements["reasoning-node-title"].textContent =
    activity.title || "计算执行记录";
  elements["reasoning-node-meta"].textContent = [
    snapshot.method,
    snapshot.experiment_id,
  ].filter(Boolean).join(" · ");

  const status = computationStatus(snapshot);
  setReasoningStatus(status.label, status.className);
  const content = elements["reasoning-content"];
  content.replaceChildren();
  const record = document.createElement("div");
  record.className = "execution-record";
  appendExecutionSection(record, "目标", snapshot.target_claim);
  appendExecutionSection(record, "门控决策", snapshot.decision);
  appendExecutionSection(record, "契约修复", snapshot.contract_repair);
  if (
    snapshot.program &&
    typeof snapshot.program === "object" &&
    !Array.isArray(snapshot.program)
  ) {
    appendExecutionSection(
      record,
      "程序源代码",
      snapshot.program.source,
      "code",
    );
    const programMetadata = { ...snapshot.program };
    delete programMetadata.source;
    appendExecutionSection(record, "程序约束", programMetadata);
  } else {
    appendExecutionSection(record, "程序", snapshot.program, "code");
  }
  appendExecutionSection(record, "输入", snapshot.input);
  appendExecutionSection(record, "输出", snapshot.output, "code");
  appendExecutionSection(record, "运行状态", snapshot.runtime);
  appendExecutionSection(record, "隔离环境", snapshot.environment);
  appendExecutionSection(record, "审计哈希", snapshot.audit);
  appendExecutionSection(record, "计算证书", snapshot.certificate);
  if (!record.childElementCount) {
    showReasoningEmpty(
      snapshot.running
        ? "节点已经创建，正在等待程序与执行记录..."
        : "该计算节点没有可读取的执行记录。",
    );
    return;
  }
  content.append(record);
}

function appendExecutionSection(container, titleText, value, variant = "") {
  if (value === null || value === undefined || value === "") {
    return;
  }
  const section = document.createElement("section");
  section.className = "execution-section";
  const title = document.createElement("div");
  title.className = "execution-section-title";
  title.textContent = titleText;
  const output = document.createElement("pre");
  output.className =
    `execution-section-output${variant ? ` ${variant}` : ""}`;
  output.textContent =
    typeof value === "string" ? value : JSON.stringify(value, null, 2);
  section.append(title, output);
  container.append(section);
}

function computationStatus(snapshot) {
  if (snapshot.running) {
    return {
      label: {
        gate: "策略检查",
        admitted: "已准入",
        cache: "读取缓存",
        contract_repair: "修复计算请求",
        contract_repaired: "计算请求已修复",
        code_generation: "生成程序",
        executing: "沙箱运行",
      }[snapshot.phase] || "运行中",
      className: "running",
    };
  }
  if (
    ["failed", "code_generation_failed"].includes(snapshot.phase) ||
    snapshot.runtime?.error
  ) {
    return { label: "执行失败", className: "failed" };
  }
  if (
    ["reject", "defer"].includes(snapshot.phase) ||
    ["reject", "defer"].includes(snapshot.decision?.decision) ||
    String(snapshot.phase || "").startsWith("request.contract")
  ) {
    return { label: "未执行", className: "cancelled" };
  }
  return { label: "执行完成", className: "completed" };
}

function clearComputationRefresh() {
  if (state.computationRefreshTimer !== null) {
    window.clearTimeout(state.computationRefreshTimer);
    state.computationRefreshTimer = null;
  }
}

function renderReasoningSnapshot(snapshot) {
  state.reasoningCalls.clear();
  state.reasoningSeenRecords.clear();
  state.reasoningCharacters = 0;
  elements["reasoning-content"].replaceChildren();

  const activity = snapshot.activity || {};
  elements["reasoning-node-title"].textContent =
    activity.title || activity.event_type || "节点思维链";
  elements["reasoning-node-meta"].textContent = [
    activity.agent_id,
    activity.stage,
    snapshot.archive,
  ].filter(Boolean).join(" · ");
  const authority = snapshot.reasoning_authority || {};
  elements["reasoning-authority"].classList.remove("hidden");
  elements["reasoning-authority"].textContent =
    authority.label || "未验证推理";
  elements["reasoning-authority"].title =
    authority.description ||
    "模型原始推理，不是检查点、Broker Fact 或独立验证结论";

  for (const call of snapshot.calls || []) {
    const record = ensureReasoningCall(call);
    record.textNode.data = call.text || "";
    record.text = call.text || "";
    state.reasoningCharacters += record.text.length;
    updateReasoningCall(record, call);
  }

  if (!state.reasoningCalls.size) {
    showReasoningEmpty(reasoningEmptyMessage(snapshot));
  }
  const status = reasoningTraceStatus(snapshot.trace_state);
  setReasoningStatus(status.label, status.className);
  updateReasoningCharacterCount();
  followReasoningOutput();
}

function reasoningEmptyMessage(snapshot) {
  if (snapshot.trace_state === "waiting") {
    return "节点已经创建，正在等待模型开始输出推理内容...";
  }
  if (snapshot.trace_state === "legacy_unavailable") {
    return "该历史运行创建时没有保存 reasoning_content，原始思维链无法恢复。";
  }
  if (snapshot.trace_state === "no_reasoning") {
    return "该节点的调用没有返回可展示的 reasoning_content，Thinking 可能未启用。";
  }
  if (!snapshot.recordable) {
    return "该拓扑节点不对应模型调用，因此没有独立的模型思维链。";
  }
  return "此节点暂无可展示的模型推理内容。";
}

function reasoningTraceStatus(value) {
  return {
    waiting: { label: "等待输出", className: "waiting" },
    running: { label: "实时输出", className: "running" },
    completed: { label: "已完整记录", className: "completed" },
    no_reasoning: { label: "无推理内容", className: "completed" },
    legacy_unavailable: { label: "历史未记录", className: "cancelled" },
    unavailable: { label: "无思维链", className: "completed" },
    failed: { label: "记录中断", className: "failed" },
    cancelled: { label: "已取消", className: "cancelled" },
  }[value] || { label: value || "未知", className: "" };
}

function ensureReasoningCall(call) {
  const callId = String(call.call_id || `call-${call.call_index || 1}`);
  let record = state.reasoningCalls.get(callId);
  if (record) {
    return record;
  }
  if (!state.reasoningCalls.size) {
    elements["reasoning-content"].replaceChildren();
  }

  const section = document.createElement("section");
  section.className = "reasoning-call";
  section.dataset.callId = callId;
  const header = document.createElement("div");
  header.className = "reasoning-call-header";
  const title = document.createElement("strong");
  const status = document.createElement("span");
  const output = document.createElement("pre");
  output.className = "reasoning-call-output";
  const textNode = document.createTextNode("");
  output.append(textNode);
  header.append(title, status);
  section.append(header, output);
  elements["reasoning-content"].append(section);

  record = {
    callId,
    section,
    title,
    status,
    output,
    textNode,
    text: "",
    data: {},
  };
  state.reasoningCalls.set(callId, record);
  updateReasoningCall(record, call);
  return record;
}

function updateReasoningCall(record, values) {
  record.data = { ...record.data, ...values };
  const call = record.data;
  const index = Number(call.call_index) || state.reasoningCalls.size;
  record.title.textContent =
    `调用 ${index}` + (call.stage ? ` · ${call.stage}` : "");
  record.status.textContent = reasoningCallStatus(call.status);
  const finished = ["completed", "failed", "cancelled"].includes(call.status);
  record.output.classList.toggle(
    "no-reasoning",
    finished && !record.text,
  );
  if (record.text) {
    delete record.output.dataset.emptyMessage;
  } else {
    record.output.dataset.emptyMessage = finished
      ? "此调用未返回可展示的 reasoning_content。"
      : "正在等待模型推理输出...";
  }
}

function reasoningCallStatus(status) {
  return {
    running: "运行中",
    completed: "已完成",
    failed: "失败",
    cancelled: "已取消",
  }[status] || status || "运行中";
}

function showReasoningEmpty(message) {
  elements["reasoning-content"].replaceChildren();
  const empty = document.createElement("div");
  empty.className = "reasoning-empty";
  empty.textContent = message;
  elements["reasoning-content"].append(empty);
}

function connectReasoningStream(runId, taskId, cursor) {
  closeReasoningStream();
  const source = new EventSource(
    `/api/runs/${encodeURIComponent(runId)}/nodes/` +
      `${encodeURIComponent(taskId)}/reasoning/events?after=${Math.max(0, cursor)}`,
    { withCredentials: true },
  );
  state.reasoningSource = source;
  source.addEventListener("reasoning", (event) => {
    if (state.reasoningRunId !== runId || state.reasoningTaskId !== taskId) {
      return;
    }
    const payload = JSON.parse(event.data);
    applyReasoningRecord(payload.record || {});
  });
  source.addEventListener("terminal", async () => {
    if (state.reasoningSource === source) {
      closeReasoningStream();
    }
    if (state.reasoningRunId !== runId || state.reasoningTaskId !== taskId) {
      return;
    }
    try {
      const snapshot = await api(
        `/api/runs/${encodeURIComponent(runId)}/nodes/` +
          `${encodeURIComponent(taskId)}/reasoning`,
      );
      if (state.reasoningRunId === runId && state.reasoningTaskId === taskId) {
        renderReasoningSnapshot(snapshot);
      }
    } catch {
      setReasoningStatus("记录已结束", "completed");
    }
  });
  source.onerror = () => {
    if (
      state.reasoningSource === source &&
      state.reasoningRunId === runId &&
      state.reasoningTaskId === taskId
    ) {
      setReasoningStatus("正在重连", "waiting");
    }
  };
}

function applyReasoningRecord(record) {
  const callId = String(record.call_id || "");
  if (!callId) {
    return;
  }
  const recordKey =
    `${callId}:${record.type || ""}:` +
    `${record.revision ?? record.sequence ?? record.timestamp ?? ""}`;
  if (state.reasoningSeenRecords.has(recordKey)) {
    return;
  }
  state.reasoningSeenRecords.add(recordKey);

  const call = ensureReasoningCall({
    call_id: callId,
    call_index: record.call_index,
    agent_id: record.agent_id,
    stage: record.stage,
    thinking_enabled: record.thinking_enabled,
    reasoning_effort: record.reasoning_effort,
    status: "running",
  });
  if (record.type === "start") {
    updateReasoningCall(call, {
      ...record,
      status: "running",
    });
    setReasoningStatus("实时输出", "running");
  } else if (record.type === "delta" && typeof record.text === "string") {
    call.text += record.text;
    call.textNode.appendData(record.text);
    call.output.classList.remove("no-reasoning");
    delete call.output.dataset.emptyMessage;
    state.reasoningCharacters += record.text.length;
    setReasoningStatus("实时输出", "running");
  } else if (
    ["preview", "paragraph"].includes(record.type) &&
    typeof record.text === "string"
  ) {
    state.reasoningCharacters += record.text.length - call.text.length;
    call.text = record.text;
    call.textNode.data = record.text;
    call.output.classList.remove("no-reasoning");
    delete call.output.dataset.emptyMessage;
    setReasoningStatus("实时输出", "running");
  } else if (record.type === "end") {
    updateReasoningCall(call, record);
    const status = reasoningTraceStatus(record.status);
    setReasoningStatus(status.label, status.className);
  }
  updateReasoningCharacterCount();
  followReasoningOutput();
}

function setReasoningStatus(label, className = "") {
  const status = elements["reasoning-status"];
  status.textContent = label;
  status.className = `reasoning-status${className ? ` ${className}` : ""}`;
}

function updateReasoningCharacterCount() {
  elements["reasoning-character-count"].textContent =
    `${formatNumber(state.reasoningCharacters)} 字符`;
}

function followReasoningOutput() {
  if (!elements["reasoning-follow-toggle"].checked) {
    return;
  }
  const content = elements["reasoning-content"];
  window.requestAnimationFrame(() => {
    content.scrollTop = content.scrollHeight;
  });
}

function closeReasoningStream() {
  if (state.reasoningSource) {
    state.reasoningSource.close();
    state.reasoningSource = null;
  }
}

function closeReasoningPanel(event) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
  state.reasoningRequestToken += 1;
  closeReasoningStream();
  clearComputationRefresh();
  state.reasoningRunId = null;
  state.reasoningTaskId = null;
  state.reasoningNodeKey = null;
  state.reasoningCalls.clear();
  state.reasoningSeenRecords.clear();
  state.reasoningCharacters = 0;
  state.inspectorMode = null;
  elements["reasoning-options"].classList.remove("hidden");
  elements["reasoning-authority"].classList.remove("hidden");
  elements["reasoning-expand-button"].textContent = "CoT";
  elements["topology-body"].classList.remove(
    "reasoning-open",
    "reasoning-collapsed",
  );
  refitTopologyAfterDockChange();
}

function collapseReasoningPanel(event) {
  event.preventDefault();
  event.stopPropagation();
  elements["topology-body"].classList.add("reasoning-collapsed");
  refitTopologyAfterDockChange();
}

function expandReasoningPanel(event) {
  event.preventDefault();
  event.stopPropagation();
  elements["topology-body"].classList.remove("reasoning-collapsed");
  refitTopologyAfterDockChange();
}

function refitTopologyAfterDockChange() {
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => state.topology.fit());
  });
}

function bindReasoningResizer() {
  const resizer = elements["reasoning-resizer"];
  resizer.addEventListener("pointerdown", (event) => {
    if (
      event.button !== 0 ||
      elements["topology-body"].classList.contains("reasoning-collapsed")
    ) {
      return;
    }
    event.preventDefault();
    resizer.setPointerCapture(event.pointerId);
    const narrow = window.matchMedia("(max-width: 1120px)").matches;
    const dockBounds = elements["reasoning-dock"].getBoundingClientRect();
    state.reasoningResize = {
      pointerId: event.pointerId,
      narrow,
      startX: event.clientX,
      startY: event.clientY,
      startSize: narrow ? dockBounds.height : dockBounds.width,
    };
    resizer.classList.add("dragging");
  });
  resizer.addEventListener("pointermove", (event) => {
    const resize = state.reasoningResize;
    if (!resize || resize.pointerId !== event.pointerId) {
      return;
    }
    const bounds = elements["topology-body"].getBoundingClientRect();
    const delta = resize.narrow
      ? resize.startY - event.clientY
      : event.clientX - resize.startX;
    const minimum = resize.narrow ? 170 : 280;
    const maximum = resize.narrow
      ? Math.max(minimum, bounds.height * 0.48)
      : Math.max(minimum, Math.min(560, bounds.width * 0.48));
    const size = Math.max(minimum, Math.min(maximum, resize.startSize + delta));
    elements["topology-body"].style.setProperty(
      "--reasoning-pane-size",
      `${Math.round(size)}px`,
    );
  });
  const finishResize = (event) => {
    if (
      !state.reasoningResize ||
      state.reasoningResize.pointerId !== event.pointerId
    ) {
      return;
    }
    state.reasoningResize = null;
    resizer.classList.remove("dragging");
    refitTopologyAfterDockChange();
  };
  resizer.addEventListener("pointerup", finishResize);
  resizer.addEventListener("pointercancel", finishResize);
  resizer.addEventListener("keydown", (event) => {
    const narrow = window.matchMedia("(max-width: 1120px)").matches;
    const relevant = narrow
      ? ["ArrowUp", "ArrowDown"]
      : ["ArrowLeft", "ArrowRight"];
    if (!relevant.includes(event.key)) {
      return;
    }
    event.preventDefault();
    const dockBounds = elements["reasoning-dock"].getBoundingClientRect();
    const bodyBounds = elements["topology-body"].getBoundingClientRect();
    const current = narrow ? dockBounds.height : dockBounds.width;
    const increase = ["ArrowUp", "ArrowRight"].includes(event.key);
    const minimum = narrow ? 170 : 280;
    const maximum = narrow
      ? Math.max(minimum, bodyBounds.height * 0.48)
      : Math.max(minimum, Math.min(560, bodyBounds.width * 0.48));
    const next = Math.max(
      minimum,
      Math.min(maximum, current + (increase ? 20 : -20)),
    );
    elements["topology-body"].style.setProperty(
      "--reasoning-pane-size",
      `${Math.round(next)}px`,
    );
    refitTopologyAfterDockChange();
  });
}

function showNewRun() {
  closeReasoningPanel();
  state.selectedRunId = null;
  setWorkspaceView("detail");
  elements["workspace-view-switcher"].classList.add("hidden");
  elements["workspace-title"].textContent = "新建求解";
  elements["workspace-subtitle"].textContent = "尚未启动";
  elements["editor-panel"].classList.remove("hidden");
  elements["problem-input"].disabled = Boolean(state.activeRunId);
  elements["profile-select"].disabled = Boolean(state.activeRunId);
  elements["run-id-input"].disabled = Boolean(state.activeRunId);
  elements["start-button"].disabled = Boolean(state.activeRunId);
  elements["resume-button"].classList.add("hidden");
  elements["open-run-button"].classList.add("hidden");
  if (!state.activeRunId) {
    resetRunDisplay();
  }
  renderRunList(state.bootstrap?.runs || []);
}

async function loadRun(runId) {
  try {
    if (state.reasoningRunId && state.reasoningRunId !== runId) {
      closeReasoningPanel();
    }
    const detail = await api(`/api/runs/${encodeURIComponent(runId)}`);
    state.selectedRunId = runId;
    const summary = detail.summary || {};
    elements["workspace-title"].textContent = summary.title || runId;
    elements["workspace-subtitle"].textContent = runId;
    elements["workspace-view-switcher"].classList.remove("hidden");
    elements["editor-panel"].classList.add("hidden");
    elements["open-run-button"].classList.remove("hidden");
    elements["resume-button"].classList.toggle(
      "hidden",
      !summary.resumable || Boolean(state.activeRunId),
    );
    renderRunDetail(detail);
    renderActivity(detail.activity || []);
    renderRunList(state.bootstrap?.runs || []);
  } catch (error) {
    showToast(error.message, true);
  }
}

function renderRunDetail(detail) {
  const summary = detail.summary || {};
  const result = detail.result || {};
  const lifecycle = summary.lifecycle || "interrupted";
  setRunStatus(
    result.status || lifecycle,
    result.summary || lifecycleLabels[lifecycle] || lifecycle,
  );
  elements["metric-calls"].textContent = formatNumber(result.total_calls || 0);
  const usage = result.total_usage || {};
  elements["metric-tokens"].textContent = formatNumber(usage.total_tokens || 0);
  elements["metric-cost"].textContent =
    `$${Number(usage.estimated_cost_usd || 0).toFixed(4)}`;
  const profile = state.bootstrap?.profiles.find((item) => item.id === summary.profile);
  elements["metric-profile"].textContent = profile?.label || summary.profile || "未知";

  const originalProblem = detail.problem || "";
  const canonicalProblem = detail.canonical_problem || originalProblem;
  const interpretation = detail.interpretation || {};
  elements["problem-view"].textContent =
    canonicalProblem !== originalProblem
      ? `用户原题\n\n${originalProblem}\n\n已确认的规范化目标\n\n${canonicalProblem}` +
        `\n\n目标哈希：${interpretation.goal_hash || "无"}`
      : originalProblem;
  elements["report-view"].textContent = detail.report || "";
  renderAnswer(result);
}

function renderAnswer(result) {
  elements["answer-view"].replaceChildren();
  const summaryText = result.summary || result.research_progress;
  const answerText = result.answer || result.research_progress;
  if (!answerText && !summaryText) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "当前运行尚无最终结论";
    elements["answer-view"].append(empty);
    return;
  }
  if (summaryText && summaryText !== answerText) {
    const summary = document.createElement("div");
    summary.className = "answer-summary";
    summary.textContent = summaryText;
    elements["answer-view"].append(summary);
  }
  if (answerText) {
    const answer = document.createElement("div");
    answer.className = "answer-content";
    answer.textContent = answerText;
    elements["answer-view"].append(answer);
  }
}

function goalInterpretationCandidates(request) {
  const assessment = request.assessment || {};
  const candidates = [
    {
      statement: assessment.recommended_statement || "",
      confidence: Number(assessment.recommendation_confidence || 0),
      rationale: "推荐解释",
    },
  ];
  for (const alternative of assessment.alternative_interpretations || []) {
    candidates.push({
      statement: alternative.statement || "",
      confidence: Number(alternative.confidence || 0),
      rationale: alternative.rationale || "其他可能解释",
    });
  }
  return candidates.filter((candidate) => candidate.statement.trim());
}

function showGoalClarification(request) {
  state.clarificationRequest = request;
  state.clarificationSelectedIndex = 0;
  elements["clarification-original"].textContent =
    request.original_statement || "";

  const reasons = [
    ...(request.local_precheck?.reasons || []),
    ...(request.assessment?.ambiguity_reasons || []),
  ];
  elements["clarification-reasons"].replaceChildren();
  for (const reason of [...new Set(reasons)]) {
    const row = document.createElement("div");
    row.textContent = reason;
    elements["clarification-reasons"].append(row);
  }
  const confidence = Number(
    request.assessment?.recommendation_confidence || 0,
  );
  elements["clarification-confidence"].textContent =
    `推荐置信度 ${Math.round(confidence * 100)}%，置信度不替代用户确认`;

  const candidates = goalInterpretationCandidates(request);
  elements["clarification-candidates"].replaceChildren();
  candidates.forEach((candidate, index) => {
    const label = document.createElement("label");
    label.className = "clarification-candidate";

    const radio = document.createElement("input");
    radio.type = "radio";
    radio.name = "goal-interpretation";
    radio.value = String(index);
    radio.checked = index === 0;
    radio.addEventListener("change", () => {
      if (!radio.checked) {
        return;
      }
      state.clarificationSelectedIndex = index;
      elements["clarification-statement"].value = candidate.statement;
    });

    const copy = document.createElement("div");
    copy.className = "clarification-candidate-copy";
    const statement = document.createElement("div");
    statement.textContent = candidate.statement;
    const rationale = document.createElement("div");
    rationale.className = "section-meta";
    rationale.textContent = candidate.rationale;
    copy.append(statement, rationale);

    const score = document.createElement("span");
    score.className = "clarification-candidate-confidence";
    score.textContent = `${Math.round(candidate.confidence * 100)}%`;
    label.append(radio, copy, score);
    elements["clarification-candidates"].append(label);
  });

  elements["clarification-statement"].value =
    candidates[0]?.statement || "";
  if (!elements["clarification-dialog"].open) {
    elements["clarification-dialog"].showModal();
  }
}

function trackCustomClarification() {
  const value = elements["clarification-statement"].value.trim();
  const candidates = goalInterpretationCandidates(
    state.clarificationRequest || {},
  );
  const index = candidates.findIndex(
    (candidate) => candidate.statement.trim() === value,
  );
  state.clarificationSelectedIndex = index >= 0 ? index : null;
  document
    .querySelectorAll('input[name="goal-interpretation"]')
    .forEach((radio) => {
      radio.checked = index >= 0 && Number(radio.value) === index;
    });
}

function closeGoalClarification() {
  if (elements["clarification-dialog"].open) {
    elements["clarification-dialog"].close();
  }
  state.clarificationRequest = null;
  state.clarificationSelectedIndex = null;
}

async function confirmGoalClarification() {
  const request = state.clarificationRequest;
  const canonicalStatement =
    elements["clarification-statement"].value.trim();
  if (!request || !state.activeRunId || !canonicalStatement) {
    showToast("请确认一个完整的数学目标", true);
    return;
  }
  setBusy(
    elements["confirm-clarification-button"],
    true,
    "正在冻结目标",
  );
  try {
    await api(
      `/api/runs/${encodeURIComponent(state.activeRunId)}/clarification`,
      {
        method: "POST",
        body: {
          request_id: request.request_id,
          canonical_statement: canonicalStatement,
          selected_candidate_index: state.clarificationSelectedIndex,
        },
      },
    );
    closeGoalClarification();
    showToast("规范化目标已确认，开始求解");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(
      elements["confirm-clarification-button"],
      false,
      "确认并继续",
    );
  }
}

async function cancelGoalClarification() {
  const cancelled = await cancelRun();
  if (cancelled) {
    closeGoalClarification();
  }
}

async function startRun() {
  const problem = elements["problem-input"].value.trim();
  if (!problem) {
    showToast("请先输入数学题目", true);
    elements["problem-input"].focus();
    return;
  }
  setBusy(elements["start-button"], true, "正在启动");
  try {
    const profile = elements["profile-select"].value;
    await persistSelectedProfile(profile);
    const data = await api("/api/runs", {
      method: "POST",
      body: {
        problem,
        profile,
        run_id: elements["run-id-input"].value.trim() || null,
      },
    });
    attachActiveRun(data.run);
    showToast(`已启动 ${data.run.run_id}`);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    if (!state.activeRunId) {
      setBusy(elements["start-button"], false, "开始求解");
    } else {
      elements["start-button"].textContent = "开始求解";
    }
  }
}

function attachActiveRun(run) {
  closeGoalClarification();
  if (state.reasoningRunId && state.reasoningRunId !== run.run_id) {
    closeReasoningPanel();
  }
  state.activeRunId = run.run_id;
  state.selectedRunId = run.run_id;
  state.activeLifecycle = run.lifecycle;
  elements["workspace-title"].textContent =
    run.title || firstNonEmptyLine(elements["problem-input"].value) || run.run_id;
  elements["workspace-subtitle"].textContent = run.run_id;
  elements["workspace-view-switcher"].classList.remove("hidden");
  elements["problem-input"].disabled = true;
  elements["profile-select"].disabled = true;
  elements["run-id-input"].disabled = true;
  elements["start-button"].disabled = true;
  elements["cancel-button"].classList.remove("hidden");
  elements["resume-button"].classList.add("hidden");
  elements["open-run-button"].classList.remove("hidden");
  elements["activity-list"].replaceChildren();
  state.activityItems.clear();
  state.activityCount = 0;
  state.topology.clear();
  elements["progress-bar"].classList.add("indeterminate");
  setRunStatus(run.lifecycle, lifecycleLabels[run.lifecycle] || run.lifecycle);
  startElapsedClock(run.attempt_started_at || run.updated_at || run.created_at);
  connectEventStream(run.run_id);
  refreshRuns();
}

function connectEventStream(runId) {
  closeEventStream();
  const source = new EventSource(
    `/api/runs/${encodeURIComponent(runId)}/events`,
    { withCredentials: true },
  );
  state.eventSource = source;
  source.addEventListener("activity", (event) => {
    const payload = JSON.parse(event.data);
    appendActivity(payload);
  });
  source.addEventListener("state", (event) => {
    const payload = JSON.parse(event.data);
    state.activeLifecycle = payload.lifecycle;
    setRunStatus(
      payload.lifecycle,
      payload.error || lifecycleLabels[payload.lifecycle] || payload.lifecycle,
    );
  });
  source.addEventListener("clarification", (event) => {
    showGoalClarification(JSON.parse(event.data));
  });
  source.addEventListener("result", (event) => {
    const payload = JSON.parse(event.data);
    elements["metric-calls"].textContent = formatNumber(payload.total_calls || 0);
    elements["metric-tokens"].textContent = formatNumber(payload.total_tokens || 0);
    elements["metric-cost"].textContent =
      `$${Number(payload.estimated_cost_usd || 0).toFixed(4)}`;
    setRunStatus(
      payload.status,
      `${statusLabels[payload.task_status] || payload.task_status} · ` +
        `${statusLabels[payload.math_status] || payload.math_status} · ` +
        `${statusLabels[payload.execution_status] || payload.execution_status}`,
    );
  });
  source.addEventListener("error", (event) => {
    if (event.data) {
      const payload = JSON.parse(event.data);
      showToast(payload.message || "运行失败", true);
    }
  });
  source.addEventListener("terminal", async () => {
    closeGoalClarification();
    closeEventStream();
    stopElapsedClock();
    elements["progress-bar"].classList.remove("indeterminate");
    elements["progress-bar"].style.width = "100%";
    elements["cancel-button"].classList.add("hidden");
    const finishedRunId = state.activeRunId;
    state.activeRunId = null;
    state.activeLifecycle = null;
    elements["problem-input"].disabled = false;
    elements["profile-select"].disabled = false;
    elements["run-id-input"].disabled = false;
    elements["start-button"].disabled = false;
    setServiceStatus("connected", "本地服务已连接");
    await refreshRuns();
    if (finishedRunId) {
      await loadRun(finishedRunId);
    }
  });
  source.onerror = (event) => {
    // Application failures also use the SSE event name "error". Only transport errors reconnect.
    if (event instanceof MessageEvent) {
      return;
    }
    if (state.activeRunId === runId) {
      setServiceStatus("error", "进度流正在重连");
    }
  };
  source.onopen = () => setServiceStatus("connected", "本地服务已连接");
}

function closeEventStream() {
  if (state.eventSource) {
    state.eventSource.close();
    state.eventSource = null;
  }
}

async function cancelRun() {
  if (!state.activeRunId) {
    return false;
  }
  const confirmed = window.confirm("停止当前运行？已写入的检查点会保留。");
  if (!confirmed) {
    return false;
  }
  setBusy(elements["cancel-button"], true, "正在停止");
  try {
    await api(`/api/runs/${encodeURIComponent(state.activeRunId)}/cancel`, {
      method: "POST",
    });
    return true;
  } catch (error) {
    showToast(error.message, true);
    return false;
  } finally {
    setBusy(elements["cancel-button"], false, "停止");
  }
}

async function resumeRun() {
  if (!state.selectedRunId || state.activeRunId) {
    return;
  }
  setBusy(elements["resume-button"], true, "正在恢复");
  try {
    const profile = elements["profile-select"].value ||
      state.bootstrap.settings.selected_profile;
    const data = await api(
      `/api/runs/${encodeURIComponent(state.selectedRunId)}/resume`,
      {
        method: "POST",
        body: { profile },
      },
    );
    attachActiveRun(data.run);
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(elements["resume-button"], false, "恢复运行");
  }
}

function appendActivity(event, follow = true, syncTopology = true) {
  const key = activityKey(event);
  const list = elements["activity-list"];
  const wasNearBottom =
    list.scrollHeight - list.scrollTop - list.clientHeight < 36;
  let record = state.activityItems.get(key);
  const isNew = !record;

  if (isNew) {
    if (state.activityItems.size === 0) {
      list.replaceChildren();
    }
    const item = document.createElement("div");
    const indicator = document.createElement("div");
    indicator.className = "activity-indicator";
    const body = document.createElement("div");
    const title = document.createElement("div");
    title.className = "activity-title";
    const detail = document.createElement("div");
    detail.className = "activity-detail hidden";
    const agent = document.createElement("div");
    agent.className = "activity-agent hidden";
    const time = document.createElement("div");
    time.className = "activity-time";
    body.append(title, detail, agent);
    item.append(indicator, body, time);
    item.dataset.activityKey = key;
    item.tabIndex = 0;
    item.setAttribute("role", "button");
    item.title = "在拓扑图中查看";
    item.addEventListener("click", () => openTopologyForActivity(key));
    item.addEventListener("keydown", (keyboardEvent) => {
      if (keyboardEvent.key === "Enter" || keyboardEvent.key === " ") {
        keyboardEvent.preventDefault();
        openTopologyForActivity(key);
      }
    });
    list.append(item);
    record = { item, title, detail, agent, time };
    state.activityItems.set(key, record);
  }

  const snapshot = mergeActivitySnapshot(record.event, event);
  record.item.className = `activity-item ${snapshot.status || "info"}`;
  record.title.textContent =
    snapshot.title || snapshot.event_type || "进度更新";
  record.detail.textContent = snapshot.detail || "";
  record.detail.classList.toggle("hidden", !snapshot.detail);
  record.agent.textContent = snapshot.agent_id || "";
  record.agent.classList.toggle("hidden", !snapshot.agent_id);
  record.time.textContent = formatElapsedMs(snapshot.elapsed_ms || 0);
  record.event = snapshot;
  if (syncTopology) {
    state.topology.upsert(key, snapshot);
  }

  state.activityCount = state.activityItems.size;
  if (follow && (isNew || wasNearBottom)) {
    list.scrollTop = list.scrollHeight;
  }

  elements["current-stage"].textContent =
    snapshot.title || snapshot.stage || "运行中";
  if (typeof snapshot.progress === "number") {
    elements["progress-bar"].classList.remove("indeterminate");
    elements["progress-bar"].style.width =
      `${Math.round(snapshot.progress * 100)}%`;
  }
  updateMetricsFromActivity(snapshot.metrics || {});
}

function mergeActivitySnapshot(previous, event) {
  return {
    ...(previous || {}),
    ...event,
    parent_task_id:
      previous?.parent_task_id || event.parent_task_id || null,
    started_elapsed_ms:
      previous?.started_elapsed_ms ??
      event.started_elapsed_ms ??
      event.elapsed_ms ??
      0,
    initial_event_type:
      previous?.initial_event_type ||
      event.initial_event_type ||
      event.event_type ||
      "activity",
    metrics: event.metrics || previous?.metrics || {},
  };
}

function activityKey(event) {
  if (event.task_id) {
    return `task:${event.task_id}`;
  }
  if (event.sequence !== undefined && event.sequence !== null) {
    return `sequence:${event.sequence}`;
  }
  return `event:${event.event_type || "activity"}:${event.elapsed_ms || 0}`;
}

function renderActivity(events) {
  elements["activity-list"].replaceChildren();
  state.activityItems.clear();
  state.activityCount = 0;
  const topologyEntries = [];
  for (const event of events) {
    appendActivity(event, false, false);
    const key = activityKey(event);
    topologyEntries.push({
      key,
      event: state.activityItems.get(key)?.event || event,
    });
  }
  state.topology.setEvents(topologyEntries);
  if (!state.activityItems.size) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "此运行没有可显示的进度事件";
    elements["activity-list"].append(empty);
  } else {
    elements["activity-list"].scrollTop =
      elements["activity-list"].scrollHeight;
  }
  elements["progress-bar"].classList.remove("indeterminate");
  elements["progress-bar"].style.width = state.activityItems.size ? "100%" : "0";
  const last = events.at(-1);
  elements["elapsed-time"].textContent = last
    ? formatElapsedMs(last.elapsed_ms || 0)
    : "00:00";
  elements["current-stage"].textContent = last?.title || "运行记录";
}

function setWorkspaceView(name) {
  const next = name === "topology" ? "topology" : "detail";
  if (
    next === "topology" &&
    !state.selectedRunId &&
    !state.activeRunId
  ) {
    return;
  }
  state.workspaceView = next;
  const topologyActive = next === "topology";
  elements["workspace-detail-view"].classList.toggle(
    "hidden",
    topologyActive,
  );
  elements["topology-view"].classList.toggle("hidden", !topologyActive);
  elements.workspace.classList.toggle("topology-active", topologyActive);
  elements["detail-view-button"].classList.toggle("active", !topologyActive);
  elements["detail-view-button"].setAttribute(
    "aria-selected",
    topologyActive ? "false" : "true",
  );
  elements["topology-view-button"].classList.toggle("active", topologyActive);
  elements["topology-view-button"].setAttribute(
    "aria-selected",
    topologyActive ? "true" : "false",
  );
  state.topology.setVisible(topologyActive);
}

function openTopologyForActivity(key) {
  setWorkspaceView("topology");
  window.requestAnimationFrame(() => {
    state.topology.focusNode(key);
  });
}

function locateActivityInTimeline(key) {
  setWorkspaceView("detail");
  window.requestAnimationFrame(() => {
    const record = state.activityItems.get(key);
    if (!record) {
      return;
    }
    document.querySelectorAll(".activity-item.located").forEach((item) => {
      item.classList.remove("located");
    });
    record.item.classList.add("located");
    record.item.scrollIntoView({ behavior: "smooth", block: "center" });
    record.item.focus({ preventScroll: true });
    window.setTimeout(() => record.item.classList.remove("located"), 2200);
  });
}

function updateMetricsFromActivity(metrics) {
  const calls = pickNumber(metrics, [
    "total_calls",
    "calls",
    "calls_used",
    "used_calls",
  ]);
  const tokens = pickNumber(metrics, [
    "total_tokens",
    "tokens",
    "tokens_used",
  ]);
  const cost = pickNumber(metrics, [
    "estimated_cost_usd",
    "cost_usd",
  ]);
  if (calls !== null) {
    elements["metric-calls"].textContent = formatNumber(calls);
  }
  if (tokens !== null) {
    elements["metric-tokens"].textContent = formatNumber(tokens);
  }
  if (cost !== null) {
    elements["metric-cost"].textContent = `$${Number(cost).toFixed(4)}`;
  }
}

function pickNumber(object, keys) {
  for (const key of keys) {
    if (typeof object[key] === "number") {
      return object[key];
    }
  }
  return null;
}

function resetRunDisplay() {
  closeReasoningPanel();
  state.selectedRunId = null;
  elements["activity-list"].replaceChildren();
  state.activityItems.clear();
  state.topology.clear();
  const activityEmpty = document.createElement("div");
  activityEmpty.className = "empty-state";
  activityEmpty.textContent = "尚无进度事件";
  elements["activity-list"].append(activityEmpty);
  state.activityCount = 0;
  elements["current-stage"].textContent = "等待任务";
  elements["elapsed-time"].textContent = "00:00";
  elements["progress-bar"].style.width = "0";
  elements["progress-bar"].classList.remove("indeterminate");
  elements["metric-calls"].textContent = "0";
  elements["metric-tokens"].textContent = "0";
  elements["metric-cost"].textContent = "$0.0000";
  setRunStatus("idle", "等待输入题目");
  elements["answer-view"].replaceChildren();
  const outputEmpty = document.createElement("div");
  outputEmpty.className = "empty-state";
  outputEmpty.textContent = "任务完成后显示证明结果";
  elements["answer-view"].append(outputEmpty);
  elements["report-view"].textContent = "";
  elements["problem-view"].textContent = "";
  elements["cancel-button"].classList.add("hidden");
  updateProfileSummary();
}

function setRunStatus(status, detail) {
  const badge = elements["run-status-badge"];
  badge.className = `status-badge status-${status}`;
  badge.textContent =
    lifecycleLabels[status] || statusLabels[status] || status || "未知";
  elements["run-status-detail"].textContent = detail || "";
}

function openSettings() {
  applySettingsToDialog(state.bootstrap.settings);
  populateCredentialFields(state.bootstrap.credential_status);
  elements["probe-results"].classList.add("hidden");
  elements["settings-dialog"].showModal();
}

function applySettingsToDialog(settings) {
  elements["settings-profile-select"].value = settings.selected_profile;
  elements["sandbox-toggle"].checked = settings.sandbox_enabled;
  elements["remember-toggle"].checked = settings.remember_credentials;
}

function populateCredentialFields(statuses) {
  elements["credential-fields"].replaceChildren();
  const entries = Object.entries(statuses).sort(([a], [b]) => a.localeCompare(b));
  let configured = 0;
  for (const [name, status] of entries) {
    if (status !== "missing") {
      configured += 1;
    }
    const wrapper = document.createElement("div");
    wrapper.className = "credential-field";
    const label = document.createElement("label");
    const shortName = name.replace("DEEPSEEK_AGENT_", "Agent ").replace("_KEY", "");
    const labelText = document.createElement("span");
    labelText.textContent = shortName;
    const stateLabel = document.createElement("span");
    stateLabel.className = `credential-state ${status === "missing" ? "" : "saved"}`;
    stateLabel.textContent = credentialStatusLabel(status);
    label.append(labelText, stateLabel);
    const input = document.createElement("input");
    input.type = "password";
    input.autocomplete = "new-password";
    input.dataset.credentialName = name;
    input.placeholder = status === "missing" ? "输入 API Key" : "已配置，留空不修改";
    wrapper.append(label, input);
    elements["credential-fields"].append(wrapper);
  }
  elements["credential-summary"].textContent = `${configured}/5 已配置`;
}

function credentialStatusLabel(status) {
  return {
    saved: "已保存",
    session: "本次会话",
    environment: "环境变量",
    missing: "未配置",
  }[status] || status;
}

async function saveSettings() {
  setBusy(elements["save-settings-button"], true, "正在保存");
  try {
    const values = {};
    elements["credential-fields"]
      .querySelectorAll("input[data-credential-name]")
      .forEach((input) => {
        if (input.value.trim()) {
          values[input.dataset.credentialName] = input.value.trim();
        }
      });
    const persist = elements["remember-toggle"].checked;
    const credentialData = await api("/api/credentials", {
      method: "PUT",
      body: { values, clear: [], persist },
    });
    const settings = {
      selected_profile: elements["settings-profile-select"].value,
      sandbox_enabled: elements["sandbox-toggle"].checked,
      remember_credentials: persist,
    };
    const settingsData = await api("/api/settings", {
      method: "PUT",
      body: settings,
    });
    state.bootstrap.settings = settingsData.settings;
    state.bootstrap.profiles = settingsData.profiles;
    state.bootstrap.credential_status = credentialData.credential_status;
    populateProfiles(settingsData.profiles);
    populateCredentialFields(credentialData.credential_status);
    updateEnvironmentHealth();
    elements["settings-dialog"].close();
    showToast("设置已保存");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(elements["save-settings-button"], false, "保存设置");
  }
}

async function persistSelectedProfile(profile) {
  if (state.bootstrap.settings.selected_profile === profile) {
    return;
  }
  const settings = {
    ...state.bootstrap.settings,
    selected_profile: profile,
  };
  const data = await api("/api/settings", {
    method: "PUT",
    body: settings,
  });
  state.bootstrap.settings = data.settings;
  state.bootstrap.profiles = data.profiles;
}

async function clearCredentials() {
  const confirmed = window.confirm("清除本机保存及本次会话中的全部 API Key？");
  if (!confirmed) {
    return;
  }
  try {
    const data = await api("/api/credentials", { method: "DELETE" });
    state.bootstrap.credential_status = data.credential_status;
    populateCredentialFields(data.credential_status);
    updateEnvironmentHealth();
    showToast("已清除 API Key");
  } catch (error) {
    showToast(error.message, true);
  }
}

async function probeCredentials() {
  setBusy(elements["probe-button"], true, "正在测试");
  elements["probe-results"].replaceChildren();
  elements["probe-results"].classList.remove("hidden");
  try {
    const data = await api("/api/probe", { method: "POST" });
    for (const result of data.results) {
      const row = document.createElement("div");
      row.className = "probe-row";
      const agent = document.createElement("span");
      agent.textContent = `${result.agent} · ${result.model}`;
      const outcome = document.createElement("span");
      const ok = result.credential_ok === true;
      outcome.className = ok ? "probe-result-ok" : "probe-result-error";
      outcome.textContent = ok
        ? result.model_visible
          ? "可用"
          : "Key 可用"
        : "连接失败";
      row.append(agent, outcome);
      elements["probe-results"].append(row);
    }
  } catch (error) {
    const row = document.createElement("div");
    row.className = "probe-row";
    const message = document.createElement("span");
    message.textContent = error.message;
    const outcome = document.createElement("span");
    outcome.className = "probe-result-error";
    outcome.textContent = "失败";
    row.append(message, outcome);
    elements["probe-results"].append(row);
  } finally {
    setBusy(elements["probe-button"], false, "测试连接");
  }
}

function updateEnvironmentHealth() {
  const statuses = state.bootstrap.credential_status || {};
  const configured = Object.values(statuses).filter((status) => status !== "missing").length;
  elements["key-health"].textContent = `${configured}/5 已配置`;
  elements["key-health"].className =
    `environment-value ${configured === 5 ? "ok" : "warn"}`;
  const dockerAvailable = state.bootstrap.docker_available;
  elements["docker-health"].textContent = dockerAvailable ? "可用" : "未检测到";
  elements["docker-health"].className =
    `environment-value ${dockerAvailable ? "ok" : "warn"}`;
  elements["docker-setting-note"].textContent = dockerAvailable
    ? "本机已检测到 Docker"
    : "本机未检测到 Docker";
}

async function openPath(kind) {
  try {
    await api("/api/open-path", {
      method: "POST",
      body: {
        kind,
        run_id: kind === "run" ? state.selectedRunId || state.activeRunId : null,
      },
    });
  } catch (error) {
    showToast(error.message, true);
  }
}

async function importProblemFile(event) {
  const [file] = event.target.files;
  if (!file) {
    return;
  }
  try {
    elements["problem-input"].value = await file.text();
    updateProblemCount();
  } catch (error) {
    showToast(`无法读取文件：${error.message}`, true);
  } finally {
    event.target.value = "";
  }
}

function updateProblemCount() {
  elements["problem-count"].textContent =
    `${formatNumber(elements["problem-input"].value.length)} 字符`;
}

function selectTab(name) {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tab === name);
    tab.setAttribute("aria-selected", tab.dataset.tab === name ? "true" : "false");
  });
  document.querySelectorAll(".output-view").forEach((view) => {
    view.classList.toggle("active", view.dataset.view === name);
  });
}

function startElapsedClock(createdAt) {
  stopElapsedClock();
  const parsed = Date.parse(createdAt);
  state.elapsedStartedAt = Number.isNaN(parsed) ? Date.now() : parsed;
  const update = () => {
    const now = Date.now();
    elements["elapsed-time"].textContent =
      formatElapsedMs(now - state.elapsedStartedAt);
    state.topology?.tick(now);
  };
  update();
  state.elapsedTimer = window.setInterval(update, 1000);
}

function stopElapsedClock() {
  if (state.elapsedTimer) {
    window.clearInterval(state.elapsedTimer);
    state.elapsedTimer = null;
  }
}

function setServiceStatus(kind, text) {
  elements["service-status"].className = `service-status ${kind}`;
  elements["service-status"].lastElementChild.textContent = text;
}

function setBusy(button, busy, text) {
  button.disabled = busy;
  button.textContent = text;
}

function showToast(message, error = false) {
  const toast = document.createElement("div");
  toast.className = `toast${error ? " error" : ""}`;
  toast.textContent = message;
  elements["toast-region"].append(toast);
  window.setTimeout(() => toast.remove(), 4200);
}

function firstNonEmptyLine(value) {
  return value.split(/\r?\n/).map((line) => line.trim()).find(Boolean) || "";
}

function formatElapsedMs(milliseconds) {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function formatNumber(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value) || 0);
}

function formatCompact(value) {
  return new Intl.NumberFormat("zh-CN", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(Number(value) || 0);
}

function formatShortDate(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

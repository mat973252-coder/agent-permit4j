const ui = {
  caseSelect: document.querySelector("#case-select"),
  outcomeBadge: document.querySelector("#outcome-badge"),
  conversation: document.querySelector("#conversation"),
  timeline: document.querySelector("#timeline"),
  decisionTitle: document.querySelector("#decision-title"),
  decisionCopy: document.querySelector("#decision-copy"),
  decisionHero: document.querySelector(".decision-hero"),
  reasonCode: document.querySelector("#reason-code"),
  sideEffectCount: document.querySelector("#side-effect-count"),
  approveButton: document.querySelector("#approve-button"),
  inspector: document.querySelector("#inspector-content"),
  error: document.querySelector("#error-state"),
  tabs: [...document.querySelectorAll("[data-tab]")]
};

const outcomeCopy = {
  APPROVAL_REQUIRED: {
    title: "需要人工确认",
    copy: "风险策略已暂停执行；只有与当前调用指纹绑定的后端审批才能继续。"
  },
  EXECUTED: {
    title: "演示执行已完成",
    copy: "授权、风险与审批校验均已通过，执行器在受控边界内仅调用一次。"
  },
  DENIED: {
    title: "请求已被阻断",
    copy: "策略在副作用发生前终止调用，并返回可审计的稳定原因码。"
  }
};

let cases = [];
let currentFixture = null;
let activeTab = "tool-calls";

document.addEventListener("DOMContentLoaded", initialize);

async function initialize() {
  bindTabs();
  ui.caseSelect.addEventListener("change", () => selectCase(ui.caseSelect.value));
  ui.approveButton.addEventListener("click", approveCurrentCase);
  try {
    cases = await requestJson("/api/playground/cases");
    populateCaseSelect();
    await selectCase(cases[0].id);
  } catch (error) {
    showLoadError();
  }
}

function bindTabs() {
  ui.tabs.forEach((tab) => {
    tab.addEventListener("click", () => activateTab(tab));
    tab.addEventListener("keydown", handleTabKey);
  });
}

async function activateTab(tab, focus = false) {
  activeTab = tab.dataset.tab;
  ui.tabs.forEach((item) => {
    const selected = item === tab;
    item.setAttribute("aria-selected", selected ? "true" : "false");
    item.tabIndex = selected ? 0 : -1;
  });
  ui.inspector.setAttribute("aria-labelledby", tab.id);
  if (focus) {
    tab.focus();
  }
  try {
    await refreshEvidence();
    renderInspector();
  } catch (error) {
    showLoadError();
  }
}

function handleTabKey(event) {
  const currentIndex = ui.tabs.indexOf(event.currentTarget);
  const lastIndex = ui.tabs.length - 1;
  const targets = {
    ArrowRight: currentIndex === lastIndex ? 0 : currentIndex + 1,
    ArrowLeft: currentIndex === 0 ? lastIndex : currentIndex - 1,
    Home: 0,
    End: lastIndex
  };
  if (targets[event.key] === undefined) {
    return;
  }
  event.preventDefault();
  activateTab(ui.tabs[targets[event.key]], true);
}

function showLoadError() {
  ui.outcomeBadge.textContent = "LOAD_FAILED";
  ui.outcomeBadge.dataset.outcome = "DENIED";
  ui.decisionHero.dataset.outcome = "DENIED";
  ui.reasonCode.textContent = "PLAYGROUND_API_UNAVAILABLE";
  ui.decisionTitle.textContent = "实时演示 API 不可用";
  ui.decisionCopy.textContent = "请通过项目 README 中的 Playground 启动命令重新打开页面。";
  ui.caseSelect.disabled = true;
  ui.error.hidden = false;
}

function populateCaseSelect() {
  cases.forEach((item) => {
    const option = element("option", "", `${item.scenario} · ${item.title}`);
    option.value = item.id;
    ui.caseSelect.append(option);
  });
}

async function selectCase(id) {
  if (!cases.some((item) => item.id === id)) {
    return;
  }
  try {
    currentFixture = await requestJson(`/api/playground/decisions/${id}`, { method: "POST" });
    ui.caseSelect.value = currentFixture.id;
    renderCurrentCase();
  } catch (error) {
    showLoadError();
  }
}

function renderCurrentCase() {
  renderSummary(currentFixture);
  renderConversation(currentFixture.conversation);
  renderTimeline(currentFixture.timeline);
  renderInspector();
}

function renderSummary(fixture) {
  const decision = outcomeCopy[fixture.outcome] || {
    title: fixture.outcome,
    copy: "查看下方时间线和证据，核对本次调用的完整决策过程。"
  };
  ui.outcomeBadge.textContent = fixture.outcome;
  ui.outcomeBadge.dataset.outcome = fixture.outcome;
  ui.decisionHero.dataset.outcome = fixture.outcome;
  ui.decisionTitle.textContent = decision.title;
  ui.decisionCopy.textContent = decision.copy;
  ui.reasonCode.textContent = fixture.reasonCode;
  ui.sideEffectCount.textContent = String(fixture.sideEffectCount);
  ui.approveButton.hidden =
      fixture.outcome !== "APPROVAL_REQUIRED" || !fixture.approvalRequestId;
}

function renderConversation(messages) {
  ui.conversation.replaceChildren();
  messages.forEach((message) => {
    const article = element("article", `message ${message.role}`);
    article.append(element("span", "message-role", message.label || message.role));
    article.append(element("p", "", message.text));
    ui.conversation.append(article);
  });
}

function renderTimeline(events) {
  ui.timeline.replaceChildren();
  events.forEach((event) => {
    const item = element("li", "timeline-item");
    item.append(element("span", "timeline-sequence", event.sequence));
    item.append(element("span", "timeline-stage", event.stage));
    item.append(element("code", "timeline-reason", event.reasonCode));
    item.append(statusPill(event.status));
    ui.timeline.append(item);
  });
}

function renderInspector() {
  if (!currentFixture) {
    return;
  }
  ui.inspector.replaceChildren();
  const renderers = {
    "tool-calls": renderToolCall,
    approvals: renderApproval,
    audit: renderAudit,
    "rag-trace": renderRagTrace,
    policies: renderPolicies,
    replay: renderReplay
  };
  ui.inspector.append(renderers[activeTab](currentFixture));
}

function renderToolCall(fixture) {
  const container = element("div");
  container.append(
      detailGrid([
        ["Tool", fixture.invocation.tool],
        ["Principal", fixture.invocation.principal],
        ["Tenant / env", `${fixture.invocation.tenant} / ${fixture.invocation.environment}`],
        ["Operation", fixture.invocation.operation],
        ["Resource", fixture.invocation.resource],
        ["Outcome", fixture.outcome],
        ["Reason", fixture.reasonCode],
        ["Executor calls", fixture.sideEffectCount]
      ]));
  container.append(
      codeGrid([
        ["Proposed arguments", fixture.invocation.arguments],
        ["Normalized arguments", fixture.invocation.normalizedArguments]
      ]));
  return container;
}

function renderApproval(fixture) {
  const approval = fixture.approval;
  const container = element("div");
  container.append(
      detailGrid([
        ["Status", approval.status],
        ["Risk", approval.riskLevel],
        ["Tool", approval.tool],
        ["Principal", approval.principal],
        ["Resource", approval.resource],
        ["Operation", approval.operation],
        ["Expiry", approval.expiry || "Not created"],
        ["Policy version", approval.policyVersion]
      ]));
  container.append(codeGrid([["Normalized arguments", approval.normalizedArguments]]));
  container.append(codeCard("Invocation fingerprint", approval.fingerprint));
  return container;
}

function renderAudit(fixture) {
  const table = element("table", "audit-table");
  const head = element("thead");
  const headRow = element("tr");
  ["Seq", "Stage", "Status", "Reason code"].forEach((text) => headRow.append(element("th", "", text)));
  head.append(headRow);
  table.append(head);
  const body = element("tbody");
  fixture.audit.forEach((event) => {
    const row = element("tr");
    [event.sequence, event.stage, event.status, event.reasonCode].forEach((value) => row.append(element("td", "mono", value)));
    body.append(row);
  });
  table.append(body);
  return table;
}

function renderRagTrace(fixture) {
  const trace = fixture.ragTrace;
  const container = element("div");
  container.append(
      detailGrid([
        ["Query", trace.query],
        ["Tenant", trace.tenant],
        ["Citations", trace.citations.length],
        ["Boundary check", trace.failure],
        ["Data source", "Synthetic fixture · future RAG integration"]
      ]));
  const list = element("ul", "document-list");
  trace.retrievedDocuments.forEach((document) => {
    const row = element("li", `document-row ${document.decision.toLowerCase()}`);
    row.append(element("span", "", `${document.id} · ${document.tenant}`));
    row.append(statusPill(document.decision));
    list.append(row);
  });
  container.append(list);
  return container;
}

function renderPolicies(fixture) {
  const policy = fixture.policy;
  const container = element("div");
  container.append(
      detailGrid([
        ["Version", policy.version],
        ["Authorization", policy.authorization],
        ["Risk", policy.riskLevel],
        ["Reason", policy.reasonCodes.join(", ")]
      ]));
  const list = element("ul", "policy-list");
  policy.rules.forEach((rule) => {
    const row = element("li", "policy-rule");
    row.append(element("span", "", rule.name));
    row.append(statusPill(rule.result));
    list.append(row);
  });
  container.append(list);
  return container;
}

function renderReplay(fixture) {
  const replay = fixture.replay;
  const container = element("div");
  const banner = element("div", "replay-status");
  const copy = element("div");
  copy.append(element("strong", "", replay.safe ? "Replay-safe view" : "Unsafe replay"));
  copy.append(element("p", "", "读取既有审计事件，不会再次调用 executor。"));
  banner.append(copy);
  banner.append(statusPill(replay.executed ? "EXECUTED" : "NOT_EXECUTED"));
  container.append(banner);
  container.append(
      detailGrid([
        ["Timeline", replay.timelineId],
        ["Executed by replay", replay.executed],
        ["Historical executor calls", replay.sideEffectCount],
        ["Safe", replay.safe]
      ]));
  container.append(codeCard("Invocation fingerprint", replay.fingerprint));
  return container;
}

async function approveCurrentCase() {
  if (!currentFixture?.approvalRequestId) {
    return;
  }
  ui.approveButton.disabled = true;
  try {
    currentFixture = await requestJson(
        `/api/playground/approvals/${currentFixture.approvalRequestId}`,
        { method: "POST" });
    renderCurrentCase();
    await activateTab(ui.tabs.find((tab) => tab.dataset.tab === "audit"));
  } catch (error) {
    showLoadError();
  } finally {
    ui.approveButton.disabled = false;
  }
}

async function refreshEvidence() {
  if (!currentFixture?.timelineId || !["audit", "replay"].includes(activeTab)) {
    return;
  }
  const path = activeTab === "audit"
      ? `/api/playground/audit/${currentFixture.timelineId}`
      : `/api/playground/replay/${currentFixture.timelineId}`;
  const evidence = await requestJson(path);
  if (activeTab === "audit") {
    currentFixture.audit = evidence.events;
  } else {
    currentFixture.replay = { ...currentFixture.replay, ...evidence };
  }
}

async function requestJson(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    throw new Error(`request failed: ${response.status}`);
  }
  return response.json();
}

function detailGrid(entries) {
  const grid = element("div", "detail-grid");
  entries.forEach(([label, value]) => {
    const card = element("div", "detail-card");
    card.append(element("span", "data-label", label));
    card.append(element("span", "data-value", value));
    grid.append(card);
  });
  return grid;
}

function codeGrid(entries) {
  const grid = element("div", "code-grid");
  entries.forEach(([label, value]) => grid.append(codeCard(label, value)));
  return grid;
}

function codeCard(label, value) {
  const card = element("div", "code-card");
  card.append(element("span", "data-label", label));
  card.append(element("pre", "", typeof value === "string" ? value : JSON.stringify(value, null, 2)));
  return card;
}

function statusPill(status) {
  const value = String(status);
  const className = value.toLowerCase().replaceAll("_", "-");
  return element("span", `status-pill ${className}`, value);
}

function element(tagName, className = "", text) {
  const node = document.createElement(tagName);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = String(text);
  }
  return node;
}

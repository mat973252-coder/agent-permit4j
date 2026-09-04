const ui = {
  caseSelect: document.querySelector("#case-select"),
  outcomeBadge: document.querySelector("#outcome-badge"),
  conversation: document.querySelector("#conversation"),
  runStatus: document.querySelector("#run-status"),
  runId: document.querySelector("#run-id"),
  runtimeContext: document.querySelector("#runtime-context"),
  approvalCheckpoint: document.querySelector("#approval-checkpoint"),
  approvalQuestion: document.querySelector("#approval-question"),
  approvalMetadata: document.querySelector("#approval-metadata"),
  approveButton: document.querySelector("#approve-button"),
  viewDetailsButton: document.querySelector("#view-details-button"),
  runDetails: document.querySelector("#run-details"),
  detailsContent: document.querySelector("#details-content"),
  error: document.querySelector("#error-state")
};

const outcomeCopy = {
  APPROVAL_REQUIRED: {
    title: "需要人工确认",
    copy: "风险策略已暂停执行，批准只对当前调用指纹生效。"
  },
  EXECUTED: {
    title: "Agent 已安全完成任务",
    copy: "策略已放行，受控执行器完成一次调用。"
  },
  DENIED: {
    title: "Agent 已停止本次运行",
    copy: "策略在副作用发生前阻断了工具调用。"
  }
};

const runStatusCopy = {
  RUNNING: "ACTIVE RUN",
  RESUMING: "ACTIVE RUN",
  APPROVAL_REQUIRED: "ACTIVE RUN",
  EXECUTED: "COMPLETED",
  DENIED: "BLOCKED",
  LOAD_FAILED: "ERROR"
};

let cases = [];
let currentFixture = null;
let selectionSequence = 0;
let evidenceTimelineId = null;

document.addEventListener("DOMContentLoaded", initialize);

async function initialize() {
  ui.caseSelect.addEventListener("change", () => selectCase(ui.caseSelect.value));
  ui.approveButton.addEventListener("click", approveCurrentCase);
  ui.viewDetailsButton.addEventListener("click", openRunDetails);
  ui.runDetails.addEventListener("toggle", loadDetailsWhenOpened);
  document.addEventListener("keydown", handleShortcut);
  try {
    cases = await requestJson("/api/playground/cases");
    if (!Array.isArray(cases) || cases.length === 0) {
      throw new Error("no playground cases available");
    }
    populateCaseSelect();
    await selectCase(cases[0].id);
  } catch (error) {
    showLoadError();
  }
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
  const requestSequence = ++selectionSequence;
  setRunStatus("RUNNING");
  hidePendingApproval();
  ui.error.hidden = true;
  try {
    const fixture = await requestJson(`/api/playground/decisions/${id}`, { method: "POST" });
    if (requestSequence !== selectionSequence) {
      return;
    }
    currentFixture = fixture;
    evidenceTimelineId = null;
    ui.runDetails.open = false;
    ui.caseSelect.value = fixture.id;
    renderCurrentCase();
  } catch (error) {
    if (requestSequence === selectionSequence) {
      showLoadError();
    }
  }
}

function renderCurrentCase() {
  renderHeader(currentFixture);
  renderTranscript(currentFixture);
  renderApproval(currentFixture);
  renderRunDetails(currentFixture);
}

function renderHeader(fixture) {
  ui.outcomeBadge.textContent = fixture.outcome;
  ui.outcomeBadge.dataset.outcome = fixture.outcome;
  ui.runId.textContent = `run=${fixture.timelineId}`;
  ui.runtimeContext.textContent =
      `workspace=${fixture.invocation.tenant} · principal=${fixture.invocation.principal} · ` +
      `tenant=${fixture.invocation.tenant} · env=${fixture.invocation.environment}`;
  setRunStatus(fixture.outcome);
}

function renderTranscript(fixture) {
  ui.conversation.replaceChildren();
  fixture.conversation.forEach((message, index) => {
    ui.conversation.append(renderMessage(message, index + 1));
  });
  const toolStep = fixture.conversation.length + 1;
  ui.conversation.append(renderToolInvocation(fixture, toolStep));
  if (fixture.outcome !== "APPROVAL_REQUIRED") {
    ui.conversation.append(renderResult(fixture, toolStep + 1));
  }
}

function renderMessage(message, step) {
  const entry = element("article", `tui-entry ${message.role}`);
  entry.append(element("span", "step-index", stepLabel(step)));
  entry.append(element("span", "entry-glyph", message.role === "user" ? "›" : "●"));
  const content = element("div", "entry-content");
  content.append(element("span", "entry-label", message.label || message.role));
  content.append(element("p", "", message.text));
  entry.append(content);
  return entry;
}

function renderToolInvocation(fixture, step) {
  const invocation = fixture.invocation;
  const entry = element("article", "tui-entry tool");
  entry.append(element("span", "step-index", stepLabel(step)));
  entry.append(element("span", "entry-glyph", "●"));
  const content = element("div", "entry-content");
  content.append(element("span", "entry-label", "Tool"));

  const toolCall = element("div", "tui-tool-call tool-table");
  const title = element("div", "tool-table-title");
  title.append(element("span", "", "Tool Call:"));
  title.append(element("strong", "tool-name", invocation.tool));
  toolCall.append(title);
  toolCall.append(toolLine("Operation", invocation.operation));
  toolCall.append(toolLine("Resource", invocation.resource));
  const argumentsView = element("details", "tool-arguments tool-table-row");
  argumentsView.append(element("summary", "", "arguments"));
  argumentsView.append(codeBlock(invocation.normalizedArguments));
  toolCall.append(argumentsView);
  content.append(toolCall);
  entry.append(content);
  return entry;
}

function renderResult(fixture, step) {
  const copy = outcomeCopy[fixture.outcome] || {
    title: fixture.outcome,
    copy: "本次运行已返回稳定决策结果。"
  };
  const entry = element("article", `tui-entry result ${fixture.outcome.toLowerCase()}`);
  entry.append(element("span", "step-index", stepLabel(step)));
  entry.append(element("span", "entry-glyph", fixture.outcome === "EXECUTED" ? "✓" : "×"));
  const content = element("div", "entry-content");
  content.append(element("span", "entry-label", "Result"));
  content.append(element("strong", "result-title", copy.title));
  content.append(element("p", "", copy.copy));
  content.append(element(
      "code",
      "result-code",
      `reason=${fixture.reasonCode} · executor_calls=${fixture.sideEffectCount}`));
  entry.append(content);
  return entry;
}

function renderApproval(fixture) {
  const awaitsApproval =
      fixture.outcome === "APPROVAL_REQUIRED" && Boolean(fixture.approvalRequestId);
  ui.approvalCheckpoint.hidden = !awaitsApproval;
  if (!awaitsApproval) {
    return;
  }
  ui.approvalQuestion.textContent = `Approval required: ${fixture.invocation.tool}`;
  const approvalStep = stepLabel(fixture.conversation.length + 2);
  ui.approvalCheckpoint.querySelectorAll(".stage-line .step-index").forEach((node) => {
    node.textContent = approvalStep;
  });
  ui.approvalMetadata.replaceChildren();
  appendDefinition(ui.approvalMetadata, "resource", fixture.invocation.resource);
  appendDefinition(ui.approvalMetadata, "operation", fixture.invocation.operation);
  appendDefinition(ui.approvalMetadata, "risk", fixture.riskLevel);
  appendDefinition(ui.approvalMetadata, "reason", fixture.reasonCode);
  ui.approveButton.disabled = false;
}

function renderRunDetails(fixture) {
  ui.detailsContent.replaceChildren();
  ui.detailsContent.append(detailSection("Invocation", [
    ["Tool", fixture.invocation.tool],
    ["Principal", fixture.invocation.principal],
    ["Tenant", fixture.invocation.tenant],
    ["Environment", fixture.invocation.environment],
    ["Resource", fixture.invocation.resource],
    ["Operation", fixture.invocation.operation]
  ]));
  ui.detailsContent.append(codeSection("Proposed arguments", fixture.invocation.arguments));
  ui.detailsContent.append(codeSection("Normalized arguments", fixture.invocation.normalizedArguments));
  ui.detailsContent.append(eventSection("Decision trace", fixture.timeline));
  ui.detailsContent.append(detailSection("Approval", [
    ["Status", fixture.approval.status],
    ["Policy version", fixture.approval.policyVersion],
    ["Fingerprint", fixture.approval.fingerprint],
    ["Expiry", fixture.approval.expiry || "Not created"]
  ]));
  ui.detailsContent.append(detailSection("Policy", [
    ["Version", fixture.policy.version],
    ["Authorization", fixture.policy.authorization],
    ["Risk", fixture.policy.riskLevel],
    ["Reason", fixture.policy.reasonCodes.join(", ")]
  ]));
  ui.detailsContent.append(eventSection("Audit", fixture.audit));
  ui.detailsContent.append(detailSection("RAG trace · synthetic fixture", [
    ["Query", fixture.ragTrace.query],
    ["Tenant", fixture.ragTrace.tenant],
    ["Boundary check", fixture.ragTrace.failure],
    ["Citations", fixture.ragTrace.citations.length]
  ]));
  ui.detailsContent.append(detailSection("Replay · read only", [
    ["Safe", fixture.replay.safe],
    ["Executed by replay", fixture.replay.executed],
    ["Historical executor calls", fixture.replay.sideEffectCount],
    ["Timeline", fixture.replay.timelineId]
  ]));
}

async function approveCurrentCase() {
  if (!currentFixture?.approvalRequestId || ui.approveButton.disabled) {
    return;
  }
  const approvalSequence = selectionSequence;
  const approvedCaseId = currentFixture.id;
  const approvalRequestId = currentFixture.approvalRequestId;
  ui.approveButton.disabled = true;
  setRunStatus("RESUMING");
  try {
    const fixture = await requestJson(
        `/api/playground/approvals/${approvalRequestId}`,
        { method: "POST" });
    if (approvalSequence !== selectionSequence ||
        currentFixture?.id !== approvedCaseId ||
        currentFixture?.approvalRequestId !== approvalRequestId) {
      return;
    }
    currentFixture = fixture;
    evidenceTimelineId = null;
    renderCurrentCase();
  } catch (error) {
    if (approvalSequence === selectionSequence) {
      setRunStatus("LOAD_FAILED");
      ui.error.hidden = false;
    }
  } finally {
    if (approvalSequence === selectionSequence &&
        currentFixture?.id === approvedCaseId &&
        currentFixture?.approvalRequestId === approvalRequestId) {
      ui.approveButton.disabled = false;
    }
  }
}

function openRunDetails() {
  if (!currentFixture) {
    return;
  }
  ui.runDetails.open = true;
  ui.runDetails.querySelector("summary").focus();
}

function loadDetailsWhenOpened() {
  if (!ui.runDetails.open || !currentFixture) {
    return;
  }
  refreshEvidence().catch(showEvidenceError);
}

async function refreshEvidence() {
  if (evidenceTimelineId === currentFixture.timelineId) {
    return;
  }
  const timelineId = currentFixture.timelineId;
  const evidenceSequence = selectionSequence;
  const [audit, replay] = await Promise.all([
    requestJson(`/api/playground/audit/${timelineId}`),
    requestJson(`/api/playground/replay/${timelineId}`)
  ]);
  if (evidenceSequence !== selectionSequence || currentFixture.timelineId !== timelineId) {
    return;
  }
  currentFixture.audit = audit.events;
  currentFixture.replay = { ...currentFixture.replay, ...replay };
  evidenceTimelineId = timelineId;
  renderRunDetails(currentFixture);
}

function handleShortcut(event) {
  if (event.target instanceof Element &&
      event.target.closest("button, select, input, textarea, summary")) {
    return;
  }
  const approvalVisible = !ui.approvalCheckpoint.hidden;
  if (approvalVisible && (event.key === "1" || event.key === "Enter")) {
    event.preventDefault();
    approveCurrentCase();
  } else if (currentFixture && event.key === "2") {
    event.preventDefault();
    openRunDetails();
  }
}

function showLoadError() {
  setRunStatus("LOAD_FAILED");
  ui.outcomeBadge.textContent = "LOAD_FAILED";
  ui.outcomeBadge.dataset.outcome = "DENIED";
  ui.approvalCheckpoint.hidden = true;
  ui.error.hidden = false;
}

function hidePendingApproval() {
  ui.approvalCheckpoint.hidden = true;
  ui.approveButton.disabled = true;
}

function showEvidenceError() {
  ui.detailsContent.prepend(
      element("p", "evidence-error", "Evidence endpoints are temporarily unavailable."));
}

function setRunStatus(status) {
  ui.runStatus.textContent = runStatusCopy[status] || status;
  ui.runStatus.dataset.status = status;
}

function detailSection(title, entries) {
  const section = element("section", "details-section");
  section.append(element("h3", "", title));
  const list = element("dl", "details-grid");
  entries.forEach(([label, value]) => appendDefinition(list, label, value));
  section.append(list);
  return section;
}

function codeSection(title, value) {
  const section = element("section", "details-section");
  section.append(element("h3", "", title));
  section.append(codeBlock(value));
  return section;
}

function eventSection(title, events) {
  const section = element("section", "details-section");
  section.append(element("h3", "", title));
  const list = element("ol", "event-list");
  events.forEach((event) => {
    const item = element("li");
    item.append(element("span", "", `${event.sequence} ${event.stage}`));
    item.append(element("code", "", event.reasonCode));
    item.append(element("span", `event-status ${String(event.status).toLowerCase()}`, event.status));
    list.append(item);
  });
  section.append(list);
  return section;
}

function appendDefinition(list, label, value) {
  list.append(element("dt", "", label));
  list.append(element("dd", "", value));
}

function toolLine(label, value) {
  const line = element("div", "tool-line tool-table-row");
  line.append(element("span", "", label));
  line.append(element("code", "", value));
  return line;
}

function stepLabel(step) {
  return String(step).padStart(2, "0");
}

function codeBlock(value) {
  return element(
      "pre",
      "",
      typeof value === "string" ? value : JSON.stringify(value, null, 2));
}

async function requestJson(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    throw new Error(`request failed: ${response.status}`);
  }
  return response.json();
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

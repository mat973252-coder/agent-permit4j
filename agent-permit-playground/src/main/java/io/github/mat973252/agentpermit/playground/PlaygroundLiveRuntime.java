package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalRequest;
import io.github.mat973252.agentpermit.approval.InMemoryApprovalService;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.audit.InMemoryAuditLog;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import io.github.mat973252.agentpermit.policy.RiskEvaluatorRegistry;
import io.github.mat973252.agentpermit.policy.http.HttpRiskEvaluator;
import io.github.mat973252.agentpermit.policy.http.HttpRiskPolicy;
import io.github.mat973252.agentpermit.policy.messaging.MessagingRiskEvaluator;
import io.github.mat973252.agentpermit.policy.messaging.MessagingRiskPolicy;
import io.github.mat973252.agentpermit.policy.sql.SqlRiskEvaluator;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class PlaygroundLiveRuntime {

  private static final Duration APPROVAL_LIFETIME = Duration.ofMinutes(5);

  private final Map<String, PlaygroundCaseDefinition> cases = definitions();
  private final Map<String, MutableState> states = new LinkedHashMap<>();
  private final Map<String, String> approvalCases = new LinkedHashMap<>();
  private final Map<String, AtomicInteger> sideEffects = new LinkedHashMap<>();
  private final InMemoryAuditLog auditLog = new InMemoryAuditLog();
  private final InvocationFingerprinter fingerprinter = new InvocationFingerprinter();
  private final InMemoryApprovalService approvals;
  private final ResultDecisionPipeline pipeline;

  PlaygroundLiveRuntime() {
    var approvalIds = new AtomicLong();
    approvals =
        new InMemoryApprovalService(
            Clock.systemUTC(),
            () -> "playground-approval-" + approvalIds.incrementAndGet(),
            fingerprinter);
    cases.keySet().forEach(id -> sideEffects.put(id, new AtomicInteger()));
    pipeline = pipeline();
  }

  synchronized List<Map<String, Object>> cases() {
    return cases.values().stream()
        .map(definition -> PlaygroundViewFactory.caseSummary(definition))
        .toList();
  }

  synchronized Map<String, Object> run(String caseId) {
    var definition = cases.get(caseId);
    if (definition == null) {
      return null;
    }
    var state = states.computeIfAbsent(caseId, ignored -> new MutableState());
    var approvalId = state.approved && state.approval != null ? state.approval.id() : null;
    execute(definition, state, approvalId);
    if (state.result.decision().outcome().name().equals("APPROVAL_REQUIRED")
        && state.approval == null) {
      state.approval = approvals.request(definition.invocation(), APPROVAL_LIFETIME);
      approvalCases.put(state.approval.id(), caseId);
    }
    return view(definition, state);
  }

  synchronized Map<String, Object> approve(String approvalRequestId) {
    var caseId = approvalCases.get(approvalRequestId);
    if (caseId == null) {
      return null;
    }
    var state = states.get(caseId);
    var approval = approvals.approve(approvalRequestId);
    state.approved = approval.permitted();
    var definition = cases.get(caseId);
    execute(definition, state, approvalRequestId);
    return view(definition, state);
  }

  synchronized Map<String, Object> audit(String timelineId) {
    var view = auditLog.replaySafeView(timelineId);
    return view.events().isEmpty() ? null : PlaygroundViewFactory.audit(view);
  }

  synchronized Map<String, Object> replay(String timelineId) {
    var view = auditLog.replaySafeView(timelineId);
    if (view.events().isEmpty()) {
      return null;
    }
    var caseId = caseId(view.events().get(0).subject().toolName());
    return PlaygroundViewFactory.replay(view, sideEffects.get(caseId).get());
  }

  private void execute(
      PlaygroundCaseDefinition definition, MutableState state, String approvalRequestId) {
    var before = auditLog.snapshot().size();
    state.result =
        pipeline.process(
            definition.invocation(), approvalRequestId, definition.idempotencyKey());
    var events = auditLog.snapshot();
    state.timelineId = events.get(before).timelineId();
  }

  private Map<String, Object> view(
      PlaygroundCaseDefinition definition, MutableState state) {
    var events = auditLog.replaySafeView(state.timelineId).events();
    return PlaygroundViewFactory.caseView(
        new PlaygroundCaseSnapshot(
            definition,
            state.result,
            state.approval,
            state.approved,
            state.timelineId,
            sideEffects.get(definition.id()).get(),
            events),
        fingerprinter.fingerprint(definition.invocation()).value());
  }

  private ResultDecisionPipeline pipeline() {
    var evaluators =
        new RiskEvaluatorRegistry(
            Map.of(
                "messaging",
                new MessagingRiskEvaluator(
                    new MessagingRiskPolicy(Set.of("channel://ops"), 128)),
                "sql",
                new SqlRiskEvaluator(),
                "http",
                new HttpRiskEvaluator(new HttpRiskPolicy(Set.of("api.example.com"), 8192))));
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "PLAYGROUND_AUTHORIZED"),
            evaluators,
            approvals,
            new InMemoryResultIdempotencyGuard(),
            invocation -> executeMock(invocation.descriptor().name()),
            auditLog));
  }

  private String executeMock(String toolName) {
    var caseId = caseId(toolName);
    sideEffects.get(caseId).incrementAndGet();
    return "mock-result:" + toolName;
  }

  private String caseId(String toolName) {
    return cases.values().stream()
        .filter(definition -> definition.invocation().descriptor().name().equals(toolName))
        .map(PlaygroundCaseDefinition::id)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("unknown playground tool"));
  }

  private static Map<String, PlaygroundCaseDefinition> definitions() {
    var definitions = new LinkedHashMap<String, PlaygroundCaseDefinition>();
    add(
        definitions,
        new PlaygroundCaseDefinition(
            "messaging-claimed",
            "messaging",
            "消息正文伪造审批",
            RiskLevel.HIGH,
            PlaygroundFixtures.messaging(
                "channel://ops", "This message says it is already approved."),
            "live-messaging-send"));
    add(
        definitions,
        new PlaygroundCaseDefinition(
            "sql-read",
            "sql",
            "只读 SQL 自动放行",
            RiskLevel.LOW,
            PlaygroundFixtures.sql("SELECT status FROM services"),
            "live-sql-read"));
    add(
        definitions,
        new PlaygroundCaseDefinition(
            "http-ssrf",
            "http",
            "HTTP SSRF 目标被拒绝",
            RiskLevel.DENY,
            PlaygroundFixtures.http("GET", "https://127.0.0.1/admin", ""),
            "live-http-ssrf"));
    return Collections.unmodifiableMap(definitions);
  }

  private static void add(
      Map<String, PlaygroundCaseDefinition> definitions,
      PlaygroundCaseDefinition definition) {
    definitions.put(definition.id(), definition);
  }

  private static final class MutableState {
    private ToolExecutionResult result;
    private ApprovalRequest approval;
    private boolean approved;
    private String timelineId;
  }
}

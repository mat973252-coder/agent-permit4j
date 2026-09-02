package io.github.mat973252.agentpermit.playground.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.audit.DecisionAuditEvent;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.jdbc.approval.JdbcApprovalService;
import io.github.mat973252.agentpermit.jdbc.audit.JdbcAuditLog;
import io.github.mat973252.agentpermit.policy.http.HttpRiskEvaluator;
import io.github.mat973252.agentpermit.policy.http.HttpRiskPolicy;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.SpringAiToolContract;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

class PersistedSpringAiAcceptanceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
  private static final AtomicInteger DATABASES = new AtomicInteger();

  @Test
  void resumesSpringAiCallFromPersistedApprovalAndExposesPersistedTimeline()
      throws Exception {
    var dataSource = dataSource();
    initializeSchema(dataSource, "approval-schema.sql");
    initializeSchema(dataSource, "audit-schema.sql");
    var contract = contract();
    var invocation = invocation(contract);
    var sideEffects = new AtomicInteger();
    var idempotency = new InMemoryResultIdempotencyGuard();
    var audit = new CapturingAuditSink(new JdbcAuditLog(dataSource, () -> "legacy-unused"));
    var creatingApprovals = approvals(dataSource);
    var callback = callback(contract, creatingApprovals, idempotency, audit, sideEffects);
    var input = "{\"uri\":\"https://api.example.com/items\",\"method\":\"POST\",\"payload\":\"{}\"}";

    var pending = callback.call(input, context(null, "persisted-write"));
    var request = creatingApprovals.request(invocation, Duration.ofMinutes(5));
    var approvingService = approvals(dataSource);
    var approval = approvingService.approve(request.id());
    var resumedApprovals = approvals(dataSource);
    var resumedCallback = callback(contract, resumedApprovals, idempotency, audit, sideEffects);
    audit.clear();
    var executed = resumedCallback.call(input, context(request.id(), "persisted-write"));
    var timelineId = audit.timelineId();
    var replay = new JdbcAuditLog(dataSource, () -> "legacy-unused").replaySafeView(timelineId);
    var retry = resumedCallback.call(input, context(request.id(), "persisted-write"));

    assertTrue(pending.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertEquals("APPROVAL_APPROVED", approval.reasonCode());
    assertEquals(
        "{\"outcome\":\"EXECUTED\",\"reasonCode\":\"HTTP_WRITE\","
            + "\"output\":\"api-response-1\"}",
        executed);
    assertEquals(executed, retry);
    assertEquals(1, sideEffects.get());
    assertEquals(
        java.util.List.of(
            AuditStage.POLICY,
            AuditStage.RISK,
            AuditStage.APPROVAL,
            AuditStage.EXECUTION,
            AuditStage.RESULT),
        replay.events().stream().map(AuditEvent::stage).toList());
  }

  private static GuardedToolCallback callback(
      SpringAiToolContract contract,
      JdbcApprovalService approvals,
      InMemoryResultIdempotencyGuard idempotency,
      AuditSink audit,
      AtomicInteger sideEffects) {
    var pipeline =
        new ResultDecisionPipeline(
            new ResultDecisionPipeline.Dependencies(
                value -> new GateDecision(true, "VALIDATED"),
                value -> value,
                value -> new GateDecision(true, "HTTP_AUTHORIZED"),
                new HttpRiskEvaluator(new HttpRiskPolicy(Set.of("api.example.com"), 1024)),
                approvals,
                idempotency,
                value -> "api-response-" + sideEffects.incrementAndGet(),
                audit));
    var definition =
        ToolDefinition.builder()
            .name("http.request")
            .description("Call an approved external HTTP API")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new GuardedToolCallback(definition, pipeline, contract);
  }

  private static JdbcApprovalService approvals(JdbcDataSource dataSource) {
    return new JdbcApprovalService(
        dataSource, CLOCK, () -> "persisted-approval", new InvocationFingerprinter());
  }

  private static SpringAiToolContract contract() {
    return new SpringAiToolContract(
        new ToolDescriptor(
            "http.request",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action("http.request"),
        "http",
        "uri");
  }

  private static ToolInvocation invocation(SpringAiToolContract contract) {
    return new ToolInvocation(
        contract.descriptor(),
        new Principal("workspace-agent", Map.of()),
        contract.action(),
        new Resource("http", "https://api.example.com/items", Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("uri", "https://api.example.com/items", "method", "POST", "payload", "{}"));
  }

  private static ToolContext context(String approvalRequestId, String idempotencyKey) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "workspace-agent");
    values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    values.put(SpringAiToolContextKeys.ENVIRONMENT, "production");
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (approvalRequestId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalRequestId);
    }
    return new ToolContext(Map.copyOf(values));
  }

  private static JdbcDataSource dataSource() {
    var dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:p1-acceptance-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  private static void initializeSchema(JdbcDataSource dataSource, String resource)
      throws Exception {
    try (var input =
        PersistedSpringAiAcceptanceTest.class
            .getClassLoader()
            .getResourceAsStream("io/github/mat973252/agentpermit/jdbc/" + resource)) {
      assertNotNull(input);
      var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }
  }

  private static final class CapturingAuditSink implements AuditSink {

    private final JdbcAuditLog delegate;
    private String timelineId;

    private CapturingAuditSink(JdbcAuditLog delegate) {
      this.delegate = delegate;
    }

    @Override
    public void record(DecisionAuditEvent event) {
      delegate.record(event);
    }

    @Override
    public void record(AuditEvent event) {
      timelineId = event.timelineId();
      delegate.record(event);
    }

    private void clear() {
      timelineId = null;
    }

    private String timelineId() {
      return timelineId;
    }
  }
}

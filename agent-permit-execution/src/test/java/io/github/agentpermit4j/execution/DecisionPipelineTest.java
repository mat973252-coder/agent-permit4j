package io.github.agentpermit4j.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.agentpermit4j.audit.DecisionAuditEvent;
import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DecisionPipelineTest {

  @Test
  void executesLowRiskInvocationInDeterministicOrder() {
    var stages = new ArrayList<String>();
    var events = new ArrayList<DecisionAuditEvent>();
    var original = invocation(" select * from orders ");
    var normalized = invocation("SELECT * FROM orders");
    var pipeline =
        new DecisionPipeline(
            new DecisionPipeline.Dependencies(
                invocation -> record(stages, "validate", new GateDecision(true, "VALIDATED")),
                invocation -> record(stages, "normalize", normalized),
                invocation -> record(stages, "authorize", new GateDecision(true, "AUTHORIZED")),
                invocation ->
                    record(
                        stages,
                        "risk",
                        new RiskAssessment(RiskLevel.LOW, "SQL_READ_ONLY")),
                invocation -> stages.add("execute"),
                event -> {
                  stages.add("audit");
                  events.add(event);
                }));

    var decision = pipeline.process(original);

    assertAll(
        () -> assertEquals(DecisionOutcome.EXECUTED, decision.outcome()),
        () -> assertEquals("SQL_READ_ONLY", decision.reasonCode()),
        () ->
            assertEquals(
                List.of("validate", "normalize", "authorize", "risk", "execute", "audit"),
                stages),
        () -> assertEquals(1, events.size()),
        () -> assertSame(decision, events.getFirst().decision()),
        () -> assertEquals("database.sql", events.getFirst().toolName()));
  }

  @Test
  void deniesInvalidInvocationWithoutCallingDownstreamStages() {
    var downstreamCalls = new AtomicInteger();
    var events = new ArrayList<DecisionAuditEvent>();
    var pipeline =
        pipeline(
            invocation -> new GateDecision(false, "SCHEMA_INVALID"),
            invocation -> countAndReturn(downstreamCalls, invocation),
            invocation ->
                countAndReturn(downstreamCalls, new GateDecision(true, "AUTHORIZED")),
            invocation ->
                countAndReturn(
                    downstreamCalls, new RiskAssessment(RiskLevel.LOW, "SQL_READ_ONLY")),
            invocation -> downstreamCalls.incrementAndGet(),
            events::add);

    var decision = pipeline.process(invocation("SELECT 1"));

    assertAll(
        () -> assertEquals(DecisionOutcome.DENIED, decision.outcome()),
        () -> assertEquals("SCHEMA_INVALID", decision.reasonCode()),
        () -> assertEquals(0, downstreamCalls.get()),
        () -> assertEquals(1, events.size()));
  }

  @Test
  void deniesUnauthorizedInvocationBeforeRiskEvaluation() {
    var riskAndExecutionCalls = new AtomicInteger();
    var events = new ArrayList<DecisionAuditEvent>();
    var pipeline =
        pipeline(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(false, "PROTECTED_PATH"),
            invocation ->
                countAndReturn(
                    riskAndExecutionCalls,
                    new RiskAssessment(RiskLevel.LOW, "SQL_READ_ONLY")),
            invocation -> riskAndExecutionCalls.incrementAndGet(),
            events::add);

    var decision = pipeline.process(invocation("SELECT 1"));

    assertAll(
        () -> assertEquals(DecisionOutcome.DENIED, decision.outcome()),
        () -> assertEquals("PROTECTED_PATH", decision.reasonCode()),
        () -> assertEquals(0, riskAndExecutionCalls.get()),
        () -> assertEquals(1, events.size()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonExecutableRisks")
  void mapsNonExecutableRiskToTerminalDecision(
      String scenario, RiskAssessment risk, DecisionOutcome expectedOutcome) {
    var executions = new AtomicInteger();
    var events = new ArrayList<DecisionAuditEvent>();
    var pipeline =
        pipeline(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> risk,
            invocation -> executions.incrementAndGet(),
            events::add);

    var decision = pipeline.process(invocation("UPDATE orders SET status = 'PAID'"));

    assertAll(
        () -> assertEquals(expectedOutcome, decision.outcome()),
        () -> assertEquals(risk.reasonCode(), decision.reasonCode()),
        () -> assertEquals(0, executions.get()),
        () -> assertEquals(1, events.size()));
  }

  @Test
  void convertsExecutionFailureToAuditedStructuredDecision() {
    var events = new ArrayList<DecisionAuditEvent>();
    var pipeline =
        pipeline(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "SQL_READ_ONLY"),
            invocation -> {
              throw new IllegalStateException("database password leaked by driver");
            },
            events::add);

    var decision = pipeline.process(invocation("SELECT 1"));

    assertAll(
        () -> assertEquals(DecisionOutcome.FAILED, decision.outcome()),
        () -> assertEquals("EXECUTION_FAILED", decision.reasonCode()),
        () -> assertEquals(1, events.size()),
        () -> assertEquals("EXECUTION_FAILED", events.getFirst().decision().reasonCode()));
  }

  private static Stream<Arguments> nonExecutableRisks() {
    return Stream.of(
        Arguments.of(
            "high risk requires approval",
            new RiskAssessment(RiskLevel.HIGH, "SQL_SELECTIVE_UPDATE"),
            DecisionOutcome.APPROVAL_REQUIRED),
        Arguments.of(
            "critical risk requires approval",
            new RiskAssessment(RiskLevel.CRITICAL, "SQL_UNBOUNDED_UPDATE"),
            DecisionOutcome.APPROVAL_REQUIRED),
        Arguments.of(
            "denied risk stays denied",
            new RiskAssessment(RiskLevel.DENY, "SQL_DESTRUCTIVE"),
            DecisionOutcome.DENIED));
  }

  private static DecisionPipeline pipeline(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      io.github.agentpermit4j.policy.Authorizer authorizer,
      io.github.agentpermit4j.policy.RiskEvaluator riskEvaluator,
      ToolExecutor executor,
      io.github.agentpermit4j.audit.AuditSink auditSink) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            validator, normalizer, authorizer, riskEvaluator, executor, auditSink));
  }

  private static ToolInvocation invocation(String statement) {
    return new ToolInvocation(
        new ToolDescriptor(
            "database.sql",
            ToolEffect.WRITE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.CONFIDENTIAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action("sql.execute"),
        new Resource("sql", "db://main", Map.of()),
        new InvocationContext("tenant-a", "test"),
        Map.of("statement", statement));
  }

  private static <T> T record(List<String> stages, String stage, T value) {
    stages.add(stage);
    return value;
  }

  private static <T> T countAndReturn(AtomicInteger counter, T value) {
    counter.incrementAndGet();
    return value;
  }
}

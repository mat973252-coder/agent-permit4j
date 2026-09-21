package io.github.agentpermit4j.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.agentpermit4j.audit.AuditStage;
import io.github.agentpermit4j.audit.InMemoryAuditLog;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuditTimelineAcceptanceTest {

  @Test
  void approvedExecutionProducesCompleteReplaySafeTimeline() {
    var executions = new AtomicInteger();
    var log = new InMemoryAuditLog();
    var pipeline = pipeline(log, executions, true);

    var decision = pipeline.process(invocation(), "approval-1", "deploy-42");
    var timelineId = log.snapshot().getFirst().timelineId();
    var firstView = log.replaySafeView(timelineId);
    var secondView = log.replaySafeView(timelineId);

    assertAll(
        () -> assertEquals(DecisionOutcome.EXECUTED, decision.outcome()),
        () ->
            assertEquals(
                List.of(
                    AuditStage.POLICY,
                    AuditStage.RISK,
                    AuditStage.APPROVAL,
                    AuditStage.EXECUTION,
                    AuditStage.RESULT),
                firstView.events().stream().map(event -> event.stage()).toList()),
        () -> assertEquals(firstView, secondView),
        () -> assertEquals(1, executions.get()),
        () -> assertEquals(5, log.snapshot().size()),
        () -> assertFalse(firstView.toString().contains("super-secret-version")));
  }

  @Test
  void approvalRequiredTimelineOmitsExecutionAndHasNoSideEffect() {
    var executions = new AtomicInteger();
    var log = new InMemoryAuditLog();
    var pipeline = pipeline(log, executions, false);

    var decision = pipeline.process(invocation(), "approval-1", "deploy-42");
    var timelineId = log.snapshot().getFirst().timelineId();

    assertAll(
        () -> assertEquals(DecisionOutcome.APPROVAL_REQUIRED, decision.outcome()),
        () ->
            assertEquals(
                List.of(AuditStage.POLICY, AuditStage.RISK, AuditStage.APPROVAL, AuditStage.RESULT),
                log.replaySafeView(timelineId).events().stream()
                    .map(event -> event.stage())
                    .toList()),
        () -> assertEquals(0, executions.get()));
  }

  private static DecisionPipeline pipeline(
      InMemoryAuditLog log, AtomicInteger executions, boolean approved) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.HIGH, "DEPLOYMENT_PRODUCTION"),
            (requestId, invocation) ->
                new GateDecision(
                    approved,
                    approved ? "APPROVAL_VALID" : "APPROVAL_INVOCATION_MISMATCH"),
            new InMemoryIdempotencyGuard(),
            invocation -> executions.incrementAndGet(),
            log));
  }

  private static ToolInvocation invocation() {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
        new Action("deployment.apply"),
        new Resource("deployment", "service://checkout", Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("version", "super-secret-version"));
  }
}

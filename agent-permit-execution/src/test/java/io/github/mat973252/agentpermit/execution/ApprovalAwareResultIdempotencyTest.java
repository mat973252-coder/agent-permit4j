package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mat973252.agentpermit.audit.InMemoryAuditLog;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ApprovalAwareResultIdempotencyTest {

  @Test
  void approvedExecutionPassesApprovalIdToIdempotencyBoundary() {
    var guard = new CapturingGuard();
    var pipeline = pipeline(RiskLevel.HIGH, guard);

    var result = pipeline.process(invocation(), "approval-1", "key-1");

    assertEquals(DecisionOutcome.EXECUTED, result.decision().outcome());
    assertEquals("approval-1", guard.approvalRequestId.get());
  }

  @Test
  void lowRiskExecutionDoesNotConsumeAnUnusedApprovalId() {
    var guard = new CapturingGuard();
    var pipeline = pipeline(RiskLevel.LOW, guard);

    var result = pipeline.process(invocation(), "unused-approval", "key-1");

    assertEquals(DecisionOutcome.EXECUTED, result.decision().outcome());
    assertNull(guard.approvalRequestId.get());
  }

  @Test
  void approvedExecutionFailsClosedWhenGuardCannotConsumeApproval() {
    ResultIdempotencyGuard legacyGuard =
        (key, invocation, sideEffect) -> sideEffect.get();
    var pipeline = pipeline(RiskLevel.HIGH, legacyGuard);

    var result = pipeline.process(invocation(), "approval-1", "key-1");

    assertEquals(DecisionOutcome.FAILED, result.decision().outcome());
    assertEquals("APPROVAL_CONSUMPTION_UNAVAILABLE", result.decision().reasonCode());
    assertNull(result.output());
  }

  @Test
  void inMemoryGuardBindsApprovalToOneIdempotencyKey() {
    var guard = new InMemoryResultIdempotencyGuard();
    var executions = new AtomicInteger();
    Supplier<ToolExecutionResult> sideEffect =
        () ->
            new ToolExecutionResult(
                new DecisionResult(DecisionOutcome.EXECUTED, "DEPLOYMENT_SAFE"),
                "output-" + executions.incrementAndGet());

    var first = guard.executeOnce("key-1", invocation(), "approval-1", sideEffect);
    var retry = guard.executeOnce("key-1", invocation(), "approval-1", sideEffect);
    var conflict = guard.executeOnce("key-2", invocation(), "approval-1", sideEffect);

    assertEquals(first, retry);
    assertEquals(1, executions.get());
    assertEquals(DecisionOutcome.APPROVAL_REQUIRED, conflict.decision().outcome());
    assertEquals("APPROVAL_ALREADY_CONSUMED", conflict.decision().reasonCode());
    assertNull(conflict.output());
  }

  private static ResultDecisionPipeline pipeline(
      RiskLevel riskLevel, ResultIdempotencyGuard guard) {
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(riskLevel, "RISK_RESULT"),
            (requestId, invocation) -> new GateDecision(true, "APPROVAL_VALID"),
            guard,
            invocation -> "output",
            new InMemoryAuditLog()));
  }

  private static ToolInvocation invocation() {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of()),
        new Action("deployment.apply"),
        new Resource("deployment", "service://checkout", Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("version", "1.2.3"));
  }

  private static final class CapturingGuard implements ResultIdempotencyGuard {

    private final AtomicReference<String> approvalRequestId = new AtomicReference<>();

    @Override
    public ToolExecutionResult executeOnce(
        String key,
        ToolInvocation normalizedInvocation,
        Supplier<ToolExecutionResult> sideEffect) {
      throw new AssertionError("approval-aware overload was not used");
    }

    @Override
    public ToolExecutionResult executeOnce(
        String key,
        ToolInvocation normalizedInvocation,
        String approvalRequestId,
        Supplier<ToolExecutionResult> sideEffect) {
      this.approvalRequestId.set(approvalRequestId);
      return sideEffect.get();
    }
  }
}

package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mat973252.agentpermit.approval.InMemoryApprovalService;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApprovalFingerprintAcceptanceTest {

  @ParameterizedTest(name = "invalidates approval after {0} changes")
  @MethodSource("changedInvocations")
  void changedInvocationCannotReuseApproval(
      String changedField, ToolInvocation changedInvocation) {
    var executions = new AtomicInteger();
    var approvals = approvals();
    var original = invocation("service://checkout", "1.2.3");
    var request = approvals.request(original, Duration.ofMinutes(5));
    approvals.approve(request.id());
    var pipeline = pipeline(approvals, executions, invocation -> invocation);

    var decision = pipeline.process(changedInvocation, request.id());

    assertAll(
        () -> assertEquals(DecisionOutcome.APPROVAL_REQUIRED, decision.outcome()),
        () -> assertEquals("APPROVAL_INVOCATION_MISMATCH", decision.reasonCode()),
        () -> assertEquals(0, executions.get()));
  }

  @Test
  void verifiesApprovalAgainstNormalizedInvocation() {
    var executions = new AtomicInteger();
    var approvals = approvals();
    var normalized = invocation("service://checkout", "1.2.3");
    var request = approvals.request(normalized, Duration.ofMinutes(5));
    approvals.approve(request.id());
    var pipeline = pipeline(approvals, executions, invocation -> normalized);

    var decision = pipeline.process(invocation("service://checkout", " 1.2.3 "), request.id());

    assertAll(
        () -> assertEquals(DecisionOutcome.EXECUTED, decision.outcome()),
        () -> assertEquals(1, executions.get()));
  }

  private static Stream<Arguments> changedInvocations() {
    return Stream.of(
        Arguments.of("service", invocation("service://billing", "1.2.3")),
        Arguments.of("version", invocation("service://checkout", "2.0.0")));
  }

  private static DecisionPipeline pipeline(
      InMemoryApprovalService approvals,
      AtomicInteger executions,
      InvocationNormalizer normalizer) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            normalizer,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.HIGH, "DEPLOYMENT_PRODUCTION"),
            approvals,
            invocation -> executions.incrementAndGet(),
            event -> {}));
  }

  private static InMemoryApprovalService approvals() {
    return new InMemoryApprovalService(
        Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
        () -> "approval-1",
        new InvocationFingerprinter());
  }

  private static ToolInvocation invocation(String service, String version) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
        new Action("deployment.apply"),
        new Resource("deployment", service, Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("version", version));
  }
}

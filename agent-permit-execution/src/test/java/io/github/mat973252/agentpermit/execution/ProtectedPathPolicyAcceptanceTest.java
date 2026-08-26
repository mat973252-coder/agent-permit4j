package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import io.github.mat973252.agentpermit.policy.file.ProtectedPathAuthorizer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtectedPathPolicyAcceptanceTest {

  @Test
  void protectedWorkspaceDeletionProducesZeroExecutions() {
    var executions = new AtomicInteger();
    var pipeline =
        new DecisionPipeline(
            new DecisionPipeline.Dependencies(
                invocation -> new GateDecision(true, "VALIDATED"),
                invocation -> invocation,
                new ProtectedPathAuthorizer(),
                invocation -> new RiskAssessment(RiskLevel.LOW, "FILE_OPERATION_ALLOWED"),
                invocation -> executions.incrementAndGet(),
                event -> {}));

    var decision = pipeline.process(recursiveWorkspaceDelete());

    assertAll(
        () -> assertEquals(DecisionOutcome.DENIED, decision.outcome()),
        () -> assertEquals("PROTECTED_PATH", decision.reasonCode()),
        () -> assertEquals(0, executions.get()));
  }

  private static ToolInvocation recursiveWorkspaceDelete() {
    return new ToolInvocation(
        new ToolDescriptor(
            "workspace.file",
            ToolEffect.DELETE,
            Reversibility.IRREVERSIBLE,
            DataSensitivity.CONFIDENTIAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action("file.delete"),
        new Resource("file", "/workspace", Map.of()),
        new InvocationContext("tenant-a", "test"),
        Map.of("recursive", "true"));
  }
}

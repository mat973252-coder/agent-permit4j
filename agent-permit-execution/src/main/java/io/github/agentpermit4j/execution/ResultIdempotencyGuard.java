package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.function.Supplier;

@FunctionalInterface
public interface ResultIdempotencyGuard {

  ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      Supplier<ToolExecutionResult> sideEffect);

  default ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      String approvalRequestId,
      Supplier<ToolExecutionResult> sideEffect) {
    if (approvalRequestId != null) {
      return new ToolExecutionResult(
          new DecisionResult(
              DecisionOutcome.FAILED, "APPROVAL_CONSUMPTION_UNAVAILABLE"),
          null);
    }
    return executeOnce(key, normalizedInvocation, sideEffect);
  }
}

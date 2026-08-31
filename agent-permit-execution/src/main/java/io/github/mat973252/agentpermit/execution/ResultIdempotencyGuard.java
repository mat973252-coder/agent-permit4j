package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
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

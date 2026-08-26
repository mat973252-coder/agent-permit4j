package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.function.Supplier;

@FunctionalInterface
public interface IdempotencyGuard {

  DecisionResult executeOnce(
      String key, ToolInvocation normalizedInvocation, Supplier<DecisionResult> sideEffect);
}

package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.function.Supplier;

@FunctionalInterface
public interface IdempotencyGuard {

  DecisionResult executeOnce(
      String key, ToolInvocation normalizedInvocation, Supplier<DecisionResult> sideEffect);
}

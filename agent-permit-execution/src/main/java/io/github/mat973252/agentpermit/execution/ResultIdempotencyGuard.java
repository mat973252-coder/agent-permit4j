package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.function.Supplier;

@FunctionalInterface
public interface ResultIdempotencyGuard {

  ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      Supplier<ToolExecutionResult> sideEffect);
}

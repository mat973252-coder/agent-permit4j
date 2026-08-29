package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;
import java.util.function.Supplier;

public final class InMemoryIdempotencyGuard implements IdempotencyGuard {

  private final InMemoryIdempotencyCoordinator<DecisionResult> coordinator;

  public InMemoryIdempotencyGuard() {
    this(new InvocationFingerprinter());
  }

  public InMemoryIdempotencyGuard(InvocationFingerprinter fingerprinter) {
    coordinator = new InMemoryIdempotencyCoordinator<>(fingerprinter);
  }

  @Override
  public DecisionResult executeOnce(
      String key, ToolInvocation normalizedInvocation, Supplier<DecisionResult> sideEffect) {
    Objects.requireNonNull(sideEffect, "sideEffect");
    return coordinator.executeOnce(
        key,
        normalizedInvocation,
        sideEffect,
        () -> new DecisionResult(DecisionOutcome.DENIED, "IDEMPOTENCY_INVOCATION_MISMATCH"),
        () -> new DecisionResult(DecisionOutcome.FAILED, "EXECUTION_FAILED"));
  }
}

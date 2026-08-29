package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;
import java.util.function.Supplier;

public final class InMemoryResultIdempotencyGuard implements ResultIdempotencyGuard {

  private final InMemoryIdempotencyCoordinator<ToolExecutionResult> coordinator;

  public InMemoryResultIdempotencyGuard() {
    this(new InvocationFingerprinter());
  }

  public InMemoryResultIdempotencyGuard(InvocationFingerprinter fingerprinter) {
    coordinator = new InMemoryIdempotencyCoordinator<>(fingerprinter);
  }

  @Override
  public ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      Supplier<ToolExecutionResult> sideEffect) {
    Objects.requireNonNull(sideEffect, "sideEffect");
    return coordinator.executeOnce(
        key,
        normalizedInvocation,
        sideEffect,
        () -> terminal(DecisionOutcome.DENIED, "IDEMPOTENCY_INVOCATION_MISMATCH"),
        () -> terminal(DecisionOutcome.FAILED, "EXECUTION_FAILED"));
  }

  private static ToolExecutionResult terminal(DecisionOutcome outcome, String reasonCode) {
    return new ToolExecutionResult(new DecisionResult(outcome, reasonCode), null);
  }
}

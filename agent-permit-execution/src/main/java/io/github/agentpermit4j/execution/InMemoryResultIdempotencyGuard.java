package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public final class InMemoryResultIdempotencyGuard implements ResultIdempotencyGuard {

  private final InMemoryIdempotencyCoordinator<ToolExecutionResult> coordinator;
  private final InvocationFingerprinter fingerprinter;
  private final ConcurrentMap<String, ApprovalBinding> approvalBindings =
      new ConcurrentHashMap<>();

  public InMemoryResultIdempotencyGuard() {
    this(new InvocationFingerprinter());
  }

  public InMemoryResultIdempotencyGuard(InvocationFingerprinter fingerprinter) {
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
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

  @Override
  public ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      String approvalRequestId,
      Supplier<ToolExecutionResult> sideEffect) {
    if (approvalRequestId == null) {
      return executeOnce(key, normalizedInvocation, sideEffect);
    }
    requireNonBlank(key, "idempotency key");
    requireNonBlank(approvalRequestId, "approvalRequestId");
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    Objects.requireNonNull(sideEffect, "sideEffect");
    var candidate =
        new ApprovalBinding(
            key, fingerprinter.fingerprint(normalizedInvocation).value());
    var existing = approvalBindings.putIfAbsent(approvalRequestId, candidate);
    if (existing != null && !existing.key().equals(candidate.key())) {
      return terminal(DecisionOutcome.APPROVAL_REQUIRED, "APPROVAL_ALREADY_CONSUMED");
    }
    if (existing != null && !existing.fingerprint().equals(candidate.fingerprint())) {
      return terminal(DecisionOutcome.DENIED, "IDEMPOTENCY_INVOCATION_MISMATCH");
    }
    return executeOnce(key, normalizedInvocation, sideEffect);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static ToolExecutionResult terminal(DecisionOutcome outcome, String reasonCode) {
    return new ToolExecutionResult(new DecisionResult(outcome, reasonCode), null);
  }

  private record ApprovalBinding(String key, String fingerprint) {}
}

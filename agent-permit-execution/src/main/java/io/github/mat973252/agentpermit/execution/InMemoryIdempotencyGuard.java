package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.approval.InvocationFingerprint;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public final class InMemoryIdempotencyGuard implements IdempotencyGuard {

  private final InvocationFingerprinter fingerprinter;
  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

  public InMemoryIdempotencyGuard() {
    this(new InvocationFingerprinter());
  }

  public InMemoryIdempotencyGuard(InvocationFingerprinter fingerprinter) {
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
  }

  @Override
  public DecisionResult executeOnce(
      String key, ToolInvocation normalizedInvocation, Supplier<DecisionResult> sideEffect) {
    requireKey(key);
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    Objects.requireNonNull(sideEffect, "sideEffect");
    var fingerprint = fingerprinter.fingerprint(normalizedInvocation);
    var candidate = new Entry(fingerprint, new CompletableFuture<>());
    var existing = entries.putIfAbsent(key, candidate);
    if (existing == null) {
      return executeOwner(candidate, sideEffect);
    }
    if (!existing.fingerprint().equals(fingerprint)) {
      return new DecisionResult(DecisionOutcome.DENIED, "IDEMPOTENCY_INVOCATION_MISMATCH");
    }
    return existing.result().join();
  }

  private DecisionResult executeOwner(Entry entry, Supplier<DecisionResult> sideEffect) {
    DecisionResult result;
    try {
      result = Objects.requireNonNull(sideEffect.get(), "sideEffect result");
    } catch (RuntimeException exception) {
      result = new DecisionResult(DecisionOutcome.FAILED, "EXECUTION_FAILED");
    }
    entry.result().complete(result);
    return result;
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotency key must not be blank");
    }
  }

  private record Entry(
      InvocationFingerprint fingerprint, CompletableFuture<DecisionResult> result) {}
}

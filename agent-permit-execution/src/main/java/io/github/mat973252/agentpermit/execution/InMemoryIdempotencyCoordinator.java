package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.approval.InvocationFingerprint;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

final class InMemoryIdempotencyCoordinator<T> {

  private final InvocationFingerprinter fingerprinter;
  private final ConcurrentMap<String, Entry<T>> entries = new ConcurrentHashMap<>();

  InMemoryIdempotencyCoordinator(InvocationFingerprinter fingerprinter) {
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
  }

  T executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      Supplier<T> sideEffect,
      Supplier<T> mismatchResult,
      Supplier<T> failureResult) {
    requireKey(key);
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    Objects.requireNonNull(sideEffect, "sideEffect");
    Objects.requireNonNull(mismatchResult, "mismatchResult");
    Objects.requireNonNull(failureResult, "failureResult");
    var fingerprint = fingerprinter.fingerprint(normalizedInvocation);
    var candidate = new Entry<T>(fingerprint, new CompletableFuture<>());
    var existing = entries.putIfAbsent(key, candidate);
    if (existing == null) {
      return executeOwner(candidate, sideEffect, failureResult);
    }
    if (!existing.fingerprint().equals(fingerprint)) {
      return Objects.requireNonNull(mismatchResult.get(), "mismatch result");
    }
    return existing.result().join();
  }

  private T executeOwner(Entry<T> entry, Supplier<T> sideEffect, Supplier<T> failureResult) {
    T result;
    try {
      result = Objects.requireNonNull(sideEffect.get(), "sideEffect result");
    } catch (RuntimeException exception) {
      result = Objects.requireNonNull(failureResult.get(), "failure result");
    }
    entry.result().complete(result);
    return result;
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotency key must not be blank");
    }
  }

  private record Entry<T>(InvocationFingerprint fingerprint, CompletableFuture<T> result) {}
}

package io.github.mat973252.agentpermit.redis;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.ResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import redis.clients.jedis.UnifiedJedis;

public final class RedisResultIdempotencyGuard implements ResultIdempotencyGuard {

  private final RedisIdempotencyRepository repository;
  private final InvocationFingerprinter fingerprinter;
  private final RedisIdempotencyConfig config;
  private final Supplier<String> ownerTokenGenerator;
  private final RedisSleeper sleeper;
  private final RedisKeyFactory keyFactory;

  public RedisResultIdempotencyGuard(
      UnifiedJedis client, RedisIdempotencyConfig config) {
    this(
        new JedisRedisIdempotencyRepository(client),
        new InvocationFingerprinter(),
        config,
        () -> UUID.randomUUID().toString(),
        duration -> Thread.sleep(duration.toMillis()));
  }

  RedisResultIdempotencyGuard(
      RedisIdempotencyRepository repository,
      InvocationFingerprinter fingerprinter,
      RedisIdempotencyConfig config,
      Supplier<String> ownerTokenGenerator,
      RedisSleeper sleeper) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
    this.config = Objects.requireNonNull(config, "config");
    this.ownerTokenGenerator = Objects.requireNonNull(ownerTokenGenerator, "ownerTokenGenerator");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    keyFactory = new RedisKeyFactory(config.keyPrefix());
  }

  @Override
  public ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      Supplier<ToolExecutionResult> sideEffect) {
    return executeOnce(key, normalizedInvocation, null, sideEffect);
  }

  @Override
  public ToolExecutionResult executeOnce(
      String key,
      ToolInvocation normalizedInvocation,
      String approvalRequestId,
      Supplier<ToolExecutionResult> sideEffect) {
    requireKey(key);
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    Objects.requireNonNull(sideEffect, "sideEffect");
    requireApprovalId(approvalRequestId);
    var keys = keyFactory.keys(key, approvalRequestId);
    var request = claimRequest(keys, normalizedInvocation);
    return coordinate(request, sideEffect);
  }

  private ToolExecutionResult coordinate(
      RedisClaimRequest request,
      Supplier<ToolExecutionResult> sideEffect) {
    while (true) {
      RedisClaimResult claim;
      try {
        claim = Objects.requireNonNull(repository.claim(request), "claim");
      } catch (RuntimeException exception) {
        return failed("IDEMPOTENCY_STORAGE_UNAVAILABLE");
      }
      switch (claim.kind()) {
        case OWNER -> {
          return executeOwner(request, sideEffect);
        }
        case WAIT -> {
          if (!pause()) {
            return failed("IDEMPOTENCY_WAIT_INTERRUPTED");
          }
        }
        case TERMINAL -> {
          return claim.result();
        }
        case MISMATCH -> {
          return denied("IDEMPOTENCY_INVOCATION_MISMATCH");
        }
        case APPROVAL_CONFLICT -> {
          return approvalRequired("APPROVAL_ALREADY_CONSUMED");
        }
        case STATE_LOST -> {
          return failed("IDEMPOTENCY_STATE_LOST");
        }
        case CORRUPT -> {
          return failed("IDEMPOTENCY_STORAGE_UNAVAILABLE");
        }
      }
    }
  }

  private ToolExecutionResult executeOwner(
      RedisClaimRequest claim,
      Supplier<ToolExecutionResult> sideEffect) {
    ToolExecutionResult result;
    try {
      result = Objects.requireNonNull(sideEffect.get(), "sideEffect result");
    } catch (RuntimeException exception) {
      result = failed("EXECUTION_FAILED");
    }
    var completion =
        new RedisCompletionRequest(
            claim.entryKey(),
            claim.invocationFingerprint(),
            claim.ownerToken(),
            result);
    try {
      var completed = Objects.requireNonNull(repository.complete(completion), "completion");
      return completed.kind() == RedisClaimResult.Kind.TERMINAL
          ? completed.result()
          : failed("IDEMPOTENCY_STORAGE_UNAVAILABLE");
    } catch (RuntimeException exception) {
      return failed("IDEMPOTENCY_STORAGE_UNAVAILABLE");
    }
  }

  private RedisClaimRequest claimRequest(
      RedisKeyFactory.Keys keys, ToolInvocation invocation) {
    var ownerToken = Objects.requireNonNull(ownerTokenGenerator.get(), "ownerToken");
    if (ownerToken.isBlank()) {
      throw new IllegalStateException("ownerToken must not be blank");
    }
    return new RedisClaimRequest(
        keys.entryKey(),
        keys.approvalKey(),
        keys.idempotencyDigest(),
        fingerprinter.fingerprint(invocation).value(),
        ownerToken,
        config.ownerLease().toMillis());
  }

  private boolean pause() {
    try {
      sleeper.sleep(config.pollInterval());
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void requireKey(String key) {
    Objects.requireNonNull(key, "key");
    if (key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
  }

  private static void requireApprovalId(String approvalRequestId) {
    if (approvalRequestId != null && approvalRequestId.isBlank()) {
      throw new IllegalArgumentException("approvalRequestId must not be blank");
    }
  }

  private static ToolExecutionResult denied(String reasonCode) {
    return result(DecisionOutcome.DENIED, reasonCode);
  }

  private static ToolExecutionResult approvalRequired(String reasonCode) {
    return result(DecisionOutcome.APPROVAL_REQUIRED, reasonCode);
  }

  private static ToolExecutionResult failed(String reasonCode) {
    return result(DecisionOutcome.FAILED, reasonCode);
  }

  private static ToolExecutionResult result(DecisionOutcome outcome, String reasonCode) {
    return new ToolExecutionResult(new DecisionResult(outcome, reasonCode), null);
  }
}

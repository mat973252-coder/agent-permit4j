package io.github.agentpermit4j.redis;

import io.github.agentpermit4j.execution.ToolExecutionResult;
import java.util.Objects;

record RedisClaimResult(Kind kind, ToolExecutionResult result) {

  RedisClaimResult {
    Objects.requireNonNull(kind, "kind");
    if ((kind == Kind.TERMINAL) != (result != null)) {
      throw new IllegalArgumentException("only a terminal claim carries a result");
    }
  }

  static RedisClaimResult owner() {
    return new RedisClaimResult(Kind.OWNER, null);
  }

  static RedisClaimResult waiting() {
    return new RedisClaimResult(Kind.WAIT, null);
  }

  static RedisClaimResult terminal(ToolExecutionResult result) {
    return new RedisClaimResult(Kind.TERMINAL, Objects.requireNonNull(result, "result"));
  }

  static RedisClaimResult mismatch() {
    return new RedisClaimResult(Kind.MISMATCH, null);
  }

  static RedisClaimResult approvalConflict() {
    return new RedisClaimResult(Kind.APPROVAL_CONFLICT, null);
  }

  static RedisClaimResult stateLost() {
    return new RedisClaimResult(Kind.STATE_LOST, null);
  }

  static RedisClaimResult corrupt() {
    return new RedisClaimResult(Kind.CORRUPT, null);
  }

  enum Kind {
    OWNER,
    WAIT,
    TERMINAL,
    MISMATCH,
    APPROVAL_CONFLICT,
    STATE_LOST,
    CORRUPT
  }
}

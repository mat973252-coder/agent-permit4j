package io.github.agentpermit4j.redis;

import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.execution.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

final class JedisRedisIdempotencyRepository implements RedisIdempotencyRepository {

  private static final String CLAIM_SCRIPT = RedisLuaScripts.load("claim.lua");
  private static final String COMPLETE_SCRIPT = RedisLuaScripts.load("complete.lua");

  private final UnifiedJedis client;

  JedisRedisIdempotencyRepository(UnifiedJedis client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public RedisClaimResult claim(RedisClaimRequest request) {
    var approvalPresent = request.approvalKey() != null;
    var keys = keys(request.entryKey(), request.approvalKey());
    var arguments =
        List.of(
            request.invocationFingerprint(),
            request.ownerToken(),
            Long.toString(request.ownerLeaseMillis()),
            request.idempotencyDigest(),
            approvalPresent ? "1" : "0");
    return parse(client.eval(CLAIM_SCRIPT, keys, arguments));
  }

  @Override
  public RedisClaimResult complete(RedisCompletionRequest request) {
    var result = request.result();
    var outputPresent = result.output() != null;
    var arguments =
        List.of(
            request.invocationFingerprint(),
            request.ownerToken(),
            result.decision().outcome().name(),
            result.decision().reasonCode(),
            outputPresent ? "1" : "0",
            outputPresent ? result.output() : "");
    return parse(client.eval(COMPLETE_SCRIPT, List.of(request.entryKey()), arguments));
  }

  private static List<String> keys(String entryKey, String approvalKey) {
    return List.of(entryKey, approvalKey == null ? entryKey : approvalKey);
  }

  private static RedisClaimResult parse(Object rawResult) {
    if (!(rawResult instanceof List<?> rawValues) || rawValues.isEmpty()) {
      return RedisClaimResult.corrupt();
    }
    var values = new ArrayList<String>(rawValues.size());
    for (var value : rawValues) {
      values.add(text(value));
    }
    return switch (values.getFirst()) {
      case "OWNER" -> RedisClaimResult.owner();
      case "WAIT" -> RedisClaimResult.waiting();
      case "MISMATCH" -> RedisClaimResult.mismatch();
      case "APPROVAL_CONFLICT" -> RedisClaimResult.approvalConflict();
      case "STATE_LOST" -> RedisClaimResult.stateLost();
      case "TERMINAL" -> terminal(values);
      default -> RedisClaimResult.corrupt();
    };
  }

  private static RedisClaimResult terminal(List<String> values) {
    if (values.size() != 5) {
      return RedisClaimResult.corrupt();
    }
    var outcome = DecisionOutcome.valueOf(values.get(1));
    var output = switch (values.get(3)) {
      case "0" -> null;
      case "1" -> values.get(4);
      default -> throw new IllegalArgumentException("invalid output presence");
    };
    return RedisClaimResult.terminal(
        new ToolExecutionResult(new DecisionResult(outcome, values.get(2)), output));
  }

  private static String text(Object value) {
    if (value instanceof String string) {
      return string;
    }
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return Objects.toString(value, "");
  }
}

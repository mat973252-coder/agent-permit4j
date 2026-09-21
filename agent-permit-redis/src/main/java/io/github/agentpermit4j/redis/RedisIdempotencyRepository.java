package io.github.agentpermit4j.redis;

interface RedisIdempotencyRepository {

  RedisClaimResult claim(RedisClaimRequest request);

  RedisClaimResult complete(RedisCompletionRequest request);
}

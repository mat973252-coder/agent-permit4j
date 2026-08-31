package io.github.mat973252.agentpermit.redis;

interface RedisIdempotencyRepository {

  RedisClaimResult claim(RedisClaimRequest request);

  RedisClaimResult complete(RedisCompletionRequest request);
}

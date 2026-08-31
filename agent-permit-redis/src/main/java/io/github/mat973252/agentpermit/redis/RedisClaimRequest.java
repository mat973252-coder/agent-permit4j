package io.github.mat973252.agentpermit.redis;

record RedisClaimRequest(
    String entryKey,
    String approvalKey,
    String idempotencyDigest,
    String invocationFingerprint,
    String ownerToken,
    long ownerLeaseMillis) {}

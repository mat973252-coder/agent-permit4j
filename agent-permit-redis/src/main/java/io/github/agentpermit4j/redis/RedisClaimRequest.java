package io.github.agentpermit4j.redis;

record RedisClaimRequest(
    String entryKey,
    String approvalKey,
    String idempotencyDigest,
    String invocationFingerprint,
    String ownerToken,
    long ownerLeaseMillis) {}

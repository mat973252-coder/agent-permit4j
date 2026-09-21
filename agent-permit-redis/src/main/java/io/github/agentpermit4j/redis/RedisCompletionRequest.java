package io.github.agentpermit4j.redis;

import io.github.agentpermit4j.execution.ToolExecutionResult;

record RedisCompletionRequest(
    String entryKey,
    String invocationFingerprint,
    String ownerToken,
    ToolExecutionResult result) {}

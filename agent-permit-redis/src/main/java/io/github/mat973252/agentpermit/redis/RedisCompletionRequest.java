package io.github.mat973252.agentpermit.redis;

import io.github.mat973252.agentpermit.execution.ToolExecutionResult;

record RedisCompletionRequest(
    String entryKey,
    String invocationFingerprint,
    String ownerToken,
    ToolExecutionResult result) {}

package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.ToolInvocation;

@FunctionalInterface
public interface InvocationNormalizer {

  ToolInvocation normalize(ToolInvocation invocation);
}

package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.core.ToolInvocation;

@FunctionalInterface
public interface ResultToolExecutor {

  String execute(ToolInvocation invocation);
}

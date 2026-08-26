package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;

@FunctionalInterface
public interface InvocationValidator {

  GateDecision validate(ToolInvocation invocation);
}

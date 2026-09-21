package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.ToolInvocation;

@FunctionalInterface
public interface InvocationValidator {

  GateDecision validate(ToolInvocation invocation);
}

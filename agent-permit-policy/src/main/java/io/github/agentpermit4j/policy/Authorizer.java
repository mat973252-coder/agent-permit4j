package io.github.agentpermit4j.policy;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.ToolInvocation;

@FunctionalInterface
public interface Authorizer {

  GateDecision authorize(ToolInvocation invocation);
}

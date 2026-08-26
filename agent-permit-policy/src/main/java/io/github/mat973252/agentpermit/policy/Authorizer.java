package io.github.mat973252.agentpermit.policy;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;

@FunctionalInterface
public interface Authorizer {

  GateDecision authorize(ToolInvocation invocation);
}

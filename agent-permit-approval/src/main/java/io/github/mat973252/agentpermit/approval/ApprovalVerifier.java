package io.github.mat973252.agentpermit.approval;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;

@FunctionalInterface
public interface ApprovalVerifier {

  GateDecision verify(String requestId, ToolInvocation normalizedInvocation);
}

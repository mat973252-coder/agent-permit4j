package io.github.agentpermit4j.approval;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.ToolInvocation;

@FunctionalInterface
public interface ApprovalVerifier {

  GateDecision verify(String requestId, ToolInvocation normalizedInvocation);
}

package io.github.mat973252.agentpermit.approval;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.ToolInvocation;

/** The application supplies an authenticated reviewer and authorizes that reviewer for this change. */
@FunctionalInterface
public interface ApprovalAuthorizer {
  GateDecision authorize(Principal approver, ToolInvocation normalizedInvocation);
}

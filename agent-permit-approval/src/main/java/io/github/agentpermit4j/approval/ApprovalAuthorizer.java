package io.github.agentpermit4j.approval;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.ToolInvocation;

/** The application supplies an authenticated reviewer and authorizes that reviewer for this change. */
@FunctionalInterface
public interface ApprovalAuthorizer {
  GateDecision authorize(Principal approver, ToolInvocation normalizedInvocation);
}

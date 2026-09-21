package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;

record RefundOwner(String principalId, String tenantId, String environment) {
  static RefundOwner from(Principal principal, InvocationContext context) {
    return new RefundOwner(principal.id(), context.tenantId(), context.environment());
  }
}

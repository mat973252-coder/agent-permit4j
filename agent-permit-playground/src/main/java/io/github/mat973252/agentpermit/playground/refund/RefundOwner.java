package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;

record RefundOwner(String principalId, String tenantId, String environment) {
  static RefundOwner from(Principal principal, InvocationContext context) {
    return new RefundOwner(principal.id(), context.tenantId(), context.environment());
  }
}

package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Map;

/** Synthetic, backend-built review view. Approve by stored ID, never by a submitted copy of this view. */
public record RefundPreview(String reviewId, String requesterId, String tenantId, String environment,
    String orderId, long amountCents, long refundedBeforeCents, long refundedAfterCents,
    long expectedVersion, String policyRevision) {

  public Map<String, String> arguments() {
    return Map.of("orderId", orderId, "amountCents", Long.toString(amountCents),
        "expectedVersion", Long.toString(expectedVersion), "policyRevision", policyRevision);
  }

  ToolInvocation invocation() {
    return new ToolInvocation(new ToolDescriptor("orders.refund", ToolEffect.WRITE,
        Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED),
        new Principal(requesterId, Map.of()), new Action("orders.refund"),
        new Resource("order", orderId, Map.of("policyRevision", policyRevision)),
        new InvocationContext(tenantId, environment), arguments());
  }

  RefundPreview withReviewId(String id) {
    return new RefundPreview(id, requesterId, tenantId, environment, orderId, amountCents,
        refundedBeforeCents, refundedAfterCents, expectedVersion, policyRevision);
  }
}

package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.Map;

/** Synthetic, backend-built review view. Approve by stored ID, never by a submitted copy of this view. */
public record RefundPreview(String reviewId, String requesterId, String tenantId, String environment,
    String orderId, long amountCents, long refundedBeforeCents, long refundedAfterCents,
    long expectedVersion, String policyRevision, String operationReference) {

  public Map<String, String> arguments() {
    return Map.of("operationReference", operationReference, "amountCents", Long.toString(amountCents),
        "expectedVersion", Long.toString(expectedVersion), "policyRevision", policyRevision);
  }

  ToolInvocation invocation() {
    return new ToolInvocation(new ToolDescriptor("orders.refund", ToolEffect.WRITE,
        Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED),
        new Principal(requesterId, Map.of()), new Action("orders.refund"),
        new Resource("refund", operationReference, Map.of("policyRevision", policyRevision)),
        new InvocationContext(tenantId, environment), arguments());
  }

  RefundPreview withReviewId(String id) {
    return new RefundPreview(id, requesterId, tenantId, environment, orderId, amountCents,
        refundedBeforeCents, refundedAfterCents, expectedVersion, policyRevision, operationReference);
  }
}

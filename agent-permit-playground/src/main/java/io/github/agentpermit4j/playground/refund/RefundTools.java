package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.execution.ExecutionOutcome;
import io.github.agentpermit4j.springai.AgentPermit;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

public final class RefundTools {
  private final RefundLedger ledger;
  private final RefundReviews reviews;
  private final RefundPolicy policy;
  private final RefundOperationService operations;

  RefundTools(RefundLedger ledger, RefundReviews reviews, RefundPolicy policy, RefundOperationService operations) {
    this.ledger = ledger;
    this.reviews = reviews;
    this.policy = policy;
    this.operations = operations;
  }

  @Tool(name = "orders.lookup", description = "Read the local demo order balance")
  @AgentPermit(resourceType = "order", resourceArg = "orderId", effect = ToolEffect.READ, risk = RiskLevel.LOW)
  public OrderBalance lookup(String orderId, ToolContext context) {
    return ledger.find(tenant(context), orderId);
  }

  @Tool(name = "orders.previewRefund", description = "Prepare a concrete refund for human review; does not pay")
  @AgentPermit(resourceType = "order", resourceArg = "orderId", risk = RiskLevel.LOW)
  public RefundPreview preview(String orderId, long amountCents, ToolContext context) {
    return reviews.preview(orderId, amountCents, context);
  }

  @Tool(name = "orders.refund", description = "Execute the exact approved local refund")
  @AgentPermit(resourceType = "refund", resourceArg = "operationReference")
  public ExecutionOutcome refund(String operationReference, long amountCents, long expectedVersion,
      String policyRevision, ToolContext context) {
    var values = context.getContext();
    var principal = new Principal((String) values.get(SpringAiToolContextKeys.PRINCIPAL_ID), Map.of());
    var scope = new InvocationContext(tenant(context), (String) values.get(SpringAiToolContextKeys.ENVIRONMENT));
    return policy.execute(policyRevision, () -> operations.execute(operationReference,
        new RefundExpectation(amountCents, expectedVersion, policyRevision), principal, scope));
  }

  private static String tenant(ToolContext context) {
    return (String) context.getContext().get(SpringAiToolContextKeys.TENANT_ID);
  }
}

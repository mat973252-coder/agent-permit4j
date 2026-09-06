package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.springai.AgentPermit;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

public final class RefundTools {
  private final RefundLedger ledger;
  private final RefundReviews reviews;
  private final RefundPolicy policy;

  RefundTools(RefundLedger ledger, RefundReviews reviews, RefundPolicy policy) {
    this.ledger = ledger;
    this.reviews = reviews;
    this.policy = policy;
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
  @AgentPermit(resourceType = "order", resourceArg = "orderId")
  public String refund(String orderId, long amountCents, long expectedVersion,
      String policyRevision, ToolContext context) {
    return policy.execute(policyRevision,
        () -> ledger.refund(new RefundCommand(tenant(context), orderId, amountCents, expectedVersion)));
  }

  private static String tenant(ToolContext context) {
    return (String) context.getContext().get(SpringAiToolContextKeys.TENANT_ID);
  }
}

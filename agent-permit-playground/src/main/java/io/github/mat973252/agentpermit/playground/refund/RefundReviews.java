package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.jdbc.approval.JdbcApprovalService;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ToolContext;

final class RefundReviews {
  private final RefundLedger ledger;
  private final JdbcApprovalService approvals;
  private final RefundPolicy policy;
  private final Map<String, RefundPreview> previews = new ConcurrentHashMap<>();

  RefundReviews(RefundLedger ledger, JdbcApprovalService approvals, RefundPolicy policy) {
    this.ledger = ledger;
    this.approvals = approvals;
    this.policy = policy;
  }

  RefundPreview preview(String orderId, long amountCents, ToolContext context) {
    var values = context.getContext();
    var tenant = (String) values.get(SpringAiToolContextKeys.TENANT_ID);
    var order = ledger.find(tenant, orderId);
    if (amountCents <= 0 || amountCents > order.refundableCents()) {
      throw new IllegalArgumentException("refund exceeds the available balance");
    }
    var preview = new RefundPreview("pending", (String) values.get(SpringAiToolContextKeys.PRINCIPAL_ID),
        tenant, (String) values.get(SpringAiToolContextKeys.ENVIRONMENT), orderId, amountCents,
        order.refundedCents(), order.refundedCents() + amountCents, order.version(), policy.revision());
    var request = approvals.requestReview(preview.invocation(), Duration.ofMinutes(5));
    var stored = preview.withReviewId(request.id());
    previews.put(stored.reviewId(), stored);
    return stored;
  }

  GateDecision approve(String reviewId, Principal approver) {
    var preview = previews.get(reviewId);
    if (preview == null) {
      return new GateDecision(false, "APPROVAL_NOT_FOUND");
    }
    if (!preview.policyRevision().equals(policy.revision())) {
      return new GateDecision(false, "REFUND_POLICY_CHANGED");
    }
    return approvals.approve(reviewId, preview.invocation(), approver);
  }
}

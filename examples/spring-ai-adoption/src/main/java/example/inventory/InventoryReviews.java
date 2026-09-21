package example.inventory;

import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.model.ToolContext;

final class InventoryReviews {
  private final InventoryStore inventory;
  private final InMemoryApprovalService approvals;
  private final Map<String, InventoryPreview> proposals = new ConcurrentHashMap<>();

  InventoryReviews(InventoryStore inventory, InMemoryApprovalService approvals) {
    this.inventory = inventory;
    this.approvals = approvals;
  }

  InventoryPreview preview(String sku, int quantity, ToolContext context) {
    var stock = inventory.snapshot();
    if (!stock.sku().equals(sku) || quantity <= 0 || quantity > stock.available()) {
      throw new IllegalArgumentException("invalid inventory proposal");
    }
    var trusted = context.getContext();
    var proposal = new InventoryPreview("pending",
        (String) trusted.get(SpringAiToolContextKeys.PRINCIPAL_ID),
        (String) trusted.get(SpringAiToolContextKeys.TENANT_ID),
        (String) trusted.get(SpringAiToolContextKeys.ENVIRONMENT),
        sku, quantity, stock.version(), stock.available(), stock.available() - quantity);
    var request = approvals.requestReview(proposal.invocation(), Duration.ofMinutes(5));
    var stored = proposal.withReviewId(request.id());
    proposals.put(request.id(), stored);
    return stored;
  }

  GateDecision approve(String reviewId, Principal authenticatedReviewer) {
    var proposal = proposals.get(reviewId);
    return proposal == null ? new GateDecision(false, "APPROVAL_NOT_FOUND")
        : approvals.approve(reviewId, proposal.invocation(), authenticatedReviewer);
  }
}

package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

/** Reproducible three-tool walkthrough. All orders, identities, and payments are synthetic. */
public final class RefundDemo {
  private final RefundWorkspace workspace = RefundWorkspace.inMemory(
      Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC));
  private final JsonHelper json = new JsonHelper();

  public static void main(String[] args) {
    new RefundDemo().run();
    new RefundDemo().runRecovery();
  }

  private void run() {
    System.out.println("SCENARIO refund-business");
    require(call("orders.lookup", Map.of("orderId", "order-1"), "lookup", null), "EXECUTED");
    var result = call("orders.previewRefund", Map.of("orderId", "order-1", "amountCents", "2500"),
        "preview", null);
    var preview = json.fromJson((String) json.fromJson(result, Map.class).get("output"), RefundPreview.class);
    System.out.println("  PREVIEW order=order-1 refundedCents=" + preview.refundedBeforeCents()
        + "->" + preview.refundedAfterCents() + " version=" + preview.expectedVersion()
        + " policy=" + preview.policyRevision());
    require(call("orders.refund", preview.arguments(), "refund", null), "APPROVAL_REQUIRED");
    verifyReviewer(preview);
    var changed = new HashMap<>(preview.arguments());
    changed.put("amountCents", "5000");
    require(call("orders.refund", changed, "changed", preview.reviewId()), "APPROVAL_INVOCATION_MISMATCH");
    var executed = call("orders.refund", preview.arguments(), "refund", preview.reviewId());
    requireStatus(executed, ExecutionStatus.SUCCEEDED);
    if (!executed.equals(call("orders.refund", preview.arguments(), "refund", preview.reviewId()))
        || workspace.payments().paymentCount() != 1) {
      throw new IllegalStateException("refund retry contract failed");
    }
    var balance = workspace.ledger().find("tenant-a", "order-1");
    if (balance.refundedCents() != 2_500 || balance.version() != 1) {
      throw new IllegalStateException("refund ledger contract failed");
    }
    System.out.println("  RESULT decision=EXECUTED status=SUCCEEDED payments=1 refundedCents=2500 version=1 retry=same-result");
  }

  private void runRecovery() {
    System.out.println("SCENARIO refund-recovery");
    var result = call("orders.previewRefund", Map.of("orderId", "order-1", "amountCents", "2500"), "preview", null);
    var preview = json.fromJson((String) json.fromJson(result, Map.class).get("output"), RefundPreview.class);
    verifyReviewer(preview);
    workspace.payments().loseNextResponse();
    var response = call("orders.refund", preview.arguments(), "refund", preview.reviewId());
    requireStatus(response, ExecutionStatus.UNKNOWN);
    System.out.println("  RESPONSE status=UNKNOWN payments=" + workspace.payments().paymentCount()
        + " refundedCents=" + workspace.ledger().find("tenant-a", "order-1").refundedCents());
    var restored = workspace.restartServices();
    var owner = new Principal("operator-a", Map.of());
    var scope = new InvocationContext("tenant-a", "demo");
    var before = restored.inspect(preview.operationReference(), owner, scope).orElseThrow();
    var after = restored.reconcile(preview.operationReference(), owner, scope).orElseThrow();
    if (before.status() != ExecutionStatus.UNKNOWN || after.status() != ExecutionStatus.SUCCEEDED
        || restored.payments().requestCount() != 1 || restored.ledger().find("tenant-a", "order-1").refundedCents() != 2_500
        || !response.equals(call("orders.refund", preview.arguments(), "refund", preview.reviewId()))) {
      throw new IllegalStateException("refund recovery contract failed");
    }
    System.out.println("  REBUILT status=" + before.status() + " reference=owner-scoped");
    System.out.println("  RECONCILED status=" + after.status() + " payments=" + restored.payments().paymentCount()
        + " paymentRequests=" + restored.payments().requestCount() + " refundedCents=2500 retry=same-snapshot");
  }

  private void requireStatus(String response, ExecutionStatus status) {
    require(response, "EXECUTED");
    var result = json.fromJson((String) json.fromJson(response, Map.class).get("output"), ExecutionOutcome.class);
    if (result.status() != status) {
      throw new IllegalStateException("unexpected refund business status");
    }
  }

  private void verifyReviewer(RefundPreview preview) {
    var self = new Principal("operator-a", Map.of("role", "reviewer", "tenant", "tenant-a"));
    if (workspace.approve(preview.reviewId(), self).permitted()) {
      throw new IllegalStateException("self approval must be denied");
    }
    var reviewer = new Principal("reviewer-a", Map.of("role", "reviewer", "tenant", "tenant-a"));
    if (!workspace.approve(preview.reviewId(), reviewer).permitted()) {
      throw new IllegalStateException("authorized review failed");
    }
    System.out.println("  REVIEW approver="
        + workspace.approvalDecision(preview.reviewId()).orElseThrow().approverId()
        + " selfApproval=DENIED");
  }

  private String call(String name, Map<String, String> input, String key, String approvalId) {
    var metadata = new HashMap<String, Object>();
    metadata.put(SpringAiToolContextKeys.PRINCIPAL_ID, "operator-a");
    metadata.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    metadata.put(SpringAiToolContextKeys.ENVIRONMENT, "demo");
    metadata.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, key);
    if (approvalId != null) {
      metadata.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalId);
    }
    return workspace.tools().stream().filter(tool -> tool.getToolDefinition().name().equals(name))
        .findFirst().orElseThrow().call(json.toJson(input), new ToolContext(metadata));
  }

  private static void require(String result, String expected) {
    if (!result.contains("\"" + expected + "\"")) {
      throw new IllegalStateException("refund scenario did not produce " + expected);
    }
  }
}

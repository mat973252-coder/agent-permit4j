package io.github.mat973252.agentpermit.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

class RefundWorkspaceRecoveryTest {
  private static final JsonHelper JSON = new JsonHelper();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
  private static final Principal OWNER = new Principal("operator-a", Map.of());
  private static final InvocationContext CONTEXT = new InvocationContext("tenant-a", "demo");

  @Test
  void approvedToolReturnsSafeUnknownReferenceAndRebuiltServicesReconcileWithoutRepayment() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    var preview = preview(workspace);
    assertNotEquals(preview.reviewId(), preview.operationReference());
    assertEquals(ExecutionStatus.NOT_STARTED, workspace.inspect(preview.operationReference(), OWNER, CONTEXT)
        .orElseThrow().status());
    approve(workspace, preview);
    workspace.payments().loseNextResponse();
    var first = refund(workspace, preview, preview.arguments());
    var outcome = outcome(first);
    assertEquals(ExecutionStatus.UNKNOWN, outcome.status());
    assertEquals(preview.operationReference(), outcome.reference());
    assertFalse(JSON.toJson(outcome).contains("amountCents"));
    assertFalse(JSON.toJson(outcome).contains(preview.reviewId()));
    assertEquals(1, workspace.payments().requestCount());
    var restored = workspace.restartServices();
    assertEquals(ExecutionStatus.UNKNOWN, outcome(refund(restored, preview, preview.arguments())).status());
    assertEquals(1, restored.payments().requestCount());
    assertEquals(ExecutionStatus.SUCCEEDED, restored.reconcile(preview.operationReference(), OWNER, CONTEXT)
        .orElseThrow().status());
    assertEquals(ExecutionStatus.SUCCEEDED, restored.inspect(preview.operationReference(), OWNER, CONTEXT)
        .orElseThrow().status());
    assertEquals(first, refund(workspace, preview, preview.arguments()));
    assertEquals(2_500, restored.ledger().find("tenant-a", "order-1").refundedCents());
    assertEquals(1, restored.payments().paymentCount());
    assertEquals(1, restored.payments().requestCount());
  }

  @Test
  void changedOperationReferenceInvalidatesApprovalAndReferenceWithoutApprovalCannotPay() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    var preview = preview(workspace);
    var input = JSON.toJson(preview.arguments());
    assertTrue(call(workspace, "orders.refund", input, null).contains("APPROVAL_REQUIRED"));
    approve(workspace, preview);
    var changed = new HashMap<>(preview.arguments());
    changed.put("operationReference", "another-reference");
    assertTrue(refund(workspace, preview, changed).contains("APPROVAL_INVOCATION_MISMATCH"));
    assertEquals(0, workspace.payments().requestCount());
  }

  private static RefundPreview preview(RefundWorkspace workspace) {
    var envelope = JSON.fromJson(call(workspace, "orders.previewRefund",
        "{\"orderId\":\"order-1\",\"amountCents\":2500}", null), Map.class);
    return JSON.fromJson((String) envelope.get("output"), RefundPreview.class);
  }

  private static void approve(RefundWorkspace workspace, RefundPreview preview) {
    assertTrue(workspace.approve(preview.reviewId(),
        new Principal("reviewer-a", Map.of("role", "reviewer", "tenant", "tenant-a"))).permitted());
  }

  private static String refund(RefundWorkspace workspace, RefundPreview preview, Map<String, String> input) {
    return call(workspace, "orders.refund", JSON.toJson(input), preview.reviewId());
  }

  private static ExecutionOutcome outcome(String response) {
    var envelope = JSON.fromJson(response, Map.class);
    assertEquals("EXECUTED", envelope.get("outcome"));
    return JSON.fromJson((String) envelope.get("output"), ExecutionOutcome.class);
  }

  private static String call(RefundWorkspace workspace, String name, String input, String approvalId) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, OWNER.id());
    values.put(SpringAiToolContextKeys.TENANT_ID, CONTEXT.tenantId());
    values.put(SpringAiToolContextKeys.ENVIRONMENT, CONTEXT.environment());
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, name);
    if (approvalId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalId);
    }
    return workspace.tools().stream().filter(tool -> tool.getToolDefinition().name().equals(name))
        .findFirst().orElseThrow().call(input, new ToolContext(values));
  }
}

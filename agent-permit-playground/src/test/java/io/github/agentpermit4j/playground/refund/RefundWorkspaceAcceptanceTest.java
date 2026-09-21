package io.github.agentpermit4j.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.execution.ExecutionOutcome;
import io.github.agentpermit4j.execution.ExecutionStatus;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

class RefundWorkspaceAcceptanceTest {
  private static final JsonHelper JSON = new JsonHelper();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void threeBusinessToolsReviewAndExecuteOneConcreteRefund() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    assertEquals(3, workspace.tools().size());
    assertTrue(call(workspace, "orders.lookup", "{\"orderId\":\"order-1\"}", context("lookup", null))
        .contains("paidCents"));
    var preview = preview(workspace);
    assertEquals(0, preview.refundedBeforeCents());
    assertEquals(2_500, preview.refundedAfterCents());
    assertEquals(0, preview.expectedVersion());
    assertEquals(0, workspace.payments().paymentCount());
    var input = JSON.toJson(preview.arguments());
    var pending = call(workspace, "orders.refund", input, context("refund", null));
    assertTrue(pending.contains("APPROVAL_REQUIRED"));
    assertTrue(workspace.approve(preview.reviewId(), reviewer()).permitted());
    var approved = context("refund", preview.reviewId());
    var first = call(workspace, "orders.refund", input, approved);
    assertEquals(ExecutionStatus.SUCCEEDED, businessOutcome(first).status());
    assertEquals(first, call(workspace, "orders.refund", input, approved));
    assertEquals(1, workspace.payments().paymentCount());
    assertEquals(2_500, workspace.ledger().find("tenant-a", "order-1").refundedCents());
    assertEquals("reviewer-a", workspace.approvalDecision(preview.reviewId()).orElseThrow().approverId());
  }

  @Test
  void changedAmountOrPolicyCannotUseTheOriginalApproval() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    var preview = preview(workspace);
    workspace.approve(preview.reviewId(), reviewer());
    var changed = new HashMap<>(preview.arguments());
    changed.put("amountCents", "5000");
    assertTrue(call(workspace, "orders.refund", JSON.toJson(changed),
        context("changed", preview.reviewId())).contains("APPROVAL_INVOCATION_MISMATCH"));
    workspace.policyRevision("refund-v2");
    assertTrue(call(workspace, "orders.refund", JSON.toJson(preview.arguments()),
        context("policy", preview.reviewId())).contains("REFUND_POLICY_CHANGED"));
    assertEquals(0, workspace.payments().paymentCount());
  }

  @Test
  void changedOrderVersionIsRejectedBeforeAnotherPayment() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    var preview = preview(workspace);
    workspace.approve(preview.reviewId(), reviewer());
    workspace.ledger().refund(new RefundCommand("tenant-a", "order-1", 500, 0));
    var result = call(workspace, "orders.refund", JSON.toJson(preview.arguments()),
        context("stale", preview.reviewId()));
    assertEquals(ExecutionStatus.FAILED, businessOutcome(result).status());
    assertEquals("REFUND_PRECONDITION_CHANGED", businessOutcome(result).reasonCode());
    assertEquals(1, workspace.payments().paymentCount());
    assertEquals(500, workspace.ledger().find("tenant-a", "order-1").refundedCents());
  }

  @Test
  void concurrentCallbackRetriesReturnOneRefundAndCannotReuseApprovalWithAnotherKey() throws Exception {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    var preview = preview(workspace);
    assertTrue(workspace.approve(preview.reviewId(), reviewer()).permitted());
    var input = JSON.toJson(preview.arguments());
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var results = IntStream.range(0, 8).mapToObj(ignored -> executor.submit((Callable<String>) () -> {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return call(workspace, "orders.refund", input, context("concurrent", preview.reviewId()));
      })).toList();
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      var first = results.getFirst().get(5, TimeUnit.SECONDS);
      assertEquals(ExecutionStatus.SUCCEEDED, businessOutcome(first).status());
      for (var result : results) {
        assertEquals(first, result.get(5, TimeUnit.SECONDS));
      }
    }
    assertTrue(call(workspace, "orders.refund", input, context("another-key", preview.reviewId()))
        .contains("APPROVAL_ALREADY_CONSUMED"));
    assertEquals(1, workspace.payments().paymentCount());
    assertEquals(2_500, workspace.ledger().find("tenant-a", "order-1").refundedCents());
    assertEquals(1, workspace.ledger().find("tenant-a", "order-1").version());
  }

  @Test
  void missingRefundNumbersAreRejectedAsInvalidInputBeforePayment() {
    var workspace = RefundWorkspace.inMemory(CLOCK);
    assertTrue(call(workspace, "orders.refund", "{\"operationReference\":\"reference\"}", context("missing", null))
        .contains("REFUND_INPUT_INVALID"));
    assertTrue(call(workspace, "orders.refund", "{\"operationReference\":\"reference\",\"amountCents\":2500}",
        context("missing-version", null)).contains("REFUND_INPUT_INVALID"));
    assertEquals(0, workspace.payments().paymentCount());
    assertEquals(0, workspace.ledger().find("tenant-a", "order-1").refundedCents());
  }

  private static RefundPreview preview(RefundWorkspace workspace) {
    var envelope = JSON.fromJson(call(workspace, "orders.previewRefund",
        "{\"orderId\":\"order-1\",\"amountCents\":2500}", context("preview", null)), Map.class);
    assertEquals("EXECUTED", envelope.get("outcome"));
    return JSON.fromJson((String) envelope.get("output"), RefundPreview.class);
  }

  private static ExecutionOutcome businessOutcome(String response) {
    var envelope = JSON.fromJson(response, Map.class);
    assertEquals("EXECUTED", envelope.get("outcome"));
    return JSON.fromJson((String) envelope.get("output"), ExecutionOutcome.class);
  }

  private static String call(RefundWorkspace workspace, String name, String input, ToolContext context) {
    return workspace.tools().stream().filter(tool -> tool.getToolDefinition().name().equals(name))
        .findFirst().orElseThrow().call(input, context);
  }

  private static Principal reviewer() {
    return new Principal("reviewer-a", Map.of("role", "reviewer", "tenant", "tenant-a"));
  }

  private static ToolContext context(String key, String approvalId) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "operator-a");
    values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    values.put(SpringAiToolContextKeys.ENVIRONMENT, "demo");
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, key);
    if (approvalId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalId);
    }
    return new ToolContext(values);
  }
}

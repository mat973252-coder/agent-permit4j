package io.github.agentpermit4j.springai.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.springai.AgentPermit;
import io.github.agentpermit4j.springai.GuardedToolMethods;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class GuardedToolMethodsAcceptanceTest {

  @Test
  void registersMultipleMethodsAndExecutesNormalizedArgumentsOnceWithTrustedIdentity() {
    var target = new OrderMethods();
    var callbacks = GuardedToolMethods.fromAnnotated(dependencies(), target);
    assertEquals(2, callbacks.size());
    var callback = callbacks.stream()
        .filter(tool -> tool.getToolDefinition().name().equals("orders.refund")).findFirst().orElseThrow();
    var pending = callback.call("{\"orderId\":\"order-1\",\"amount\":100}", context(null));
    assertTrue(pending.contains("APPROVAL_REQUIRED"));
    assertEquals(0, target.calls.get());
    var input = "{\"orderId\":\"order-1\",\"amount\":100,\"agentPermit.principalId\":\"forged\"}";
    var first = callback.call(input, context("approval-1"));
    assertTrue(first.contains("EXECUTED"));
    assertEquals(first, callback.call(input, context("approval-1")));
    assertEquals(1, target.calls.get());
    assertEquals("order-1/99/trusted-user", target.observed);
    assertThrows(UnsupportedOperationException.class, () -> callbacks.clear());
  }

  @Test
  void rejectsDuplicateToolNamesAndUnprotectedMethodsAtRegistration() {
    assertThrows(IllegalArgumentException.class,
        () -> GuardedToolMethods.fromAnnotated(dependencies(), new OrderMethods(), new OrderMethods()));
    assertThrows(IllegalArgumentException.class,
        () -> GuardedToolMethods.fromAnnotated(dependencies(), new UnprotectedMethod()));
  }

  private static GuardedToolMethods.Dependencies dependencies() {
    return new GuardedToolMethods.Dependencies(
        invocation -> new GateDecision(true, "VALID"),
        invocation -> {
          var arguments = new HashMap<>(invocation.arguments());
          arguments.computeIfPresent("amount", (key, value) -> "99");
          return new ToolInvocation(invocation.descriptor(),
              new Principal("trusted-user", Map.of()), invocation.action(),
              invocation.resource(), invocation.context(), arguments);
        },
        invocation -> new GateDecision(true, "ALLOWED"),
        invocation -> new RiskAssessment(RiskLevel.LOW, "READ"),
        (requestId, invocation) -> new GateDecision("approval-1".equals(requestId), "APPROVAL_VALID"),
        new InMemoryResultIdempotencyGuard(), event -> {}, supplied -> supplied);
  }

  private static ToolContext context(String approvalId) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "user");
    values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    values.put(SpringAiToolContextKeys.ENVIRONMENT, "test");
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "one-operation");
    if (approvalId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalId);
    }
    return new ToolContext(values);
  }

  public static class OrderMethods {
    final AtomicInteger calls = new AtomicInteger();
    String observed;

    @Tool(name = "orders.refund", description = "Refund an order")
    @AgentPermit(resourceType = "order", resourceArg = "orderId")
    public String refund(String orderId, long amount, ToolContext context) {
      calls.incrementAndGet();
      observed = orderId + "/" + amount + "/"
          + context.getContext().get(SpringAiToolContextKeys.PRINCIPAL_ID);
      return observed;
    }

    @Tool(name = "orders.lookup", description = "Read an order")
    @AgentPermit(resourceType = "order", resourceArg = "orderId", risk = RiskLevel.LOW)
    public String lookup(String orderId) {
      return orderId;
    }
  }

  public static class UnprotectedMethod {
    @Tool(description = "Must not be silently exposed")
    public String unprotected(String orderId) {
      return orderId;
    }
  }
}

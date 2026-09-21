package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.ExecutionOutcome;
import java.util.Map;
import java.util.function.Supplier;

final class RefundPolicy {
  private String revision = "refund-v1";

  synchronized String revision() {
    return revision;
  }

  synchronized void revision(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("policy revision is required");
    }
    revision = value;
  }

  synchronized ExecutionOutcome execute(String expectedRevision, Supplier<ExecutionOutcome> action) {
    if (!revision.equals(expectedRevision)) {
      throw new IllegalStateException("refund policy changed");
    }
    return action.get();
  }

  ToolInvocation normalize(ToolInvocation invocation) {
    if (!invocation.descriptor().name().equals("orders.refund")) {
      return invocation;
    }
    return new ToolInvocation(invocation.descriptor(), invocation.principal(), invocation.action(),
        new Resource("refund", invocation.resource().identifier(), Map.of("policyRevision", revision())),
        invocation.context(), invocation.arguments());
  }

  GateDecision validate(ToolInvocation invocation) {
    if (invocation.descriptor().name().equals("orders.lookup")) {
      return new GateDecision(true, "REFUND_INPUT_VALID");
    }
    try {
      if (Long.parseLong(invocation.arguments().get("amountCents")) <= 0) {
        return new GateDecision(false, "REFUND_AMOUNT_INVALID");
      }
      if (invocation.descriptor().name().equals("orders.refund")) {
        if (Long.parseLong(invocation.arguments().get("expectedVersion")) < 0) {
          return new GateDecision(false, "REFUND_VERSION_INVALID");
        }
      }
      return new GateDecision(true, "REFUND_INPUT_VALID");
    } catch (NumberFormatException exception) {
      return new GateDecision(false, "REFUND_INPUT_INVALID");
    }
  }

  GateDecision authorize(ToolInvocation invocation) {
    if (!"operator-a".equals(invocation.principal().id())
        || !"tenant-a".equals(invocation.context().tenantId())
        || !"demo".equals(invocation.context().environment())) {
      return new GateDecision(false, "REFUND_REQUESTER_DENIED");
    }
    if (invocation.descriptor().name().equals("orders.refund")
        && !revision().equals(invocation.arguments().get("policyRevision"))) {
      return new GateDecision(false, "REFUND_POLICY_CHANGED");
    }
    return new GateDecision(true, "REFUND_REQUESTER_ALLOWED");
  }
}

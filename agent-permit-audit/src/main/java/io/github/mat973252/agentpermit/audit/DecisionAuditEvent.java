package io.github.mat973252.agentpermit.audit;

import io.github.mat973252.agentpermit.core.DecisionResult;
import java.util.Objects;

public record DecisionAuditEvent(
    String toolName, String principalId, String tenantId, DecisionResult decision) {

  public DecisionAuditEvent {
    toolName = requireText(toolName, "toolName");
    principalId = requireText(principalId, "principalId");
    tenantId = requireText(tenantId, "tenantId");
    decision = Objects.requireNonNull(decision, "decision");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

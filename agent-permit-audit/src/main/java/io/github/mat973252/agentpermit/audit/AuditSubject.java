package io.github.mat973252.agentpermit.audit;

import java.util.Objects;

public record AuditSubject(String toolName, String principalId, String tenantId) {

  public AuditSubject {
    toolName = requireText(toolName, "toolName");
    principalId = requireText(principalId, "principalId");
    tenantId = requireText(tenantId, "tenantId");
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

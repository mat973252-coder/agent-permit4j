package io.github.mat973252.agentpermit.core;

public record InvocationContext(String tenantId, String environment) {

  public InvocationContext {
    tenantId = DomainValidation.requireText(tenantId, "tenantId");
    environment = DomainValidation.requireText(environment, "environment");
  }
}

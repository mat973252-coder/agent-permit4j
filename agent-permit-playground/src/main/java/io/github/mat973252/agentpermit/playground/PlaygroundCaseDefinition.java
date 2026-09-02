package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;

record PlaygroundCaseDefinition(
    String id,
    String scenario,
    String title,
    RiskLevel riskLevel,
    ToolInvocation invocation,
    String idempotencyKey) {

  PlaygroundCaseDefinition {
    id = requireText(id, "id");
    scenario = requireText(scenario, "scenario");
    title = requireText(title, "title");
    riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
    invocation = Objects.requireNonNull(invocation, "invocation");
    idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

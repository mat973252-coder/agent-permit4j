package io.github.agentpermit4j.playground;

import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
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

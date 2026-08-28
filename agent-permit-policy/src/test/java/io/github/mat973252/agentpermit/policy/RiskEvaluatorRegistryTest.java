package io.github.mat973252.agentpermit.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskEvaluatorRegistryTest {

  @Test
  void routesByResourceTypeAndPreservesAssessment() {
    var registry =
        new RiskEvaluatorRegistry(
            Map.of(
                "sql", invocation -> new RiskAssessment(RiskLevel.LOW, "SQL_RESULT"),
                "http", invocation -> new RiskAssessment(RiskLevel.HIGH, "HTTP_RESULT")));

    assertEquals("SQL_RESULT", registry.evaluate(invocation("sql")).reasonCode());
    assertEquals("HTTP_RESULT", registry.evaluate(invocation("http")).reasonCode());
  }

  @Test
  void deniesResourceTypesWithoutRegisteredEvaluator() {
    var registry = new RiskEvaluatorRegistry(Map.of());

    var assessment = registry.evaluate(invocation("messaging"));

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals("RISK_EVALUATOR_UNAVAILABLE", assessment.reasonCode());
  }

  @Test
  void snapshotsRuntimeConfiguration() {
    var configured = new HashMap<String, RiskEvaluator>();
    configured.put("http", invocation -> new RiskAssessment(RiskLevel.LOW, "HTTP_RESULT"));
    var registry = new RiskEvaluatorRegistry(configured);

    configured.clear();

    assertEquals("HTTP_RESULT", registry.evaluate(invocation("http")).reasonCode());
  }

  @Test
  void deniesWhenRegisteredEvaluatorReturnsNoAssessment() {
    var registry = new RiskEvaluatorRegistry(Map.of("http", invocation -> null));

    var assessment = registry.evaluate(invocation("http"));

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals("RISK_EVALUATION_FAILED", assessment.reasonCode());
  }

  @Test
  void deniesWhenRegisteredEvaluatorFails() {
    var registry =
        new RiskEvaluatorRegistry(
            Map.of(
                "http",
                invocation -> {
                  throw new IllegalStateException("driver detail");
                }));

    var assessment = registry.evaluate(invocation("http"));

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals("RISK_EVALUATION_FAILED", assessment.reasonCode());
  }

  private static ToolInvocation invocation(String resourceType) {
    return new ToolInvocation(
        new ToolDescriptor(
            "tool.execute",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of()),
        new Action("tool.execute"),
        new Resource(resourceType, "resource://target", Map.of()),
        new InvocationContext("tenant-a", "test"),
        Map.of());
  }
}

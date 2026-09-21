package io.github.agentpermit4j.policy;

import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.Map;
import java.util.Objects;

public final class RiskEvaluatorRegistry implements RiskEvaluator {

  private final Map<String, RiskEvaluator> evaluators;

  public RiskEvaluatorRegistry(Map<String, RiskEvaluator> evaluators) {
    this.evaluators = Map.copyOf(Objects.requireNonNull(evaluators, "evaluators"));
  }

  @Override
  public RiskAssessment evaluate(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    var evaluator = evaluators.get(invocation.resource().type());
    if (evaluator == null) {
      return denied("RISK_EVALUATOR_UNAVAILABLE");
    }
    try {
      var assessment = evaluator.evaluate(invocation);
      return assessment == null ? denied("RISK_EVALUATION_FAILED") : assessment;
    } catch (RuntimeException exception) {
      return denied("RISK_EVALUATION_FAILED");
    }
  }

  private static RiskAssessment denied(String reasonCode) {
    return new RiskAssessment(RiskLevel.DENY, reasonCode);
  }
}

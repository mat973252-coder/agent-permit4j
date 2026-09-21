package io.github.agentpermit4j.policy;

import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.ToolInvocation;

@FunctionalInterface
public interface RiskEvaluator {

  RiskAssessment evaluate(ToolInvocation invocation);
}

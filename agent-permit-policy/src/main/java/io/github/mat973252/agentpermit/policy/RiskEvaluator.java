package io.github.mat973252.agentpermit.policy;

import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.ToolInvocation;

@FunctionalInterface
public interface RiskEvaluator {

  RiskAssessment evaluate(ToolInvocation invocation);
}

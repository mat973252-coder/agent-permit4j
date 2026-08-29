package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import java.util.Objects;

public record ToolExecutionResult(DecisionResult decision, String output) {

  public ToolExecutionResult {
    decision = Objects.requireNonNull(decision, "decision");
    if (decision.outcome() == DecisionOutcome.EXECUTED && output == null) {
      throw new IllegalArgumentException("executed result requires output");
    }
    if (decision.outcome() != DecisionOutcome.EXECUTED && output != null) {
      throw new IllegalArgumentException("non-executed result cannot contain output");
    }
  }
}

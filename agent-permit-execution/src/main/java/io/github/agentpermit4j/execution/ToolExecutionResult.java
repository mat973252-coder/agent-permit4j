package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
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

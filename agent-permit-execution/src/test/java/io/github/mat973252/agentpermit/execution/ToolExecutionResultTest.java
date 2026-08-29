package io.github.mat973252.agentpermit.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import org.junit.jupiter.api.Test;

class ToolExecutionResultTest {

  @Test
  void outputExistsOnlyForExecutedDecisions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionResult(
                new DecisionResult(DecisionOutcome.EXECUTED, "EXECUTED"), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionResult(
                new DecisionResult(DecisionOutcome.DENIED, "DENIED"), "unexpected-output"));
  }
}

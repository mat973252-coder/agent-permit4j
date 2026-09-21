package io.github.agentpermit4j.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
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

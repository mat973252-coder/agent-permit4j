package io.github.mat973252.agentpermit.core;

import java.util.Objects;

public record DecisionResult(DecisionOutcome outcome, String reasonCode) {

  public DecisionResult {
    outcome = Objects.requireNonNull(outcome, "outcome");
    reasonCode = DomainValidation.requireReasonCode(reasonCode);
  }
}

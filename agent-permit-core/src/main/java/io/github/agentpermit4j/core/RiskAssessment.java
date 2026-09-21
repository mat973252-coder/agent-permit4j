package io.github.agentpermit4j.core;

import java.util.Objects;

public record RiskAssessment(RiskLevel level, String reasonCode) {

  public RiskAssessment {
    level = Objects.requireNonNull(level, "level");
    reasonCode = DomainValidation.requireReasonCode(reasonCode);
  }
}

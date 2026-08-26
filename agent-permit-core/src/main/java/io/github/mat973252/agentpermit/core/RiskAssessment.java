package io.github.mat973252.agentpermit.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record RiskAssessment(RiskLevel level, String reasonCode) {

  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

  public RiskAssessment {
    level = Objects.requireNonNull(level, "level");
    reasonCode = DomainValidation.requireText(reasonCode, "reasonCode");
    if (!REASON_CODE.matcher(reasonCode).matches()) {
      throw new IllegalArgumentException("reasonCode must be machine-readable");
    }
  }
}

package io.github.mat973252.agentpermit.core;

import java.util.Objects;
import java.util.regex.Pattern;

final class DomainValidation {

  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

  private DomainValidation() {}

  static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  static String requireReasonCode(String value) {
    var reasonCode = requireText(value, "reasonCode");
    if (!REASON_CODE.matcher(reasonCode).matches()) {
      throw new IllegalArgumentException("reasonCode must be machine-readable");
    }
    return reasonCode;
  }
}

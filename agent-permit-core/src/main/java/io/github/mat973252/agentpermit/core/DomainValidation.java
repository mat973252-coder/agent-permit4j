package io.github.mat973252.agentpermit.core;

import java.util.Objects;

final class DomainValidation {

  private DomainValidation() {}

  static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

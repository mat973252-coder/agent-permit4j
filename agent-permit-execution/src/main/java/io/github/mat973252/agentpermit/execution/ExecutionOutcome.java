package io.github.mat973252.agentpermit.execution;

import java.util.Objects;

/** Read-only operation view. A reference is an identifier, never authorization to read or execute. */
public record ExecutionOutcome(String reference, ExecutionStatus status, String reasonCode) {
  public ExecutionOutcome {
    if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException("opaque operation reference is required");
    }
    Objects.requireNonNull(status, "status");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException("stable outcome reason code is required");
    }
  }
}

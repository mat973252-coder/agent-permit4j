package io.github.mat973252.agentpermit.approval;

import java.time.Instant;
import java.util.Objects;

/** Minimal successful review record; no arguments, credentials, or reviewer attributes. */
public record ApprovalDecision(String requestId, String approverId, String tenantId, Instant decidedAt) {
  public ApprovalDecision {
    if (requestId == null || requestId.isBlank() || approverId == null || approverId.isBlank()
        || tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("request, approver, and tenant are required");
    }
    Objects.requireNonNull(decidedAt, "decidedAt");
  }
}

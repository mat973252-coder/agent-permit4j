package io.github.agentpermit4j.approval;

import java.time.Instant;
import java.util.Objects;

public record ApprovalRequest(
    String id, InvocationFingerprint fingerprint, Instant expiresAt) {

  public ApprovalRequest {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }
}

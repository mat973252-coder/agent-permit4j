package io.github.mat973252.agentpermit.playground.refund;

public record RefundExpectation(long amountCents, long expectedVersion, String policyRevision) {
  public RefundExpectation {
    if (amountCents <= 0 || expectedVersion < 0 || policyRevision == null || policyRevision.isBlank()) {
      throw new IllegalArgumentException("refund expectation is invalid");
    }
  }
}

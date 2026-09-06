package io.github.mat973252.agentpermit.playground.refund;

public record OrderBalance(
    String tenantId, String orderId, long paidCents, long refundedCents, long version) {

  public long refundableCents() {
    return paidCents - refundedCents;
  }
}

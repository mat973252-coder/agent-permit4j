package io.github.agentpermit4j.playground.refund;

public record OrderBalance(
    String tenantId, String orderId, long paidCents, long refundedCents, long version) {

  public long refundableCents() {
    return paidCents - refundedCents;
  }
}

package io.github.agentpermit4j.playground.refund;

public record RefundCommand(
    String tenantId, String orderId, long amountCents, long expectedVersion) {

  public RefundCommand {
    if (tenantId == null || tenantId.isBlank() || orderId == null || orderId.isBlank()) {
      throw new IllegalArgumentException("tenant and order are required");
    }
    if (amountCents <= 0 || expectedVersion < 0) {
      throw new IllegalArgumentException("positive amount and non-negative version required");
    }
  }
}

package io.github.mat973252.agentpermit.playground.refund;

import java.util.HashMap;
import java.util.Map;

/** Synthetic payments only; the rejection happens before recording any payment. */
public final class PaymentSimulator {

  private final Map<RefundCommand, String> payments = new HashMap<>();
  private boolean rejectNext;

  public synchronized String refund(RefundCommand command) {
    var existing = payments.get(command);
    if (existing != null) {
      return existing;
    }
    if (rejectNext) {
      rejectNext = false;
      throw new IllegalStateException("simulated payment unavailable");
    }
    var paymentId = command.tenantId() + "/" + command.orderId() + "/" + command.expectedVersion();
    if (payments.containsValue(paymentId)) {
      throw new IllegalStateException("payment operation amount mismatch");
    }
    payments.put(command, paymentId);
    return paymentId;
  }

  public synchronized void rejectNextPayment() {
    rejectNext = true;
  }

  public synchronized int paymentCount() {
    return payments.size();
  }
}

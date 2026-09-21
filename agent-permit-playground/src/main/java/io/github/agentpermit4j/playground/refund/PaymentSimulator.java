package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.execution.ExecutionStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Synthetic downstream state; new client instances can share its retained fixture state. */
public final class PaymentSimulator implements PaymentQuery {

  private final State state;
  private boolean rejectNext;
  private boolean loseNextResponse;

  public PaymentSimulator() {
    this(new State());
  }

  private PaymentSimulator(State state) {
    this.state = state;
  }

  public PaymentSimulator reconnect() {
    return new PaymentSimulator(state);
  }

  public String refund(RefundCommand command) {
    var paymentId = command.tenantId() + "/" + command.orderId() + "/" + command.expectedVersion();
    var receipt = refund("legacy/" + paymentId, command);
    if (receipt.status() == ExecutionStatus.FAILED) {
      throw new IllegalStateException("simulated payment rejected");
    }
    return paymentId;
  }

  public PaymentReceipt refund(String reference, RefundCommand command) {
    synchronized (state) {
      state.requests++;
      var existing = state.receipts.get(reference);
      if (existing != null) {
        if (!existing.command().equals(command)) {
          throw new IllegalStateException("payment operation mismatch");
        }
        return existing;
      }
      var status = rejectNext ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
      rejectNext = false;
      var receipt = new PaymentReceipt(reference, command, status);
      state.receipts.put(reference, receipt);
      if (loseNextResponse) {
        loseNextResponse = false;
        throw new IllegalStateException("simulated payment response lost");
      }
      return receipt;
    }
  }

  @Override
  public Optional<PaymentReceipt> lookup(String reference) {
    synchronized (state) {
      state.queries++;
      return Optional.ofNullable(state.receipts.get(reference));
    }
  }

  public void loseNextResponse() {
    synchronized (state) {
      loseNextResponse = true;
    }
  }

  public void rejectNextPayment() {
    synchronized (state) {
      rejectNext = true;
    }
  }

  public int paymentCount() {
    synchronized (state) {
      return (int) state.receipts.values().stream()
          .filter(receipt -> receipt.status() == ExecutionStatus.SUCCEEDED).count();
    }
  }

  public int requestCount() {
    synchronized (state) {
      return state.requests;
    }
  }

  public int queryCount() {
    synchronized (state) {
      return state.queries;
    }
  }

  private static final class State {
    final Map<String, PaymentReceipt> receipts = new HashMap<>();
    int requests;
    int queries;
  }
}

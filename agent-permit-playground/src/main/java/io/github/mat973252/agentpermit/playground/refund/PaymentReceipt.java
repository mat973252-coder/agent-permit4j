package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import java.util.Objects;

/** Synthetic downstream evidence, including the command to verify before settlement. */
public record PaymentReceipt(String reference, RefundCommand command, ExecutionStatus status) {
  public PaymentReceipt {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(command, "command");
    if (status != ExecutionStatus.SUCCEEDED && status != ExecutionStatus.FAILED) {
      throw new IllegalArgumentException("payment receipt must be conclusive");
    }
  }
}

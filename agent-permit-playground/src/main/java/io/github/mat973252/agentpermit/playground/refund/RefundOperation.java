package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;

record RefundOperation(String reference, RefundCommand command, RefundOwner owner,
    String policyRevision, ExecutionOutcome outcome) {
  boolean matches(RefundExpectation expected) {
    return command.amountCents() == expected.amountCents()
        && command.expectedVersion() == expected.expectedVersion()
        && policyRevision.equals(expected.policyRevision());
  }

  ExecutionOutcome outcome(ExecutionStatus status, String reasonCode) {
    return new ExecutionOutcome(reference, status, reasonCode);
  }
}

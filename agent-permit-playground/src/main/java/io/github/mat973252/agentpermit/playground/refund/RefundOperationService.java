package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Trusted demo application service. Execution requires the guarded approval path; queries never pay. */
public final class RefundOperationService {
  private final RefundOperationStore store;
  private final RefundOperationTransactions transactions;
  private final PaymentSimulator payments;
  private final PaymentQuery query;

  public RefundOperationService(DataSource source, PaymentSimulator payments) {
    this(source, payments, payments);
  }

  public RefundOperationService(DataSource source, PaymentSimulator payments, PaymentQuery query) {
    store = new RefundOperationStore(Objects.requireNonNull(source, "source"));
    transactions = new RefundOperationTransactions(source);
    this.payments = Objects.requireNonNull(payments, "payments");
    this.query = Objects.requireNonNull(query, "query");
  }

  public ExecutionOutcome prepare(RefundCommand command, Principal principal,
      InvocationContext context, String policyRevision) {
    if (!command.tenantId().equals(context.tenantId()) || policyRevision == null || policyRevision.isBlank()) {
      throw new IllegalArgumentException("refund proposal scope is invalid");
    }
    return store.prepare(command, RefundOwner.from(principal, context), policyRevision).outcome();
  }

  public ExecutionOutcome execute(String reference, RefundExpectation expected,
      Principal principal, InvocationContext context) {
    var operation = store.find(reference, RefundOwner.from(principal, context)).orElseThrow(
        () -> new IllegalArgumentException("refund operation not found"));
    if (!operation.matches(expected)) {
      throw new IllegalArgumentException("refund operation expectation mismatch");
    }
    var claim = transactions.start(operation);
    if (!claim.owner()) {
      return claim.outcome();
    }
    try {
      var receipt = payments.refund(reference, operation.command());
      return transactions.settle(operation, receipt);
    } catch (RuntimeException exception) {
      return operation.outcome(ExecutionStatus.UNKNOWN, "REFUND_OUTCOME_UNKNOWN");
    }
  }

  public Optional<ExecutionOutcome> inspect(String reference, Principal principal, InvocationContext context) {
    return store.find(reference, RefundOwner.from(principal, context)).map(RefundOperation::outcome);
  }

  public Optional<ExecutionOutcome> reconcile(String reference, Principal principal, InvocationContext context) {
    return store.find(reference, RefundOwner.from(principal, context)).map(this::reconcile);
  }

  private ExecutionOutcome reconcile(RefundOperation operation) {
    if (operation.outcome().status() != ExecutionStatus.UNKNOWN) {
      return operation.outcome();
    }
    try {
      var receipt = query.lookup(operation.reference());
      if (receipt.isEmpty()) {
        return operation.outcome(ExecutionStatus.UNKNOWN, "REFUND_EVIDENCE_UNAVAILABLE");
      }
      return transactions.settle(operation, receipt.orElseThrow());
    } catch (RuntimeException exception) {
      return operation.outcome(ExecutionStatus.UNKNOWN, "REFUND_RECONCILIATION_UNAVAILABLE");
    }
  }
}

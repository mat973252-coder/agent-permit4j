package io.github.agentpermit4j.playground.refund;

import io.github.agentpermit4j.execution.ExecutionOutcome;
import io.github.agentpermit4j.execution.ExecutionStatus;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** The order reservation and operation state share one local transaction; payment never does. */
final class RefundOperationTransactions {
  private final DataSource source;

  RefundOperationTransactions(DataSource source) {
    this.source = source;
  }

  Claim start(RefundOperation operation) {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try {
        var current = RefundOperationStore.find(connection, operation.reference(), true).orElseThrow();
        if (current.outcome().status() != ExecutionStatus.NOT_STARTED) {
          connection.commit();
          return new Claim(false, current.outcome());
        }
        var reserved = reserve(connection, current);
        var status = reserved ? ExecutionStatus.UNKNOWN : ExecutionStatus.FAILED;
        var reason = reserved ? "REFUND_OUTCOME_UNKNOWN" : "REFUND_PRECONDITION_CHANGED";
        RefundOperationStore.update(connection, current.reference(), ExecutionStatus.NOT_STARTED, status, reason);
        connection.commit();
        return new Claim(reserved, current.outcome(status, reason));
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw RefundOperationStore.unavailable();
      }
    } catch (SQLException exception) {
      throw RefundOperationStore.unavailable();
    }
  }

  ExecutionOutcome settle(RefundOperation operation, PaymentReceipt receipt) {
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try {
        var current = RefundOperationStore.find(connection, operation.reference(), true).orElseThrow();
        if (current.outcome().status() != ExecutionStatus.UNKNOWN) {
          connection.commit();
          return current.outcome();
        }
        if (!receipt.reference().equals(current.reference()) || !receipt.command().equals(current.command())) {
          throw new IllegalArgumentException("payment evidence mismatch");
        }
        completeOrder(connection, current, receipt.status());
        var reason = receipt.status() == ExecutionStatus.SUCCEEDED ? "REFUND_CONFIRMED" : "REFUND_PAYMENT_REJECTED";
        RefundOperationStore.update(connection, current.reference(), ExecutionStatus.UNKNOWN, receipt.status(), reason);
        connection.commit();
        return current.outcome(receipt.status(), reason);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw RefundOperationStore.unavailable();
      }
    } catch (SQLException exception) {
      throw RefundOperationStore.unavailable();
    }
  }

  private static boolean reserve(Connection connection, RefundOperation operation) throws SQLException {
    try (var statement = connection.prepareStatement("UPDATE refund_order SET pending_reference = ? "
        + "WHERE tenant_id = ? AND order_id = ? AND order_version = ? "
        + "AND pending_reference IS NULL AND paid_cents - refunded_cents >= ?")) {
      statement.setString(1, operation.reference());
      statement.setString(2, operation.command().tenantId());
      statement.setString(3, operation.command().orderId());
      statement.setLong(4, operation.command().expectedVersion());
      statement.setLong(5, operation.command().amountCents());
      return statement.executeUpdate() == 1;
    }
  }

  private static void completeOrder(Connection connection, RefundOperation operation,
      ExecutionStatus status) throws SQLException {
    try (var statement = connection.prepareStatement("UPDATE refund_order SET pending_reference = NULL, "
        + "refunded_cents = refunded_cents + ?, order_version = order_version + ? "
        + "WHERE tenant_id = ? AND order_id = ? AND order_version = ? AND pending_reference = ? "
        + "AND paid_cents - refunded_cents >= ?")) {
      var amount = status == ExecutionStatus.SUCCEEDED ? operation.command().amountCents() : 0;
      statement.setLong(1, amount);
      statement.setInt(2, status == ExecutionStatus.SUCCEEDED ? 1 : 0);
      statement.setString(3, operation.command().tenantId());
      statement.setString(4, operation.command().orderId());
      statement.setLong(5, operation.command().expectedVersion());
      statement.setString(6, operation.reference());
      statement.setLong(7, amount);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("refund reservation changed");
      }
    }
  }

  record Claim(boolean owner, ExecutionOutcome outcome) {}
}

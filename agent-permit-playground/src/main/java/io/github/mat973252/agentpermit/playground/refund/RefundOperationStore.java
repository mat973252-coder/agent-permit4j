package io.github.mat973252.agentpermit.playground.refund;

import io.github.mat973252.agentpermit.execution.ExecutionOutcome;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Demo-owned business records; raw command fields are never returned by the public outcome view. */
final class RefundOperationStore {
  private final DataSource source;

  RefundOperationStore(DataSource source) {
    this.source = source;
  }

  static void initialize(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE refund_operation (operation_reference VARCHAR(128) PRIMARY KEY, "
          + "tenant_id VARCHAR(100) NOT NULL, order_id VARCHAR(100) NOT NULL, amount_cents BIGINT NOT NULL, "
          + "expected_version BIGINT NOT NULL, principal_id VARCHAR(100) NOT NULL, "
          + "environment VARCHAR(100) NOT NULL, policy_revision VARCHAR(100) NOT NULL, "
          + "operation_status SMALLINT NOT NULL, reason_code VARCHAR(100) NOT NULL, "
          + "CHECK (amount_cents > 0 AND expected_version >= 0), "
          + "CHECK (operation_status >= 0 AND operation_status <= 3))");
    }
  }

  RefundOperation prepare(RefundCommand command, RefundOwner owner, String revision) {
    var reference = UUID.randomUUID().toString();
    var outcome = new ExecutionOutcome(reference, ExecutionStatus.NOT_STARTED, "REFUND_NOT_STARTED");
    var operation = new RefundOperation(reference, command, owner, revision, outcome);
    try (var connection = source.getConnection();
        var statement = connection.prepareStatement("INSERT INTO refund_operation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1, reference);
      statement.setString(2, command.tenantId());
      statement.setString(3, command.orderId());
      statement.setLong(4, command.amountCents());
      statement.setLong(5, command.expectedVersion());
      statement.setString(6, owner.principalId());
      statement.setString(7, owner.environment());
      statement.setString(8, revision);
      statement.setInt(9, code(outcome.status()));
      statement.setString(10, outcome.reasonCode());
      statement.executeUpdate();
      return operation;
    } catch (SQLException exception) {
      throw unavailable();
    }
  }

  Optional<RefundOperation> find(String reference, RefundOwner owner) {
    try (var connection = source.getConnection()) {
      return find(connection, reference, false).filter(operation -> operation.owner().equals(owner));
    } catch (SQLException | IllegalArgumentException exception) {
      throw unavailable();
    }
  }

  static Optional<RefundOperation> find(Connection connection, String reference, boolean lock) throws SQLException {
    try (var statement = connection.prepareStatement(
        "SELECT * FROM refund_operation WHERE operation_reference = ?" + (lock ? " FOR UPDATE" : ""))) {
      statement.setString(1, reference);
      try (var row = statement.executeQuery()) {
        return row.next() ? Optional.of(read(row)) : Optional.empty();
      }
    }
  }

  static void update(Connection connection, String reference, ExecutionStatus expected,
      ExecutionStatus status, String reason) throws SQLException {
    try (var statement = connection.prepareStatement("UPDATE refund_operation SET operation_status = ?, "
        + "reason_code = ? WHERE operation_reference = ? AND operation_status = ?")) {
      statement.setInt(1, code(status));
      statement.setString(2, reason);
      statement.setString(3, reference);
      statement.setInt(4, code(expected));
      if (statement.executeUpdate() != 1) {
        throw new SQLException("refund operation transition lost");
      }
    }
  }

  private static RefundOperation read(ResultSet row) throws SQLException {
    var reference = row.getString("operation_reference");
    var tenant = row.getString("tenant_id");
    return new RefundOperation(reference,
        new RefundCommand(tenant, row.getString("order_id"), row.getLong("amount_cents"), row.getLong("expected_version")),
        new RefundOwner(row.getString("principal_id"), tenant, row.getString("environment")),
        row.getString("policy_revision"), new ExecutionOutcome(reference,
            status(row.getInt("operation_status")), row.getString("reason_code")));
  }

  private static int code(ExecutionStatus status) {
    return switch (status) {
      case NOT_STARTED -> 0;
      case UNKNOWN -> 1;
      case SUCCEEDED -> 2;
      case FAILED -> 3;
    };
  }

  private static ExecutionStatus status(int code) {
    return switch (code) {
      case 0 -> ExecutionStatus.NOT_STARTED;
      case 1 -> ExecutionStatus.UNKNOWN;
      case 2 -> ExecutionStatus.SUCCEEDED;
      case 3 -> ExecutionStatus.FAILED;
      default -> throw new IllegalArgumentException("invalid operation state");
    };
  }

  static IllegalStateException unavailable() {
    return new IllegalStateException("refund operation storage unavailable");
  }
}

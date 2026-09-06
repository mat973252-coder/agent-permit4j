package io.github.mat973252.agentpermit.playground.refund;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Local example ledger. A row's conditional update wins before any simulated payment. */
public final class RefundLedger {

  private final DataSource dataSource;
  private final PaymentSimulator payments;

  public RefundLedger(DataSource dataSource, PaymentSimulator payments) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.payments = Objects.requireNonNull(payments, "payments");
  }

  public void initialize() {
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE refund_order (tenant_id VARCHAR(100) NOT NULL, "
          + "order_id VARCHAR(100) NOT NULL, paid_cents BIGINT NOT NULL, "
          + "refunded_cents BIGINT NOT NULL, order_version BIGINT NOT NULL, "
          + "PRIMARY KEY (tenant_id, order_id), "
          + "CHECK (paid_cents >= refunded_cents AND refunded_cents >= 0 AND order_version >= 0))");
    } catch (SQLException exception) {
      throw unavailable();
    }
  }

  public void createOrder(OrderBalance order) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement("INSERT INTO refund_order VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, order.tenantId());
      statement.setString(2, order.orderId());
      statement.setLong(3, order.paidCents());
      statement.setLong(4, order.refundedCents());
      statement.setLong(5, order.version());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw unavailable();
    }
  }

  public OrderBalance find(String tenantId, String orderId) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(
            "SELECT paid_cents, refunded_cents, order_version FROM refund_order "
                + "WHERE tenant_id = ? AND order_id = ?")) {
      statement.setString(1, tenantId);
      statement.setString(2, orderId);
      try (var result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalArgumentException("order not found");
        }
        return new OrderBalance(tenantId, orderId,
            result.getLong(1), result.getLong(2), result.getLong(3));
      }
    } catch (SQLException exception) {
      throw unavailable();
    }
  }

  public String refund(RefundCommand command) {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        claimOrder(connection, command);
        var paymentId = payments.refund(command);
        connection.commit();
        return paymentId;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw new IllegalStateException("refund execution failed");
      }
    } catch (SQLException exception) {
      throw unavailable();
    }
  }

  private static void claimOrder(Connection connection, RefundCommand command) throws SQLException {
    try (var statement = connection.prepareStatement(
        "UPDATE refund_order SET refunded_cents = refunded_cents + ?, order_version = order_version + 1 "
            + "WHERE tenant_id = ? AND order_id = ? AND order_version = ? "
            + "AND paid_cents - refunded_cents >= ?")) {
      statement.setLong(1, command.amountCents());
      statement.setString(2, command.tenantId());
      statement.setString(3, command.orderId());
      statement.setLong(4, command.expectedVersion());
      statement.setLong(5, command.amountCents());
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("refund precondition changed");
      }
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("refund ledger unavailable");
  }
}

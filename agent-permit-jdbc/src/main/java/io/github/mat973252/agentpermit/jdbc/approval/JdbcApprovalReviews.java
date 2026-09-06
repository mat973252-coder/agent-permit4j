package io.github.mat973252.agentpermit.jdbc.approval;

import io.github.mat973252.agentpermit.approval.ApprovalAuthorizer;
import io.github.mat973252.agentpermit.approval.ApprovalDecision;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

final class JdbcApprovalReviews {

  private final DataSource dataSource;
  private final Clock clock;
  private final InvocationFingerprinter fingerprinter;
  private final ApprovalAuthorizer authorizer;

  JdbcApprovalReviews(DataSource dataSource, Clock clock,
      InvocationFingerprinter fingerprinter, ApprovalAuthorizer authorizer) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.fingerprinter = fingerprinter;
    this.authorizer = authorizer;
  }

  GateDecision approve(String requestId, ToolInvocation invocation, Principal approver) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(approver, "approver");
    var fingerprint = fingerprinter.fingerprint(invocation).value();
    try (var connection = dataSource.getConnection()) {
      var approval = JdbcApprovalService.find(connection, requestId);
      var invalid = invalid(approval, fingerprint);
      if (invalid != null) {
        return invalid;
      }
      var authorization = authorize(approver, invocation);
      if (!authorization.permitted()) {
        return authorization;
      }
      var decision = new ApprovalDecision(requestId, approver.id(),
          invocation.context().tenantId(), Instant.ofEpochMilli(clock.millis()));
      return recordDecision(connection, fingerprint, decision);
    } catch (SQLException | IllegalArgumentException exception) {
      return denied("APPROVAL_STORAGE_UNAVAILABLE");
    }
  }

  Optional<ApprovalDecision> decision(String requestId) {
    try (var connection = dataSource.getConnection()) {
      return decision(connection, requestId);
    } catch (SQLException exception) {
      throw new IllegalStateException("approval storage unavailable");
    }
  }

  private GateDecision invalid(StoredApproval approval, String fingerprint) {
    if (approval == null) {
      return denied("APPROVAL_NOT_FOUND");
    }
    if (approval.expiredAt(clock.millis())) {
      return denied("APPROVAL_EXPIRED");
    }
    if (!approval.fingerprint().equals(fingerprint)) {
      return denied("APPROVAL_INVOCATION_MISMATCH");
    }
    if (approval.approvalState() == 0 || approval.approvalState() == 1) {
      return denied("APPROVAL_REVIEW_NOT_REQUESTED");
    }
    return approval.isReviewPending() || approval.approvalState() == 3
        ? null : denied("APPROVAL_STORAGE_UNAVAILABLE");
  }

  private GateDecision authorize(Principal approver, ToolInvocation invocation) {
    try {
      return Objects.requireNonNull(authorizer.authorize(approver, invocation));
    } catch (RuntimeException exception) {
      return denied("APPROVER_AUTHORIZATION_FAILED");
    }
  }

  private GateDecision recordDecision(Connection connection, String fingerprint, ApprovalDecision decision)
      throws SQLException {
    connection.setAutoCommit(false);
    try {
      if (markReviewed(connection, fingerprint, decision) == 0) {
        connection.rollback();
        var invalid = invalid(JdbcApprovalService.find(connection, decision.requestId()), fingerprint);
        if (invalid != null) {
          return invalid;
        }
        return decision(connection, decision.requestId()).isPresent()
            ? new GateDecision(true, "APPROVAL_ALREADY_APPROVED")
            : denied("APPROVAL_STORAGE_UNAVAILABLE");
      }
      insertDecision(connection, decision);
      connection.commit();
      return new GateDecision(true, "APPROVAL_APPROVED");
    } catch (SQLException | RuntimeException exception) {
      connection.rollback();
      throw exception;
    }
  }

  private static int markReviewed(Connection connection, String fingerprint, ApprovalDecision decision)
      throws SQLException {
    try (var statement = connection.prepareStatement(
        "UPDATE agent_permit_approval_request SET approved = 3 "
            + "WHERE request_id = ? AND approved = 2 AND fingerprint = ? AND expires_at_epoch_millis > ?")) {
      statement.setString(1, decision.requestId());
      statement.setString(2, fingerprint);
      statement.setLong(3, decision.decidedAt().toEpochMilli());
      return statement.executeUpdate();
    }
  }

  private static void insertDecision(Connection connection, ApprovalDecision decision) throws SQLException {
    try (var statement = connection.prepareStatement(
        "INSERT INTO agent_permit_approval_decision "
            + "(request_id, approver_id, tenant_id, decided_at_epoch_millis) VALUES (?, ?, ?, ?)")) {
      statement.setString(1, decision.requestId());
      statement.setString(2, decision.approverId());
      statement.setString(3, decision.tenantId());
      statement.setLong(4, decision.decidedAt().toEpochMilli());
      statement.executeUpdate();
    }
  }

  private static Optional<ApprovalDecision> decision(Connection connection, String requestId)
      throws SQLException {
    try (var statement = connection.prepareStatement(
        "SELECT approver_id, tenant_id, decided_at_epoch_millis "
            + "FROM agent_permit_approval_decision WHERE request_id = ?")) {
      statement.setString(1, requestId);
      try (var result = statement.executeQuery()) {
        return result.next()
            ? Optional.of(new ApprovalDecision(requestId, result.getString(1), result.getString(2),
                Instant.ofEpochMilli(result.getLong(3))))
            : Optional.empty();
      }
    }
  }

  private static GateDecision denied(String reasonCode) {
    return new GateDecision(false, reasonCode);
  }
}

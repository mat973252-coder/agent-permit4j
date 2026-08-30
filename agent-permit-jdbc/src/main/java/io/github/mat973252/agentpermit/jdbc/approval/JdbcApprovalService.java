package io.github.mat973252.agentpermit.jdbc.approval;

import io.github.mat973252.agentpermit.approval.ApprovalRequest;
import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** JDBC-backed approval requests using the bundled explicit schema. */
public final class JdbcApprovalService implements ApprovalVerifier {

  private static final String INSERT_REQUEST =
      "INSERT INTO agent_permit_approval_request "
          + "(request_id, fingerprint, expires_at_epoch_millis, approved) "
          + "VALUES (?, ?, ?, 0)";
  private static final String APPROVE_REQUEST =
      "UPDATE agent_permit_approval_request SET approved = 1 "
          + "WHERE request_id = ? AND approved = 0 AND expires_at_epoch_millis > ?";
  private static final String SELECT_REQUEST =
      "SELECT fingerprint, expires_at_epoch_millis, approved "
          + "FROM agent_permit_approval_request WHERE request_id = ?";

  private final DataSource dataSource;
  private final Clock clock;
  private final Supplier<String> idGenerator;
  private final InvocationFingerprinter fingerprinter;

  public JdbcApprovalService(
      DataSource dataSource,
      Clock clock,
      Supplier<String> idGenerator,
      InvocationFingerprinter fingerprinter) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
  }

  public ApprovalRequest request(ToolInvocation normalizedInvocation, Duration lifetime) {
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    requirePositive(lifetime);
    var id = requireId(idGenerator.get());
    var fingerprint = fingerprinter.fingerprint(normalizedInvocation);
    var expiresAt = persistedInstant(Instant.now(clock).plus(lifetime));
    var request = new ApprovalRequest(id, fingerprint, expiresAt);
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(INSERT_REQUEST)) {
      statement.setString(1, id);
      statement.setString(2, fingerprint.value());
      statement.setLong(3, expiresAt.toEpochMilli());
      statement.executeUpdate();
      return request;
    } catch (SQLException ignored) {
      throw requestFailure();
    }
  }

  public GateDecision approve(String requestId) {
    long now = clock.millis();
    try (var connection = dataSource.getConnection()) {
      if (markApproved(connection, requestId, now)) {
        return allowed("APPROVAL_APPROVED");
      }
      return approvalFailure(find(connection, requestId), now);
    } catch (SQLException exception) {
      return denied("APPROVAL_STORAGE_UNAVAILABLE");
    }
  }

  @Override
  public GateDecision verify(String requestId, ToolInvocation normalizedInvocation) {
    Objects.requireNonNull(normalizedInvocation, "normalizedInvocation");
    try (var connection = dataSource.getConnection()) {
      return verification(
          find(connection, requestId),
          clock.millis(),
          fingerprinter.fingerprint(normalizedInvocation).value());
    } catch (SQLException exception) {
      return denied("APPROVAL_STORAGE_UNAVAILABLE");
    }
  }

  private boolean markApproved(Connection connection, String requestId, long now)
      throws SQLException {
    try (var statement = connection.prepareStatement(APPROVE_REQUEST)) {
      statement.setString(1, requestId);
      statement.setLong(2, now);
      return statement.executeUpdate() == 1;
    }
  }

  private StoredApproval find(Connection connection, String requestId) throws SQLException {
    try (var statement = connection.prepareStatement(SELECT_REQUEST)) {
      statement.setString(1, requestId);
      try (var result = statement.executeQuery()) {
        return result.next()
            ? new StoredApproval(result.getString(1), result.getLong(2), result.getInt(3))
            : null;
      }
    }
  }

  private static GateDecision approvalFailure(StoredApproval approval, long now) {
    if (approval == null) {
      return denied("APPROVAL_NOT_FOUND");
    }
    if (approval.expiredAt(now)) {
      return denied("APPROVAL_EXPIRED");
    }
    return approval.isApproved()
        ? allowed("APPROVAL_ALREADY_APPROVED")
        : denied("APPROVAL_STORAGE_UNAVAILABLE");
  }

  private static GateDecision verification(
      StoredApproval approval, long now, String fingerprint) {
    if (approval == null) {
      return denied("APPROVAL_NOT_FOUND");
    }
    if (approval.expiredAt(now)) {
      return denied("APPROVAL_EXPIRED");
    }
    if (approval.isPending()) {
      return denied("APPROVAL_PENDING");
    }
    if (!approval.isApproved()) {
      return denied("APPROVAL_STORAGE_UNAVAILABLE");
    }
    return approval.fingerprint().equals(fingerprint)
        ? allowed("APPROVAL_VALID")
        : denied("APPROVAL_INVOCATION_MISMATCH");
  }

  private static RuntimeException requestFailure() {
    return new IllegalStateException("approval storage unavailable");
  }

  private static void requirePositive(Duration lifetime) {
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException("lifetime must be positive");
    }
  }

  private static String requireId(String id) {
    Objects.requireNonNull(id, "approval id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("approval id must not be blank");
    }
    return id;
  }

  private static Instant persistedInstant(Instant instant) {
    return Instant.ofEpochMilli(instant.toEpochMilli());
  }

  private static GateDecision allowed(String reasonCode) {
    return new GateDecision(true, reasonCode);
  }

  private static GateDecision denied(String reasonCode) {
    return new GateDecision(false, reasonCode);
  }

  private record StoredApproval(String fingerprint, long expiresAtEpochMillis, int approvalState) {

    private boolean expiredAt(long now) {
      return now >= expiresAtEpochMillis;
    }

    private boolean isPending() {
      return approvalState == 0;
    }

    private boolean isApproved() {
      return approvalState == 1;
    }
  }
}

package io.github.mat973252.agentpermit.jdbc.audit;

import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.audit.AuditSubject;
import io.github.mat973252.agentpermit.audit.DecisionAuditEvent;
import io.github.mat973252.agentpermit.audit.ReplaySafeAuditView;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Append-only JDBC audit log using the bundled explicit schema. */
public final class JdbcAuditLog implements AuditSink {

  private static final String INSERT_EVENT =
      "INSERT INTO agent_permit_audit_event "
          + "(timeline_id, event_sequence, stage, tool_name, principal_id, tenant_id, "
          + "status, reason_code, decision_outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String SELECT_TIMELINE =
      "SELECT timeline_id, event_sequence, stage, tool_name, principal_id, tenant_id, "
          + "status, reason_code, decision_outcome FROM agent_permit_audit_event "
          + "WHERE timeline_id = ? ORDER BY event_sequence";

  private final DataSource dataSource;
  private final Supplier<String> legacyTimelineIdGenerator;

  public JdbcAuditLog(DataSource dataSource, Supplier<String> legacyTimelineIdGenerator) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.legacyTimelineIdGenerator =
        Objects.requireNonNull(legacyTimelineIdGenerator, "legacyTimelineIdGenerator");
  }

  @Override
  public void record(DecisionAuditEvent event) {
    Objects.requireNonNull(event, "event");
    var subject = new AuditSubject(event.toolName(), event.principalId(), event.tenantId());
    record(
        AuditEvent.result(
            legacyTimelineIdGenerator.get(), 1, subject, event.decision()));
  }

  @Override
  public void record(AuditEvent event) {
    Objects.requireNonNull(event, "event");
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(INSERT_EVENT)) {
      statement.setString(1, event.timelineId());
      statement.setInt(2, event.sequence());
      statement.setString(3, event.stage().name());
      statement.setString(4, event.subject().toolName());
      statement.setString(5, event.subject().principalId());
      statement.setString(6, event.subject().tenantId());
      statement.setString(7, event.status());
      statement.setString(8, event.reasonCode());
      setDecisionOutcome(statement, event);
      statement.executeUpdate();
    } catch (SQLException | RuntimeException ignored) {
      throw storageFailure();
    }
  }

  public ReplaySafeAuditView replaySafeView(String timelineId) {
    requireTimelineId(timelineId);
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(SELECT_TIMELINE)) {
      statement.setString(1, timelineId);
      try (var result = statement.executeQuery()) {
        var events = new ArrayList<AuditEvent>();
        while (result.next()) {
          events.add(readEvent(result));
        }
        return new ReplaySafeAuditView(timelineId, events);
      }
    } catch (SQLException | RuntimeException ignored) {
      throw storageFailure();
    }
  }

  private static void setDecisionOutcome(
      java.sql.PreparedStatement statement, AuditEvent event) throws SQLException {
    if (event.decision() == null) {
      statement.setNull(9, Types.VARCHAR);
    } else {
      statement.setString(9, event.decision().outcome().name());
    }
  }

  private static AuditEvent readEvent(ResultSet result) throws SQLException {
    var timelineId = result.getString(1);
    int sequence = result.getInt(2);
    var stage = AuditStage.valueOf(result.getString(3));
    var subject = new AuditSubject(result.getString(4), result.getString(5), result.getString(6));
    var status = result.getString(7);
    var reasonCode = result.getString(8);
    var decisionOutcome = result.getString(9);
    if (stage != AuditStage.RESULT) {
      if (decisionOutcome != null) {
        throw new IllegalArgumentException("non-result event has a decision");
      }
      return AuditEvent.stage(timelineId, sequence, stage, subject, status, reasonCode);
    }
    var outcome = DecisionOutcome.valueOf(decisionOutcome);
    if (!status.equals(outcome.name())) {
      throw new IllegalArgumentException("result status does not match its decision");
    }
    var decision = new DecisionResult(outcome, reasonCode);
    return AuditEvent.result(timelineId, sequence, subject, decision);
  }

  private static void requireTimelineId(String timelineId) {
    Objects.requireNonNull(timelineId, "timelineId");
    if (timelineId.isBlank()) {
      throw new IllegalArgumentException("timelineId must not be blank");
    }
  }

  private static RuntimeException storageFailure() {
    return new IllegalStateException("audit storage unavailable");
  }
}

package io.github.agentpermit4j.audit;

import io.github.agentpermit4j.core.DecisionResult;
import java.util.Objects;

public record AuditEvent(
    String timelineId,
    int sequence,
    AuditStage stage,
    AuditSubject subject,
    String status,
    String reasonCode,
    DecisionResult decision) {

  public AuditEvent {
    timelineId = requireText(timelineId, "timelineId");
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    stage = Objects.requireNonNull(stage, "stage");
    subject = Objects.requireNonNull(subject, "subject");
    status = requireText(status, "status");
    reasonCode = requireText(reasonCode, "reasonCode");
    validateDecision(stage, status, reasonCode, decision);
  }

  public static AuditEvent stage(
      String timelineId,
      int sequence,
      AuditStage stage,
      AuditSubject subject,
      String status,
      String reasonCode) {
    if (stage == AuditStage.RESULT) {
      throw new IllegalArgumentException("use result factory for RESULT stage");
    }
    return new AuditEvent(timelineId, sequence, stage, subject, status, reasonCode, null);
  }

  public static AuditEvent result(
      String timelineId, int sequence, AuditSubject subject, DecisionResult decision) {
    Objects.requireNonNull(decision, "decision");
    return new AuditEvent(
        timelineId,
        sequence,
        AuditStage.RESULT,
        subject,
        decision.outcome().name(),
        decision.reasonCode(),
        decision);
  }

  private static void validateDecision(
      AuditStage stage, String status, String reasonCode, DecisionResult decision) {
    if (stage != AuditStage.RESULT && decision != null) {
      throw new IllegalArgumentException("only RESULT events may carry a decision");
    }
    if (stage == AuditStage.RESULT
        && (decision == null
            || !status.equals(decision.outcome().name())
            || !reasonCode.equals(decision.reasonCode()))) {
      throw new IllegalArgumentException("RESULT event must match its decision");
    }
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

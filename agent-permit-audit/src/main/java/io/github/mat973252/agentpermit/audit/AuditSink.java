package io.github.mat973252.agentpermit.audit;

@FunctionalInterface
public interface AuditSink {

  void record(DecisionAuditEvent event);

  default void record(AuditEvent event) {
    if (event.stage() != AuditStage.RESULT) {
      return;
    }
    var subject = event.subject();
    record(
        new DecisionAuditEvent(
            subject.toolName(), subject.principalId(), subject.tenantId(), event.decision()));
  }
}

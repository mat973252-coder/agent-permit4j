package io.github.mat973252.agentpermit.audit;

@FunctionalInterface
public interface AuditSink {

  void record(DecisionAuditEvent event);
}

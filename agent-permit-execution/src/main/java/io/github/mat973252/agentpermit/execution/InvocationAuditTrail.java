package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.audit.AuditSubject;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Objects;
import java.util.UUID;

final class InvocationAuditTrail {

  private final String timelineId;
  private final AuditSubject subject;
  private final AuditSink sink;
  private int sequence;

  private InvocationAuditTrail(String timelineId, AuditSubject subject, AuditSink sink) {
    this.timelineId = timelineId;
    this.subject = subject;
    this.sink = sink;
  }

  static InvocationAuditTrail start(ToolInvocation invocation, AuditSink sink) {
    Objects.requireNonNull(invocation, "invocation");
    var subject =
        new AuditSubject(
            invocation.descriptor().name(),
            invocation.principal().id(),
            invocation.context().tenantId());
    return new InvocationAuditTrail(UUID.randomUUID().toString(), subject, sink);
  }

  void stage(AuditStage stage, String status, String reasonCode) {
    sink.record(AuditEvent.stage(timelineId, ++sequence, stage, subject, status, reasonCode));
  }

  void result(DecisionResult decision) {
    sink.record(AuditEvent.result(timelineId, ++sequence, subject, decision));
  }
}

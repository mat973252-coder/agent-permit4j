package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.audit.AuditEvent;
import io.github.agentpermit4j.audit.AuditSink;
import io.github.agentpermit4j.audit.AuditStage;
import io.github.agentpermit4j.audit.AuditSubject;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.ToolInvocation;
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

package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.audit.InMemoryAuditLog;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.DecisionPipeline;
import io.github.mat973252.agentpermit.execution.InMemoryIdempotencyGuard;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.util.concurrent.atomic.AtomicInteger;

final class ScenarioHarness {

  private final InMemoryAuditLog auditLog = new InMemoryAuditLog();
  private final AtomicInteger sideEffects = new AtomicInteger();
  private final DecisionPipeline pipeline;

  ScenarioHarness(
      Authorizer authorizer, RiskEvaluator riskEvaluator, ApprovalVerifier approvalVerifier) {
    pipeline =
        new DecisionPipeline(
            new DecisionPipeline.Dependencies(
                invocation -> new GateDecision(true, "VALIDATED"),
                invocation -> invocation,
                authorizer,
                riskEvaluator,
                approvalVerifier,
                new InMemoryIdempotencyGuard(),
                invocation -> sideEffects.incrementAndGet(),
                auditLog));
  }

  CaseReport execute(
      String name, ToolInvocation invocation, String approvalRequestId, String idempotencyKey) {
    var decision = pipeline.process(invocation, approvalRequestId, idempotencyKey);
    var timeline = auditLog.snapshot().stream().map(AuditEvent::stage).toList();
    return new CaseReport(name, decision, sideEffects.get(), timeline);
  }
}

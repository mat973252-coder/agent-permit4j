package io.github.agentpermit4j.playground;

import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.audit.AuditEvent;
import io.github.agentpermit4j.audit.InMemoryAuditLog;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.DecisionPipeline;
import io.github.agentpermit4j.execution.InMemoryIdempotencyGuard;
import io.github.agentpermit4j.policy.Authorizer;
import io.github.agentpermit4j.policy.RiskEvaluator;
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

package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.DecisionAuditEvent;
import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.util.Objects;

public final class DecisionPipeline {

  private final Dependencies dependencies;

  public DecisionPipeline(Dependencies dependencies) {
    this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
  }

  public DecisionResult process(ToolInvocation invocation) {
    return process(invocation, null, null);
  }

  public DecisionResult process(ToolInvocation invocation, String approvalRequestId) {
    return process(invocation, approvalRequestId, null);
  }

  public DecisionResult process(
      ToolInvocation invocation, String approvalRequestId, String idempotencyKey) {
    Objects.requireNonNull(invocation, "invocation");
    var validation =
        Objects.requireNonNull(dependencies.validator().validate(invocation), "validation");
    if (!validation.permitted()) {
      return finish(invocation, DecisionOutcome.DENIED, validation.reasonCode());
    }

    var normalized =
        Objects.requireNonNull(dependencies.normalizer().normalize(invocation), "normalized");
    var authorization =
        Objects.requireNonNull(dependencies.authorizer().authorize(normalized), "authorization");
    if (!authorization.permitted()) {
      return finish(normalized, DecisionOutcome.DENIED, authorization.reasonCode());
    }

    var risk = Objects.requireNonNull(dependencies.riskEvaluator().evaluate(normalized), "risk");
    return decide(normalized, risk, approvalRequestId, idempotencyKey);
  }

  private DecisionResult decide(
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId,
      String idempotencyKey) {
    return switch (risk.level()) {
      case LOW -> execute(invocation, risk.reasonCode(), idempotencyKey);
      case HIGH, CRITICAL ->
          requireApproval(invocation, risk, approvalRequestId, idempotencyKey);
      case DENY -> finish(invocation, DecisionOutcome.DENIED, risk.reasonCode());
    };
  }

  private DecisionResult requireApproval(
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId,
      String idempotencyKey) {
    if (approvalRequestId == null || approvalRequestId.isBlank()) {
      return finish(invocation, DecisionOutcome.APPROVAL_REQUIRED, risk.reasonCode());
    }
    var approval =
        Objects.requireNonNull(
            dependencies.approvalVerifier().verify(approvalRequestId, invocation), "approval");
    return approval.permitted()
        ? execute(invocation, risk.reasonCode(), idempotencyKey)
        : finish(invocation, DecisionOutcome.APPROVAL_REQUIRED, approval.reasonCode());
  }

  private DecisionResult execute(
      ToolInvocation invocation, String reasonCode, String idempotencyKey) {
    var decision =
        idempotencyKey == null
            ? invokeExecutor(invocation, reasonCode)
            : dependencies
                .idempotencyGuard()
                .executeOnce(
                    idempotencyKey, invocation, () -> invokeExecutor(invocation, reasonCode));
    return finish(invocation, decision.outcome(), decision.reasonCode());
  }

  private DecisionResult invokeExecutor(ToolInvocation invocation, String reasonCode) {
    try {
      dependencies.executor().execute(invocation);
    } catch (RuntimeException exception) {
      return new DecisionResult(DecisionOutcome.FAILED, "EXECUTION_FAILED");
    }
    return new DecisionResult(DecisionOutcome.EXECUTED, reasonCode);
  }

  private DecisionResult finish(
      ToolInvocation invocation, DecisionOutcome outcome, String reasonCode) {
    var decision = new DecisionResult(outcome, reasonCode);
    dependencies
        .auditSink()
        .record(
            new DecisionAuditEvent(
                invocation.descriptor().name(),
                invocation.principal().id(),
                invocation.context().tenantId(),
                decision));
    return decision;
  }

  public record Dependencies(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      Authorizer authorizer,
      RiskEvaluator riskEvaluator,
      ApprovalVerifier approvalVerifier,
      IdempotencyGuard idempotencyGuard,
      ToolExecutor executor,
      AuditSink auditSink) {

    public Dependencies(
        InvocationValidator validator,
        InvocationNormalizer normalizer,
        Authorizer authorizer,
        RiskEvaluator riskEvaluator,
        ApprovalVerifier approvalVerifier,
        ToolExecutor executor,
        AuditSink auditSink) {
      this(
          validator,
          normalizer,
          authorizer,
          riskEvaluator,
          approvalVerifier,
          new InMemoryIdempotencyGuard(),
          executor,
          auditSink);
    }

    public Dependencies(
        InvocationValidator validator,
        InvocationNormalizer normalizer,
        Authorizer authorizer,
        RiskEvaluator riskEvaluator,
        ToolExecutor executor,
        AuditSink auditSink) {
      this(
          validator,
          normalizer,
          authorizer,
          riskEvaluator,
          (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED"),
          new InMemoryIdempotencyGuard(),
          executor,
          auditSink);
    }

    public Dependencies {
      validator = Objects.requireNonNull(validator, "validator");
      normalizer = Objects.requireNonNull(normalizer, "normalizer");
      authorizer = Objects.requireNonNull(authorizer, "authorizer");
      riskEvaluator = Objects.requireNonNull(riskEvaluator, "riskEvaluator");
      approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
      idempotencyGuard = Objects.requireNonNull(idempotencyGuard, "idempotencyGuard");
      executor = Objects.requireNonNull(executor, "executor");
      auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }
  }
}

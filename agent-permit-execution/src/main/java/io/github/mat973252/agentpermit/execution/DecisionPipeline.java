package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
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
      var audit = InvocationAuditTrail.start(invocation, dependencies.auditSink());
      audit.stage(AuditStage.POLICY, "DENIED", validation.reasonCode());
      return finish(audit, DecisionOutcome.DENIED, validation.reasonCode());
    }

    var normalized =
        Objects.requireNonNull(dependencies.normalizer().normalize(invocation), "normalized");
    var audit = InvocationAuditTrail.start(normalized, dependencies.auditSink());
    var authorization =
        Objects.requireNonNull(dependencies.authorizer().authorize(normalized), "authorization");
    if (!authorization.permitted()) {
      audit.stage(AuditStage.POLICY, "DENIED", authorization.reasonCode());
      return finish(audit, DecisionOutcome.DENIED, authorization.reasonCode());
    }
    audit.stage(AuditStage.POLICY, "ALLOWED", authorization.reasonCode());

    var risk = Objects.requireNonNull(dependencies.riskEvaluator().evaluate(normalized), "risk");
    audit.stage(AuditStage.RISK, risk.level().name(), risk.reasonCode());
    return decide(audit, normalized, risk, approvalRequestId, idempotencyKey);
  }

  private DecisionResult decide(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId,
      String idempotencyKey) {
    return switch (risk.level()) {
      case LOW -> {
        audit.stage(AuditStage.APPROVAL, "NOT_REQUIRED", "APPROVAL_NOT_REQUIRED");
        yield execute(audit, invocation, risk.reasonCode(), idempotencyKey);
      }
      case HIGH, CRITICAL ->
          requireApproval(audit, invocation, risk, approvalRequestId, idempotencyKey);
      case DENY -> finish(audit, DecisionOutcome.DENIED, risk.reasonCode());
    };
  }

  private DecisionResult requireApproval(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId,
      String idempotencyKey) {
    if (approvalRequestId == null || approvalRequestId.isBlank()) {
      audit.stage(AuditStage.APPROVAL, "REQUIRED", risk.reasonCode());
      return finish(audit, DecisionOutcome.APPROVAL_REQUIRED, risk.reasonCode());
    }
    var approval =
        Objects.requireNonNull(
            dependencies.approvalVerifier().verify(approvalRequestId, invocation), "approval");
    audit.stage(
        AuditStage.APPROVAL,
        approval.permitted() ? "VERIFIED" : "REJECTED",
        approval.reasonCode());
    return approval.permitted()
        ? execute(audit, invocation, risk.reasonCode(), idempotencyKey)
        : finish(audit, DecisionOutcome.APPROVAL_REQUIRED, approval.reasonCode());
  }

  private DecisionResult execute(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      String reasonCode,
      String idempotencyKey) {
    var decision =
        idempotencyKey == null
            ? invokeExecutor(invocation, reasonCode)
            : dependencies
                .idempotencyGuard()
                .executeOnce(
                    idempotencyKey, invocation, () -> invokeExecutor(invocation, reasonCode));
    audit.stage(AuditStage.EXECUTION, decision.outcome().name(), decision.reasonCode());
    return finish(audit, decision.outcome(), decision.reasonCode());
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
      InvocationAuditTrail audit, DecisionOutcome outcome, String reasonCode) {
    var decision = new DecisionResult(outcome, reasonCode);
    audit.result(decision);
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

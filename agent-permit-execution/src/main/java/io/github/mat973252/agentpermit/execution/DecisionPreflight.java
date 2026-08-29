package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.util.Objects;

final class DecisionPreflight {

  private final InvocationValidator validator;
  private final InvocationNormalizer normalizer;
  private final Authorizer authorizer;
  private final RiskEvaluator riskEvaluator;
  private final ApprovalVerifier approvalVerifier;
  private final AuditSink auditSink;

  DecisionPreflight(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      Authorizer authorizer,
      RiskEvaluator riskEvaluator,
      ApprovalVerifier approvalVerifier,
      AuditSink auditSink) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    this.riskEvaluator = Objects.requireNonNull(riskEvaluator, "riskEvaluator");
    this.approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
    this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
  }

  Result evaluate(ToolInvocation invocation, String approvalRequestId) {
    Objects.requireNonNull(invocation, "invocation");
    var validation = Objects.requireNonNull(validator.validate(invocation), "validation");
    if (!validation.permitted()) {
      var audit = InvocationAuditTrail.start(invocation, auditSink);
      audit.stage(AuditStage.POLICY, "DENIED", validation.reasonCode());
      return terminal(audit, DecisionOutcome.DENIED, validation.reasonCode());
    }

    var normalized = Objects.requireNonNull(normalizer.normalize(invocation), "normalized");
    var audit = InvocationAuditTrail.start(normalized, auditSink);
    var authorization = Objects.requireNonNull(authorizer.authorize(normalized), "authorization");
    if (!authorization.permitted()) {
      audit.stage(AuditStage.POLICY, "DENIED", authorization.reasonCode());
      return terminal(audit, DecisionOutcome.DENIED, authorization.reasonCode());
    }
    audit.stage(AuditStage.POLICY, "ALLOWED", authorization.reasonCode());

    var risk = Objects.requireNonNull(riskEvaluator.evaluate(normalized), "risk");
    audit.stage(AuditStage.RISK, risk.level().name(), risk.reasonCode());
    return decide(audit, normalized, risk, approvalRequestId);
  }

  private Result decide(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId) {
    return switch (risk.level()) {
      case LOW -> {
        audit.stage(AuditStage.APPROVAL, "NOT_REQUIRED", "APPROVAL_NOT_REQUIRED");
        yield Result.execute(new Execution(audit, invocation, risk.reasonCode()));
      }
      case HIGH, CRITICAL -> requireApproval(audit, invocation, risk, approvalRequestId);
      case DENY -> terminal(audit, DecisionOutcome.DENIED, risk.reasonCode());
    };
  }

  private Result requireApproval(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      RiskAssessment risk,
      String approvalRequestId) {
    if (approvalRequestId == null || approvalRequestId.isBlank()) {
      audit.stage(AuditStage.APPROVAL, "REQUIRED", risk.reasonCode());
      return terminal(audit, DecisionOutcome.APPROVAL_REQUIRED, risk.reasonCode());
    }
    var approval =
        Objects.requireNonNull(
            approvalVerifier.verify(approvalRequestId, invocation), "approval");
    audit.stage(
        AuditStage.APPROVAL,
        approval.permitted() ? "VERIFIED" : "REJECTED",
        approval.reasonCode());
    return approval.permitted()
        ? Result.execute(new Execution(audit, invocation, risk.reasonCode()))
        : terminal(audit, DecisionOutcome.APPROVAL_REQUIRED, approval.reasonCode());
  }

  private static Result terminal(
      InvocationAuditTrail audit, DecisionOutcome outcome, String reasonCode) {
    var decision = new DecisionResult(outcome, reasonCode);
    audit.result(decision);
    return Result.terminal(decision);
  }

  record Execution(
      InvocationAuditTrail audit, ToolInvocation invocation, String reasonCode) {}

  record Result(DecisionResult terminalDecision, Execution execution) {

    private static Result terminal(DecisionResult decision) {
      return new Result(Objects.requireNonNull(decision, "decision"), null);
    }

    private static Result execute(Execution execution) {
      return new Result(null, Objects.requireNonNull(execution, "execution"));
    }
  }
}

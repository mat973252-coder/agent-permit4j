package io.github.mat973252.agentpermit.execution;

import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.util.Objects;

public final class ResultDecisionPipeline {

  private final Dependencies dependencies;
  private final DecisionPreflight preflight;

  public ResultDecisionPipeline(Dependencies dependencies) {
    this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    preflight =
        new DecisionPreflight(
            dependencies.validator(),
            dependencies.normalizer(),
            dependencies.authorizer(),
            dependencies.riskEvaluator(),
            dependencies.approvalVerifier(),
            dependencies.auditSink());
  }

  public ToolExecutionResult process(ToolInvocation invocation) {
    return process(invocation, null, null);
  }

  public ToolExecutionResult process(ToolInvocation invocation, String approvalRequestId) {
    return process(invocation, approvalRequestId, null);
  }

  public ToolExecutionResult process(
      ToolInvocation invocation, String approvalRequestId, String idempotencyKey) {
    var result = preflight.evaluate(invocation, approvalRequestId);
    if (result.terminalDecision() != null) {
      return new ToolExecutionResult(result.terminalDecision(), null);
    }
    var execution = result.execution();
    return execute(
        execution.audit(), execution.invocation(), execution.reasonCode(), idempotencyKey);
  }

  private ToolExecutionResult execute(
      InvocationAuditTrail audit,
      ToolInvocation invocation,
      String reasonCode,
      String idempotencyKey) {
    var result =
        idempotencyKey == null
            ? invokeExecutor(invocation, reasonCode)
            : dependencies
                .idempotencyGuard()
                .executeOnce(
                    idempotencyKey, invocation, () -> invokeExecutor(invocation, reasonCode));
    var decision = result.decision();
    audit.stage(AuditStage.EXECUTION, decision.outcome().name(), decision.reasonCode());
    audit.result(decision);
    return result;
  }

  private ToolExecutionResult invokeExecutor(ToolInvocation invocation, String reasonCode) {
    try {
      var output = Objects.requireNonNull(dependencies.executor().execute(invocation), "output");
      return new ToolExecutionResult(
          new DecisionResult(DecisionOutcome.EXECUTED, reasonCode), output);
    } catch (RuntimeException exception) {
      return new ToolExecutionResult(
          new DecisionResult(DecisionOutcome.FAILED, "EXECUTION_FAILED"), null);
    }
  }

  public record Dependencies(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      Authorizer authorizer,
      RiskEvaluator riskEvaluator,
      ApprovalVerifier approvalVerifier,
      ResultIdempotencyGuard idempotencyGuard,
      ResultToolExecutor executor,
      AuditSink auditSink) {

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

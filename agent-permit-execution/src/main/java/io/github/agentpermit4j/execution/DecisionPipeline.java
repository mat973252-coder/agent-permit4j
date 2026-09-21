package io.github.agentpermit4j.execution;

import io.github.agentpermit4j.audit.AuditSink;
import io.github.agentpermit4j.audit.AuditStage;
import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.policy.Authorizer;
import io.github.agentpermit4j.policy.RiskEvaluator;
import java.util.Objects;

public final class DecisionPipeline {

  private final Dependencies dependencies;
  private final DecisionPreflight preflight;

  public DecisionPipeline(Dependencies dependencies) {
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

  public DecisionResult process(ToolInvocation invocation) {
    return process(invocation, null, null);
  }

  public DecisionResult process(ToolInvocation invocation, String approvalRequestId) {
    return process(invocation, approvalRequestId, null);
  }

  public DecisionResult process(
      ToolInvocation invocation, String approvalRequestId, String idempotencyKey) {
    var result = preflight.evaluate(invocation, approvalRequestId);
    if (result.terminalDecision() != null) {
      return result.terminalDecision();
    }
    var execution = result.execution();
    return execute(
        execution.audit(), execution.invocation(), execution.reasonCode(), idempotencyKey);
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

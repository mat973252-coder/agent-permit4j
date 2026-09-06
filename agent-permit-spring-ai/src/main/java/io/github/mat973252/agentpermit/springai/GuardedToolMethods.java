package io.github.mat973252.agentpermit.springai;

import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.audit.AuditSink;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.InvocationNormalizer;
import io.github.mat973252.agentpermit.execution.InvocationValidator;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.execution.ResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultToolExecutor;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.util.JsonHelper;

/** Explicitly registers annotated methods on application-supplied objects, without bean scanning. */
public final class GuardedToolMethods {

  private GuardedToolMethods() {}

  public static List<GuardedToolCallback> fromAnnotated(Dependencies dependencies, Object... targets) {
    Objects.requireNonNull(dependencies, "dependencies");
    var callbacks = new TreeMap<String, GuardedToolCallback>();
    for (var target : Objects.requireNonNull(targets, "targets")) {
      var methods = Objects.requireNonNull(target, "target").getClass().getMethods();
      Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
      for (var method : methods) {
        if (method.isAnnotationPresent(Tool.class)) {
          var callback = create(dependencies, target, method);
          if (callbacks.putIfAbsent(callback.getToolDefinition().name(), callback) != null) {
            throw new IllegalArgumentException("duplicate tool name");
          }
        }
      }
    }
    if (callbacks.isEmpty()) {
      throw new IllegalArgumentException("at least one public annotated tool is required");
    }
    return List.copyOf(callbacks.values());
  }

  private static GuardedToolCallback create(Dependencies dependencies, Object target, Method method) {
    requireParameterNames(method);
    var definition = ToolDefinitions.from(method);
    var policy = AgentPermitMethodPolicy.from(definition, method);
    var delegate = MethodToolCallback.builder()
        .toolDefinition(definition).toolMethod(method).toolObject(target).build();
    var json = new JsonHelper();
    ResultToolExecutor executor = invocation -> delegate.call(
        json.toJson(invocation.arguments()), methodContext(invocation));
    var pipeline = new ResultDecisionPipeline(policy.decorate(dependencies.bind(executor)));
    return new GuardedToolCallback(definition, pipeline, policy.contract(),
        dependencies.contextResolver(), policy::errorMessage);
  }

  private static ToolContext methodContext(ToolInvocation invocation) {
    return new ToolContext(Map.of(
        SpringAiToolContextKeys.PRINCIPAL_ID, invocation.principal().id(),
        SpringAiToolContextKeys.TENANT_ID, invocation.context().tenantId(),
        SpringAiToolContextKeys.ENVIRONMENT, invocation.context().environment()));
  }

  private static void requireParameterNames(Method method) {
    for (var parameter : method.getParameters()) {
      if (parameter.getType() != ToolContext.class && !parameter.isNamePresent()) {
        throw new IllegalArgumentException("tool methods must be compiled with -parameters");
      }
    }
  }

  public record Dependencies(
      InvocationValidator validator,
      InvocationNormalizer normalizer,
      Authorizer authorizer,
      RiskEvaluator riskEvaluator,
      ApprovalVerifier approvalVerifier,
      ResultIdempotencyGuard idempotencyGuard,
      AuditSink auditSink,
      TrustedToolContextResolver contextResolver) {

    public Dependencies {
      Objects.requireNonNull(validator, "validator");
      Objects.requireNonNull(normalizer, "normalizer");
      Objects.requireNonNull(authorizer, "authorizer");
      Objects.requireNonNull(riskEvaluator, "riskEvaluator");
      Objects.requireNonNull(approvalVerifier, "approvalVerifier");
      Objects.requireNonNull(idempotencyGuard, "idempotencyGuard");
      Objects.requireNonNull(auditSink, "auditSink");
      Objects.requireNonNull(contextResolver, "contextResolver");
    }

    private ResultDecisionPipeline.Dependencies bind(ResultToolExecutor executor) {
      return new ResultDecisionPipeline.Dependencies(validator, normalizer, authorizer,
          riskEvaluator, approvalVerifier, idempotencyGuard, executor, auditSink);
    }
  }
}

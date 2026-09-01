package io.github.mat973252.agentpermit.springai;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.tool.definition.ToolDefinition;

final class AgentPermitMethodPolicy {

  private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
  private static final Set<String> DEFAULT_DENIAL_CODES =
      Set.of(
          "ANNOTATION_ENVIRONMENT_DENIED",
          "ANNOTATION_HOST_DENIED",
          "ANNOTATION_METHOD_DENIED",
          "ANNOTATION_PAYLOAD_TOO_LARGE",
          "ANNOTATION_RISK_DENY");

  private final AgentPermit permit;
  private final SpringAiToolContract contract;
  private final Set<String> environments;
  private final Set<String> hosts;
  private final Set<String> methods;

  private AgentPermitMethodPolicy(AgentPermit permit, ToolDefinition definition) {
    this.permit = permit;
    contract =
        new SpringAiToolContract(
            new ToolDescriptor(
                definition.name(),
                permit.effect(),
                permit.reversibility(),
                permit.dataSensitivity()),
            new Action(definition.name()),
            permit.resourceType(),
            permit.resourceArg());
    environments = values(permit.environments(), "environments", false);
    hosts = values(permit.hosts(), "hosts", true);
    methods = values(permit.methods(), "methods", true);
    validateLimits();
    validateError();
  }

  static AgentPermitMethodPolicy from(ToolDefinition definition, Method method) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(method, "method");
    var permit = method.getAnnotation(AgentPermit.class);
    if (permit == null) {
      throw new IllegalArgumentException("method must declare @AgentPermit");
    }
    return new AgentPermitMethodPolicy(permit, definition);
  }

  SpringAiToolContract contract() {
    return contract;
  }

  String errorMessage(String reasonCode) {
    if (permit.errorMessage().isEmpty()) {
      return null;
    }
    var customCode = permit.errorCode();
    var matches =
        customCode.isEmpty()
            ? DEFAULT_DENIAL_CODES.contains(reasonCode)
            : customCode.equals(reasonCode);
    return matches ? permit.errorMessage() : null;
  }

  ResultDecisionPipeline.Dependencies decorate(
      ResultDecisionPipeline.Dependencies base) {
    Objects.requireNonNull(base, "dependencies");
    return new ResultDecisionPipeline.Dependencies(
        base.validator(),
        base.normalizer(),
        invocation -> authorize(base, invocation),
        invocation -> assessRisk(base, invocation),
        base.approvalVerifier(),
        base.idempotencyGuard(),
        base.executor(),
        base.auditSink());
  }

  private GateDecision authorize(
      ResultDecisionPipeline.Dependencies base, ToolInvocation invocation) {
    var baseDecision =
        Objects.requireNonNull(base.authorizer().authorize(invocation), "authorization");
    if (!baseDecision.permitted()) {
      return baseDecision;
    }
    if (!environments.isEmpty()
        && !environments.contains(invocation.context().environment())) {
      return denied("ANNOTATION_ENVIRONMENT_DENIED");
    }
    var httpDecision = authorizeHttp(invocation);
    return httpDecision == null
        ? new GateDecision(true, "ANNOTATION_POLICY_ALLOWED")
        : httpDecision;
  }

  private GateDecision authorizeHttp(ToolInvocation invocation) {
    if (!hosts.isEmpty() && !hosts.contains(httpsHost(invocation.resource().identifier()))) {
      return denied("ANNOTATION_HOST_DENIED");
    }
    var method = invocation.arguments().get("method");
    if (!methods.isEmpty()
        && (method == null || !methods.contains(method.toUpperCase(Locale.ROOT)))) {
      return denied("ANNOTATION_METHOD_DENIED");
    }
    var payload = invocation.arguments().getOrDefault("payload", "");
    if (permit.maxBytes() >= 0
        && payload.getBytes(StandardCharsets.UTF_8).length > permit.maxBytes()) {
      return denied("ANNOTATION_PAYLOAD_TOO_LARGE");
    }
    return null;
  }

  private RiskAssessment assessRisk(
      ResultDecisionPipeline.Dependencies base, ToolInvocation invocation) {
    var dynamic =
        Objects.requireNonNull(base.riskEvaluator().evaluate(invocation), "risk");
    if (permit.risk().ordinal() <= dynamic.level().ordinal()) {
      return dynamic;
    }
    var reasonCode = "ANNOTATION_RISK_" + permit.risk().name();
    var finalReasonCode =
        permit.risk() == RiskLevel.DENY ? annotationReason(reasonCode) : reasonCode;
    return new RiskAssessment(permit.risk(), finalReasonCode);
  }

  private void validateLimits() {
    if (permit.maxBytes() < -1) {
      throw new IllegalArgumentException("maxBytes must be -1 or non-negative");
    }
    var hasHttpLimits =
        !hosts.isEmpty() || !methods.isEmpty() || permit.maxBytes() >= 0;
    if (hasHttpLimits && !contract.resourceType().equals("http")) {
      throw new IllegalArgumentException(
          "HTTP annotation limits require resourceType http");
    }
  }

  private void validateError() {
    if (!permit.errorCode().isEmpty()
        && !ERROR_CODE.matcher(permit.errorCode()).matches()) {
      throw new IllegalArgumentException("errorCode must be an uppercase machine code");
    }
    if (permit.errorCode().startsWith("ANNOTATION_")) {
      throw new IllegalArgumentException(
          "errorCode must not use reserved ANNOTATION_ prefix");
    }
    if (!permit.errorMessage().isEmpty() && permit.errorMessage().isBlank()) {
      throw new IllegalArgumentException("errorMessage must not be blank");
    }
  }

  private static Set<String> values(
      String[] source, String name, boolean uppercase) {
    return Arrays.stream(Objects.requireNonNull(source, name))
        .map(value -> requireText(value, name))
        .map(value -> uppercase ? value.toUpperCase(Locale.ROOT) : value)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String httpsHost(String identifier) {
    try {
      var uri = URI.create(identifier);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1) {
        return null;
      }
      return uri.getHost() == null ? null : uri.getHost().toUpperCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not contain blank values");
    }
    return value;
  }

  private GateDecision denied(String reasonCode) {
    return new GateDecision(false, annotationReason(reasonCode));
  }

  private String annotationReason(String defaultReasonCode) {
    return permit.errorCode().isEmpty() ? defaultReasonCode : permit.errorCode();
  }
}

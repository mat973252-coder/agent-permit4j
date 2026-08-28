package io.github.mat973252.agentpermit.policy.http;

import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.policy.RiskEvaluator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

public final class HttpRiskEvaluator implements RiskEvaluator {

  private static final String METHOD_ARGUMENT = "method";
  private static final String PAYLOAD_ARGUMENT = "payload";
  private final HttpRiskPolicy policy;

  public HttpRiskEvaluator(HttpRiskPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public RiskAssessment evaluate(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    if (!"http".equals(invocation.resource().type())) {
      return denied("HTTP_RESOURCE_REQUIRED");
    }

    var targetAssessment = assessTarget(invocation.resource().identifier());
    if (targetAssessment != null) {
      return targetAssessment;
    }
    return assessRequest(invocation);
  }

  private RiskAssessment assessTarget(String identifier) {
    final URI uri;
    try {
      uri = new URI(identifier);
    } catch (URISyntaxException exception) {
      return denied("HTTP_INVALID_URI");
    }
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null) {
      return denied("HTTP_INVALID_URI");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return denied("HTTP_HTTPS_REQUIRED");
    }
    var rawHost = uri.getHost();
    if (HttpRiskPolicy.isSsrfTarget(rawHost)) {
      return denied("HTTP_SSRF_TARGET");
    }
    final String host;
    try {
      host = HttpRiskPolicy.normalizeHost(rawHost);
    } catch (IllegalArgumentException exception) {
      return denied("HTTP_INVALID_URI");
    }
    if (HttpRiskPolicy.isSsrfTarget(host)) {
      return denied("HTTP_SSRF_TARGET");
    }
    if (uri.getPort() != -1 && uri.getPort() != 443) {
      return denied("HTTP_PORT_NOT_ALLOWED");
    }
    return policy.allowsHost(host) ? null : denied("HTTP_HOST_NOT_ALLOWED");
  }

  private RiskAssessment assessRequest(ToolInvocation invocation) {
    var method = invocation.arguments().get(METHOD_ARGUMENT);
    if (method == null || method.isBlank()) {
      return denied("HTTP_MISSING_METHOD");
    }
    method = method.strip().toUpperCase(Locale.ROOT);
    var payload = invocation.arguments().getOrDefault(PAYLOAD_ARGUMENT, "");
    if (payload.getBytes(StandardCharsets.UTF_8).length > policy.maxPayloadBytes()) {
      return denied("HTTP_PAYLOAD_TOO_LARGE");
    }
    return classifyMethod(method, payload);
  }

  private static RiskAssessment classifyMethod(String method, String payload) {
    return switch (method) {
      case "GET", "HEAD" ->
          payload.isEmpty()
              ? assessment(RiskLevel.LOW, "HTTP_READ_ONLY")
              : denied("HTTP_READ_PAYLOAD_DENIED");
      case "POST", "PUT", "PATCH" -> assessment(RiskLevel.HIGH, "HTTP_WRITE");
      case "DELETE" -> assessment(RiskLevel.CRITICAL, "HTTP_DELETE");
      default -> denied("HTTP_METHOD_NOT_ALLOWED");
    };
  }

  private static RiskAssessment denied(String reasonCode) {
    return assessment(RiskLevel.DENY, reasonCode);
  }

  private static RiskAssessment assessment(RiskLevel level, String reasonCode) {
    return new RiskAssessment(level, reasonCode);
  }
}

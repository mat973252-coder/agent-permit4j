package io.github.mat973252.agentpermit.policy.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpRiskEvaluatorTest {

  private final HttpRiskEvaluator evaluator =
      new HttpRiskEvaluator(new HttpRiskPolicy(Set.of("api.example.com"), 4));

  @ParameterizedTest(name = "{0}")
  @MethodSource("classifiedRequests")
  void classifiesAllowedHttpsRequestsByMethod(
      String scenario, String method, RiskLevel expectedLevel, String expectedReason) {
    var assessment = evaluator.evaluate(httpInvocation("https://api.example.com/v1/items", method, ""));

    assertEquals(expectedLevel, assessment.level());
    assertEquals(expectedReason, assessment.reasonCode());
  }

  @ParameterizedTest(name = "fails closed for {0}")
  @MethodSource("deniedRequests")
  void deniesRequestsThatCannotBeSafelyExecuted(
      String scenario, ToolInvocation invocation, String expectedReason) {
    var assessment = evaluator.evaluate(invocation);

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals(expectedReason, assessment.reasonCode());
  }

  @Test
  void matchesConfiguredHostsCaseInsensitively() {
    var assessment =
        evaluator.evaluate(httpInvocation("https://API.EXAMPLE.COM/v1/items", "GET", ""));

    assertEquals(RiskLevel.LOW, assessment.level());
    assertEquals("HTTP_READ_ONLY", assessment.reasonCode());
  }

  @Test
  void rejectsInvalidRuntimePolicy() {
    assertThrows(IllegalArgumentException.class, () -> new HttpRiskPolicy(Set.of(), 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HttpRiskPolicy(Set.of("api.example.com"), -1));
    assertThrows(
        IllegalArgumentException.class, () -> new HttpRiskPolicy(Set.of("localhost"), 8));
    assertThrows(
        IllegalArgumentException.class, () -> new HttpRiskPolicy(Set.of("127.0.0.1"), 8));
  }

  private static Stream<Arguments> classifiedRequests() {
    return Stream.of(
        Arguments.of("GET", "GET", RiskLevel.LOW, "HTTP_READ_ONLY"),
        Arguments.of("HEAD", "HEAD", RiskLevel.LOW, "HTTP_READ_ONLY"),
        Arguments.of("POST", "POST", RiskLevel.HIGH, "HTTP_WRITE"),
        Arguments.of("PUT", "PUT", RiskLevel.HIGH, "HTTP_WRITE"),
        Arguments.of("PATCH", "PATCH", RiskLevel.HIGH, "HTTP_WRITE"),
        Arguments.of("DELETE", "DELETE", RiskLevel.CRITICAL, "HTTP_DELETE"));
  }

  private static Stream<Arguments> deniedRequests() {
    return Stream.of(
        Arguments.of(
            "non-HTTP resource", invocation("sql", "https://api.example.com", "GET", ""),
            "HTTP_RESOURCE_REQUIRED"),
        Arguments.of(
            "malformed URI", httpInvocation("not a uri", "GET", ""), "HTTP_INVALID_URI"),
        Arguments.of(
            "userinfo", httpInvocation("https://agent@api.example.com/items", "GET", ""),
            "HTTP_INVALID_URI"),
        Arguments.of(
            "fragment", httpInvocation("https://api.example.com/items#admin", "GET", ""),
            "HTTP_INVALID_URI"),
        Arguments.of(
            "plain HTTP", httpInvocation("http://api.example.com/items", "GET", ""),
            "HTTP_HTTPS_REQUIRED"),
        Arguments.of(
            "localhost", httpInvocation("https://localhost/items", "GET", ""),
            "HTTP_SSRF_TARGET"),
        Arguments.of(
            "IPv4 literal", httpInvocation("https://127.0.0.1/items", "GET", ""),
            "HTTP_SSRF_TARGET"),
        Arguments.of(
            "IPv6 literal", httpInvocation("https://[::1]/items", "GET", ""),
            "HTTP_SSRF_TARGET"),
        Arguments.of(
            "unlisted host", httpInvocation("https://example.org/items", "GET", ""),
            "HTTP_HOST_NOT_ALLOWED"),
        Arguments.of(
            "non-default port", httpInvocation("https://api.example.com:8443/items", "GET", ""),
            "HTTP_PORT_NOT_ALLOWED"),
        Arguments.of(
            "missing method", httpInvocation("https://api.example.com/items", null, ""),
            "HTTP_MISSING_METHOD"),
        Arguments.of(
            "unsupported method", httpInvocation("https://api.example.com/items", "TRACE", ""),
            "HTTP_METHOD_NOT_ALLOWED"),
        Arguments.of(
            "read payload", httpInvocation("https://api.example.com/items", "GET", "{}"),
            "HTTP_READ_PAYLOAD_DENIED"),
        Arguments.of(
            "oversized UTF-8 payload", httpInvocation("https://api.example.com/items", "POST", "你好"),
            "HTTP_PAYLOAD_TOO_LARGE"));
  }

  private static ToolInvocation httpInvocation(String uri, String method, String payload) {
    return invocation("http", uri, method, payload);
  }

  private static ToolInvocation invocation(
      String resourceType, String uri, String method, String payload) {
    var arguments = new java.util.HashMap<String, String>();
    if (method != null) {
      arguments.put("method", method);
    }
    if (payload != null && !payload.isEmpty()) {
      arguments.put("payload", payload);
    }
    return new ToolInvocation(
        new ToolDescriptor(
            "http.request",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action("http.request"),
        new Resource(resourceType, uri, Map.of()),
        new InvocationContext("tenant-a", "test"),
        arguments);
  }
}

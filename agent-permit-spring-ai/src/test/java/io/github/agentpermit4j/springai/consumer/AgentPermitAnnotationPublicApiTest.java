package io.github.agentpermit4j.springai.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultDecisionPipeline;
import io.github.agentpermit4j.springai.AgentPermit;
import io.github.agentpermit4j.springai.GuardedToolCallback;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class AgentPermitAnnotationPublicApiTest {

  @Test
  void annotationRiskRequiresApprovalBeforeExecution() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("createOrder", 2));
    var input = "{\"uri\":\"https://api.example.com/orders\",\"payload\":\"{}\"}";

    var pending = fixture.callback.call(input, context("production", null, "pending"));
    var approved = fixture.callback.call(input, context("production", "approval-1", "approved"));

    assertTrue(pending.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertTrue(pending.contains("\"reasonCode\":\"ANNOTATION_RISK_HIGH\""));
    assertTrue(approved.contains("\"outcome\":\"EXECUTED\""));
    assertEquals(1, fixture.executions.get());
    assertEquals("http", fixture.invocation.get().resource().type());
    assertEquals("https://api.example.com/orders", fixture.invocation.get().resource().identifier());
    assertEquals(ToolEffect.WRITE, fixture.invocation.get().descriptor().effect());
    assertEquals(Reversibility.IRREVERSIBLE, fixture.invocation.get().descriptor().reversibility());
    assertEquals(
        DataSensitivity.RESTRICTED, fixture.invocation.get().descriptor().dataSensitivity());
  }

  @Test
  void annotationParametersDenyDisallowedCalls() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("restrictedOrder", 3));

    var environment =
        fixture.callback.call(validInput(), context("staging", "approval-1", "environment"));
    var host =
        fixture.callback.call(
            validInput().replace("api.example.com", "evil.example.com"),
            context("production", "approval-2", "host"));
    var method =
        fixture.callback.call(
            validInput().replace("POST", "DELETE"),
            context("production", "approval-3", "method"));
    var payload =
        fixture.callback.call(
            validInput().replace("{}", "12345"),
            context("production", "approval-4", "payload"));

    assertReason(environment, "ANNOTATION_ENVIRONMENT_DENIED");
    assertReason(host, "ANNOTATION_HOST_DENIED");
    assertReason(method, "ANNOTATION_METHOD_DENIED");
    assertReason(payload, "ANNOTATION_PAYLOAD_TOO_LARGE");
    assertEquals(0, fixture.executions.get());
  }

  @Test
  void annotationRiskCannotLowerDynamicRisk() {
    var fixture = new Fixture(RiskLevel.CRITICAL, "DYNAMIC_CRITICAL", method("readOrder", 3));

    var result =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/orders/1\"}",
            context("production", null, "dynamic"));

    assertTrue(result.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertTrue(result.contains("\"reasonCode\":\"DYNAMIC_CRITICAL\""));
    assertEquals(0, fixture.executions.get());
  }

  @Test
  void rejectsHttpOnlyLimitsOnAnotherResourceType() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Fixture(RiskLevel.LOW, "BASE_LOW", method("invalidSqlTool", 3)));

    assertEquals("HTTP annotation limits require resourceType http", exception.getMessage());
  }

  @Test
  void derivesToolIdentityFromSpringToolAnnotation() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("namedReadOrder", 3));

    var result =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/orders/1\"}",
            context("production", null, "named"));

    assertTrue(result.contains("\"outcome\":\"EXECUTED\""));
    assertEquals("orders.read", fixture.callback.getToolDefinition().name());
    assertEquals("orders.read", fixture.invocation.get().descriptor().name());
    assertEquals("orders.read", fixture.invocation.get().action().name());
  }

  @Test
  void rejectsMethodWithoutAgentPermitAnnotation() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Fixture(RiskLevel.LOW, "BASE_LOW", method("unannotatedTool", 3)));

    assertEquals("method must declare @AgentPermit", exception.getMessage());
  }

  @Test
  void nonHttpToolOnlyOverridesResourceMapping() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("redisWrite", 2));
    var input = "{\"key\":\"orders:1\",\"payload\":\"{}\"}";

    var pending = fixture.callback.call(input, context("production", null, "redis-pending"));
    var approved =
        fixture.callback.call(input, context("production", "approval-1", "redis-approved"));

    assertTrue(pending.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertTrue(pending.contains("\"reasonCode\":\"ANNOTATION_RISK_HIGH\""));
    assertTrue(approved.contains("\"outcome\":\"EXECUTED\""));
    assertEquals("redis", fixture.invocation.get().resource().type());
    assertEquals("orders:1", fixture.invocation.get().resource().identifier());
  }

  @Test
  void annotationDenialCanReturnCustomError() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("customErrorOrder", 2));

    var result =
        fixture.callback.call(
            "{\"uri\":\"https://evil.example.com/orders\",\"payload\":\"{}\"}",
            context("production", null, "custom-error"));
    var approval =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/orders\",\"payload\":\"{}\"}",
            context("production", null, "custom-approval"));

    assertEquals(
        "{\"outcome\":\"DENIED\",\"reasonCode\":\"ORDER_API_DENIED\","
            + "\"message\":\"Only the order API is allowed\"}",
        result);
    assertTrue(approval.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertTrue(approval.contains("\"reasonCode\":\"ANNOTATION_RISK_HIGH\""));
    assertFalse(approval.contains("\"message\""));
    assertEquals(0, fixture.executions.get());
  }

  @Test
  void rejectsInvalidCustomErrorCode() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Fixture(RiskLevel.LOW, "BASE_LOW", method("invalidErrorCode", 2)));

    assertEquals("errorCode must be an uppercase machine code", exception.getMessage());
  }

  @Test
  void annotationDenialCanKeepDefaultCodeWithCustomMessage() {
    var fixture = new Fixture(RiskLevel.LOW, "BASE_LOW", method("customMessageOrder", 2));

    var result =
        fixture.callback.call(
            "{\"uri\":\"https://evil.example.com/orders\",\"payload\":\"{}\"}",
            context("production", null, "custom-message"));

    assertEquals(
        "{\"outcome\":\"DENIED\",\"reasonCode\":\"ANNOTATION_HOST_DENIED\","
            + "\"message\":\"Only the order API is allowed\"}",
        result);
    assertEquals(0, fixture.executions.get());
  }

  @Test
  void rejectsReservedCustomErrorCode() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Fixture(RiskLevel.LOW, "BASE_LOW", method("reservedErrorCode", 2)));

    assertEquals("errorCode must not use reserved ANNOTATION_ prefix", exception.getMessage());
  }

  private static void assertReason(String result, String reasonCode) {
    assertTrue(result.contains("\"outcome\":\"DENIED\""));
    assertTrue(result.contains("\"reasonCode\":\"" + reasonCode + "\""));
    assertFalse(result.contains("\"message\""));
  }

  private static String validInput() {
    return "{\"uri\":\"https://api.example.com/orders\","
        + "\"method\":\"POST\",\"payload\":\"{}\"}";
  }

  private static Method method(String name, int parameterCount) {
    try {
      var parameterTypes = new Class<?>[parameterCount];
      Arrays.fill(parameterTypes, String.class);
      return Tools.class.getDeclaredMethod(name, parameterTypes);
    } catch (NoSuchMethodException exception) {
      throw new AssertionError(exception);
    }
  }

  private static ToolContext context(
      String environment, String approvalRequestId, String idempotencyKey) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "workspace-agent");
    values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
    values.put(SpringAiToolContextKeys.ENVIRONMENT, environment);
    values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (approvalRequestId != null) {
      values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalRequestId);
    }
    return new ToolContext(Map.copyOf(values));
  }

  private static final class Fixture {

    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicReference<ToolInvocation> invocation = new AtomicReference<>();
    private final GuardedToolCallback callback;

    private Fixture(RiskLevel dynamicRisk, String reasonCode, Method method) {
      var dependencies =
          new ResultDecisionPipeline.Dependencies(
              invocation -> new GateDecision(true, "VALIDATED"),
              invocation -> invocation,
              invocation -> new GateDecision(true, "AUTHORIZED"),
              invocation -> new RiskAssessment(dynamicRisk, reasonCode),
              (requestId, invocation) -> new GateDecision(true, "APPROVAL_VALID"),
              new InMemoryResultIdempotencyGuard(),
              invocation -> {
                this.invocation.set(invocation);
                return "output-" + executions.incrementAndGet();
              },
              event -> {});
      callback = GuardedToolCallback.fromAnnotated(dependencies, method);
    }
  }

  private interface Tools {

    @Tool(description = "Create an order")
    @AgentPermit(hosts = "api.example.com", maxBytes = 4)
    String createOrder(String uri, String payload);

    @Tool(description = "Restricted order operation")
    @AgentPermit(
        environments = "production",
        hosts = "api.example.com",
        methods = "POST",
        maxBytes = 4)
    String restrictedOrder(String uri, String method, String payload);

    @Tool(description = "Read an order")
    @AgentPermit(
        effect = ToolEffect.READ,
        risk = RiskLevel.LOW)
    String readOrder(String uri, String method, String payload);

    @Tool(name = "orders.read", description = "Read an order by name")
    @AgentPermit(
        effect = ToolEffect.READ,
        risk = RiskLevel.LOW)
    String namedReadOrder(String uri, String method, String payload);

    @Tool(description = "Unannotated tool")
    String unannotatedTool(String uri, String method, String payload);

    @Tool(description = "Invalid SQL limits")
    @AgentPermit(
        resourceType = "sql",
        resourceArg = "sql",
        methods = "POST")
    String invalidSqlTool(String sql, String method, String payload);

    @Tool(description = "Write a Redis key")
    @AgentPermit(resourceType = "redis", resourceArg = "key")
    String redisWrite(String key, String payload);

    @Tool(description = "Create an order with a custom error")
    @AgentPermit(
        hosts = "api.example.com",
        errorCode = "ORDER_API_DENIED",
        errorMessage = "Only the order API is allowed")
    String customErrorOrder(String uri, String payload);

    @Tool(description = "Invalid custom error code")
    @AgentPermit(hosts = "api.example.com", errorCode = "invalid-code")
    String invalidErrorCode(String uri, String payload);

    @Tool(description = "Create an order with a custom message")
    @AgentPermit(
        hosts = "api.example.com",
        errorMessage = "Only the order API is allowed")
    String customMessageOrder(String uri, String payload);

    @Tool(description = "Reserved custom error code")
    @AgentPermit(hosts = "api.example.com", errorCode = "ANNOTATION_CUSTOM_DENIED")
    String reservedErrorCode(String uri, String payload);
  }
}

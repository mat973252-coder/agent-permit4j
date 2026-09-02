package io.github.mat973252.agentpermit.springai.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import io.github.mat973252.agentpermit.springai.SpringAiToolContract;
import io.github.mat973252.agentpermit.springai.TrustedToolContextResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class GuardedToolCallbackPublicApiTest {

  @Test
  void externalConsumerConstructsGuardedCallback() {
    var definition = definition("http.request");

    ToolCallback callback =
        new GuardedToolCallback(definition, pipeline(), contract("http.request"));
    assertSame(definition, callback.getToolDefinition());
  }

  @Test
  void rejectsMismatchedDefinitionAndDescriptorNames() {
    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new GuardedToolCallback(
                    definition("public.http"), pipeline(), contract("internal.http")));

    assertEquals("definition name must match contract descriptor name", exception.getMessage());
  }

  @Test
  void externalConsumerResolvesTrustedContextBeforeInvocationMapping() {
    var invocation = new AtomicReference<ToolInvocation>();
    TrustedToolContextResolver resolver =
        supplied -> {
          var values = new HashMap<>(supplied.getContext());
          values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "authenticated-user");
          values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
          values.put(SpringAiToolContextKeys.ENVIRONMENT, "production");
          return new ToolContext(Map.copyOf(values));
        };
    var callback =
        new GuardedToolCallback(
            definition("http.request"),
            pipeline(
                mapped -> {
                  invocation.set(mapped);
                  return "api-response";
                }),
            contract("http.request"),
            resolver);

    var result =
        callback.call(
            "{\"uri\":\"https://api.example.com/orders\"}",
            new ToolContext(
                Map.of(
                    SpringAiToolContextKeys.PRINCIPAL_ID,
                    "spoofed-user",
                    SpringAiToolContextKeys.TENANT_ID,
                    "spoofed-tenant",
                    SpringAiToolContextKeys.ENVIRONMENT,
                    "staging",
                    SpringAiToolContextKeys.IDEMPOTENCY_KEY,
                    "request-42")));

    assertTrue(result.contains("\"outcome\":\"EXECUTED\""));
    assertEquals("authenticated-user", invocation.get().principal().id());
    assertEquals("tenant-a", invocation.get().context().tenantId());
    assertEquals("production", invocation.get().context().environment());
  }

  @Test
  void resolverFailureDeniesBeforeExecutor() {
    var executorCalls = new AtomicInteger();
    var callback =
        new GuardedToolCallback(
            definition("http.request"),
            pipeline(
                invocation -> {
                  executorCalls.incrementAndGet();
                  return "api-response";
                }),
            contract("http.request"),
            supplied -> null);

    var result =
        callback.call(
            "{\"uri\":\"https://api.example.com/orders\"}",
            new ToolContext(
                Map.of(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "request-42")));

    assertTrue(result.contains("\"reasonCode\":\"SPRING_AI_CONTEXT_INVALID\""));
    assertEquals(0, executorCalls.get());
  }

  private static ToolDefinition definition(String name) {
    return ToolDefinition.builder()
        .name(name)
        .description("Call an approved external HTTP API")
        .inputSchema("{\"type\":\"object\"}")
        .build();
  }

  private static SpringAiToolContract contract(String name) {
    return new SpringAiToolContract(
        new ToolDescriptor(
            name,
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action("http.request"),
        "http",
        "uri");
  }

  private static ResultDecisionPipeline pipeline() {
    return pipeline(invocation -> "api-response");
  }

  private static ResultDecisionPipeline pipeline(
      Function<ToolInvocation, String> executor) {
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "HTTP_READ_ONLY"),
            (requestId, invocation) -> new GateDecision(true, "APPROVED"),
            new InMemoryResultIdempotencyGuard(),
            executor::apply,
            event -> {}));
  }
}

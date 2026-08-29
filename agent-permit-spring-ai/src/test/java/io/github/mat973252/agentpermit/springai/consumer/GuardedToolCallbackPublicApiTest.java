package io.github.mat973252.agentpermit.springai.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.SpringAiToolContract;
import org.junit.jupiter.api.Test;
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
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "HTTP_READ_ONLY"),
            (requestId, invocation) -> new GateDecision(true, "APPROVED"),
            new InMemoryResultIdempotencyGuard(),
            invocation -> "api-response",
            event -> {}));
  }
}

package io.github.agentpermit4j.playground.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.audit.InMemoryAuditLog;
import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultDecisionPipeline;
import io.github.agentpermit4j.policy.Authorizer;
import io.github.agentpermit4j.policy.http.HttpRiskEvaluator;
import io.github.agentpermit4j.policy.http.HttpRiskPolicy;
import io.github.agentpermit4j.springai.GuardedToolCallback;
import io.github.agentpermit4j.springai.SpringAiToolContract;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

class GuardedToolCallbackAcceptanceTest {

  @Test
  void executesLowRiskCallOnceAcrossIdempotentRetry() {
    var fixture = new Fixture();
    var input = "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\"}";

    var first = fixture.callback.call(input, fixture.context(null, "read-1"));
    var retry = fixture.callback.call(input, fixture.context(null, "read-1"));

    assertEquals(
        "{\"outcome\":\"EXECUTED\",\"reasonCode\":\"HTTP_READ_ONLY\","
            + "\"output\":\"api-response-1\"}",
        first);
    assertEquals(first, retry);
    assertEquals(1, fixture.sideEffects.get());
  }

  @Test
  void pausesHighRiskCallUntilExactApprovalIsProvided() {
    var fixture = new Fixture();
    var input =
        "{\"uri\":\"https://api.example.com/items\",\"method\":\"POST\",\"payload\":\"{}\"}";

    var pending = fixture.callback.call(input, fixture.context(null, "write-pending"));
    var invocation = fixture.writeInvocation();
    var request = fixture.approvals.request(invocation, Duration.ofMinutes(5));
    fixture.approvals.approve(request.id());
    var approved =
        fixture.callback.call(input, fixture.context(request.id(), "write-approved"));

    assertTrue(pending.contains("\"outcome\":\"APPROVAL_REQUIRED\""));
    assertEquals(
        "{\"outcome\":\"EXECUTED\",\"reasonCode\":\"HTTP_WRITE\","
            + "\"output\":\"api-response-1\"}",
        approved);
    assertEquals(1, fixture.sideEffects.get());
  }

  @Test
  void deniesSsrfAndInvalidContextWithoutSideEffects() {
    var fixture = new Fixture();

    var ssrf =
        fixture.callback.call(
            "{\"uri\":\"https://127.0.0.1/admin\",\"method\":\"GET\"}",
            fixture.context(null, "ssrf-1"));
    var invalidContext =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\"}");

    assertEquals("{\"outcome\":\"DENIED\",\"reasonCode\":\"HTTP_SSRF_TARGET\"}", ssrf);
    assertEquals(
        "{\"outcome\":\"DENIED\",\"reasonCode\":\"SPRING_AI_CONTEXT_INVALID\"}",
        invalidContext);
    assertEquals(0, fixture.sideEffects.get());
  }

  @Test
  void failsClosedWhenPipelineDependencyThrows() {
    var fixture =
        new Fixture(
            invocation -> {
              throw new IllegalStateException("sensitive implementation detail");
            });

    var result =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\"}",
            fixture.context(null, "pipeline-failure"));

    assertEquals(
        "{\"outcome\":\"FAILED\",\"reasonCode\":\"SPRING_AI_PIPELINE_FAILED\"}", result);
    assertEquals(0, fixture.sideEffects.get());
  }

  @Test
  void safelySerializesToolOutputAsJson() {
    var output = "quoted \"value\" with \\ and\na new line";
    var fixture = new Fixture(output);

    var response =
        fixture.callback.call(
            "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\"}",
            fixture.context(null, "escaped-output"));
    var envelope = new JsonHelper().fromJsonToMap(response);

    assertEquals(output, envelope.get("output"));
  }

  private static final class Fixture {

    private final AtomicInteger sideEffects = new AtomicInteger();
    private final InMemoryApprovalService approvals =
        new InMemoryApprovalService(
            Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC),
            () -> "spring-approval",
            new InvocationFingerprinter());
    private final SpringAiToolContract contract = contract();
    private final String fixedOutput;
    private final GuardedToolCallback callback;

    private Fixture() {
      this(invocation -> new GateDecision(true, "HTTP_AUTHORIZED"), null);
    }

    private Fixture(Authorizer authorizer) {
      this(authorizer, null);
    }

    private Fixture(String fixedOutput) {
      this(invocation -> new GateDecision(true, "HTTP_AUTHORIZED"), fixedOutput);
    }

    private Fixture(Authorizer authorizer, String fixedOutput) {
      this.fixedOutput = fixedOutput;
      callback = callback(authorizer);
    }

    private GuardedToolCallback callback(Authorizer authorizer) {
      var pipeline =
          new ResultDecisionPipeline(
              new ResultDecisionPipeline.Dependencies(
                  invocation -> new GateDecision(true, "VALIDATED"),
                  invocation -> invocation,
                  authorizer,
                  new HttpRiskEvaluator(new HttpRiskPolicy(Set.of("api.example.com"), 1024)),
                  approvals,
                  new InMemoryResultIdempotencyGuard(),
                  invocation -> {
                    var execution = sideEffects.incrementAndGet();
                    return fixedOutput == null ? "api-response-" + execution : fixedOutput;
                  },
                  new InMemoryAuditLog()));
      var definition =
          ToolDefinition.builder()
              .name("http.request")
              .description("Call an approved external HTTP API")
              .inputSchema("{\"type\":\"object\"}")
              .build();
      return new GuardedToolCallback(definition, pipeline, contract);
    }

    private ToolContext context(String approvalRequestId, String idempotencyKey) {
      var values = new HashMap<String, Object>();
      values.put(SpringAiToolContextKeys.PRINCIPAL_ID, "workspace-agent");
      values.put(SpringAiToolContextKeys.TENANT_ID, "tenant-a");
      values.put(SpringAiToolContextKeys.ENVIRONMENT, "production");
      values.put(SpringAiToolContextKeys.IDEMPOTENCY_KEY, idempotencyKey);
      if (approvalRequestId != null) {
        values.put(SpringAiToolContextKeys.APPROVAL_REQUEST_ID, approvalRequestId);
      }
      return new ToolContext(Map.copyOf(values));
    }

    private ToolInvocation writeInvocation() {
      return new ToolInvocation(
          contract.descriptor(),
          new Principal("workspace-agent", Map.of()),
          contract.action(),
          new Resource("http", "https://api.example.com/items", Map.of()),
          new InvocationContext("tenant-a", "production"),
          Map.of(
              "uri", "https://api.example.com/items",
              "method", "POST",
              "payload", "{}"));
    }
  }

  private static SpringAiToolContract contract() {
    return new SpringAiToolContract(
        new ToolDescriptor(
            "http.request",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action("http.request"),
        "http",
        "uri");
  }
}

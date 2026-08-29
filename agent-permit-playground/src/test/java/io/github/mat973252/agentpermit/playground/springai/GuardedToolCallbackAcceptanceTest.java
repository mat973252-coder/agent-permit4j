package io.github.mat973252.agentpermit.playground.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.approval.InMemoryApprovalService;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.audit.InMemoryAuditLog;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.policy.Authorizer;
import io.github.mat973252.agentpermit.policy.http.HttpRiskEvaluator;
import io.github.mat973252.agentpermit.policy.http.HttpRiskPolicy;
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
    var invocation = fixture.mapper.map(input, fixture.context(null, "write-approved")).invocation();
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
    private final SpringAiInvocationMapper mapper = mapper();
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
      return new GuardedToolCallback(definition, pipeline, mapper);
    }

    private ToolContext context(String approvalRequestId, String idempotencyKey) {
      var values = new HashMap<String, Object>();
      values.put(SpringAiInvocationMapper.PRINCIPAL_ID, "workspace-agent");
      values.put(SpringAiInvocationMapper.TENANT_ID, "tenant-a");
      values.put(SpringAiInvocationMapper.ENVIRONMENT, "production");
      values.put(SpringAiInvocationMapper.IDEMPOTENCY_KEY, idempotencyKey);
      if (approvalRequestId != null) {
        values.put(SpringAiInvocationMapper.APPROVAL_REQUEST_ID, approvalRequestId);
      }
      return new ToolContext(Map.copyOf(values));
    }
  }

  private static SpringAiInvocationMapper mapper() {
    return new SpringAiInvocationMapper(
        new SpringAiInvocationMapper.Contract(
            new ToolDescriptor(
                "http.request",
                ToolEffect.EXECUTE,
                Reversibility.COMPENSATABLE,
                DataSensitivity.INTERNAL),
            new Action("http.request"),
            "http",
            "uri"));
  }
}

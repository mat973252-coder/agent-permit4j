package io.github.mat973252.agentpermit.playground.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class SpringAiInvocationMapperTest {

  private final SpringAiInvocationMapper mapper = mapper();

  @Test
  void mapsFlatJsonAndTrustedToolContext() {
    var mapped =
        mapper.map(
            "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\",\"limit\":2}",
            context("request-1", "key-1"));

    assertEquals("workspace-agent", mapped.invocation().principal().id());
    assertFalse(mapped.invocation().arguments().containsKey(SpringAiInvocationMapper.PRINCIPAL_ID));
    assertEquals("tenant-a", mapped.invocation().context().tenantId());
    assertEquals("production", mapped.invocation().context().environment());
    assertEquals("https://api.example.com/items", mapped.invocation().resource().identifier());
    assertEquals("GET", mapped.invocation().arguments().get("method"));
    assertEquals("2", mapped.invocation().arguments().get("limit"));
    assertEquals("request-1", mapped.approvalRequestId());
    assertEquals("key-1", mapped.idempotencyKey());
    assertFalse(mapped.invocation().arguments().containsKey(SpringAiInvocationMapper.IDEMPOTENCY_KEY));
  }

  @Test
  void modelArgumentsCannotOverrideTrustedIdentity() {
    var mapped =
        mapper.map(
            "{\"uri\":\"https://api.example.com/items\",\"method\":\"GET\","
                + "\"agentPermit.principalId\":\"admin\","
                + "\"agentPermit.tenantId\":\"other-tenant\","
                + "\"agentPermit.environment\":\"development\","
                + "\"agentPermit.approvalRequestId\":\"other-approval\","
                + "\"agentPermit.idempotencyKey\":\"other-key\"}",
            context(null, "key-1"));

    assertEquals("workspace-agent", mapped.invocation().principal().id());
    assertEquals("tenant-a", mapped.invocation().context().tenantId());
    assertEquals("production", mapped.invocation().context().environment());
    assertNull(mapped.approvalRequestId());
    assertEquals("key-1", mapped.idempotencyKey());
  }

  @Test
  void rejectsNestedArgumentsAndMissingTransportContext() {
    var nested =
        assertThrows(
            SpringAiMappingException.class,
            () ->
                mapper.map(
                    "{\"uri\":\"https://api.example.com/items\",\"payload\":{\"x\":1}}",
                    context(null, "key-1")));
    var missingContext =
        assertThrows(
            SpringAiMappingException.class,
            () -> mapper.map("{\"uri\":\"https://api.example.com/items\"}", null));
    var missingIdempotency =
        assertThrows(
            SpringAiMappingException.class,
            () ->
                mapper.map(
                    "{\"uri\":\"https://api.example.com/items\"}", context(null, null)));
    var nullDocument =
        assertThrows(
            SpringAiMappingException.class, () -> mapper.map("null", context(null, "key-1")));

    assertEquals("SPRING_AI_INPUT_INVALID", nested.reasonCode());
    assertEquals("SPRING_AI_CONTEXT_INVALID", missingContext.reasonCode());
    assertEquals("SPRING_AI_CONTEXT_INVALID", missingIdempotency.reasonCode());
    assertEquals("SPRING_AI_INPUT_INVALID", nullDocument.reasonCode());
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

  private static ToolContext context(String approvalRequestId, String idempotencyKey) {
    var values = new HashMap<String, Object>();
    values.put(SpringAiInvocationMapper.PRINCIPAL_ID, "workspace-agent");
    values.put(SpringAiInvocationMapper.TENANT_ID, "tenant-a");
    values.put(SpringAiInvocationMapper.ENVIRONMENT, "production");
    if (approvalRequestId != null) {
      values.put(SpringAiInvocationMapper.APPROVAL_REQUEST_ID, approvalRequestId);
    }
    if (idempotencyKey != null) {
      values.put(SpringAiInvocationMapper.IDEMPOTENCY_KEY, idempotencyKey);
    }
    return new ToolContext(Map.copyOf(values));
  }
}

package io.github.agentpermit4j.springai;

import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

final class SpringAiInvocationMapper {

  private static final String INPUT_INVALID = "SPRING_AI_INPUT_INVALID";
  private static final String CONTEXT_INVALID = "SPRING_AI_CONTEXT_INVALID";
  private static final Set<String> TRANSPORT_KEYS =
      Set.of(
          SpringAiToolContextKeys.PRINCIPAL_ID,
          SpringAiToolContextKeys.TENANT_ID,
          SpringAiToolContextKeys.ENVIRONMENT,
          SpringAiToolContextKeys.APPROVAL_REQUEST_ID,
          SpringAiToolContextKeys.IDEMPOTENCY_KEY);

  private final SpringAiToolContract contract;
  private final JsonHelper json = new JsonHelper();

  SpringAiInvocationMapper(SpringAiToolContract contract) {
    this.contract = Objects.requireNonNull(contract, "contract");
  }

  MappedToolCall map(String toolInput, ToolContext toolContext) {
    var arguments = parseArguments(toolInput);
    var context = trustedContext(toolContext);
    var resourceIdentifier =
        requiredText(arguments.get(contract.resourceIdentifierArgument()), INPUT_INVALID);
    var invocation =
        new ToolInvocation(
            contract.descriptor(),
            new Principal(context.principalId(), Map.of()),
            contract.action(),
            new Resource(contract.resourceType(), resourceIdentifier, Map.of()),
            new InvocationContext(context.tenantId(), context.environment()),
            arguments);
    return new MappedToolCall(invocation, context.approvalRequestId(), context.idempotencyKey());
  }

  private Map<String, String> parseArguments(String toolInput) {
    final Map<String, Object> parsed;
    try {
      parsed = json.fromJsonToMap(toolInput);
    } catch (RuntimeException exception) {
      throw new SpringAiMappingException(INPUT_INVALID);
    }
    var arguments = new HashMap<String, String>();
    for (var entry : parsed.entrySet()) {
      if (!TRANSPORT_KEYS.contains(entry.getKey())) {
        arguments.put(entry.getKey(), scalarText(entry.getValue()));
      }
    }
    return Map.copyOf(arguments);
  }

  private static TrustedContext trustedContext(ToolContext toolContext) {
    if (toolContext == null) {
      throw new SpringAiMappingException(CONTEXT_INVALID);
    }
    var values = toolContext.getContext();
    return new TrustedContext(
        requiredText(values.get(SpringAiToolContextKeys.PRINCIPAL_ID), CONTEXT_INVALID),
        requiredText(values.get(SpringAiToolContextKeys.TENANT_ID), CONTEXT_INVALID),
        requiredText(values.get(SpringAiToolContextKeys.ENVIRONMENT), CONTEXT_INVALID),
        optionalText(values.get(SpringAiToolContextKeys.APPROVAL_REQUEST_ID)),
        requiredText(values.get(SpringAiToolContextKeys.IDEMPOTENCY_KEY), CONTEXT_INVALID));
  }

  private static String scalarText(Object value) {
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    throw new SpringAiMappingException(INPUT_INVALID);
  }

  private static String requiredText(Object value, String reasonCode) {
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new SpringAiMappingException(reasonCode);
  }

  private static String optionalText(Object value) {
    if (value == null) {
      return null;
    }
    return requiredText(value, CONTEXT_INVALID);
  }

  record MappedToolCall(
      ToolInvocation invocation, String approvalRequestId, String idempotencyKey) {}

  private record TrustedContext(
      String principalId,
      String tenantId,
      String environment,
      String approvalRequestId,
      String idempotencyKey) {}
}

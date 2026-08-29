package io.github.mat973252.agentpermit.playground.springai;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.util.JsonHelper;

final class SpringAiInvocationMapper {

  static final String PRINCIPAL_ID = "agentPermit.principalId";
  static final String TENANT_ID = "agentPermit.tenantId";
  static final String ENVIRONMENT = "agentPermit.environment";
  static final String APPROVAL_REQUEST_ID = "agentPermit.approvalRequestId";
  static final String IDEMPOTENCY_KEY = "agentPermit.idempotencyKey";

  private static final String INPUT_INVALID = "SPRING_AI_INPUT_INVALID";
  private static final String CONTEXT_INVALID = "SPRING_AI_CONTEXT_INVALID";
  private static final Set<String> TRANSPORT_KEYS =
      Set.of(PRINCIPAL_ID, TENANT_ID, ENVIRONMENT, APPROVAL_REQUEST_ID, IDEMPOTENCY_KEY);

  private final Contract contract;
  private final JsonHelper json = new JsonHelper();

  SpringAiInvocationMapper(Contract contract) {
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
        requiredText(values.get(PRINCIPAL_ID), CONTEXT_INVALID),
        requiredText(values.get(TENANT_ID), CONTEXT_INVALID),
        requiredText(values.get(ENVIRONMENT), CONTEXT_INVALID),
        optionalText(values.get(APPROVAL_REQUEST_ID)),
        requiredText(values.get(IDEMPOTENCY_KEY), CONTEXT_INVALID));
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

  record Contract(
      ToolDescriptor descriptor,
      Action action,
      String resourceType,
      String resourceIdentifierArgument) {

    Contract {
      descriptor = Objects.requireNonNull(descriptor, "descriptor");
      action = Objects.requireNonNull(action, "action");
      resourceType = requireContractText(resourceType, "resourceType");
      resourceIdentifierArgument =
          requireContractText(resourceIdentifierArgument, "resourceIdentifierArgument");
    }

    private static String requireContractText(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
      return value;
    }
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

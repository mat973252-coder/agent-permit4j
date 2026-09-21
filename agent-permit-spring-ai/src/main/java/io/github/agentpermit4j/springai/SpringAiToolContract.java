package io.github.agentpermit4j.springai;

import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.ToolDescriptor;
import java.util.Objects;

public record SpringAiToolContract(
    ToolDescriptor descriptor,
    Action action,
    String resourceType,
    String resourceIdentifierArgument) {

  public SpringAiToolContract {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    action = Objects.requireNonNull(action, "action");
    resourceType = requireText(resourceType, "resourceType");
    resourceIdentifierArgument =
        requireText(resourceIdentifierArgument, "resourceIdentifierArgument");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

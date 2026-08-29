package io.github.mat973252.agentpermit.springai;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
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

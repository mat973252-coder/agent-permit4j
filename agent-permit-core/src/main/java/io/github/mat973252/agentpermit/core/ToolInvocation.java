package io.github.mat973252.agentpermit.core;

import java.util.Map;
import java.util.Objects;

public record ToolInvocation(
    ToolDescriptor descriptor,
    Principal principal,
    Action action,
    Resource resource,
    InvocationContext context,
    Map<String, String> arguments) {

  public ToolInvocation {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    principal = Objects.requireNonNull(principal, "principal");
    action = Objects.requireNonNull(action, "action");
    resource = Objects.requireNonNull(resource, "resource");
    context = Objects.requireNonNull(context, "context");
    arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
  }
}

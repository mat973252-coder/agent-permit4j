package io.github.mat973252.agentpermit.policy.file;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.policy.Authorizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class ProtectedPathAuthorizer implements Authorizer {

  private static final List<String> PROTECTED_ROOT = List.of("workspace");

  @Override
  public GateDecision authorize(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    var fileAction = invocation.action().name().toLowerCase(Locale.ROOT).startsWith("file.");
    var fileResource = "file".equalsIgnoreCase(invocation.resource().type());
    if (fileAction && !fileResource) {
      return denied("FILE_RESOURCE_REQUIRED");
    }
    if (!fileResource) {
      return allowed();
    }

    var path = parse(invocation.resource().identifier());
    if (path.isEmpty()) {
      return denied("INVALID_FILE_PATH");
    }
    if (!"file.delete".equalsIgnoreCase(invocation.action().name())) {
      return allowed();
    }

    var recursive = parseRecursive(invocation);
    if (recursive.isEmpty()) {
      return denied("INVALID_RECURSIVE_FLAG");
    }
    if (removesProtectedPath(path.get(), recursive.get())) {
      return denied("PROTECTED_PATH");
    }
    return allowed();
  }

  private static Optional<List<String>> parse(String rawPath) {
    if (containsControl(rawPath) || rawPath.indexOf(':') >= 0) {
      return Optional.empty();
    }

    var portablePath = rawPath.replace('\\', '/');
    if (!portablePath.startsWith("/") || portablePath.startsWith("//")) {
      return Optional.empty();
    }

    var segments = new ArrayList<String>();
    for (var segment : portablePath.split("/+")) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        return Optional.empty();
      }
      segments.add(segment.toLowerCase(Locale.ROOT));
    }
    return Optional.of(List.copyOf(segments));
  }

  private static boolean containsControl(String value) {
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (character < 32 || character == 127) {
        return true;
      }
    }
    return false;
  }

  private static Optional<Boolean> parseRecursive(ToolInvocation invocation) {
    var value = invocation.arguments().get("recursive");
    if (value == null || "false".equalsIgnoreCase(value)) {
      return Optional.of(false);
    }
    if ("true".equalsIgnoreCase(value)) {
      return Optional.of(true);
    }
    return Optional.empty();
  }

  private static boolean removesProtectedPath(List<String> path, boolean recursive) {
    if (path.size() < PROTECTED_ROOT.size()
        || !path.subList(0, PROTECTED_ROOT.size()).equals(PROTECTED_ROOT)) {
      return false;
    }
    return path.size() == PROTECTED_ROOT.size() || recursive;
  }

  private static GateDecision allowed() {
    return new GateDecision(true, "PROTECTED_PATH_ALLOWED");
  }

  private static GateDecision denied(String reasonCode) {
    return new GateDecision(false, reasonCode);
  }
}

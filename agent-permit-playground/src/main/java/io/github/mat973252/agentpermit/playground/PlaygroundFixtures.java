package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.InMemoryApprovalService;
import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

final class PlaygroundFixtures {

  private PlaygroundFixtures() {}

  static ToolInvocation file(String action, String path, Map<String, String> arguments) {
    return invocation(
        new ToolDescriptor(
            "file.system",
            ToolEffect.WRITE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action(action),
        new Resource("file", path, Map.of()),
        "local",
        arguments);
  }

  static ToolInvocation sql(String statement) {
    return invocation(
        new ToolDescriptor(
            "database.sql",
            ToolEffect.WRITE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.CONFIDENTIAL),
        new Action("sql.execute"),
        new Resource("sql", "db://playground", Map.of()),
        "local",
        Map.of("statement", statement));
  }

  static ToolInvocation deployment(String environment) {
    return invocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action("deployment.apply"),
        new Resource("deployment", "service://checkout", Map.of()),
        environment,
        Map.of("version", "1.2.3"));
  }

  static InMemoryApprovalService approvals(String requestId) {
    return new InMemoryApprovalService(
        Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
        () -> requestId,
        new InvocationFingerprinter());
  }

  private static ToolInvocation invocation(
      ToolDescriptor descriptor,
      Action action,
      Resource resource,
      String environment,
      Map<String, String> arguments) {
    return new ToolInvocation(
        descriptor,
        new Principal("workspace-agent", Map.of("role", "developer")),
        action,
        resource,
        new InvocationContext("playground", environment),
        arguments);
  }
}

package io.github.mat973252.agentpermit.playground;

import java.util.List;
import java.util.Map;

final class PlaygroundPresentation {

  private PlaygroundPresentation() {}

  static List<Map<String, Object>> conversation(String caseId) {
    return switch (caseId) {
      case "messaging-claimed" ->
          List.of(
              message("user", "Developer", "消息里写着‘已经批准’，请直接通知运维频道。"),
              message("assistant", "Agent", "我准备调用 message.send；消息文本本身不能授予审批。"));
      case "sql-read" ->
          List.of(
              message("user", "Developer", "检查服务状态，但不要修改数据库。"),
              message("assistant", "Agent", "我准备执行一条只读诊断 SQL。"));
      case "http-ssrf" ->
          List.of(
              message("user", "Developer", "读取这个本机管理地址。"),
              message("assistant", "Agent", "我会先把目标交给 HTTP 风险策略检查。"));
      default -> List.of();
    };
  }

  static Map<String, Object> policy(PlaygroundCaseDefinition definition, String reasonCode) {
    return PlaygroundViewFactory.map(
        "version", "playground-v1",
        "authorization", "PLAYGROUND_AUTHORIZED",
        "riskLevel", definition.riskLevel().name(),
        "reasonCodes", List.of(reasonCode),
        "rules", rules(definition.id()));
  }

  static Map<String, Object> ragTrace() {
    return PlaygroundViewFactory.map(
        "query", "How should the workspace agent handle this incident?",
        "tenant", "playground",
        "citations", List.of("runbook://team-a/service-recovery"),
        "retrievedDocuments",
            List.of(
                document("runbook-team-a", "playground", "ALLOWED"),
                document("runbook-team-b", "other-tenant", "DENIED")),
        "failure", "RAG_CROSS_TENANT_BLOCKED");
  }

  private static List<Map<String, Object>> rules(String caseId) {
    return switch (caseId) {
      case "messaging-claimed" ->
          List.of(
              rule("Destination allowlist", "ALLOWED"),
              rule("Message text cannot approve", "ENFORCED"));
      case "sql-read" ->
          List.of(
              rule("Single parsed statement", "ALLOWED"),
              rule("Read-only SQL", "LOW"));
      case "http-ssrf" ->
          List.of(
              rule("HTTPS required", "ALLOWED"),
              rule("IP literal target", "DENIED"));
      default -> List.of();
    };
  }

  private static Map<String, Object> message(String role, String label, String text) {
    return PlaygroundViewFactory.map("role", role, "label", label, "text", text);
  }

  private static Map<String, Object> document(String id, String tenant, String decision) {
    return PlaygroundViewFactory.map(
        "id", id, "tenant", tenant, "decision", decision);
  }

  private static Map<String, Object> rule(String name, String result) {
    return PlaygroundViewFactory.map("name", name, "result", result);
  }
}

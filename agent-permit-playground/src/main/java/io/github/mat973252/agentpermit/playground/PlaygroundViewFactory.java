package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.audit.AuditEvent;
import io.github.mat973252.agentpermit.audit.ReplaySafeAuditView;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.RiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlaygroundViewFactory {

  private PlaygroundViewFactory() {}

  static Map<String, Object> caseSummary(PlaygroundCaseDefinition definition) {
    return map(
        "id", definition.id(),
        "scenario", definition.scenario(),
        "title", definition.title());
  }

  static Map<String, Object> caseView(PlaygroundCaseSnapshot snapshot, String fingerprint) {
    var definition = snapshot.definition();
    var decision = snapshot.result().decision();
    var response =
        map(
            "id", definition.id(),
            "scenario", definition.scenario(),
            "title", definition.title(),
            "outcome", decision.outcome().name(),
            "reasonCode", decision.reasonCode(),
            "riskLevel", definition.riskLevel().name(),
            "sideEffectCount", snapshot.sideEffectCount(),
            "timelineId", snapshot.timelineId(),
            "conversation", PlaygroundPresentation.conversation(definition.id()),
            "invocation", invocation(definition),
            "timeline", events(snapshot.events()),
            "approval", approval(snapshot, fingerprint),
            "audit", events(snapshot.events()),
            "ragTrace", PlaygroundPresentation.ragTrace(),
            "policy", PlaygroundPresentation.policy(definition, decision.reasonCode()),
            "replay", replaySummary(snapshot, fingerprint));
    if (snapshot.approval() != null) {
      response.put("approvalRequestId", snapshot.approval().id());
    }
    return response;
  }

  static Map<String, Object> audit(ReplaySafeAuditView view) {
    return map("timelineId", view.timelineId(), "events", events(view.events()));
  }

  static Map<String, Object> replay(ReplaySafeAuditView view, int sideEffectCount) {
    return map(
        "timelineId", view.timelineId(),
        "safe", true,
        "executed", false,
        "sideEffectCount", sideEffectCount,
        "events", events(view.events()));
  }

  static Map<String, Object> error(String reasonCode) {
    return map("outcome", "DENIED", "reasonCode", reasonCode);
  }

  static LinkedHashMap<String, Object> map(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("entries must be key-value pairs");
    }
    var values = new LinkedHashMap<String, Object>();
    for (var index = 0; index < entries.length; index += 2) {
      values.put((String) entries[index], entries[index + 1]);
    }
    return values;
  }

  private static Map<String, Object> invocation(PlaygroundCaseDefinition definition) {
    var invocation = definition.invocation();
    return map(
        "tool", invocation.descriptor().name(),
        "principal", invocation.principal().id(),
        "tenant", invocation.context().tenantId(),
        "environment", invocation.context().environment(),
        "operation", invocation.action().name(),
        "resource", invocation.resource().identifier(),
        "arguments", invocation.arguments(),
        "normalizedArguments", invocation.arguments());
  }

  private static Map<String, Object> approval(
      PlaygroundCaseSnapshot snapshot, String fingerprint) {
    var definition = snapshot.definition();
    var request = snapshot.approval();
    var status = approvalStatus(snapshot);
    return map(
        "status", status,
        "riskLevel", definition.riskLevel().name(),
        "tool", definition.invocation().descriptor().name(),
        "principal", definition.invocation().principal().id(),
        "resource", definition.invocation().resource().identifier(),
        "operation", definition.invocation().action().name(),
        "normalizedArguments", definition.invocation().arguments(),
        "fingerprint", fingerprint,
        "expiry", request == null ? "Not created" : request.expiresAt().toString(),
        "policyVersion", "playground-v1");
  }

  private static String approvalStatus(PlaygroundCaseSnapshot snapshot) {
    if (snapshot.definition().riskLevel() == RiskLevel.LOW) {
      return "NOT_REQUIRED";
    }
    if (snapshot.result().decision().outcome() == DecisionOutcome.DENIED) {
      return "NOT_CREATED";
    }
    return snapshot.approved() ? "APPROVED" : "PENDING";
  }

  private static Map<String, Object> replaySummary(
      PlaygroundCaseSnapshot snapshot, String fingerprint) {
    return map(
        "timelineId", snapshot.timelineId(),
        "fingerprint", fingerprint,
        "safe", true,
        "executed", false,
        "sideEffectCount", snapshot.sideEffectCount());
  }

  private static List<Map<String, Object>> events(List<AuditEvent> events) {
    var result = new ArrayList<Map<String, Object>>(events.size());
    for (var event : events) {
      result.add(
          map(
              "sequence", event.sequence(),
              "stage", event.stage().name(),
              "status", event.status(),
              "reasonCode", event.reasonCode()));
    }
    return List.copyOf(result);
  }
}

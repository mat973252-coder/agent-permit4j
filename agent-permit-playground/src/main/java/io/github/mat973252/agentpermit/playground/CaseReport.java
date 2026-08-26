package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.core.DecisionResult;
import java.util.List;
import java.util.Objects;

public record CaseReport(
    String name, DecisionResult decision, int sideEffectCount, List<AuditStage> timeline) {

  public CaseReport {
    Objects.requireNonNull(name, "name");
    if (name.isBlank() || sideEffectCount < 0) {
      throw new IllegalArgumentException("invalid case report");
    }
    decision = Objects.requireNonNull(decision, "decision");
    timeline = List.copyOf(Objects.requireNonNull(timeline, "timeline"));
  }
}

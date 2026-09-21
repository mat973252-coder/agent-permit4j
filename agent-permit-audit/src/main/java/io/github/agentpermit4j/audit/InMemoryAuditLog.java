package io.github.agentpermit4j.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class InMemoryAuditLog implements AuditSink {

  private final List<AuditEvent> events = new ArrayList<>();
  private long legacyTimelineSequence;

  @Override
  public synchronized void record(DecisionAuditEvent event) {
    Objects.requireNonNull(event, "event");
    var subject = new AuditSubject(event.toolName(), event.principalId(), event.tenantId());
    events.add(
        AuditEvent.result(
            "legacy-" + ++legacyTimelineSequence, 1, subject, event.decision()));
  }

  @Override
  public synchronized void record(AuditEvent event) {
    events.add(Objects.requireNonNull(event, "event"));
  }

  public synchronized List<AuditEvent> snapshot() {
    return List.copyOf(events);
  }

  public synchronized ReplaySafeAuditView replaySafeView(String timelineId) {
    var timeline =
        events.stream()
            .filter(event -> event.timelineId().equals(timelineId))
            .sorted(Comparator.comparingInt(AuditEvent::sequence))
            .toList();
    return new ReplaySafeAuditView(timelineId, timeline);
  }
}

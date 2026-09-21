package io.github.agentpermit4j.audit;

import java.util.List;
import java.util.Objects;

public record ReplaySafeAuditView(String timelineId, List<AuditEvent> events) {

  public ReplaySafeAuditView {
    Objects.requireNonNull(timelineId, "timelineId");
    if (timelineId.isBlank()) {
      throw new IllegalArgumentException("timelineId must not be blank");
    }
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    int previous = 0;
    for (var event : events) {
      if (!timelineId.equals(event.timelineId()) || event.sequence() <= previous) {
        throw new IllegalArgumentException("events must be ordered within one timeline");
      }
      previous = event.sequence();
    }
  }
}

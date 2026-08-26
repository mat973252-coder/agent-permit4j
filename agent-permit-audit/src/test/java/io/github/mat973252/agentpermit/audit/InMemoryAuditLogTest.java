package io.github.mat973252.agentpermit.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.DecisionOutcome;
import io.github.mat973252.agentpermit.core.DecisionResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryAuditLogTest {

  private static final AuditSubject SUBJECT =
      new AuditSubject("deployment.apply", "agent-1", "tenant-a");

  @Test
  void appendsEventsAndReturnsDetachedImmutableSnapshots() {
    var log = new InMemoryAuditLog();
    var policy = AuditEvent.stage("timeline-1", 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED");
    var result =
        AuditEvent.result(
            "timeline-1",
            2,
            SUBJECT,
            new DecisionResult(DecisionOutcome.EXECUTED, "DEPLOYMENT_SAFE"));

    log.record(policy);
    var firstSnapshot = log.snapshot();
    log.record(result);

    assertEquals(List.of(policy), firstSnapshot);
    assertEquals(List.of(policy, result), log.snapshot());
    assertThrows(UnsupportedOperationException.class, () -> log.snapshot().clear());
  }

  @Test
  void replaySafeViewFiltersAndOrdersWithoutChangingTheLog() {
    var log = new InMemoryAuditLog();
    var result =
        AuditEvent.result(
            "timeline-1",
            2,
            SUBJECT,
            new DecisionResult(DecisionOutcome.EXECUTED, "DEPLOYMENT_SAFE"));
    var policy = AuditEvent.stage("timeline-1", 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED");
    var other = AuditEvent.stage("timeline-2", 1, AuditStage.POLICY, SUBJECT, "DENIED", "PROTECTED_PATH");
    log.record(result);
    log.record(other);
    log.record(policy);

    var view = log.replaySafeView("timeline-1");

    assertEquals(List.of(policy, result), view.events());
    assertEquals(3, log.snapshot().size());
    assertThrows(UnsupportedOperationException.class, () -> view.events().removeFirst());
  }
}

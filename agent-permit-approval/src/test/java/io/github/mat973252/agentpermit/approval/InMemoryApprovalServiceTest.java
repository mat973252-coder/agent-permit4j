package io.github.mat973252.agentpermit.approval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryApprovalServiceTest {

  private MutableClock clock;
  private InMemoryApprovalService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
    var sequence = new AtomicInteger();
    service =
        new InMemoryApprovalService(
            clock,
            () -> "approval-" + sequence.incrementAndGet(),
            new InvocationFingerprinter());
  }

  @Test
  void approvesAndVerifiesMatchingInvocationBeforeExpiry() {
    var invocation = invocation("service://checkout", "1.2.3");
    var request = service.request(invocation, Duration.ofMinutes(5));

    var pending = service.verify(request.id(), invocation);
    var approved = service.approve(request.id());
    var verified = service.verify(request.id(), invocation);

    assertAll(
        () -> assertFalse(pending.permitted()),
        () -> assertEquals("APPROVAL_PENDING", pending.reasonCode()),
        () -> assertTrue(approved.permitted()),
        () -> assertEquals("APPROVAL_APPROVED", approved.reasonCode()),
        () -> assertTrue(verified.permitted()),
        () -> assertEquals("APPROVAL_VALID", verified.reasonCode()));
  }

  @Test
  void expiresAtTheExactBoundary() {
    var invocation = invocation("service://checkout", "1.2.3");
    var request = service.request(invocation, Duration.ofMinutes(5));
    service.approve(request.id());

    clock.advance(Duration.ofMinutes(5));
    var verified = service.verify(request.id(), invocation);

    assertAll(
        () -> assertFalse(verified.permitted()),
        () -> assertEquals("APPROVAL_EXPIRED", verified.reasonCode()));
  }

  @Test
  void rejectsApprovalAfterExpiryAndUnknownRequest() {
    var request = service.request(invocation("service://checkout", "1.2.3"), Duration.ofSeconds(1));
    clock.advance(Duration.ofSeconds(2));

    var expired = service.approve(request.id());
    var unknown = service.approve("missing");

    assertAll(
        () -> assertFalse(expired.permitted()),
        () -> assertEquals("APPROVAL_EXPIRED", expired.reasonCode()),
        () -> assertFalse(unknown.permitted()),
        () -> assertEquals("APPROVAL_NOT_FOUND", unknown.reasonCode()));
  }

  @Test
  void rejectsNonPositiveRequestLifetime() {
    var invocation = invocation("service://checkout", "1.2.3");

    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> service.request(invocation, Duration.ZERO)),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> service.request(invocation, Duration.ofSeconds(-1))));
  }

  @Test
  void invalidatesApprovalWhenInvocationFingerprintChanges() {
    var original = invocation("service://checkout", "1.2.3");
    var request = service.request(original, Duration.ofMinutes(5));
    service.approve(request.id());

    var changed = service.verify(request.id(), invocation("service://checkout", "2.0.0"));

    assertAll(
        () -> assertFalse(changed.permitted()),
        () -> assertEquals("APPROVAL_INVOCATION_MISMATCH", changed.reasonCode()));
  }

  private static ToolInvocation invocation(String service, String version) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
        new Action("deployment.apply"),
        new Resource("deployment", service, Map.of()),
        new InvocationContext("tenant-a", "production"),
        Map.of("version", version));
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

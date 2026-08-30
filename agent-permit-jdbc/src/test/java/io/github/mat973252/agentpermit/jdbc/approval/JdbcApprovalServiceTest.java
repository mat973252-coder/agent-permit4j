package io.github.mat973252.agentpermit.jdbc.approval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import io.github.mat973252.agentpermit.execution.DecisionPipeline;
import io.github.mat973252.agentpermit.execution.InMemoryIdempotencyGuard;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcApprovalServiceTest {

  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

  private JdbcDataSource dataSource;
  private MutableClock clock;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:approval-"
            + DATABASE_SEQUENCE.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
    initializeSchema();
  }

  @Test
  void persistsApprovalAcrossServiceInstances() {
    var invocation = invocation("1.2.3");
    var creator = service(() -> "approval-1");
    var request = creator.request(invocation, Duration.ofMinutes(5));
    var reloaded = service(() -> "unused");

    var pending = reloaded.verify(request.id(), invocation);
    var approved = reloaded.approve(request.id());
    var valid = creator.verify(request.id(), invocation);

    assertAll(
        () -> assertEquals(Instant.parse("2026-08-30T00:05:00Z"), request.expiresAt()),
        () -> assertEquals("APPROVAL_PENDING", pending.reasonCode()),
        () -> assertEquals("APPROVAL_APPROVED", approved.reasonCode()),
        () -> assertTrue(valid.permitted()),
        () -> assertEquals("APPROVAL_VALID", valid.reasonCode()));
  }

  @Test
  void rejectsChangedInvocationAndExpiresAtExactBoundary() {
    var service = service(() -> "approval-1");
    var request = service.request(invocation("1.2.3"), Duration.ofMinutes(5));
    service.approve(request.id());

    var mismatch = service.verify(request.id(), invocation("2.0.0"));
    clock.advance(Duration.ofMinutes(5));
    var expired = service.verify(request.id(), invocation("1.2.3"));

    assertAll(
        () -> assertFalse(mismatch.permitted()),
        () -> assertEquals("APPROVAL_INVOCATION_MISMATCH", mismatch.reasonCode()),
        () -> assertFalse(expired.permitted()),
        () -> assertEquals("APPROVAL_EXPIRED", expired.reasonCode()));
  }

  @Test
  void approvesOnlyOnceAcrossConcurrentCallers() throws Exception {
    var service = service(() -> "approval-1");
    var request = service.request(invocation("1.2.3"), Duration.ofMinutes(5));
    int callers = 8;
    var ready = new CountDownLatch(callers);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(callers)) {
      var futures = new ArrayList<java.util.concurrent.Future<GateDecision>>();
      for (int index = 0; index < callers; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return service(() -> "unused").approve(request.id());
                }));
      }
      ready.await();
      start.countDown();

      var decisions = new ArrayList<GateDecision>();
      for (var future : futures) {
        decisions.add(future.get());
      }
      assertAll(
          () -> assertEquals(1, count(decisions, "APPROVAL_APPROVED")),
          () -> assertEquals(callers - 1, count(decisions, "APPROVAL_ALREADY_APPROVED")),
          () -> assertTrue(decisions.stream().allMatch(GateDecision::permitted)));
    }
  }

  @Test
  void failsClosedWhenApprovalTableIsUnavailable() throws Exception {
    var service = service(() -> "approval-1");
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE agent_permit_approval_request");
    }

    var approval = service.approve("approval-1");
    var verification = service.verify("approval-1", invocation("1.2.3"));

    var requestFailure =
        assertThrows(
            IllegalStateException.class,
            () -> service.request(invocation("1.2.3"), Duration.ofMinutes(5)));

    assertAll(
        () -> assertFalse(approval.permitted()),
        () -> assertEquals("APPROVAL_STORAGE_UNAVAILABLE", approval.reasonCode()),
        () -> assertFalse(verification.permitted()),
        () -> assertEquals("APPROVAL_STORAGE_UNAVAILABLE", verification.reasonCode()),
        () -> assertEquals("approval storage unavailable", requestFailure.getMessage()),
        () -> assertNull(requestFailure.getCause()));
  }

  @Test
  void doesNotExposeDuplicateIdDatabaseDetails() {
    var service = service(() -> "approval-1");
    service.request(invocation("1.2.3"), Duration.ofMinutes(5));

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.request(invocation("2.0.0"), Duration.ofMinutes(5)));

    assertAll(
        () -> assertEquals("approval storage unavailable", failure.getMessage()),
        () -> assertNull(failure.getCause()),
        () -> assertEquals("APPROVAL_PENDING", service.verify("approval-1", invocation("1.2.3")).reasonCode()));
  }

  @Test
  void rejectsCorruptApprovalState() throws Exception {
    var service = service(() -> "approval-1");
    var request = service.request(invocation("1.2.3"), Duration.ofMinutes(5));
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(
            "UPDATE agent_permit_approval_request SET approved = 2 WHERE request_id = ?")) {
      statement.setString(1, request.id());
      statement.executeUpdate();
    }

    var approval = service.approve(request.id());
    var verification = service.verify(request.id(), invocation("1.2.3"));

    assertAll(
        () -> assertEquals("APPROVAL_STORAGE_UNAVAILABLE", approval.reasonCode()),
        () -> assertEquals("APPROVAL_STORAGE_UNAVAILABLE", verification.reasonCode()));
  }

  @Test
  void executesHighRiskInvocationOnlyAfterPersistedApproval() {
    var approvals = service(() -> "approval-1");
    var invocation = invocation("1.2.3");
    var request = approvals.request(invocation, Duration.ofMinutes(5));
    var executions = new AtomicInteger();
    var pipeline = pipeline(approvals, executions);

    var pending = pipeline.process(invocation, request.id(), "deployment-1");
    approvals.approve(request.id());
    var executed = pipeline.process(invocation, request.id(), "deployment-1");

    assertAll(
        () -> assertEquals("APPROVAL_REQUIRED", pending.outcome().name()),
        () -> assertEquals("EXECUTED", executed.outcome().name()),
        () -> assertEquals(1, executions.get()));
  }

  private JdbcApprovalService service(java.util.function.Supplier<String> idGenerator) {
    return new JdbcApprovalService(dataSource, clock, idGenerator, new InvocationFingerprinter());
  }

  private void initializeSchema() throws Exception {
    try (var input =
            getClass()
                .getClassLoader()
                .getResourceAsStream("io/github/mat973252/agentpermit/jdbc/approval-schema.sql")) {
      assertNotNull(input);
      var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }
  }

  private static long count(java.util.List<GateDecision> decisions, String reasonCode) {
    return decisions.stream().filter(decision -> reasonCode.equals(decision.reasonCode())).count();
  }

  private static DecisionPipeline pipeline(
      JdbcApprovalService approvals, AtomicInteger executions) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.HIGH, "DEPLOYMENT_PRODUCTION"),
            approvals,
            new InMemoryIdempotencyGuard(),
            invocation -> executions.incrementAndGet(),
            event -> {}));
  }

  private static ToolInvocation invocation(String version) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
        new Action("deployment.apply"),
        new Resource("deployment", "service://checkout", Map.of()),
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

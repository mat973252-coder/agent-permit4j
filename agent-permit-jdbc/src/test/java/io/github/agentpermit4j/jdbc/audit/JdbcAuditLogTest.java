package io.github.agentpermit4j.jdbc.audit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.agentpermit4j.audit.AuditEvent;
import io.github.agentpermit4j.audit.AuditStage;
import io.github.agentpermit4j.audit.AuditSubject;
import io.github.agentpermit4j.audit.DecisionAuditEvent;
import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.DecisionResult;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.DecisionPipeline;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcAuditLogTest {

  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
  private static final AuditSubject SUBJECT =
      new AuditSubject("deployment.apply", "agent-1", "tenant-a");

  private JdbcDataSource dataSource;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:audit-" + DATABASE_SEQUENCE.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
    initializeSchema();
  }

  @Test
  void persistsOrderedReplayAcrossServiceInstances() {
    var expected = completeTimeline("timeline-1");
    var writer = log(() -> "unused");
    expected.forEach(writer::record);

    var view = log(() -> "unused").replaySafeView("timeline-1");

    assertAll(
        () -> assertEquals(expected, view.events()),
        () ->
            assertThrows(
                UnsupportedOperationException.class,
                () -> view.events().add(expected.getFirst())));
  }

  @Test
  void bridgesLegacyDecisionEventsToIndependentResultTimelines() {
    var log = log(sequence("legacy-1", "legacy-2"));
    var decision = new DecisionResult(DecisionOutcome.DENIED, "POLICY_DENIED");

    log.record(new DecisionAuditEvent("file.delete", "agent-1", "tenant-a", decision));
    log.record(new DecisionAuditEvent("file.delete", "agent-1", "tenant-a", decision));

    assertAll(
        () -> assertEquals(decision, log.replaySafeView("legacy-1").events().getFirst().decision()),
        () -> assertEquals(1, log.replaySafeView("legacy-2").events().getFirst().sequence()));
  }

  @Test
  void rejectsDuplicateSequenceWithoutOverwriting() {
    var log = log(() -> "unused");
    var original = AuditEvent.stage("timeline-1", 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED");
    var duplicate = AuditEvent.stage("timeline-1", 1, AuditStage.RISK, SUBJECT, "LOW", "LOW_RISK");
    log.record(original);

    var failure = assertThrows(IllegalStateException.class, () -> log.record(duplicate));

    assertAll(
        () -> assertEquals("audit storage unavailable", failure.getMessage()),
        () -> assertNull(failure.getCause()),
        () -> assertEquals(List.of(original), log.replaySafeView("timeline-1").events()));
  }

  @Test
  void appendsOneTimelineConcurrentlyAcrossServiceInstances() throws Exception {
    int callers = 16;
    var ready = new CountDownLatch(callers);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(callers)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int sequence = 1; sequence <= callers; sequence++) {
        int eventSequence = sequence;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  log(() -> "unused")
                      .record(
                          AuditEvent.stage(
                              "timeline-concurrent",
                              eventSequence,
                              AuditStage.POLICY,
                              SUBJECT,
                              "ALLOWED",
                              "EVENT_" + eventSequence));
                  return null;
                }));
      }
      ready.await();
      start.countDown();
      for (var future : futures) {
        future.get();
      }
    }

    var sequences =
        log(() -> "unused").replaySafeView("timeline-concurrent").events().stream()
            .map(AuditEvent::sequence)
            .toList();
    assertEquals(IntStream.rangeClosed(1, callers).boxed().toList(), sequences);
  }

  @Test
  void failsWithGenericErrorWhenAuditTableIsUnavailable() throws Exception {
    dropAuditTable();
    var log = log(() -> "unused");
    var event = AuditEvent.stage("timeline-1", 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED");

    var writeFailure = assertThrows(IllegalStateException.class, () -> log.record(event));
    var readFailure =
        assertThrows(IllegalStateException.class, () -> log.replaySafeView("timeline-1"));

    assertAll(
        () -> assertEquals("audit storage unavailable", writeFailure.getMessage()),
        () -> assertNull(writeFailure.getCause()),
        () -> assertEquals("audit storage unavailable", readFailure.getMessage()),
        () -> assertNull(readFailure.getCause()));
  }

  @Test
  void hidesRuntimeDataSourceFailureDetails() {
    var failingDataSource = runtimeFailingDataSource("private-driver-detail");
    var log = new JdbcAuditLog(failingDataSource, () -> "unused");
    var event = AuditEvent.stage("timeline-1", 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED");

    var writeFailure = assertThrows(IllegalStateException.class, () -> log.record(event));
    var readFailure =
        assertThrows(IllegalStateException.class, () -> log.replaySafeView("timeline-1"));

    assertAll(
        () -> assertEquals("audit storage unavailable", writeFailure.getMessage()),
        () -> assertNull(writeFailure.getCause()),
        () -> assertEquals("audit storage unavailable", readFailure.getMessage()),
        () -> assertNull(readFailure.getCause()));
  }

  @Test
  void rejectsCorruptPersistedEvent() throws Exception {
    insertCorruptEvent();

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> log(() -> "unused").replaySafeView("timeline-corrupt"));

    assertAll(
        () -> assertEquals("audit storage unavailable", failure.getMessage()),
        () -> assertNull(failure.getCause()));
  }

  @Test
  void rejectsResultWhoseStoredStatusDoesNotMatchDecision() throws Exception {
    insertMismatchedResult();

    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> log(() -> "unused").replaySafeView("timeline-mismatch"));

    assertAll(
        () -> assertEquals("audit storage unavailable", failure.getMessage()),
        () -> assertNull(failure.getCause()));
  }

  @Test
  void persistsPipelineTimelineWithoutSensitiveInvocationData() throws Exception {
    var log = log(() -> "unused");
    var executions = new AtomicInteger();
    var pipeline = pipeline(log, executions);

    var decision =
        pipeline.process(invocation(), "private-approval-id", "private-idempotency-key");
    var timelineId = singleTimelineId();
    var view = log.replaySafeView(timelineId);
    var storedText = storedText();

    assertAll(
        () -> assertEquals(DecisionOutcome.EXECUTED, decision.outcome()),
        () -> assertEquals(1, executions.get()),
        () ->
            assertEquals(
                List.of(
                    AuditStage.POLICY,
                    AuditStage.RISK,
                    AuditStage.APPROVAL,
                    AuditStage.EXECUTION,
                    AuditStage.RESULT),
                view.events().stream().map(AuditEvent::stage).toList()),
        () -> assertFalse(storedText.contains("private-version")),
        () -> assertFalse(storedText.contains("private-principal-attribute")),
        () -> assertFalse(storedText.contains("private-resource-attribute")),
        () -> assertFalse(storedText.contains("private-approval-id")),
        () -> assertFalse(storedText.contains("private-idempotency-key")));
  }

  private JdbcAuditLog log(Supplier<String> legacyTimelineIds) {
    return new JdbcAuditLog(dataSource, legacyTimelineIds);
  }

  private static Supplier<String> sequence(String first, String second) {
    var values = List.of(first, second).iterator();
    return values::next;
  }

  private static DataSource runtimeFailingDataSource(String privateMessage) {
    return (DataSource)
        Proxy.newProxyInstance(
            JdbcAuditLogTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getConnection")) {
                throw new IllegalStateException(privateMessage);
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static List<AuditEvent> completeTimeline(String timelineId) {
    return List.of(
        AuditEvent.stage(timelineId, 1, AuditStage.POLICY, SUBJECT, "ALLOWED", "AUTHORIZED"),
        AuditEvent.stage(timelineId, 2, AuditStage.RISK, SUBJECT, "HIGH", "DEPLOYMENT_PRODUCTION"),
        AuditEvent.stage(timelineId, 3, AuditStage.APPROVAL, SUBJECT, "VERIFIED", "APPROVAL_VALID"),
        AuditEvent.stage(timelineId, 4, AuditStage.EXECUTION, SUBJECT, "EXECUTED", "DEPLOYMENT_PRODUCTION"),
        AuditEvent.result(
            timelineId,
            5,
            SUBJECT,
            new DecisionResult(DecisionOutcome.EXECUTED, "DEPLOYMENT_PRODUCTION")));
  }

  private static DecisionPipeline pipeline(JdbcAuditLog auditLog, AtomicInteger executions) {
    return new DecisionPipeline(
        new DecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "DEPLOYMENT_STAGING"),
            invocation -> executions.incrementAndGet(),
            auditLog));
  }

  private static ToolInvocation invocation() {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("private", "private-principal-attribute")),
        new Action("deployment.apply"),
        new Resource(
            "deployment",
            "service://checkout",
            Map.of("private", "private-resource-attribute")),
        new InvocationContext("tenant-a", "staging"),
        Map.of("version", "private-version"));
  }

  private void initializeSchema() throws Exception {
    try (var input =
            getClass()
                .getClassLoader()
                .getResourceAsStream("io/github/agentpermit4j/jdbc/audit-schema.sql")) {
      assertNotNull(input);
      var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }
  }

  private void dropAuditTable() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE agent_permit_audit_event");
    }
  }

  private void insertCorruptEvent() throws Exception {
    var sql =
        "INSERT INTO agent_permit_audit_event "
            + "(timeline_id, event_sequence, stage, tool_name, principal_id, tenant_id, "
            + "status, reason_code, decision_outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, "timeline-corrupt");
      statement.setInt(2, 1);
      statement.setString(3, "INVALID_STAGE");
      statement.setString(4, "deployment.apply");
      statement.setString(5, "agent-1");
      statement.setString(6, "tenant-a");
      statement.setString(7, "ALLOWED");
      statement.setString(8, "AUTHORIZED");
      statement.setString(9, null);
      statement.executeUpdate();
    }
  }

  private void insertMismatchedResult() throws Exception {
    var sql =
        "INSERT INTO agent_permit_audit_event "
            + "(timeline_id, event_sequence, stage, tool_name, principal_id, tenant_id, "
            + "status, reason_code, decision_outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, "timeline-mismatch");
      statement.setInt(2, 1);
      statement.setString(3, "RESULT");
      statement.setString(4, "deployment.apply");
      statement.setString(5, "agent-1");
      statement.setString(6, "tenant-a");
      statement.setString(7, "DENIED");
      statement.setString(8, "DEPLOYMENT_STAGING");
      statement.setString(9, "EXECUTED");
      statement.executeUpdate();
    }
  }

  private String singleTimelineId() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(
            "SELECT DISTINCT timeline_id FROM agent_permit_audit_event")) {
      result.next();
      var timelineId = result.getString(1);
      assertFalse(result.next());
      return timelineId;
    }
  }

  private String storedText() throws Exception {
    var sql =
        "SELECT timeline_id, stage, tool_name, principal_id, tenant_id, status, reason_code, "
            + "decision_outcome FROM agent_permit_audit_event";
    var text = new StringBuilder();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      while (result.next()) {
        for (int column = 1; column <= 8; column++) {
          text.append(result.getString(column)).append('|');
        }
      }
    }
    return text.toString();
  }
}

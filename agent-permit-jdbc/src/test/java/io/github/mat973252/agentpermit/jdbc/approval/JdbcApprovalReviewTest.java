package io.github.mat973252.agentpermit.jdbc.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.approval.InvocationFingerprinter;
import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.core.Resource;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.core.ToolInvocation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcApprovalReviewTest {

  @Test
  void concurrentReviewersPersistExactlyOneDecision() throws Exception {
    var source = source();
    var service = service(source);
    var invocation = invocation();
    var request = service.requestReview(invocation, Duration.ofMinutes(5));
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = IntStream.range(0, 8).mapToObj(index -> (Callable<GateDecision>) () ->
          service(source).approve(request.id(), invocation, new Principal("reviewer-" + index, Map.of())))
          .toList();
      var results = pool.invokeAll(tasks);
      int firstDecisions = 0;
      for (var result : results) {
        assertTrue(result.get().permitted());
        if (result.get().reasonCode().equals("APPROVAL_APPROVED")) {
          firstDecisions++;
        }
      }
      assertEquals(1, firstDecisions);
    }
    assertTrue(service(source).decision(request.id()).orElseThrow().approverId().startsWith("reviewer-"));
  }

  @Test
  void decisionStorageFailureRollsBackApproval() throws Exception {
    var source = source();
    var service = service(source);
    var request = service.requestReview(invocation(), Duration.ofMinutes(5));
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      statement.execute("DROP TABLE agent_permit_approval_decision");
    }
    var result = service.approve(request.id(), invocation(), new Principal("reviewer", Map.of()));
    assertFalse(result.permitted());
    assertEquals("APPROVAL_STORAGE_UNAVAILABLE", result.reasonCode());
    assertEquals("APPROVAL_PENDING", service.verify(request.id(), invocation()).reasonCode());
    assertEquals("APPROVAL_REVIEW_REQUIRED", service.approve(request.id()).reasonCode());
  }

  private static JdbcApprovalService service(JdbcDataSource source) {
    return new JdbcApprovalService(source,
        Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
        () -> UUID.randomUUID().toString(), new InvocationFingerprinter(),
        (actor, invocation) -> new GateDecision(true, "TRUSTED_TEST_REVIEWER"));
  }

  private static JdbcDataSource source() throws Exception {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:review-transaction-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      for (var name : new String[] {"approval-schema.sql", "approval-review-schema.sql"}) {
        try (var resource = JdbcApprovalService.class.getResourceAsStream(
            "/io/github/mat973252/agentpermit/jdbc/" + name)) {
          statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    return source;
  }

  private static ToolInvocation invocation() {
    return new ToolInvocation(new ToolDescriptor("refund", ToolEffect.WRITE,
        Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED), new Principal("requester", Map.of()),
        new Action("refund"), new Resource("order", "1", Map.of()),
        new InvocationContext("tenant-a", "test"), Map.of("amount", "100"));
  }
}

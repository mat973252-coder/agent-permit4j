package io.github.agentpermit4j.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.approval.ApprovalAuthorizer;
import io.github.agentpermit4j.approval.ApprovalDecision;
import io.github.agentpermit4j.approval.ApprovalRequest;
import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.jdbc.approval.JdbcApprovalService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedApprovalAcceptanceTest {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ApprovalAuthorizer AUTHORIZER = (approver, invocation) -> {
    var allowed = "reviewer".equals(approver.attributes().get("role"))
        && invocation.context().tenantId().equals(approver.attributes().get("tenant"))
        && !invocation.principal().id().equals(approver.id());
    return new GateDecision(allowed, allowed ? "REVIEW_ALLOWED" : "REVIEWER_DENIED");
  };

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void requiresAuthorizedReviewerAndPreservesTheFirstDecision(boolean jdbc) throws Exception {
    var store = store(jdbc);
    var invocation = invocation("100", "revision-1");
    var request = store.request.apply(invocation);
    assertEquals("APPROVAL_REVIEW_REQUIRED", store.legacy.apply(request.id()).reasonCode());
    for (var approver : new Principal[] {
        actor("requester", "reviewer", "tenant-a"),
        actor("reviewer-b", "reviewer", "tenant-b"),
        actor("reader", "reader", "tenant-a")}) {
      assertEquals("REVIEWER_DENIED",
          store.approve.apply(new Review(request.id(), invocation, approver)).reasonCode());
    }
    assertTrue(store.decision.apply(request.id()).isEmpty());
    assertFalse(store.verifier.verify(request.id(), invocation).permitted());
    assertTrue(store.approve.apply(new Review(request.id(), invocation,
        actor("reviewer-a", "reviewer", "tenant-a"))).permitted());
    assertEquals("APPROVAL_ALREADY_APPROVED", store.approve.apply(new Review(
        request.id(), invocation, actor("reviewer-c", "reviewer", "tenant-a"))).reasonCode());
    var decision = store.decision.apply(request.id()).orElseThrow();
    assertEquals(new ApprovalDecision(request.id(), "reviewer-a", "tenant-a", NOW), decision);
    assertTrue(store.verifier.verify(request.id(), invocation).permitted());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsChangedAmountAndPolicyBeforeRecordingApproval(boolean jdbc) throws Exception {
    var store = store(jdbc);
    var request = store.request.apply(invocation("100", "revision-1"));
    var approver = actor("reviewer-a", "reviewer", "tenant-a");
    for (var changed : new ToolInvocation[] {
        invocation("200", "revision-1"), invocation("100", "revision-2")}) {
      assertEquals("APPROVAL_INVOCATION_MISMATCH",
          store.approve.apply(new Review(request.id(), changed, approver)).reasonCode());
    }
    assertTrue(store.decision.apply(request.id()).isEmpty());
  }

  private static Store store(boolean jdbc) throws Exception {
    var fingerprinter = new InvocationFingerprinter();
    if (!jdbc) {
      var service = new InMemoryApprovalService(CLOCK, () -> UUID.randomUUID().toString(),
          fingerprinter, AUTHORIZER);
      return new Store(invocation -> service.requestReview(invocation, Duration.ofMinutes(5)),
          service::approve, review -> service.approve(review.id, review.invocation, review.approver),
          service::decision, service);
    }
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:review-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
      for (var name : new String[] {"approval-schema.sql", "approval-review-schema.sql"}) {
        try (var resource = JdbcApprovalService.class.getResourceAsStream(
            "/io/github/agentpermit4j/jdbc/" + name)) {
          statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    var service = new JdbcApprovalService(source, CLOCK, () -> UUID.randomUUID().toString(),
        fingerprinter, AUTHORIZER);
    var reloaded = new JdbcApprovalService(source, CLOCK, () -> UUID.randomUUID().toString(),
        fingerprinter, AUTHORIZER);
    return new Store(invocation -> service.requestReview(invocation, Duration.ofMinutes(5)),
        reloaded::approve,
        review -> reloaded.approve(review.id, review.invocation, review.approver),
        service::decision, reloaded);
  }

  private static Principal actor(String id, String role, String tenant) {
    return new Principal(id, Map.of("role", role, "tenant", tenant));
  }

  private static ToolInvocation invocation(String amount, String policyRevision) {
    return new ToolInvocation(new ToolDescriptor("orders.refund", ToolEffect.WRITE,
        Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED),
        new Principal("requester", Map.of()), new Action("orders.refund"),
        new Resource("order", "order-1", Map.of("version", "0", "policyRevision", policyRevision)),
        new InvocationContext("tenant-a", "test"), Map.of("amountCents", amount));
  }

  private record Review(String id, ToolInvocation invocation, Principal approver) {}
  private record Store(Function<ToolInvocation, ApprovalRequest> request,
      Function<String, GateDecision> legacy, Function<Review, GateDecision> approve,
      Function<String, Optional<ApprovalDecision>> decision, ApprovalVerifier verifier) {}
}

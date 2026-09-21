package io.github.agentpermit4j.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.agentpermit4j.approval.InMemoryApprovalService;
import io.github.agentpermit4j.approval.InvocationFingerprinter;
import io.github.agentpermit4j.audit.InMemoryAuditLog;
import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.DecisionOutcome;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.execution.InMemoryResultIdempotencyGuard;
import io.github.agentpermit4j.execution.ResultDecisionPipeline;
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

class RefundLedgerAcceptanceTest {

  @Test
  void approvalAndConcurrentRetriesProduceOnePaymentAndOneLedgerChange() throws Exception {
    var fixture = new Fixture();
    var invocation = fixture.invocation(2_500, 0);
    var awaiting = fixture.pipeline.process(invocation, null, "refund-1");
    assertEquals(DecisionOutcome.APPROVAL_REQUIRED, awaiting.decision().outcome());
    assertEquals(0, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(0, fixture.payments.paymentCount());

    var request = fixture.approvals.request(invocation, Duration.ofMinutes(5));
    fixture.approvals.approve(request.id());
    try (var executor = Executors.newFixedThreadPool(8)) {
      var calls = IntStream.range(0, 8)
          .mapToObj(ignored -> (Callable<String>) () ->
              fixture.pipeline.process(invocation, request.id(), "refund-1").output())
          .toList();
      var results = executor.invokeAll(calls);
      for (var result : results) {
        assertEquals("tenant-a/order-1/0", result.get());
      }
    }
    assertEquals(2_500, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(1, fixture.ledger.find("tenant-a", "order-1").version());
    assertEquals(1, fixture.payments.paymentCount());
  }

  @Test
  void staleVersionOrExcessiveAmountCannotReachPayment() {
    var fixture = new Fixture();
    fixture.ledger.refund(new RefundCommand("tenant-a", "order-1", 1_000, 0));
    assertThrows(IllegalStateException.class,
        () -> fixture.ledger.refund(new RefundCommand("tenant-a", "order-1", 500, 0)));
    assertThrows(IllegalStateException.class,
        () -> fixture.ledger.refund(new RefundCommand("tenant-a", "order-1", 20_000, 1)));
    assertThrows(IllegalStateException.class,
        () -> fixture.ledger.refund(new RefundCommand("other-tenant", "order-1", 500, 1)));
    assertEquals(1, fixture.payments.paymentCount());
    assertEquals(1_000, fixture.ledger.find("tenant-a", "order-1").refundedCents());
  }

  @Test
  void knownPaymentFailureRollsBackTheLedgerAndIsNotAutomaticallyRetried() {
    var fixture = new Fixture();
    var invocation = fixture.invocation(2_500, 0);
    var request = fixture.approvals.request(invocation, Duration.ofMinutes(5));
    fixture.approvals.approve(request.id());
    fixture.payments.rejectNextPayment();

    var first = fixture.pipeline.process(invocation, request.id(), "failed-refund");
    var retry = fixture.pipeline.process(invocation, request.id(), "failed-refund");
    assertEquals(first, retry);
    assertEquals(DecisionOutcome.FAILED, first.decision().outcome());
    assertEquals("EXECUTION_FAILED", first.decision().reasonCode());
    assertEquals(0, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(0, fixture.ledger.find("tenant-a", "order-1").version());
    assertEquals(0, fixture.payments.paymentCount());
  }

  @Test
  void orderChangedDuringApprovalVerificationIsRejectedBeforeRefundPayment() {
    var fixture = new Fixture(true);
    var invocation = fixture.invocation(2_500, 0);
    var request = fixture.approvals.request(invocation, Duration.ofMinutes(5));
    fixture.approvals.approve(request.id());
    var result = fixture.pipeline.process(invocation, request.id(), "changed-after-preflight");
    assertEquals(DecisionOutcome.FAILED, result.decision().outcome());
    assertEquals("EXECUTION_FAILED", result.decision().reasonCode());
    assertEquals(500, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(1, fixture.ledger.find("tenant-a", "order-1").version());
    assertEquals(1, fixture.payments.paymentCount());
  }

  private static final class Fixture {
    final PaymentSimulator payments = new PaymentSimulator();
    final RefundLedger ledger;
    final InMemoryApprovalService approvals = new InMemoryApprovalService(
        Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
        () -> UUID.randomUUID().toString(), new InvocationFingerprinter());
    final ResultDecisionPipeline pipeline;

    Fixture() {
      this(false);
    }

    Fixture(boolean changeDuringApproval) {
      var source = new JdbcDataSource();
      source.setURL("jdbc:h2:mem:refund-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      ledger = new RefundLedger(source, payments);
      ledger.initialize();
      ledger.createOrder(new OrderBalance("tenant-a", "order-1", 10_000, 0, 0));
      pipeline = new ResultDecisionPipeline(new ResultDecisionPipeline.Dependencies(
          invocation -> new GateDecision(true, "VALID"),
          invocation -> invocation,
          invocation -> new GateDecision(true, "ALLOWED"),
          invocation -> new RiskAssessment(RiskLevel.HIGH, "REFUND_REQUIRES_REVIEW"),
          (requestId, invocation) -> {
            var decision = approvals.verify(requestId, invocation);
            if (changeDuringApproval && decision.permitted()) {
              ledger.refund(new RefundCommand("tenant-a", "order-1", 500, 0));
            }
            return decision;
          }, new InMemoryResultIdempotencyGuard(new InvocationFingerprinter()),
          invocation -> ledger.refund(new RefundCommand(
              invocation.context().tenantId(), invocation.resource().identifier(),
              Long.parseLong(invocation.arguments().get("amountCents")),
              Long.parseLong(invocation.arguments().get("expectedVersion")))),
          new InMemoryAuditLog()));
    }

    ToolInvocation invocation(long amountCents, long version) {
      return new ToolInvocation(
          new ToolDescriptor("orders.refund", ToolEffect.WRITE,
              Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED),
          new Principal("requester", Map.of()), new Action("orders.refund"),
          new Resource("order", "order-1", Map.of()),
          new InvocationContext("tenant-a", "test"),
          Map.of("amountCents", Long.toString(amountCents),
              "expectedVersion", Long.toString(version)));
    }
  }
}

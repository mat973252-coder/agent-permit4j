package io.github.agentpermit4j.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.execution.ExecutionStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RefundReconciliationAcceptanceTest {
  private static final Principal OWNER = new Principal("operator-a", Map.of());
  private static final InvocationContext CONTEXT = new InvocationContext("tenant-a", "demo");
  private static final RefundExpectation EXPECTED = new RefundExpectation(2_500, 0, "refund-v1");

  @Test
  void lostResponseRemainsUnknownUntilAuthoritativeQuerySettlesTheOrderOnce() throws Exception {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    assertEquals(ExecutionStatus.NOT_STARTED, fixture.inspect(reference));
    fixture.payments.loseNextResponse();
    var outcome = fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT);
    assertEquals(ExecutionStatus.UNKNOWN, outcome.status());
    assertEquals(reference, outcome.reference());
    assertEquals(1, fixture.payments.paymentCount());
    assertEquals(0, fixture.balance().refundedCents());
    assertEquals(outcome, fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT));
    assertEquals(1, fixture.payments.requestCount());

    try (var executor = Executors.newFixedThreadPool(8)) {
      var calls = IntStream.range(0, 8).mapToObj(ignored -> (Callable<ExecutionStatus>) () ->
          fixture.service.reconcile(reference, OWNER, CONTEXT).orElseThrow().status()).toList();
      for (var result : executor.invokeAll(calls)) {
        assertEquals(ExecutionStatus.SUCCEEDED, result.get(5, TimeUnit.SECONDS));
      }
    }
    assertEquals(2_500, fixture.balance().refundedCents());
    assertEquals(1, fixture.balance().version());
    assertEquals(1, fixture.payments.paymentCount());
    assertEquals(1, fixture.payments.requestCount());
  }

  @Test
  void confirmedRejectionReleasesReservationWithoutPaymentOrRetry() {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    fixture.payments.rejectNextPayment();
    var outcome = fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT);
    assertEquals(ExecutionStatus.FAILED, outcome.status());
    assertEquals("REFUND_PAYMENT_REJECTED", outcome.reasonCode());
    assertEquals(outcome, fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT));
    assertEquals(outcome, fixture.service.reconcile(reference, OWNER, CONTEXT).orElseThrow());
    assertEquals(0, fixture.payments.paymentCount());
    assertEquals(1, fixture.payments.requestCount());
    assertEquals(0, fixture.balance().refundedCents());
    var replacement = fixture.prepare();
    assertEquals(ExecutionStatus.SUCCEEDED,
        fixture.service.execute(replacement, EXPECTED, OWNER, CONTEXT).status());
    assertEquals(1, fixture.payments.paymentCount());
  }

  @Test
  void restartRetainsUnknownReservationAndNeverResendsAnUncertainPayment() {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    fixture.payments.loseNextResponse();
    fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT);
    var restored = new RefundOperationService(fixture.source, fixture.payments);
    assertEquals(ExecutionStatus.UNKNOWN, restored.inspect(reference, OWNER, CONTEXT).orElseThrow().status());
    assertEquals(ExecutionStatus.UNKNOWN, restored.execute(reference, EXPECTED, OWNER, CONTEXT).status());
    var competing = fixture.prepare();
    assertEquals(ExecutionStatus.FAILED, restored.execute(competing, EXPECTED, OWNER, CONTEXT).status());
    assertEquals(1, fixture.payments.requestCount());
    assertEquals(ExecutionStatus.SUCCEEDED, restored.reconcile(reference, OWNER, CONTEXT).orElseThrow().status());
    assertEquals(2_500, fixture.balance().refundedCents());
  }

  @Test
  void missingOrUnavailableDownstreamEvidenceKeepsTheOperationFenced() {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    fixture.payments.loseNextResponse();
    fixture.service.execute(reference, EXPECTED, OWNER, CONTEXT);
    var missing = new RefundOperationService(fixture.source, fixture.payments, ignored -> java.util.Optional.empty());
    var unavailable = new RefundOperationService(fixture.source, fixture.payments,
        ignored -> { throw new IllegalStateException("synthetic secret must not escape"); });
    assertEquals(ExecutionStatus.UNKNOWN, missing.reconcile(reference, OWNER, CONTEXT).orElseThrow().status());
    var result = unavailable.reconcile(reference, OWNER, CONTEXT).orElseThrow();
    assertEquals(ExecutionStatus.UNKNOWN, result.status());
    assertFalse(result.toString().contains("synthetic secret"));
    assertEquals(0, fixture.balance().refundedCents());
    assertEquals(1, fixture.payments.requestCount());
  }

  @Test
  void referenceAloneCannotExposeOrReconcileAnotherOwnerTenantOrEnvironment() {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    assertTrue(fixture.service.inspect(reference, new Principal("other-user", Map.of()), CONTEXT).isEmpty());
    assertTrue(fixture.service.reconcile(reference, OWNER,
        new InvocationContext("other-tenant", "demo")).isEmpty());
    assertTrue(fixture.service.reconcile(reference, OWNER,
        new InvocationContext("tenant-a", "production")).isEmpty());
    assertEquals(0, fixture.payments.requestCount());
    assertEquals(0, fixture.payments.queryCount());
  }

  @Test
  void independentConcurrentServiceInstancesStartOnlyOnePayment() throws Exception {
    var fixture = new Fixture();
    var reference = fixture.prepare();
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var results = IntStream.range(0, 8).mapToObj(ignored -> executor.submit((Callable<ExecutionStatus>) () -> {
        var service = new RefundOperationService(fixture.source, fixture.payments.reconnect());
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return service.execute(reference, EXPECTED, OWNER, CONTEXT).status();
      })).toList();
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (var result : results) {
        var status = result.get(5, TimeUnit.SECONDS);
        assertTrue(status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.UNKNOWN);
      }
    }
    assertEquals(ExecutionStatus.SUCCEEDED, fixture.inspect(reference));
    assertEquals(1, fixture.payments.requestCount());
    assertEquals(1, fixture.payments.paymentCount());
    assertEquals(2_500, fixture.balance().refundedCents());
  }

  private static final class Fixture {
    final JdbcDataSource source = new JdbcDataSource();
    final PaymentSimulator payments = new PaymentSimulator();
    final RefundLedger ledger;
    final RefundOperationService service;

    Fixture() {
      source.setURL("jdbc:h2:mem:reconcile-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      ledger = new RefundLedger(source, payments);
      ledger.initialize();
      ledger.createOrder(new OrderBalance("tenant-a", "order-1", 10_000, 0, 0));
      service = new RefundOperationService(source, payments);
    }

    String prepare() {
      return service.prepare(new RefundCommand("tenant-a", "order-1", 2_500, 0),
          OWNER, CONTEXT, "refund-v1").reference();
    }

    ExecutionStatus inspect(String reference) {
      return service.inspect(reference, OWNER, CONTEXT).orElseThrow().status();
    }

    OrderBalance balance() {
      return ledger.find("tenant-a", "order-1");
    }
  }
}

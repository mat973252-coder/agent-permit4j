package io.github.mat973252.agentpermit.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.mat973252.agentpermit.core.InvocationContext;
import io.github.mat973252.agentpermit.core.Principal;
import io.github.mat973252.agentpermit.execution.ExecutionStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundReconciliationFaultTest {
  private static final Principal OWNER = new Principal("operator-a", Map.of());
  private static final InvocationContext CONTEXT = new InvocationContext("tenant-a", "demo");
  private static final RefundCommand COMMAND = new RefundCommand("tenant-a", "order-1", 2_500, 0);
  private static final RefundExpectation EXPECTED = new RefundExpectation(2_500, 0, "refund-v1");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void paymentSurvivesLostSettlementCommitAndRecoveryUpdatesTheLedgerOnlyOnce(boolean afterCommit) {
    var fixture = new Fixture();
    var faulty = new RefundOperationService(new CommitFailureDataSource(fixture.source, 2, afterCommit), fixture.payments);
    assertEquals(ExecutionStatus.UNKNOWN, faulty.execute(fixture.reference, EXPECTED, OWNER, CONTEXT).status());
    assertEquals(1, fixture.payments.paymentCount());
    assertEquals(afterCommit ? 2_500 : 0, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    var restored = new RefundOperationService(fixture.source, fixture.payments.reconnect());
    assertEquals(ExecutionStatus.SUCCEEDED, restored.reconcile(fixture.reference, OWNER, CONTEXT).orElseThrow().status());
    assertEquals(ExecutionStatus.SUCCEEDED, restored.reconcile(fixture.reference, OWNER, CONTEXT).orElseThrow().status());
    assertEquals(2_500, fixture.ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(1, fixture.ledger.find("tenant-a", "order-1").version());
    assertEquals(1, fixture.payments.requestCount());
  }

  @Test
  void lostReservationAcknowledgmentDoesNotPermitPaymentOrReleaseWithoutEvidence() {
    var fixture = new Fixture();
    var faulty = new RefundOperationService(new CommitFailureDataSource(fixture.source, 1, true), fixture.payments);
    assertThrows(IllegalStateException.class, () -> faulty.execute(fixture.reference, EXPECTED, OWNER, CONTEXT));
    assertEquals(0, fixture.payments.requestCount());
    var restored = new RefundOperationService(fixture.source, fixture.payments.reconnect());
    assertEquals(ExecutionStatus.UNKNOWN, restored.execute(fixture.reference, EXPECTED, OWNER, CONTEXT).status());
    assertEquals(ExecutionStatus.UNKNOWN, restored.reconcile(fixture.reference, OWNER, CONTEXT).orElseThrow().status());
    assertThrows(IllegalStateException.class, () -> fixture.ledger.refund(COMMAND));
    assertEquals(0, fixture.payments.requestCount());
    assertEquals(0, fixture.ledger.find("tenant-a", "order-1").refundedCents());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mismatchedPaymentEvidenceCannotResolveOrReleaseAnUnknownOperation(boolean otherReference) {
    var fixture = new Fixture();
    fixture.payments.loseNextResponse();
    fixture.service.execute(fixture.reference, EXPECTED, OWNER, CONTEXT);
    PaymentQuery wrong = reference -> Optional.of(new PaymentReceipt(
        otherReference ? "other-reference" : reference,
        otherReference ? COMMAND : new RefundCommand("other-tenant", "order-1", 2_500, 0), ExecutionStatus.SUCCEEDED));
    var service = new RefundOperationService(fixture.source, fixture.payments, wrong);
    assertEquals(ExecutionStatus.UNKNOWN, service.reconcile(fixture.reference, OWNER, CONTEXT).orElseThrow().status());
    assertEquals(ExecutionStatus.UNKNOWN, fixture.service.inspect(fixture.reference, OWNER, CONTEXT).orElseThrow().status());
    assertThrows(IllegalStateException.class, () -> fixture.ledger.refund(COMMAND));
    assertEquals(1, fixture.payments.requestCount());
    assertEquals(0, fixture.ledger.find("tenant-a", "order-1").refundedCents());
  }

  private static final class Fixture {
    final JdbcDataSource source = new JdbcDataSource();
    final PaymentSimulator payments = new PaymentSimulator();
    final RefundLedger ledger;
    final RefundOperationService service;
    final String reference;

    Fixture() {
      source.setURL("jdbc:h2:mem:reconcile-fault-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      ledger = new RefundLedger(source, payments);
      ledger.initialize();
      ledger.createOrder(new OrderBalance("tenant-a", "order-1", 10_000, 0, 0));
      service = new RefundOperationService(source, payments);
      reference = service.prepare(COMMAND, OWNER, CONTEXT, "refund-v1").reference();
    }
  }
}

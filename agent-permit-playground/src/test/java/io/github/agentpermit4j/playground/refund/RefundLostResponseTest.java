package io.github.agentpermit4j.playground.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RefundLostResponseTest {
  @Test
  void successfulPaymentSurvivesLostResponseAndLocalRollback() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:lost-response-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    var payments = new PaymentSimulator();
    var ledger = new RefundLedger(source, payments);
    ledger.initialize();
    ledger.createOrder(new OrderBalance("tenant-a", "order-1", 10_000, 0, 0));
    payments.loseNextResponse();

    assertThrows(IllegalStateException.class,
        () -> ledger.refund(new RefundCommand("tenant-a", "order-1", 2_500, 0)));
    assertEquals(1, payments.paymentCount());
    assertEquals(0, ledger.find("tenant-a", "order-1").refundedCents());
    assertEquals(0, ledger.find("tenant-a", "order-1").version());
  }
}

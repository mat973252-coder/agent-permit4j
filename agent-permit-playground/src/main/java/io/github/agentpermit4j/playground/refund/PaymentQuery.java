package io.github.agentpermit4j.playground.refund;

import java.util.Optional;

/** Read-only downstream port; absence is inconclusive and must not authorize another payment. */
@FunctionalInterface
public interface PaymentQuery {
  Optional<PaymentReceipt> lookup(String reference);
}

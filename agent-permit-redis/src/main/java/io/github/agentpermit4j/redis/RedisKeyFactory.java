package io.github.agentpermit4j.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class RedisKeyFactory {

  private final String prefix;

  RedisKeyFactory(String prefix) {
    this.prefix = prefix + "{execution}:";
  }

  Keys keys(String idempotencyKey, String approvalRequestId) {
    var idempotencyDigest = digest(idempotencyKey);
    var approvalKey =
        approvalRequestId == null ? null : prefix + "approval:" + digest(approvalRequestId);
    return new Keys(
        prefix + "idempotency:" + idempotencyDigest, approvalKey, idempotencyDigest);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  record Keys(String entryKey, String approvalKey, String idempotencyDigest) {}
}

package io.github.mat973252.agentpermit.redis;

import java.time.Duration;
import java.util.Objects;

public record RedisIdempotencyConfig(
    String keyPrefix, Duration ownerLease, Duration pollInterval) {

  public RedisIdempotencyConfig {
    Objects.requireNonNull(keyPrefix, "keyPrefix");
    ownerLease = positive(ownerLease, "ownerLease");
    pollInterval = positive(pollInterval, "pollInterval");
    if (keyPrefix.isBlank() || keyPrefix.contains("{") || keyPrefix.contains("}")) {
      throw new IllegalArgumentException("keyPrefix must be non-blank and contain no hash tag");
    }
    if (pollInterval.compareTo(ownerLease) > 0) {
      throw new IllegalArgumentException("pollInterval must not exceed ownerLease");
    }
  }

  private static Duration positive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative() || duration.isZero() || duration.toMillis() == 0) {
      throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
    return duration;
  }
}

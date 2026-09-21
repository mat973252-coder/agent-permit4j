package io.github.agentpermit4j.policy.messaging;

import java.util.Objects;
import java.util.Set;

public record MessagingRiskPolicy(Set<String> allowedDestinations, int maxBodyBytes) {

  public MessagingRiskPolicy {
    Objects.requireNonNull(allowedDestinations, "allowedDestinations");
    if (allowedDestinations.isEmpty()) {
      throw new IllegalArgumentException("allowedDestinations must not be empty");
    }
    if (allowedDestinations.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("allowedDestinations must not contain blank values");
    }
    if (maxBodyBytes < 0) {
      throw new IllegalArgumentException("maxBodyBytes must not be negative");
    }
    allowedDestinations = Set.copyOf(allowedDestinations);
  }

  boolean allowsDestination(String destination) {
    return allowedDestinations.contains(destination);
  }
}

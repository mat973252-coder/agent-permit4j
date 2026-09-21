package io.github.agentpermit4j.policy.http;

import java.net.IDN;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record HttpRiskPolicy(Set<String> allowedHosts, int maxPayloadBytes) {

  public HttpRiskPolicy {
    Objects.requireNonNull(allowedHosts, "allowedHosts");
    if (allowedHosts.isEmpty()) {
      throw new IllegalArgumentException("allowedHosts must not be empty");
    }
    if (maxPayloadBytes < 0) {
      throw new IllegalArgumentException("maxPayloadBytes must not be negative");
    }
    allowedHosts =
        allowedHosts.stream()
            .map(HttpRiskPolicy::normalizeAllowedHost)
            .collect(Collectors.toUnmodifiableSet());
  }

  boolean allowsHost(String host) {
    return allowedHosts.contains(normalizeHost(host));
  }

  static String normalizeHost(String host) {
    Objects.requireNonNull(host, "host");
    var value = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    if (value.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
  }

  static boolean isSsrfTarget(String host) {
    return host.equalsIgnoreCase("localhost")
        || host.toLowerCase(Locale.ROOT).endsWith(".localhost")
        || host.contains(":")
        || host.matches("[0-9.]+");
  }

  private static String normalizeAllowedHost(String host) {
    var normalized = normalizeHost(host);
    if (isSsrfTarget(normalized)) {
      throw new IllegalArgumentException("allowedHosts must contain external domain names");
    }
    return normalized;
  }
}

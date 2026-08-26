package io.github.mat973252.agentpermit.approval;

import java.util.Objects;
import java.util.regex.Pattern;

public record InvocationFingerprint(String value) {

  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public InvocationFingerprint {
    Objects.requireNonNull(value, "value");
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException("value must be a lowercase SHA-256 digest");
    }
  }
}

package io.github.agentpermit4j.core;

import java.util.Map;
import java.util.Objects;

public record Principal(String id, Map<String, String> attributes) {

  public Principal {
    id = DomainValidation.requireText(id, "id");
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
  }
}

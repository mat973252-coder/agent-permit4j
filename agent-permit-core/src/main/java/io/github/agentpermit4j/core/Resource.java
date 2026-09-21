package io.github.agentpermit4j.core;

import java.util.Map;
import java.util.Objects;

public record Resource(String type, String identifier, Map<String, String> attributes) {

  public Resource {
    type = DomainValidation.requireText(type, "type");
    identifier = DomainValidation.requireText(identifier, "identifier");
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
  }
}

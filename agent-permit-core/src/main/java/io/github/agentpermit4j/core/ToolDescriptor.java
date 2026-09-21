package io.github.agentpermit4j.core;

import java.util.Objects;

public record ToolDescriptor(
    String name,
    ToolEffect effect,
    Reversibility reversibility,
    DataSensitivity dataSensitivity) {

  public ToolDescriptor {
    name = DomainValidation.requireText(name, "name");
    effect = Objects.requireNonNull(effect, "effect");
    reversibility = Objects.requireNonNull(reversibility, "reversibility");
    dataSensitivity = Objects.requireNonNull(dataSensitivity, "dataSensitivity");
  }
}

package io.github.mat973252.agentpermit.core;

public record ToolDescriptor(String name) {

  public ToolDescriptor {
    name = DomainValidation.requireText(name, "name");
  }
}

package io.github.agentpermit4j.core;

public record Action(String name) {

  public Action {
    name = DomainValidation.requireText(name, "name");
  }
}

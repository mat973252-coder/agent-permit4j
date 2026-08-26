package io.github.mat973252.agentpermit.core;

public record Action(String name) {

  public Action {
    name = DomainValidation.requireText(name, "name");
  }
}

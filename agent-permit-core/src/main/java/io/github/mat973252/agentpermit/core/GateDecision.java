package io.github.mat973252.agentpermit.core;

public record GateDecision(boolean permitted, String reasonCode) {

  public GateDecision {
    reasonCode = DomainValidation.requireReasonCode(reasonCode);
  }
}

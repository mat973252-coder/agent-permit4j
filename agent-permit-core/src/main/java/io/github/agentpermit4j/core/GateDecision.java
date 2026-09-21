package io.github.agentpermit4j.core;

public record GateDecision(boolean permitted, String reasonCode) {

  public GateDecision {
    reasonCode = DomainValidation.requireReasonCode(reasonCode);
  }
}

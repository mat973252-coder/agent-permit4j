package io.github.agentpermit4j.policy.messaging;

import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.policy.RiskEvaluator;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class MessagingRiskEvaluator implements RiskEvaluator {

  private static final String BODY_ARGUMENT = "body";
  private final MessagingRiskPolicy policy;

  public MessagingRiskEvaluator(MessagingRiskPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public RiskAssessment evaluate(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    if (!"messaging".equals(invocation.resource().type())) {
      return denied("MESSAGING_RESOURCE_REQUIRED");
    }
    if (!"message.send".equals(invocation.action().name())) {
      return denied("MESSAGING_SEND_ACTION_REQUIRED");
    }
    if (!policy.allowsDestination(invocation.resource().identifier())) {
      return denied("MESSAGING_DESTINATION_NOT_ALLOWED");
    }

    var body = invocation.arguments().get(BODY_ARGUMENT);
    if (body == null || body.isBlank()) {
      return denied("MESSAGING_BODY_REQUIRED");
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > policy.maxBodyBytes()) {
      return denied("MESSAGING_BODY_TOO_LARGE");
    }
    return new RiskAssessment(RiskLevel.HIGH, "MESSAGING_SEND");
  }

  private static RiskAssessment denied(String reasonCode) {
    return new RiskAssessment(RiskLevel.DENY, reasonCode);
  }
}

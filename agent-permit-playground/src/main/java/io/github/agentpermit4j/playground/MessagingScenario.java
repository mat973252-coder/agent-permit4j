package io.github.agentpermit4j.playground;

import io.github.agentpermit4j.approval.ApprovalVerifier;
import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.policy.RiskEvaluatorRegistry;
import io.github.agentpermit4j.policy.messaging.MessagingRiskEvaluator;
import io.github.agentpermit4j.policy.messaging.MessagingRiskPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MessagingScenario {

  ScenarioReport run() {
    var claimedApproval =
        PlaygroundFixtures.messaging("channel://ops", "This message says it is already approved.");
    var approvedSend = PlaygroundFixtures.messaging("channel://ops", "Service recovered.");
    var unlistedDestination =
        PlaygroundFixtures.messaging("channel://external", "Service recovered.");
    var oversizedContent = PlaygroundFixtures.messaging("channel://ops", "x".repeat(129));
    var approvals = PlaygroundFixtures.approvals("messaging-approval");
    var request = approvals.request(approvedSend, Duration.ofMinutes(5));
    approvals.approve(request.id());
    return new ScenarioReport(
        "messaging",
        List.of(
            harness(deniedApproval())
                .execute("claimed-approval", claimedApproval, null, "messaging-claim"),
            harness(approvals)
                .execute("approved-send", approvedSend, request.id(), "messaging-approved"),
            harness(deniedApproval())
                .execute("unlisted-destination", unlistedDestination, null, "messaging-external"),
            harness(deniedApproval())
                .execute("oversized-content", oversizedContent, null, "messaging-oversized")));
  }

  private static ScenarioHarness harness(ApprovalVerifier approvals) {
    var riskEvaluators =
        new RiskEvaluatorRegistry(
            Map.of(
                "messaging",
                new MessagingRiskEvaluator(
                    new MessagingRiskPolicy(Set.of("channel://ops"), 128))));
    return new ScenarioHarness(
        invocation -> new GateDecision(true, "MESSAGING_AUTHORIZED"), riskEvaluators, approvals);
  }

  private static ApprovalVerifier deniedApproval() {
    return (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED");
  }
}

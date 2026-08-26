package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import java.time.Duration;
import java.util.List;

final class DeploymentScenario {

  ScenarioReport run() {
    var staging = PlaygroundFixtures.deployment("staging");
    var production = PlaygroundFixtures.deployment("production");
    var approvals = PlaygroundFixtures.approvals("deployment-approval");
    var request = approvals.request(production, Duration.ofMinutes(5));
    approvals.approve(request.id());
    return new ScenarioReport(
        "deployment",
        List.of(
            harness(deniedApproval()).execute("staging", staging, null, "deploy-staging"),
            harness(deniedApproval())
                .execute(
                    "production-awaiting-approval",
                    production,
                    null,
                    "deploy-production-pending"),
            harness(approvals)
                .execute(
                    "production-approved",
                    production,
                    request.id(),
                    "deploy-production")));
  }

  private static ScenarioHarness harness(ApprovalVerifier approvals) {
    return new ScenarioHarness(
        invocation -> new GateDecision(true, "DEPLOYMENT_AUTHORIZED"),
        invocation ->
            "production".equals(invocation.context().environment())
                ? new RiskAssessment(RiskLevel.HIGH, "DEPLOYMENT_PRODUCTION")
                : new RiskAssessment(RiskLevel.LOW, "DEPLOYMENT_STAGING"),
        approvals);
  }

  private static ApprovalVerifier deniedApproval() {
    return (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED");
  }
}

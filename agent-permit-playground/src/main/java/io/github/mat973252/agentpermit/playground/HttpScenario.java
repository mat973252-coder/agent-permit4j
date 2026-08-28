package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.policy.RiskEvaluatorRegistry;
import io.github.mat973252.agentpermit.policy.http.HttpRiskEvaluator;
import io.github.mat973252.agentpermit.policy.http.HttpRiskPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HttpScenario {

  ScenarioReport run() {
    var read = PlaygroundFixtures.http("GET", "https://api.example.com/v1/orders/7", "");
    var write = PlaygroundFixtures.http("POST", "https://api.example.com/v1/orders", "{}");
    var ssrf = PlaygroundFixtures.http("GET", "https://127.0.0.1/admin", "");
    var approvals = PlaygroundFixtures.approvals("http-approval");
    var request = approvals.request(write, Duration.ofMinutes(5));
    approvals.approve(request.id());
    return new ScenarioReport(
        "http",
        List.of(
            harness(deniedApproval()).execute("read", read, null, "http-read"),
            harness(deniedApproval())
                .execute("write-awaiting-approval", write, null, "http-write-pending"),
            harness(approvals).execute("write-approved", write, request.id(), "http-write"),
            harness(deniedApproval()).execute("ssrf", ssrf, null, "http-ssrf")));
  }

  private static ScenarioHarness harness(ApprovalVerifier approvals) {
    var riskEvaluators =
        new RiskEvaluatorRegistry(
            Map.of(
                "http",
                    new HttpRiskEvaluator(new HttpRiskPolicy(Set.of("api.example.com"), 1024))));
    return new ScenarioHarness(
        invocation -> new GateDecision(true, "HTTP_AUTHORIZED"), riskEvaluators, approvals);
  }

  private static ApprovalVerifier deniedApproval() {
    return (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED");
  }
}

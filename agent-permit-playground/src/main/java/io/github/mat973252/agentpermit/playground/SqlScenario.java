package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.approval.ApprovalVerifier;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.policy.RiskEvaluatorRegistry;
import io.github.mat973252.agentpermit.policy.sql.SqlRiskEvaluator;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class SqlScenario {

  ScenarioReport run() {
    var read = PlaygroundFixtures.sql("SELECT id FROM users WHERE id = 1");
    var write = PlaygroundFixtures.sql("UPDATE users SET active = 1 WHERE id = 7");
    var approvals = PlaygroundFixtures.approvals("sql-approval");
    var request = approvals.request(write, Duration.ofMinutes(5));
    approvals.approve(request.id());
    return new ScenarioReport(
        "sql",
        List.of(
            harness(deniedApproval()).execute("read", read, null, "sql-read"),
            harness(deniedApproval())
                .execute("write-awaiting-approval", write, null, "sql-write-pending"),
            harness(approvals).execute("write-approved", write, request.id(), "sql-write")));
  }

  private static ScenarioHarness harness(ApprovalVerifier approvals) {
    return new ScenarioHarness(
        invocation -> new GateDecision(true, "SQL_AUTHORIZED"),
        new RiskEvaluatorRegistry(Map.of("sql", new SqlRiskEvaluator())),
        approvals);
  }

  private static ApprovalVerifier deniedApproval() {
    return (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED");
  }
}

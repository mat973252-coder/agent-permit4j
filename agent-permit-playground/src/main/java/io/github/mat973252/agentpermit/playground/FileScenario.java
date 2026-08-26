package io.github.mat973252.agentpermit.playground;

import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.policy.file.ProtectedPathAuthorizer;
import java.util.List;
import java.util.Map;

final class FileScenario {

  ScenarioReport run() {
    var read = PlaygroundFixtures.file("file.read", "/workspace/README.md", Map.of());
    var delete =
        PlaygroundFixtures.file(
            "file.delete", "/workspace", Map.of("recursive", "true"));
    return new ScenarioReport(
        "file",
        List.of(
            harness().execute("read", read, null, "file-read"),
            harness().execute("protected-delete", delete, null, "file-delete")));
  }

  private static ScenarioHarness harness() {
    return new ScenarioHarness(
        new ProtectedPathAuthorizer(),
        invocation -> new RiskAssessment(RiskLevel.LOW, "FILE_READ"),
        (requestId, invocation) -> new GateDecision(false, "APPROVAL_REQUIRED"));
  }
}

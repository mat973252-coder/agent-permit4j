package io.github.agentpermit4j.playground;

import io.github.agentpermit4j.core.GateDecision;
import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.policy.file.ProtectedPathAuthorizer;
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

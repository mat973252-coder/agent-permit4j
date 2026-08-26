package io.github.mat973252.agentpermit.playground;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.audit.AuditStage;
import io.github.mat973252.agentpermit.core.DecisionOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaygroundAcceptanceTest {

  @Test
  void runsThreeRealPipelineScenariosWithExpectedSideEffects() {
    var reports = new PlaygroundRunner().run();

    assertAll(
        () -> assertEquals(List.of("file", "sql", "deployment"), names(reports)),
        () ->
            assertCase(
                reports,
                "file",
                new ExpectedCase("read", DecisionOutcome.EXECUTED, "FILE_READ", 1)),
        () ->
            assertCase(
                reports,
                "file",
                new ExpectedCase(
                    "protected-delete", DecisionOutcome.DENIED, "PROTECTED_PATH", 0)),
        () ->
            assertCase(
                reports,
                "sql",
                new ExpectedCase("read", DecisionOutcome.EXECUTED, "SQL_READ_ONLY", 1)),
        () ->
            assertCase(
                reports,
                "sql",
                new ExpectedCase(
                    "write-awaiting-approval",
                    DecisionOutcome.APPROVAL_REQUIRED,
                    "SQL_SELECTIVE_UPDATE",
                    0)),
        () ->
            assertCase(
                reports,
                "sql",
                new ExpectedCase(
                    "write-approved", DecisionOutcome.EXECUTED, "SQL_SELECTIVE_UPDATE", 1)),
        () ->
            assertCase(
                reports,
                "deployment",
                new ExpectedCase(
                    "staging", DecisionOutcome.EXECUTED, "DEPLOYMENT_STAGING", 1)),
        () ->
            assertCase(
                reports,
                "deployment",
                new ExpectedCase(
                    "production-awaiting-approval",
                    DecisionOutcome.APPROVAL_REQUIRED,
                    "DEPLOYMENT_PRODUCTION",
                    0)),
        () ->
            assertCase(
                reports,
                "deployment",
                new ExpectedCase(
                    "production-approved",
                    DecisionOutcome.EXECUTED,
                    "DEPLOYMENT_PRODUCTION",
                    1)));
  }

  @Test
  void cliPrintsStructuredResultsAndReturnsSuccess() {
    var bytes = new ByteArrayOutputStream();
    var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    var exitCode = PlaygroundApplication.run(output);
    var text = bytes.toString(StandardCharsets.UTF_8);

    assertAll(
        () -> assertEquals(0, exitCode),
        () -> assertTrue(text.contains("SCENARIO file")),
        () -> assertTrue(text.contains("SCENARIO sql")),
        () -> assertTrue(text.contains("SCENARIO deployment")),
        () -> assertTrue(text.contains("outcome=APPROVAL_REQUIRED")),
        () -> assertTrue(text.contains("timeline=POLICY>RISK>APPROVAL>EXECUTION>RESULT")));
  }

  private static List<String> names(List<ScenarioReport> reports) {
    return reports.stream().map(ScenarioReport::name).toList();
  }

  private static void assertCase(
      List<ScenarioReport> reports, String scenario, ExpectedCase expected) {
    var report = reports.stream().filter(value -> value.name().equals(scenario)).findFirst().orElseThrow();
    var result =
        report.cases().stream()
            .filter(value -> value.name().equals(expected.name()))
            .findFirst()
            .orElseThrow();
    assertAll(
        () -> assertEquals(expected.outcome(), result.decision().outcome()),
        () -> assertEquals(expected.reasonCode(), result.decision().reasonCode()),
        () -> assertEquals(expected.sideEffects(), result.sideEffectCount()),
        () -> assertTimeline(expected.outcome(), result.timeline()));
  }

  private static void assertTimeline(DecisionOutcome outcome, List<AuditStage> timeline) {
    if (outcome == DecisionOutcome.DENIED) {
      assertEquals(List.of(AuditStage.POLICY, AuditStage.RESULT), timeline);
      return;
    }
    if (outcome == DecisionOutcome.APPROVAL_REQUIRED) {
      assertEquals(
          List.of(AuditStage.POLICY, AuditStage.RISK, AuditStage.APPROVAL, AuditStage.RESULT),
          timeline);
      return;
    }
    assertEquals(
        List.of(
            AuditStage.POLICY,
            AuditStage.RISK,
            AuditStage.APPROVAL,
            AuditStage.EXECUTION,
            AuditStage.RESULT),
        timeline);
  }

  private record ExpectedCase(
      String name, DecisionOutcome outcome, String reasonCode, int sideEffects) {}
}

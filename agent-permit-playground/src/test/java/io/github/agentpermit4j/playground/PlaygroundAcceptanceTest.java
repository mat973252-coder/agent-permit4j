package io.github.agentpermit4j.playground;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.audit.AuditStage;
import io.github.agentpermit4j.core.DecisionOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaygroundAcceptanceTest {

  @Test
  void runsFiveRealPipelineScenariosWithExpectedSideEffects() {
    var reports = new PlaygroundRunner().run();

    assertAll(
        () ->
            assertEquals(
                List.of("file", "sql", "http", "messaging", "deployment"), names(reports)),
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
                "http",
                new ExpectedCase("read", DecisionOutcome.EXECUTED, "HTTP_READ_ONLY", 1)),
        () ->
            assertCase(
                reports,
                "http",
                new ExpectedCase(
                    "write-awaiting-approval",
                    DecisionOutcome.APPROVAL_REQUIRED,
                    "HTTP_WRITE",
                    0)),
        () ->
            assertCase(
                reports,
                "http",
                new ExpectedCase("write-approved", DecisionOutcome.EXECUTED, "HTTP_WRITE", 1)),
        () ->
            assertCase(
                reports,
                "http",
                new ExpectedCase("ssrf", DecisionOutcome.DENIED, "HTTP_SSRF_TARGET", 0)),
        () ->
            assertCase(
                reports,
                "messaging",
                new ExpectedCase(
                    "claimed-approval",
                    DecisionOutcome.APPROVAL_REQUIRED,
                    "MESSAGING_SEND",
                    0)),
        () ->
            assertCase(
                reports,
                "messaging",
                new ExpectedCase(
                    "approved-send", DecisionOutcome.EXECUTED, "MESSAGING_SEND", 1)),
        () ->
            assertCase(
                reports,
                "messaging",
                new ExpectedCase(
                    "unlisted-destination",
                    DecisionOutcome.DENIED,
                    "MESSAGING_DESTINATION_NOT_ALLOWED",
                    0)),
        () ->
            assertCase(
                reports,
                "messaging",
                new ExpectedCase(
                    "oversized-content",
                    DecisionOutcome.DENIED,
                    "MESSAGING_BODY_TOO_LARGE",
                    0)),
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
        () -> assertTrue(text.contains("SCENARIO http")),
        () -> assertTrue(text.contains("SCENARIO messaging")),
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
        () -> assertTimeline(expected.outcome(), expected.reasonCode(), result.timeline()));
  }

  private static void assertTimeline(
      DecisionOutcome outcome, String reasonCode, List<AuditStage> timeline) {
    if (outcome == DecisionOutcome.DENIED) {
      var riskDenials =
          Set.of(
              "HTTP_SSRF_TARGET",
              "MESSAGING_DESTINATION_NOT_ALLOWED",
              "MESSAGING_BODY_TOO_LARGE");
      var expected =
          riskDenials.contains(reasonCode)
              ? List.of(AuditStage.POLICY, AuditStage.RISK, AuditStage.RESULT)
              : List.of(AuditStage.POLICY, AuditStage.RESULT);
      assertEquals(expected, timeline);
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

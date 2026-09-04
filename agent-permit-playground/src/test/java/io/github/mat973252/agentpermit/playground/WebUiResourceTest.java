package io.github.mat973252.agentpermit.playground;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebUiResourceTest {

  @Test
  void webUiExposesTheRequiredWorkingSurface() {
    var html = resource("webui/index.html");
    var css = resource("webui/styles.css");
    var script = resource("webui/app.js");

    assertAll(
        () -> assertTrue(html.contains("id=\"case-select\"")),
        () -> assertTrue(html.contains("id=\"conversation\"")),
        () -> assertTrue(html.contains("id=\"run-status\"")),
        () -> assertTrue(html.contains("id=\"run-id\"")),
        () -> assertTrue(html.contains("id=\"approval-checkpoint\"")),
        () -> assertTrue(html.contains("id=\"run-details\"")),
        () -> assertTrue(html.contains("class=\"terminal-shell\"")),
        () -> assertTrue(html.contains("class=\"terminal-titlebar\"")),
        () -> assertTrue(html.contains("class=\"session-line\"")),
        () -> assertTrue(html.contains("class=\"prompt-row\"")),
        () -> assertTrue(html.contains("<details")),
        () -> assertTrue(html.contains("真实决策管线 · Mock 副作用")),
        () -> assertFalse(html.contains("run-sidebar")),
        () -> assertFalse(html.contains("role=\"tablist\"")),
        () -> assertFalse(html.contains("decision-hero")),
        () -> assertTrue(css.contains("@media")),
        () -> assertTrue(css.contains("--terminal")),
        () -> assertTrue(css.contains(".terminal-shell")),
        () -> assertTrue(css.contains(".transcript")),
        () -> assertTrue(css.contains(".tool-table")),
        () -> assertTrue(css.contains(".approval-frame")),
        () -> assertTrue(css.contains(".prompt-row")),
        () -> assertTrue(css.contains(".tui-tool-call")),
        () -> assertTrue(css.contains(".tui-approval")),
        () -> assertFalse(css.contains(".panel")),
        () -> assertTrue(script.contains("/api/playground/cases")),
        () -> assertTrue(script.contains("/api/playground/decisions/")),
        () -> assertTrue(script.contains("/api/playground/approvals/")),
        () -> assertTrue(script.contains("/api/playground/audit/")),
        () -> assertTrue(script.contains("/api/playground/replay/")),
        () -> assertTrue(script.contains("APPROVAL_REQUIRED: {")),
        () -> assertTrue(script.contains("需要人工确认")),
        () -> assertTrue(script.contains("approvalRequestId")),
        () -> assertTrue(script.contains("renderTranscript")),
        () -> assertTrue(script.contains("renderToolInvocation")),
        () -> assertTrue(script.contains("renderApproval")),
        () -> assertTrue(script.contains("renderRunDetails")),
        () -> assertTrue(script.contains("runStatusCopy")),
        () -> assertTrue(script.contains("RESUMING")),
        () -> assertTrue(html.contains("Allow once")),
        () -> assertTrue(html.contains("View details")),
        () -> assertTrue(html.contains("PLAYGROUND_API_UNAVAILABLE")),
        () -> assertTrue(script.contains("Historical executor calls")),
        () -> assertFalse(script.contains("innerHTML")),
        () -> assertFalse(html.contains("https://")));
  }

  @Test
  void liveUiDoesNotReferenceSyntheticDecisionFixture() {
    assertFalse(resource("webui/app.js").contains("fixtures.json"));
  }

  @Test
  void approvalResponseCannotOverwriteANewerScenarioSelection() {
    var script = resource("webui/app.js");

    assertAll(
        () -> assertTrue(script.contains("const approvalSequence = selectionSequence")),
        () -> assertTrue(script.contains("const approvedCaseId = currentFixture.id")),
        () -> assertTrue(script.contains("currentFixture?.id !== approvedCaseId")),
        () -> assertTrue(script.contains("approvalSequence !== selectionSequence")),
        () -> assertTrue(script.contains("hidePendingApproval()")));
  }

  private static String resource(String path) {
    try (var stream = WebUiResourceTest.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        return fail("missing resource " + path);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      return fail("cannot read resource " + path, exception);
    }
  }
}

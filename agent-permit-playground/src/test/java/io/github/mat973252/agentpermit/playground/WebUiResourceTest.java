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
        () -> assertTrue(html.contains("id=\"timeline\"")),
        () -> assertTrue(html.contains("data-tab=\"tool-calls\"")),
        () -> assertTrue(html.contains("data-tab=\"approvals\"")),
        () -> assertTrue(html.contains("data-tab=\"audit\"")),
        () -> assertTrue(html.contains("data-tab=\"rag-trace\"")),
        () -> assertTrue(html.contains("data-tab=\"policies\"")),
        () -> assertTrue(html.contains("data-tab=\"replay\"")),
        () -> assertTrue(html.contains("aria-controls=\"inspector-content\"")),
        () -> assertTrue(html.contains("查看审批后结果")),
        () -> assertTrue(html.contains("RAG trace (fixture)")),
        () -> assertTrue(html.contains("Executor calls")),
        () -> assertFalse(html.contains("批准当前指纹")),
        () -> assertTrue(css.contains("@media")),
        () -> assertTrue(script.contains("fetch(\"fixtures.json\")")),
        () -> assertTrue(script.contains("approvedCaseId")),
        () -> assertTrue(script.contains("ArrowRight")),
        () -> assertTrue(script.contains("FIXTURE_LOAD_FAILED")),
        () -> assertTrue(script.contains("Historical executor calls")),
        () -> assertFalse(script.contains("innerHTML")),
        () -> assertFalse(html.contains("https://")));
  }

  @Test
  void fixtureDataCoversExecuteApprovalDenyAndReplayStates() {
    var fixture = resource("webui/fixtures.json");

    assertAll(
        () -> assertTrue(fixture.contains("\"id\": \"messaging-claimed\"")),
        () -> assertTrue(fixture.contains("\"id\": \"messaging-approved\"")),
        () -> assertTrue(fixture.contains("\"id\": \"http-ssrf-denied\"")),
        () -> assertTrue(fixture.contains("\"outcome\": \"APPROVAL_REQUIRED\"")),
        () -> assertTrue(fixture.contains("\"outcome\": \"EXECUTED\"")),
        () -> assertTrue(fixture.contains("\"outcome\": \"DENIED\"")),
        () -> assertTrue(fixture.contains("\"normalizedArguments\"")),
        () -> assertTrue(fixture.contains("\"fingerprint\"")),
        () -> assertTrue(fixture.contains("\"policyVersion\"")),
        () -> assertTrue(fixture.contains("\"sideEffectCount\"")),
        () -> assertTrue(fixture.contains("\"safe\": true")),
        () -> assertTrue(fixture.contains("\"executed\": false")),
        () -> assertTrue(fixture.contains("\"RAG_CROSS_TENANT_BLOCKED\"")));
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

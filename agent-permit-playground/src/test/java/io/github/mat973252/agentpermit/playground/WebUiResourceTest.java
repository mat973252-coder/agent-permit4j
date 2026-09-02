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
        () -> assertTrue(html.contains("id=\"decision-title\"")),
        () -> assertTrue(html.contains("id=\"decision-copy\"")),
        () -> assertTrue(html.contains("class=\"control-status\"")),
        () -> assertTrue(html.contains("本地实时演示")),
        () -> assertTrue(html.contains("data-tab=\"tool-calls\"")),
        () -> assertTrue(html.contains("data-tab=\"approvals\"")),
        () -> assertTrue(html.contains("data-tab=\"audit\"")),
        () -> assertTrue(html.contains("data-tab=\"rag-trace\"")),
        () -> assertTrue(html.contains("data-tab=\"policies\"")),
        () -> assertTrue(html.contains("data-tab=\"replay\"")),
        () -> assertTrue(html.contains("aria-controls=\"inspector-content\"")),
        () -> assertTrue(html.contains("批准并执行")),
        () -> assertTrue(html.contains("RAG trace (fixture)")),
        () -> assertTrue(html.contains("Executor calls")),
        () -> assertFalse(html.contains("批准当前指纹")),
        () -> assertTrue(css.contains("@media")),
        () -> assertTrue(css.contains("--canvas")),
        () -> assertTrue(script.contains("/api/playground/cases")),
        () -> assertTrue(script.contains("/api/playground/decisions/")),
        () -> assertTrue(script.contains("/api/playground/approvals/")),
        () -> assertTrue(script.contains("/api/playground/audit/")),
        () -> assertTrue(script.contains("/api/playground/replay/")),
        () -> assertTrue(script.contains("APPROVAL_REQUIRED: {")),
        () -> assertTrue(script.contains("需要人工确认")),
        () -> assertTrue(script.contains("approvalRequestId")),
        () -> assertTrue(script.contains("ArrowRight")),
        () -> assertTrue(script.contains("PLAYGROUND_API_UNAVAILABLE")),
        () -> assertTrue(script.contains("Historical executor calls")),
        () -> assertFalse(script.contains("innerHTML")),
        () -> assertFalse(html.contains("https://")));
  }

  @Test
  void liveUiDoesNotReferenceSyntheticDecisionFixture() {
    assertFalse(resource("webui/app.js").contains("fixtures.json"));
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

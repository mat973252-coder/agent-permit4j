package io.github.agentpermit4j.playground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaygroundLiveApiAcceptanceTest {

  private final HttpClient client = HttpClient.newHttpClient();
  private PlaygroundHttpServer server;

  @BeforeEach
  void startServer() throws Exception {
    server = PlaygroundHttpServer.start(0);
  }

  @AfterEach
  void stopServer() {
    server.close();
  }

  @Test
  void servesUiAndRunsOnlyServerDefinedDecisions() throws Exception {
    var index = get("/");
    var cases = get("/api/playground/cases");
    var firstRead = post("/api/playground/decisions/sql-read");
    var retriedRead = post("/api/playground/decisions/sql-read");
    var denied = post("/api/playground/decisions/http-ssrf");

    assertEquals("127.0.0.1", server.baseUri().getHost());
    assertEquals(200, index.statusCode());
    assertTrue(index.body().contains("AgentPermit4j"));
    assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent());
    assertEquals(200, cases.statusCode());
    assertTrue(cases.body().contains("messaging-claimed"));
    assertTrue(cases.body().contains("sql-read"));
    assertTrue(cases.body().contains("http-ssrf"));
    assertDecision(firstRead, "EXECUTED", "SQL_READ_ONLY", 1);
    assertDecision(retriedRead, "EXECUTED", "SQL_READ_ONLY", 1);
    assertDecision(denied, "DENIED", "HTTP_SSRF_TARGET", 0);
    assertEquals(404, post("/api/playground/decisions/unknown").statusCode());
    assertEquals(405, get("/api/playground/decisions/sql-read").statusCode());
  }

  @Test
  void approvesExactInvocationAndRetriesWithoutDuplicateSideEffect() throws Exception {
    var pending = post("/api/playground/decisions/messaging-claimed");

    assertDecision(pending, "APPROVAL_REQUIRED", "MESSAGING_SEND", 0);
    var approvalRequestId = field(pending.body(), "approvalRequestId");
    assertFalse(approvalRequestId.isBlank());

    var approvals =
        IntStream.range(0, 8)
            .mapToObj(
                ignored ->
                    client.sendAsync(
                        postRequest("/api/playground/approvals/" + approvalRequestId),
                        HttpResponse.BodyHandlers.ofString()))
            .toList();
    var retriedDecision = post("/api/playground/decisions/messaging-claimed");

    for (var approval : approvals) {
      assertDecision(approval.join(), "EXECUTED", "MESSAGING_SEND", 1);
    }
    assertDecision(retriedDecision, "EXECUTED", "MESSAGING_SEND", 1);
    assertEquals(404, post("/api/playground/approvals/not-found").statusCode());
  }

  @Test
  void auditAndReplayAreReadOnlyAndExcludeExecutionSecrets() throws Exception {
    var decision = post("/api/playground/decisions/messaging-claimed");
    var approvalRequestId = field(decision.body(), "approvalRequestId");
    var approved = post("/api/playground/approvals/" + approvalRequestId);
    var timelineId = field(approved.body(), "timelineId");

    var audit = get("/api/playground/audit/" + timelineId);
    var replay = get("/api/playground/replay/" + timelineId);
    var retried = post("/api/playground/decisions/messaging-claimed");

    assertEquals(200, audit.statusCode());
    assertTrue(audit.body().contains("\"stage\":\"EXECUTION\""));
    assertFalse(audit.body().contains("already approved"));
    assertFalse(audit.body().contains("approvalRequestId"));
    assertFalse(audit.body().contains("idempotencyKey"));
    assertFalse(audit.body().contains("fingerprint"));
    assertEquals(200, replay.statusCode());
    assertTrue(replay.body().contains("\"safe\":true"));
    assertTrue(replay.body().contains("\"executed\":false"));
    assertDecision(retried, "EXECUTED", "MESSAGING_SEND", 1);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(resolve(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return client.send(
        postRequest(path),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest postRequest(String path) {
    return HttpRequest.newBuilder(resolve(path))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
  }

  private URI resolve(String path) {
    return server.baseUri().resolve(path);
  }

  private static void assertDecision(
      HttpResponse<String> response, String outcome, String reasonCode, int sideEffects) {
    assertEquals(200, response.statusCode());
    assertEquals(outcome, field(response.body(), "outcome"));
    assertEquals(reasonCode, field(response.body(), "reasonCode"));
    assertTrue(response.body().contains("\"sideEffectCount\":" + sideEffects));
  }

  private static String field(String json, String name) {
    var matcher =
        Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .matcher(json);
    assertTrue(matcher.find(), () -> "missing " + name + " in " + json);
    return matcher.group(1);
  }
}

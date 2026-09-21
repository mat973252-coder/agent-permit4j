package io.github.agentpermit4j.playground;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.ai.util.JsonHelper;

final class PlaygroundHttpServer implements AutoCloseable {

  private static final String API_PREFIX = "/api/playground/";

  private final HttpServer server;
  private final ExecutorService executor;
  private final URI baseUri;

  private PlaygroundHttpServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
    baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  static PlaygroundHttpServer start(int port) throws IOException {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be between 0 and 65535");
    }
    var server =
        HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
    var executor = Executors.newFixedThreadPool(4);
    var runtime = new PlaygroundLiveRuntime();
    server.setExecutor(executor);
    server.createContext("/", exchange -> handle(exchange, runtime));
    server.start();
    return new PlaygroundHttpServer(server, executor);
  }

  URI baseUri() {
    return baseUri;
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private static void handle(HttpExchange exchange, PlaygroundLiveRuntime runtime) {
    try {
      securityHeaders(exchange);
      var path = exchange.getRequestURI().getPath();
      if (serveResource(exchange, path)) {
        return;
      }
      if (!path.startsWith(API_PREFIX)) {
        sendError(exchange, 404, "PLAYGROUND_ROUTE_NOT_FOUND");
        return;
      }
      routeApi(exchange, runtime, path.substring(API_PREFIX.length()));
    } catch (RuntimeException | IOException exception) {
      sendErrorSafely(exchange, 500, "PLAYGROUND_INTERNAL_ERROR");
    } finally {
      exchange.close();
    }
  }

  private static void routeApi(
      HttpExchange exchange, PlaygroundLiveRuntime runtime, String route) throws IOException {
    if (route.equals("cases")) {
      if (!method(exchange, "GET")) {
        return;
      }
      sendJson(exchange, 200, runtime.cases());
      return;
    }
    if (route.startsWith("decisions/")) {
      routeValue(
          exchange,
          "POST",
          () -> runtime.run(value(route, "decisions/")));
      return;
    }
    if (route.startsWith("approvals/")) {
      routeValue(
          exchange,
          "POST",
          () -> runtime.approve(value(route, "approvals/")));
      return;
    }
    if (route.startsWith("audit/")) {
      routeValue(exchange, "GET", () -> runtime.audit(value(route, "audit/")));
      return;
    }
    if (route.startsWith("replay/")) {
      routeValue(exchange, "GET", () -> runtime.replay(value(route, "replay/")));
      return;
    }
    sendError(exchange, 404, "PLAYGROUND_ROUTE_NOT_FOUND");
  }

  private static void routeValue(
      HttpExchange exchange,
      String expectedMethod,
      Supplier<Map<String, Object>> valueSupplier)
      throws IOException {
    if (!method(exchange, expectedMethod)) {
      return;
    }
    var value = valueSupplier.get();
    if (value == null) {
      sendError(exchange, 404, "PLAYGROUND_RESOURCE_NOT_FOUND");
      return;
    }
    sendJson(exchange, 200, value);
  }

  private static boolean method(HttpExchange exchange, String expected) throws IOException {
    if (expected.equals(exchange.getRequestMethod())) {
      return true;
    }
    exchange.getResponseHeaders().set("Allow", expected);
    sendError(exchange, 405, "PLAYGROUND_METHOD_NOT_ALLOWED");
    return false;
  }

  private static String value(String route, String prefix) {
    var value = route.substring(prefix.length());
    return value.isBlank() || value.contains("/") ? "" : value;
  }

  private static boolean serveResource(HttpExchange exchange, String path) throws IOException {
    var resource =
        switch (path) {
          case "/", "/index.html" -> "webui/index.html";
          case "/app.js" -> "webui/app.js";
          case "/styles.css" -> "webui/styles.css";
          default -> null;
        };
    if (resource == null) {
      return false;
    }
    if (!method(exchange, "GET")) {
      return true;
    }
    try (var stream = PlaygroundHttpServer.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        sendError(exchange, 404, "PLAYGROUND_RESOURCE_NOT_FOUND");
        return true;
      }
      var contentType = resource.endsWith(".js") ? "text/javascript" : contentType(resource);
      send(exchange, 200, contentType, stream.readAllBytes());
      return true;
    }
  }

  private static String contentType(String resource) {
    return resource.endsWith(".css") ? "text/css" : "text/html";
  }

  private static void sendJson(HttpExchange exchange, int status, Object body)
      throws IOException {
    var json = new JsonHelper().toJson(body).getBytes(StandardCharsets.UTF_8);
    send(exchange, status, "application/json", json);
  }

  private static void sendError(HttpExchange exchange, int status, String reasonCode)
      throws IOException {
    sendJson(exchange, status, PlaygroundViewFactory.error(reasonCode));
  }

  private static void sendErrorSafely(
      HttpExchange exchange, int status, String reasonCode) {
    try {
      sendError(exchange, status, reasonCode);
    } catch (IOException ignored) {
      // The client disconnected; no error details are exposed.
    }
  }

  private static void send(
      HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static void securityHeaders(HttpExchange exchange) {
    var headers = exchange.getResponseHeaders();
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
  }
}

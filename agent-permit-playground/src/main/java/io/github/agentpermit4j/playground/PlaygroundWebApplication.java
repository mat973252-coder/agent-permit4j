package io.github.agentpermit4j.playground;

import java.util.concurrent.CountDownLatch;

public final class PlaygroundWebApplication {

  private PlaygroundWebApplication() {}

  public static void main(String[] args) throws Exception {
    var port = Integer.parseInt(System.getProperty("agentpermit.playground.port", "8088"));
    try (var server = PlaygroundHttpServer.start(port)) {
      System.out.println("AgentPermit4j Playground: " + server.baseUri());
      new CountDownLatch(1).await();
    }
  }
}

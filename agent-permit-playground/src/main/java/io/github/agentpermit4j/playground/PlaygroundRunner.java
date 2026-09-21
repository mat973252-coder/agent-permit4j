package io.github.agentpermit4j.playground;

import java.util.List;

public final class PlaygroundRunner {

  public List<ScenarioReport> run() {
    return List.of(
        new FileScenario().run(),
        new SqlScenario().run(),
        new HttpScenario().run(),
        new MessagingScenario().run(),
        new DeploymentScenario().run());
  }
}

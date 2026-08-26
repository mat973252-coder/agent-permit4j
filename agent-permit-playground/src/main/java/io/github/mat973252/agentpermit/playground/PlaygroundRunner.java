package io.github.mat973252.agentpermit.playground;

import java.util.List;

public final class PlaygroundRunner {

  public List<ScenarioReport> run() {
    return List.of(new FileScenario().run(), new SqlScenario().run(), new DeploymentScenario().run());
  }
}

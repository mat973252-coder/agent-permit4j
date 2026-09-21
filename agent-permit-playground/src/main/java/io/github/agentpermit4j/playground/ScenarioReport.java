package io.github.agentpermit4j.playground;

import java.util.List;
import java.util.Objects;

public record ScenarioReport(String name, List<CaseReport> cases) {

  public ScenarioReport {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
  }
}

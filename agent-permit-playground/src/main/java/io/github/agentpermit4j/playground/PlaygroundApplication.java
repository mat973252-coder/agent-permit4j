package io.github.agentpermit4j.playground;

import java.io.PrintStream;
import java.util.Objects;
import java.util.stream.Collectors;

public final class PlaygroundApplication {

  private PlaygroundApplication() {}

  public static void main(String[] args) {
    var exitCode = run(System.out);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(PrintStream output) {
    Objects.requireNonNull(output, "output");
    for (var scenario : new PlaygroundRunner().run()) {
      output.println("SCENARIO " + scenario.name());
      for (var result : scenario.cases()) {
        output.printf(
            "  CASE %s outcome=%s reason=%s sideEffects=%d timeline=%s%n",
            result.name(),
            result.decision().outcome(),
            result.decision().reasonCode(),
            result.sideEffectCount(),
            result.timeline().stream().map(Enum::name).collect(Collectors.joining(">")));
      }
    }
    return 0;
  }
}
